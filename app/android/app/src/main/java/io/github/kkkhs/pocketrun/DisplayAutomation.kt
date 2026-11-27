package io.github.kkkhs.pocketrun

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Every instance belongs to exactly one virtual display session. No active-window fallback.
internal class DisplayAutomation(private val displayId: Int) : Closeable {
    private val closed = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val active = AtomicReference<Query?>()
    private val connection = AtomicReference<Any?>()
    private val automation = AtomicReference<Any?>()
    private val eventThread = AtomicReference<HandlerThread?>()
    private val cleanupLock = Any()
    private val callbacks = OwnedConnectThreads("PocketRun-UiOwner-$displayId")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(callbacks.group, task, "PocketRun-UiQuery-$displayId").apply { isDaemon = true }
    }

    // Called with the session lifecycle lock held; never connects or waits here.
    fun beginQuery(): Query {
        check(!closed.get()) { "虚拟屏 UI 查询已关闭。" }
        if (!busy.compareAndSet(false, true)) return Query().apply { complete(status(UiTreeCodec.Status.Busy)) }
        val query = Query()
        active.set(query)
        try {
            worker.execute {
                try {
                    query.checkDeadline()
                    val tree = collect(query, getAutomation(query))
                    query.checkDeadline()
                    if (!closed.get()) query.complete(tree)
                } catch (_: DeadlineExceeded) {
                    query.complete(status(UiTreeCodec.Status.Timeout))
                } catch (_: Exception) {
                    query.complete(status(if (query.expired()) UiTreeCodec.Status.Timeout else UiTreeCodec.Status.QueryFailed))
                } finally {
                    query.complete(status(UiTreeCodec.Status.QueryFailed))
                    active.compareAndSet(query, null)
                    busy.set(false)
                    if (closed.get()) disconnectAndQuit()
                }
            }
        } catch (_: Exception) {
            active.compareAndSet(query, null)
            busy.set(false)
            query.complete(status(UiTreeCodec.Status.QueryFailed))
        }
        return query
    }

    /** Injects UTF-8 / Chinese text into the focused editable node on this owned virtual display only. */
    fun setFocusedText(text: String): Boolean {
        if (closed.get() || !DisplayInputPolicy.acceptsAppText(text)) return false
        if (!busy.compareAndSet(false, true)) return false
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val query = Query()
        active.set(query)
        try {
            worker.execute {
                try {
                    query.checkDeadline()
                    val instance = getAutomation(query)
                    query.checkDeadline()
                    val all = instance.javaClass.getMethod("getWindowsOnAllDisplays").invoke(instance) as? SparseArray<*>
                        ?: return@execute
                    try {
                        val windows = all.get(displayId) as? List<*> ?: return@execute
                        var target: AccessibilityNodeInfo? = null
                        fun findTarget(node: AccessibilityNodeInfo, depth: Int) {
                            if (depth > UiTreeCodec.MAX_DEPTH) return
                            query.checkDeadline()
                            if (node.isEditable && node.isFocused) {
                                target?.let { @Suppress("DEPRECATION") it.recycle() }
                                target = AccessibilityNodeInfo.obtain(node)
                                return
                            }
                            if (target == null && node.isEditable && node.isEnabled) {
                                target = AccessibilityNodeInfo.obtain(node)
                            }
                            for (i in 0 until node.childCount) {
                                val child = node.getChild(i) ?: continue
                                try {
                                    findTarget(child, depth + 1)
                                } finally {
                                    @Suppress("DEPRECATION")
                                    child.recycle()
                                }
                                if (target?.isFocused == true) return
                            }
                        }
                        for (value in windows) {
                            val window = value as? AccessibilityWindowInfo ?: continue
                            val root = window.root ?: continue
                            try {
                                findTarget(root, 0)
                            } finally {
                                @Suppress("DEPRECATION")
                                root.recycle()
                            }
                            if (target?.isFocused == true) break
                        }
                        val chosen = target
                        if (chosen != null) {
                            try {
                                val args = Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                                }
                                ok.set(chosen.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                            } finally {
                                @Suppress("DEPRECATION")
                                chosen.recycle()
                            }
                        }
                    } finally {
                        for (index in 0 until all.size()) {
                            (all.valueAt(index) as? List<*>)?.forEach { value ->
                                @Suppress("DEPRECATION")
                                (value as? AccessibilityWindowInfo)?.recycle()
                            }
                        }
                    }
                } catch (_: Exception) {
                    ok.set(false)
                } finally {
                    active.compareAndSet(query, null)
                    busy.set(false)
                    done.countDown()
                    if (closed.get()) disconnectAndQuit()
                }
            }
            done.await(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            active.compareAndSet(query, null)
            busy.set(false)
        }
        return ok.get()
    }

    inner class Query internal constructor() {
        private val deadline = SystemClock.elapsedRealtime() + QUERY_TIMEOUT_MS
        private val done = CountDownLatch(1)
        private val result = AtomicReference<ByteArray?>()
        private val cancelled = AtomicBoolean(false)

        fun await(): ByteArray {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            try {
                if (!done.await(remaining, TimeUnit.MILLISECONDS)) {
                    cancelled.set(true)
                    return UiTreeCodec.encode(status(UiTreeCodec.Status.Timeout))
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                cancelled.set(true)
                return UiTreeCodec.encode(status(UiTreeCodec.Status.QueryFailed))
            }
            // A released session or a late result can never be reported as Available.
            return if (closed.get()) UiTreeCodec.encode(status(UiTreeCodec.Status.QueryFailed))
                else if (expired()) UiTreeCodec.encode(status(UiTreeCodec.Status.Timeout))
                else result.get() ?: UiTreeCodec.encode(status(UiTreeCodec.Status.QueryFailed))
        }

        internal fun complete(tree: UiTreeCodec.Tree) {
            if (result.get() != null) return
            // Encode inside the worker's error boundary, not on the waiting Binder caller.
            val encoded = UiTreeCodec.encode(tree)
            if (result.compareAndSet(null, encoded)) done.countDown()
        }

        internal fun cancel() {
            cancelled.set(true)
            complete(status(UiTreeCodec.Status.QueryFailed))
        }

        internal fun expired() = SystemClock.elapsedRealtime() >= deadline
        internal fun checkDeadline() {
            if (expired()) throw DeadlineExceeded()
            check(!cancelled.get() && !closed.get() && !Thread.currentThread().isInterrupted) {
                "UI 查询已取消。"
            }
        }
    }

    private fun getAutomation(query: Query): Any {
        automation.get()?.let { return it }
        query.checkDeadline()
        callbacks.requireDrained()
        val events = HandlerThread("PocketRun-UiEvents-$displayId")
        var localPeer: Any? = null
        var localInstance: Any? = null
        eventThread.set(events)
        try {
            events.start()
            query.checkDeadline()
            val connectionClass = Class.forName("android.app.UiAutomationConnection")
            val peer = connectionClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            localPeer = peer
            connection.set(peer)
            query.checkDeadline()
            val api = Class.forName("android.app.UiAutomation")
            val peerApi = Class.forName("android.app.IUiAutomationConnection")
            val instance = api.getDeclaredConstructor(Looper::class.java, peerApi)
                .apply { isAccessible = true }.newInstance(events.looper, peer)
            localInstance = instance
            automation.set(instance)
            query.checkDeadline()
            val timedConnect = api.methods.firstOrNull {
                it.name == "connectWithTimeout" && it.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType))
            }
            val connectFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            val before = callbacks.snapshot()
            check(before.complete) { "Owned connect thread enumeration is incomplete." }
            try {
                if (timedConnect != null) {
                    timedConnect.invoke(instance, connectFlags, QUERY_TIMEOUT_MS)
                } else {
                    // Older releases have an internal connect timeout; caller deadline is independent.
                    api.getMethod("connect", Int::class.javaPrimitiveType).invoke(instance, connectFlags)
                }
            } finally {
                val after = callbacks.snapshot()
                callbacks.capture(before, after) { thread ->
                    thread is HandlerThread && thread.name == "UiAutomation" && thread.looper?.thread === thread
                }
                after.threads.filter { thread -> before.threads.none { it === thread } }.forEach { thread ->
                    Log.i("PocketRun-UiAutomation", "connect-return sourceApk=${System.getProperty("java.class.path")} " +
                        "backendUID=${Process.myUid()} backendPID=${Process.myPid()} displayId=$displayId " +
                        "thread=${thread.name}/${thread.id} identity=${System.identityHashCode(thread)} " +
                        "groupIdentity=${System.identityHashCode(thread.threadGroup)}")
                }
            }
            query.checkDeadline()
            val info = api.getMethod("getServiceInfo").invoke(instance) as? AccessibilityServiceInfo
                ?: error("UiAutomation service info 不可用。")
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            api.getMethod("setServiceInfo", AccessibilityServiceInfo::class.java).invoke(instance, info)
            query.checkDeadline()
            return instance
        } catch (error: Throwable) {
            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            Log.w("PocketRun-UiAutomation", "connect-failed backendUID=${Process.myUid()} backendPID=${Process.myPid()} " +
                "displayId=$displayId cause=${cause.javaClass.name} message=${cause.message}")
            // close() may have taken the shared refs and attempted disconnect BEFORE
            // connect started. Still disconnect this local pair after a late connect:
            // taking a ref is not proof that its eventual connection was closed.
            disconnectOwned(localPeer, localInstance, events)
            throw error
        }
    }

    private fun collect(query: Query, instance: Any): UiTreeCodec.Tree {
        query.checkDeadline()
        val all = instance.javaClass.getMethod("getWindowsOnAllDisplays").invoke(instance) as? SparseArray<*>
            ?: error("多屏窗口查询不可用。")
        val nodes = ArrayList<UiTreeCodec.Node>()
        var encodedSize = 21
        var truncated = false
        var foundRoot = false
        fun field(value: CharSequence?) = bounded(value) { truncated = true }
        try {
            // Only SparseArray[owned displayId] is traversed. Other displays are recycled only.
            val windows = all.get(displayId) as? List<*>
            if (windows.isNullOrEmpty()) return status(UiTreeCodec.Status.NoWindow)

            fun visit(node: AccessibilityNodeInfo, parent: Int, windowId: Int, depth: Int) {
                try {
                    query.checkDeadline()
                    if (nodes.size >= UiTreeCodec.MAX_NODES || depth > UiTreeCodec.MAX_DEPTH) {
                        truncated = true
                        return
                    }
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    // Off-screen clipped RecyclerView/ScrollView children on Android 16 can report
                    // inverted bounds (e.g. top > bottom). Skip invisible non-root subtrees cleanly.
                    if (bounds.left > bounds.right || bounds.top > bounds.bottom) {
                        if (parent >= 0) return
                        bounds.right = maxOf(bounds.left, bounds.right)
                        bounds.bottom = maxOf(bounds.top, bounds.bottom)
                    }
                    val record = UiTreeCodec.Node(parent, windowId, depth,
                        field(node.className), field(node.packageName), field(node.viewIdResourceName),
                        field(node.text), field(node.contentDescription),
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                        node.isClickable, node.isEditable, node.isEnabled, node.isFocused)
                    val recordSize = 49 + listOf(record.className, record.packageName, record.viewId,
                        record.text, record.contentDescription).sumOf { it.toByteArray(Charsets.UTF_8).size }
                    if (encodedSize + recordSize > UiTreeCodec.MAX_TREE_BYTES) {
                        truncated = true
                        return
                    }
                    val index = nodes.size
                    nodes.add(record)
                    encodedSize += recordSize
                    val childCount = node.childCount
                    if (depth == UiTreeCodec.MAX_DEPTH && childCount > 0) {
                        truncated = true
                        return
                    }
                    for (childIndex in 0 until childCount) {
                        query.checkDeadline()
                        if (nodes.size >= UiTreeCodec.MAX_NODES || encodedSize + 49 > UiTreeCodec.MAX_TREE_BYTES) {
                            truncated = true
                            break
                        }
                        node.getChild(childIndex)?.let { visit(it, index, windowId, depth + 1) }
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }

            for (value in windows) {
                query.checkDeadline()
                if (nodes.size >= UiTreeCodec.MAX_NODES || encodedSize + 49 > UiTreeCodec.MAX_TREE_BYTES) {
                    truncated = true
                    break
                }
                val window = value as? AccessibilityWindowInfo ?: continue
                val windowId = window.id
                check(windowId >= 0) { "UI windowId 无效。" }
                val root = window.root ?: continue
                foundRoot = true
                visit(root, -1, windowId, 0)
            }
            query.checkDeadline()
            return UiTreeCodec.Tree(displayId, if (foundRoot) UiTreeCodec.Status.Available
                else UiTreeCodec.Status.RootUnavailable, truncated, nodes)
        } finally {
            for (index in 0 until all.size()) {
                (all.valueAt(index) as? List<*>)?.forEach { value ->
                    @Suppress("DEPRECATION")
                    (value as? AccessibilityWindowInfo)?.recycle()
                }
            }
        }
    }

    private fun bounded(value: CharSequence?, onTruncated: () -> Unit): String {
        if (value == null) return ""
        val out = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = Character.codePointAt(value, offset)
            val count = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + count > UiTreeCodec.MAX_STRING_BYTES) {
                onTruncated()
                break
            }
            if (codePoint in 0xd800..0xdfff) {
                out.append('?')
                onTruncated()
            } else out.appendCodePoint(codePoint)
            bytes += count
            offset += Character.charCount(codePoint)
        }
        return out.toString()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.get()?.cancel()
        worker.shutdownNow()
        eventThread.getAndSet(null)?.quitSafely()
        // Disconnect may itself make a Binder call. Never run it under the lifecycle lock,
        // and never join a worker whose late callback might need a session-owned lock.
        Thread({ disconnectAndQuit() }, "PocketRun-UiClose-$displayId").apply { isDaemon = true }.start()
    }

    private fun disconnectAndQuit() {
        val resources = synchronized(cleanupLock) {
            // Take the pair atomically: competing cleanup paths must not disconnect the
            // peer first and strand UiAutomation's internal callback thread.
            Triple(connection.getAndSet(null), automation.getAndSet(null), eventThread.getAndSet(null))
        }
        disconnectResources(resources.first, resources.second, resources.third)
    }

    private fun disconnectOwned(peer: Any?, instance: Any?, events: HandlerThread) {
        synchronized(cleanupLock) {
            // Never clear resources published by another initialization attempt.
            if (peer != null) connection.compareAndSet(peer, null)
            if (instance != null) automation.compareAndSet(instance, null)
            eventThread.compareAndSet(events, null)
        }
        // Local initialization ownership survives close() taking the shared refs.
        // Calls may be repeated: a premature disconnect can fail while a later one
        // must still close a connect that completed after cancellation.
        disconnectResources(peer, instance, events)
    }

    private fun disconnectResources(peer: Any?, instance: Any?, events: HandlerThread?) {
        try {
            // Android 15/16 disconnect throws BEFORE its callback-thread finally when a rejected
            // registration leaves CONNECTING. destroy() has the same dependency, so is not a fix.
            if (instance != null) runCatching { instance.javaClass.getMethod("disconnect").invoke(instance) }
            if (peer != null) runCatching { peer.javaClass.getMethod("disconnect").invoke(peer) }
        } finally {
            events?.quitSafely()
            val drained = callbacks.stopAndJoin(CALLBACK_JOIN_MS) { thread ->
                // Captured only inside this connect's private group, by object + Looper identity.
                // No hidden fields/state edits, process-wide name matching, or service takeover.
                Log.i("PocketRun-UiAutomation", "callback-quit backendUID=${Process.myUid()} backendPID=${Process.myPid()} " +
                    "displayId=$displayId thread=${thread.name}/${thread.id} identity=${System.identityHashCode(thread)}")
                (thread as HandlerThread).looper?.quit()
            }
            Log.i("PocketRun-UiAutomation", "cleanup backendUID=${Process.myUid()} backendPID=${Process.myPid()} " +
                "displayId=$displayId callbackThreadsExited=$drained")
        }
    }

    private fun status(value: UiTreeCodec.Status) = UiTreeCodec.Tree(displayId, value, false, emptyList())
    private class DeadlineExceeded : RuntimeException()

    companion object {
        private const val QUERY_TIMEOUT_MS = 3500L
        private const val CALLBACK_JOIN_MS = 500L
    }
}
