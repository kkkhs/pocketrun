package io.github.kkkhs.pocketrun

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent

// Pure policy: neither a missing session nor an invalid display may become display 0.
internal object DisplayInputPolicy {
    const val MAX_TEXT_CHARS = 64
    const val WIDTH = 720
    const val HEIGHT = 1280

    fun requireDisplay(expectedDisplayId: Int, currentDisplayId: Int) {
        require(expectedDisplayId > 0) { "目标 displayId 必须大于 0。" }
        check(currentDisplayId > 0 && expectedDisplayId == currentDisplayId) {
            "虚拟屏已释放或 displayId 已失效。"
        }
    }

    fun acceptsTap(x: Int, y: Int) = x in 0 until WIDTH && y in 0 until HEIGHT
    fun acceptsText(text: String) = text.length in 1..MAX_TEXT_CHARS &&
        text.all { it.code in 0x20..0x7e }
}

internal class DisplayInput(private val targetAppUid: Int) {
    fun tap(displayId: Int, x: Int, y: Int): Boolean {
        if (!DisplayInputPolicy.acceptsTap(x, y)) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            val injector = Injector(targetAppUid, displayId)
            // Bind and read back both events before dispatching either one.
            if (!injector.bind(down, displayId) || !injector.bind(up, displayId)) return false
            val downAccepted = runCatching { injector.inject(down) }.getOrDefault(false)
            // Always attempt UP on the same display; a failed DOWN must not leave a pointer held.
            val upAccepted = runCatching { injector.inject(up) }.getOrDefault(false)
            downAccepted && upAccepted
        } catch (_: Exception) {
            false
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun text(displayId: Int, text: String): Boolean {
        if (!DisplayInputPolicy.acceptsText(text)) return false
        var events: Array<KeyEvent>? = null
        return try {
            events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            val keys = events ?: return false
            if (keys.isEmpty() || keys.size > DisplayInputPolicy.MAX_TEXT_CHARS * 8 ||
                keys.any { it.keyCode == KeyEvent.KEYCODE_UNKNOWN ||
                    it.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP) }) return false
            val injector = Injector(targetAppUid, displayId)
            for (event in keys) {
                event.source = InputDevice.SOURCE_KEYBOARD
                if (!injector.bind(event, displayId)) return false
            }
            var accepted = true
            // Finish the generated sequence even on a rejected event, so modifier UPs are sent.
            for (event in keys) {
                if (!runCatching { injector.inject(event) }.getOrDefault(false)) accepted = false
            }
            accepted
        } catch (_: Exception) {
            false
        } finally {
            events?.forEach { event ->
                runCatching { InputEvent::class.java.getMethod("recycle").invoke(event) }
            }
        }
    }

    private class Injector(targetAppUid: Int, private val expectedDisplayId: Int) {
        private val service: Any
        private val inject: java.lang.reflect.Method
        private val targeted: Boolean
        private val appUid = targetAppUid
        private val setter = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        private val getter = InputEvent::class.java.getMethod("getDisplayId")

        init {
            require(appUid > 0) { "输入目标 App UID 无效。" }
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
                ?: error("InputManager 不可用。")
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            service = checkNotNull(stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder))
            val api = Class.forName("android.hardware.input.IInputManager")
            val target = api.methods.firstOrNull {
                it.name == "injectInputEventToTarget" &&
                    it.parameterTypes.contentEquals(arrayOf(InputEvent::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            }
            targeted = target != null
            inject = target ?: api.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            inject.isAccessible = true
        }

        fun bind(event: InputEvent, displayId: Int): Boolean {
            if (displayId <= 0 || displayId != expectedDisplayId) return false
            setter.invoke(event, displayId)
            return getter.invoke(event) == expectedDisplayId
        }

        fun inject(event: InputEvent): Boolean {
            // The third argument is the target App UID, NEVER the display ID.
            // Legacy dispatch is allowed only after bind() has verified the event display.
            check(expectedDisplayId > 0 && getter.invoke(event) == expectedDisplayId)
            val result = if (targeted) inject.invoke(service, event, WAIT_FOR_RESULT, appUid)
                else inject.invoke(service, event, WAIT_FOR_RESULT)
            return result == true
        }
    }

    companion object {
        private const val WAIT_FOR_RESULT = 1
    }
}
