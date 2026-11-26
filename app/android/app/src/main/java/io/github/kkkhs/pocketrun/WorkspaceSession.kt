package io.github.kkkhs.pocketrun

import android.os.Process
import android.system.Os
import android.system.OsConstants
import io.github.kkkhs.pocketrun.taskhost.CanonicalChildPath
import io.github.kkkhs.pocketrun.taskhost.DemoCsvReport
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

class WorkspaceSession(
    private val filesDirectory: File,
    private val commands: List<List<String>> = DEMO_COMMANDS,
    private val beforeSamplePublish: () -> Unit = {},
) : Closeable {
    data class Info(val directory: String, val sampleFile: String, val sampleCreated: Boolean)

    private val pipeline = CommandPipeline()
    private val directory = File(File(filesDirectory.canonicalFile, "workspaces"), "demo")
    private val sample = File(directory, "items.csv")
    private val lock = PREPARATION_LOCK
    private var closed = false

    fun prepare(): Info = synchronized(lock) {
        check(!closed) { "工作目录会话已关闭。" }
        check(filesDirectory.isDirectory) { "App 私有文件目录不可用。" }
        val parent = checkNotNull(directory.parentFile)
        require(parent.canonicalFile == parent.absoluteFile) { "工作目录不能使用符号链接。" }
        check(parent.isDirectory || parent.mkdir()) { "无法创建工作目录。" }
        require(directory.canonicalFile == directory.absoluteFile) { "工作目录不能逃逸 App 私有目录。" }
        check(directory.isDirectory || directory.mkdir()) { "无法创建演示工作目录。" }
        Files.setPosixFilePermissions(directory.toPath(), setOf(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        require(sample.canonicalFile == sample.absoluteFile) { "演示文件不能使用符号链接。" }
        directory.listFiles()?.filter {
            it.name.startsWith(".pocketrun-sample-") && it.name.endsWith(".tmp") &&
                it.isFile && it.canonicalFile == it.absoluteFile
        }?.forEach { check(it.delete()) { "无法清理中断的演示临时文件。" } }
        val created = if (sample.exists()) false else publishSample()
        validateSample()
        Info(directory.path, sample.path, created)
    }

    fun reserve(): CommandPipeline.Run {
        validateSample()
        return pipeline.reserve()
    }

    fun runDemo(run: CommandPipeline.Run) =
        pipeline.execute(run, directory, commands, validate = ::validateSample)

    internal data class Publication(val status: String, val outputDigest: String)

    internal fun demoRoot(): File = synchronized(lock) {
        check(!closed && directory.isDirectory && directory.canonicalFile == directory.absoluteFile) {
            "私有演示工作目录不可用。"
        }
        directory
    }

    internal fun readDemoCsvBytes(): ByteArray {
        val root = demoRoot().toPath()
        val path = CanonicalChildPath.existingRegularFile(root, "items.csv")
        return readRegular(path.toFile(), DemoCsvReport.MAX_BYTES)
    }

    internal fun readDemoResultBytes(): ByteArray? {
        val root = demoRoot().toPath()
        val path = root.resolve("result.txt")
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { return null }
        return readRegular(CanonicalChildPath.existingRegularFile(root, "result.txt").toFile(), 65)
    }

    internal fun publishDemoResult(text: String, guard: () -> Unit,
        onTemporary: (File) -> Unit = {},
        commit: (File, File) -> Boolean = AtomicSamplePublisher::publish): Publication {
        require(text.length in 1..64 && text.all { it in ' '..'~' }) { "INVALID_DEMO_RESULT_TEXT" }
        val root = demoRoot()
        val target = File(root, "result.txt")
        val expected = (text + "\n").toByteArray(Charsets.UTF_8)
        guard()
        val present = readDemoResultBytes()
        if (present != null) {
            require(present.contentEquals(expected)) { "EXISTING_RESULT_MISMATCH" }
            return Publication("already_present", DemoCsvReport.digest(present))
        }
        val temporary = File.createTempFile(".pocketrun-result-", ".tmp", root)
        try {
            onTemporary(temporary)
            Files.setPosixFilePermissions(temporary.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            FileOutputStream(temporary).use { output -> output.write(expected); output.fd.sync() }
            guard()
            val created = commit(temporary, target)
            if (created) syncDirectory(root)
            val actual = checkNotNull(readDemoResultBytes()) { "RESULT_PUBLICATION_UNCONFIRMED" }
            require(actual.contentEquals(expected)) { "RESULT_PUBLICATION_UNCONFIRMED" }
            guard()
            return Publication(if (created) "published" else "already_present", DemoCsvReport.digest(actual))
        } finally {
            temporary.delete()
            check(LinuxDirectoryLease.readAttributes(temporary) == null) { "OWNED_RESULT_TEMP_CLEANUP_UNPROVEN" }
        }
    }

    private fun syncDirectory(root: File) {
        if (System.getProperty("java.vm.name") == "Dalvik") {
            val fd = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try {
                val info = Os.fstat(fd)
                require(OsConstants.S_ISDIR(info.st_mode) && info.st_uid == Process.myUid()) { "INVALID_WORKSPACE_DIRECTORY" }
                Os.fsync(fd)
            } finally { Os.close(fd) }
        } else FileChannel.open(root.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun readRegular(file: File, limit: Int): ByteArray {
        if (System.getProperty("java.vm.name") == "Dalvik") {
            val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            return FileInputStream(fd).use { input ->
                val before = Os.fstat(input.fd)
                require(OsConstants.S_ISREG(before.st_mode) && before.st_uid == Process.myUid()) { "INVALID_WORKSPACE_FILE" }
                val bytes = readBounded(input, limit)
                val after = Os.fstat(input.fd)
                require(before.st_dev == after.st_dev && before.st_ino == after.st_ino &&
                    before.st_mtim.tv_sec == after.st_mtim.tv_sec && before.st_mtim.tv_nsec == after.st_mtim.tv_nsec &&
                    before.st_ctim.tv_sec == after.st_ctim.tv_sec && before.st_ctim.tv_nsec == after.st_ctim.tv_nsec &&
                    after.st_size == bytes.size.toLong()) {
                    "WORKSPACE_FILE_CHANGED"
                }
                bytes
            }
        }
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(before.isRegularFile) { "INVALID_WORKSPACE_FILE" }
        val bytes = Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            readBounded(it, limit)
        }
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(after.isRegularFile && before.fileKey() == after.fileKey() &&
            before.lastModifiedTime() == after.lastModifiedTime() && after.size() == bytes.size.toLong()) {
            "WORKSPACE_FILE_CHANGED"
        }
        return bytes
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        require(size <= limit) { "WORKSPACE_FILE_BUDGET" }
        return bytes.copyOf(size)
    }

    fun cancel() = pipeline.cancel()
    override fun close() {
        synchronized(lock) { closed = true }
        pipeline.close()
    }

    private fun publishSample(): Boolean {
        val temporary = File.createTempFile(".pocketrun-sample-", ".tmp", directory)
        try {
            check(temporary.setReadable(false, false) && temporary.setReadable(true, true) &&
                temporary.setWritable(false, false) && temporary.setWritable(true, true)) { "无法设置私有文件权限。" }
            FileOutputStream(temporary).use { output ->
                output.write(DEMO_CSV.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            beforeSamplePublish()
            check(!closed) { "工作目录会话已关闭。" }
            // Publish complete data atomically without replacing a concurrent user file.
            return AtomicSamplePublisher.publish(temporary, sample)
        } finally {
            check(temporary.delete() || !temporary.exists()) { "无法删除演示临时文件。" }
        }
    }

    private fun validateSample() {
        require(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) { "请先初始化私有工作目录。" }
        require(sample.isFile && sample.canonicalFile == sample.absoluteFile) { "演示文件无效或已离开工作目录。" }
        require(sample.length() <= CommandPipeline.MAX_OUTPUT_BYTES) { "演示文件超过 64 KiB。" }
    }

    companion object {
        private val PREPARATION_LOCK = Any()
        const val DEMO_CSV = "name,quantity\napple,2\nbanana,3\n"
        private val DEMO_COMMANDS = listOf(
            listOf("/system/bin/toybox", "cat", "items.csv"),
            listOf("/system/bin/toybox", "wc", "-l"),
        )
    }
}
