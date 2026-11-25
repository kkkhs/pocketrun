package io.github.kkkhs.pocketrun

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkkhs.pocketrun.taskhost.EncryptedSnapshotFile
import io.github.kkkhs.pocketrun.taskhost.MemoryCodec
import io.github.kkkhs.pocketrun.taskhost.MemoryStore
import io.github.kkkhs.pocketrun.taskhost.ModelCredentialStore
import io.github.kkkhs.pocketrun.taskhost.ModelSettings
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated storage mechanisms, not a model request, phone approval or App-restart acceptance. */
@RunWith(AndroidJUnit4::class)
class Chapter12MemoryStoreTest {
    @Test fun encryptedCrudSurvivesNewStoreAndNeverReusesEmptyRevision() = isolated { context, directory, alias ->
        val store = MemoryStore(context, alias)
        val first = store.snapshot()
        assertEquals(first.revision, store.snapshot().revision)
        var cancellations = 0
        val added = store.save(first.revision, "", "Report A") { cancellations++ }
        assertEquals(1, cancellations)
        assertFalse(String(File(directory, "memory.bin").readBytes(), Charsets.UTF_8).contains("Report A"))
        assertEquals(0x180, Os.stat(File(directory, "memory.bin").path).st_mode and 0x1ff)
        val reloaded = MemoryStore(context, alias).snapshot()
        assertEquals(added.revision, reloaded.revision)
        assertEquals(added.notes, reloaded.notes)
        val note = reloaded.notes.single()
        assertThrows(IllegalArgumentException::class.java) {
            store.save(first.revision, note.id, "stale") { cancellations++ }
        }
        assertEquals(1, cancellations)
        val corrected = store.save(added.revision, note.id, "Report B") { cancellations++ }
        assertEquals(note.id, corrected.notes.single().id)
        assertEquals("Report B", store.select(corrected.revision, listOf(note.id)).notes.single().text)
        val deleted = store.delete(corrected.revision, note.id) { cancellations++ }
        assertTrue(deleted.notes.isEmpty())
        assertNotEquals(first.revision, deleted.revision)
        assertNotEquals(corrected.revision, deleted.revision)
        assertEquals(deleted.revision, MemoryStore(context, alias).snapshot().revision)
        assertThrows(IllegalArgumentException::class.java) { store.select(corrected.revision, listOf(note.id)) }
        assertThrows(IllegalStateException::class.java) { store.resetUnreadable {} }
    }

    @Test fun invalidMutationDoesNotCancelAndDiskFailureKeepsPreviousSnapshot() = isolated { context, directory, alias ->
        var failWrite = false
        val events = mutableListOf<String>()
        val store = MemoryStore(context, alias) {
            if (failWrite) { events += "disk-failure"; throw IOException("injected fixture failure") }
        }
        val added = store.save(store.snapshot().revision, "", "Report A") {}
        assertThrows(IllegalArgumentException::class.java) {
            store.save(added.revision, "", "x".repeat(257)) { events += "cancel" }
        }
        assertTrue(events.isEmpty())
        val previousBytes = File(directory, "memory.bin").readBytes()
        failWrite = true
        assertThrows(IOException::class.java) {
            store.save(added.revision, added.notes.single().id, "Report B") { events += "cancel" }
        }
        assertEquals(listOf("cancel", "disk-failure"), events)
        assertArrayEquals(previousBytes, File(directory, "memory.bin").readBytes())
        assertEquals(added.revision, MemoryStore(context, alias).snapshot().revision)
        assertEquals("Report A", MemoryStore(context, alias).snapshot().notes.single().text)
    }

    @Test fun modifiedCiphertextAndMissingKeyRequireExplicitReset() = isolated { context, directory, alias ->
        val store = MemoryStore(context, alias)
        val added = store.save(store.snapshot().revision, "", "Report A") {}
        val file = File(directory, "memory.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
        assertThrows(IllegalStateException::class.java) { store.clear(added.revision) {} }
        val reset = store.resetUnreadable {}
        assertTrue(reset.notes.isEmpty())
        assertNotEquals(added.revision, reset.revision)
        val next = store.save(reset.revision, "", "Report B") {}
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
        val restored = store.resetUnreadable {}
        assertTrue(restored.notes.isEmpty())
        assertNotEquals(next.revision, restored.revision)
    }

    @Test fun memoryResetCannotEraseModelCredentialsAndAadDomainsCannotBeMixed() = isolated { context, directory, alias ->
        val model = ModelCredentialStore(context, alias + "-model")
        model.save(ModelSettings.DEFAULT, "demo-not-a-real-api-credential")
        val memory = MemoryStore(context, alias)
        val snapshot = memory.snapshot()
        val plaintext = MemoryCodec.encode(snapshot)
        try {
            EncryptedSnapshotFile.write(AtomicFile(File(directory, "memory.bin")), alias,
                "pocketrun.model-config.v1".toByteArray(), plaintext, MemoryCodec.MAX_SNAPSHOT_BYTES)
        } finally { plaintext.fill(0) }
        assertThrows(IllegalStateException::class.java) { memory.snapshot() }
        memory.resetUnreadable {}
        assertEquals("demo-not-a-real-api-credential", model.load()!!.apiKey)
    }

    @Test fun atomicBackupRestoresLastCommittedSnapshotButOrphanNewFileIsNotEmptyMemory() = isolated { context, directory, alias ->
        val store = MemoryStore(context, alias)
        val added = store.save(store.snapshot().revision, "", "Report A") {}
        val file = File(directory, "memory.bin")
        val backup = File(directory, "memory.bin.bak")
        assertTrue(file.renameTo(backup))
        assertEquals(added.revision, MemoryStore(context, alias).snapshot().revision)
        assertTrue(file.isFile)
        File(directory, "memory.bin.new").writeText("incomplete")
        assertEquals(added.revision, store.snapshot().revision)
        assertFalse(File(directory, "memory.bin.new").exists())
        assertTrue(file.delete())
        File(directory, "memory.bin.new").writeText("incomplete")
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
        assertTrue(store.resetUnreadable {}.notes.isEmpty())
    }

    @Test fun closedStoreCannotReadOrMutateButAnotherInstanceStillLoadsData() = isolated { context, _, alias ->
        val store = MemoryStore(context, alias)
        val snapshot = store.save(store.snapshot().revision, "", "Report A") {}
        store.close()
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
        assertThrows(IllegalStateException::class.java) { store.clear(snapshot.revision) {} }
        assertEquals(snapshot.revision, MemoryStore(context, alias).snapshot().revision)
    }

    private fun isolated(action: (Context, File, String) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val directory = File(base.noBackupFilesDir, "chapter12-test-$id").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val alias = "pocketrun.memory.chapter12-test-$id"
        try { action(context, directory, alias) }
        finally {
            EncryptedSnapshotFile.clear(AtomicFile(File(directory, "memory.bin")), alias)
            ModelCredentialStore(context, alias + "-model").clear()
            check(directory.delete()) { "Owned memory test directory did not clear." }
        }
    }
}
