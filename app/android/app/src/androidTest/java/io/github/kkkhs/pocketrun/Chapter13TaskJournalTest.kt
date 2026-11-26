package io.github.kkkhs.pocketrun

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkkhs.pocketrun.taskhost.EncryptedSnapshotFile
import io.github.kkkhs.pocketrun.taskhost.TaskJournal
import io.github.kkkhs.pocketrun.taskhost.TaskJournalCodec
import io.github.kkkhs.pocketrun.taskhost.TaskJournalRecord
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated journal storage checks only; no model call, production file, approval or action replay. */
@RunWith(AndroidJUnit4::class)
class Chapter13TaskJournalTest {
    @Test fun encryptedRecordPersistsItsUncertainIntentWithoutStartingWork() = isolated { context, directory, alias ->
        val journal = TaskJournal(context, alias)
        assertNull(journal.load())
        assertThrows(IllegalStateException::class.java) { journal.resetUnreadable() }
        val record = TaskJournalRecord(TaskJournalCodec.newRunId(), "Read demo and verify output",
            phase = "interrupted", stage = "demo_verified", inputDigest = "a".repeat(64),
            expectedText = "items=2;quantity=5", pendingEffect = "publish",
            displayId = 7, displayEpoch = 9L, packageUid = 10150)
        journal.save(record)
        val file = File(directory, "complete-task.bin")
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains(record.goal))
        assertEquals(0x180, Os.stat(file.path).st_mode and 0x1ff)
        assertEquals(record, TaskJournal(context, alias).load())
        assertThrows(IllegalStateException::class.java) { journal.resetUnreadable() }
        journal.close()
        assertThrows(IllegalStateException::class.java) { journal.load() }
        assertThrows(IllegalStateException::class.java) { journal.save(record) }
        assertEquals(record, TaskJournal(context, alias).load())
    }

    @Test fun corruptCiphertextCannotBeOverwrittenWithoutExplicitReset() = isolated { context, directory, alias ->
        val journal = TaskJournal(context, alias)
        val record = TaskJournalRecord(TaskJournalCodec.newRunId(), "Read demo")
        journal.save(record)
        val file = File(directory, "complete-task.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) { journal.load() }
        assertThrows(IllegalStateException::class.java) { journal.save(record.copy(runId = TaskJournalCodec.newRunId())) }
        assertArrayEquals(bytes, file.readBytes())
        journal.resetUnreadable()
        assertNull(journal.load())
        journal.save(record)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertThrows(IllegalStateException::class.java) { journal.load() }
        assertThrows(IllegalStateException::class.java) { journal.save(record) }
        journal.resetUnreadable()
        assertNull(journal.load())
    }

    @Test fun authenticatedDataDomainCannotBeSubstituted() = isolated { context, directory, alias ->
        val record = TaskJournalRecord(TaskJournalCodec.newRunId(), "Read demo")
        val journal = TaskJournal(context, alias)
        journal.save(record)
        val plaintext = TaskJournalCodec.encode(record)
        try {
            EncryptedSnapshotFile.write(AtomicFile(File(directory, "complete-task.bin")), alias,
                "pocketrun.memory.v1".toByteArray(), plaintext, TaskJournalCodec.MAX_BYTES)
        } finally { plaintext.fill(0) }
        assertThrows(IllegalStateException::class.java) { journal.load() }
        journal.resetUnreadable()
        assertNull(journal.load())
    }

    @Test fun atomicBackupRestoresVerifiedBytesButOrphanNewFileIsNotMissingHistory() = isolated { context, directory, alias ->
        val journal = TaskJournal(context, alias)
        val record = TaskJournalRecord(TaskJournalCodec.newRunId(), "Read demo")
        journal.save(record)
        val file = File(directory, "complete-task.bin")
        assertTrue(file.renameTo(File(directory, "complete-task.bin.bak")))
        assertEquals(record, TaskJournal(context, alias).load())
        assertTrue(file.isFile)
        File(directory, "complete-task.bin.new").writeText("incomplete")
        assertEquals(record, journal.load())
        assertFalse(File(directory, "complete-task.bin.new").exists())
        assertTrue(file.delete())
        File(directory, "complete-task.bin.new").writeText("incomplete")
        assertThrows(IllegalStateException::class.java) { journal.load() }
        journal.resetUnreadable()
        assertNull(journal.load())
    }

    private fun isolated(action: (Context, File, String) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val directory = File(base.noBackupFilesDir, "chapter13-journal-test-$id").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val alias = "pocketrun.task-journal.chapter13-test-$id"
        try { action(context, directory, alias) }
        finally {
            EncryptedSnapshotFile.clear(AtomicFile(File(directory, "complete-task.bin")), alias)
            check(directory.delete()) { "Owned journal fixture did not clear." }
        }
    }
}
