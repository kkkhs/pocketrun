package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskHostBackendDeathTest {
    private val target = DisplayTarget(7L, 11, 10469, true)

    @Test
    fun bindsOnlyCurrentPendingOrExecutingNonceAndEpoch() {
        val pending = status(HostPhase.PENDING_APPROVAL, "nonce-1", target.backendEpoch())
        assertEquals(BackendDeathBinding("nonce-1", target.backendEpoch()),
            TaskHostBackendDeath.binding(pending, target))
        val executing = status(HostPhase.EXECUTING, "nonce-2", target.backendEpoch())
        assertEquals("nonce-2", TaskHostBackendDeath.binding(executing, target)?.requestNonce)
    }

    @Test
    fun staleTargetOrTerminalStateCannotInvalidateAnotherRun() {
        val next = status(HostPhase.PENDING_APPROVAL, "nonce-next",
            DisplayTarget(8L, 12, 10469, true).backendEpoch())
        assertNull(TaskHostBackendDeath.binding(next, target))
        assertNull(TaskHostBackendDeath.binding(
            status(HostPhase.SUCCEEDED, "nonce-1", target.backendEpoch()), target))
        assertNull(TaskHostBackendDeath.binding(
            status(HostPhase.PENDING_APPROVAL, null, target.backendEpoch()), target))
    }

    private fun status(phase: HostPhase, nonce: String?, epoch: String) = HostStatus(
        phase = phase,
        tool = "android.virtual_display_observe_or_demo_input",
        requestNonce = nonce,
        backendEpoch = epoch,
    )
}
