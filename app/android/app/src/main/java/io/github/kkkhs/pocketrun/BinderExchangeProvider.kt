package io.github.kkkhs.pocketrun

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

class BinderExchangeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_EXCHANGE) throw SecurityException("Binder exchange 被拒绝。")
        val token = extras?.getString(KEY_TOKEN) ?: throw SecurityException("Binder exchange 被拒绝。")
        val backend = extras.getBinder(KEY_BACKEND) ?: throw SecurityException("Binder exchange 被拒绝。")
        val client = BinderExchangeRegistry.exchange(Binder.getCallingUid(), token, backend)
        return Bundle().apply { putBinder(KEY_CLIENT, client) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw SecurityException("Binder exchange 不提供数据访问。")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw SecurityException("Binder exchange 不提供数据访问。")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Binder exchange 不提供数据访问。")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Binder exchange 不提供数据访问。")

    companion object {
        const val METHOD_EXCHANGE = "exchange"
        const val KEY_TOKEN = "token"
        const val KEY_BACKEND = "backend"
        const val KEY_CLIENT = "client"
    }
}

object BinderExchangeRegistry {
    private const val WINDOW_MILLIS = 10_000L
    private val lock = Any()
    private var pending: Pending? = null

    private class Pending(
        val token: ByteArray,
        val client: IBinder,
        val expiresAt: Long,
        val future: CompletableFuture<IBinder>,
    )

    fun prepare(token: String, client: IBinder): CompletableFuture<IBinder> {
        val future = CompletableFuture<IBinder>()
        synchronized(lock) {
            pending?.let {
                it.token.fill(0)
                it.future.completeExceptionally(CancellationException("Binder exchange 已替换。"))
            }
            pending = Pending(
                token.toByteArray(StandardCharsets.UTF_8),
                client,
                android.os.SystemClock.elapsedRealtime() + WINDOW_MILLIS,
                future,
            )
        }
        return future
    }

    fun exchange(callingUid: Int, token: String, backend: IBinder): IBinder {
        if (callingUid != Process.SHELL_UID) throw SecurityException("Binder exchange 被拒绝。")
        val requestToken = token.toByteArray(StandardCharsets.UTF_8)
        try {
            synchronized(lock) {
                val current = pending ?: throw SecurityException("Binder exchange 被拒绝。")
                if (android.os.SystemClock.elapsedRealtime() > current.expiresAt) {
                    pending = null
                    current.token.fill(0)
                    current.future.completeExceptionally(TimeoutException("Binder exchange 已过期。"))
                    throw SecurityException("Binder exchange 被拒绝。")
                }
                if (!MessageDigest.isEqual(current.token, requestToken) ||
                    backend.interfaceDescriptor != IPrivilegedBackend.DESCRIPTOR
                ) {
                    throw SecurityException("Binder exchange 被拒绝。")
                }
                pending = null
                current.token.fill(0)
                current.future.complete(backend)
                return current.client
            }
        } finally {
            requestToken.fill(0)
        }
    }

    fun cancel(token: String) {
        val requestToken = token.toByteArray(StandardCharsets.UTF_8)
        try {
            synchronized(lock) {
                val current = pending ?: return
                if (!MessageDigest.isEqual(current.token, requestToken)) return
                pending = null
                current.token.fill(0)
                current.future.completeExceptionally(CancellationException("Binder exchange 已取消。"))
            }
        } finally {
            requestToken.fill(0)
        }
    }
}
