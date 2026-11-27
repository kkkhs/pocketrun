package io.github.kkkhs.pocketrun

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import io.github.kkkhs.pocketrun.taskhost.AndroidHostReadiness
import io.github.kkkhs.pocketrun.taskhost.AndroidTaskCapabilities
import io.github.kkkhs.pocketrun.taskhost.AppUiDecision
import io.github.kkkhs.pocketrun.taskhost.CleanupState
import io.github.kkkhs.pocketrun.taskhost.ContextStatus
import io.github.kkkhs.pocketrun.taskhost.AgentLoop
import io.github.kkkhs.pocketrun.taskhost.AgentStatus
import io.github.kkkhs.pocketrun.taskhost.AnthropicModelTransport
import io.github.kkkhs.pocketrun.taskhost.ChatConversation
import io.github.kkkhs.pocketrun.taskhost.ChatEngine
import io.github.kkkhs.pocketrun.taskhost.ChatRuntimeStatus
import io.github.kkkhs.pocketrun.taskhost.ConversationCodec
import io.github.kkkhs.pocketrun.taskhost.ConversationStore
import io.github.kkkhs.pocketrun.taskhost.DisplayTarget
import io.github.kkkhs.pocketrun.taskhost.ModelCredentialStore
import io.github.kkkhs.pocketrun.taskhost.ModelMode
import io.github.kkkhs.pocketrun.taskhost.ModelSettings
import io.github.kkkhs.pocketrun.taskhost.MemoryCodec
import io.github.kkkhs.pocketrun.taskhost.MemorySelection
import io.github.kkkhs.pocketrun.taskhost.MemorySnapshot
import io.github.kkkhs.pocketrun.taskhost.MemoryStore
import io.github.kkkhs.pocketrun.taskhost.SingleTaskContext
import io.github.kkkhs.pocketrun.taskhost.CompleteTaskContract
import io.github.kkkhs.pocketrun.taskhost.TaskJournal
import io.github.kkkhs.pocketrun.taskhost.TaskJournalCodec
import io.github.kkkhs.pocketrun.taskhost.TaskJournalRecord
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
    private val agent = AgentLoop(task)
    private val modelStore = ModelCredentialStore(context)
    private val memoryStore = MemoryStore(context)
    private val conversationStore = ConversationStore(context)
    private val chatEngine = ChatEngine(task, conversationStore)
    private val journal = TaskJournal(context)
    private var journalInitialized = false
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
        requireOrdinaryTaskAvailable()
        task.withoutActiveTask { stateMap(host.submit(parseCall(tool, argumentsJson))) }
    }

    override fun getModelConfiguration(promise: Promise) = executeModel(promise) { modelConfigurationMap() }

    override fun saveModelConfiguration(endpoint: String, model: String, apiKey: String, promise: Promise) = executeModel(promise) {
        agent.cancel("MODEL_CONFIG_CHANGED")
        chatEngine.cancel("MODEL_CONFIG_CHANGED")
        modelStore.save(ModelSettings(endpoint, model), apiKey)
        modelConfigurationMap()
    }

    override fun clearModelConfiguration(promise: Promise) = executeModel(promise) {
        agent.cancel("MODEL_CONFIG_CLEARED")
        chatEngine.cancel("MODEL_CONFIG_CLEARED")
        modelStore.clear()
        modelConfigurationMap()
    }

    override fun startAgent(goal: String, promise: Promise) = executeModel(promise) { startModel(goal) }

    override fun listConversations(promise: Promise) = executeChat(promise) {
        conversationListMap(conversationStore.list())
    }

    override fun getConversation(conversationId: String, promise: Promise) = executeChat(promise) {
        val conv = conversationStore.get(conversationId) ?: throw IllegalArgumentException("CONVERSATION_NOT_FOUND")
        conversationDetailMap(conv)
    }

    override fun createConversation(title: String, promise: Promise) = executeChat(promise) {
        conversationDetailMap(conversationStore.create(title))
    }

    override fun deleteConversation(conversationId: String, promise: Promise) = executeChat(promise) {
        if (chatEngine.state().conversationId == conversationId && chatEngine.isActive()) {
            chatEngine.cancel("CONVERSATION_DELETED")
        }
        conversationListMap(conversationStore.delete(conversationId))
    }

    override fun resetConversations(promise: Promise) = executeChat(promise) {
        chatEngine.cancel("CONVERSATION_RESET")
        conversationListMap(conversationStore.resetUnreadable())
    }

    override fun sendChatMessage(conversationId: String, prompt: String, includeMemory: Boolean, promise: Promise) =
        executeChat(promise) {
            check(!disposed)
            check(!agent.isActive()) { "MODEL_TASK_ACTIVE" }
            check(android.os.Build.VERSION.SDK_INT >= 26) { "MODEL_RUNTIME_REQUIRES_API_26" }
            val credential = checkNotNull(modelStore.load()) { "MODEL_CREDENTIAL_REQUIRED" }
            val memory = if (includeMemory) {
                val snap = memoryStore.snapshot()
                if (snap.notes.isNotEmpty()) {
                    MemoryCodec.select(snap, snap.notes.take(MemoryCodec.MAX_SELECTED).map { it.id })
                } else null
            } else null
            val ready = adapter.readiness()
            val observation = "androidReady=true; workspaceReady=true; linuxReady=${ready.linuxReady}; " +
                "displayReady=${ready.displayReady}; displayId=${ready.displayId}; displayEpoch=${ready.displayEpoch}"
            val source = AnthropicModelTransport(credential, ModelMode.CHAT)
            try {
                chatRuntimeMap(chatEngine.send(conversationId.ifBlank { null }, prompt, source, observation, memory))
            } catch (error: Exception) {
                source.close()
                throw error
            }
        }

    override fun getChatRuntimeState(promise: Promise) {
        resolve(promise) { chatRuntimeMap(chatEngine.state()) }
    }

    override fun cancelChatTurn(promise: Promise) = executeChat(promise) {
        chatRuntimeMap(chatEngine.cancel())
    }

    override fun listWorkspaceFiles(promise: Promise) = execute(promise) {
        val target = adapter.currentWorkspaceRoot()
        val files = adapter.listWorkspaceFileSummaries()
        JSONArray().apply {
            files.forEach { (path, size) ->
                put(JSONObject().put("path", path).put("bytes", size))
            }
        }.toString().also { check(target.packageUid > 0) }
    }

    private fun executeChat(promise: Promise, action: () -> Any?) {
        try {
            worker.execute {
                try {
                    check(!disposed)
                    val value = action()
                    check(!disposed)
                    promise.resolve(value)
                } catch (error: HostBusyException) {
                    promise.reject("E_CHAT_BUSY", "当前已有任务正在执行，请等待完成或先取消。")
                } catch (error: Exception) {
                    val code = error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "CHAT_OPERATION_FAILED"
                    promise.reject("E_CHAT_$code", chatFailureMessage(code))
                }
            }
        } catch (_: RejectedExecutionException) { promise.reject("E_CHAT_CLOSED", "对话模块已关闭。") }
    }

    private fun chatFailureMessage(code: String): String = when (code) {
        "MODEL_CREDENTIAL_REQUIRED" -> "请先在「设置」中保存模型地址、模型名和 API Key。"
        "CONVERSATION_STORE_CORRUPT" -> "本地对话记录不可读取，可在「设置」中手动重置。"
        "CONVERSATION_NOT_FOUND" -> "该对话已不存在，请刷新列表或新建对话。"
        "INVALID_CHAT_PROMPT" -> "请输入 1～2048 字节以内的有效消息。"
        "MODEL_TASK_ACTIVE" -> "实验室中仍有活跃任务正在运行，请先结束或取消。"
        else -> "对话操作失败（$code），请检查配置或稍后重试。"
    }

    private fun conversationListMap(conversations: List<ChatConversation>): WritableMap = Arguments.createMap().apply {
        putString("conversationsJson", ConversationCodec.listSummariesJson(conversations))
    }

    private fun conversationDetailMap(conversation: ChatConversation): WritableMap = Arguments.createMap().apply {
        putString("id", conversation.id)
        putString("title", conversation.title)
        putDouble("createdAtMillis", conversation.createdAtMillis.toDouble())
        putDouble("updatedAtMillis", conversation.updatedAtMillis.toDouble())
        putString("messagesJson", ConversationCodec.messagesJson(conversation.messages))
    }

    private fun chatRuntimeMap(value: ChatRuntimeStatus): WritableMap = Arguments.createMap().apply {
        putString("phase", value.phase.name.lowercase())
        putNullableString("conversationId", value.conversationId)
        putNullableString("taskId", value.taskId)
        putString("streamingText", value.streamingText)
        putNullableString("pendingNonce", value.pendingApproval?.requestNonce)
        putNullableString("pendingHash", value.pendingApproval?.requestHash)
        putNullableString("pendingTool", value.pendingApproval?.tool)
        putNullableString("pendingTarget", value.pendingApproval?.target)
        putNullableString("pendingArgs", value.pendingApproval?.argumentsSummary)
        putString("activeToolsJson", ConversationCodec.toolTracesJson(value.activeTools))
        putInt("rounds", value.rounds)
        putDouble("outputTokens", value.outputTokens.toDouble())
        putNullableString("code", value.code)
        putNullableString("errorMessage", value.errorMessage)
    }

    override fun getMemorySnapshot(promise: Promise) = executeMemory(promise) { memoryMap(memoryStore.snapshot()) }

    override fun saveMemoryNote(expectedRevision: String, noteId: String, text: String, promise: Promise) =
        executeMemory(promise) {
            memoryMap(memoryStore.save(expectedRevision, noteId, text) {
                agent.discardMemoryContext()
                chatEngine.cancel("MEMORY_CHANGED")
            })
        }

    override fun deleteMemoryNote(expectedRevision: String, noteId: String, promise: Promise) = executeMemory(promise) {
        memoryMap(memoryStore.delete(expectedRevision, noteId) {
            agent.discardMemoryContext()
            chatEngine.cancel("MEMORY_CHANGED")
        })
    }

    override fun clearMemory(expectedRevision: String, promise: Promise) = executeMemory(promise) {
        memoryMap(memoryStore.clear(expectedRevision) {
            agent.discardMemoryContext()
            chatEngine.cancel("MEMORY_CHANGED")
        })
    }

    override fun resetMemory(promise: Promise) = executeMemory(promise) {
        memoryMap(memoryStore.resetUnreadable {
            agent.discardMemoryContext()
            chatEngine.cancel("MEMORY_CHANGED")
        })
    }

    override fun startAgentWithMemory(goal: String, noteIds: ReadableArray, expectedRevision: String,
        expectedEndpoint: String, expectedModel: String, promise: Promise) {
        val ids = try {
            require(noteIds.size() <= MemoryCodec.MAX_SELECTED)
            (0 until noteIds.size()).map { index ->
                require(noteIds.getType(index) == ReadableType.String)
                checkNotNull(noteIds.getString(index)).also(MemoryCodec::validateId)
            }
        } catch (_: Exception) {
            promise.reject("E_MEMORY_OPERATION", "Invalid memory selection; refresh and select the notes again.")
            return
        }
        executeMemory(promise) {
            startModel(goal, memoryStore.select(expectedRevision, ids), expectedEndpoint, expectedModel)
        }
    }

    private fun startModel(goal: String, memory: MemorySelection? = null,
        expectedEndpoint: String? = null, expectedModel: String? = null,
        complete: CompleteTaskContract? = null): WritableMap {
        check(!disposed)
        if (complete == null) requireOrdinaryTaskAvailable()
        check(android.os.Build.VERSION.SDK_INT >= 26) { "MODEL_RUNTIME_REQUIRES_API_26" }
        val credential = checkNotNull(modelStore.load()) { "MODEL_CREDENTIAL_REQUIRED" }
        if (memory != null || complete != null) check(credential.settings.endpoint == expectedEndpoint &&
            credential.settings.model == expectedModel) { "MODEL_TARGET_CHANGED" }
        val source = AnthropicModelTransport(credential, complete != null)
        return try {
            val ready = adapter.readiness()
            val observation = "linuxReady=${ready.linuxReady}; displayReady=${ready.displayReady}; " +
                "displayId=${ready.displayId}; displayEpoch=${ready.displayEpoch}"
            agentMap(agent.start(goal, source, observation, memory, complete))
        } catch (error: Exception) {
            source.close()
            throw error
        }
    }

    override fun getCompleteTaskState(promise: Promise) = executeComplete(promise) { task.state(); completeMap(currentJournal()) }

    override fun prepareCompleteTask(promise: Promise) = executeComplete(promise) {
        task.withoutActiveTask {
            requireSettledHost()
            adapter.prepareWorkspaceEnvironment()
            completeMap(currentJournal())
        }
    }

    override fun beginCompleteTask(expectedRunId: String, expectedDisplayId: Double,
        expectedDisplayEpoch: String, promise: Promise) = executeComplete(promise) {
        val contract = newCompleteContract(expectedRunId, expectedDisplayId, expectedDisplayEpoch)
        task.beginComplete(COMPLETE_GOAL, contract)
        completeMap(currentJournal())
    }

    override fun startCompleteAgent(expectedRunId: String, expectedDisplayId: Double,
        expectedDisplayEpoch: String, expectedEndpoint: String, expectedModel: String,
        promise: Promise) = executeComplete(promise) {
        val contract = newCompleteContract(expectedRunId, expectedDisplayId, expectedDisplayEpoch)
        startModel(COMPLETE_GOAL, expectedEndpoint = expectedEndpoint, expectedModel = expectedModel, complete = contract)
        completeMap(currentJournal())
    }

    override fun resetCompleteJournal(promise: Promise) = executeComplete(promise) {
        task.withoutActiveTask {
            requireSettledHost()
            journal.resetUnreadable()
            journalInitialized = true
            completeMap(null)
        }
    }

    // All journal entry points use this module's existing serial worker; the file itself is atomic.
    private fun currentJournal(): TaskJournalRecord? {
        val value = journal.load()
        val next = if (!journalInitialized && value?.phase in setOf("ready", "running")) {
            checkNotNull(value).copy(phase = "interrupted", code = "CONTEXT_RECREATED").also(journal::save)
        } else value
        journalInitialized = true
        return next
    }

    private fun requireOrdinaryTaskAvailable() {
        val value = currentJournal()
        check(value == null || value.pendingEffect == "none" && value.phase !in setOf("ready", "running", "interrupted")) {
            "COMPLETE_TASK_REVIEW_REQUIRED"
        }
    }

    private fun requireSettledHost() {
        check(!agent.isActive() && !chatEngine.isActive()) { "MODEL_TASK_ACTIVE" }
        val value = host.state()
        check(value.phase !in setOf(io.github.kkkhs.pocketrun.taskhost.HostPhase.PENDING_APPROVAL,
            io.github.kkkhs.pocketrun.taskhost.HostPhase.EXECUTING) &&
            value.cleanupState !in setOf(CleanupState.PENDING, CleanupState.UNPROVEN)) { "TASK_OWNER_UNSETTLED" }
    }

    private fun newCompleteContract(expectedRunId: String, expectedDisplayId: Double,
        expectedDisplayEpoch: String): CompleteTaskContract = task.withoutActiveTask {
        requireSettledHost()
        val previous = currentJournal()
        check(expectedRunId == previous?.runId.orEmpty()) { "COMPLETE_JOURNAL_CHANGED" }
        val target = adapter.currentDisplayTarget()
        check(expectedDisplayId.isFinite() && expectedDisplayId == target.displayId.toDouble() &&
            expectedDisplayEpoch == target.displayEpoch.toString() && target.backendReady) { "COMPLETE_DISPLAY_CHANGED" }
        val workspace = adapter.currentWorkspaceTarget()
        val report = adapter.checkWorkspaceRecovery()
        check(workspace.inputDigest == report.inputDigest && workspace.packageUid == target.packageUid) { "COMPLETE_INPUT_CHANGED" }
        if (previous != null) {
            check(previous.inputDigest.isEmpty() || previous.inputDigest == report.inputDigest) { "COMPLETE_INPUT_CHANGED" }
            check(previous.packageUid == 0 || previous.packageUid == workspace.packageUid) { "COMPLETE_UID_CHANGED" }
        }
        val actual = adapter.currentDemoObservation(target)
        check(actual.target == target && (actual.value.isEmpty() && actual.resultValue == "input: " ||
            actual.value == report.text && actual.resultValue == "input: ${report.text}")) { "COMPLETE_DEMO_REVIEW_REQUIRED" }
        val result = adapter.currentDemoResult()
        check(result == null || result.contentEquals(report.fileText.toByteArray(Charsets.UTF_8))) { "COMPLETE_OUTPUT_REVIEW_REQUIRED" }
        CompleteTaskContract(TaskJournalRecord(TaskJournalCodec.newRunId(), COMPLETE_GOAL,
            inputDigest = workspace.inputDigest), target, adapter::currentWorkspaceTarget,
            { adapter.currentDemoObservation(target) }, adapter::currentDemoResult, journal::save)
    }

    private fun completeMap(value: TaskJournalRecord?): WritableMap = Arguments.createMap().apply {
        putNullableString("runId", value?.runId)
        putNullableString("taskId", task.state().taskId?.takeIf { value != null && task.completeRunId() == value.runId })
        putString("phase", value?.phase ?: "none")
        putString("stage", value?.stage ?: "none")
        putString("pendingEffect", value?.pendingEffect ?: "none")
        putString("inputDigest", value?.inputDigest.orEmpty())
        putString("expectedText", value?.expectedText.orEmpty())
        putString("outputDigest", value?.outputDigest.orEmpty())
        putNullableString("code", value?.code)
        if (value == null || value.displayId == 0) putNull("displayId") else putInt("displayId", value.displayId)
        putNullableString("displayEpoch", value?.displayEpoch?.takeIf { it > 0 }?.toString())
        putInt("packageUid", value?.packageUid ?: 0)
    }

    private fun executeComplete(promise: Promise, action: () -> Any?) {
        try {
            worker.execute {
                try { check(!disposed); val value = action(); check(!disposed); promise.resolve(value) }
                catch (error: Exception) {
                    val code = error.message?.takeIf { it in COMPLETE_FAILURE_CODES } ?: "COMPLETE_STATE_UNVERIFIED"
                    Log.w(NAME, "Complete operation failed: $code")
                    promise.reject("E_COMPLETE_TASK", "Complete task could not be verified ($code). Refresh the journal and target; check input, Demo, output and retained cleanup. Nothing is automatically replayed or replaced.")
                }
            }
        } catch (_: RejectedExecutionException) { promise.reject("E_COMPLETE_CLOSED", "Complete-task module is closed.") }
    }

    private fun executeMemory(promise: Promise, action: () -> Any?) {
        try {
            worker.execute {
                try {
                    check(!disposed)
                    val value = action()
                    check(!disposed)
                    promise.resolve(value)
                } catch (_: Exception) {
                    promise.reject("E_MEMORY_OPERATION", "Memory operation failed; refresh the revision, check byte limits and model target. An unreadable store requires explicit reset.")
                }
            }
        } catch (_: RejectedExecutionException) { promise.reject("E_MEMORY_CLOSED", "Memory module is closed.") }
    }

    private fun memoryMap(snapshot: MemorySnapshot): WritableMap = Arguments.createMap().apply {
        putString("revision", snapshot.revision)
        putString("notesJson", JSONArray().apply {
            snapshot.notes.forEach { note -> put(JSONObject().put("id", note.id).put("text", note.text)) }
        }.toString())
        putInt("maxNotes", MemoryCodec.MAX_NOTES)
        putInt("maxNoteBytes", MemoryCodec.MAX_NOTE_BYTES)
        putInt("maxSelected", MemoryCodec.MAX_SELECTED)
        putInt("maxSelectedBytes", MemoryCodec.MAX_SELECTED_BYTES)
    }

    override fun getAgentState(promise: Promise) { resolve(promise) { agentMap(agent.state()) } }
    override fun cancelAgent(promise: Promise) = executeModel(promise) { agentMap(agent.cancel()) }

    private fun executeModel(promise: Promise, action: () -> Any?) {
        try {
            worker.execute {
                try { promise.resolve(action()) }
                catch (_: Exception) { promise.reject("E_MODEL_CONFIGURATION", "Model operation failed; check configuration, task ownership and credential store.") }
            }
        } catch (_: RejectedExecutionException) { promise.reject("E_MODEL_CLOSED", "Model module is closed.") }
    }

    private fun modelConfigurationMap(): WritableMap {
        val saved = modelStore.load()
        val settings = saved?.settings ?: ModelSettings.DEFAULT
        return Arguments.createMap().apply {
            putString("endpoint", settings.endpoint)
            putString("model", settings.model)
            putBoolean("hasCredential", saved != null)
        }
    }

    private fun agentMap(value: AgentStatus): WritableMap = Arguments.createMap().apply {
        putString("phase", value.phase.name.lowercase())
        putNullableString("taskId", value.taskId)
        putInt("rounds", value.rounds)
        putDouble("outputTokens", value.outputTokens.toDouble())
        putInt("contextBytes", value.contextBytes)
        putNullableString("finalText", value.finalText)
        putNullableString("code", value.code)
        putNullableString("memoryRevision", value.memoryRevision)
        putString("memoryIdsJson", JSONArray(value.memoryIds).toString())
        putInt("memoryBytes", value.memoryBytes)
    }

    override fun beginTask(goal: String, promise: Promise) = execute(promise) {
        if (agent.isActive() || chatEngine.isActive()) throw HostBusyException()
        requireOrdinaryTaskAvailable()
        contextMap(task.begin(goal))
    }

    override fun submitTaskStep(taskId: String, tool: String, argumentsJson: String, promise: Promise) = execute(promise) {
        if (agent.isActive() || chatEngine.isActive()) throw HostBusyException()
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
        if (agent.isActive() || chatEngine.isActive()) throw HostBusyException()
        contextMap(task.finish(taskId))
    }

    override fun cancelTask(taskId: String, promise: Promise) = execute(promise) {
        check(task.state().taskId == taskId) { "Task identity is stale." }
        if (agent.isActive()) agent.cancel()
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
        chatEngine.close()
        conversationStore.close()
        memoryStore.close()
        displayDeathRegistration.close()
        backendDeathWorker.shutdownNow()
        agent.close()
        task.close()
        journal.close()
        // If cleanup is unproven, the adapter retains its source owner until process teardown.
        if (host.state().cleanupState != CleanupState.UNPROVEN) adapter.close()
        worker.shutdown()
        super.invalidate()
    }

    companion object {
        const val NAME = "PocketRunTaskHost"
        private val COMPLETE_FAILURE_CODES = setOf("DEMO_INPUT_NOT_UNIQUE", "DEMO_RESULT_NOT_UNIQUE", "DEMO_OBSERVATION_UNAVAILABLE",
            "INVALID_DEMO_NODE", "DEMO_VALUE_UNCONFIRMED", "COMPLETE_INPUT_CHANGED", "COMPLETE_DISPLAY_CHANGED", "COMPLETE_UID_CHANGED",
            "COMPLETE_OUTPUT_REVIEW_REQUIRED", "COMPLETE_DEMO_REVIEW_REQUIRED", "COMPLETE_JOURNAL_CHANGED", "WORKSPACE_RESULT_RECOVERY_REQUIRED",
            "TASK_JOURNAL_CORRUPT", "MODEL_CREDENTIAL_REQUIRED", "MODEL_TARGET_CHANGED", "TASK_OWNER_UNSETTLED", "MODEL_TASK_ACTIVE")
        private const val COMPLETE_GOAL = "读取私有 items.csv，整理为 items=N;quantity=N；输入自有虚拟屏 Demo，重新观察确认后无覆盖发布并读回 result.txt。"
    }
}
