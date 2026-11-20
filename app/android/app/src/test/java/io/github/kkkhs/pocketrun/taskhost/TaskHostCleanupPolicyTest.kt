package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TaskHostCleanupPolicyTest {
    @Test
    fun adapterCloseFailureRejectsNewWorkButRemainsRetryableUntilDisposed() {
        val coordinator = RetryableCloseCoordinator()
        var activeCloseCalls = 0
        var sessionCloseCalls = 0
        var workerShutdownCalls = 0
        try {
            coordinator.close {
                activeCloseCalls++
                throw IOException("injected active cleanup failure")
            }
            throw AssertionError("Expected close failure")
        } catch (_: IOException) {
            assertTrue(coordinator.closing)
            assertTrue(!coordinator.disposed)
        }
        coordinator.close {
            activeCloseCalls++
            sessionCloseCalls++
            workerShutdownCalls++
        }
        assertTrue(coordinator.closing)
        assertTrue(coordinator.disposed)
        assertEquals(2, activeCloseCalls)
        assertEquals(1, sessionCloseCalls)
        assertEquals(1, workerShutdownCalls)
        coordinator.close { throw AssertionError("Disposed close must be idempotent") }
    }

    @Test
    fun originalOperationMustBeReprovenBeforeRunRecovery() {
        val original = Any()
        var retried: Any? = null
        var recoverCalls = 0
        assertTrue(!LinuxCleanupPolicy.retryOriginal(original, { operation ->
            retried = operation
            false
        }, {
            recoverCalls++
            true
        }))
        assertSame(original, retried)
        assertEquals(0, recoverCalls)

        assertTrue(LinuxCleanupPolicy.retryOriginal(original, { operation ->
            assertSame(original, operation)
            true
        }, {
            recoverCalls++
            true
        }))
        assertEquals(1, recoverCalls)
        assertTrue(!LinuxCleanupPolicy.retryOriginal(null, { true }, { true }))
    }
}
