package io.github.kkkhs.pocketrun.taskhost

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Shared envelope mechanics only; each caller owns its file, schema, key domain and byte budget. */
internal object EncryptedSnapshotFile {
    private const val VERSION: Byte = 1
    private const val OVERHEAD = 29

    fun isPresent(file: AtomicFile) = stateFiles(file).map(::isRegularOrAbsent).any { it }

    private fun stateFiles(file: AtomicFile) = listOf(file.baseFile,
        File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new"))

    private fun isRegularOrAbsent(file: File): Boolean = try {
        require(OsConstants.S_ISREG(Os.lstat(file.path).st_mode)) { "INVALID_SNAPSHOT_FILE" }
        true
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) false else throw error
    }

    private fun readEnvelope(file: AtomicFile, maxPlainBytes: Int): ByteArray {
        require(maxPlainBytes in 1..65_536)
        return file.openRead().use { input ->
            require(OsConstants.S_ISREG(Os.fstat(input.fd).st_mode)) { "INVALID_SNAPSHOT_FILE" }
            val bytes = ByteArray(maxPlainBytes + OVERHEAD + 1)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size in OVERHEAD..maxPlainBytes + OVERHEAD) { "ENCRYPTED_SNAPSHOT_BUDGET" }
            bytes.copyOf(size)
        }
    }

    fun read(file: AtomicFile, alias: String, aad: ByteArray, maxPlainBytes: Int): ByteArray? {
        if (!isPresent(file)) return null
        val envelope = readEnvelope(file, maxPlainBytes)
        require(envelope[0] == VERSION) { "INVALID_ENCRYPTED_SNAPSHOT" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(alias, false), GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(envelope, 13, envelope.size - 13).also {
            require(it.size <= maxPlainBytes) { "ENCRYPTED_SNAPSHOT_BUDGET" }
        }
    }

    fun write(file: AtomicFile, alias: String, aad: ByteArray, plaintext: ByteArray, maxPlainBytes: Int) {
        require(maxPlainBytes in 1..65_536 && plaintext.size <= maxPlainBytes) { "ENCRYPTED_SNAPSHOT_BUDGET" }
        isPresent(file) // Reject unreadable or nonregular atomic-file state before replacing it.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(alias, true))
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        val envelope = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
        val stream = file.startWrite()
        try {
            Os.fchmod(stream.fd, 0x180) // 0600: the App UID alone can read and write.
            stream.write(envelope)
            Os.fsync(stream.fd)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        check(readEnvelope(file, maxPlainBytes).contentEquals(envelope)) { "SNAPSHOT_COMMIT_UNCONFIRMED" }
    }

    fun clear(file: AtomicFile, alias: String) {
        file.delete()
        check(!isPresent(file)) { "SNAPSHOT_DELETE_UNCONFIRMED" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun key(alias: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing != null) return existing as SecretKey
        check(create) { "ENCRYPTED_SNAPSHOT_KEY_MISSING" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
