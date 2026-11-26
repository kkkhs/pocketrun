package io.github.kkkhs.pocketrun.taskhost

import java.io.Closeable
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

fun interface MonotonicClock {
    fun nowNanos(): Long
}

fun interface NonceSource {
    fun nextNonce(): String
}

enum class AppUiDecision { APPROVE, REJECT }

enum class ApprovalState {
    NOT_REQUIRED,
    PENDING_APP_UI,
    APPROVED_BY_APP_UI,
    REJECTED_BY_APP_UI,
    CANCELLED_BY_APP_UI,
    EXPIRED,
    INVALIDATED,
}

enum class CleanupState { NONE, PENDING, PROVEN, UNPROVEN }

enum class HostPhase {
    IDLE,
    PENDING_APPROVAL,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    REJECTED,
    CANCELLED,
    EXPIRED,
    INVALIDATED,
    INVALID_INPUT,
    CLOSED,
}

data class HostStatus(
    val phase: HostPhase,
    val tool: String? = null,
    val requestNonce: String? = null,
    val requestHash: String? = null,
    val backendEpoch: String? = null,
    val target: String? = null,
    val approvalActor: String? = null,
    val approvalState: ApprovalState? = null,
    val cleanupState: CleanupState = CleanupState.NONE,
    val deadlineNanos: Long? = null,
    val code: String? = null,
    val message: String? = null,
    val result: ToolResult? = null,
)

class HostBusyException : IllegalStateException("A PocketRun task or unproven cleanup still owns the slot.")

/**
 * One deterministic task owner. It stores at most one request, entirely in memory.
 * There is deliberately no generic agent loop, model client, persistence, or auto-approve API.
 */
