package io.github.kkkhs.pocketrun.taskhost

import java.nio.charset.CharacterCodingException
import org.junit.Assert.*
import org.junit.Test

class TaskJournalCodecTest {
    private val record = TaskJournalRecord("run_" + "a".repeat(32), "读取示例、核对后保存",
        displayId = 7, displayEpoch = 9L, packageUid = 10150)
    private val input = "b".repeat(64)
    private val output = "c".repeat(64)

    @Test fun roundTripPreservesUncertainIntentInsteadOfRestoringAuthority() {
        val interrupted = record.copy(phase = "interrupted", stage = "text_accepted", inputDigest = input,
            expectedText = "items=2;quantity=5", pendingEffect = "observe", code = "APP_INTERRUPTED")
        val encoded = TaskJournalCodec.encode(interrupted)
        assertEquals(interrupted, TaskJournalCodec.decode(encoded))
        val fields = TaskHostJson.parseObject(String(encoded, Charsets.UTF_8))
        assertEquals("observe", fields["pendingEffect"])
        assertFalse(fields.keys.any { it in setOf("pid", "fd", "binder", "nonce", "apiKey", "modelHistory") })
        assertNotEquals(TaskJournalCodec.newRunId(), TaskJournalCodec.newRunId())
    }

    @Test fun exactFlatSchemaRejectsUnknownMetadataAndDuplicateKeys() {
        val valid = String(TaskJournalCodec.encode(record), Charsets.UTF_8)
        val bad = listOf(
            valid.replace("\"schema\":1", "\"schema\":2"),
            valid.replace("\"schema\":1", "\"schema\":\"1\""),
            valid.replace("\"schema\":1", "\"schema\":1.0"),
            valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
            valid.dropLast(1) + ",\"nonce\":\"old-approval\"}",
            valid.replace("\"pendingEffect\":\"none\"", "\"pendingEffect\":null"),
            valid.replace("\"goal\":", "\"unknownGoal\":"),
        )
        for (source in bad) {
            assertThrows(IllegalArgumentException::class.java) { TaskJournalCodec.decode(source.toByteArray()) }
        }
    }

    @Test fun allStateAndScalarFieldsHaveExplicitLimits() {
        val invalid = listOf(
            record.copy(runId = "task-1"), record.copy(goal = ""), record.copy(goal = "x".repeat(1025)),
            record.copy(goal = "a" + 0.toChar() + "b"), record.copy(phase = "resume"), record.copy(stage = "approved"),
            record.copy(pendingEffect = "execute"), record.copy(inputDigest = "b".repeat(63)),
            record.copy(outputDigest = "C".repeat(64)), record.copy(expectedText = "items=02;quantity=5"),
            record.copy(expectedText = "items=2;quantity=-5"), record.copy(expectedText = "x".repeat(65)),
            record.copy(code = "provider text"), record.copy(code = "A".repeat(65)),
        )
        for (value in invalid) assertThrows(IllegalArgumentException::class.java) { TaskJournalCodec.encode(value) }
        val exactGoal = record.copy(goal = "x".repeat(1024))
        assertEquals(exactGoal, TaskJournalCodec.decode(TaskJournalCodec.encode(exactGoal)))
    }

    @Test fun completionRequiresVerifiedOutputAndNoPendingEffect() {
        val completed = record.copy(phase = "completed", stage = "output_verified", inputDigest = input,
            expectedText = "items=2;quantity=5", outputDigest = output)
        assertEquals(completed, TaskJournalCodec.decode(TaskJournalCodec.encode(completed)))
        for (value in listOf(completed.copy(stage = "demo_verified"), completed.copy(pendingEffect = "publish"),
            completed.copy(inputDigest = ""), completed.copy(outputDigest = ""), completed.copy(expectedText = ""))) {
            assertThrows(IllegalArgumentException::class.java) { TaskJournalCodec.encode(value) }
        }
        assertEquals(record.copy(phase = "failed", inputDigest = input, pendingEffect = "read", code = "BAD_CSV"),
            TaskJournalCodec.decode(TaskJournalCodec.encode(record.copy(phase = "failed", inputDigest = input,
                pendingEffect = "read", code = "BAD_CSV"))))
    }

    @Test fun unicodeEscapesAndInvalidUtf8AreCountedOrRejected() {
        val escaped = record.copy(goal = "读取\"示例\"\n保留不确定结果")
        val bytes = TaskJournalCodec.encode(escaped)
        assertEquals(escaped, TaskJournalCodec.decode(bytes))
        assertTrue(bytes.size <= TaskJournalCodec.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { TaskJournalCodec.encode(record.copy(goal = "中".repeat(342))) }
        assertThrows(CharacterCodingException::class.java) { TaskJournalCodec.encode(record.copy(goal = "\ud800")) }
        assertThrows(CharacterCodingException::class.java) { TaskJournalCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { TaskJournalCodec.decode(ByteArray(4097)) }
    }
}
