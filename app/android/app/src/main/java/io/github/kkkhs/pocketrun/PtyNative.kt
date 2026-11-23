package io.github.kkkhs.pocketrun

import java.io.File
import java.io.IOException

/** Trusted App-internal launch configuration. It is deliberately not a JS-facing command shape. */
internal class PtyTrustedConfig private constructor(
    val nativeLibraryDirectory: File,
    val rootfsDirectory: File,
    val workspaceDirectory: File,
    val runDirectory: File,
    val temporaryDirectory: File,
    val appUid: Int,
    val parentPid: Int,
) {
    companion object {
        fun create(
            filesDirectory: File,
            nativeLibraryDirectory: File,
            rootfsDirectory: File,
            workspaceDirectory: File,
            runDirectory: File,
            appUid: Int,
            parentPid: Int,
        ): PtyTrustedConfig {
            require(appUid > 0 && parentPid > 1) { "Invalid App process identity." }
            val files = filesDirectory.canonicalFile
            val native = nativeLibraryDirectory.canonicalFile
            val root = rootfsDirectory.canonicalFile
            val workspace = workspaceDirectory.canonicalFile
            val run = runDirectory.canonicalFile
            fun requireChild(value: File, label: String) {
                require(value.toPath().startsWith(files.toPath()) && value != files) { "$label must remain in filesDir." }
            }
            requireChild(root, "rootfs")
            requireChild(workspace, "workspace")
            requireChild(run, "run directory")
            val tmp = File(run, "tmp").canonicalFile
            requireChild(tmp, "temporary directory")
            require(tmp.parentFile == run) { "Temporary directory alias is not allowed." }
            return PtyTrustedConfig(native, root, workspace, run, tmp, appUid, parentPid)
        }
    }
}

internal data class PtyExit(
    val kind: Kind,
    val code: Int,
    val childPid: Int,
    /** This only proves the direct native tracer was reaped; descendant proof remains an integration obligation. */
    val rootReaped: Boolean,
) {
    enum class Kind { EXITED, SIGNALED }
}

internal interface PtyBackend {
    @Throws(IOException::class) fun open(config: PtyTrustedConfig): Long
    @Throws(IOException::class) fun read(handle: Long, maximumBytes: Int): ByteArray?
    @Throws(IOException::class) fun write(handle: Long, bytes: ByteArray): Int
    @Throws(IOException::class) fun resize(handle: Long, columns: Int, rows: Int)
    @Throws(IOException::class) fun signalOwned(handle: Long, signal: Int)
    @Throws(IOException::class) fun terminateRoot(handle: Long): Boolean
    @Throws(IOException::class) fun waitForExit(handle: Long): PtyExit
    fun close(handle: Long): Boolean
}

internal object NativePtyBackend : PtyBackend {
    override fun open(config: PtyTrustedConfig): Long = PtyNative.nativeOpen(
        config.nativeLibraryDirectory.path,
        config.rootfsDirectory.path,
        config.workspaceDirectory.path,
        config.runDirectory.path,
        config.temporaryDirectory.path,
        config.appUid,
        config.parentPid,
    )
    override fun read(handle: Long, maximumBytes: Int) = PtyNative.nativeRead(handle, maximumBytes)
    override fun write(handle: Long, bytes: ByteArray) = PtyNative.nativeWrite(handle, bytes)
    override fun resize(handle: Long, columns: Int, rows: Int) = PtyNative.nativeResize(handle, columns, rows)
    override fun signalOwned(handle: Long, signal: Int) = PtyNative.nativeSignalOwned(handle, signal)
    override fun terminateRoot(handle: Long) = PtyNative.nativeTerminateRoot(handle)
    override fun waitForExit(handle: Long): PtyExit {
        val result = PtyNative.nativeWaitForExit(handle)
        if (result.size != 4 || result[0] !in 1..2 || result[1] !in 0..255 || result[2] <= 1) {
            throw IOException("Invalid native PTY wait result.")
        }
        return PtyExit(if (result[0] == 1) PtyExit.Kind.EXITED else PtyExit.Kind.SIGNALED,
            result[1], result[2], result[3] == 1)
    }
    override fun close(handle: Long) = PtyNative.nativeClose(handle)
}

/** Thin JNI surface; only fixed PRoot/Alpine launch data reaches native code. */
internal object PtyNative {
    init { System.loadLibrary("pocketrun_pty") }

    @JvmStatic external fun nativeOpen(
        nativeLibraryDirectory: String,
        rootfsDirectory: String,
        workspaceDirectory: String,
        runDirectory: String,
        temporaryDirectory: String,
        expectedUid: Int,
        expectedParentPid: Int,
    ): Long
    @JvmStatic external fun nativeRead(handle: Long, maximumBytes: Int): ByteArray?
    @JvmStatic external fun nativeWrite(handle: Long, bytes: ByteArray): Int
    @JvmStatic external fun nativeResize(handle: Long, columns: Int, rows: Int)
    @JvmStatic external fun nativeSignalOwned(handle: Long, signal: Int)
    @JvmStatic external fun nativeTerminateRoot(handle: Long): Boolean
    @JvmStatic external fun nativeWaitForExit(handle: Long): IntArray
    @JvmStatic external fun nativeClose(handle: Long): Boolean
}
