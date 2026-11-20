package io.github.kkkhs.pocketrun

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.HandlerThread
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test-only observer acquisition. A null/throw from Instrumentation must not orphan its callback. */
internal class OwnedInstrumentationObserver(
    private val instrumentation: Instrumentation,
    private val flags: Int,
) {
    private val callbacks = OwnedConnectThreads("PocketRun-ProductTest-UiOwner")
    private val closed = AtomicBoolean(false)
    private val automation = AtomicReference<UiAutomation?>()
    private var worker: Thread? = null

    fun acquire(): UiAutomation {
        check(worker == null && !closed.get())
        val task = FutureTask<UiAutomation?> {
            val before = callbacks.snapshot()
            check(before.complete) { "Observer ownership enumeration is incomplete." }
            var local: UiAutomation? = null
            try {
                local = instrumentation.getUiAutomation(flags)
                local
            } finally {
                val after = callbacks.snapshot()
                fun expected(thread: Thread) = thread is HandlerThread && thread.name == "UiAutomation" &&
                    thread.looper?.thread === thread
                callbacks.capture(before, after, ::expected)
                val created = after.threads.filter { thread -> before.threads.none { it === thread } }
                val newlyOwned = after.complete && created.size == 1 && expected(created.single())
                // A cached observer from another test/run is NOT ours to disconnect. Publish the
                // UiAutomation handle only after this call's fresh callback identity is proven.
                if (newlyOwned) automation.set(local)
                if (closed.get()) disconnectAndDrain(if (newlyOwned) local else null)
                check(newlyOwned) { "No unambiguous newly-owned observer; preserve the original owner." }
            }
        }
        worker = Thread(callbacks.group, task, "PocketRun-ProductTest-UiAcquire").apply {
            isDaemon = true
            start()
        }
        return checkNotNull(task.get(20, TimeUnit.SECONDS)) { "Instrumentation observer connection failed." }
    }

    /** No global service lookup, hidden fields, name-only cleanup, or takeover of another owner. */
    fun closeAndConfirm(): Boolean {
        closed.set(true)
        worker?.interrupt()
        disconnectAndDrain(automation.getAndSet(null))
        var interrupted = Thread.interrupted()
        try {
            try { worker?.join(700) }
            catch (_: InterruptedException) { interrupted = true }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        val drained = disconnectAndDrain(automation.getAndSet(null))
        return worker?.isAlive != true && drained
    }

    private fun disconnectAndDrain(instance: UiAutomation?): Boolean {
        if (instance != null) {
            // Exactly the object obtained by this test. CONNECTING can reject disconnect;
            // public Looper cleanup of this acquisition's captured identity is still required.
            runCatching { instance.javaClass.getMethod("disconnect").invoke(instance) }
        }
        return callbacks.stopAndJoin(700) { thread -> (thread as HandlerThread).looper?.quit() }
    }
}
