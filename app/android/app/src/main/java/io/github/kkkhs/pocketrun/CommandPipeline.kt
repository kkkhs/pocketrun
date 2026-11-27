package io.github.kkkhs.pocketrun

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Internal process plumbing; commands are selected by WorkspaceSession, never by JavaScript. */
class CommandPipeline : Closeable {
    data class Result(
        val status: String,
        val stdout: String,
        val stderr: String,
        val exitCodes: List<Int>,
        val durationMillis: Long,
        val cleanupProven: Boolean = true,
        val cleanupFailure: String? = null,
    )

    class Run internal constructor() {
        internal val cancelled = AtomicBoolean(false)
        internal val failure = AtomicReference<String?>(null)
        internal val processes = ArrayList<Process>()
        internal val cleanupFailure = AtomicReference<String?>(null)
        @Volatile internal var cleanupProven = false
        internal var terminateOwned: ((Process) -> Unit)? = null
        internal var executing = false // guarded by the pipeline lock.
        internal var executed = false
        @Volatile internal var ioCleanupProven = true
        @Volatile internal var ioExecutor: ExecutorService? = null
        internal val terminationLock = Any()
        internal val streamClosers = ArrayList<Thread>()
        internal val streamsScheduled = HashSet<Process>()
        internal val streamFailure = AtomicReference<String?>(null)
    }

    private val lock = Any()
    private var current: Run? = null
    private var closed = false

    fun reserve(): Run = synchronized(lock) {
        check(!closed) { "命令管道已关闭。" }
        check(current == null) { "已有命令管道正在运行。" }
        Run().also { current = it }
    }

