package io.github.kkkhs.pocketrun

import android.content.Context
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PrivilegedBackendController(private val context: Context) : Closeable {
    data class Info(val running: Boolean, val uid: Int, val pid: Int, val reply: String)
    data class DisplayInfo(val active: Boolean, val displayId: Int)
    data class CapabilitySnapshot(val ready: Boolean, val displayId: Int, val queryGeneration: Long)
    data class CapabilityDeath(val displayId: Int, val queryGeneration: Long)
    data class ScreenshotInfo(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val frameTimestampNanos: String,
        val uri: String,
    )
    data class UiTreeInfo(
        val displayId: Int,
        val status: String,
        val truncated: Boolean,
        val nodeCount: Int,
        val json: String,
    )
    private data class QuerySession(val service: IPrivilegedBackend, val binder: IBinder, val generation: Long)

    private val lock = Any()
    private var service: IPrivilegedBackend? = null
    private var serviceBinder: IBinder? = null
    private var clientToken: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var deathSignal: CountDownLatch? = null
    private var lastInfo = stoppedInfo()
    private var pendingToken: String? = null
    private var generation = 0L
    private var lastDisplayId = -1
    private val frameCaches = FrameCacheSlots()
    private val capabilityDeathListeners = LinkedHashSet<(CapabilityDeath) -> Unit>()
    private val capabilityDeathExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketRun-BackendDeath").apply { isDaemon = true }
    }

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
        pingLocked(requireServiceLocked())
    }

    fun status(): Info = synchronized(lock) {
        if (serviceBinder?.isBinderAlive == true) {
            lastInfo
        } else {
            clearLocked()
            stoppedInfo()
        }
    }

    fun createDemoDisplay(): DisplayInfo = synchronized(lock) {
        val current = requireServiceLocked()
        val id = current.createDemoDisplay()
        require(id > 0) { "后端返回了无效 displayId。" }
        lastDisplayId = id
        invalidateQueriesLocked()
        DisplayInfo(true, id)
    }

    fun displayStatus(): DisplayInfo = synchronized(lock) {
        val current = requireServiceLocked()
        val id = current.displayId
        lastDisplayId = id.takeIf { it > 0 } ?: -1
        if (id <= 0) invalidateQueriesLocked()
        DisplayInfo(id > 0, id)
    }

    fun releaseDisplay(): DisplayInfo {
        val outcome = synchronized(lock) {
            val event = lastDisplayId.takeIf { it > 0 }?.let { CapabilityDeath(it, generation) }
            val notification = event?.let { it to capabilityDeathListeners.toList() }
            invalidateQueriesLocked()
            val result = try {
                requireServiceLocked().releaseDisplay()
                lastDisplayId = -1
                Result.success(DisplayInfo(false, -1))
            } catch (error: Throwable) {
                Result.failure(error)
            }
            result to notification
        }
        outcome.second?.let { (event, listeners) -> notifyCapabilityDeath(event, listeners) }
        return outcome.first.getOrThrow()
    }

    fun captureScreenshot(expectedDisplayId: Int, expectedGeneration: Long = queryGeneration()): ScreenshotInfo =
        captureScreenshotOwned(expectedDisplayId, expectedGeneration, taskHostOwned = false)

    fun captureTaskHostScreenshot(expectedDisplayId: Int, expectedGeneration: Long): ScreenshotInfo =
        captureScreenshotOwned(expectedDisplayId, expectedGeneration, taskHostOwned = true)

    private fun captureScreenshotOwned(expectedDisplayId: Int, expectedGeneration: Long,
        taskHostOwned: Boolean): ScreenshotInfo {
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val memory = snapshot.service.captureScreenshot(expectedDisplayId)
            ?: error("后端没有返回截图。")
        val packet = memory.use { ScreenshotCodec.fromSharedMemory(it, expectedDisplayId) }
        require(packet.displayId == expectedDisplayId) { "截图 displayId 已失效。" }
        require(packet.width == DISPLAY_WIDTH && packet.height == DISPLAY_HEIGHT && packet.frameTimestampNanos > 0) {
            "截图尺寸或时间戳无效。"
        }
        require(packet.jpeg.size in 1..ScreenshotCodec.MAX_JPEG_BYTES) { "截图超过 2 MiB。" }
        return synchronized(lock) {
            checkSessionLocked(snapshot)
            val prefix = if (taskHostOwned) "pocketrun-taskhost-frame-" else "pocketrun-frame-"
            val file = File.createTempFile(prefix, ".jpg", context.cacheDir)
            try {
                Os.chmod(file.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
                file.outputStream().use { it.write(packet.jpeg) }
                val info = ScreenshotInfo(packet.displayId, packet.width, packet.height,
                    packet.frameTimestampNanos.toString(), Uri.fromFile(file).toString())
                if (taskHostOwned) frameCaches.replaceTaskHost(file)
                else frameCaches.replaceDisplay(file)
                info
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }

    private fun readTree(snapshot: QuerySession, expectedDisplayId: Int): UiTreeCodec.Tree {
        val bytes = snapshot.service.dumpUiTree(expectedDisplayId) ?: error("后端没有返回 UI 树。")
        require(bytes.size <= UiTreeCodec.MAX_TREE_BYTES) { "UI 树数据过大。" }
        return UiTreeCodec.decode(bytes, expectedDisplayId)
    }

    internal fun dumpTypedUiTree(expectedDisplayId: Int, expectedGeneration: Long): UiTreeCodec.Tree {
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val tree = readTree(snapshot, expectedDisplayId)
        return synchronized(lock) { checkSessionLocked(snapshot); tree }
    }

    fun dumpUiTree(expectedDisplayId: Int, expectedGeneration: Long = queryGeneration()): UiTreeInfo {
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val tree = readTree(snapshot, expectedDisplayId)
        val nodes = JSONArray()
        for (node in tree.nodes) {
            nodes.put(JSONObject().apply {
                put("parentIndex", node.parentIndex)
                put("windowId", node.windowId)
                put("depth", node.depth)
                put("className", node.className)
                put("packageName", node.packageName)
                put("viewId", node.viewId)
                put("text", node.text)
                put("contentDescription", node.contentDescription)
                put("bounds", JSONObject().apply {
                    put("left", node.left)
                    put("top", node.top)
                    put("right", node.right)
                    put("bottom", node.bottom)
                })
                put("flags", JSONObject().apply {
                    put("clickable", node.clickable)
                    put("editable", node.editable)
                    put("enabled", node.enabled)
                    put("focused", node.focused)
                })
            })
        }
        val json = JSONObject().apply {
            put("displayId", tree.displayId)
            put("status", tree.status.wire)
            put("truncated", tree.truncated)
            put("nodes", nodes)
        }.toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "UI 树 JSON 过大。" }
        return synchronized(lock) {
            checkSessionLocked(snapshot)
            UiTreeInfo(tree.displayId, tree.status.wire, tree.truncated, tree.nodes.size, json)
        }
    }

    fun injectTap(expectedDisplayId: Int, x: Int, y: Int, expectedGeneration: Long = queryGeneration()): Boolean {
        require(x in 0 until DISPLAY_WIDTH && y in 0 until DISPLAY_HEIGHT) { "点击坐标超出虚拟屏。" }
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val result = snapshot.service.injectTap(expectedDisplayId, x, y)
        synchronized(lock) { checkSessionLocked(snapshot) }
        return result
    }

    fun injectText(expectedDisplayId: Int, text: String, expectedGeneration: Long = queryGeneration()): Boolean {
        require(text.length in 1..64 && text.all { it in ' '..'~' }) { "仅允许最多 64 个可打印 ASCII 字符。" }
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val result = snapshot.service.injectText(expectedDisplayId, text)
        synchronized(lock) { checkSessionLocked(snapshot) }
        return result
    }

    fun launchAppOnDisplay(expectedDisplayId: Int, packageName: String, expectedGeneration: Long = queryGeneration()): Boolean {
        VirtualDisplaySession.requireValidPackageName(packageName)
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val result = snapshot.service.launchAppOnDisplay(expectedDisplayId, packageName)
        synchronized(lock) { checkSessionLocked(snapshot) }
        return result
    }

    fun injectAppTap(expectedDisplayId: Int, x: Int, y: Int, expectedGeneration: Long = queryGeneration()): Boolean {
        require(DisplayInputPolicy.acceptsTap(x, y)) { "点击坐标超出虚拟屏。" }
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val result = snapshot.service.injectAppTap(expectedDisplayId, x, y)
        synchronized(lock) { checkSessionLocked(snapshot) }
        return result
    }

    fun injectAppSwipe(
        expectedDisplayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        expectedGeneration: Long = queryGeneration(),
    ): Boolean {
        require(DisplayInputPolicy.acceptsSwipe(x1, y1, x2, y2, durationMs)) { "滑动坐标或时长无效。" }
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val result = snapshot.service.injectAppSwipe(expectedDisplayId, x1, y1, x2, y2, durationMs)
        synchronized(lock) { checkSessionLocked(snapshot) }
        return result
    }

    fun injectAppKey(expectedDisplayId: Int, keyCode: Int, expectedGeneration: Long = queryGeneration()): Boolean {
        require(DisplayInputPolicy.acceptsKeyCode(keyCode)) { "不支持的按键编码。" }
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val result = snapshot.service.injectAppKey(expectedDisplayId, keyCode)
        synchronized(lock) { checkSessionLocked(snapshot) }
        return result
    }

    fun injectAppText(expectedDisplayId: Int, text: String, expectedGeneration: Long = queryGeneration()): Boolean {
        require(DisplayInputPolicy.acceptsAppText(text)) { "文本长度须在 1..256 字符之间。" }
        val snapshot = querySession(expectedDisplayId, expectedGeneration)
        val result = snapshot.service.injectAppText(expectedDisplayId, text)
        synchronized(lock) { checkSessionLocked(snapshot) }
        return result
    }

    fun invalidateQueries() {
        val notification = synchronized(lock) {
            val event = lastDisplayId.takeIf { it > 0 }?.let { CapabilityDeath(it, generation) }
            invalidateQueriesLocked()
            event?.let { it to capabilityDeathListeners.toList() }
        }
        notification?.let { (event, listeners) -> notifyCapabilityDeath(event, listeners) }
    }

    fun queryGeneration(): Long = synchronized(lock) { generation }

    fun capabilitySnapshot(): CapabilitySnapshot = synchronized(lock) {
        val current = service
        if (current == null || serviceBinder?.isBinderAlive != true) {
            return@synchronized CapabilitySnapshot(false, -1, generation)
        }
        val id = current.displayId
        lastDisplayId = id.takeIf { it > 0 } ?: -1
        CapabilitySnapshot(id > 0 && lastInfo.uid == Process.SHELL_UID, id, generation)
    }

    fun addCapabilityDeathListener(listener: (CapabilityDeath) -> Unit): Closeable {
        synchronized(lock) { capabilityDeathListeners.add(listener) }
        return Closeable { synchronized(lock) { capabilityDeathListeners.remove(listener) } }
    }

    fun invalidateQueriesIfCurrent(expectedDisplayId: Int, expectedGeneration: Long): Boolean = synchronized(lock) {
        if (generation != expectedGeneration || serviceBinder?.isBinderAlive != true ||
            service?.displayId != expectedDisplayId) return@synchronized false
        invalidateQueriesLocked()
        true
    }

    private fun querySession(expectedDisplayId: Int, expectedGeneration: Long): QuerySession = synchronized(lock) {
        require(expectedDisplayId > 0) { "expectedDisplayId 必须为正整数。" }
        check(generation == expectedGeneration) { "虚拟屏操作已取消。" }
        QuerySession(requireServiceLocked(), checkNotNull(serviceBinder), generation)
    }

    private fun checkSessionLocked(snapshot: QuerySession) {
        check(service === snapshot.service && serviceBinder === snapshot.binder &&
            snapshot.binder.isBinderAlive && generation == snapshot.generation) { "后端或虚拟屏已失效，结果已丢弃。" }
    }

    private fun notifyCapabilityDeath(event: CapabilityDeath, listeners: List<(CapabilityDeath) -> Unit>) {
        for (listener in listeners) {
            try { listener(event) }
            catch (error: Throwable) { Log.e(TAG, "TaskHost backend-death listener failed", error) }
        }
    }

    private fun deleteAllCachesLocked() {
        try { frameCaches.clear() }
        catch (error: IOException) { Log.e(TAG, "Owned screenshot cache cleanup failed", error) }
    }

    private fun invalidateQueriesLocked() {
        generation++
        deleteAllCachesLocked()
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
        invalidateQueries()
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
        invalidateQueries()
        cancelStart()
        runCatching { stop() }
        synchronized(lock) {
            clearLocked()
            capabilityDeathListeners.clear()
        }
        capabilityDeathExecutor.shutdown()
    }

    private fun requireServiceLocked(): IPrivilegedBackend {
        val current = service ?: throw IllegalStateException("请先启动 shell 后端。")
        if (serviceBinder?.isBinderAlive != true) {
            clearLocked()
            throw IllegalStateException("shell 后端已经退出。")
        }
        return current
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

    private fun scheduleCapabilityDeathLocked() {
        val id = lastDisplayId
        if (id <= 0 || capabilityDeathListeners.isEmpty()) return
        val event = CapabilityDeath(id, generation)
        val listeners = capabilityDeathListeners.toList()
        try {
            capabilityDeathExecutor.execute { notifyCapabilityDeath(event, listeners) }
        } catch (error: RejectedExecutionException) {
            Log.e(TAG, "TaskHost backend-death dispatch rejected", error)
        }
    }

    private fun clearLocked() {
        scheduleCapabilityDeathLocked()
        invalidateQueriesLocked()
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
        lastDisplayId = -1
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
        private const val TAG = "PocketRunBackend"
        private const val UID_PER_USER = 100_000
        private const val DISPLAY_WIDTH = 720
        private const val DISPLAY_HEIGHT = 1280
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private fun stoppedInfo() = Info(false, -1, -1, "")
    }
}
