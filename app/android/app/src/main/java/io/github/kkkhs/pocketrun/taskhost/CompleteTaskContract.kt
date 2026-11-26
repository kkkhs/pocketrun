package io.github.kkkhs.pocketrun.taskhost

/** One named exercise's native prerequisites, not a second executor or persisted authority. */
internal class CompleteTaskContract(
    initial: TaskJournalRecord,
    private val target: DisplayTarget,
    private val workspace: () -> WorkspaceTarget,
    private val observe: () -> DemoObservation,
    private val output: () -> ByteArray?,
    private val persist: (TaskJournalRecord) -> Unit,
) {
    private val lock = Any()
    private var record = initial

    fun start() = synchronized(lock) {
        require(target.backendReady && target.displayId > 0 && target.displayEpoch > 0 && target.packageUid > 0)
        require(record.phase == "ready" && record.stage == "none" && record.pendingEffect == "none") { "COMPLETE_NEW_CONTEXT_REQUIRED" }
        update(record.copy(displayId = target.displayId, displayEpoch = target.displayEpoch, packageUid = target.packageUid))
    }

    fun snapshot(): TaskJournalRecord = synchronized(lock) { record.copy() }
    fun blocksOrdinaryTask(): Boolean = synchronized(lock) { record.pendingEffect != "none" || record.phase in setOf("ready", "running", "interrupted") }

    fun validate(action: ParsedAction) = synchronized(lock) {
        require(record.phase == "ready" || record.phase == "running") { "COMPLETE_TASK_TERMINAL" }
        val allowed = when (action) {
            ParsedAction.ReadDemoCsv -> record.stage == "none"
            is ParsedAction.Display.ObserveDemo -> record.stage != "none"
            is ParsedAction.Display.DemoTap -> record.stage == "demo_observed"
            is ParsedAction.Display.DemoText -> record.stage == "tap_accepted" && action.text == record.expectedText
            is ParsedAction.PublishDemoResult -> record.stage == "demo_verified" &&
                action.text == record.expectedText && action.expectedInputDigest == record.inputDigest
            else -> false
        }
        require(allowed) { "COMPLETE_TASK_PREREQUISITE" }
        if (action is ParsedAction.Display) require(action.expectedDisplayId == target.displayId &&
            action.expectedDisplayEpoch == target.displayEpoch) { "COMPLETE_DISPLAY_CHANGED" }
    }

    /** Rechecked by the owner immediately before input/publication, as well as by TaskHost. */
    fun guard(action: ParsedAction): Boolean = synchronized(lock) {
        validate(action)
        val current = workspace()
        if (record.inputDigest.isNotEmpty()) require(current.inputDigest == record.inputDigest) { "COMPLETE_INPUT_CHANGED" }
        if (action is ParsedAction.Display || action is ParsedAction.PublishDemoResult) {
            val actual = observe()
            require(actual.target == target) { "COMPLETE_DISPLAY_CHANGED" }
            val already = actual.value == record.expectedText && actual.resultValue == "input: ${record.expectedText}"
            when (action) {
                is ParsedAction.Display.DemoTap -> {
                    require(actual.value.isEmpty() || already) { "COMPLETE_DEMO_NOT_EMPTY" }
                    require(action.x == actual.x && action.y == actual.y) { "COMPLETE_TAP_CHANGED" }
                }
                is ParsedAction.Display.DemoText -> require(already || actual.value.isEmpty() && actual.focused) {
                    "COMPLETE_INPUT_NOT_FOCUSED_OR_EMPTY"
                }
                is ParsedAction.PublishDemoResult -> require(already) { "COMPLETE_DEMO_UNVERIFIED" }
                else -> Unit
            }
            already && (action is ParsedAction.Display.DemoTap || action is ParsedAction.Display.DemoText)
        } else false
    }

    fun beforeStart(action: ParsedAction) = synchronized(lock) {
        guard(action)
        update(record.copy(phase = "running", pendingEffect = effect(action), code = ""))
    }

    fun accept(action: ParsedAction, result: ToolResult) = synchronized(lock) {
        require(record.phase == "running" && record.pendingEffect == effect(action)) { "COMPLETE_RESULT_STALE" }
        val fields = result.fields
        val next = when (action) {
            ParsedAction.ReadDemoCsv -> {
                val report = DemoCsvReport.parse(checkNotNull(fields["csvText"]).toByteArray(Charsets.UTF_8))
                require(fields["inputDigest"] == report.inputDigest &&
                    (record.inputDigest.isEmpty() || record.inputDigest == report.inputDigest)) { "COMPLETE_INPUT_CHANGED" }
                record.copy(stage = "csv_read", inputDigest = report.inputDigest, expectedText = report.text)
            }
            is ParsedAction.Display.ObserveDemo -> {
                val value = checkObservation(fields)
                require(value == "" || value == record.expectedText) { "COMPLETE_DEMO_VALUE_MISMATCH" }
                if (record.stage in setOf("text_accepted", "demo_verified", "output_verified")) {
                    require(value == record.expectedText) { "COMPLETE_INPUT_UNCONFIRMED" }
                }
                val stage = when {
                    record.stage == "output_verified" -> "output_verified"
                    value == record.expectedText -> "demo_verified"
                    record.stage == "tap_accepted" -> "tap_accepted"
                    else -> "demo_observed"
                }
                record.copy(stage = stage)
            }
            is ParsedAction.Display.DemoTap -> record.copy(stage = if (fields["effect"] == "already_present") {
                require(checkObservation(fields) == record.expectedText); "demo_verified"
            } else "tap_accepted")
            is ParsedAction.Display.DemoText -> record.copy(stage = if (fields["effect"] == "already_present") {
                require(checkObservation(fields) == record.expectedText); "demo_verified"
            } else "text_accepted")
            is ParsedAction.PublishDemoResult -> {
                val expected = (record.expectedText + "\n").toByteArray(Charsets.UTF_8)
                require(fields["outputDigest"] == DemoCsvReport.digest(expected) &&
                    fields["publicationStatus"] in setOf("published", "already_present")) { "COMPLETE_OUTPUT_UNVERIFIED" }
                record.copy(stage = "output_verified", outputDigest = checkNotNull(fields["outputDigest"]),
                    code = if (fields["publicationStatus"] == "already_present") "OUTPUT_ALREADY_PRESENT" else "OUTPUT_PUBLISHED")
            }
            else -> error("Complete action was validated")
        }
        update(next.copy(pendingEffect = "none"))
    }

    fun finish(beforeCommit: () -> Unit = {}) = synchronized(lock) {
        require(record.phase in setOf("ready", "running") && record.stage == "output_verified" &&
            record.pendingEffect == "none" && record.expectedText.isNotEmpty()) {
            "COMPLETE_GOAL_UNVERIFIED"
        }
        require(workspace().inputDigest == record.inputDigest) { "COMPLETE_INPUT_CHANGED" }
        val actual = observe()
        require(actual.target == target && actual.value == record.expectedText && actual.resultValue == "input: ${record.expectedText}") {
            "COMPLETE_DEMO_UNVERIFIED"
        }
        val bytes = checkNotNull(output()) { "COMPLETE_OUTPUT_MISSING" }
        require(bytes.contentEquals((record.expectedText + "\n").toByteArray()) && DemoCsvReport.digest(bytes) == record.outputDigest) {
            "COMPLETE_OUTPUT_CHANGED"
        }
        require(workspace().inputDigest == record.inputDigest) { "COMPLETE_INPUT_CHANGED" }
        beforeCommit()
        update(record.copy(phase = "completed"))
    }

    fun terminal(code: String, cancelled: Boolean = false) = synchronized(lock) {
        if (record.phase != "completed") update(record.copy(phase = if (cancelled) "cancelled" else "failed", code = safeCode(code)))
    }

    private fun effect(action: ParsedAction): String = when (action) {
        ParsedAction.ReadDemoCsv -> "read"
        is ParsedAction.Display.ObserveDemo -> "observe"
        is ParsedAction.Display.DemoTap -> "tap"
        is ParsedAction.Display.DemoText -> "text"
        is ParsedAction.PublishDemoResult -> "publish"
        else -> error("Complete action was validated")
    }

    private fun checkObservation(fields: Map<String, String>): String {
        require(fields["displayId"] == target.displayId.toString() && fields["displayEpoch"] == target.displayEpoch.toString() &&
            fields["source"] == "app-owned-demo-typed-binder") { "COMPLETE_OBSERVATION_CHANGED" }
        val value = checkNotNull(fields["demoValue"])
        require(fields["demoResult"] == "input: $value") { "COMPLETE_DEMO_UNVERIFIED" }
        return value
    }

    private fun safeCode(code: String) = code.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) } ?: "COMPLETE_TASK_FAILED"

    private fun update(next: TaskJournalRecord) {
        TaskJournalCodec.encode(next)
        persist(next)
        record = next
    }
}
