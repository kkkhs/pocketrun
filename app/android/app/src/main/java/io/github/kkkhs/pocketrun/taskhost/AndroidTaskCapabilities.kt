package io.github.kkkhs.pocketrun.taskhost

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.kkkhs.pocketrun.LinuxDirectoryLease
import io.github.kkkhs.pocketrun.LinuxRecoveryProbe
import io.github.kkkhs.pocketrun.LinuxRuntimeManifest
import io.github.kkkhs.pocketrun.LinuxSession
import io.github.kkkhs.pocketrun.RootfsInstaller
import io.github.kkkhs.pocketrun.WorkspaceSession
import io.github.kkkhs.pocketrun.AtomicSamplePublisher
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AndroidHostReadiness(
    val packageUid: Int,
    val linuxReady: Boolean,
    val rootfsGeneration: String?,
    val displayReady: Boolean,
    val displayEpoch: Long?,
    val displayId: Int?,
)

/** Actual Android/Linux adapters. No result is synthesized when a source capability is absent. */
internal class AndroidTaskCapabilities(
    private val context: Context,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketRun-TaskHost")
    },
) : TaskCapabilities, Closeable {
    private val lock = Any()
    private val packageUid = Process.myUid()
    private val files = context.filesDir.canonicalFile
    private val workspace = File(files, "workspaces/demo")
    private var linuxSession: LinuxSession? = null
    private var linuxTarget: LinuxTarget? = null
    private var linuxPreparing = false
    private var activeLinux: LinuxCountExecution? = null
    private val workspaceSession = WorkspaceSession(files)
    private var activeWorkspace: WorkspaceExecution? = null
    private var sequence = 0L
    private val closeCoordinator = RetryableCloseCoordinator()

    fun prepareLinuxEnvironment(): LinuxTarget {
        synchronized(lock) {
            check(!closeCoordinator.closing) { "TaskHost adapter is closing or disposed." }
            linuxTarget?.let { return it }
            check(!linuxPreparing && activeLinux == null) { "A Linux preparation or count already owns the adapter." }
            linuxPreparing = true
        }
        var candidate: LinuxSession? = null
        try {
            val runtime = LinuxRuntimeManifest.read(context.assets.open("linux/runtime.json"))
            val installer = RootfsInstaller(files,
                File(context.applicationInfo.nativeLibraryDir).canonicalFile,
                runtime, context.assets::open)
            val session = LinuxSession(files, installer, packageUid, Process.myPid(),
                canRemoveRun = LinuxRecoveryProbe::isUnusedByAppProcesses,
                beforeDemoExecution = { directory ->
                    synchronized(lock) { activeLinux?.runDirectory = directory.canonicalFile }
                })
            candidate = session
            val token = synchronized(lock) { "taskhost-prepare-${++sequence}" }
            val info = session.prepare(session.reservePrepare(token))
            val target = LinuxTarget(info.installationGeneration, packageUid,
                File(info.workspaceDirectory).canonicalFile.toPath(),
                verifiedLinuxSession = true, backendAlive = true)
            synchronized(lock) {
                check(!closeCoordinator.closing && linuxSession == null && linuxTarget == null && linuxPreparing) {
                    "TaskHost Linux preparation became stale."
                }
                linuxSession = session
                linuxTarget = target
                linuxPreparing = false
            }
            return target
        } catch (error: Throwable) {
            try {
                candidate?.close()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            } finally {
                synchronized(lock) { linuxPreparing = false }
            }
            throw error
        }
    }

    fun readiness(): AndroidHostReadiness {
        val linux = synchronized(lock) { linuxTarget }
        val display = currentDisplayTarget()
        return AndroidHostReadiness(packageUid, linux?.verifiedLinuxSession == true,
            linux?.rootfsGeneration, display.backendReady,
            display.displayEpoch.takeIf { display.backendReady },
            display.displayId.takeIf { display.backendReady })
    }

    override fun currentDeviceInfoTarget() =
        DeviceInfoTarget(packageUid, available = !closeCoordinator.closing)

    override fun currentLinuxTarget(): LinuxTarget = synchronized(lock) {
        linuxTarget?.copy(backendAlive = !closeCoordinator.closing) ?: LinuxTarget("unprepared", packageUid,
            workspace.toPath().toAbsolutePath().normalize(),
            verifiedLinuxSession = false, backendAlive = !closeCoordinator.closing)
    }

    override fun currentDisplayTarget(): DisplayTarget {
        val unavailable = DisplayTarget(0L, -1, packageUid, backendReady = false)
        if (closeCoordinator.closing) return unavailable
        val source = TaskHostDisplayPortRegistry.current() ?: return unavailable
        return try {
            source.snapshot().takeIf { it.packageUid == packageUid && it.backendReady &&
                it.displayEpoch > 0 && it.displayId > 0 } ?: unavailable
        } catch (_: Exception) {
            unavailable
        }
    }

    override fun prepareDeviceInfo(expected: DeviceInfoTarget): OwnedExecution {
        synchronized(lock) {
            check(!closeCoordinator.closing && expected == DeviceInfoTarget(packageUid)) { "Device-info binding changed." }
        }
        return ImmediateExecution {
            ToolResult(linkedMapOf(
                "androidVersion" to Build.VERSION.RELEASE,
                "apiLevel" to Build.VERSION.SDK_INT.toString(),
                "supportedAbis" to Build.SUPPORTED_ABIS.joinToString(","),
                "packageUid" to packageUid.toString(),
                "packageName" to context.packageName,
            ))
        }
    }

    override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: Path): OwnedExecution = synchronized(lock) {
        check(!closeCoordinator.closing) { "TaskHost adapter is closing or disposed." }
        val current = checkNotNull(linuxTarget) { "LinuxSession is not prepared." }
        check(current == expected) { "Linux rootfs generation or App UID changed." }
        check(CanonicalChildPath.existingRegularFile(current.workspaceRoot,
            CanonicalChildPath.DEMO_RELATIVE_PATH) == canonicalCsv) { "Fixed demo CSV changed." }
        check(activeLinux == null) { "A Linux count still owns the adapter." }
        LinuxCountExecution("taskhost-count-${++sequence}", this).also { activeLinux = it }
    }

    override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction): OwnedExecution {
        val port = TaskHostDisplayPortRegistry.current()
            ?: throw IllegalStateException("App-owned typed-Binder display backend is not ready.")
        check(port.snapshot() == expected && expected.packageUid == packageUid && expected.backendReady) {
            "Typed-Binder display target changed."
        }
        return DisplayExecution(port, expected, action, worker)
    }

    fun prepareWorkspaceEnvironment(): WorkspaceTarget {
        synchronized(lock) { check(!closeCoordinator.closing && activeWorkspace == null) }
        workspaceSession.prepare()
        return currentWorkspaceTarget()
    }

    fun checkWorkspaceRecovery(): DemoCsvReport {
        val root = workspaceSession.demoRoot()
        val children = checkNotNull(root.listFiles()) { "WORKSPACE_RECOVERY_UNKNOWN" }
        check(children.none { it.name.startsWith(".pocketrun-result-") && it.name.endsWith(".tmp") }) {
            "WORKSPACE_RESULT_RECOVERY_REQUIRED"
        }
        return DemoCsvReport.parse(workspaceSession.readDemoCsvBytes())
    }

    override fun currentWorkspaceTarget(): WorkspaceTarget {
        synchronized(lock) { check(!closeCoordinator.closing) }
        val bytes = workspaceSession.readDemoCsvBytes()
        val report = DemoCsvReport.parse(bytes)
        return WorkspaceTarget(packageUid, workspaceSession.demoRoot().toPath(), report.inputDigest)
    }

    override fun currentDemoObservation(expected: DisplayTarget): DemoObservation {
        val port = checkNotNull(TaskHostDisplayPortRegistry.current()) { "Owned Demo backend is unavailable." }
        check(port.snapshot() == expected && expected.packageUid == packageUid)
        return port.observeDemo(expected)
    }

    override fun currentDemoResult(): ByteArray? = workspaceSession.readDemoResultBytes()

    override fun prepareReadDemoCsv(expected: WorkspaceTarget): OwnedExecution = synchronized(lock) {
        check(!closeCoordinator.closing && activeWorkspace == null && currentWorkspaceTarget() == expected)
        WorkspaceExecution(expected, null, {}).also { activeWorkspace = it }
    }

    override fun preparePublishDemoResult(expected: WorkspaceTarget, text: String, guard: () -> Unit): OwnedExecution = synchronized(lock) {
        check(!closeCoordinator.closing && activeWorkspace == null && currentWorkspaceTarget() == expected)
        WorkspaceExecution(expected, text, guard).also { activeWorkspace = it }
    }

    override fun currentWorkspaceRoot(): WorkspaceRootTarget {
        synchronized(lock) { check(!closeCoordinator.closing) }
        return WorkspaceRootTarget(packageUid, workspaceSession.ensureRoot().canonicalFile.toPath())
    }

    fun listWorkspaceFileSummaries(): List<Pair<String, Long>> =
        workspaceSession.listFiles().map { it.relativePath to it.bytes }

    override fun prepareWorkspaceList(expected: WorkspaceRootTarget): OwnedExecution = synchronized(lock) {
        check(!closeCoordinator.closing && currentWorkspaceRoot() == expected)
        GeneralWorkspaceExecution(expected) {
            val files = workspaceSession.listFiles()
            val formatted = if (files.isEmpty()) "(empty)" else files.joinToString("\n") { "${it.relativePath} (${it.bytes} B)" }
            ToolResult(linkedMapOf(
                "source" to "app-private-workspace",
                "count" to files.size.toString(),
                "files" to formatted,
            ))
        }
    }

    override fun prepareWorkspaceRead(expected: WorkspaceRootTarget, relativePath: String): OwnedExecution = synchronized(lock) {
        check(!closeCoordinator.closing && currentWorkspaceRoot() == expected)
        GeneralWorkspaceExecution(expected) {
            val read = workspaceSession.readTextFile(relativePath)
            ToolResult(linkedMapOf(
                "source" to "app-private-workspace",
                "path" to read.relativePath,
                "content" to read.content,
                "bytes" to read.bytes.toString(),
                "sha256" to read.sha256,
            ))
        }
    }

    override fun prepareWorkspaceWrite(expected: WorkspaceRootTarget, relativePath: String, content: String): OwnedExecution = synchronized(lock) {
        check(!closeCoordinator.closing && currentWorkspaceRoot() == expected)
        GeneralWorkspaceExecution(expected) {
            val written = workspaceSession.writeTextFile(relativePath, content)
            ToolResult(linkedMapOf(
                "source" to "app-private-workspace",
                "path" to written.relativePath,
                "bytes" to written.bytes.toString(),
                "sha256" to written.sha256,
                "status" to if (written.created) "created" else "updated",
            ))
        }
    }

    private inner class GeneralWorkspaceExecution(
        private val expected: WorkspaceRootTarget,
        private val run: () -> ToolResult,
    ) : OwnedExecution {
        private val cancelled = AtomicBoolean(false)
        private val started = AtomicBoolean(false)
        private val done = CountDownLatch(1)

        override fun start(completion: (ExecutionOutcome) -> Unit) {
            check(started.compareAndSet(false, true) && !cancelled.get()) { "Workspace execution is stale." }
            try {
                worker.execute {
                    val outcome = try {
                        check(!cancelled.get() && !closeCoordinator.closing && currentWorkspaceRoot() == expected) {
                            "Workspace target changed."
                        }
                        val result = run()
                        check(!cancelled.get() && !closeCoordinator.closing) { "Workspace execution cancelled." }
                        ExecutionOutcome.Success(result)
                    } catch (error: Exception) {
                        ExecutionOutcome.Failure("WORKSPACE_TOOL_ERROR", error.message.orEmpty().take(256))
                    }
                    done.countDown()
                    completion(outcome)
                }
            } catch (error: RejectedExecutionException) {
                done.countDown()
                throw error
            }
        }

        override fun close() {
            cancelled.set(true)
            if (started.get() && !done.await(3_000, TimeUnit.MILLISECONDS)) {
                throw IOException("Workspace IO cleanup remains unproven.")
            }
        }
    }

    override fun prepareCompleteDisplay(expected: DisplayTarget, action: DisplayAction, skipInput: () -> Boolean): OwnedExecution {
        val port = checkNotNull(TaskHostDisplayPortRegistry.current()) { "Owned Demo backend is unavailable." }
        check(port.snapshot() == expected && expected.packageUid == packageUid && expected.backendReady)
        return DisplayExecution(port, expected, action, worker, skipInput)
    }

    private inner class WorkspaceExecution(
        private val expected: WorkspaceTarget,
        private val text: String?,
        private val guard: () -> Unit,
    ) : OwnedExecution {
        private val gate = Any()
        private val done = CountDownLatch(1)
        private var started = false
        private var cancelled = false
        private var temporary: File? = null

        private fun checkCurrent() {
            check(!cancelled && !closeCoordinator.closing && activeWorkspace === this) { "Workspace execution is stale." }
            check(currentWorkspaceTarget() == expected) { "Workspace input changed." }
        }

        override fun start(completion: (ExecutionOutcome) -> Unit) {
            synchronized(gate) { checkCurrent(); check(!started); started = true }
            try {
                worker.execute {
                    val outcome = try {
                        synchronized(gate) { checkCurrent(); guard() }
                        val result = if (text == null) {
                            val bytes = workspaceSession.readDemoCsvBytes()
                            val report = DemoCsvReport.parse(bytes)
                            check(report.inputDigest == expected.inputDigest)
                            ToolResult(mapOf("source" to "app-private-workspace", "csvText" to String(bytes, Charsets.UTF_8),
                                "inputDigest" to report.inputDigest))
                        } else {
                            val publication = workspaceSession.publishDemoResult(text,
                                guard = { synchronized(gate) { checkCurrent(); guard() } },
                                onTemporary = { temporary = it },
                                commit = { source, target -> synchronized(gate) {
                                    checkCurrent(); guard(); AtomicSamplePublisher.publish(source, target)
                                } })
                            ToolResult(mapOf("source" to "app-private-workspace", "inputDigest" to expected.inputDigest,
                                "publicationStatus" to publication.status, "outputDigest" to publication.outputDigest))
                        }
                        synchronized(gate) { checkCurrent() }
                        ExecutionOutcome.Success(result)
                    } catch (_: Exception) { ExecutionOutcome.Failure("WORKSPACE_SOURCE_FAILED", "Workspace state or publication could not be verified.") }
                    done.countDown()
                    completion(outcome)
                }
            } catch (error: RejectedExecutionException) { done.countDown(); throw error }
        }

        override fun close() {
            val wasStarted = synchronized(gate) { cancelled = true; started }
            if (wasStarted && !done.await(3000, TimeUnit.MILLISECONDS)) throw IOException("Workspace IO cleanup remains unproven.")
            temporary?.let { file ->
                val exists = LinuxDirectoryLease.readAttributes(file) != null
                if (exists && !file.delete()) throw IOException("Workspace temporary file remains owned.")
                if (LinuxDirectoryLease.readAttributes(file) != null) throw IOException("Workspace temporary cleanup remains unproven.")
            }
            synchronized(lock) { if (activeWorkspace === this) activeWorkspace = null }
        }
    }

    private fun startLinux(execution: LinuxCountExecution, completion: (ExecutionOutcome) -> Unit) {
        val session: LinuxSession
        val operation: LinuxSession.Operation
        synchronized(lock) {
            check(!closeCoordinator.closing && activeLinux === execution && !execution.started) { "Linux execution is stale." }
            val target = checkNotNull(linuxTarget)
            check(target == currentLinuxTarget()) { "Linux target changed before start." }
            session = checkNotNull(linuxSession)
            operation = session.reserve(execution.token)
            execution.operation = operation
            execution.started = true
        }
        try {
            worker.execute {
                var result: LinuxSession.Result? = null
                val outcome = try {
                    result = session.runDemo(operation)
                    linuxOutcome(checkNotNull(result))
                } catch (error: Throwable) {
                    ExecutionOutcome.Failure("LINUX_SOURCE_ERROR",
                        error.message.orEmpty().take(256))
                }
                execution.result = result
                execution.done.countDown()
                completion(outcome)
            }
        } catch (error: RejectedExecutionException) {
            session.cancel(execution.token)
            execution.done.countDown()
            throw error
        }
    }

    private fun closeLinux(execution: LinuxCountExecution) {
        val session: LinuxSession?
        val started: Boolean
        synchronized(lock) {
            if (activeLinux !== execution) return
            started = execution.started
            session = linuxSession
            if (!started) {
                activeLinux = null
                return
            }
        }
        val ownedSession = checkNotNull(session)
        ownedSession.cancel(execution.token)
        if (!execution.done.await(LINUX_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IOException("Linux execution did not settle before cleanup deadline.")
        }
        val cleanupProven = LinuxCleanupPolicy.retryOriginal(execution.operation,
            ownedSession::retryProcessCleanup) {
            val run = execution.runDirectory
            if (run == null) false else {
                val recovered = if (execution.result?.cleanupStatus == "done") true
                    else LinuxDirectoryLease.recoverOwned(run, LinuxDirectoryLease.Kind.RUN,
                        LinuxRecoveryProbe::isUnusedByAppProcesses)
                recovered && LinuxDirectoryLease.readAttributes(run) == null
            }
        }
        if (!cleanupProven) {
            // RUN-directory absence alone cannot release CommandPipeline's retained process/IO slot.
            throw IOException("Original Linux process/IO or RUN cleanup remains unproven.")
        }
        synchronized(lock) { if (activeLinux === execution) activeLinux = null }
    }

    override fun close() = closeCoordinator.close {
        val active = synchronized(lock) {
            if (linuxPreparing) throw IOException("Linux preparation is still settling.")
            activeLinux
        }
        // A failed close retains active/session/worker and keeps this coordinator retryable.
        active?.close()
        synchronized(lock) { activeWorkspace }?.close()
        workspaceSession.close()
        val session = synchronized(lock) {
            check(activeLinux == null) { "Linux execution cleanup is still owned." }
            linuxSession
        }
        session?.close()
        worker.shutdown()
        synchronized(lock) {
            check(activeLinux == null && !linuxPreparing)
            linuxSession = null
            linuxTarget = null
        }
    }

    private class LinuxCountExecution(
        val token: String,
        private val owner: AndroidTaskCapabilities,
    ) : OwnedExecution {
        val done = CountDownLatch(1)
        @Volatile var result: LinuxSession.Result? = null
        @Volatile var runDirectory: File? = null
        var operation: LinuxSession.Operation? = null
        var started = false

        override fun start(completion: (ExecutionOutcome) -> Unit) = owner.startLinux(this, completion)
        override fun close() = owner.closeLinux(this)
    }

    private class ImmediateExecution(private val source: () -> ToolResult) : OwnedExecution {
        private val closed = AtomicBoolean(false)
        private val started = AtomicBoolean(false)
        override fun start(completion: (ExecutionOutcome) -> Unit) {
            check(!closed.get() && started.compareAndSet(false, true)) { "Immediate execution is stale." }
            completion(try {
                ExecutionOutcome.Success(source())
            } catch (error: Exception) {
                ExecutionOutcome.Failure("DEVICE_INFO_SOURCE_ERROR", error.message.orEmpty().take(256))
            })
        }
        override fun close() { closed.set(true) }
    }

    internal class DisplayExecution(
        private val port: TaskHostDisplayPort,
        private val expected: DisplayTarget,
        private val action: DisplayAction,
        private val worker: ExecutorService,
        private val skipInput: (() -> Boolean)? = null,
    ) : OwnedExecution {
        private val cancelled = AtomicBoolean(false)
        private val started = AtomicBoolean(false)
        private val completedSuccessfully = AtomicBoolean(false)
        private val done = CountDownLatch(1)

        override fun start(completion: (ExecutionOutcome) -> Unit) {
            check(started.compareAndSet(false, true) && !cancelled.get()) { "Display execution is stale." }
            try {
                worker.execute {
                    val outcome = try {
                        check(!cancelled.get() && port.snapshot() == expected) { "Display target changed." }
                        val skip = skipInput?.invoke() == true
                        val result = if (skip && (action is DisplayAction.DemoTap || action is DisplayAction.DemoText)) {
                            ToolResult(OwnedDemoObservation.fields(port.observeDemo(expected)) + ("effect" to "already_present"))
                        } else when (val value = action) {
                            DisplayAction.Observe -> port.observe(expected)
                            DisplayAction.ObserveDemo -> ToolResult(OwnedDemoObservation.fields(port.observeDemo(expected)))
                            is DisplayAction.DemoTap -> port.demoTap(expected, value.x, value.y)
                            is DisplayAction.DemoText -> port.demoText(expected, value.text)
                        }
                        check(!cancelled.get() && port.snapshot() == expected) { "Display result became stale." }
                        ExecutionOutcome.Success(result)
                    } catch (error: Exception) {
                        ExecutionOutcome.Failure("DISPLAY_SOURCE_ERROR", error.message.orEmpty().take(256))
                    }
                    if (outcome is ExecutionOutcome.Success) completedSuccessfully.set(true)
                    done.countDown()
                    completion(outcome)
                }
            } catch (error: RejectedExecutionException) {
                done.countDown()
                throw error
            }
        }

        override fun close() {
            cancelled.set(true)
            if (!started.get()) return
            // A completion callback is published only after the source operation has fully returned.
            // Normal result cleanup owns no pending Binder query and must preserve its epoch/cache URI.
            if (done.count > 0L || !completedSuccessfully.get()) port.invalidate(expected)
            if (!done.await(DISPLAY_CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw IOException("Typed-Binder display operation cleanup is unproven.")
            }
        }
    }

    companion object {
        private const val LINUX_CLOSE_WAIT_MS = 6_000L
        private const val DISPLAY_CLOSE_WAIT_MS = 2_500L

        internal fun linuxOutcome(result: LinuxSession.Result): ExecutionOutcome {
            val fields = linkedMapOf(
                "installationGeneration" to result.installationGeneration,
                "sourceStatus" to result.status,
                "cleanupStatus" to result.cleanupStatus,
                "stdout" to result.stdout,
                "stderr" to result.stderr,
                "exitCodes" to result.exitCodes.joinToString(","),
                "stageExitCodes" to result.stageExitCodes.joinToString(","),
                "durationMillis" to result.durationMillis.toString(),
                "packageUid" to result.appUid.toString(),
                "alpineVersion" to result.alpineVersion.orEmpty(),
                "guestUid" to result.guestUid?.toString().orEmpty(),
                "cwd" to result.cwd.orEmpty(),
                "lineCount" to result.lineCount?.toString().orEmpty(),
            )
            val output = ToolResult(fields)
            val valid = result.status == "completed" && result.cleanupStatus == "done" &&
                result.lineCount != null && result.guestUid == result.appUid && result.cwd == "/workspace" &&
                result.exitCodes.size == 1 && result.exitCodes.all { it == 0 } &&
                result.stageExitCodes.size == 5 && result.stageExitCodes.all { it == 0 }
            return if (valid) ExecutionOutcome.Success(output)
                else ExecutionOutcome.Failure("LINUX_SOURCE_FAILED",
                    "Linux source status=${result.status}, cleanup=${result.cleanupStatus}.", output)
        }
    }
}
