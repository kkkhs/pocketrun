package io.github.kkkhs.pocketrun

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkkhs.pocketrun.taskhost.ModelCredentialStore
import io.github.kkkhs.pocketrun.taskhost.ModelSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Mechanism checks only, using a separate test directory/alias and a nonfunctional demo credential. */
@RunWith(AndroidJUnit4::class)
class Chapter11CredentialStoreTest {
    @Test fun encryptsSurvivesNewStoreAndRejectsModifiedCiphertext() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.noBackupFilesDir, "chapter11-vault-test").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val alias = "pocketrun.model-config.chapter11-test"
        val store = ModelCredentialStore(isolated, alias)
        try {
            store.clear()
            val demo = "demo-not-a-real-api-credential"
            store.save(ModelSettings.DEFAULT, demo)
            val file = File(directory, "model-config.bin")
            assertFalse(String(file.readBytes(), Charsets.UTF_8).contains(demo))
            assertEquals(demo, ModelCredentialStore(isolated, alias).load()!!.apiKey)
            val envelope = file.readBytes()
            envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
            file.writeBytes(envelope)
            assertThrows(IllegalStateException::class.java) { ModelCredentialStore(isolated, alias).load() }
        } finally { store.clear(); directory.delete() }
    }
}
