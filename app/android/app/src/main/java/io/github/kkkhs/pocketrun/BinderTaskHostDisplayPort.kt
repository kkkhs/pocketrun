package io.github.kkkhs.pocketrun

import android.os.Process
import io.github.kkkhs.pocketrun.taskhost.DisplayTarget
import io.github.kkkhs.pocketrun.taskhost.DemoObservation
import io.github.kkkhs.pocketrun.taskhost.OwnedDemoObservation
import io.github.kkkhs.pocketrun.taskhost.TaskHostDisplayPort
import io.github.kkkhs.pocketrun.taskhost.ToolResult
import java.io.Closeable
import org.json.JSONObject

/** Uses the same App-owned controller as AdbModule, never a host ADB transport. */
internal class BinderTaskHostDisplayPort(
    private val backend: PrivilegedBackendController,
    private val packageName: String,
) : TaskHostDisplayPort, Closeable {
    private val lock = Any()
    private var deathListener: ((DisplayTarget) -> Unit)? = null
    private var lastTarget: DisplayTarget? = null
    private var closed = false
    private val backendDeathRegistration = backend.addCapabilityDeathListener { event ->
        val target = DisplayTarget(event.queryGeneration, event.displayId, Process.myUid(), true)
        val listener = synchronized(lock) {
            if (closed) null else { lastTarget = target; deathListener }
        }
        listener?.invoke(target)
    }
    override fun snapshot(): DisplayTarget {
        check(synchronized(lock) { !closed }) { "TaskHost display port is closed." }
        val current = backend.capabilitySnapshot()
        val target = DisplayTarget(current.queryGeneration, current.displayId, Process.myUid(), current.ready)
        if (target.backendReady && target.displayId > 0 && target.displayEpoch > 0) {
            synchronized(lock) { if (!closed) lastTarget = target }
        }
        return target
    }

    override fun lastKnownTarget(): DisplayTarget? = synchronized(lock) { lastTarget }

    override fun observe(expected: DisplayTarget): ToolResult {
        requireTarget(expected)
        val frame = backend.captureTaskHostScreenshot(expected.displayId, expected.displayEpoch)
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

    override fun observeDemo(expected: DisplayTarget): DemoObservation {
        requireTarget(expected)
        val tree = backend.dumpTypedUiTree(expected.displayId, expected.displayEpoch)
        val value = OwnedDemoObservation.select(tree, expected, packageName)
        requireTarget(expected)
        return value
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

    override fun openApp(expected: DisplayTarget, packageName: String): ToolResult {
        requireTarget(expected)
        check(backend.launchAppOnDisplay(expected.displayId, packageName, expected.displayEpoch)) {
            "Failed to launch $packageName on virtual display ${expected.displayId}."
        }
        Thread.sleep(450)
        val preview = runCatching { backend.captureTaskHostScreenshot(expected.displayId, expected.displayEpoch) }.getOrNull()
        val fields = linkedMapOf(
            "source" to "app-owned-typed-binder",
            "displayId" to expected.displayId.toString(),
            "displayEpoch" to expected.displayEpoch.toString(),
            "packageName" to packageName,
            "action" to "app_open",
            "launched" to "true",
            "sourceStatus" to "accepted",
        )
        if (preview != null) fields["screenshotUri"] = preview.uri
        return ToolResult(fields)
    }

    override fun observeScreen(expected: DisplayTarget): ToolResult {
        requireTarget(expected)
        val frame = runCatching { backend.captureTaskHostScreenshot(expected.displayId, expected.displayEpoch) }.getOrNull()
        val tree = backend.dumpTypedUiTree(expected.displayId, expected.displayEpoch)
        val summary = UiTreeSummaryFormatter.format(tree)
        val fields = linkedMapOf(
            "source" to "app-owned-typed-binder",
            "displayId" to expected.displayId.toString(),
            "displayEpoch" to expected.displayEpoch.toString(),
            "action" to "app_observe",
            "uiTreeStatus" to tree.status.wire,
            "topPackage" to summary.topPackage,
            "nodeCount" to summary.elementCount.toString(),
            "truncated" to summary.truncated.toString(),
            "uiElements" to summary.text,
            "sourceStatus" to "observed",
        )
        if (frame != null) {
            fields["screenshotUri"] = frame.uri
            fields["frameTimestampNanos"] = frame.frameTimestampNanos
        }
        return ToolResult(fields)
    }

    override fun appTap(expected: DisplayTarget, x: Int, y: Int): ToolResult {
        requireTarget(expected)
        check(backend.injectAppTap(expected.displayId, x, y, expected.displayEpoch)) {
            "Backend did not accept app tap at ($x,$y)."
        }
        Thread.sleep(300)
        return appActionResult(expected, "app_tap")
    }

    override fun appSwipe(expected: DisplayTarget, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ToolResult {
        requireTarget(expected)
        check(backend.injectAppSwipe(expected.displayId, x1, y1, x2, y2, durationMs, expected.displayEpoch)) {
            "Backend did not accept app swipe."
        }
        Thread.sleep(300)
        return appActionResult(expected, "app_swipe")
    }

    override fun appInputText(expected: DisplayTarget, text: String): ToolResult {
        requireTarget(expected)
        check(backend.injectAppText(expected.displayId, text, expected.displayEpoch)) {
            "Backend did not accept app text input (ensure an editable text field is focused)."
        }
        Thread.sleep(250)
        return appActionResult(expected, "app_input_text")
    }

    override fun appPressKey(expected: DisplayTarget, key: String): ToolResult {
        requireTarget(expected)
        val keyCode = checkNotNull(DisplayInputPolicy.ALLOWED_KEYS[key.uppercase()]) { "Unsupported key: $key" }
        check(backend.injectAppKey(expected.displayId, keyCode, expected.displayEpoch)) {
            "Backend did not accept key $key."
        }
        Thread.sleep(300)
        return appActionResult(expected, "app_press_key", extra = mapOf("key" to key.uppercase()))
    }

    private fun appActionResult(
        expected: DisplayTarget,
        action: String,
        extra: Map<String, String> = emptyMap(),
    ): ToolResult {
        val preview = runCatching { backend.captureTaskHostScreenshot(expected.displayId, expected.displayEpoch) }.getOrNull()
        val fields = linkedMapOf(
            "source" to "app-owned-typed-binder",
            "displayId" to expected.displayId.toString(),
            "displayEpoch" to expected.displayEpoch.toString(),
            "action" to action,
            "sourceStatus" to "accepted",
        )
        fields.putAll(extra)
        if (preview != null) fields["screenshotUri"] = preview.uri
        return ToolResult(fields)
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
