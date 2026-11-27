package io.github.kkkhs.pocketrun

import java.io.Closeable
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed Alpine demo, in the App UID. PRoot is compatibility, not a root/security boundary. */
internal class LinuxSession(
    private val filesDirectory: File,
    private val installer: RootfsInstaller,
    private val appUid: Int,
    private val parentPid: Int,
    private val canRemoveRun: (File, Long) -> Boolean = { _, _ -> false },
    private val beforeDemoExecution: (File) -> Unit = {}, // internal failure-injection hook, never exposed to JS.
) : Closeable {
    data class Info(val token: String, val installationGeneration: String, val alpineVersion: String,
        val appUid: Int, val rootfsDirectory: String, val workspaceDirectory: String, val guestCwd: String,
        val installedNow: Boolean, val sampleCreated: Boolean)
    data class Result(val token: String, val installationGeneration: String, val status: String,
        val stdout: String, val stderr: String, val exitCodes: List<Int>, val stageExitCodes: List<Int>,
        val durationMillis: Long, val appUid: Int, val alpineVersion: String?, val guestUid: Int?,
        val cwd: String?, val lineCount: Int?, val cleanupStatus: String = "unproven")
    data class ShellResult(
        val token: String,
        val installationGeneration: String,
        val status: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMillis: Long,
        val appUid: Int,
        val cleanupStatus: String = "unproven",
    )
    class Operation internal constructor(
        val token: String,
        internal val run: CommandPipeline.Run?,
        internal val owner: LinuxSession,
        timeoutMillis: Long = CommandPipeline.TIMEOUT_MS,
    ) {
        internal val cancelled = AtomicBoolean(false)
        internal var finished = false
        internal var settled = false // guarded by the session lock; cancellation has a definite commit point.
        internal val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    }
    private val lock = Any()
    private val pipeline = CommandPipeline()
    private var current: Operation? = null
    private var closed = false
    private var installation: RootfsInstaller.Installation? = null
    private val workspace = File(filesDirectory, "workspaces/demo")

    fun reservePrepare(token: String): Operation = reserve(token, false, CommandPipeline.TIMEOUT_MS)
    fun reserve(token: String, timeoutMillis: Long = CommandPipeline.TIMEOUT_MS): Operation = reserve(token, true, timeoutMillis)
    private fun reserve(token: String, command: Boolean, timeoutMillis: Long): Operation = synchronized(lock) {
        require(token.matches(Regex("[a-zA-Z0-9_.:-]{1,96}"))) { "Linux correlation token 无效。" }
        require(timeoutMillis in 1..CommandPipeline.MAX_SHELL_TIMEOUT_MS) { "Linux 超时设置无效。" }
        check(!closed) { "Linux 会话已关闭。" }
        check(current == null) { "已有 Linux 操作正在运行。" }
        Operation(token, if (command) pipeline.reserve() else null, this, timeoutMillis).also { current = it }
    }

    fun prepare(operation: Operation): Info {
        try {
            guard(operation)
            // Same chapter-6 preparation contract, preserving existing CSV/workspace data.
            val sample = WorkspaceSession(filesDirectory).use { it.prepare() }
            val next = installer.prepare({ guard(operation) }) { action ->
                synchronized(lock) { guard(operation); action() }
            }
            LinuxDirectoryLease.recover(File(filesDirectory, "linux/runs"), LinuxDirectoryLease.Kind.RUN, canRemoveRun,
                guard = { guard(operation) })
            return synchronized(lock) {
                guard(operation)
                installation = next
                operation.settled = true
                Info(operation.token, next.generation, RootfsInstaller.ALPINE_VERSION, appUid,
                    next.root.path, workspace.path, "/workspace", next.created, sample.sampleCreated)
            }
        } finally { finish(operation) }
    }

    fun runDemo(operation: Operation): Result {
        val started = System.nanoTime()
        var owned: LinuxDirectoryLease? = null
        var allocationAttempted = false
        var pipelineCleanupProven = true // No child exists until execute is attempted.
        var reservationReleased = false
        var cleanupAttempted = false
        var primaryFailure: Throwable? = null
        try {
            val outcome = try {
                guard(operation)
                val root = synchronized(lock) { checkNotNull(installation) { "请先准备 Linux 环境。" }.root }
                installer.verifyArtifacts { guard(operation) }
                installer.validateInstalled { guard(operation) }
                validateWorkspace()
                val runs = File(filesDirectory, "linux/runs")
                allocationAttempted = true
                owned = LinuxDirectoryLease.allocate(runs, LinuxDirectoryLease.Kind.RUN,
                    unused = { directory, budget -> pipelineCleanupProven && canRemoveRun(directory, budget) },
                    guard = { guard(operation) })
                val tmp = File(owned.directory, "tmp")
                RootfsTar.privateDirectory(tmp)
                beforeDemoExecution(owned.directory)
                // Registration observes an already-started child; it is not a pre-guest approval barrier.
                val controller = LinuxProcessControl(owned.directory, File(installer.nativeDirectory, "libproot.so"))
                val argv = demoArgv(root, workspace, tmp, installer.nativeDirectory, installer.runtime.bindings)
                val env = demoEnvironment(installer.nativeDirectory, tmp, parentPid, controller.tracerRecord)
                val remaining = TimeUnit.NANOSECONDS.toMillis(operation.deadline - System.nanoTime())
                if (remaining <= 0) throw TimeoutException()
                pipelineCleanupProven = false
                val result = pipeline.execute(checkNotNull(operation.run), workspace, listOf(argv),
                    timeoutMillis = remaining.coerceAtMost(CommandPipeline.TIMEOUT_MS), env = env,
                    clearEnvironment = true, onStarted = controller::registerOwned,
                    terminateOwned = controller::terminateOwned) { guard(operation); validateWorkspace() }
                pipelineCleanupProven = result.cleanupProven
                val parsed = parseDemo(result.stdout)
                val valid = parsed != null && parsed.version == RootfsInstaller.ALPINE_VERSION &&
                    parsed.uid == appUid && parsed.cwd == "/workspace" && parsed.exits.all { it == 0 }
                val status = if (result.status == "completed" && !valid) "failed" else result.status
                Result(operation.token, installer.runtime.generation, status, result.stdout,
                    result.stderr, result.exitCodes, parsed?.exits.orEmpty(), elapsed(started), appUid,
                    parsed?.version, parsed?.uid, parsed?.cwd, parsed?.count)
            } catch (error: Exception) {
                val status = when (error) {
                    is CancellationException -> "cancelled"
                    is TimeoutException -> "timeout"
                    else -> "failed"
                }
                Result(operation.token, installer.runtime.generation, status, "",
                    error.message.orEmpty().take(256), emptyList(), emptyList(), elapsed(started), appUid,
                    null, null, null, null)
            }
            val originalRun = checkNotNull(operation.run)
            pipeline.abandon(originalRun)
            reservationReleased = true
            cleanupAttempted = true
            val actualProcessCleanup = originalRun.cleanupProven && originalRun.ioCleanupProven
            val cleaned = if (allocationAttempted && owned == null) outcome.copy(cleanupStatus = "unproven")
                else cleanupResult(outcome, owned, pipelineCleanupProven && actualProcessCleanup)
            return settle(operation, cleaned.copy(durationMillis = elapsed(started)))
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                if (!reservationReleased) pipeline.abandon(checkNotNull(operation.run))
            } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else { primaryFailure = cleanup; throw cleanup }
            } finally {
                try {
                    if (!cleanupAttempted) owned?.close()
                } catch (cleanup: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
                } finally { finish(operation) }
            }
        }
    }

    fun runShell(operation: Operation, command: String, timeoutMillis: Long = 10_000L): ShellResult {
        val started = System.nanoTime()
        var owned: LinuxDirectoryLease? = null
        var allocationAttempted = false
        var pipelineCleanupProven = true
        var reservationReleased = false
        var cleanupAttempted = false
        var primaryFailure: Throwable? = null
        try {
            val outcome = try {
                guard(operation)
                require(command.isNotBlank() && command.toByteArray(Charsets.UTF_8).size <= MAX_SHELL_COMMAND_BYTES &&
                    !command.contains('\u0000')) { "Shell 命令无效或超过上限。" }
                require(timeoutMillis in 1_000L..CommandPipeline.MAX_SHELL_TIMEOUT_MS) { "Shell 超时必须在 1..15 秒内。" }
                val root = synchronized(lock) { checkNotNull(installation) { "请先准备 Linux 环境。" }.root }
                installer.verifyArtifacts { guard(operation) }
                installer.validateInstalled { guard(operation) }
                require(workspace.isDirectory && workspace.canonicalFile == workspace.absoluteFile) { "私有工作目录无效。" }
                val runs = File(filesDirectory, "linux/runs")
                allocationAttempted = true
                owned = LinuxDirectoryLease.allocate(runs, LinuxDirectoryLease.Kind.RUN,
                    unused = { directory, budget -> pipelineCleanupProven && canRemoveRun(directory, budget) },
                    guard = { guard(operation) })
                val tmp = File(owned.directory, "tmp")
                RootfsTar.privateDirectory(tmp)
                beforeDemoExecution(owned.directory)
                val controller = LinuxProcessControl(owned.directory, File(installer.nativeDirectory, "libproot.so"))
                val argv = shellArgv(root, workspace, tmp, installer.nativeDirectory, installer.runtime.bindings, command)
                val env = demoEnvironment(installer.nativeDirectory, tmp, parentPid, controller.tracerRecord)
                val remaining = TimeUnit.NANOSECONDS.toMillis(operation.deadline - System.nanoTime())
                if (remaining <= 0) throw TimeoutException()
                pipelineCleanupProven = false
                val result = pipeline.execute(
                    checkNotNull(operation.run), workspace, listOf(argv),
                    timeoutMillis = remaining.coerceAtMost(timeoutMillis), env = env,
                    clearEnvironment = true, onStarted = controller::registerOwned,
                    terminateOwned = controller::terminateOwned,
                ) { guard(operation) }
                pipelineCleanupProven = result.cleanupProven
                val code = result.exitCodes.firstOrNull() ?: -1
                ShellResult(
                    token = operation.token,
                    installationGeneration = installer.runtime.generation,
                    status = result.status,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    exitCode = code,
                    durationMillis = elapsed(started),
                    appUid = appUid,
                )
            } catch (error: Exception) {
                val status = when (error) {
                    is CancellationException -> "cancelled"
                    is TimeoutException -> "timeout"
                    else -> "failed"
                }
                ShellResult(operation.token, installer.runtime.generation, status, "",
                    error.message.orEmpty().take(256), -1, elapsed(started), appUid)
            }
            val originalRun = checkNotNull(operation.run)
            pipeline.abandon(originalRun)
            reservationReleased = true
            cleanupAttempted = true
            val actualProcessCleanup = originalRun.cleanupProven && originalRun.ioCleanupProven
            val proven = if (allocationAttempted && owned == null) false else try {
                owned?.close()
                pipelineCleanupProven && actualProcessCleanup && (owned == null || owned.cleanupProven)
            } catch (_: Exception) { false }
            val cleaned = outcome.copy(cleanupStatus = if (proven) "done" else "unproven", durationMillis = elapsed(started))
            return synchronized(lock) {
                operation.settled = true
                if (operation.cancelled.get() || closed) cleaned.copy(status = "cancelled") else cleaned
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                if (!reservationReleased) pipeline.abandon(checkNotNull(operation.run))
            } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else { primaryFailure = cleanup; throw cleanup }
            } finally {
                try {
                    if (!cleanupAttempted) owned?.close()
                } catch (cleanup: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
                } finally { finish(operation) }
            }
        }
    }

    fun cancel(token: String): Boolean = synchronized(lock) {
        val operation = current ?: return false
        if (operation.token != token || closed || operation.settled) return false
        operation.cancelled.set(true)
        if (operation.run != null) pipeline.cancel()
        true
    }

    override fun close() {
        synchronized(lock) { closed = true; current?.cancelled?.set(true) }
        pipeline.close()
    }

    private fun guard(operation: Operation) = synchronized(lock) {
        if (operation.cancelled.get() || closed) throw CancellationException("Linux 操作已取消或关闭。")
        check(current === operation) { "Linux 操作已失效。" }
        if (System.nanoTime() >= operation.deadline) throw TimeoutException("Linux 操作超过 5 秒。")
    }
    private fun settle(operation: Operation, result: Result): Result = synchronized(lock) {
        operation.settled = true
        if (operation.cancelled.get() || closed) result.copy(status = "cancelled") else result
    }
    private fun finish(operation: Operation) = synchronized(lock) {
        operation.finished = true
        val run = operation.run
        if (current === operation && (run == null || (run.cleanupProven && run.ioCleanupProven))) current = null
    }

    /** Re-prove only this session's original finished run; directory recovery is a separate caller gate. */
    internal fun retryProcessCleanup(operation: Operation): Boolean = synchronized(lock) {
        if (operation.owner !== this || !operation.finished || (current != null && current !== operation)) return false
        val run = operation.run ?: return false
        try { pipeline.abandon(run) } catch (_: Exception) { return false }
        val proven = run.cleanupProven && run.ioCleanupProven
        if (proven && current === operation) current = null
        proven
    }
    private fun validateWorkspace() {
        val sample = File(workspace, "items.csv")
        require(workspace.isDirectory && workspace.canonicalFile == workspace.absoluteFile &&
            sample.isFile && sample.canonicalFile == sample.absoluteFile && sample.length() <= CommandPipeline.MAX_OUTPUT_BYTES) { "私有演示工作目录无效。" }
    }

    companion object {
        internal fun cleanupResult(result: Result, owned: LinuxDirectoryLease?, pipelineCleanupProven: Boolean = true): Result {
            val proven = try {
                owned?.close()
                owned == null || owned.cleanupProven
            } catch (_: Exception) { false }
            // Preserve command output/exits even when cleanup could not be established.
            return result.copy(cleanupStatus = if (pipelineCleanupProven && proven) "done" else "unproven")
        }

        internal const val DEMO_SCRIPT = "cat /etc/alpine-release; v=\$?; pwd; p=\$?; id -u; u=\$?; " +
            "(cat items.csv; c=\$?; printf '%s\\n' \"\$c\" > /tmp/pocketrun-cat.exit; exit \"\$c\") | wc -l; w=\$?; " +
            "c=\$(cat /tmp/pocketrun-cat.exit) || exit 125; " +
            "printf 'PR_EXITS:%s,%s,%s,%s,%s\\n' \"\$v\" \"\$p\" \"\$u\" \"\$c\" \"\$w\"; " +
            "[ \"\$v,\$p,\$u,\$c,\$w\" = '0,0,0,0,0' ]"
        internal const val MAX_SHELL_COMMAND_BYTES = 1024
        internal fun shellArgv(
            root: File, workspace: File, tmp: File, native: File,
            bindings: List<LinuxBinding>, command: String,
        ): List<String> =
            listOf(File(native, "libproot.so").path, "--kill-on-exit", "-r", root.path, "-w", "/workspace") +
                bindings.flatMap { listOf("-b", "${File(native, it.name).path}:${it.guestPath}") } +
                listOf("-b", "${workspace.path}:/workspace", "-b", "${tmp.path}:/tmp", "-b", "/proc:/proc",
                    "-b", "/dev/null:/dev/null", "/bin/busybox", "sh", "-c", command)
        internal fun demoArgv(root: File, workspace: File, tmp: File, native: File, bindings: List<LinuxBinding>): List<String> =
            listOf(File(native, "libproot.so").path, "--kill-on-exit", "-r", root.path, "-w", "/workspace") +
                bindings.flatMap { listOf("-b", "${File(native, it.name).path}:${it.guestPath}") } +
                listOf("-b", "${workspace.path}:/workspace", "-b", "${tmp.path}:/tmp", "-b", "/proc:/proc",
                    "-b", "/dev/null:/dev/null", "/bin/busybox", "sh", "-c", DEMO_SCRIPT)
        internal fun demoEnvironment(native: File, tmp: File, parentPid: Int,
            tracerRecord: File = File(checkNotNull(tmp.parentFile), "tracer.identity")): Map<String, String> {
            require(tracerRecord.absoluteFile == File(checkNotNull(tmp.parentFile), "tracer.identity").absoluteFile)
            return mapOf(
                "PROOT_LOADER" to File(native, "libproot_loader.so").path,
                "POCKETRUN_EXPECTED_PARENT_PID" to parentPid.toString(),
                "POCKETRUN_TRACER_RECORD" to tracerRecord.path,
                "PROOT_TMP_DIR" to tmp.path, "HOME" to "/root", "PATH" to "/bin:/usr/bin", "LANG" to "C",
            )
        }
        internal data class Demo(val version: String, val cwd: String, val uid: Int, val count: Int, val exits: List<Int>)
        internal fun parseDemo(stdout: String): Demo? {
            val lines = stdout.trimEnd('\n').split('\n')
            if (lines.size != 5 || !lines[4].startsWith("PR_EXITS:")) return null
            val exits = lines[4].removePrefix("PR_EXITS:").split(',').map { it.toIntOrNull() ?: return null }
            if (exits.size != 5 || exits.any { it !in 0..255 }) return null
            val uid = lines[2].trim().toIntOrNull() ?: return null
            val count = lines[3].trim().toIntOrNull() ?: return null
            if (uid < 0 || count < 0) return null
            return Demo(lines[0], lines[1], uid, count, exits)
        }
        private fun elapsed(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }
}
