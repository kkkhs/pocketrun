package io.github.kkkhs.pocketrun

import android.app.Instrumentation
import android.app.UiAutomation
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Test-only observer acquisition. A null/throw from Instrumentation must not orphan its callback. */
@Suppress("DEPRECATION") // Thread.getId is public on the selected API35 device.
internal class OwnedInstrumentationObserver(
    private val instrumentation: Instrumentation,
    private val flags: Int,
) {
    private val callbacks = OwnedConnectThreads("PocketRun-ProductTest-UiOwner")
    private val closed = AtomicBoolean(false)
    private val automation = AtomicReference<UiAutomation?>()
    private val sourcePid = Process.myPid()
    private val sourceStartTick = runCatching {
        val stat = File("/proc/self/stat").readText()
        stat.substring(stat.lastIndexOf(')') + 1).trim().split(Regex("\\s+"))[19].toLong().also { check(it > 0) }
    }.getOrNull()
    private var worker: Thread? = null

    fun acquire(): UiAutomation {
        check(worker == null && !closed.get())
        diagnostic("observer-create")
        val task = FutureTask<UiAutomation?> {
            val before = try { callbacks.snapshot() } catch (error: Throwable) {
                diagnoseFailure("observer-snapshot-before-threw", error)
                throw error
            }
            if (!before.complete) {
                val error = OwnedCallbackBindUnknown()
                diagnoseFailure("observer-snapshot-before-incomplete", error)
                throw error
            }
            var local: UiAutomation? = null
            var primary: Throwable? = null
            var bindingFailure: Throwable? = null
            diagnostic("get-ui-automation-start")
            try {
                local = instrumentation.getUiAutomation(flags)
                diagnostic(if (local == null) "get-ui-automation-returned-null" else "get-ui-automation-returned-instance",
                    if (local == null) ObserverReturnedNull() else null)
            } catch (error: Throwable) {
                primary = error
                diagnoseFailure("get-ui-automation-threw", error)
            } finally {
                try {
                    val after = callbacks.snapshot()
                    fun expected(thread: Thread) = thread is HandlerThread && thread.name == "UiAutomation" &&
                        thread.looper?.thread === thread
                    callbacks.capture(before, after, ::expected)
                    val created = after.threads.filter { thread -> before.threads.none { it === thread } }
                    val newlyOwned = after.complete && created.size == 1 && expected(created.single())
                    val receipt = created.joinToString(";") { thread ->
                        val looper = (thread as? HandlerThread)?.looper
                        "kind=${if (thread.name == "UiAutomation") "frameworkUiAutomation" else "other"}," +
                            "javaThreadId=${thread.id},objectIdentity=${System.identityHashCode(thread)}," +
                            "groupIdentity=${System.identityHashCode(thread.threadGroup)}," +
                            "sameGroup=${thread.threadGroup === callbacks.group},alive=${thread.isAlive}," +
                            "looperIdentity=${System.identityHashCode(looper)}," +
                            "looperThreadIdentity=${System.identityHashCode(looper?.thread)}"
                    }
                    diagnostic("observer-owned-receipt", extra = "beforeComplete=${before.complete} " +
                        "afterComplete=${after.complete} newThreadCount=${created.size} newlyOwned=$newlyOwned " +
                        "observerOwnedReceipt=[$receipt]")
                    // Cached observers are not ours. Only publish a handle after fresh identity proof.
                    if (newlyOwned) automation.set(local)
                    else bindingFailure = OwnedCallbackBindUnknown()
                    if (closed.get()) diagnostic("late-observer-framework-owned", extra = "frameworkCleanup=runner-finish-pending")
                } catch (error: Throwable) {
                    bindingFailure = OwnedCallbackBindUnknown(error)
                    diagnoseFailure("callback-bind-or-late-cleanup-threw", error)
                }
            }
            // Preserve the real getUiAutomation exception; a finally/binding failure must not mask it.
            bindingFailure?.let { diagnoseFailure("callback-bind-unknown", it) }
            primary?.let { throw it }
            bindingFailure?.let { throw it }
            local ?: throw ObserverReturnedNull()
        }
        worker = Thread(callbacks.group, task, "PocketRun-ProductTest-UiAcquire").apply {
            isDaemon = true
            start()
        }
        try {
            return checkNotNull(task.get(20, TimeUnit.SECONDS))
        } catch (error: Throwable) {
            diagnoseFailure("observer-future-wait-threw", error)
            throw error
        }
    }

    /** Stop only our acquisition worker; Instrumentation.finish owns observer/callback teardown. */
    fun closeAndConfirmWorker(): Boolean {
        closed.set(true)
        diagnostic("cleanup-start", extra = "instanceHandle=${automation.get() != null}")
        worker?.interrupt()
        var interrupted = Thread.interrupted()
        try {
            try { worker?.join(700) }
            catch (_: InterruptedException) { interrupted = true }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        automation.set(null)
        val workerExited = worker?.isAlive != true
        diagnostic("cleanup-result", extra = "workerExited=$workerExited frameworkCleanup=runner-finish-pending " +
            "privateInstrumentationState=untouched")
        return workerExited
    }

    fun diagnoseFailure(stage: String, error: Throwable) = diagnostic(stage, error)

    private fun diagnostic(stage: String, error: Throwable? = null, extra: String = "") {
        val chain = mutableListOf<Throwable>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var next = error
        while (next != null && chain.size < 12 && seen.add(next)) { chain += next; next = next.cause }
        val category = when {
            chain.any { it is IllegalStateException && it.message?.let { message ->
                message.startsWith("UiAutomationService ") && message.endsWith("already registered!")
            } == true } -> "connect-rejected-existing-service"
            chain.any { it is LinkageError } -> "native-linkage"
            chain.any { it is TimeoutException } -> "timeout"
            chain.any { it is OwnedCallbackBindUnknown } -> "owned-callback-bind-unknown"
            chain.any { it is ObserverReturnedNull } -> "observer-returned-null"
            chain.any { it is IllegalStateException && it.message?.startsWith("Cannot call disconnect() while connecting ") == true }
                -> "disconnect-while-connecting"
            chain.any { it is SecurityException } -> "security-exception"
            error == null -> "none"
            else -> "unclassified"
        }
        // Messages are only inspected for exact known classifications above, NEVER emitted.
        Log.i("PocketRun-ObserverDiag", "stage=$stage category=$category " +
            "causeClasses=[${chain.joinToString(">") { it.javaClass.name }}] causeChainIncomplete=${next != null} " +
            "sourceUID=${Process.myUid()} sourcePID=$sourcePid sourceStartTick=${sourceStartTick ?: "unreadable"} " +
            "requestedFlags=$flags groupIdentity=${System.identityHashCode(callbacks.group)} $extra")
    }

    private class ObserverReturnedNull : IllegalStateException("Instrumentation returned no observer instance.")
    private class OwnedCallbackBindUnknown(cause: Throwable? = null) :
        IllegalStateException("Fresh callback ownership is unknown; preserve the original owner.", cause)
}
