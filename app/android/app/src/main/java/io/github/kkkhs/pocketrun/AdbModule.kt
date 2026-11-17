package io.github.kkkhs.pocketrun

import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
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
    private var active: Operation? = null
    private var connection: AdbClient? = null
    private var connectionTransport: AdbTransport? = null
    private var connectionPort = 0
    private var connectionUid = -1
    private var disposed = false
    private val key by lazy(LazyThreadSafetyMode.NONE) {
        AdbKey(PreferenceAdbKeyStore(context.getSharedPreferences("pocketrun_adb_identity", 0)), "pocketrun@android")
    }

    private class Operation(val kind: String, val port: Int, val transport: AdbTransport, var client: AdbClient? = null) {
        @Volatile var timedOut = false
    }

    override fun getName() = NAME

    override fun pair(port: Double, code: String, promise: Promise) {
        try {
            require(Regex("[0-9]{6}").matches(code)) { "请输入系统显示的六位配对码。" }
            begin("pair", checkedPort(port), code, promise)
        } catch (error: Exception) {
            promise.reject("E_ADB_INPUT", error.message)
        }
    }

    override fun connect(port: Double, promise: Promise) {
        try {
            begin("connect", checkedPort(port), null, promise)
        } catch (error: Exception) {
            promise.reject("E_ADB_INPUT", error.message)
        }
    }

    override fun probe(promise: Promise) {
        begin("probe", 0, null, promise)
    }

    private fun checkedPort(port: Double): Int {
        require(port.isFinite() && port in 1.0..65535.0 && port % 1.0 == 0.0) {
            "请输入 1～65535 的整数端口。"
        }
        return port.toInt()
    }

    private fun begin(kind: String, port: Int, code: String?, promise: Promise) {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT < 30 || disposed) {
                promise.reject("E_ADB_UNAVAILABLE", "无线配对需要 Android 11 及以上，并且模块仍可用。")
                return
            }
            if (active != null) {
                promise.reject("E_ADB_BUSY", "已有操作进行中，请先取消或等待。")
                return
            }
            if ((kind == "probe") != (connection != null)) {
                promise.reject("E_ADB_STATE", if (kind == "probe") "请先连接。" else "请先断开当前连接。")
                return
            }
            val operation = Operation(kind, if (kind == "probe") connectionPort else port,
                if (kind == "probe") checkNotNull(connectionTransport) else AdbTransport(),
                if (kind == "probe") connection else null)
            active = operation
            val deadline = timer.schedule({
                synchronized(lock) {
                    if (active === operation) {
                        operation.timedOut = true
                        operation.transport.cancel()
                    }
                }
            }, 15, TimeUnit.SECONDS)
            worker.execute {
                var result: Int? = null
                var failure: Exception? = null
                try {
                    operation.transport.beginDeadline()
                    if (kind == "pair") {
                        AdbPairingClient("127.0.0.1", port, checkNotNull(code), key, operation.transport).use {
                            if (!it.start()) throw AdbInvalidPairingCodeException()
                        }
                    } else {
                        val client = operation.client ?: AdbClient("127.0.0.1", port, key, operation.transport).also {
                            operation.client = it
                            it.connect(requireTls = true)
                        }
                        val output = ByteArrayOutputStream()
                        client.shellCommand("id -u") { chunk ->
                            require(output.size() + chunk.size <= 1024) { "连接验证输出过大。" }
                            output.write(chunk)
                        }
                        result = output.toString("UTF-8").trim().toIntOrNull()
                        require(result != null && result!! >= 0) { "连接端未返回有效 UID。" }
                    }
                    operation.transport.checkActive()
                } catch (error: Exception) {
                    failure = error
                } finally {
                    deadline.cancel(false)
                    synchronized(lock) {
                        if (disposed || operation.transport.cancelled) failure = IllegalStateException("操作已取消。")
                        if (failure == null && kind != "pair") {
                            connection = operation.client
                            connectionTransport = operation.transport
                            connectionPort = operation.port
                            connectionUid = checkNotNull(result)
                        } else {
                            operation.client?.close()
                            operation.transport.close()
                            if (kind == "probe") {
                                connection = null
                                connectionTransport = null
                                connectionPort = 0
                                connectionUid = -1
                            }
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
                    }
                )
            }
        }
    }

    override fun disconnect(promise: Promise) {
        synchronized(lock) {
            if (disposed) {
                promise.reject("E_ADB_UNAVAILABLE", "模块已关闭。")
                return
            }
            active?.transport?.cancel()
            connection?.close()
            connection = null
            connectionTransport = null
            connectionPort = 0
            connectionUid = -1
            // FIFO: settle only after the owning worker has released pairing JNI state.
            worker.execute { promise.resolve(null) }
        }
    }

    override fun getStatus(promise: Promise) {
        val snapshot = synchronized(lock) {
            Arguments.createMap().apply {
                putBoolean("connected", connection != null)
                putBoolean("busy", active != null)
                putInt("port", connectionPort)
                putInt("uid", connectionUid)
            }
        }
        promise.resolve(snapshot)
    }

    override fun invalidate() {
        synchronized(lock) {
            disposed = true
            active?.transport?.cancel()
            connection?.close()
            connection = null
            connectionTransport = null
            timer.shutdownNow()
            worker.shutdown()
        }
        super.invalidate()
    }

    companion object { const val NAME = "PocketRunAdb" }
}
