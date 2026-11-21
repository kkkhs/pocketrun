package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test

class LinuxOperationGateTest {
    @Test fun releaseHappensBeforeCallbackAndAnOldCompletionCannotClearTheNewSlot() {
        val gate = LinuxOperationGate()
        val first = gate.begin()
        var second = 0L
        synchronized(gate.lock) {
            assertTrue(gate.complete(first))
            assertFalse(gate.busy)
            // A Native Promise resolve/reject may synchronously schedule the next operation.
            second = gate.begin()
        }
        assertTrue(gate.busy)
        assertFalse(gate.complete(first))
        assertTrue(gate.busy)
        assertTrue(gate.complete(second))
        assertFalse(gate.busy)
    }

    @Test fun runtimeRequiresAndroid14AndAnArm64AppProcess() {
        assertTrue(LinuxOperationGate.supportsRuntime(34, true, true))
        assertTrue(LinuxOperationGate.supportsRuntime(36, true, true))
        assertFalse(LinuxOperationGate.supportsRuntime(33, true, true))
        assertFalse(LinuxOperationGate.supportsRuntime(34, false, true))
        assertFalse(LinuxOperationGate.supportsRuntime(34, true, false))
    }

    @Test fun busyAndClosedRejectAndLateResultsCannotReopenTheGate() {
        val gate = LinuxOperationGate()
        val epoch = gate.begin()
        RootfsTarTest.expectFailure { gate.begin() }
        gate.close()
        assertFalse(gate.complete(epoch))
        assertTrue(gate.closed)
        RootfsTarTest.expectFailure { gate.begin() }
    }
}
