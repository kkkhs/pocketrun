package io.github.kkkhs.pocketrun

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CancellationException

/** One owned PTY at a time. This lifecycle core does not create a task host or expose arbitrary commands. */
internal class PtySession(private val backend: PtyBackend = NativePtyBackend) : Closeable {
    class Operation internal constructor(val token: String, internal val epoch: Long)
    enum class Signal(val number: Int) {
        HANGUP(1), INTERRUPT(2), QUIT(3), TERMINATE(15), CONTINUE(18), SUSPEND(20)
    }
    enum class Control(val byte: Byte) {
        INTERRUPT(3), END_OF_INPUT(4), FORM_FEED(12), SUSPEND(26), QUIT(28)
    }

    private data class Slot(
        val operation: Operation,
        val callback: (String) -> Unit,
        val codec: PtyCodec = PtyCodec(),
        var handle: Long = 0,
        var cancelled: Boolean = false,
        var settled: Boolean = false,
    )

    private val lock = Any()
    private val callbackOrder = Any()
    private val readOrder = Any()
    private var queuedEpoch = 0L
    private var current: Slot? = null
    private var closed = false

    @Throws(IOException::class)
    fun open(token: String, config: PtyTrustedConfig, onOutput: (String) -> Unit): Operation {
        require(TOKEN.matches(token)) { "PTY correlation token is invalid." }
        val slot = synchronized(lock) {
            check(!closed) { "PTY session is closed." }
            check(current == null) { "A PTY session is already active." }
            Slot(Operation(token, ++queuedEpoch), onOutput).also { current = it }
        }
        val handle = try { backend.open(config) }
        catch (error: Throwable) {
            synchronized(lock) { if (current === slot) current = null; slot.settled = true }
            throw error
        }
        if (handle <= 0) {
            synchronized(lock) { if (current === slot) current = null; slot.settled = true }
            throw IOException("Native PTY returned an invalid handle.")
        }
        val accepted = synchronized(lock) {
            slot.handle = handle // Retain cleanup ownership even if cancellation won the open race.
            current === slot && !slot.cancelled && !closed && slot.operation.epoch == queuedEpoch
        }
        if (!accepted) {
            backend.terminateRoot(handle)
            if (backend.close(handle)) {
                synchronized(lock) { if (current === slot) current = null; slot.settled = true }
                throw CancellationException("PTY open was cancelled.")
            }
            throw IOException("cleanup_unproven: cancelled PTY open remains owned for retry.")
        }
        return slot.operation
    }

    @Throws(IOException::class)
    fun pump(operation: Operation): Boolean = synchronized(readOrder) {
        val slot = requireActive(operation)
        val bytes = backend.read(slot.handle, PtyCodec.MAX_READ_BYTES) ?: return false
        val chunk = slot.codec.consume(bytes)
        emitIfCurrent(slot, chunk.text)
        true
    }

    @Throws(IOException::class)
    fun write(operation: Operation, bytes: ByteArray): Int {
        require(bytes.size in 1..MAX_INPUT_BYTES) { "PTY input must contain 1..4096 bytes." }
        val slot = requireActive(operation)
        return backend.write(slot.handle, bytes.copyOf())
    }

    @Throws(IOException::class)
    fun sendControl(operation: Operation, control: Control): Int = write(operation, byteArrayOf(control.byte))

    @Throws(IOException::class)
    fun resize(operation: Operation, columns: Int, rows: Int) {
        require(columns in MIN_CELLS..MAX_CELLS && rows in MIN_CELLS..MAX_CELLS) {
            "PTY dimensions must remain in 16..400."
        }
        backend.resize(requireActive(operation).handle, columns, rows)
    }

    @Throws(IOException::class)
    fun signal(operation: Operation, signal: Signal) {
        backend.signalOwned(requireActive(operation).handle, signal.number)
    }

