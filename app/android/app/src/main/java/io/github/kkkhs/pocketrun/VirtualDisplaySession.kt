package io.github.kkkhs.pocketrun

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SharedMemory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

class VirtualDisplaySession(private val packageName: String) : Closeable {
    private val lock = Any()
    private var owned: OwnedDisplay? = null
    private var terminated = false

    fun createDemoDisplay(): Int = synchronized(lock) {
        check(!terminated) { "shell 后端正在停止。" }
        check(owned == null) { "请先释放当前虚拟屏。" }
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 4)
        val frames = try {
            FrameReader(reader)
        } catch (error: Throwable) {
            reader.close()
            throw error
        }
        var selected: VirtualDisplay? = null
        var lastFailure: Exception? = null
        try {
            frames.start()
            for (flags in FLAG_MASKS) {
                var candidate: VirtualDisplay? = null
                try {
                    val created = createVirtualDisplay(reader, flags) ?: continue
                    candidate = created
                    if (created.display.displayId <= 0) continue
                    selected = created
                    break
                } catch (error: Exception) {
                    lastFailure = error
                } finally {
                    if (candidate !== selected) runCatching { candidate?.release() }
                }
            }
            val display = selected ?: throw IllegalStateException("无法创建安全的虚拟屏。", lastFailure)
            val session = OwnedDisplay(display.display.displayId, display, frames)
            owned = session
            launchDemo(session.displayId)
            session.displayId
        } catch (error: Throwable) {
            val current = owned
            owned = null
            if (current != null) current.close() else {
                runCatching { selected?.release() }
                frames.close()
            }
            throw error
        }
    }

    fun getDisplayId(): Int = synchronized(lock) { owned?.displayId ?: -1 }

    fun captureScreenshot(expectedDisplayId: Int): SharedMemory = synchronized(lock) {
        val current = requireOwnedLocked(expectedDisplayId)
        ScreenshotCodec.toSharedMemory(current.frames.capture(current.displayId))
    }

    fun dumpUiTree(expectedDisplayId: Int): ByteArray {
        val query = synchronized(lock) {
            requireOwnedLocked(expectedDisplayId).automation.beginQuery()
        }
        // A timeout/cancellation must not hold up releaseDisplay() or a new session.
        return query.await()
    }

    fun injectTap(expectedDisplayId: Int, x: Int, y: Int, appUid: Int): Boolean = synchronized(lock) {
        val current = requireOwnedLocked(expectedDisplayId)
        DisplayInput(appUid).tap(current.displayId, x, y)
    }

    fun injectText(expectedDisplayId: Int, text: String, appUid: Int): Boolean = synchronized(lock) {
        val current = requireOwnedLocked(expectedDisplayId)
        DisplayInput(appUid).text(current.displayId, text)
    }

    private fun requireOwnedLocked(expectedDisplayId: Int): OwnedDisplay {
        DisplayInputPolicy.requireDisplay(expectedDisplayId, owned?.displayId ?: -1)
        return checkNotNull(owned)
    }

    override fun close() {
        synchronized(lock) { closeLocked() }
    }

    internal fun terminate() {
        synchronized(lock) {
            terminated = true
            closeLocked()
        }
    }

    private fun closeLocked() {
        val previous = owned
        owned = null
        previous?.close()
    }

    private class OwnedDisplay(
        val displayId: Int,
        private val display: VirtualDisplay,
        val frames: FrameReader,
    ) : Closeable {
        val automation = DisplayAutomation(displayId)

        override fun close() {
            try {
                automation.close()
            } finally {
                runCatching { display.release() }
                frames.close()
            }
        }
    }

    private class FrameReader(private val reader: ImageReader) : Closeable {
        private val imageLock = Any()
        private val thread = HandlerThread("PocketRun-DisplayFrames")
        private var latest: Image? = null
        private var closed = false

        fun start() {
            thread.start()
            reader.setOnImageAvailableListener({
                synchronized(imageLock) {
                    if (!closed) runCatching { drainLatestLocked() }
                }
            }, Handler(thread.looper))
        }

        private fun drainLatestLocked() {
            val next = reader.acquireLatestImage() ?: return
            val previous = latest
            latest = next
            previous?.close()
        }

        fun capture(displayId: Int): ScreenshotCodec.Packet {
            val frame = synchronized(imageLock) {
                check(!closed) { "虚拟屏图像读取器已关闭。" }
                drainLatestLocked()
                val image = latest ?: error("虚拟屏还没有可用帧。")
                check(image.timestamp > 0) { "虚拟屏帧时间戳无效。" }
                val crop = image.cropRect
                check(crop.width() == WIDTH && crop.height() == HEIGHT &&
                    crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height) {
                    "虚拟屏帧尺寸无效。"
                }
                val plane = image.planes.firstOrNull() ?: error("虚拟屏帧没有像素平面。")
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                check(pixelStride >= 4 && rowStride.toLong() >= image.width.toLong() * pixelStride) {
                    "虚拟屏像素布局无效。"
                }
                val pixels = plane.buffer.duplicate()
                val base = pixels.position().toLong()
                val end = base + (crop.bottom - 1).toLong() * rowStride +
                    (crop.right - 1).toLong() * pixelStride + 4
                check(end <= pixels.limit().toLong()) { "虚拟屏像素平面过短。" }
                // Copy only the valid RGBA area, never row padding. No padded Bitmap is retained.
                val colors = IntArray(WIDTH * HEIGHT)
                for (y in 0 until HEIGHT) {
                    var offset = (base + (y + crop.top).toLong() * rowStride +
                        crop.left.toLong() * pixelStride).toInt()
                    for (x in 0 until WIDTH) {
                        val red = pixels.get(offset).toInt() and 0xff
                        val green = pixels.get(offset + 1).toInt() and 0xff
                        val blue = pixels.get(offset + 2).toInt() and 0xff
                        val alpha = pixels.get(offset + 3).toInt() and 0xff
                        colors[y * WIDTH + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                        offset += pixelStride
                    }
                }
                image.timestamp to colors
            }
            val bitmap = Bitmap.createBitmap(frame.second, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                val output = BoundedJpegOutput()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "虚拟屏 JPEG 编码失败。" }
                val jpeg = output.toByteArray()
                check(jpeg.isNotEmpty()) { "虚拟屏 JPEG 为空。" }
                return ScreenshotCodec.Packet(displayId, WIDTH, HEIGHT, frame.first, jpeg)
            } finally {
                bitmap.recycle()
            }
        }

        override fun close() {
            synchronized(imageLock) {
                if (closed) return
                closed = true
                runCatching { reader.setOnImageAvailableListener(null, null) }
                runCatching { latest?.close() }
                latest = null
                runCatching { reader.close() }
            }
            // The callback takes imageLock; joining here could deadlock it. Quit without join.
            thread.quitSafely()
        }
    }

    private class BoundedJpegOutput : ByteArrayOutputStream(32 * 1024) {
        override fun write(value: Int) {
            check(count < ScreenshotCodec.MAX_JPEG_BYTES) { "虚拟屏 JPEG 超出 2 MiB。" }
            super.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length >= 0 && length <= ScreenshotCodec.MAX_JPEG_BYTES - count) { "虚拟屏 JPEG 超出 2 MiB。" }
            super.write(bytes, offset, length)
        }
    }

    private fun createVirtualDisplay(reader: ImageReader, flags: Int): VirtualDisplay? {
        val config = VirtualDisplayConfig.Builder(DISPLAY_NAME, WIDTH, HEIGHT, DENSITY_DPI)
            .setSurface(reader.surface)
            .setFlags(flags)
            .build()
        val shellContext = object : ContextWrapper(null) {
            override fun getPackageName() = SHELL_PACKAGE
            override fun getOpPackageName() = SHELL_PACKAGE
        }
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getDeclaredMethod("getInstance").invoke(null)
        val create = globalClass.methods.first {
            it.name == "createVirtualDisplay" &&
                it.parameterCount == 5 &&
                it.parameterTypes.first() == Context::class.java
        }.apply { isAccessible = true }
        return create.invoke(global, shellContext, null, config, null, null) as VirtualDisplay?
    }

    private fun launchDemo(id: Int) {
        val process = ProcessBuilder(buildDemoLaunchArguments(packageName, id))
            .redirectOutput(File("/dev/null"))
            .redirectError(File("/dev/null"))
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            // Keep even failed lifecycle operations bounded; no unbounded process wait.
            process.waitFor(1, TimeUnit.SECONDS)
            throw IllegalStateException("启动虚拟屏演示页超时。")
        }
        check(process.exitValue() == 0) { "启动虚拟屏演示页失败。" }
    }

    companion object {
        const val WIDTH = DisplayInputPolicy.WIDTH
        const val HEIGHT = DisplayInputPolicy.HEIGHT
        const val DENSITY_DPI = 320
        const val DISPLAY_NAME = "PocketRun-Demo"
        const val SHELL_PACKAGE = "com.android.shell"
        private const val JPEG_QUALITY = 90

        fun buildDemoLaunchArguments(packageName: String, displayId: Int): List<String> {
            require(Regex("[A-Za-z0-9_.]+").matches(packageName)) { "Demo package 无效。" }
            require(displayId > 0) { "Demo displayId 无效。" }
            return listOf(
                "/system/bin/am",
                "start",
                "-W",
                "--display",
                displayId.toString(),
                "-n",
                "$packageName/.DisplayDemoActivity",
            )
        }

        // Same compatibility ladder as the target architecture, excluding the final
        // main-display-mirroring fallback because it lacks OWN_CONTENT_ONLY.
        private val FLAG_MASKS = intArrayOf(0x1fcc9, 0x1ecc9, 0x1f8c9, 0x1e8c9, 0x160c9)
    }
}
