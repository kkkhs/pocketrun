package io.github.kkkhs.pocketrun.taskhost

import java.io.Closeable
import java.util.Collections
import java.util.concurrent.TimeUnit

enum class ContextPhase { IDLE, READY, RUNNING, COMPLETED, CANCELLED, FAILED, BUDGET_EXHAUSTED, CLOSED }

data class ContextBudget(
    val maxSteps: Int = 8,
    val maxContextBytes: Int = 65_536,
    val maxResultBytes: Int = 16_384,
    val maxDurationMillis: Long = 120_000,
) {
    init {
        require(maxSteps in 1..16)
        require(maxContextBytes in 1024..131_072)
        require(maxResultBytes in 256..32_768 && maxResultBytes <= maxContextBytes)
        require(maxDurationMillis in 1..300_000)
    }
}

data class ContextStep(val number: Int, val call: RawToolCall, val status: HostStatus)
data class ContextStatus(
    val taskId: String?,
    val goal: String?,
    val phase: ContextPhase,
    val steps: List<ContextStep>,
    val budget: ContextBudget,
    val contextBytes: Int,
    val code: String?,
)

/** One in-memory task ledger, using the existing host's sole execution/approval/cleanup slot. */
class SingleTaskContext(
    private val host: TaskHost,
    private val clock: MonotonicClock = MonotonicClock(host::nowNanos),
    private val budget: ContextBudget = ContextBudget(),
) : Closeable {
    private val lock = Any()
    private var sequence = 0L
    private var closed = false
    private var activeBudget = budget
    private var taskId: String? = null
    private var goal: String? = null
    private var phase = ContextPhase.IDLE
    private var code: String? = null
    private var deadline = 0L
    private val steps = mutableListOf<ContextStep>()
    private var pendingNumber: Int? = null
    private var complete: CompleteTaskContract? = null

    fun begin(value: String): ContextStatus = synchronized(lock) { beginLocked(value, null, budget) }

    internal fun beginWithBudget(value: String, customBudget: ContextBudget): ContextStatus =
        synchronized(lock) { beginLocked(value, null, customBudget) }

    internal fun beginComplete(value: String, contract: CompleteTaskContract): ContextStatus = synchronized(lock) {
        beginLocked(value, contract, budget)
    }

    private fun beginLocked(value: String, contract: CompleteTaskContract?, nextBudget: ContextBudget): ContextStatus {
        refreshLocked()
        check(!closed) { "Task context is closed." }
        if (isActive() || hostOwnsSlot()) throw HostBusyException()
        val nextGoal = value.trim()
        require(bytes(nextGoal) in 1..1024) { "Task goal must be 1..1024 UTF-8 bytes." }
        host.installCompleteContract(contract)
        complete = contract
        activeBudget = nextBudget
        taskId = "task-${Math.addExact(sequence, 1L).also { sequence = it }}"
        goal = nextGoal
        steps.clear()
        pendingNumber = null
        code = null
        phase = ContextPhase.READY
        deadline = clock.nowNanos() + TimeUnit.MILLISECONDS.toNanos(activeBudget.maxDurationMillis)
        return snapshot()
    }

    fun submit(expectedTaskId: String, call: RawToolCall): ContextStatus = synchronized(lock) {
        requireCurrent(expectedTaskId)
        refreshLocked()
        check(phase == ContextPhase.READY && pendingNumber == null) { "Task is not ready for another step." }
        if (steps.size >= activeBudget.maxSteps) return stopLocked(ContextPhase.BUDGET_EXHAUSTED, "STEP_BUDGET")
        val copied: RawToolCall
        try {
            ToolSchema.validate(call)
            copied = RawToolCall(call.tool, Collections.unmodifiableMap(LinkedHashMap(call.arguments)))
        } catch (_: ToolValidationException) {
            return stopLocked(ContextPhase.FAILED, "INVALID_TOOL_INPUT")
        }
        // Reserve metadata space before requesting any capability or approval.
        val inputBytes = bytes(copied.tool) + copied.arguments.entries.sumOf { bytes(it.key) + bytes(it.value.toString()) }
        if (measuredBytes() + inputBytes + STEP_METADATA_RESERVE > activeBudget.maxContextBytes) {
            return stopLocked(ContextPhase.BUDGET_EXHAUSTED, "CONTEXT_BUDGET")
        }
        val number = steps.size + 1
        val status = try {
            host.submit(copied, taskDeadlineNanos = deadline)
        } catch (_: ToolValidationException) {
            return stopLocked(ContextPhase.FAILED, "TOOL_BINDING_REJECTED")
        }
        steps += ContextStep(number, copied, status)
        pendingNumber = number
        phase = ContextPhase.RUNNING
        refreshLocked()
        snapshot()
    }

    fun finish(expectedTaskId: String): ContextStatus = synchronized(lock) {
        requireCurrent(expectedTaskId)
        refreshLocked()
        check(phase == ContextPhase.READY && pendingNumber == null && steps.isNotEmpty()) {
            "Only a task with settled successful steps may complete."
        }
        try { complete?.finish { check(clock.nowNanos() - deadline < 0) { "TIME_BUDGET" } } }
        catch (_: Exception) {
            return@synchronized if (clock.nowNanos() - deadline >= 0) stopLocked(ContextPhase.BUDGET_EXHAUSTED, "TIME_BUDGET")
                else stopLocked(ContextPhase.FAILED, "NATIVE_GOAL_UNVERIFIED")
        }
        phase = ContextPhase.COMPLETED
        code = "COMPLETED"
        snapshot()
    }

    fun cancel(expectedTaskId: String): ContextStatus = synchronized(lock) {
        requireCurrent(expectedTaskId)
        refreshLocked()
        if (isActive()) stopLocked(ContextPhase.CANCELLED, "USER_CANCELLED") else snapshot()
    }

    fun fail(expectedTaskId: String, reason: String): ContextStatus = synchronized(lock) {
        requireCurrent(expectedTaskId)
        require(reason.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        if (isActive()) stopLocked(ContextPhase.FAILED, reason) else snapshot()
    }

    internal fun completeRunId(): String? = synchronized(lock) { complete?.snapshot()?.runId }

    fun state(): ContextStatus = synchronized(lock) {
        refreshLocked()
        snapshot()
    }

    /** Chapter 9 remains usable, but can never interleave its requests into an active task. */
    fun <T> withoutActiveTask(action: () -> T): T = synchronized(lock) {
        refreshLocked()
        if (isActive() || pendingNumber != null || hostOwnsSlot()) throw HostBusyException()
        action()
    }

    override fun close() = synchronized(lock) {
        closed = true
        if (isActive()) stopLocked(ContextPhase.CLOSED, "CONTEXT_CLOSED")
        // Capture the cancelled request's cleanup before idle host.close() drops its nonce.
        refreshLocked()
        phase = ContextPhase.CLOSED
        host.close()
        refreshLocked()
    }

    private fun refreshLocked() {
        try { refreshHostLocked() } finally { recordCompleteTerminal() }
    }

    private fun refreshHostLocked() {
        if (isActive() && clock.nowNanos() - deadline >= 0) {
            stopLocked(ContextPhase.BUDGET_EXHAUSTED, "TIME_BUDGET")
        }
        val number = pendingNumber ?: return
        val index = number - 1
        val previous = steps[index]
        val current = host.state()
        // A new task or stale host state can never be attached to this step.
        if (current.requestNonce != previous.status.requestNonce || current.tool != previous.call.tool) {
            if (!closed) {
                phase = ContextPhase.FAILED
                code = "HOST_CORRELATION_LOST"
            }
            pendingNumber = null
            return
        }
        val outputBytes = current.result?.fields?.entries?.sumOf { bytes(it.key) + bytes(it.value) } ?: 0
        var retained = current
        if (outputBytes > activeBudget.maxResultBytes) {
            retained = current.copy(result = null)
            if (isActive()) {
                phase = ContextPhase.BUDGET_EXHAUSTED
                code = "RESULT_BUDGET"
            }
        }
        steps[index] = previous.copy(status = retained)
        if (measuredBytes() > activeBudget.maxContextBytes) {
            steps[index] = previous.copy(status = retained.copy(result = null))
            if (isActive()) {
                phase = ContextPhase.BUDGET_EXHAUSTED
                code = "CONTEXT_BUDGET"
            }
        }
        if (current.cleanupState == CleanupState.UNPROVEN && isActive()) {
            phase = ContextPhase.FAILED
            code = current.code ?: "RESOURCE_CLEANUP_UNPROVEN"
        }
        if (current.phase == HostPhase.PENDING_APPROVAL || current.phase == HostPhase.EXECUTING ||
            current.cleanupState == CleanupState.PENDING || current.cleanupState == CleanupState.UNPROVEN) return
        pendingNumber = null
        if (!isActive()) return
        if (current.phase == HostPhase.SUCCEEDED && current.cleanupState == CleanupState.PROVEN) {
            phase = ContextPhase.READY
        } else {
            phase = ContextPhase.FAILED
            code = current.code ?: "TOOL_FAILED"
        }
    }

    private fun stopLocked(next: ContextPhase, reason: String): ContextStatus {
        phase = next
        code = reason
        pendingNumber?.let { number ->
            val nonce = steps[number - 1].status.requestNonce
            if (nonce != null) host.cancelFromAppUi(nonce)
        }
        recordCompleteTerminal()
        // Keep the host owner visible even when its cleanup has not yet been proven.
        return snapshot()
    }

    private fun recordCompleteTerminal() {
        if (isActive()) return
        complete?.let { contract ->
            if (contract.snapshot().phase in setOf("ready", "running")) {
                try { contract.terminal(code ?: "CONTEXT_STOPPED", phase == ContextPhase.CANCELLED || phase == ContextPhase.CLOSED) }
                catch (_: Exception) { phase = ContextPhase.FAILED; code = "JOURNAL_TERMINAL_UNCONFIRMED" }
            }
        }
    }

    private fun requireCurrent(expected: String) {
        check(taskId != null && taskId == expected) { "Task identity is stale." }
    }

    private fun isActive() = phase == ContextPhase.READY || phase == ContextPhase.RUNNING
    private fun hostOwnsSlot(): Boolean {
        val value = host.state()
        return value.phase == HostPhase.PENDING_APPROVAL || value.phase == HostPhase.EXECUTING ||
            value.cleanupState == CleanupState.PENDING || value.cleanupState == CleanupState.UNPROVEN
    }

    private fun measuredBytes(): Int = bytes(goal.orEmpty()) + steps.sumOf { step ->
        val value = step.status
        STEP_METADATA_RESERVE + bytes(step.call.tool) +
            step.call.arguments.entries.sumOf { bytes(it.key) + bytes(it.value.toString()) } +
            (value.result?.fields?.entries?.sumOf { bytes(it.key) + bytes(it.value) } ?: 0)
    }

    private fun snapshot() = ContextStatus(taskId, goal, phase,
        Collections.unmodifiableList(ArrayList(steps)), activeBudget, measuredBytes(), code)

    companion object {
        private const val STEP_METADATA_RESERVE = 768
        private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8).size
    }
}
