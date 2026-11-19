package io.github.kkkhs.pocketrun.taskhost

import android.content.Context
import android.os.Build
import android.os.Process
import io.github.kkkhs.pocketrun.LinuxDirectoryLease
import io.github.kkkhs.pocketrun.LinuxRecoveryProbe
import io.github.kkkhs.pocketrun.LinuxRuntimeManifest
import io.github.kkkhs.pocketrun.LinuxSession
import io.github.kkkhs.pocketrun.RootfsInstaller
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

/**
 * The display side is intentionally empty until the App's own typed-Binder controller installs
 * a port. A host ADB connection or Dora transport is never a substitute for this App-owned port.
 */
internal interface TaskHostDisplayPort {
    fun snapshot(): DisplayTarget
    fun observe(expected: DisplayTarget): ToolResult
    fun demoTap(expected: DisplayTarget, x: Int, y: Int): ToolResult
    fun demoText(expected: DisplayTarget, text: String): ToolResult
    fun invalidate(expected: DisplayTarget)
}

internal object TaskHostDisplayPortRegistry {
    private val lock = Any()
    private var port: TaskHostDisplayPort? = null

    fun install(value: TaskHostDisplayPort): Closeable = synchronized(lock) {
        check(port == null) { "TaskHost display port is already installed." }
        port = value
        Closeable { synchronized(lock) { if (port === value) port = null } }
    }

    fun current(): TaskHostDisplayPort? = synchronized(lock) { port }
}

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
                        val result = when (val value = action) {
                            DisplayAction.Observe -> port.observe(expected)
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