    fun execute(run: Run, directory: File, commands: List<List<String>>, timeoutMillis: Long = TIMEOUT_MS,
        env: Map<String, String> = emptyMap(), clearEnvironment: Boolean = false,
        onStarted: ((Process) -> Unit)? = null, terminateOwned: ((Process) -> Unit)? = null,
        validate: () -> Unit = {}): Result {
        val started = System.nanoTime()
        val stdout = LimitedOutput(MAX_OUTPUT_BYTES)
        val stderr = LimitedOutput(MAX_OUTPUT_BYTES)
        val io = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "PocketRun-PipeIO").apply { isDaemon = true }
        }
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        // Allocate before marking the reservation executing: allocation failure has launched no resources.
        synchronized(lock) {
            check(current === run && !run.executed) { "命令管道已失效或重复执行。" }
            run.terminateOwned = terminateOwned
            run.executing = true
            run.executed = true
            run.ioCleanupProven = false
            run.ioExecutor = io
        }
        try {
            require(timeoutMillis in 1..MAX_SHELL_TIMEOUT_MS) { "管道超时时间无效。" }
            require(directory.isDirectory && commands.size in 1..2) { "管道工作目录或阶段无效。" }
            validate()
            commands.forEach { require(it.isNotEmpty() && it.size <= 96 && it.all { value -> value.isNotEmpty() && 0.toChar() !in value }) }
            require(env.size <= 16 && env.all { (key, value) ->
                key.matches(Regex("[A-Z_][A-Z0-9_]{0,63}")) && value.length <= 4096 && 0.toChar() !in value
            }) { "命令环境无效。" }
            for (command in commands) {
                val process = synchronized(lock) {
                    check(current === run && !closed) { "命令管道已失效。" }
                    if (run.cancelled.get()) throw CancellationException()
                    if (System.nanoTime() >= deadline) throw TimeoutException()
                    ProcessBuilder(command).directory(directory).apply {
                        environment().apply {
                            if (clearEnvironment) clear()
                            putAll(env)
                        }
                    }.start().also { run.processes += it }
                }
                onStarted?.invoke(process)
            }
            run.processes.first().outputStream.close()
            val readers = run.processes.map { process ->
                io.submit {
                    readStream(run, process.errorStream, stderr)
                }
            }.toMutableList()
            if (run.processes.size == 2) {
                readers += io.submit {
                    try {
                        run.processes[1].outputStream.use { destination ->
                            copyLimited(run.processes[0].inputStream, destination)
                        }
                    } catch (error: Exception) {
                        if (!run.cancelled.get()) fail(run, if (error is OutputLimitExceeded) "output_limit" else "failed")
                    }
                }
            }
            readers += io.submit { readStream(run, run.processes.last().inputStream, stdout) }
            for (process in run.processes) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw TimeoutException()
            }
            for (reader in readers) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw TimeoutException()
                reader.get(remaining, TimeUnit.NANOSECONDS)
            }
        } catch (_: CancellationException) {
            run.cancelled.set(true)
        } catch (_: TimeoutException) {
            fail(run, "timeout")
        } catch (error: Exception) {
            if (!run.cancelled.get()) {
                run.failure.compareAndSet(null, "failed")
                stderr.append(error.message.orEmpty().take(256).toByteArray(Charsets.UTF_8))
            }
        } finally {
            terminate(run)
            io.shutdownNow()
            try {
                run.ioCleanupProven = io.awaitTermination(1, TimeUnit.SECONDS)
                if (!run.ioCleanupProven) run.cleanupFailure.updateAndGet {
                    listOfNotNull(it, "cleanup_unproven: pipe IO threads did not terminate.").joinToString("; ")
                }
                else if (run.cleanupProven) run.cleanupFailure.set(null)
            } catch (_: InterruptedException) {
                run.cleanupFailure.updateAndGet {
                    listOfNotNull(it, "cleanup_unproven: pipe IO cleanup interrupted.").joinToString("; ")
                }
                Thread.currentThread().interrupt()
            } finally {
                synchronized(lock) {
                    run.executing = false
                    if (current === run && run.cleanupProven && run.ioCleanupProven) current = null
                }
            }
        }
        val exits = run.processes.map { if (it.isAlive) -1 else it.exitValue() }
        val outputText = stdout.text()
        val errorText = stderr.text()
        if (stdout.textTruncated || stderr.textTruncated) run.failure.compareAndSet(null, "output_limit")
        val status = when {
            run.cancelled.get() -> "cancelled"
            run.failure.get() != null -> checkNotNull(run.failure.get())
            !run.cleanupProven || !run.ioCleanupProven -> "failed"
            exits.size != commands.size || exits.any { it != 0 } -> "failed"
            else -> "completed"
        }
        return Result(status, outputText, errorText, exits,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            run.cleanupProven && run.ioCleanupProven, run.cleanupFailure.get())
    }

    internal fun abandon(run: Run) {
        terminate(run)
        val retryIO = synchronized(lock) { !run.executing && !run.ioCleanupProven }
        if (retryIO) {
            try {
                run.ioCleanupProven = run.ioExecutor?.awaitTermination(1, TimeUnit.SECONDS) == true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                run.cleanupFailure.updateAndGet {
                    listOfNotNull(it, "cleanup_unproven: pipe IO retry interrupted.").joinToString("; ")
                }
            }
        }
        synchronized(lock) {
            if (current === run && !run.executing && run.cleanupProven && run.ioCleanupProven) {
                run.cleanupFailure.set(null)
                current = null
            }
        }
    }

    fun cancel(): Boolean {
        val run = synchronized(lock) {
            current?.also { it.cancelled.set(true) }
        } ?: return false
        terminate(run)
        return true
    }

    internal fun runningProcesses(): List<Process> = synchronized(lock) {
        current?.processes?.toList().orEmpty()
    }

    override fun close() {
        val run = synchronized(lock) {
            closed = true
            current?.also { it.cancelled.set(true) }
        }
        if (run != null) terminate(run)
    }

    private fun readStream(run: Run, source: InputStream, output: LimitedOutput) {
        try {
            source.use {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (!output.append(buffer, count)) {
                        fail(run, "output_limit")
                        break
                    }
                }
            }
        } catch (_: Exception) {
            if (!run.cancelled.get() && run.failure.get() == null) fail(run, "failed")
        }
    }

    private fun copyLimited(source: InputStream, destination: OutputStream) {
        source.use {
            val buffer = ByteArray(4096)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_OUTPUT_BYTES) throw OutputLimitExceeded()
                destination.write(buffer, 0, count)
            }
        }
    }

    private fun fail(run: Run, status: String) {
        run.failure.compareAndSet(null, status)
        terminate(run)
    }

    private fun terminate(run: Run) = synchronized(run.terminationLock) {
        val processes = synchronized(lock) { run.processes.toList() }
        val failures = ArrayList<String>()
        processes.forEach { process ->
            try {
                val owned = run.terminateOwned
                if (owned != null) owned(process)
                else {
                    if (process.isAlive) process.destroyForcibly()
                    if (!process.waitFor(500, TimeUnit.MILLISECONDS)) throw IllegalStateException("Java child not reaped.")
                }
                if (process.isAlive) throw IllegalStateException("Owned child still alive.")
            } catch (error: Exception) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                failures += error.message ?: error.javaClass.simpleName
            }
            // close() itself can block behind a reader. Bound it, track it, and never declare that thread reclaimed early.
            if (!process.isAlive && run.streamsScheduled.add(process)) {
                listOf(process.outputStream, process.inputStream, process.errorStream).forEach { stream ->
                    val closer = Thread({
                        try { stream.close() }
                        catch (error: Exception) { run.streamFailure.compareAndSet(null, error.message ?: error.javaClass.simpleName) }
                    }, "PocketRun-PipeClose").apply { isDaemon = true }
                    run.streamClosers += closer
                    closer.start()
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
        run.streamClosers.forEach { closer ->
            try {
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) TimeUnit.NANOSECONDS.timedJoin(closer, remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                failures += "Stream cleanup interrupted."
            }
        }
        if (run.streamClosers.any { it.isAlive }) failures += "Stream close threads did not terminate."
        run.streamFailure.get()?.let { failures += "Stream close failed: $it" }
        run.cleanupProven = failures.isEmpty() && processes.none { it.isAlive }
        if (failures.isNotEmpty()) run.cleanupFailure.set("cleanup_unproven: " + failures.joinToString("; "))
        else if (run.ioCleanupProven) run.cleanupFailure.set(null)
    }

    private class LimitedOutput(private val limit: Int) {
        private val bytes = ByteArrayOutputStream()
        var textTruncated = false
            private set
        @Synchronized fun append(source: ByteArray, count: Int = source.size): Boolean {
            val remaining = limit - bytes.size()
            bytes.write(source, 0, minOf(remaining, count))
            return count <= remaining
        }
        @Synchronized fun text(): String {
            val encoded = bytes.toString(Charsets.UTF_8.name()).toByteArray(Charsets.UTF_8)
            if (encoded.size <= limit) return String(encoded, Charsets.UTF_8)
            textTruncated = true
            var end = limit
            while (end > 0 && (encoded[end].toInt() and 0xc0) == 0x80) end--
            return String(encoded, 0, end, Charsets.UTF_8)
        }
    }

    private class OutputLimitExceeded : Exception()

    companion object {
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val TIMEOUT_MS = 5000L
        const val MAX_SHELL_TIMEOUT_MS = 15_000L
    }
}
