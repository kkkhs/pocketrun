package io.github.kkkhs.pocketrun

import android.os.Build
import android.os.Process
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ReactModule(name = PtyModule.NAME)
class PtyModule private constructor(
    private val context: ReactApplicationContext,
    private val timeoutMillis: Long,
) : NativePocketRunPtySpec(context) {
    constructor(context: ReactApplicationContext) : this(context, DEFAULT_TIMEOUT_MS)
    internal constructor(context: ReactApplicationContext, timeoutMillis: Long,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit) : this(context, timeoutMillis) {
        require(timeoutMillis in 1..DEFAULT_TIMEOUT_MS)
    }
    private class Active(
        val token: String,
        val epoch: Long,
        initialColumns: Int,
        initialRows: Int,
        val promise: Promise,
    ) {
        var columns = initialColumns
        var rows = initialRows
        var ready = false
        val cleanupLock = Any()
        val writing = AtomicBoolean(false)
        val output = StringBuilder()
        var outputBytes = 0
        var session: PtySession? = null
        var operation: PtySession.Operation? = null
        var lease: LinuxDirectoryLease? = null
        var runsDirectory: File? = null
        var runDirectory: File? = null
        var leaseReleased = false
        var cancelRequested = false
        var timedOut = false
        @Volatile var timeoutFuture: ScheduledFuture<*>? = null
        var running = true
        var cleanupPending = false
        var promiseSettled = false
        val startedNanos = System.nanoTime()

        fun append(text: String) = synchronized(output) {
            outputBytes = PtyModule.appendBounded(output, outputBytes, text)
        }
        fun outputText() = synchronized(output) { output.toString() }
    }

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "PocketRun-PTY") }
    private val controls = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "PocketRun-PTY-Control") }
    private val deadline: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "PocketRun-PTY-Deadline")
    }
    private var active: Active? = null
    private var epoch = 0L
    private var closed = false

    override fun getName() = NAME

    override fun startPty(token: String, columns: Double, rows: Double, promise: Promise) {
        val dimensions = try { validateStart(token, columns, rows) }
        catch (error: Exception) { promise.reject("E_PTY_ARGUMENT", error.message, error); return }
        val owned = synchronized(lock) {
            when {
                closed -> { promise.reject("E_PTY_CLOSED", "PTY 模块已关闭。"); null }
                active != null -> { promise.reject("E_PTY_BUSY", "已有 PTY 会话或待确认清理资源。"); null }
                !supportsRuntime(Build.VERSION.SDK_INT, Process.is64Bit(), Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) -> {
                    promise.reject("E_PTY_UNSUPPORTED", "交互终端需要 Android 14 / API 34 及 ARM64 App 进程。")
                    null
                }
                else -> Active(token, ++epoch, dimensions.first, dimensions.second, promise).also { active = it }
            }
        } ?: return
        try { owned.timeoutFuture = scheduleTimeout(deadline, timeoutMillis) { expire(owned) } }
        catch (error: RejectedExecutionException) {
            synchronized(lock) { if (active === owned) active = null }
            promise.reject("E_PTY_CLOSED", "PTY 模块已关闭。", error)
            return
        }
        try { worker.execute { run(owned) } }
        catch (error: RejectedExecutionException) {
            owned.timeoutFuture?.cancel(false)
            synchronized(lock) { if (active === owned) active = null }
            promise.reject("E_PTY_CLOSED", "PTY 模块已关闭。", error)
        }
    }

    override fun writePty(token: String, text: String, control: String, promise: Promise) {
        val action = try { interaction(text, control) }
        catch (error: Exception) { promise.reject("E_PTY_ARGUMENT", error.message, error); return }
        val owned = synchronized(lock) {
            active?.takeIf { it.token == token && it.running && !it.cancelRequested && !it.cleanupPending }
        }
        if (owned == null) { promise.reject("E_PTY_STALE", "PTY token 无效、已退出或正在清理。"); return }
        if (!owned.writing.compareAndSet(false, true)) {
            promise.reject("E_PTY_WRITE_BUSY", "上一笔 PTY 输入仍在发送。"); return
        }
        try {
            controls.execute {
                try {
                    val session: PtySession
                    val operation: PtySession.Operation
                    synchronized(lock) {
                        if (active !== owned || owned.cancelRequested || !owned.running) throw CancellationException("PTY 输入已失效。")
                        session = owned.session ?: throw IllegalStateException("PTY 尚在准备 Linux 环境。")
                        operation = owned.operation ?: throw IllegalStateException("PTY 尚未取得原生句柄。")
                    }
                    if (action.first != null) session.write(operation, action.first!!)
                    else session.sendControl(operation, checkNotNull(action.second))
                    promise.resolve(true)
                } catch (error: Exception) {
                    promise.reject(if (error is IllegalStateException) "E_PTY_STARTING" else "E_PTY_WRITE", error.message, error)
                } finally { owned.writing.set(false) }
            }
        } catch (error: RejectedExecutionException) {
            owned.writing.set(false)
            promise.reject("E_PTY_CLOSED", "PTY 模块已关闭。", error)
        }
    }

    override fun getPtyOutput(token: String, promise: Promise) {
        val owned = synchronized(lock) { active?.takeIf { it.token == token } }
        if (owned == null) { promise.reject("E_PTY_STALE", "PTY token 无效或会话已结束。"); return }
        val snapshot = Arguments.createMap().apply {
            putString("token", owned.token)
            putString("output", owned.outputText())
            synchronized(lock) {
                putBoolean("ready", owned.ready && owned.running && !owned.cancelRequested)
                putInt("columns", owned.columns)
                putInt("rows", owned.rows)
                putBoolean("cleanupPending", owned.cleanupPending)
            }
        }
        promise.resolve(snapshot)
    }

    override fun resizePty(token: String, columns: Double, rows: Double, promise: Promise) {
        val dimensions = try { validateDimensions(columns, rows) }
        catch (error: Exception) { promise.reject("E_PTY_ARGUMENT", error.message, error); return }
        val owned = synchronized(lock) {
            active?.takeIf { it.token == token && it.running && it.ready && !it.cancelRequested && !it.cleanupPending }
        }
        if (owned == null) { promise.reject("E_PTY_STALE", "PTY token 无效、尚未 ready 或正在清理。"); return }
        if (!owned.writing.compareAndSet(false, true)) {
            promise.reject("E_PTY_WRITE_BUSY", "上一笔 PTY I/O 仍在执行。"); return
        }
        try {
            controls.execute {
                try {
                    val session: PtySession
                    val operation: PtySession.Operation
                    synchronized(lock) {
                        if (active !== owned || owned.cancelRequested || !owned.running || !owned.ready) {
                            throw CancellationException("PTY resize 已失效。")
                        }
                        session = checkNotNull(owned.session)
                        operation = checkNotNull(owned.operation)
                    }
                    session.resize(operation, dimensions.first, dimensions.second)
                    synchronized(lock) {
                        if (active !== owned || owned.cancelRequested || !owned.running) throw CancellationException("PTY resize 已失效。")
                        owned.columns = dimensions.first
                        owned.rows = dimensions.second
                    }
                    promise.resolve(true)
                } catch (error: Exception) {
                    promise.reject("E_PTY_RESIZE", error.message, error)
                } finally { owned.writing.set(false) }
            }
        } catch (error: RejectedExecutionException) {
            owned.writing.set(false)
            promise.reject("E_PTY_CLOSED", "PTY 模块已关闭。", error)
        }
    }

    override fun closePty(token: String, promise: Promise) {
        val owned = synchronized(lock) {
            val value = active
            if (value == null || value.token != token) null else value.also { it.cancelRequested = true }
        }
        if (owned == null) { promise.resolve(false); return }
        try {
            controls.execute {
                if (synchronized(lock) { owned.running }) {
                    try {
                        val accepted = synchronized(lock) {
                            val session = owned.session
                            val operation = owned.operation
                            if (session == null || operation == null) true else session.cancel(token)
                        }
                        promise.resolve(accepted)
                    } catch (error: Exception) {
                        synchronized(lock) { owned.cleanupPending = true }
                        promise.reject("E_PTY_CLEANUP_UNPROVEN", cleanupFailureMessage(error), error)
                    }
                    return@execute
                }
                try {
                    if (!retryCleanup(owned)) {
                        promise.reject("E_PTY_CLEANUP_UNPROVEN", "cleanup_unproven: PTY 或 RUN 目录仍需同 token 重试。")
                        return@execute
                    }
                    synchronized(lock) { if (active === owned) active = null }
                    promise.resolve(true)
                } catch (error: Exception) {
                    promise.reject("E_PTY_CLEANUP_UNPROVEN", cleanupFailureMessage(error), error)
                }
            }
        } catch (error: RejectedExecutionException) {
            promise.reject("E_PTY_CLOSED", "PTY 模块已关闭。", error)
        }
    }

    private fun run(owned: Active) {
        var exit: PtyExit? = null
        var failure: Throwable? = null
        var cancelled = false
        try {
            checkCurrent(owned)
            val files = context.filesDir.canonicalFile
            val runtime = LinuxRuntimeManifest.read(context.assets.open("linux/runtime.json"))
            val installer = RootfsInstaller(files, File(context.applicationInfo.nativeLibraryDir).canonicalFile,
                runtime, context.assets::open)
            val info = LinuxSession(files, installer, Process.myUid(), Process.myPid(),
                canRemoveRun = LinuxRecoveryProbe::isUnusedByAppProcesses).use { linux ->
                linux.prepare(linux.reservePrepare("${owned.token}:prepare"))
            }
            checkCurrent(owned)
            val runs = File(files, "linux/runs")
            val lease = LinuxDirectoryLease.allocate(runs, LinuxDirectoryLease.Kind.RUN,
                LinuxRecoveryProbe::isUnusedByAppProcesses, guard = { checkCurrent(owned) })
            // Transfer ownership immediately: no tmp/path/check can fail before finally knows this lease.
            synchronized(lock) {
                owned.lease = lease
                owned.runsDirectory = runs
                owned.runDirectory = lease.directory
            }
            val temporary = File(lease.directory, "tmp").apply { RootfsTar.privateDirectory(this) }
            checkCurrent(owned)
            val config = PtyTrustedConfig.create(files, installer.nativeDirectory,
                File(info.rootfsDirectory), File(info.workspaceDirectory), lease.directory,
                Process.myUid(), Process.myPid())
            require(config.temporaryDirectory == temporary.canonicalFile)
            val terminal = PtySession()
            synchronized(lock) { owned.session = terminal }
            val operation = terminal.open(owned.token, config, owned::append)
            synchronized(lock) { owned.operation = operation }
            terminal.resize(operation, owned.columns, owned.rows)
            synchronized(lock) {
                if (active !== owned || owned.cancelRequested) throw CancellationException("PTY 已取消。")
                owned.ready = true
            }
            if (synchronized(lock) { owned.cancelRequested }) {
                terminal.cancel(owned.token)
                throw CancellationException("PTY 已取消。")
            }
            exit = terminal.waitForExit(operation)
        } catch (error: Throwable) {
            failure = error
            cancelled = error is CancellationException || synchronized(lock) { owned.cancelRequested }
        }
        owned.timeoutFuture?.cancel(false)

        val cleanupDone = try { retryCleanup(owned) }
        catch (cleanup: Throwable) {
            if (failure != null) failure.addSuppressed(cleanup) else failure = cleanup
            false
        }
        synchronized(lock) {
            owned.running = false
            owned.ready = false
            owned.cleanupPending = !cleanupDone
            if (cleanupDone && active === owned) active = null
        }

        if (failure != null && !cancelled) {
            settleReject(owned, if (cleanupDone) "E_PTY_OPERATION" else "E_PTY_CLEANUP_UNPROVEN", failure)
            return
        }
        val status = classifyStatus(synchronized(lock) { owned.timedOut }, cancelled, exit)
        settleResolve(owned, resultMap(owned, status, exit, if (cleanupDone) "done" else "unproven"))
    }

    private fun retryCleanup(owned: Active): Boolean = synchronized(owned.cleanupLock) {
        val terminal = synchronized(lock) { owned.session }
        if (terminal != null) {
            try { terminal.close() }
            catch (_: IOException) { return false }
        }
        val lease = synchronized(lock) { owned.lease }
        if (lease != null && !owned.leaseReleased) {
            try { lease.close() } finally { owned.leaseReleased = true }
            if (lease.cleanupProven) return true
        }
        val directory = synchronized(lock) { owned.runDirectory } ?: return true
        if (isGone(directory)) return true
        LinuxDirectoryLease.recoverOwned(directory, LinuxDirectoryLease.Kind.RUN,
            LinuxRecoveryProbe::isUnusedByAppProcesses) && isGone(directory)
    }

    private fun expire(owned: Active) {
        val terminal = synchronized(lock) {
            if (closed || active !== owned || !owned.running || owned.promiseSettled) return
            owned.timedOut = true
            owned.cancelRequested = true
            owned.ready = false
            owned.session
        }
        try { terminal?.cancel(owned.token) }
        catch (_: Exception) { synchronized(lock) { owned.cleanupPending = true } }
    }

    private fun checkCurrent(owned: Active) = synchronized(lock) {
        if (closed || active !== owned || owned.epoch != epoch || owned.cancelRequested) {
            throw CancellationException("PTY 操作已取消或失效。")
        }
    }

    private fun settleResolve(owned: Active, value: WritableMap) {
        val settle = synchronized(lock) { if (owned.promiseSettled) false else { owned.promiseSettled = true; true } }
        if (settle) owned.promise.resolve(value)
    }

    private fun settleReject(owned: Active, code: String, error: Throwable) {
        val settle = synchronized(lock) { if (owned.promiseSettled) false else { owned.promiseSettled = true; true } }
        if (settle) owned.promise.reject(code,
            if (code == "E_PTY_CLEANUP_UNPROVEN") cleanupFailureMessage(error) else error.message, error)
    }

    private fun resultMap(owned: Active, status: String, exit: PtyExit?, cleanup: String): WritableMap =
        Arguments.createMap().apply {
            putString("token", owned.token)
            putString("status", status)
            putString("cleanupStatus", cleanup)
            putString("output", owned.outputText())
            putString("exitKind", exit?.kind?.name?.lowercase())
            if (exit == null) putNull("exitCode") else putInt("exitCode", exit.code)
            if (exit == null) putNull("childPid") else putInt("childPid", exit.childPid)
            putBoolean("rootReaped", exit?.rootReaped == true)
            putInt("columns", owned.columns)
            putInt("rows", owned.rows)
            putInt("appUid", Process.myUid())
            putDouble("durationMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - owned.startedNanos).toDouble())
        }

    override fun invalidate() {
        val owned = synchronized(lock) {
            if (closed) null else { closed = true; epoch++; active?.also { it.cancelRequested = true } }
        }
        owned?.timeoutFuture?.cancel(false)
        try { owned?.session?.close() } catch (_: Exception) { /* Retain lease/marker for conservative recovery. */ }
        worker.shutdown()
        controls.shutdownNow()
        deadline.shutdownNow()
        super.invalidate()
    }

    companion object {
        const val NAME = "PocketRunPty"
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val DEFAULT_TIMEOUT_MS = 120_000L
        private val TOKEN = Regex("[a-zA-Z0-9_.:-]{1,64}")
        private val CONTROLS = mapOf(
            "interrupt" to PtySession.Control.INTERRUPT,
            "eof" to PtySession.Control.END_OF_INPUT,
            "form-feed" to PtySession.Control.FORM_FEED,
            "suspend" to PtySession.Control.SUSPEND,
            "quit" to PtySession.Control.QUIT,
        )

        internal fun supportsRuntime(sdk: Int, app64Bit: Boolean, arm64: Boolean) = sdk >= 34 && app64Bit && arm64

        internal fun validateStart(token: String, columns: Double, rows: Double): Pair<Int, Int> {
            require(TOKEN.matches(token)) { "PTY token 无效。" }
            return validateDimensions(columns, rows)
        }

        internal fun validateDimensions(columns: Double, rows: Double): Pair<Int, Int> {
            require(columns.isFinite() && rows.isFinite() && columns == columns.toInt().toDouble() && rows == rows.toInt().toDouble()) {
                "PTY 尺寸必须是整数。"
            }
            val width = columns.toInt()
            val height = rows.toInt()
            require(width in PtySession.MIN_CELLS..PtySession.MAX_CELLS && height in PtySession.MIN_CELLS..PtySession.MAX_CELLS) {
                "PTY 尺寸必须在 16..400。"
            }
            return width to height
        }

        internal fun scheduleTimeout(scheduler: ScheduledExecutorService, timeoutMillis: Long, action: () -> Unit): ScheduledFuture<*> {
            require(timeoutMillis in 1..DEFAULT_TIMEOUT_MS)
            return scheduler.schedule(action, timeoutMillis, TimeUnit.MILLISECONDS)
        }

        internal fun isGone(directory: File, inspect: (File) -> Unit = { file ->
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            Unit
        }): Boolean = try {
            inspect(directory)
            false
        } catch (_: NoSuchFileException) { true }

        internal fun cleanupFailureMessage(error: Throwable): String {
            val message = error.message ?: error.javaClass.simpleName
            return if (message.startsWith("cleanup_unproven:")) message else "cleanup_unproven: $message"
        }

        internal fun classifyStatus(timedOut: Boolean, cancelled: Boolean, exit: PtyExit?): String = when {
            timedOut -> "timed_out"
            cancelled -> "cancelled"
            exit?.kind == PtyExit.Kind.EXITED -> "exited"
            exit?.kind == PtyExit.Kind.SIGNALED -> "signaled"
            else -> "failed"
        }

        internal fun interaction(text: String, control: String): Pair<ByteArray?, PtySession.Control?> {
            require((text.isNotEmpty()) xor control.isNotEmpty()) { "每次只能发送文本或一个控制字符。" }
            if (text.isNotEmpty()) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                require(bytes.size in 1..PtySession.MAX_INPUT_BYTES) { "PTY 文本 UTF-8 输入上限为 4 KiB。" }
                return bytes to null
            }
            return null to requireNotNull(CONTROLS[control]) { "PTY 控制字符不在固定白名单。" }
        }

        internal fun appendBounded(output: StringBuilder, currentBytes: Int, text: String): Int {
            if (text.isEmpty() || currentBytes >= MAX_OUTPUT_BYTES) return currentBytes
            val remaining = MAX_OUTPUT_BYTES - currentBytes
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= remaining) { output.append(text); return currentBytes + bytes.size }
            var used = 0
            var index = 0
            while (index < text.length) {
                val codePoint = Character.codePointAt(text, index)
                val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
                if (used + encoded > remaining) break
                output.appendCodePoint(codePoint)
                used += encoded
                index += Character.charCount(codePoint)
            }
            return currentBytes + used
        }
    }
}