    /**
     * Continuously drains the PTY before collecting terminal status. Native read multiplexes the
     * PTY and the launch/reaper wake FD, so a child cannot deadlock in waitpid behind a full PTY.
     */
    @Throws(IOException::class)
    fun waitForExit(operation: Operation): PtyExit {
        val slot = requireActive(operation)
        var primary: Throwable? = null
        var exit: PtyExit? = null
        try {
            synchronized(readOrder) {
                while (true) {
                    val bytes = backend.read(slot.handle, PtyCodec.MAX_READ_BYTES) ?: break
                    emitIfCurrent(slot, slot.codec.consume(bytes).text)
                }
                exit = backend.waitForExit(slot.handle)
                synchronized(lock) { if (!isCurrent(slot)) throw CancellationException("PTY wait was cancelled.") }
                emitIfCurrent(slot, slot.codec.finish().text)
            }
        } catch (error: Throwable) {
            val cancelWon = synchronized(lock) {
                slot.cancelled || closed || current !== slot || slot.operation.epoch != queuedEpoch
            }
            primary = if (cancelWon && error !is CancellationException) {
                CancellationException("PTY wait was cancelled before native exit collection.").also { it.initCause(error) }
            } else error
        }

        val cleanupProven = try { backend.close(slot.handle) }
        catch (cleanup: Throwable) {
            if (primary != null) primary.addSuppressed(cleanup) else primary = cleanup
            false
        }
        if (!cleanupProven) {
            synchronized(lock) {
                if (current === slot && !slot.cancelled) { slot.cancelled = true; queuedEpoch++ }
            }
            val cleanup = IOException("cleanup_unproven: native PTY handle remains owned for retry.")
            if (primary != null) primary.addSuppressed(cleanup) else primary = cleanup
        } else synchronized(lock) {
            slot.settled = true
            if (current === slot) current = null
        }
        primary?.let { throw it }
        return checkNotNull(exit)
    }

    /** A false native close never clears the busy slot; retrying the same token remains possible. */
    @Throws(IOException::class)
    fun cancel(token: String): Boolean {
        val slot: Slot
        val handle: Long
        synchronized(callbackOrder) {
            synchronized(lock) {
                slot = current ?: return false
                if (slot.operation.token != token || slot.settled) return false
                if (!slot.cancelled) {
                    slot.cancelled = true
                    queuedEpoch++
                }
                handle = slot.handle
            }
        }
        if (handle == 0L) return true // Opening thread publishes its handle and performs first cleanup.
        backend.terminateRoot(handle)
        if (!backend.close(handle)) throw IOException("cleanup_unproven: cancelled PTY remains owned for retry.")
        synchronized(lock) { slot.settled = true; if (current === slot) current = null }
        return true
    }

    @Throws(IOException::class)
    override fun close() {
        val slot: Slot?
        val handle: Long
        synchronized(callbackOrder) {
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    queuedEpoch++
                    current?.cancelled = true
                }
                slot = current
                handle = slot?.handle ?: 0
                if (slot == null) return
            }
        }
        if (handle == 0L) return // Opening thread must finish before close can be retried.
        backend.terminateRoot(handle)
        if (!backend.close(handle)) throw IOException("cleanup_unproven: closed PTY remains owned for retry.")
        synchronized(lock) { slot?.settled = true; if (current === slot) current = null }
    }

    private fun requireActive(operation: Operation): Slot = synchronized(lock) {
        val slot = current
        check(slot != null && slot.operation === operation && slot.operation.epoch == queuedEpoch &&
            !slot.cancelled && !slot.settled && slot.handle != 0L && !closed) { "PTY operation is invalid or stale." }
        slot
    }

    private fun emitIfCurrent(slot: Slot, text: String) {
        if (text.isEmpty()) return
        synchronized(callbackOrder) {
            synchronized(lock) { check(isCurrent(slot)) { "PTY output belongs to a stale operation." } }
            slot.callback(text)
        }
    }

    private fun isCurrent(slot: Slot): Boolean = current === slot && slot.operation.epoch == queuedEpoch &&
        !slot.cancelled && !slot.settled && !closed

    companion object {
        const val MAX_INPUT_BYTES = 4 * 1024
        const val MIN_CELLS = 16
        const val MAX_CELLS = 400
        private val TOKEN = Regex("[a-zA-Z0-9_.:-]{1,96}")
    }
}
