package io.github.kkkhs.pocketrun

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.ImageReader
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit

class VirtualDisplaySession(private val packageName: String) : Closeable {
    private val lock = Any()
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displayId = -1

    fun createDemoDisplay(): Int = synchronized(lock) {
        check(displayId < 0) { "请先释放当前虚拟屏。" }
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 4)
        var selected: VirtualDisplay? = null
        var lastFailure: Exception? = null
        for (flags in FLAG_MASKS) {
            try {
                val candidate = createVirtualDisplay(reader, flags) ?: continue
                if (candidate.display.displayId <= 0) {
                    candidate.release()
                    continue
                }
                selected = candidate
                break
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        if (selected == null) {
            reader.close()
            throw IllegalStateException("无法创建安全的虚拟屏。", lastFailure)
        }
        imageReader = reader
        virtualDisplay = selected
        displayId = selected.display.displayId
        try {
            launchDemo(displayId)
        } catch (error: Exception) {
            closeLocked()
            throw error
        }
        displayId
    }

    fun getDisplayId(): Int = synchronized(lock) { displayId }

    override fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        displayId = -1
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
            process.waitFor()
            throw IllegalStateException("启动虚拟屏演示页超时。")
        }
        check(process.exitValue() == 0) { "启动虚拟屏演示页失败。" }
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val DENSITY_DPI = 320
        const val DISPLAY_NAME = "PocketRun-Demo"
        const val SHELL_PACKAGE = "com.android.shell"

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
