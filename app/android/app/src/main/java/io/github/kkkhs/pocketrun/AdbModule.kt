package io.github.kkkhs.pocketrun

import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbInvalidPairingCodeException
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.AdbTransport
import moe.shizuku.manager.adb.PreferenceAdbKeyStore

@ReactModule(name = AdbModule.NAME)
class AdbModule(private val context: ReactApplicationContext) : NativePocketRunAdbSpec(context) {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val backend = PrivilegedBackendController(context)
    private var active: Operation? = null
    private var backendBusy = false
    private var connection: AdbClient? = null
    private var connectionTransport: AdbTransport? = null
    private var connectionPort = 0
    private var connectionUid = -1
    private var disposed = false
    private val key by lazy(LazyThreadSafetyMode.NONE) {
        AdbKey(PreferenceAdbKeyStore(context.getSharedPreferences("pocketrun_adb_identity", 0)), "pocketrun@android")
    }

    private enum class Kind(val needsConnection: Boolean) {
        Pair(false),
        Connect(false),
        Probe(true),
        StartBackend(true),
    }

    private class Operation(
        val kind: Kind,
        val port: Int,
        val transport: AdbTransport,
        var client: AdbClient? = null,
    ) {
        @Volatile var timedOut = false
    }

    override fun getName() = NAME

    override fun pair(port: Double, code: String, promise: Promise) {
        try {
            require(Regex("[0-9]{6}").matches(code)) { "请输入系统显示的六位配对码。" }
            begin(Kind.Pair, checkedPort(port), code, promise)
        } catch (error: Exception) {
            promise.reject("E_ADB_INPUT", error.message)
        }
    }

    override fun connect(port: Double, promise: Promise) {
        try {
            begin(Kind.Connect, checkedPort(port), null, promise)
        } catch (error: Exception) {
            promise.reject("E_ADB_INPUT", error.message)
        }
    }

    override fun probe(promise: Promise) {
        begin(Kind.Probe, 0, null, promise)
    }

    override fun startBackend(promise: Promise) {
        begin(Kind.StartBackend, 0, null, promise)
    }

    override fun pingBackend(promise: Promise) {
        runBackendAction(promise) { backendMap(backend.ping()) }
    }

    override fun stopBackend(promise: Promise) {
        runBackendAction(promise) {
            backend.stop()
            null
        }
    }

    override fun getBackendStatus(promise: Promise) {
        promise.resolve(backendMap(backend.status()))
    }

    override fun createDemoDisplay(promise: Promise) {
        runBackendAction(promise) { displayMap(backend.createDemoDisplay()) }
    }

    override fun getDisplayStatus(promise: Promise) {
        runBackendAction(promise) { displayMap(backend.displayStatus()) }
    }

    override fun releaseDisplay(promise: Promise) {
        runBackendAction(promise) { displayMap(backend.releaseDisplay()) }
    }

    private fun checkedPort(port: Double): Int {
        require(port.isFinite() && port in 1.0..65535.0 && port % 1.0 == 0.0) {
            "请输入 1～65535 的整数端口。"
        }
        return port.toInt()
    }

