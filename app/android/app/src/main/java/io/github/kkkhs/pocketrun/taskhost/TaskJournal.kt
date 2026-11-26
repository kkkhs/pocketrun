package io.github.kkkhs.pocketrun.taskhost

import android.content.Context
import android.util.AtomicFile
import java.io.Closeable
import java.io.File

/** One durable fact record. Loading it never starts work or restores an old approval. */
internal class TaskJournal(context: Context, private val alias: String = ALIAS) : Closeable {
    private val file = AtomicFile(File(context.noBackupFilesDir, "complete-task.bin"))
    private var closed = false

    fun load(): TaskJournalRecord? = synchronized(LOCK) {
        checkOpen()
        loadLocked()
    }

    fun save(record: TaskJournalRecord): Unit = synchronized(LOCK) {
        checkOpen()
        val plaintext = TaskJournalCodec.encode(record)
        try {
            loadLocked() // A new record is not permission to overwrite unreadable history.
            EncryptedSnapshotFile.write(file, alias, AAD, plaintext, TaskJournalCodec.MAX_BYTES)
        } finally { plaintext.fill(0) }
    }

    fun resetUnreadable(): Unit = synchronized(LOCK) {
        checkOpen()
        val unreadable = try { loadLocked(); false } catch (_: IllegalStateException) { true }
        check(unreadable) { "TASK_JOURNAL_RESET_REQUIRES_UNREADABLE_STORE" }
        EncryptedSnapshotFile.clear(file, alias)
    }

    private fun loadLocked(): TaskJournalRecord? {
        try {
            val plaintext = EncryptedSnapshotFile.read(file, alias, AAD, TaskJournalCodec.MAX_BYTES) ?: return null
            try { return TaskJournalCodec.decode(plaintext) }
            finally { plaintext.fill(0) }
        } catch (_: Exception) {
            throw IllegalStateException("TASK_JOURNAL_CORRUPT")
        }
    }

    private fun checkOpen() { check(!closed) { "TASK_JOURNAL_CLOSED" } }
    override fun close() = synchronized(LOCK) { closed = true }

    companion object {
        private const val ALIAS = "pocketrun.task-journal.v1"
        private val AAD = "pocketrun.task-journal.v1".toByteArray(Charsets.UTF_8)
        // ponytail: one App journal file; use per-file locks for independent journals.
        private val LOCK = Any()
    }
}
