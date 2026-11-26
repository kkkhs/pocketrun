package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

class CompleteTaskContractTest {
    @Test fun readAndObservedDemoCannotBypassTheNativeCompletionGate() {
        val exercise = Exercise()
        exercise.contract.start()
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.finish() }
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.validate(exercise.publish) }
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.validate(exercise.text) }
        exercise.read()
        exercise.observe()
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.finish() }
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.validate(exercise.publish) }
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.validate(ParsedAction.DeviceInfo) }
        assertEquals("demo_observed", exercise.contract.snapshot().stage)
    }

    @Test fun acceptedInputNeedsASeparateObservationBeforePublication() {
        val exercise = Exercise()
        exercise.read()
        exercise.observe()
        exercise.settle(exercise.tap, emptyMap()) { exercise.demo = exercise.demo.copy(focused = true) }
        assertThrows(IllegalArgumentException::class.java) {
            exercise.contract.validate(exercise.text.copy(text = "items=2;quantity=6"))
        }
        exercise.settle(exercise.text, emptyMap()) { exercise.demo = exercise.withText(exercise.report.text) }
        assertEquals("text_accepted", exercise.contract.snapshot().stage)
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.validate(exercise.publish) }
        exercise.observe()
        assertEquals("demo_verified", exercise.contract.snapshot().stage)
        exercise.settle(exercise.publish, exercise.publication("published")) {
            exercise.result = exercise.report.fileText.toByteArray(Charsets.UTF_8)
        }
        exercise.contract.finish()
        assertEquals("completed", exercise.contract.snapshot().phase)
        assertEquals("output_verified", exercise.saved.last().stage)
        assertEquals("none", exercise.saved.last().pendingEffect)
    }

    @Test fun changedInputTargetTapOrFocusStopsBeforeAnEffect() {
        val exercise = Exercise()
        exercise.read()
        exercise.observe()
        exercise.workspace = exercise.workspace.copy(inputDigest = "f".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.guard(exercise.tap) }
        exercise.workspace = exercise.workspace.copy(inputDigest = exercise.report.inputDigest)
        assertThrows(IllegalArgumentException::class.java) {
            exercise.contract.guard(exercise.tap.copy(expectedDisplayEpoch = 4))
        }
        exercise.demo = exercise.demo.copy(target = exercise.target.copy(displayEpoch = 4))
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.guard(exercise.tap) }
        exercise.demo = exercise.demo.copy(target = exercise.target)
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.guard(exercise.tap.copy(x = 1)) }
        exercise.settle(exercise.tap, emptyMap())
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.guard(exercise.text) }
        exercise.demo = exercise.demo.copy(focused = true)
        assertFalse(exercise.contract.guard(exercise.text))
        exercise.demo = exercise.withText("items=2")
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.guard(exercise.text) }
    }

    @Test fun exactExistingInputAndOutputAreObservedWithoutClaimingNewEffects() {
        val exercise = Exercise()
        exercise.read()
        exercise.observe()
        exercise.demo = exercise.withText(exercise.report.text)
        assertTrue(exercise.contract.guard(exercise.tap))
        exercise.settle(exercise.tap, OwnedDemoObservation.fields(exercise.demo) + ("effect" to "already_present"))
        assertEquals("demo_verified", exercise.contract.snapshot().stage)
        exercise.result = exercise.report.fileText.toByteArray(Charsets.UTF_8)
        exercise.settle(exercise.publish, exercise.publication("already_present"))
        assertEquals("OUTPUT_ALREADY_PRESENT", exercise.contract.snapshot().code)
        exercise.contract.finish()
        assertEquals("completed", exercise.contract.snapshot().phase)
        assertEquals("OUTPUT_ALREADY_PRESENT", exercise.contract.snapshot().code)
    }

    @Test fun finishRechecksTheActualInputDemoAndOutput() {
        val exercise = Exercise()
        exercise.read()
        exercise.demo = exercise.withText(exercise.report.text)
        exercise.observe()
        exercise.settle(exercise.publish, exercise.publication("published")) {
            exercise.result = exercise.report.fileText.toByteArray(Charsets.UTF_8)
        }
        exercise.workspace = exercise.workspace.copy(inputDigest = "f".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.finish() }
        exercise.workspace = exercise.workspace.copy(inputDigest = exercise.report.inputDigest)
        exercise.demo = exercise.withText("items=2")
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.finish() }
        exercise.demo = exercise.withText(exercise.report.text)
        exercise.result = null
        assertThrows(IllegalStateException::class.java) { exercise.contract.finish() }
        exercise.result = "other user data\n".toByteArray(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.finish() }
        assertNotEquals("completed", exercise.contract.snapshot().phase)
    }

    @Test fun inputChangedDuringFinalObservationCannotCommitCompletion() {
        val exercise = Exercise()
        exercise.read()
        exercise.demo = exercise.withText(exercise.report.text)
        exercise.observe()
        exercise.settle(exercise.publish, exercise.publication("published")) {
            exercise.result = exercise.report.fileText.toByteArray(Charsets.UTF_8)
        }
        exercise.observeHook = { exercise.workspace = exercise.workspace.copy(inputDigest = "f".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.finish() }
        assertNotEquals("completed", exercise.contract.snapshot().phase)
    }

    @Test fun journalFailureKeepsUncertainIntentRatherThanConfirmingAnEffect() {
        val exercise = Exercise()
        exercise.read()
        exercise.observe()
        exercise.contract.beforeStart(exercise.tap)
        exercise.failPersist = true
        assertThrows(IllegalStateException::class.java) {
            exercise.contract.accept(exercise.tap, ToolResult(emptyMap()))
        }
        assertEquals("demo_observed", exercise.contract.snapshot().stage)
        assertEquals("tap", exercise.contract.snapshot().pendingEffect)
        exercise.failPersist = false
        exercise.contract.terminal("CLEANUP_UNPROVEN", cancelled = true)
        assertEquals("cancelled", exercise.contract.snapshot().phase)
        assertEquals("tap", exercise.contract.snapshot().pendingEffect)
        assertTrue(exercise.contract.blocksOrdinaryTask())
        assertThrows(IllegalArgumentException::class.java) { exercise.contract.guard(exercise.tap) }
    }

    private class Exercise {
        val csv = "name,quantity\napple,2\nbanana,3\n"
        val report = DemoCsvReport.parse(csv.toByteArray(Charsets.UTF_8))
        val target = DisplayTarget(3, 7, 10001, true)
        var workspace = WorkspaceTarget(10001, Paths.get("/private/demo"), report.inputDigest)
        var demo = DemoObservation(target, "", "input: ", 10, 20, 200, 80, false)
        var result: ByteArray? = null
        val saved = mutableListOf<TaskJournalRecord>()
        var failPersist = false
        var observeHook: () -> Unit = {}
        val contract = CompleteTaskContract(TaskJournalRecord(TaskJournalCodec.newRunId(), "Complete the demo report"),
            target, { workspace }, { observeHook(); demo }, { result }) {
            check(!failPersist) { "test journal write failed" }
            saved += it
        }
        init { contract.start() }
        val observe = ParsedAction.Display.ObserveDemo(target.displayId, target.displayEpoch)
        val tap = ParsedAction.Display.DemoTap(target.displayId, target.displayEpoch, demo.x, demo.y)
        val text = ParsedAction.Display.DemoText(target.displayId, target.displayEpoch, report.text)
        val publish = ParsedAction.PublishDemoResult(report.text, report.inputDigest)

        fun read() = settle(ParsedAction.ReadDemoCsv, mapOf("csvText" to csv, "inputDigest" to report.inputDigest))
        fun observe() = settle(observe, OwnedDemoObservation.fields(demo))
        fun withText(value: String) = demo.copy(value = value, resultValue = "input: $value")
        fun publication(status: String) = mapOf("publicationStatus" to status,
            "outputDigest" to DemoCsvReport.digest(report.fileText.toByteArray(Charsets.UTF_8)))
        fun settle(action: ParsedAction, fields: Map<String, String>, effect: () -> Unit = {}) {
            contract.beforeStart(action)
            effect()
            contract.accept(action, ToolResult(fields))
        }
    }
}
