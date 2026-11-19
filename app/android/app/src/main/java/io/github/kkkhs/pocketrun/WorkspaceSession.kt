package io.github.kkkhs.pocketrun

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
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
