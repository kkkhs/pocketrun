package io.github.kkkhs.pocketrun

import android.os.Build
import android.os.Process
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@ReactModule(name = LinuxModule.NAME)
class LinuxModule(private val context: ReactApplicationContext) : NativePocketRunLinuxSpec(context) {
    private val gate = LinuxOperationGate()
    private val worker = Executors.newSingleThreadExecutor()
    private var session: LinuxSession? = null

    override fun getName() = NAME
    override fun prepareLinux(token: String, promise: Promise) = submit(token, promise, false)
    override fun runLinuxDemo(token: String, promise: Promise) = submit(token, promise, true)
    override fun cancelLinux(token: String, promise: Promise) {
        synchronized(gate.lock) {
            if (gate.closed) promise.reject("E_LINUX_CLOSED", "Linux 模块已关闭。")
            else promise.resolve(session?.cancel(token) ?: false)
        }
    }

    private fun submit(token: String, promise: Promise, command: Boolean) {
        val epoch: Long
        val linux: LinuxSession
        val operation: LinuxSession.Operation
        synchronized(gate.lock) {
            if (gate.closed) { promise.reject("E_LINUX_CLOSED", "Linux 模块已关闭。"); return }
            if (gate.busy) { promise.reject("E_LINUX_BUSY", "请等待当前 Linux 操作结束。"); return }
            if (!LinuxOperationGate.supportsRuntime(Build.VERSION.SDK_INT, Process.is64Bit(),
                    Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a"))) {
                promise.reject("E_LINUX_UNSUPPORTED", "Linux 最小运行环境需要 Android 14 / API 34 及 ARM64 App 进程。")
                return
            }
            try {
                linux = session ?: createSession().also { session = it }
                operation = if (command) linux.reserve(token) else linux.reservePrepare(token)
            } catch (error: Exception) {
                promise.reject("E_LINUX_PREPARE", error.message, error)
                return
            }
            epoch = gate.begin()
        }
        try {
            worker.execute {
                val value: WritableMap
                try {
                    value = if (command) resultMap(linux.runDemo(operation)) else infoMap(linux.prepare(operation))
                } catch (error: Throwable) {
                    synchronized(gate.lock) {
                        val valid = gate.complete(epoch)
                        promise.reject(if (valid) "E_LINUX_OPERATION" else "E_LINUX_CLOSED", error.message, error)
                    }
                    if (error !is Exception) throw error
                    return@execute
                }
                synchronized(gate.lock) {
                    // Release before invoking Promise callbacks; there is no older finally to clear a newer slot.
                    if (gate.complete(epoch)) promise.resolve(value)
                    else promise.reject("E_LINUX_CLOSED", "Linux 结果已失效。")
                }
            }
        } catch (error: RejectedExecutionException) {
            linux.close()
            synchronized(gate.lock) {
                gate.complete(epoch)
                promise.reject("E_LINUX_CLOSED", "Linux 模块已关闭。", error)
            }
        }
    }

    private fun createSession(): LinuxSession {
        val runtime = LinuxRuntimeManifest.read(context.assets.open("linux/runtime.json"))
        // Platform filesDir may be the trusted /data/user/0 alias of /data/data; normalize it once.
        val files = context.filesDir.canonicalFile
        return LinuxSession(files,
            RootfsInstaller(files, File(context.applicationInfo.nativeLibraryDir).canonicalFile, runtime, context.assets::open),
            Process.myUid(), Process.myPid(), canRemoveRun = LinuxRecoveryProbe::isUnusedByAppProcesses)
    }

    override fun invalidate() {
        synchronized(gate.lock) { gate.close(); session?.close() }
        // Gracefully drain the already-reserved operation so its Promise settles and stage cleans up.
        worker.shutdown()
        super.invalidate()
    }

    private fun infoMap(info: LinuxSession.Info): WritableMap = Arguments.createMap().apply {
        putString("token", info.token)
        putString("installationGeneration", info.installationGeneration)
        putString("alpineVersion", info.alpineVersion)
        putInt("appUid", info.appUid)
        putString("rootfsDirectory", info.rootfsDirectory)
        putString("workspaceDirectory", info.workspaceDirectory)
        putString("guestCwd", info.guestCwd)
        putBoolean("installedNow", info.installedNow)
        putBoolean("sampleCreated", info.sampleCreated)
    }
    private fun resultMap(result: LinuxSession.Result): WritableMap = Arguments.createMap().apply {
        putString("token", result.token)
        putString("installationGeneration", result.installationGeneration)
        putString("status", result.status)
        putString("cleanupStatus", result.cleanupStatus)
        putString("stdout", result.stdout)
        putString("stderr", result.stderr)
        putArray("exitCodes", Arguments.createArray().apply { result.exitCodes.forEach(::pushInt) })
        putArray("stageExitCodes", Arguments.createArray().apply { result.stageExitCodes.forEach(::pushInt) })
        putDouble("durationMillis", result.durationMillis.toDouble())
        putInt("appUid", result.appUid)
        putString("alpineVersion", result.alpineVersion)
        if (result.guestUid == null) putNull("guestUid") else putInt("guestUid", result.guestUid)
        putString("cwd", result.cwd)
        if (result.lineCount == null) putNull("lineCount") else putInt("lineCount", result.lineCount)
    }
    companion object { const val NAME = "PocketRunLinux" }
}
