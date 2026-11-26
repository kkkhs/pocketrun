package io.github.kkkhs.pocketrun.taskhost

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Last observed facts and unfinished intent; no executable handles or reusable approval. */
internal data class TaskJournalRecord(
    val runId: String,
    val goal: String,
    val phase: String = "ready",
    val stage: String = "none",
    val inputDigest: String = "",
    val expectedText: String = "",
    val pendingEffect: String = "none",
    val outputDigest: String = "",
    val code: String = "",
    val displayId: Int = 0,
    val displayEpoch: Long = 0,
    val packageUid: Int = 0,
)

internal object TaskJournalCodec {
    const val MAX_BYTES = 4096
    private val RUN_ID = Regex("run_[a-f0-9]{32}")
    private val DIGEST = Regex("[a-f0-9]{64}")
    private val REPORT = Regex("items=(0|[1-9][0-9]*);quantity=(0|[1-9][0-9]*)")
    private val CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    private val PHASES = setOf("ready", "running", "interrupted", "completed", "cancelled", "failed")
    private val STAGES = setOf("none", "csv_read", "demo_observed", "tap_accepted", "text_accepted", "demo_verified", "output_verified")
    private val EFFECTS = setOf("none", "read", "observe", "tap", "text", "publish")
    private val KEYS = setOf("schema", "runId", "goal", "phase", "stage", "inputDigest", "expectedText", "pendingEffect", "outputDigest", "code", "displayId", "displayEpoch", "packageUid")

    fun newRunId() = "run_" + UUID.randomUUID().toString().replace("-", "")

    fun encode(record: TaskJournalRecord): ByteArray {
        validate(record)
        val fields = linkedMapOf<String, Any>(
            "schema" to 1, "runId" to record.runId, "goal" to record.goal,
            "phase" to record.phase, "stage" to record.stage, "inputDigest" to record.inputDigest,
            "expectedText" to record.expectedText, "pendingEffect" to record.pendingEffect,
            "outputDigest" to record.outputDigest, "code" to record.code,
            "displayId" to record.displayId, "displayEpoch" to record.displayEpoch, "packageUid" to record.packageUid,
        )
        return encoded(SdkJsonBudget.json(fields)).also {
            require(it.size <= MAX_BYTES) { "TASK_JOURNAL_BUDGET" }
        }
    }

    fun decode(bytes: ByteArray): TaskJournalRecord {
        require(bytes.size in 1..MAX_BYTES) { "TASK_JOURNAL_BUDGET" }
        val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val fields = TaskHostJson.parseObject(source)
        require(fields.keys == KEYS && fields["schema"] is Int && fields["schema"] == 1) { "INVALID_TASK_JOURNAL_SCHEMA" }
        fun text(key: String): String = (fields[key] as? String)
            ?: throw IllegalArgumentException("INVALID_TASK_JOURNAL_FIELD")
        val displayId = fields["displayId"] as? Int ?: throw IllegalArgumentException("INVALID_TASK_JOURNAL_TARGET")
        val epoch = fields["displayEpoch"]
        require(epoch is Int || epoch is Long) { "INVALID_TASK_JOURNAL_TARGET" }
        val uid = fields["packageUid"] as? Int ?: throw IllegalArgumentException("INVALID_TASK_JOURNAL_TARGET")
        return TaskJournalRecord(text("runId"), text("goal"), text("phase"), text("stage"),
            text("inputDigest"), text("expectedText"), text("pendingEffect"), text("outputDigest"), text("code"),
            displayId, (epoch as Number).toLong(), uid).also(::validate)
    }

    private fun validate(record: TaskJournalRecord) {
        require(record.runId.length == 36 && RUN_ID.matches(record.runId)) { "INVALID_TASK_RUN_ID" }
        require(record.goal.length in 1..1024 && record.goal.isNotBlank() && encoded(record.goal).size <= 1024 &&
            record.goal.none { it.code < 0x20 && it != '\n' && it != '\t' }) { "INVALID_TASK_JOURNAL_GOAL" }
        require(record.phase in PHASES && record.stage in STAGES && record.pendingEffect in EFFECTS) { "INVALID_TASK_JOURNAL_STATE" }
        require((record.displayId == 0 && record.displayEpoch == 0L && record.packageUid == 0 && record.stage == "none") ||
            (record.displayId > 0 && record.displayEpoch > 0 && record.packageUid > 0)) { "INVALID_TASK_JOURNAL_TARGET" }
        if (record.stage != "none") require(record.inputDigest.isNotEmpty() && record.expectedText.isNotEmpty()) {
            "INVALID_TASK_JOURNAL_PREREQUISITE"
        }
        require(validDigest(record.inputDigest) && validDigest(record.outputDigest)) { "INVALID_TASK_JOURNAL_DIGEST" }
        require(record.expectedText.isEmpty() || record.expectedText.length <= 64 && REPORT.matches(record.expectedText)) {
            "INVALID_TASK_JOURNAL_REPORT"
        }
        require(record.code.isEmpty() || record.code.length <= 64 && CODE.matches(record.code)) { "INVALID_TASK_JOURNAL_CODE" }
        if (record.phase == "completed") {
            require(record.stage == "output_verified" && record.pendingEffect == "none" &&
                record.inputDigest.isNotEmpty() && record.outputDigest.isNotEmpty() && record.expectedText.isNotEmpty()) {
                "UNVERIFIED_TASK_COMPLETION"
            }
        }
    }

    private fun validDigest(value: String) = value.isEmpty() || value.length == 64 && DIGEST.matches(value)

    private fun encoded(source: String): ByteArray {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }
}
