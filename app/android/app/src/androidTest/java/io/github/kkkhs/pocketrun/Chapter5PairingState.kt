package io.github.kkkhs.pocketrun

import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Test-only state: one successful pairing per actual instrumentation run and retained key store. */
internal class Chapter5PairingState {
    private var run: Any? = null
    private val successfulStores = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Synchronized fun once(currentRun: Any, storeIdentity: Any, pair: () -> Unit) {
        if (run !== currentRun) {
            successfulStores.clear()
            run = currentRun
        }
        if (successfulStores.contains(storeIdentity)) return
        pair() // Any failure leaves this store uncached; never treat a failed attempt as paired.
        successfulStores.add(storeIdentity)
    }
}

/** Pure JVM fixture-policy checks. These do not claim system TLS pairing/Native execution. */
class Chapter5PairingStateTest {
    @Test fun successfulPairingRunsOnceForTheSameRunAndStore() {
        val state = Chapter5PairingState()
        val run = Any()
        val store = Any()
        var calls = 0
        repeat(2) { state.once(run, store) { calls++ } }
        assertEquals(1, calls)
    }

    @Test fun failedPairingIsNotCachedAndCanReallyRetry() {
        val state = Chapter5PairingState()
        val run = Any()
        val store = Any()
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            state.once(run, store) { calls++; throw IllegalStateException("rejected pairing") }
        }
        state.once(run, store) { calls++ }
        state.once(run, store) { calls++ }
        assertEquals(2, calls)
    }

    @Test fun aNewInstrumentationRunCannotInheritSuccessfulPairing() {
        val state = Chapter5PairingState()
        val store = Any()
        var calls = 0
        state.once(Any(), store) { calls++ }
        state.once(Any(), store) { calls++ }
        assertEquals(2, calls)
    }

    @Test fun aDifferentStoreIdentityMustPairEvenWhenEqualByValue() {
        val state = Chapter5PairingState()
        val run = Any()
        val first = String(charArrayOf('k', 'e', 'y'))
        val second = String(charArrayOf('k', 'e', 'y'))
        var calls = 0
        state.once(run, first) { calls++ }
        state.once(run, second) { calls++ }
        assertEquals(2, calls)
    }
}
