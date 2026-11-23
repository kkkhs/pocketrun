package io.github.kkkhs.pocketrun

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

/** A private, serialized connect scope; never infer ownership from a process-wide thread name. */
internal class OwnedConnectThreads(
    name: String,
    private val maxSnapshotThreads: Int = 1024,
    private val enumerate: (ThreadGroup, Array<Thread?>) -> Int = { group, threads -> group.enumerate(threads, false) },
) {
    init { require(maxSnapshotThreads in 1..1024) }
    data class Snapshot(val threads: Set<Thread>, val complete: Boolean)
    // Allocate only when the first worker is created; detach from the parent after the last
    // owned thread exits, rather than retaining a ThreadGroup for every released display.
    @Suppress("DEPRECATION")
    val group: ThreadGroup by lazy { ThreadGroup(name).apply { isDaemon = true } }
    private val owned = Collections.newSetFromMap(IdentityHashMap<Thread, Boolean>())
    private val uncertain = Collections.newSetFromMap(IdentityHashMap<Thread, Boolean>())

    private var incompleteEnumeration = false

    @Synchronized fun snapshot(): Snapshot {
        var capacity = minOf(16, maxSnapshotThreads)
        while (true) {
            val buffer = arrayOfNulls<Thread>(capacity)
            val count = enumerate(group, buffer)
            val threads = buffer.filterNotNull().filterTo(
                Collections.newSetFromMap(IdentityHashMap<Thread, Boolean>())) { it.threadGroup === group }
            // activeCount is only an estimate. A full array is NEVER evidence of a complete scan.
            if (count in 0 until capacity) return Snapshot(threads, true)
            if (capacity == maxSnapshotThreads) {
                uncertain.addAll(threads)
                incompleteEnumeration = true // Retain the scope; do not bind/replace an unknown owner.
                return Snapshot(threads, false)
            }
            capacity = minOf(capacity * 2, maxSnapshotThreads)
        }
    }

    @Synchronized fun requireDrained() {
        owned.removeAll { !it.isAlive }
        uncertain.removeAll { !it.isAlive }
        check(!incompleteEnumeration && owned.isEmpty() && uncertain.isEmpty()) {
            "Previous owned connect threads have not exited or enumeration was incomplete."
        }
    }

    @Synchronized fun capture(before: Snapshot, after: Snapshot, expected: (Thread) -> Boolean) {
        check(Thread.currentThread().threadGroup === group) { "Connect must run in its private thread group." }
        if (!before.complete || !after.complete) incompleteEnumeration = true
        val created = after.threads.filter { thread ->
            thread.threadGroup === group && before.threads.none { it === thread }
        }
        // Android 15/16 create exactly one HandlerThread synchronously in connectWithTimeout.
        // A changed/ambiguous implementation is retained and blocks retry, never killed by name.
        if (before.complete && after.complete && created.size == 1 && expected(created.single())) owned.add(created.single())
        else uncertain.addAll(created)
    }

    /** Keep identities until actual exit, including interrupted/bounded cleanup and unknown threads. */
    fun stopAndJoin(timeoutMillis: Long, stop: (Thread) -> Unit): Boolean {
        val threads = synchronized(this) { owned.toList() }
        threads.forEach(stop)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = Thread.interrupted()
        try {
            for (thread in threads) {
                if (thread === Thread.currentThread()) continue
                while (thread.isAlive) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    try { TimeUnit.NANOSECONDS.timedJoin(thread, remaining) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return synchronized(this) {
            owned.removeAll { !it.isAlive }
            uncertain.removeAll { !it.isAlive }
            !incompleteEnumeration && owned.isEmpty() && uncertain.isEmpty()
        }
    }
}
