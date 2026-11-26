package io.github.kkkhs.pocketrun.taskhost

import io.github.kkkhs.pocketrun.UiTreeCodec

/** Only the two known Demo views are disclosed; never aggregate matching across a UI tree. */
internal object OwnedDemoObservation {
    fun select(tree: UiTreeCodec.Tree, target: DisplayTarget, packageName: String): DemoObservation {
        require(target.backendReady && target.displayId > 0 && target.displayEpoch > 0 &&
            tree.displayId == target.displayId && tree.status == UiTreeCodec.Status.Available && !tree.truncated) {
            "DEMO_OBSERVATION_UNAVAILABLE"
        }
        val input = tree.nodes.filter { it.packageName == packageName && it.viewId == "android:id/edit" }.singleOrNull()
            ?: throw IllegalArgumentException("DEMO_INPUT_NOT_UNIQUE")
        val result = tree.nodes.filter { it.packageName == packageName && it.viewId == "android:id/text1" }.singleOrNull()
            ?: throw IllegalArgumentException("DEMO_RESULT_NOT_UNIQUE")
        require(input.className == "android.widget.EditText" && result.className == "android.widget.TextView" &&
            input.editable && input.enabled && result.enabled && input.windowId == result.windowId &&
            input.left in 0..719 && input.top in 0..1279 && input.right in 1..720 && input.bottom in 1..1280 &&
            input.right > input.left && input.bottom > input.top) { "INVALID_DEMO_NODE" }
        // The exact result view proves empty; the known hint alone never does.
        val value = if (result.text == "input: " && (input.text.isEmpty() || input.text == "PocketRun_Ditto_5")) "" else input.text
        require(value.length <= 64 && value.all { it in ' '..'~' } && result.text == "input: $value") {
            "DEMO_VALUE_UNCONFIRMED"
        }
        return DemoObservation(target, value, result.text, input.left, input.top, input.right, input.bottom, input.focused)
    }

    fun fields(value: DemoObservation) = mapOf(
        "source" to "app-owned-demo-typed-binder",
        "displayId" to value.target.displayId.toString(), "displayEpoch" to value.target.displayEpoch.toString(),
        "demoValue" to value.value, "demoResult" to value.resultValue,
        "left" to value.left.toString(), "top" to value.top.toString(),
        "right" to value.right.toString(), "bottom" to value.bottom.toString(),
        "focused" to value.focused.toString(), "x" to value.x.toString(), "y" to value.y.toString(),
    )
}
