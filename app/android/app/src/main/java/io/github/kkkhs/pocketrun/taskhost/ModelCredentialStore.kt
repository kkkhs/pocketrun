package io.github.kkkhs.pocketrun.taskhost

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** App-private, no-backup encrypted configuration. No JS read API ever returns apiKey. */
internal class ModelCredentialStore(context: Context, private val alias: String = ALIAS) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "model-config.bin"))
    private val lock = Any()

    fun load(): ModelCredential? = synchronized(lock) {
        try {
            val decoded = EncryptedSnapshotFile.read(file, alias, AAD, MAX_FILE_BYTES - 29) ?: return null
            try {
                val value = JSONObject(String(decoded, Charsets.UTF_8))
                val protocol = ModelProtocol.fromWireName(value.optString("protocol", "anthropic"))
                val settings = ModelSettings(value.getString("endpoint"), value.getString("model"), protocol)
                val credential = value.getString("apiKey")
                ModelSettings.validateCredential(credential)
                ModelCredential(settings, credential)
            } finally { decoded.fill(0) }
        } catch (_: Exception) {
            // Never include ciphertext, API key, SDK error body or attacker-controlled text.
            throw IllegalStateException("MODEL_CONFIG_CORRUPT")
        }
    }

    fun save(settings: ModelSettings, suppliedKey: String) = synchronized(lock) {
        val credential = if (suppliedKey.isEmpty()) load()?.apiKey
            ?: throw IllegalStateException("MODEL_CREDENTIAL_REQUIRED") else suppliedKey
        ModelSettings.validateCredential(credential)
        val plaintext = JSONObject()
            .put("endpoint", settings.endpoint)
            .put("model", settings.model)
            .put("protocol", settings.protocol.wireName)
            .put("apiKey", credential)
            .toString()
            .toByteArray(Charsets.UTF_8)
        try { EncryptedSnapshotFile.write(file, alias, AAD, plaintext, MAX_FILE_BYTES - 29) }
        finally { plaintext.fill(0) }
    }

    fun clear() = synchronized(lock) { EncryptedSnapshotFile.clear(file, alias) }

    companion object {
        private const val ALIAS = "pocketrun.model-config.v1"
        private const val MAX_FILE_BYTES = 16_384
        private val AAD = "pocketrun.model-config.v1".toByteArray(Charsets.UTF_8)
    }
}
