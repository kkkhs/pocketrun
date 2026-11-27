package io.github.kkkhs.pocketrun.taskhost

import java.io.Closeable

internal interface TaskHostDisplayPort {
    fun snapshot(): DisplayTarget
    fun lastKnownTarget(): DisplayTarget?
    fun observe(expected: DisplayTarget): ToolResult
    fun observeDemo(expected: DisplayTarget): DemoObservation = throw UnsupportedOperationException("Demo observation unavailable.")
    fun demoTap(expected: DisplayTarget, x: Int, y: Int): ToolResult
    fun demoText(expected: DisplayTarget, text: String): ToolResult
    fun openApp(expected: DisplayTarget, packageName: String): ToolResult =
        throw UnsupportedOperationException("General app launch unavailable.")
    fun observeScreen(expected: DisplayTarget): ToolResult =
        throw UnsupportedOperationException("General screen observation unavailable.")
    fun appTap(expected: DisplayTarget, x: Int, y: Int): ToolResult =
        throw UnsupportedOperationException("General app tap unavailable.")
    fun appSwipe(expected: DisplayTarget, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ToolResult =
        throw UnsupportedOperationException("General app swipe unavailable.")
    fun appInputText(expected: DisplayTarget, text: String): ToolResult =
        throw UnsupportedOperationException("General app text input unavailable.")
    fun appPressKey(expected: DisplayTarget, key: String): ToolResult =
        throw UnsupportedOperationException("General app key press unavailable.")
    fun invalidate(expected: DisplayTarget)
    fun setDeathListener(listener: ((DisplayTarget) -> Unit)?)
}

internal object TaskHostDisplayPortRegistry {
    private val lock = Any()
    private var port: TaskHostDisplayPort? = null
    private val deathListeners = LinkedHashSet<(DisplayTarget) -> Unit>()

    fun install(value: TaskHostDisplayPort): Closeable {
        synchronized(lock) {
            check(port == null) { "TaskHost display port is already installed." }
            port = value
        }
        try {
            value.setDeathListener(::publishDeath)
        } catch (error: Throwable) {
            synchronized(lock) { if (port === value) port = null }
            throw error
        }
        return Closeable {
            val oldTarget = (runCatching { value.snapshot() }.getOrNull()
                ?.takeIf { it.backendReady && it.displayId > 0 && it.displayEpoch > 0 }
                ?: value.lastKnownTarget())
                ?.takeIf { it.backendReady && it.displayId > 0 && it.displayEpoch > 0 }
            val removed = synchronized(lock) {
                if (port === value) { port = null; true } else false
            }
            if (removed) {
                var failure: Throwable? = null
                try {
                    value.setDeathListener(null)
                    (value as? Closeable)?.close()
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    if (oldTarget != null) {
                        try { publishDeath(oldTarget) }
                        catch (error: Throwable) {
                            if (failure == null) failure = error else failure.addSuppressed(error)
                        }
                    }
                }
                failure?.let { throw it }
            }
        }
    }

    fun current(): TaskHostDisplayPort? = synchronized(lock) { port }

    fun addDeathListener(listener: (DisplayTarget) -> Unit): Closeable {
        synchronized(lock) { deathListeners.add(listener) }
        return Closeable { synchronized(lock) { deathListeners.remove(listener) } }
    }

    private fun publishDeath(target: DisplayTarget) {
        val listeners = synchronized(lock) { deathListeners.toList() }
        var failure: Throwable? = null
        for (listener in listeners) {
            try { listener(target) }
            catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
