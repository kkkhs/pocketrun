package io.github.kkkhs.pocketrun

import android.content.Context
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.util.Base64
import java.io.Closeable
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PrivilegedBackendController(private val context: Context) : Closeable {
    data class Info(val running: Boolean, val uid: Int, val pid: Int, val reply: String)

    private val lock = Any()
    private var service: IPrivilegedBackend? = null
    private var serviceBinder: IBinder? = null
    private var clientToken: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var deathSignal: CountDownLatch? = null
    private var lastInfo = stoppedInfo()
    private var pendingToken: String? = null

    fun start(runCommand: (String) -> String): Info {
        synchronized(lock) {
            if (serviceBinder?.isBinderAlive == true) return pingLocked(checkNotNull(service))
            clearLocked()
            check(pendingToken == null) { "shell 后端正在启动。" }
        }

        val token = newToken()
        val appToken = Binder()
        val future = BinderExchangeRegistry.prepare(token, appToken)
        synchronized(lock) { pendingToken = token }
        var candidate: IPrivilegedBackend? = null
        try {
            val command = BackendLaunchCommand.build(
                context.applicationInfo.sourceDir,
                token,
                "${context.packageName}.binder_exchange",
                Process.myUid(),
                Process.myUid() / UID_PER_USER,
            )
            val launchOutput = runCommand(command).trim()
            require(launchOutput == BackendLaunchCommand.STARTED_MARKER) {
                "后端启动命令失败：${launchOutput.take(128)}"
            }
            val binder = await(future)
            candidate = IPrivilegedBackend.Stub.asInterface(binder)
                ?: error("Binder exchange 返回了空后端。")
            val signal = CountDownLatch(1)
            val recipient = IBinder.DeathRecipient {
                synchronized(lock) {
                    if (serviceBinder === binder) clearLocked()
                }
                signal.countDown()
            }
            binder.linkToDeath(recipient, 0)
            val info = query(candidate)
            require(info.uid == Process.SHELL_UID) { "后端不是 shell UID 2000。" }
            synchronized(lock) {
                if (pendingToken != token) throw CancellationException("shell 后端启动已取消。")
                service = candidate
                serviceBinder = binder
                clientToken = appToken
                deathRecipient = recipient
                deathSignal = signal
                lastInfo = info
                pendingToken = null
            }
            return info
        } catch (error: Exception) {
            runCatching { candidate?.shutdown() }
            throw error
        } finally {
            BinderExchangeRegistry.cancel(token)
            synchronized(lock) {
                if (pendingToken == token) pendingToken = null
            }
        }
    }

    fun ping(): Info = synchronized(lock) {
        val current = service ?: throw IllegalStateException("请先启动 shell 后端。")
        if (serviceBinder?.isBinderAlive != true) {
            clearLocked()
            throw IllegalStateException("shell 后端已经退出。")
        }
        pingLocked(current)
    }

    fun status(): Info = synchronized(lock) {
        if (serviceBinder?.isBinderAlive == true) {
            lastInfo
        } else {
            clearLocked()
            stoppedInfo()
        }
    }

    fun cancelStart() {
        val token = synchronized(lock) {
            val value = pendingToken
            pendingToken = null
            value
        }
        if (token != null) BinderExchangeRegistry.cancel(token)
    }

    fun stop() {
        cancelStart()
        val snapshot = synchronized(lock) {
            val current = service ?: return
            Triple(current, checkNotNull(serviceBinder), checkNotNull(deathSignal))
        }
        try {
            snapshot.first.shutdown()
        } catch (_: DeadObjectException) {
            synchronized(lock) {
                if (serviceBinder === snapshot.second) clearLocked()
            }
            return
        }
        if (!snapshot.third.await(3, TimeUnit.SECONDS) && snapshot.second.isBinderAlive) {
            throw IllegalStateException("shell 后端没有按时退出。")
        }
        synchronized(lock) {
            if (serviceBinder === snapshot.second) clearLocked()
        }
    }

    override fun close() {
        cancelStart()
        runCatching { stop() }
    }

    private fun pingLocked(current: IPrivilegedBackend): Info {
        val info = query(current)
        lastInfo = info
        return info
    }

    private fun query(current: IPrivilegedBackend) = Info(
        running = true,
        uid = current.uid,
        pid = current.pid,
        reply = current.ping(),
    )

    private fun clearLocked() {
        val binder = serviceBinder
        val recipient = deathRecipient
        if (binder != null && recipient != null && binder.isBinderAlive) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        service = null
        serviceBinder = null
        clientToken = null
        deathRecipient = null
        deathSignal?.countDown()
        deathSignal = null
        lastInfo = stoppedInfo()
    }

    private fun await(future: CompletableFuture<IBinder>): IBinder = try {
        future.get(10, TimeUnit.SECONDS)
    } catch (error: ExecutionException) {
        throw (error.cause as? Exception ?: error)
    } catch (_: TimeoutException) {
        throw IllegalStateException("等待 Binder exchange 超时。")
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val UID_PER_USER = 100_000
        private fun stoppedInfo() = Info(false, -1, -1, "")
    }
}
