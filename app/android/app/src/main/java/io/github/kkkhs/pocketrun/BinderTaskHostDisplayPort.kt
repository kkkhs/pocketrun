package io.github.kkkhs.pocketrun

import android.os.Process
import io.github.kkkhs.pocketrun.taskhost.DisplayTarget
import io.github.kkkhs.pocketrun.taskhost.TaskHostDisplayPort
import io.github.kkkhs.pocketrun.taskhost.ToolResult
import java.io.Closeable
import org.json.JSONObject

/** Uses the same App-owned controller as AdbModule, never a host ADB transport. */
internal class BinderTaskHostDisplayPort(
    private val backend: PrivilegedBackendController,
) : TaskHostDisplayPort, Closeable {
    private val lock = Any()
    private var deathListener: ((DisplayTarget) -> Unit)? = null
    private var closed = false
    private val backendDeathRegistration = backend.addCapabilityDeathListener { event ->
        val listener = synchronized(lock) { if (closed) null else deathListener }
        listener?.invoke(DisplayTarget(event.queryGeneration, event.displayId, Process.myUid(), true))
    }
    override fun snapshot(): DisplayTarget {
        check(synchronized(lock) { !closed }) { "TaskHost display port is closed." }
        val current = backend.capabilitySnapshot()
        return DisplayTarget(current.queryGeneration, current.displayId, Process.myUid(), current.ready)
    }

    override fun observe(expected: DisplayTarget): ToolResult {
        requireTarget(expected)
        val frame = backend.captureScreenshot(expected.displayId, expected.displayEpoch)
        val tree = backend.dumpUiTree(expected.displayId, expected.displayEpoch)
        return ToolResult(mapOf(
            "source" to "app-owned-typed-binder",
            "displayId" to frame.displayId.toString(),
            "displayEpoch" to expected.displayEpoch.toString(),
            "screenshotUri" to frame.uri,
            "frameTimestampNanos" to frame.frameTimestampNanos,
            "uiTreeStatus" to tree.status,
            "uiTreeText" to observedText(tree.json),
            "nodeCount" to tree.nodeCount.toString(),
            "treeTruncated" to tree.truncated.toString(),
        ))
    }

    override fun demoTap(expected: DisplayTarget, x: Int, y: Int): ToolResult {
        requireTarget(expected)
        check(backend.injectTap(expected.displayId, x, y, expected.displayEpoch)) {
            "Backend did not accept the expected-display input."
        }
        return inputResult(expected, "demo-tap")
    }

    override fun demoText(expected: DisplayTarget, text: String): ToolResult {
        requireTarget(expected)
        check(backend.injectText(expected.displayId, text, expected.displayEpoch)) {
            "Backend did not accept the expected-display input."
        }
        return inputResult(expected, "demo-text")
    }

    override fun invalidate(expected: DisplayTarget) {
        if (expected.packageUid == Process.myUid() && expected.displayId > 0) {
            backend.invalidateQueriesIfCurrent(expected.displayId, expected.displayEpoch)
        }
    }

    override fun setDeathListener(listener: ((DisplayTarget) -> Unit)?) {
        synchronized(lock) {
            check(!closed || listener == null) { "TaskHost display port is closed." }
            deathListener = listener
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            deathListener = null
        }
        backendDeathRegistration.close()
    }

    private fun requireTarget(expected: DisplayTarget) {
        check(expected.backendReady && expected.displayId > 0 && expected.packageUid == Process.myUid() &&
            snapshot() == expected) { "App-owned backend/display target is not ready or has changed." }
    }

    private fun observedText(json: String): String {
        val nodes = JSONObject(json).getJSONArray("nodes")
        val text = LinkedHashSet<String>()
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            for (key in arrayOf("text", "contentDescription")) {
                val value = node.optString(key).trim()
                if (value.isNotEmpty()) text.add(value)
            }
            if (text.sumOf { it.length + 3 } >= MAX_TREE_TEXT_CHARS) break
        }
        return text.joinToString(" | ").take(MAX_TREE_TEXT_CHARS)
    }

    private fun inputResult(expected: DisplayTarget, action: String) = ToolResult(mapOf(
        "source" to "app-owned-typed-binder",
        "action" to action,
        "displayId" to expected.displayId.toString(),
        "displayEpoch" to expected.displayEpoch.toString(),
        "accepted" to "true",
    ))

    companion object { private const val MAX_TREE_TEXT_CHARS = 8192 }
}
