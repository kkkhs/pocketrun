package io.github.kkkhs.pocketrun.taskhost

import java.io.Closeable

internal interface TaskHostDisplayPort {
    fun snapshot(): DisplayTarget
    fun lastKnownTarget(): DisplayTarget?
    fun observe(expected: DisplayTarget): ToolResult
    fun observeDemo(expected: DisplayTarget): DemoObservation = throw UnsupportedOperationException("Demo observation unavailable.")
    fun demoTap(expected: DisplayTarget, x: Int, y: Int): ToolResult
    fun demoText(expected: DisplayTarget, text: String): ToolResult
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
