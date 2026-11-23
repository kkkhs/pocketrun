package io.github.kkkhs.pocketrun.taskhost

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, no-backup encrypted configuration. No JS read API ever returns apiKey. */
internal class ModelCredentialStore(context: Context, private val alias: String = ALIAS) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "model-config.bin"))
    private val lock = Any()

    fun load(): ModelCredential? = synchronized(lock) {
        if (!file.baseFile.exists()) return null
        check(file.baseFile.length() in 29..MAX_FILE_BYTES) { "MODEL_CONFIG_CORRUPT" }
        try {
            val envelope = file.readFully()
            check(envelope.size in 29..MAX_FILE_BYTES.toInt() && envelope[0] == VERSION)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
            cipher.updateAAD(AAD)
            val decoded = cipher.doFinal(envelope, 13, envelope.size - 13)
            try {
                val value = JSONObject(String(decoded, Charsets.UTF_8))
                val settings = ModelSettings(value.getString("endpoint"), value.getString("model"))
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
        val plaintext = JSONObject().put("endpoint", settings.endpoint).put("model", settings.model)
            .put("apiKey", credential).toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            cipher.updateAAD(AAD)
            check(cipher.iv.size == 12)
            val envelope = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
            check(envelope.size <= MAX_FILE_BYTES)
            val stream = file.startWrite()
            try {
                stream.write(envelope)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        } finally { plaintext.fill(0) }
    }

    fun clear() = synchronized(lock) {
        file.delete()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        check(create) { "MODEL_CONFIG_KEY_MISSING" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val ALIAS = "pocketrun.model-config.v1"
        private const val VERSION: Byte = 1
        private const val MAX_FILE_BYTES = 16_384L
        private val AAD = "pocketrun.model-config.v1".toByteArray(Charsets.UTF_8)
    }
}