    private fun begin(kind: Kind, port: Int, code: String?, promise: Promise) {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < 30 || disposed) {
                promise.reject("E_ADB_UNAVAILABLE", "无线配对需要 Android 11 及以上，并且模块仍可用。")
                return
            }
            if (active != null || backendBusy) {
                promise.reject("E_ADB_BUSY", "已有操作进行中，请先取消或等待。")
                return
            }
            if (kind.needsConnection != (connection != null)) {
                promise.reject(
                    "E_ADB_STATE",
                    if (kind.needsConnection) "请先连接 ADB。" else "请先断开当前 ADB 连接。",
                )
                return
            }
            val operation = Operation(
                kind,
                if (kind.needsConnection) connectionPort else port,
                if (kind.needsConnection) checkNotNull(connectionTransport) else AdbTransport(),
                if (kind.needsConnection) connection else null,
            )
            active = operation
            val deadline = timer.schedule({
                synchronized(lock) {
                    if (active === operation) {
                        operation.timedOut = true
                        operation.transport.cancel()
                        if (operation.kind == Kind.StartBackend) backend.cancelStart()
                    }
                }
            }, 15, TimeUnit.SECONDS)
            worker.execute { runOperation(operation, code, promise, deadline) }
        }
    }

    private fun runOperation(
        operation: Operation,
        code: String?,
        promise: Promise,
        deadline: java.util.concurrent.ScheduledFuture<*>,
    ) {
        var result: Any? = null
        var failure: Exception? = null
        try {
            operation.transport.beginDeadline()
            when (operation.kind) {
                Kind.Pair -> {
                    AdbPairingClient(
                        "127.0.0.1",
                        operation.port,
                        checkNotNull(code),
                        key,
                        operation.transport,
                    ).use {
                        if (!it.start()) throw AdbInvalidPairingCodeException()
                    }
                }
                Kind.Connect, Kind.Probe -> {
                    val client = connectedClient(operation)
                    result = probeUid(client)
                }
                Kind.StartBackend -> {
                    val client = checkNotNull(operation.client)
                    result = backendMap(backend.start { command -> shellOutput(client, command) })
                }
            }
            operation.transport.checkActive()
        } catch (error: Exception) {
            failure = error
        } finally {
            deadline.cancel(false)
            if (failure != null && operation.kind == Kind.StartBackend) {
                backend.cancelStart()
                runCatching { backend.stop() }
            }
            synchronized(lock) {
                if (disposed || operation.transport.cancelled) {
                    failure = IllegalStateException("操作已取消。")
                }
                if (failure == null && operation.kind != Kind.Pair) {
                    connection = operation.client
                    connectionTransport = operation.transport
                    connectionPort = operation.port
                    if (operation.kind != Kind.StartBackend) connectionUid = result as Int
                } else {
                    operation.client?.close()
                    operation.transport.close()
                    if (operation.kind.needsConnection) clearConnectionLocked()
                }
                if (active === operation) active = null
            }
        }
        val error = failure
        if (error == null) promise.resolve(result)
        else promise.reject(
            when {
                operation.timedOut -> "E_ADB_TIMEOUT"
                operation.transport.cancelled -> "E_ADB_CANCELLED"
                error is AdbInvalidPairingCodeException -> "E_ADB_PAIRING"
                else -> "E_ADB_OPERATION"
            },
            when {
                operation.timedOut -> "操作超时，连接已关闭。"
                operation.transport.cancelled -> "操作已取消，连接已关闭。"
                error is AdbInvalidPairingCodeException -> "配对失败，请检查配对码和配对端口。"
                else -> error.message ?: "ADB 操作失败，请检查无线调试和端口。"
            },
        )
    }

    private fun connectedClient(operation: Operation): AdbClient =
        operation.client ?: AdbClient("127.0.0.1", operation.port, key, operation.transport).also {
            operation.client = it
            it.connect(requireTls = true)
        }

    private fun probeUid(client: AdbClient): Int {
        val output = ByteArrayOutputStream()
        client.shellCommand("id -u") { chunk ->
            require(output.size() + chunk.size <= 1024) { "连接验证输出过大。" }
            output.write(chunk)
        }
        val uid = output.toString("UTF-8").trim().toIntOrNull()
        require(uid != null && uid >= 0) { "连接端未返回有效 UID。" }
        return uid
    }

    private fun shellOutput(client: AdbClient, command: String): String {
        val output = ByteArrayOutputStream()
        client.shellCommand(command) { chunk ->
            require(output.size() + chunk.size <= 1024) { "后端启动输出过大。" }
            output.write(chunk)
        }
        return output.toString("UTF-8")
    }

    private fun runBackendAction(promise: Promise, action: () -> Any?) {
        synchronized(lock) {
            if (disposed) {
                promise.reject("E_ADB_UNAVAILABLE", "模块已关闭。")
                return
            }
            if (active != null || backendBusy) {
                promise.reject("E_ADB_BUSY", "已有操作进行中，请先取消或等待。")
                return
            }
            backendBusy = true
            worker.execute {
                var result: Any? = null
                var failure: Exception? = null
                try {
                    result = action()
                } catch (error: Exception) {
                    failure = error
                } finally {
                    synchronized(lock) { backendBusy = false }
                }
                if (failure == null) promise.resolve(result)
                else promise.reject("E_BACKEND_OPERATION", failure.message ?: "shell 后端操作失败。")
            }
        }
    }

    override fun disconnect(promise: Promise) {
        synchronized(lock) {
            if (disposed) {
                promise.reject("E_ADB_UNAVAILABLE", "模块已关闭。")
                return
            }
            backend.cancelStart()
            active?.transport?.cancel()
            connection?.close()
            clearConnectionLocked()
            worker.execute { promise.resolve(null) }
        }
    }

    override fun getStatus(promise: Promise) {
        val snapshot = synchronized(lock) {
            Arguments.createMap().apply {
                putBoolean("connected", connection != null)
                putBoolean("busy", active != null || backendBusy)
                putInt("port", connectionPort)
                putInt("uid", connectionUid)
            }
        }
        promise.resolve(snapshot)
    }

    private fun clearConnectionLocked() {
        connection = null
        connectionTransport = null
        connectionPort = 0
        connectionUid = -1
    }

    override fun invalidate() {
        synchronized(lock) {
            disposed = true
            backend.cancelStart()
            active?.transport?.cancel()
            connection?.close()
            clearConnectionLocked()
            timer.shutdownNow()
            worker.shutdown()
        }
        backend.close()
        super.invalidate()
    }

    private fun backendMap(info: PrivilegedBackendController.Info): WritableMap =
        Arguments.createMap().apply {
            putBoolean("running", info.running)
            putInt("uid", info.uid)
            putInt("pid", info.pid)
            putString("reply", info.reply)
        }

    private fun displayMap(info: PrivilegedBackendController.DisplayInfo): WritableMap =
        Arguments.createMap().apply {
            putBoolean("active", info.active)
            putInt("displayId", info.displayId)
        }

    companion object { const val NAME = "PocketRunAdb" }
}
