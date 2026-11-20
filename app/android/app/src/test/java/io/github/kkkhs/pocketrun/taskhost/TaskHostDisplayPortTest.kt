package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.Closeable

class TaskHostDisplayPortTest {
    @Test
    fun deathAndRegistrationRemovalPublishExactOldTargetOutsideRegistryOwnership() {
        assertNull(TaskHostDisplayPortRegistry.current())
        val port = RecordingPort()
        val deaths = mutableListOf<DisplayTarget>()
        TaskHostDisplayPortRegistry.addDeathListener(deaths::add).use {
            TaskHostDisplayPortRegistry.install(port).use {
                assertSame(port, TaskHostDisplayPortRegistry.current())
                port.emitDeath()
                assertEquals(listOf(port.target), deaths)
            }
            assertEquals(listOf(port.target, port.target), deaths)
        }
        assertEquals(1, port.closes)
        assertNull(TaskHostDisplayPortRegistry.current())
    }

    @Test
    fun unregisterStillPublishesOldTargetWhenPortCloseFails() {
        val port = RecordingPort(failClose = true)
        val deaths = mutableListOf<DisplayTarget>()
        TaskHostDisplayPortRegistry.addDeathListener(deaths::add).use {
            val installation = TaskHostDisplayPortRegistry.install(port)
            try { installation.close() } catch (_: IllegalStateException) { /* expected */ }
        }
        assertEquals(listOf(port.target), deaths)
        assertNull(TaskHostDisplayPortRegistry.current())
    }

    @Test
    fun removedModuleListenerCannotReceiveOldOrFutureCallbacks() {
        val port = RecordingPort()
        var callbacks = 0
        val listener = TaskHostDisplayPortRegistry.addDeathListener { callbacks++ }
        val installation = TaskHostDisplayPortRegistry.install(port)
        listener.close()
        port.emitDeath()
        installation.close()
        assertEquals(0, callbacks)
    }

    private class RecordingPort(private val failClose: Boolean = false) : TaskHostDisplayPort, Closeable {
        val target = DisplayTarget(7L, 11, 10469, true)
        private var listener: ((DisplayTarget) -> Unit)? = null
        var closes = 0

        override fun snapshot() = target
        override fun observe(expected: DisplayTarget) = ToolResult(emptyMap())
        override fun demoTap(expected: DisplayTarget, x: Int, y: Int) = ToolResult(emptyMap())
        override fun demoText(expected: DisplayTarget, text: String) = ToolResult(emptyMap())
        override fun invalidate(expected: DisplayTarget) = Unit
        override fun setDeathListener(listener: ((DisplayTarget) -> Unit)?) { this.listener = listener }
        fun emitDeath() { listener?.invoke(target) }
        override fun close() {
            closes++
            if (failClose) throw IllegalStateException("injected port close failure")
        }
    }
}
