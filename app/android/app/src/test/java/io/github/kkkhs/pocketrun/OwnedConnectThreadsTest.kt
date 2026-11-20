package io.github.kkkhs.pocketrun

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedConnectThreadsTest {
    @Test fun rejectedConnectCapturesItsNewIdentityAndLeavesSameNameOwnersAlone() {
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("owned-connect")
            val existing = fixture.start(scope.group)
            val foreign = fixture.start(ThreadGroup("other-owner"))
            lateinit var callback: Thread
            inGroup(scope) {
                val before = scope.snapshot()
                try {
                    callback = fixture.start(scope.group)
                    throw IllegalStateException("already registered")
                } catch (_: IllegalStateException) {
                    // Model only the source failure boundary; this is not Android/Native evidence.
                } finally {
                    scope.capture(before, scope.snapshot()) { it.name == "UiAutomation" }
                }
            }
            val stopped = mutableListOf<Thread>()
            assertTrue(scope.stopAndJoin(1000) { stopped += it; fixture.release(it) })
            assertEquals(1, stopped.size)
            assertTrue(stopped.single() === callback)
            assertFalse(callback.isAlive)
            assertTrue(existing.isAlive && foreign.isAlive)
            scope.requireDrained()
        }
    }

    @Test fun retryDoesNotOverwriteAnOwnerThatFailedToExit() {
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("bounded-cleanup")
            fixture.start(scope.group, "owned-query-worker") // Like the live executor worker across retries.
            inGroup(scope) {
                val before = scope.snapshot()
                fixture.start(scope.group)
                scope.capture(before, scope.snapshot()) { true }
            }
            assertFalse(scope.stopAndJoin(5) { /* Simulated quit which has not exited yet. */ })
            assertThrows(IllegalStateException::class.java) { scope.requireDrained() }
            assertTrue(scope.stopAndJoin(1000, fixture::release))
            scope.requireDrained()
            inGroup(scope) {
                val before = scope.snapshot()
                fixture.start(scope.group)
                scope.capture(before, scope.snapshot()) { true }
            }
            assertTrue(scope.stopAndJoin(1000, fixture::release))
            scope.requireDrained()
        }
    }

    @Test fun ambiguousOrChangedFrameworkThreadsAreRetainedButNeverStopped() {
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("ambiguous-connect")
            inGroup(scope) {
                val before = scope.snapshot()
                fixture.start(scope.group)
                fixture.start(scope.group)
                scope.capture(before, scope.snapshot()) { it.name == "UiAutomation" }
            }
            var stopped = 0
            assertFalse(scope.stopAndJoin(5) { stopped++ })
            assertEquals(0, stopped)
            assertThrows(IllegalStateException::class.java) { scope.requireDrained() }
            fixture.close()
            scope.requireDrained()
        }
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("changed-framework")
            inGroup(scope) {
                val before = scope.snapshot()
                fixture.start(scope.group, "unknown-framework-thread")
                scope.capture(before, scope.snapshot()) { it.name == "UiAutomation" }
            }
            assertFalse(scope.stopAndJoin(5) { throw AssertionError("Unknown identity must not be stopped") })
            assertThrows(IllegalStateException::class.java) { scope.requireDrained() }
        }
    }

    @Test fun interruptedCleanupStillJoinsAndRestoresInterrupt() {
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("interrupted-cleanup")
            inGroup(scope) {
                val before = scope.snapshot()
                fixture.start(scope.group)
                scope.capture(before, scope.snapshot()) { true }
            }
            Thread.currentThread().interrupt()
            try {
                assertTrue(scope.stopAndJoin(1000, fixture::release))
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            scope.requireDrained()
        }
    }

    @Test fun captureRequiresTheActualPrivateConnectCaller() {
        val scope = OwnedConnectThreads("wrong-caller")
        val empty = OwnedConnectThreads.Snapshot(emptySet(), true)
        assertThrows(IllegalStateException::class.java) { scope.capture(empty, empty) { true } }
    }

    @Test fun privateGroupEnumerationGrowsAndNeverIncludesDescendantsOrForeignGroups() {
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("enumeration-grow", maxSnapshotThreads = 32)
            val direct = (1..20).map { fixture.start(scope.group) }
            val child = fixture.start(ThreadGroup(scope.group, "child-owner"))
            val foreign = fixture.start(ThreadGroup("foreign-owner"))
            val snapshot = scope.snapshot()
            assertTrue(snapshot.complete)
            assertEquals(20, snapshot.threads.size)
            assertTrue(direct.all { expected -> snapshot.threads.any { it === expected } })
            assertFalse(snapshot.threads.any { it === child || it === foreign })
        }
    }

    @Test fun fullBoundIsUnknownNotFalseEmptyAndCannotForgetTheOwner() {
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("enumeration-full", maxSnapshotThreads = 32)
            val ownerGroup = scope.group
            repeat(33) { fixture.start(scope.group) }
            val snapshot = scope.snapshot()
            assertFalse(snapshot.complete)
            assertEquals(32, snapshot.threads.size)
            assertTrue(snapshot.threads.all { it.threadGroup === ownerGroup })
            assertThrows(IllegalStateException::class.java) { scope.requireDrained() }
            assertFalse(scope.stopAndJoin(5) { throw AssertionError("An incomplete scan cannot own identities") })
            fixture.close()
            // Even an apparently empty later observation cannot silently clear the quarantine.
            assertThrows(IllegalStateException::class.java) { scope.requireDrained() }
        }
    }

    @Test fun incompletePostConnectEnumerationCannotBindEvenOneVisibleCallback() {
        Fixture().use { fixture ->
            val scope = OwnedConnectThreads("enumeration-after", maxSnapshotThreads = 2)
            inGroup(scope) {
                val before = scope.snapshot()
                assertTrue(before.complete)
                fixture.start(scope.group)
                val after = scope.snapshot()
                assertFalse(after.complete) // Caller + callback exactly fill the bounded array.
                scope.capture(before, after) { it.name == "UiAutomation" }
            }
            assertFalse(scope.stopAndJoin(5) { throw AssertionError("Partial identity must not be killed") })
            assertThrows(IllegalStateException::class.java) { scope.requireDrained() }
        }
    }

    @Test fun sourceUsesPublicLooperCleanupAndNativeChecksIncludeFrameworkThread() {
        val root = File(System.getProperty("pocketrun.sourceRoot") ?: ".")
        val source = root.resolve("src/main/java/io/github/kkkhs/pocketrun/DisplayAutomation.kt").readText()
        val native = root.resolve("src/androidTest/java/io/github/kkkhs/pocketrun/Chapter5DittoIntegrationTest.kt").readText()
        val helper = root.resolve("src/main/java/io/github/kkkhs/pocketrun/OwnedConnectThreads.kt").readText()
        assertTrue(helper.contains("group.enumerate(threads, false)"))
        assertFalse(helper.contains("getAllStackTraces"))
        assertTrue(native.contains("PAIRING_STATE.once(instrumentation, storeIdentity)"))
        assertTrue(native.contains("ownedAutomation.javaClass.getMethod(\"disconnect\").invoke(ownedAutomation)"))
        assertTrue(source.contains("Thread(callbacks.group, task"))
        assertTrue(source.contains("it.name == \"connectWithTimeout\""))
        assertTrue(source.contains("thread.looper?.thread === thread"))
        assertTrue(source.contains("callbacks.stopAndJoin"))
        assertTrue(source.contains("looper?.quit()"))
        assertFalse(source.contains("getDeclaredField"))
        assertFalse(source.contains("getMethod(\"destroy\")"))
        assertTrue(native.contains("\"UiAutomation\", \"PocketRun-UiClose-\""))
        assertTrue(native.contains("failedRegistrationReleasesCallbackThreadsAndRetries"))
        assertTrue(native.contains("already registered!"))
        assertTrue(native.contains("ownerThreads.all { it in stillOwned }"))
    }

    private fun inGroup(scope: OwnedConnectThreads, action: () -> Unit) {
        val task = FutureTask { action() }
        val thread = Thread(scope.group, task, "owned-query")
        thread.start()
        try { task.get(2, TimeUnit.SECONDS) }
        finally { thread.join(2000); assertFalse(thread.isAlive) }
    }

    private class Fixture : AutoCloseable {
        private val threads = mutableMapOf<Thread, CountDownLatch>()
        fun start(group: ThreadGroup, name: String = "UiAutomation"): Thread {
            val stop = CountDownLatch(1)
            val started = CountDownLatch(1)
            return Thread(group, { started.countDown(); stop.await() }, name).also {
                threads[it] = stop
                it.start()
                assertTrue(started.await(1, TimeUnit.SECONDS))
            }
        }
        fun release(thread: Thread) { checkNotNull(threads[thread]).countDown() }
        override fun close() {
            threads.values.forEach { it.countDown() }
            threads.keys.forEach { it.join(2000); assertFalse(it.isAlive) }
        }
    }
}
