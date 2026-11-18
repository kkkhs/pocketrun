package io.github.kkkhs.pocketrun

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class DisplayDemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val displayId = display?.displayId ?: -1
        if (displayId <= 0) {
            finishAndRemoveTask()
            return
        }
        val padding = (32 * resources.displayMetrics.density).toInt()
        val result = label("input: ", 16f, false).apply {
            id = RESULT_VIEW_ID
            contentDescription = "演示输入结果"
        }
        val input = EditText(this).apply {
            // Framework IDs are stable and resolvable as viewIdResourceName in UiAutomation.
            id = INPUT_VIEW_ID
            contentDescription = "演示输入框"
            hint = "PocketRun_Ditto_5"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            showSoftInputOnFocus = false
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(148, 163, 184))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    result.text = "input: ${s ?: ""}"
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                setBackgroundColor(Color.rgb(15, 23, 42))
                addView(label("PocketRun Virtual Display Demo", 28f, true))
                addView(label("displayId: $displayId", 22f, false))
                addView(label("chapter-5-ditto", 16f, false))
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(result)
            }
        )
    }

    private fun label(value: String, size: Float, heading: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(if (heading) Color.WHITE else Color.rgb(147, 197, 253))
        setPadding(0, 16, 0, 16)
    }

    companion object {
        const val INPUT_VIEW_ID = android.R.id.edit
        const val RESULT_VIEW_ID = android.R.id.text1
    }
}
