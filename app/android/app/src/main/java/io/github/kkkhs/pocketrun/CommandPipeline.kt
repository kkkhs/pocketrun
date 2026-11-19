package io.github.kkkhs.pocketrun

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
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
    )

    class Run internal constructor() {
        internal val cancelled = AtomicBoolean(false)
        internal val failure = AtomicReference<String?>(null)
        internal val processes = ArrayList<Process>()
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
        validate: () -> Unit = {}): Result {
        val started = System.nanoTime()
        val stdout = LimitedOutput(MAX_OUTPUT_BYTES)
        val stderr = LimitedOutput(MAX_OUTPUT_BYTES)
        val io = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "PocketRun-PipeIO").apply { isDaemon = true }
        }
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            require(timeoutMillis in 1..TIMEOUT_MS) { "管道超时时间无效。" }
            require(directory.isDirectory && commands.size in 1..2) { "管道工作目录或阶段无效。" }
            validate()
            commands.forEach { require(it.isNotEmpty() && it.size <= 8 && it.all(String::isNotEmpty)) }
            for (command in commands) {
                synchronized(lock) {
                    check(current === run && !closed) { "命令管道已失效。" }
                    if (run.cancelled.get()) throw CancellationException()
                    if (System.nanoTime() >= deadline) throw TimeoutException()
                    run.processes += ProcessBuilder(command).directory(directory).start()
                }
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
                io.awaitTermination(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                synchronized(lock) { if (current === run) current = null }
            }
        }
        val exits = run.processes.map { if (it.isAlive) -1 else it.exitValue() }
        val outputText = stdout.text()
        val errorText = stderr.text()
        if (stdout.textTruncated || stderr.textTruncated) run.failure.compareAndSet(null, "output_limit")
        val status = when {
            run.cancelled.get() -> "cancelled"
            run.failure.get() != null -> checkNotNull(run.failure.get())
            exits.size != commands.size || exits.any { it != 0 } -> "failed"
            else -> "completed"
        }
        return Result(status, outputText, errorText, exits,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
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

    private fun terminate(run: Run) {
        val processes = synchronized(lock) { run.processes.toList() }
        processes.forEach { process ->
            runCatching { process.destroyForcibly() }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }
        }
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
    }
}
