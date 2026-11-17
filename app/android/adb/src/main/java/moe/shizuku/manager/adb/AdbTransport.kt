package moe.shizuku.manager.adb

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Shared socket ownership for pairing and connection; cancellation never frees JNI state. */
class AdbTransport(private val timeoutMillis: Int = 15000) : Closeable {
    private val lock = Any()
    private var raw: Socket? = null
    private var tls: SSLSocket? = null
    private var closed = false
    @Volatile var cancelled = false
        private set
    private var deadline = System.nanoTime() + timeoutMillis * 1_000_000L

    fun beginDeadline() {
        synchronized(lock) { check(!closed && !cancelled) { "连接已关闭。" } }
        deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    }

    fun remainingMillis(): Int {
        if (cancelled) throw AdbException("操作已取消或超时。")
        val remaining = (deadline - System.nanoTime()) / 1_000_000L
        if (remaining <= 0) throw SocketTimeoutException("ADB 操作超时。")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }

    fun checkActive() {
        remainingMillis()
    }

    fun connect(host: String, port: Int): Socket {
        val socket = Socket()
        synchronized(lock) {
            check(!closed && !cancelled) { "连接已关闭。" }
            raw = socket
        }
        socket.connect(InetSocketAddress(host, port), remainingMillis())
        socket.tcpNoDelay = true
        socket.soTimeout = remainingMillis()
        return socket
    }

    fun startTls(context: SSLContext, host: String, port: Int): SSLSocket {
        val plain = synchronized(lock) {
            check(!closed && !cancelled) { "连接已关闭。" }
            checkNotNull(raw)
        }
        val socket = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
        synchronized(lock) {
            if (closed || cancelled) {
                socket.close()
                throw AdbException("连接已关闭。")
            }
            tls = socket
        }
        socket.soTimeout = remainingMillis()
        socket.startHandshake()
        return socket
    }

    fun beforeRead() {
        val timeout = remainingMillis()
        synchronized(lock) {
            check(!closed) { "连接已关闭。" }
            raw?.soTimeout = timeout
            tls?.soTimeout = timeout
        }
    }

    fun cancel() {
        cancelled = true
        close()
    }

    override fun close() {
        val sockets = synchronized(lock) {
            if (closed) return
            closed = true
            val owned = raw to tls
            raw = null
            tls = null
            owned
        }
        // Close TCP first so TLS shutdown cannot wait for a peer response.
        runCatching { sockets.first?.close() }
        runCatching { sockets.second?.close() }
    }
}