class TaskHost private constructor(
    private val capabilities: TaskCapabilities,
    private val clock: MonotonicClock,
    private val nonceSource: NonceSource,
    approvalTtlMillis: Long,
    executionTimeoutMillis: Long,
    private val beforeOwnedStart: () -> Unit,
) : Closeable {
    constructor(
        capabilities: TaskCapabilities,
        clock: MonotonicClock = MonotonicClock(System::nanoTime),
        nonceSource: NonceSource = SecureNonceSource(),
        approvalTtlMillis: Long = DEFAULT_APPROVAL_TTL_MILLIS,
        executionTimeoutMillis: Long = DEFAULT_EXECUTION_TIMEOUT_MILLIS,
    ) : this(capabilities, clock, nonceSource, approvalTtlMillis, executionTimeoutMillis, {})
    private val lock = Any()
    private val approvalTtlNanos = checkedDuration(approvalTtlMillis)
    private val executionTimeoutNanos = checkedDuration(executionTimeoutMillis)
    private var status = HostStatus(HostPhase.IDLE)
    private var active: ActiveRequest? = null
    private var requestSequence = 0L
    private var executionSequence = 0L
    private var closed = false
    private var complete: CompleteTaskContract? = null

    internal fun installCompleteContract(value: CompleteTaskContract?) = synchronized(lock) {
        check(!closed && active == null) { "TaskHost still owns a request." }
        if (value == null) check(complete?.blocksOrdinaryTask() != true) { "Interrupted complete task requires review." }
        value?.start()
        complete = value
    }

    fun submit(call: RawToolCall, taskDeadlineNanos: Long? = null): HostStatus {
        refresh()
        var launch: Launch? = null
        try {
            synchronized(lock) {
                check(!closed) { "TaskHost is closed." }
                if (active != null) throw HostBusyException()
                if (complete?.blocksOrdinaryTask() == false) complete = null
                val action = ToolSchema.validate(call)
                val needsComplete = action == ParsedAction.ReadDemoCsv || action is ParsedAction.PublishDemoResult || action is ParsedAction.Display.ObserveDemo
                if (needsComplete && complete == null) throw ToolValidationException("COMPLETE_TASK_REQUIRED", "This action requires the native complete-task contract.")
                try { complete?.validate(action) }
                catch (_: IllegalArgumentException) { throw ToolValidationException("COMPLETE_TASK_PREREQUISITE", "Complete-task prerequisites are not satisfied.") }
                val binding = bind(action)
                requestSequence = Math.addExact(requestSequence, 1L)
                val sourceNonce = nonceSource.nextNonce()
                if (!sourceNonce.matches(NONCE_PATTERN)) {
                    throw ToolValidationException("INVALID_NONCE_SOURCE", "Approval nonce source returned an invalid value.")
                }
                // Sequence makes a repeated injected nonce unable to authorize a later request.
                val nonce = "${requestSequence}_$sourceNonce"
                val requestHash = requestHash(nonce, action, binding)
                val request = ActiveRequest(nonce, requestHash, action, binding, taskDeadlineNanos)
                active = request
                if (action.approvalRequired) {
                    request.approvalDeadlineNanos = boundedDeadline(request, approvalTtlNanos)
                    status = view(request, HostPhase.PENDING_APPROVAL,
                        deadline = request.approvalDeadlineNanos, actor = APP_PHONE_UI_ACTOR)
                } else {
                    launch = beginExecutionLocked(request)
                }
            }
        } catch (error: ToolValidationException) {
            synchronized(lock) {
                if (active == null) {
                    status = HostStatus(HostPhase.INVALID_INPUT, code = error.code,
                        message = bounded(error.message))
                }
            }
            throw error
        }
        launch?.let(::launch)
        return state()
    }

    /** The only approval entry point; callers cannot supply an actor or automate approval. */
    fun onAppUiDecision(requestNonce: String, decision: AppUiDecision): Boolean {
        refresh()
        var launch: Launch? = null
        synchronized(lock) {
            val request = active ?: return false
            if (status.phase != HostPhase.PENDING_APPROVAL || request.nonce != requestNonce ||
                request.approvalConsumed) return false
            if (expireApprovalLocked(request)) return true
            request.approvalConsumed = true
            request.approvalState = if (decision == AppUiDecision.REJECT)
                ApprovalState.REJECTED_BY_APP_UI else ApprovalState.APPROVED_BY_APP_UI
            if (decision == AppUiDecision.REJECT) {
                request.terminal = Terminal(HostPhase.REJECTED, "USER_REJECTED",
                    "The App phone UI rejected this request.")
                active = null
                status = terminalView(request, checkNotNull(request.terminal), CleanupState.NONE)
            } else {
                launch = beginExecutionLocked(request)
            }
        }
        launch?.let(::launch)
        return true
    }

    fun cancelFromAppUi(requestNonce: String): Boolean {
        refresh()
        val cleanup: CleanupWork?
        synchronized(lock) {
            val request = active ?: return false
            if (request.nonce != requestNonce || request.terminal != null) return false
            request.approvalConsumed = true
            request.approvalState = ApprovalState.CANCELLED_BY_APP_UI
            cleanup = terminateLocked(request, Terminal(HostPhase.CANCELLED, "USER_CANCELLED",
                "The App phone UI cancelled this request."))
        }
        cleanup?.let(::cleanupAndCommit)
        return true
    }

    /** A death callback must carry both the current request nonce and bound backend epoch. */
    fun onBackendDeath(requestNonce: String, backendEpoch: String): Boolean {
        val cleanup: CleanupWork?
        synchronized(lock) {
            val request = active ?: return false
            if (request.nonce != requestNonce || request.backendEpoch != backendEpoch ||
                request.terminal != null) return false
            request.approvalConsumed = true
            request.approvalState = ApprovalState.INVALIDATED
            cleanup = terminateLocked(request, Terminal(HostPhase.INVALIDATED, "BACKEND_DEATH",
                "The bound capability backend died."))
        }
        cleanup?.let(::cleanupAndCommit)
        return true
    }

    /** Retry is mandatory after a close failure; a new task stays blocked until it succeeds. */
    fun retryCleanup(): Boolean {
        val cleanup = synchronized(lock) {
            val request = active ?: return true
            val owner = request.owner ?: return false
            if (status.cleanupState != CleanupState.UNPROVEN || request.terminal == null) return false
            CleanupWork(request, owner, checkNotNull(request.terminal))
        }
        cleanupAndCommit(cleanup)
        return synchronized(lock) { active !== cleanup.request }
    }

    internal fun nowNanos(): Long = clock.nowNanos()

    fun state(): HostStatus {
        refresh()
        return synchronized(lock) { status }
    }

    /** Explicit monotonic-clock maintenance point, suitable for an Android lifecycle timer. */
    fun refresh() {
        val cleanup: CleanupWork?
        synchronized(lock) {
            val request = active
            val now = clock.nowNanos()
            cleanup = when {
                request == null || request.terminal != null -> null
                status.phase == HostPhase.PENDING_APPROVAL && isDue(now, request.approvalDeadlineNanos) -> {
                    request.approvalConsumed = true
                    request.approvalState = ApprovalState.EXPIRED
                    terminateLocked(request, Terminal(HostPhase.EXPIRED, "APPROVAL_EXPIRED",
                        "Approval expired before an App UI decision."))
                }
                status.phase == HostPhase.EXECUTING && isDue(now, request.executionDeadlineNanos) -> {
                    request.approvalState = ApprovalState.EXPIRED
                    terminateLocked(request, Terminal(HostPhase.EXPIRED, "EXECUTION_TIMEOUT",
                        "Tool execution exceeded its monotonic deadline."))
                }
                else -> null
            }
        }
        cleanup?.let(::cleanupAndCommit)
    }

    override fun close() {
        val cleanup: CleanupWork?
        synchronized(lock) {
            val request = active
            if (closed && request == null) return
            closed = true
            cleanup = if (request == null) {
                status = HostStatus(HostPhase.CLOSED, code = "HOST_CLOSED")
                null
            } else if (request.terminal != null && request.owner != null) {
                CleanupWork(request, checkNotNull(request.owner), checkNotNull(request.terminal))
            } else if (request.terminal != null) {
                null // Preparation still owns the right to return a resource; launch() will clean it.
            } else {
                request.approvalState = ApprovalState.INVALIDATED
                terminateLocked(request, Terminal(HostPhase.CLOSED, "HOST_CLOSED",
                    "TaskHost closed and released its owned capability."))
            }
        }
        cleanup?.let(::cleanupAndCommit)
    }

    /** Context expiry shares every approval/start/completion gate, even without a context poll. */
    private fun boundedDeadline(request: ActiveRequest, duration: Long): Long {
        val now = clock.nowNanos()
        val taskDeadline = request.taskDeadlineNanos
        return if (taskDeadline != null && taskDeadline - now < duration) taskDeadline else deadline(now, duration)
    }

    private fun expireApprovalLocked(request: ActiveRequest): Boolean {
        if (!isDue(clock.nowNanos(), request.approvalDeadlineNanos)) return false
        request.approvalConsumed = true
        request.approvalState = ApprovalState.EXPIRED
        request.terminal = Terminal(HostPhase.EXPIRED, "APPROVAL_EXPIRED",
            "Approval expired before the App UI decision committed.")
        active = null
        status = terminalView(request, checkNotNull(request.terminal), CleanupState.NONE)
        return true
    }

    private fun beginExecutionLocked(request: ActiveRequest): Launch? {
        if (request.action.approvalRequired && expireApprovalLocked(request)) return null
        val mismatch = targetMismatch(request)
        if (mismatch != null) {
            request.approvalState = ApprovalState.INVALIDATED
            request.terminal = Terminal(HostPhase.INVALIDATED, mismatch,
                "The approved capability binding is no longer current.")
            active = null
            status = terminalView(request, checkNotNull(request.terminal), CleanupState.NONE)
            return null
        }
        // Snapshot revalidation may itself consume the remaining approval TTL.
        if (request.action.approvalRequired && expireApprovalLocked(request)) return null
        executionSequence = Math.addExact(executionSequence, 1L)
        request.executionEpoch = executionSequence
        request.executionDeadlineNanos = boundedDeadline(request, executionTimeoutNanos)
        request.preparing = true
        status = view(request, HostPhase.EXECUTING, deadline = request.executionDeadlineNanos)
        return Launch(request, executionSequence)
    }

    private fun launch(launch: Launch) {
        val delegate = try {
            when (val binding = launch.request.binding) {
                is Binding.Device -> capabilities.prepareDeviceInfo(binding.target)
                is Binding.Linux -> capabilities.prepareCountDemoCsv(binding.target, binding.csv)
                is Binding.Workspace -> when (val action = binding.action) {
                    ParsedAction.ReadDemoCsv -> capabilities.prepareReadDemoCsv(binding.target)
                    is ParsedAction.PublishDemoResult -> capabilities.preparePublishDemoResult(binding.target, action.text) {
                        guardCompleteExecution(launch, action)
                    }
                    else -> error("Workspace action was bound")
                }
                is Binding.WorkspaceGeneral -> when (val action = binding.action) {
                    ParsedAction.ListWorkspaceFiles -> capabilities.prepareWorkspaceList(binding.target)
                    is ParsedAction.ReadWorkspaceFile -> capabilities.prepareWorkspaceRead(binding.target, action.path)
                    is ParsedAction.WriteWorkspaceFile -> capabilities.prepareWorkspaceWrite(binding.target, action.path, action.content)
                    else -> error("General workspace action was bound")
                }
                is Binding.Display -> {
                    val contract = complete
                    if (contract == null) capabilities.prepareDisplay(binding.target, binding.action)
                    else capabilities.prepareCompleteDisplay(binding.target, binding.action) { guardCompleteExecution(launch, launch.request.action) }
                }
            }
        } catch (error: Throwable) {
            preparationFailed(launch, error)
            if (error !is Exception) throw error
            return
        }
        val lease = ExecutionLease(delegate)

        var cleanup: CleanupWork? = null
        var mayStart = false
        synchronized(lock) {
            val request = active
            if (request !== launch.request || request.executionEpoch != launch.executionEpoch) {
                // Defensive path: no returned capability may be abandoned even after host teardown.
                cleanup = CleanupWork(launch.request, lease,
                    Terminal(HostPhase.INVALIDATED, "STALE_PREPARATION", "Prepared capability is stale."))
            } else {
                request.preparing = false
                request.owner = lease
                val terminal = request.terminal ?: preStartInvalidation(request)
                if (terminal != null) {
                    request.terminal = terminal
                    cleanup = CleanupWork(request, lease, terminal)
                } else {
                    mayStart = true
                }
            }
        }
        cleanup?.let { cleanupAndCommit(it); return }
        if (!mayStart) return

        // A deterministic test hook widens the real prepare/install/start race without changing policy.
        try {
            beforeOwnedStart()
        } catch (error: Throwable) {
            failAndCleanup(launch, "PRE_START_FAILED", error.message)
            if (error !is Exception) throw error
            return
        }

        synchronized(lock) {
            val request = active
            val terminal = if (request !== launch.request || request.executionEpoch != launch.executionEpoch)
                Terminal(HostPhase.INVALIDATED, "STALE_EXECUTION", "Execution ownership is stale.")
            else request.terminal ?: preStartInvalidation(request)
            if (terminal != null) {
                launch.request.terminal = terminal
                cleanup = CleanupWork(launch.request, lease, terminal)
                mayStart = false
            }
        }
        cleanup?.let { cleanupAndCommit(it); return }
        if (!mayStart) return

        try {
            complete?.beforeStart(launch.request.action)
            // start() and requestClose() share one gate: close-before-start can never start later.
            lease.start { outcome -> finish(launch, outcome) }
        } catch (error: Throwable) {
            failAndCleanup(launch, "START_FAILED", error.message)
            if (error !is Exception) throw error
        }
    }

    private fun guardCompleteExecution(launch: Launch, action: ParsedAction): Boolean {
        fun requireOwner() = synchronized(lock) {
            val request = active
            check(request === launch.request && request.executionEpoch == launch.executionEpoch &&
                request.terminal == null && !isDue(clock.nowNanos(), request.executionDeadlineNanos)) {
                "COMPLETE_EXECUTION_STALE"
            }
        }
        requireOwner()
        val skip = checkNotNull(complete).guard(action)
        requireOwner()
        return skip
    }

    private fun preparationFailed(launch: Launch, error: Throwable) {
        synchronized(lock) {
            val request = active
            if (request !== launch.request || request.executionEpoch != launch.executionEpoch) return
            request.preparing = false
            val terminal = request.terminal ?: Terminal(HostPhase.FAILED, "PREPARE_FAILED", bounded(error.message))
            request.terminal = terminal
            active = null
            status = terminalView(request, terminal, CleanupState.NONE)
        }
    }

    private fun preStartInvalidation(request: ActiveRequest): Terminal? {
        if (isDue(clock.nowNanos(), request.executionDeadlineNanos)) {
            request.approvalState = ApprovalState.EXPIRED
            return Terminal(HostPhase.EXPIRED, "EXECUTION_TIMEOUT",
                "Tool execution expired before the owned capability could start.")
        }
        val mismatch = targetMismatch(request)
        if (mismatch != null) {
            request.approvalState = ApprovalState.INVALIDATED
            return Terminal(HostPhase.INVALIDATED, mismatch,
                "The capability binding changed before execution start.")
        }
        if (isDue(clock.nowNanos(), request.executionDeadlineNanos)) {
            request.approvalState = ApprovalState.EXPIRED
            return Terminal(HostPhase.EXPIRED, "EXECUTION_TIMEOUT",
                "Capability revalidation consumed the remaining execution deadline.")
        }
        return null
    }

    private fun finish(launch: Launch, outcome: ExecutionOutcome) {
        val cleanup: CleanupWork
        synchronized(lock) {
            val request = active
            if (request !== launch.request || status.phase != HostPhase.EXECUTING ||
                request.executionEpoch != launch.executionEpoch || request.terminal != null) return
            val terminal = when {
                isDue(clock.nowNanos(), request.executionDeadlineNanos) -> {
                    request.approvalState = ApprovalState.EXPIRED
                    Terminal(HostPhase.EXPIRED, "EXECUTION_TIMEOUT",
                        "A late callback arrived after the execution deadline.")
                }
                outcome is ExecutionOutcome.Success -> {
                    val mismatch = targetMismatch(request)
                    if (mismatch != null) {
                        request.approvalState = ApprovalState.INVALIDATED
                        Terminal(HostPhase.INVALIDATED, mismatch,
                            "The bound capability changed before completion.")
                    } else if (isDue(clock.nowNanos(), request.executionDeadlineNanos)) {
                        request.approvalState = ApprovalState.EXPIRED
                        Terminal(HostPhase.EXPIRED, "EXECUTION_TIMEOUT",
                            "Completion revalidation consumed the remaining deadline.")
                    } else Terminal(HostPhase.SUCCEEDED, "OK", null, outcome.result)
                }
                outcome is ExecutionOutcome.Failure -> Terminal(HostPhase.FAILED,
                    safeCode(outcome.code), bounded(outcome.message), outcome.result)
                else -> error("Unknown execution outcome.")
            }
            request.terminal = terminal
            cleanup = CleanupWork(request, checkNotNull(request.owner), terminal)
        }
        cleanupAndCommit(cleanup)
    }

    private fun failAndCleanup(launch: Launch, code: String, message: String?) {
        val cleanup = synchronized(lock) {
            val request = active
            if (request !== launch.request || request.executionEpoch != launch.executionEpoch ||
                request.terminal != null) return
            val terminal = Terminal(HostPhase.FAILED, code, bounded(message))
            request.terminal = terminal
            CleanupWork(request, checkNotNull(request.owner), terminal)
        }
        cleanupAndCommit(cleanup)
    }

    private fun terminateLocked(request: ActiveRequest, terminal: Terminal): CleanupWork? {
        request.terminal = terminal
        val owner = request.owner
        return when {
            owner != null -> {
                status = terminalView(request, terminal, CleanupState.PENDING)
                CleanupWork(request, owner, terminal)
            }
            request.preparing -> {
                // Keep the slot: prepare may still return a resource that must be closed.
                status = terminalView(request, terminal, CleanupState.PENDING)
                null
            }
            else -> {
                active = null
                status = terminalView(request, terminal, CleanupState.NONE)
                null
            }
        }
    }

    private fun cleanupAndCommit(work: CleanupWork) {
        val proven = work.owner.requestClose()
        synchronized(lock) {
            val request = active
            if (request !== work.request) return
            if (proven) {
                request.owner = null
                active = null
                val terminal = if (request.cleanupEverFailed) Terminal(HostPhase.FAILED,
                    "CLEANUP_RECOVERED", "Resource cleanup required a retry; success is not claimed.",
                    work.terminal.result) else work.terminal
                status = terminalView(request, terminal, CleanupState.PROVEN)
                if (terminal.phase == HostPhase.SUCCEEDED) {
                    try { complete?.accept(request.action, checkNotNull(terminal.result)) }
                    catch (_: Exception) {
                        status = view(request, HostPhase.FAILED, code = "JOURNAL_COMMIT_UNCONFIRMED",
                            message = "Tool state was observed but journal commit could not be confirmed.",
                            result = terminal.result, cleanupState = CleanupState.PROVEN)
                    }
                }
            } else {
                request.cleanupEverFailed = true
                request.owner = work.owner
                status = terminalView(request, Terminal(HostPhase.FAILED,
                    "RESOURCE_CLEANUP_UNPROVEN", bounded(work.owner.lastCloseError?.message),
                    work.terminal.result), CleanupState.UNPROVEN)
            }
        }
    }

    private fun bind(action: ParsedAction): Binding = when (action) {
        ParsedAction.DeviceInfo -> {
            val target = snapshot("Device info") { capabilities.currentDeviceInfoTarget() }
            requireUid(target.packageUid)
            if (!target.available) throw ToolValidationException("BACKEND_NOT_READY", "Device info is unavailable.")
            Binding.Device(target)
        }
        ParsedAction.CountDemoCsv -> {
            val target = snapshot("Linux") { capabilities.currentLinuxTarget() }
            requireUid(target.packageUid)
            validateGeneration(target.rootfsGeneration)
            if (!target.backendAlive) throw ToolValidationException("BACKEND_NOT_READY", "Linux backend is unavailable.")
            if (!target.verifiedLinuxSession) {
                throw ToolValidationException("LINUX_SESSION_NOT_VERIFIED", "LinuxSession has not verified this rootfs.")
            }
            val csv = CanonicalChildPath.existingRegularFile(target.workspaceRoot,
                CanonicalChildPath.DEMO_RELATIVE_PATH)
            Binding.Linux(target.copy(workspaceRoot = target.workspaceRoot.toAbsolutePath().normalize()), csv)
        }
        ParsedAction.ReadDemoCsv, is ParsedAction.PublishDemoResult -> {
            val target = snapshot("Private workspace") { capabilities.currentWorkspaceTarget() }
            requireUid(target.packageUid)
            if (!target.inputDigest.matches(Regex("[a-f0-9]{64}"))) throw ToolValidationException("INVALID_INPUT_DIGEST", "Workspace digest is invalid.")
            if (action is ParsedAction.PublishDemoResult && action.expectedInputDigest != target.inputDigest) {
                throw ToolValidationException("INPUT_CHANGED", "Approved input digest is not current.")
            }
            Binding.Workspace(target, action)
        }
        ParsedAction.ListWorkspaceFiles, is ParsedAction.ReadWorkspaceFile, is ParsedAction.WriteWorkspaceFile -> {
            val target = snapshot("Private workspace root") { capabilities.currentWorkspaceRoot() }
            requireUid(target.packageUid)
            val normalizedRoot = target.root.toAbsolutePath().normalize()
            when (action) {
                is ParsedAction.ReadWorkspaceFile -> CanonicalChildPath.existingRegularFile(normalizedRoot, action.path)
                is ParsedAction.WriteWorkspaceFile -> CanonicalChildPath.targetRegularFile(normalizedRoot, action.path)
                else -> Unit
            }
            Binding.WorkspaceGeneral(target.copy(root = normalizedRoot), action)
        }
        is ParsedAction.Display -> {
            val target = snapshot("Typed Binder virtual display") { capabilities.currentDisplayTarget() }
            requireUid(target.packageUid)
            if (!target.backendReady) {
                throw ToolValidationException("BACKEND_NOT_READY", "Typed Binder virtual-display backend is not ready.")
            }
            if (target.displayId <= 0 || target.displayEpoch <= 0) {
                throw ToolValidationException("INVALID_DISPLAY_TARGET", "Owned virtual display target is invalid.")
            }
            if (action.expectedDisplayId != target.displayId ||
                action.expectedDisplayEpoch != target.displayEpoch) {
                throw ToolValidationException("DISPLAY_TARGET_MISMATCH",
                    "Expected displayId/epoch is not the current owned virtual display.")
            }
            val displayAction = when (action) {
                is ParsedAction.Display.Observe -> DisplayAction.Observe
                is ParsedAction.Display.ObserveDemo -> DisplayAction.ObserveDemo
                is ParsedAction.Display.DemoTap -> DisplayAction.DemoTap(action.x, action.y)
                is ParsedAction.Display.DemoText -> DisplayAction.DemoText(action.text)
            }
            Binding.Display(target, displayAction)
        }
    }

    private fun <T> snapshot(label: String, read: () -> T): T = try {
        read()
    } catch (error: ToolValidationException) {
        throw error
    } catch (_: Exception) {
        throw ToolValidationException("BACKEND_NOT_READY", "$label capability snapshot is unavailable.")
    }

    private fun targetMismatch(request: ActiveRequest): String? = try {
        val input = complete?.snapshot()?.inputDigest.orEmpty()
        if (input.isNotEmpty() && capabilities.currentWorkspaceTarget().inputDigest != input) "INPUT_CHANGED"
        else when (val binding = request.binding) {
            is Binding.Device -> {
                val current = capabilities.currentDeviceInfoTarget()
                when {
                    !current.available -> "BACKEND_DEATH"
                    current != binding.target -> "TARGET_CHANGED"
                    else -> null
                }
            }
            is Binding.Linux -> {
                val current = capabilities.currentLinuxTarget()
                when {
                    !current.backendAlive -> "BACKEND_DEATH"
                    !current.verifiedLinuxSession -> "SESSION_INVALIDATED"
                    current.rootfsGeneration != binding.target.rootfsGeneration ||
                        current.packageUid != binding.target.packageUid ||
                        current.workspaceRoot.toAbsolutePath().normalize() != binding.target.workspaceRoot -> "TARGET_CHANGED"
                    CanonicalChildPath.existingRegularFile(current.workspaceRoot,
                        CanonicalChildPath.DEMO_RELATIVE_PATH) != binding.csv -> "TARGET_CHANGED"
                    else -> null
                }
            }
            is Binding.Workspace -> if (capabilities.currentWorkspaceTarget() == binding.target) null else "INPUT_CHANGED"
            is Binding.WorkspaceGeneral -> {
                val current = capabilities.currentWorkspaceRoot()
                val normalized = current.root.toAbsolutePath().normalize()
                if (current.packageUid != binding.target.packageUid || normalized != binding.target.root) "TARGET_CHANGED"
                else when (val act = binding.action) {
                    is ParsedAction.ReadWorkspaceFile -> {
                        CanonicalChildPath.existingRegularFile(normalized, act.path)
                        null
                    }
                    is ParsedAction.WriteWorkspaceFile -> {
                        CanonicalChildPath.targetRegularFile(normalized, act.path)
                        null
                    }
                    else -> null
                }
            }
            is Binding.Display -> {
                val current = capabilities.currentDisplayTarget()
                when {
                    !current.backendReady -> "BACKEND_DEATH"
                    current != binding.target -> "TARGET_CHANGED"
                    else -> null
                }
            }
        }
    } catch (_: Exception) {
        "TARGET_CHANGED"
    }

    private fun requestHash(nonce: String, action: ParsedAction, binding: Binding): String {
        val parts = mutableListOf(
            "schema" to "pocketrun.task.v1",
            "nonce" to nonce,
            "tool" to action.tool.wireName,
        )
        complete?.snapshot()?.let { parts += listOf("completeRunId" to it.runId, "inputDigest" to it.inputDigest) }
        when (binding) {
            is Binding.Device -> parts += "packageUid" to binding.target.packageUid.toString()
            is Binding.Linux -> parts += listOf(
                "packageUid" to binding.target.packageUid.toString(),
                "rootfsGeneration" to binding.target.rootfsGeneration,
                "guestPath" to CanonicalChildPath.DEMO_GUEST_PATH,
                "hostPath" to binding.csv.toString(),
            )
            is Binding.Workspace -> {
                parts += listOf("packageUid" to binding.target.packageUid.toString(), "root" to binding.target.root.toString(),
                    "inputDigest" to binding.target.inputDigest)
                if (binding.action is ParsedAction.PublishDemoResult) parts += "text" to binding.action.text
            }
            is Binding.WorkspaceGeneral -> {
                parts += listOf("packageUid" to binding.target.packageUid.toString(), "root" to binding.target.root.toString())
                when (val act = binding.action) {
                    is ParsedAction.ReadWorkspaceFile -> parts += "path" to act.path
                    is ParsedAction.WriteWorkspaceFile -> parts += listOf(
                        "path" to act.path,
                        "contentDigest" to DemoCsvReport.digest(act.content.toByteArray(Charsets.UTF_8)),
                    )
                    else -> Unit
                }
            }
            is Binding.Display -> {
                parts += listOf(
                    "packageUid" to binding.target.packageUid.toString(),
                    "displayEpoch" to binding.target.displayEpoch.toString(),
                    "displayId" to binding.target.displayId.toString(),
                )
                when (val displayAction = binding.action) {
                    DisplayAction.Observe -> parts += "action" to "observe"
                    DisplayAction.ObserveDemo -> parts += "action" to "observe_demo"
                    is DisplayAction.DemoTap -> parts += listOf(
                        "action" to "demo_tap", "x" to displayAction.x.toString(),
                        "y" to displayAction.y.toString())
                    is DisplayAction.DemoText -> parts += listOf(
                        "action" to "demo_text", "text" to displayAction.text)
                }
            }
        }
        return RequestHasher.hash(parts)
    }

    private fun view(
        request: ActiveRequest,
        phase: HostPhase,
        deadline: Long? = null,
        actor: String? = null,
        code: String? = null,
        message: String? = null,
        result: ToolResult? = null,
        cleanupState: CleanupState = CleanupState.NONE,
    ) = HostStatus(
        phase = phase,
        tool = request.action.tool.wireName,
        requestNonce = request.nonce,
        requestHash = request.hash,
        backendEpoch = request.backendEpoch,
        target = request.targetLabel(),
        approvalActor = actor,
        approvalState = request.approvalState,
        cleanupState = cleanupState,
        deadlineNanos = deadline,
        code = code,
        message = message,
        result = result,
    )

    private fun terminalView(request: ActiveRequest, terminal: Terminal, cleanup: CleanupState): HostStatus {
        if (terminal.phase != HostPhase.SUCCEEDED) {
            try { complete?.terminal(terminal.code, terminal.phase == HostPhase.CANCELLED) }
            catch (_: Exception) {
                return view(request, HostPhase.FAILED, code = "JOURNAL_UPDATE_UNCONFIRMED",
                    message = "Terminal state could not be durably recorded.", result = terminal.result, cleanupState = cleanup)
            }
        }
        return view(request, terminal.phase, code = terminal.code, message = terminal.message,
            result = terminal.result, cleanupState = cleanup)
    }

    private fun ActiveRequest.targetLabel(): String = when (val value = binding) {
        is Binding.Device -> "PocketRun App UID ${value.target.packageUid}"
        is Binding.Linux -> "${CanonicalChildPath.DEMO_GUEST_PATH} · rootfs ${value.target.rootfsGeneration} · UID ${value.target.packageUid}"
        is Binding.Workspace -> "private ${if (value.action == ParsedAction.ReadDemoCsv) "items.csv disclosure" else "result.txt no-overwrite publication"} · input ${value.target.inputDigest} · UID ${value.target.packageUid}"
        is Binding.WorkspaceGeneral -> when (val act = value.action) {
            ParsedAction.ListWorkspaceFiles -> "工作区文件列表 · UID ${value.target.packageUid}"
            is ParsedAction.ReadWorkspaceFile -> "读取工作区文件 ${act.path} · UID ${value.target.packageUid}"
            is ParsedAction.WriteWorkspaceFile -> "写入工作区文件 ${act.path}（${act.content.toByteArray(Charsets.UTF_8).size} 字节） · UID ${value.target.packageUid}"
            else -> "工作区 · UID ${value.target.packageUid}"
        }
        is Binding.Display -> "virtual display ${value.target.displayId} · epoch ${value.target.displayEpoch} · PocketRun UID ${value.target.packageUid}"
    }

    private fun requireUid(uid: Int) {
        if (uid <= 0) throw ToolValidationException("INVALID_PACKAGE_UID", "Package UID must be positive.")
    }

    private fun validateGeneration(value: String) {
        if (!value.matches(GENERATION_PATTERN)) {
            throw ToolValidationException("INVALID_ROOTFS_GENERATION", "Invalid rootfs generation.")
        }
    }

    private fun safeCode(value: String): String =
        if (value.matches(ERROR_CODE_PATTERN)) value else "ADAPTER_FAILURE"

    private sealed interface Binding {
        val backendEpoch: String

        data class Device(val target: DeviceInfoTarget) : Binding {
            override val backendEpoch = "app:${target.packageUid}"
        }
        data class Linux(val target: LinuxTarget, val csv: Path) : Binding {
            override val backendEpoch = "rootfs:${target.rootfsGeneration}:${target.packageUid}"
        }
        data class Workspace(val target: WorkspaceTarget, val action: ParsedAction) : Binding {
            override val backendEpoch = "workspace:${target.packageUid}:${target.inputDigest}"
        }
        data class WorkspaceGeneral(val target: WorkspaceRootTarget, val action: ParsedAction) : Binding {
            override val backendEpoch = "workspace-root:${target.packageUid}"
        }
        data class Display(val target: DisplayTarget, val action: DisplayAction) : Binding {
            override val backendEpoch = "display:${target.displayEpoch}:${target.displayId}:${target.packageUid}"
        }
    }

    private class ActiveRequest(
        val nonce: String,
        val hash: String,
        val action: ParsedAction,
        val binding: Binding,
        val taskDeadlineNanos: Long?,
    ) {
        val backendEpoch = binding.backendEpoch
        var approvalConsumed = false
        var approvalState = if (action.approvalRequired) ApprovalState.PENDING_APP_UI
            else ApprovalState.NOT_REQUIRED
        var approvalDeadlineNanos = Long.MAX_VALUE
        var executionDeadlineNanos = Long.MAX_VALUE
        var executionEpoch = 0L
        var preparing = false
        var owner: ExecutionLease? = null
        var terminal: Terminal? = null
        var cleanupEverFailed = false
    }

    private data class Terminal(
        val phase: HostPhase,
        val code: String,
        val message: String?,
        val result: ToolResult? = null,
    )
    private data class Launch(val request: ActiveRequest, val executionEpoch: Long)
    private data class CleanupWork(val request: ActiveRequest, val owner: ExecutionLease, val terminal: Terminal)

    /** Linearizes start against close and retains ownership after a failed close for retry. */
    private class ExecutionLease(private val delegate: OwnedExecution) {
        private val gate = Any()
        private var closeRequested = false
        private var closeProven = false
        private var started = false
        var lastCloseError: Throwable? = null
            private set

        fun start(completion: (ExecutionOutcome) -> Unit): Boolean = synchronized(gate) {
            if (closeRequested) return false
            check(!started) { "Owned execution already started." }
            started = true
            delegate.start(completion)
            true
        }

        fun requestClose(): Boolean = synchronized(gate) {
            closeRequested = true
            if (closeProven) return true
            try {
                delegate.close()
                closeProven = true
                lastCloseError = null
                true
            } catch (error: Throwable) {
                lastCloseError = error
                false
            }
        }
    }

    companion object {
        const val DEFAULT_APPROVAL_TTL_MILLIS = 30_000L
        const val DEFAULT_EXECUTION_TIMEOUT_MILLIS = 5_000L
        const val APP_PHONE_UI_ACTOR = "app_phone_ui"
        private val NONCE_PATTERN = Regex("[A-Za-z0-9_-]{16,64}")
        private val GENERATION_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")
        private val ERROR_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")

        internal fun withStartProbe(
            capabilities: TaskCapabilities,
            clock: MonotonicClock,
            nonceSource: NonceSource,
            approvalTtlMillis: Long,
            executionTimeoutMillis: Long,
            beforeOwnedStart: () -> Unit,
        ) = TaskHost(capabilities, clock, nonceSource, approvalTtlMillis,
            executionTimeoutMillis, beforeOwnedStart)

        private fun checkedDuration(millis: Long): Long {
            require(millis in 1..TimeUnit.DAYS.toMillis(1)) { "Invalid timeout." }
            return TimeUnit.MILLISECONDS.toNanos(millis)
        }

        // nanoTime is a wrapping signed counter; subtraction remains valid for our <=1 day TTLs.
        private fun deadline(now: Long, duration: Long): Long = now + duration
        private fun isDue(now: Long, deadline: Long): Boolean = now - deadline >= 0L
        private fun bounded(message: String?): String = message.orEmpty().take(256)
    }
}

private class SecureNonceSource : NonceSource {
    private val random = SecureRandom()
    override fun nextNonce(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
