package io.github.kkkhs.pocketrun.taskhost

import android.content.Context
import android.util.AtomicFile
import java.io.Closeable
import java.io.File

/** User-controlled memory; never an approval store or a log of model/tool output. */
internal class MemoryStore(
    context: Context,
    private val alias: String = ALIAS,
    private val beforeWrite: () -> Unit = {}, // Deterministic failure check, never exposed to JavaScript.
) : Closeable {
    private val file = AtomicFile(File(context.noBackupFilesDir, "memory.bin"))
    private var closed = false

    fun snapshot(): MemorySnapshot = synchronized(LOCK) {
        checkOpen()
        loadLocked() ?: MemoryCodec.snapshot(emptyList()).also(::writeLocked)
    }

    fun select(expectedRevision: String, ids: List<String>): MemorySelection = synchronized(LOCK) {
        val current = requireCurrent(expectedRevision)
        MemoryCodec.select(current, ids)
    }

    fun save(expectedRevision: String, id: String, text: String, beforeCommit: () -> Unit): MemorySnapshot =
        change(expectedRevision, beforeCommit) { notes ->
            MemoryCodec.validateText(text)
            if (id.isEmpty()) {
                require(notes.size < MemoryCodec.MAX_NOTES) { "MEMORY_NOTE_BUDGET" }
                notes += MemoryNote(MemoryCodec.newNoteId(), text)
            } else {
                MemoryCodec.validateId(id)
                val index = notes.indexOfFirst { it.id == id }
                require(index >= 0) { "MEMORY_NOTE_MISSING" }
                notes[index] = MemoryNote(id, text)
            }
        }

    fun delete(expectedRevision: String, id: String, beforeCommit: () -> Unit): MemorySnapshot =
        change(expectedRevision, beforeCommit) { notes ->
            MemoryCodec.validateId(id)
            require(notes.removeAll { it.id == id }) { "MEMORY_NOTE_MISSING" }
        }

    fun clear(expectedRevision: String, beforeCommit: () -> Unit): MemorySnapshot =
        change(expectedRevision, beforeCommit) { it.clear() }

    fun resetUnreadable(beforeCommit: () -> Unit): MemorySnapshot = synchronized(LOCK) {
        checkOpen()
        val unreadable = try { loadLocked(); false } catch (_: IllegalStateException) { true }
        check(unreadable) { "MEMORY_RESET_REQUIRES_UNREADABLE_STORE" }
        beforeCommit()
        checkOpen()
        EncryptedSnapshotFile.clear(file, alias)
        MemoryCodec.snapshot(emptyList()).also(::writeLocked)
    }

    private fun change(expectedRevision: String, beforeCommit: () -> Unit,
        edit: (MutableList<MemoryNote>) -> Unit): MemorySnapshot = synchronized(LOCK) {
        val current = requireCurrent(expectedRevision)
        val notes = current.notes.toMutableList()
        edit(notes)
        val next = MemoryCodec.snapshot(notes)
        beforeCommit() // Cancel and discard old model context before any valid replacement is written.
        checkOpen()
        writeLocked(next)
        next
    }

    private fun requireCurrent(expectedRevision: String): MemorySnapshot {
        checkOpen()
        MemoryCodec.validateRevision(expectedRevision)
        val current = snapshot()
        require(current.revision == expectedRevision) { "STALE_MEMORY_REVISION" }
        return current
    }

    private fun loadLocked(): MemorySnapshot? {
        try {
            val plaintext = EncryptedSnapshotFile.read(file, alias, AAD, MemoryCodec.MAX_SNAPSHOT_BYTES) ?: return null
            try { return MemoryCodec.decode(plaintext) }
            finally { plaintext.fill(0) }
        } catch (_: Exception) {
            throw IllegalStateException("MEMORY_STORE_CORRUPT")
        }
    }

    private fun writeLocked(snapshot: MemorySnapshot) {
        val plaintext = MemoryCodec.encode(snapshot)
        try {
            beforeWrite()
            EncryptedSnapshotFile.write(file, alias, AAD, plaintext, MemoryCodec.MAX_SNAPSHOT_BYTES)
        } finally { plaintext.fill(0) }
    }

    private fun checkOpen() { check(!closed) { "MEMORY_STORE_CLOSED" } }
    override fun close() = synchronized(LOCK) { closed = true }

    companion object {
        private const val ALIAS = "pocketrun.memory.v1"
        private val AAD = "pocketrun.memory.v1".toByteArray(Charsets.UTF_8)
        // ponytail: one App memory file; use per-file locks if independent profiles are added.
        private val LOCK = Any()
    }
}
