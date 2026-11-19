package io.github.kkkhs.pocketrun

import android.os.Process
import io.github.kkkhs.pocketrun.taskhost.DisplayTarget
import io.github.kkkhs.pocketrun.taskhost.TaskHostDisplayPort
import io.github.kkkhs.pocketrun.taskhost.ToolResult

/** Uses the same App-owned controller as AdbModule, never a host ADB transport. */
internal class BinderTaskHostDisplayPort(
    private val backend: PrivilegedBackendController,
) : TaskHostDisplayPort {
    override fun snapshot(): DisplayTarget {
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

    private fun requireTarget(expected: DisplayTarget) {
        check(expected.backendReady && expected.displayId > 0 && expected.packageUid == Process.myUid() &&
            snapshot() == expected) { "App-owned backend/display target is not ready or has changed." }
    }

    private fun inputResult(expected: DisplayTarget, action: String) = ToolResult(mapOf(
        "source" to "app-owned-typed-binder",
        "action" to action,
        "displayId" to expected.displayId.toString(),
        "displayEpoch" to expected.displayEpoch.toString(),
        "accepted" to "true",
    ))
}
