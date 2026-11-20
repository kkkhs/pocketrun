package io.github.kkkhs.pocketrun

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import io.github.kkkhs.pocketrun.taskhost.AndroidHostReadiness
import io.github.kkkhs.pocketrun.taskhost.AndroidTaskCapabilities
import io.github.kkkhs.pocketrun.taskhost.AppUiDecision
import io.github.kkkhs.pocketrun.taskhost.CleanupState
import io.github.kkkhs.pocketrun.taskhost.ContextStatus
import io.github.kkkhs.pocketrun.taskhost.DisplayTarget
import io.github.kkkhs.pocketrun.taskhost.SingleTaskContext
import org.json.JSONArray
import io.github.kkkhs.pocketrun.taskhost.HostBusyException
import io.github.kkkhs.pocketrun.taskhost.HostStatus
import io.github.kkkhs.pocketrun.taskhost.RawToolCall
import io.github.kkkhs.pocketrun.taskhost.TaskHost
import io.github.kkkhs.pocketrun.taskhost.TaskHostBackendDeath
import io.github.kkkhs.pocketrun.taskhost.TaskHostDisplayPortRegistry
import io.github.kkkhs.pocketrun.taskhost.TaskHostJson
import io.github.kkkhs.pocketrun.taskhost.ToolValidationException
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@ReactModule(name = TaskHostModule.NAME)
class TaskHostModule(context: ReactApplicationContext) : NativePocketRunTaskHostSpec(context) {
    private val adapter = AndroidTaskCapabilities(context)
    private val host = TaskHost(adapter)
    private val task = SingleTaskContext(host)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketRun-TaskHostModule")
    }
    private val backendDeathWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketRun-TaskHostDeath")
    }
    @Volatile private var disposed = false
    private val displayDeathRegistration = TaskHostDisplayPortRegistry.addDeathListener(::onDisplayDeath)

    override fun getName() = NAME

    override fun prepareHost(promise: Promise) = execute(promise) {
        task.withoutActiveTask {
            adapter.prepareLinuxEnvironment()
            readinessMap(adapter.readiness())
        }
    }

    override fun getHostReadiness(promise: Promise) {
        resolve(promise) { readinessMap(adapter.readiness()) }
    }

    override fun submitTool(tool: String, argumentsJson: String, promise: Promise) = execute(promise) {
        task.withoutActiveTask { stateMap(host.submit(parseCall(tool, argumentsJson))) }
    }

    override fun beginTask(goal: String, promise: Promise) = execute(promise) {
        contextMap(task.begin(goal))
    }

    override fun submitTaskStep(taskId: String, tool: String, argumentsJson: String, promise: Promise) = execute(promise) {
        val call = try {
            parseCall(tool, argumentsJson)
        } catch (_: IllegalArgumentException) {
            return@execute contextMap(task.fail(taskId, "INVALID_TOOL_JSON"))
        }
        contextMap(task.submit(taskId, call))
    }

    override fun getSingleTaskState(promise: Promise) {
        resolve(promise) { contextMap(task.state()) }
    }

    override fun finishTask(taskId: String, promise: Promise) = execute(promise) {
        contextMap(task.finish(taskId))
    }

    override fun cancelTask(taskId: String, promise: Promise) = execute(promise) {
        contextMap(task.cancel(taskId))
    }

    private fun parseCall(tool: String, argumentsJson: String): RawToolCall {
        val parsed = TaskHostJson.parseObject(argumentsJson).toMutableMap()
        // JSON has one integer syntax; the core contract intentionally distinguishes Binder epoch Long.
        val epoch = parsed["expectedDisplayEpoch"]
        if (epoch is Int) parsed["expectedDisplayEpoch"] = epoch.toLong()
        return RawToolCall(tool, parsed)
    }

    private fun contextMap(value: ContextStatus): WritableMap = Arguments.createMap().apply {
        putNullableString("taskId", value.taskId)
        putNullableString("goal", value.goal)
        putString("phase", value.phase.name.lowercase())
        putNullableString("code", value.code)
        putInt("contextBytes", value.contextBytes)
        putInt("maxContextBytes", value.budget.maxContextBytes)
        putInt("maxResultBytes", value.budget.maxResultBytes)
        putInt("maxSteps", value.budget.maxSteps)
        putDouble("maxDurationMillis", value.budget.maxDurationMillis.toDouble())
        putString("stepsJson", JSONArray().apply {
            value.steps.forEach { step ->
                put(JSONObject().apply {
                    put("number", step.number)
                    put("tool", step.call.tool)
                    put("arguments", JSONObject(step.call.arguments))
                    put("phase", step.status.phase.name.lowercase())
                    put("requestNonce", step.status.requestNonce)
                    put("requestHash", step.status.requestHash)
                    put("target", step.status.target)
                    put("backendEpoch", step.status.backendEpoch)
                    put("approvalState", step.status.approvalState?.name?.lowercase())
                    put("cleanupState", step.status.cleanupState.name.lowercase())
                    put("code", step.status.code)
                    put("result", JSONObject(step.status.result?.fields ?: emptyMap<String, String>()))
                })
            }
        }.toString())
    }

    override fun getTaskState(promise: Promise) {
        resolve(promise) { stateMap(host.state()) }
    }

    override fun decideFromAppUi(requestNonce: String, decision: String, promise: Promise) = execute(promise) {
        val value = when (decision) {
            "approve" -> AppUiDecision.APPROVE
            "reject" -> AppUiDecision.REJECT
            else -> throw IllegalArgumentException("App UI decision must be approve or reject.")
        }
        check(host.onAppUiDecision(requestNonce, value)) { "Approval nonce is stale or already consumed." }
        stateMap(host.state())
    }

    override fun cancelFromAppUi(requestNonce: String, promise: Promise) = execute(promise) {
        check(host.cancelFromAppUi(requestNonce)) { "Task nonce is stale or already settled." }
        stateMap(host.state())
    }

    override fun refreshTask(promise: Promise) = execute(promise) {
        host.refresh()
        stateMap(host.state())
    }

    override fun retryCleanup(promise: Promise) = execute(promise) {
        check(host.retryCleanup()) { "Cleanup is still unproven or no retryable owner exists." }
        stateMap(host.state())
    }

    private fun onDisplayDeath(target: DisplayTarget) {
        if (disposed) return
        try {
            backendDeathWorker.execute death@{
                if (disposed) return@death
                try {
                    val binding = TaskHostBackendDeath.binding(host.state(), target) ?: return@death
                    host.onBackendDeath(binding.requestNonce, binding.backendEpoch)
                } catch (error: Throwable) {
                    Log.e(NAME, "TaskHost backend-death transition failed", error)
                }
            }
        } catch (error: RejectedExecutionException) {
            if (!disposed) throw error
        }
    }

    private fun execute(promise: Promise, action: () -> Any?) {
        try {
            worker.execute { resolve(promise, action) }
        } catch (error: RejectedExecutionException) {
            promise.reject("E_TASK_HOST_CLOSED", "TaskHost module is closed.", error)
        }
    }

    private fun resolve(promise: Promise, action: () -> Any?) {
        try {
            promise.resolve(action())
        } catch (error: ToolValidationException) {
            promise.reject("E_TASK_${error.code}", error.message, error)
        } catch (error: HostBusyException) {
            promise.reject("E_TASK_BUSY", error.message, error)
        } catch (error: Exception) {
            promise.reject("E_TASK_HOST", error.message ?: "TaskHost operation failed.", error)
        }
    }

    private fun stateMap(value: HostStatus): WritableMap = Arguments.createMap().apply {
        putString("phase", value.phase.name.lowercase())
        putNullableString("tool", value.tool)
        putNullableString("requestNonce", value.requestNonce)
        putNullableString("requestHash", value.requestHash)
        putNullableString("backendEpoch", value.backendEpoch)
        putNullableString("target", value.target)
        putNullableString("approvalActor", value.approvalActor)
        putNullableString("approvalState", value.approvalState?.name?.lowercase())
        putString("cleanupState", value.cleanupState.name.lowercase())
        putNullableString("deadlineNanos", value.deadlineNanos?.toString())
        putNullableString("code", value.code)
        putNullableString("message", value.message)
        putString("resultJson", value.result?.fields?.let { fields ->
            JSONObject().apply { fields.toSortedMap().forEach { (key, field) -> put(key, field) } }.toString()
        } ?: "{}")
    }

    private fun readinessMap(value: AndroidHostReadiness): WritableMap = Arguments.createMap().apply {
        putInt("packageUid", value.packageUid)
        putBoolean("linuxReady", value.linuxReady)
        putNullableString("rootfsGeneration", value.rootfsGeneration)
        putBoolean("displayReady", value.displayReady)
        putNullableString("displayEpoch", value.displayEpoch?.toString())
        if (value.displayId == null) putNull("displayId") else putInt("displayId", value.displayId)
    }

    private fun WritableMap.putNullableString(key: String, value: String?) {
        if (value == null) putNull(key) else putString(key, value)
    }

    override fun invalidate() {
        disposed = true
        displayDeathRegistration.close()
        backendDeathWorker.shutdownNow()
        task.close()
        // If cleanup is unproven, the adapter retains its source owner until process teardown.
        if (host.state().cleanupState != CleanupState.UNPROVEN) adapter.close()
        worker.shutdown()
        super.invalidate()
    }

    companion object { const val NAME = "PocketRunTaskHost" }
}
