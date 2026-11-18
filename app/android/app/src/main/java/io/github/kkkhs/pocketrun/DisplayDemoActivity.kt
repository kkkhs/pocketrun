package io.github.kkkhs.pocketrun

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(padding, padding, padding, padding)
                setBackgroundColor(Color.rgb(15, 23, 42))
                addView(label("PocketRun Virtual Display Demo", 28f, true))
                addView(label("displayId: $displayId", 22f, false))
                addView(label("chapter-4-demo", 16f, false))
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
}
