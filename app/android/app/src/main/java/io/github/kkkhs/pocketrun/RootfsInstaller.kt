package io.github.kkkhs.pocketrun

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal data class LinuxArtifact(val name: String, val sha256: String)
internal data class LinuxBinding(val name: String, val guestPath: String)
internal data class LinuxRuntime(
    val generation: String,
    val artifacts: List<LinuxArtifact>,
    val bindings: List<LinuxBinding>,
    val dataName: String,
    val dataSha256: String,
)

/** Only one newly allocated stage is owned/deleted by a prepare attempt. */
internal class RootfsInstaller(
    private val filesDirectory: File,
    val nativeDirectory: File,
    val runtime: LinuxRuntime,
    private val openAsset: (String) -> InputStream,
    private val publish: (File, File) -> Boolean = AtomicSamplePublisher::publishDirectory,
) {
    data class Installation(val root: File, val generation: String, val created: Boolean)
    private val base = File(filesDirectory, "linux")
    private val target = File(base, "rootfs-${runtime.generation}")
    private val marker get() = File(target, "installed.manifest")

    fun prepare(guard: () -> Unit = {}, commit: (() -> Boolean) -> Boolean = { it() }): Installation {
        validateRuntime()
        verifyArtifacts(guard)
        require(filesDirectory.isDirectory && filesDirectory.canonicalFile == filesDirectory.absoluteFile)
        RootfsTar.privateDirectory(base)
        val stages = File(base, "stage")
        LinuxDirectoryLease.recover(stages, LinuxDirectoryLease.Kind.STAGE, guard = guard)
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            validateInstalled(guard)
            verifyDataAsset(guard = guard)
            return Installation(File(target, "data"), runtime.generation, false)
        }
        val owned = LinuxDirectoryLease.allocate(stages, LinuxDirectoryLease.Kind.STAGE, guard = guard)
        val stage = owned.directory
        try {
            val compressed = File(stage, "data.tar.gz")
            FileOutputStream(compressed).use { output ->
                verifyDataAsset(output, guard)
                output.fd.sync()
            }
            val root = File(stage, "data")
            RootfsTar.privateDirectory(root)
            compressed.inputStream().use { RootfsTar.extract(it, root, guard) }
            check(compressed.delete())
            runtime.bindings.forEach { binding ->
                guard()
                val placeholder = File(root, binding.guestPath.removePrefix("/"))
                require(placeholder.parentFile!!.isDirectory && placeholder.parentFile!!.canonicalFile == placeholder.parentFile!!.absoluteFile)
                if (Files.exists(placeholder.toPath(), NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(placeholder.toPath(), NOFOLLOW_LINKS) && placeholder.length() == 0L) { "ELF guest binding 与 data-only 文件冲突。" }
                } else {
                    FileOutputStream(placeholder).use { it.fd.sync() }
                }
            }
            require(File(root, "etc/alpine-release").readText().trim() == ALPINE_VERSION) { "Alpine 版本与固定来源不符。" }
            val inventory = inventory(root, guard)
            FileOutputStream(File(stage, "installed.manifest")).use {
                it.write((runtime.generation + "\n" + inventory).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            val created = commit {
                guard()
                publish(stage, target)
            }
            validateInstalled(guard)
            return Installation(File(target, "data"), runtime.generation, created)
        } finally {
            owned.close()
        }
    }

    private fun verifyDataAsset(output: OutputStream? = null, guard: () -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0
        openAsset(runtime.dataName).use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                guard()
                val count = input.read(bytes)
                if (count < 0) break
                total += count
                require(total <= 2 * 1024 * 1024) { "压缩 rootfs 超过上限。" }
                digest.update(bytes, 0, count)
                output?.write(bytes, 0, count)
            }
        }
        require(hex(digest.digest()) == runtime.dataSha256) { "rootfs SHA-256 校验失败。" }
    }

    fun validateInstalled(guard: () -> Unit = {}) {
        require(target.isDirectory && target.canonicalFile == target.absoluteFile &&
            marker.isFile && !Files.isSymbolicLink(marker.toPath()) && marker.length() <= 256 * 1024) { "请先安装完整 Linux 环境。" }
        require(marker.readText() == runtime.generation + "\n" + inventory(File(target, "data"), guard)) { "Linux 安装内容已改变或不完整。" }
    }

    fun verifyArtifacts(guard: () -> Unit = {}) {
        require(nativeDirectory.isDirectory && nativeDirectory.canonicalFile == nativeDirectory.absoluteFile)
        runtime.artifacts.forEach { artifact ->
            val file = File(nativeDirectory, artifact.name)
            require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "只读 APK ELF 不可用。" }
            require(hash(file, guard) == artifact.sha256) { "APK ELF SHA-256 校验失败：${artifact.name}" }
        }
    }

    private fun validateRuntime() {
        require(runtime.generation.matches(Regex("[a-f0-9]{64}")) && runtime.dataSha256.matches(Regex("[a-f0-9]{64}")))
        require(runtime.dataName == "linux/alpine-data.bin")
        require(runtime.artifacts.size in 4..19 && runtime.artifacts.map { it.name }.toSet().size == runtime.artifacts.size)
        runtime.artifacts.forEach { require(it.name.matches(Regex("lib[a-zA-Z0-9_]+\\.so")) && it.sha256.matches(Regex("[a-f0-9]{64}"))) }
        require(runtime.artifacts.any { it.name == "libproot.so" } && runtime.artifacts.any { it.name == "libproot_loader.so" })
        require(runtime.bindings.size in 2..17 && runtime.bindings.map { it.guestPath }.toSet().size == runtime.bindings.size)
        require(runtime.bindings.map { it.name }.toSet().size == runtime.bindings.size)
        runtime.bindings.forEach {
            require(runtime.artifacts.any { artifact -> artifact.name == it.name } && it.guestPath.startsWith('/'))
            require("/" + RootfsTar.relativePath(it.guestPath.removePrefix("/")) == it.guestPath)
            require(it.guestPath.startsWith("/bin/") || it.guestPath.startsWith("/lib/") ||
                it.guestPath.startsWith("/sbin/") || it.guestPath.startsWith("/usr/"))
        }
        require(runtime.bindings.any { it.guestPath == "/bin/busybox" } && runtime.bindings.any { it.guestPath == "/lib/ld-musl-aarch64.so.1" })
    }

    private fun inventory(root: File, guard: () -> Unit): String {
        require(root.isDirectory && root.canonicalFile == root.absoluteFile)
        var total = 0L
        return Files.walk(root.toPath()).use { paths ->
            val entries = paths.iterator().asSequence().toList()
            require(entries.size <= 2048)
            entries.filter { it != root.toPath() }.map { path ->
                guard()
                val relative = root.toPath().relativize(path).toString()
                when {
                    Files.isSymbolicLink(path) -> {
                        val link = Files.readSymbolicLink(path)
                        require(!link.isAbsolute && path.parent.resolve(link).normalize().startsWith(root.toPath()))
                        "L\t$relative\t$link"
                    }
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> "D\t$relative"
                    else -> {
                        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                        val size = Files.size(path)
                        total += size
                        require(size <= 1024 * 1024 && total <= 4 * 1024 * 1024)
                        require(Files.getPosixFilePermissions(path).none { it.name.endsWith("EXECUTE") })
                        val prefix = ByteArray(4)
                        val prefixSize = Files.newInputStream(path).use { input ->
                            var offset = 0
                            while (offset < prefix.size) {
                                val count = input.read(prefix, offset, prefix.size - offset)
                                if (count < 0) break
                                offset += count
                            }
                            offset
                        }
                        require(prefixSize < 4 || !RootfsTar.isElf(prefix))
                        "F\t$relative\t${hash(path.toFile(), guard)}"
                    }
                }
            }.sorted().joinToString("\n", postfix = "\n")
        }
    }

    companion object {
        const val ALPINE_VERSION = "3.22.8"
        internal fun hash(file: File, guard: () -> Unit = {}): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(8192)
            var total = 0L
            while (true) {
                guard()
                val count = input.read(bytes)
                if (count < 0) break
                total += count
                require(total <= 16 * 1024 * 1024) { "校验文件过大。" }
                digest.update(bytes, 0, count)
            }
            hex(digest.digest())
        }
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        internal fun removeOwned(directory: File, guard: () -> Unit = {}) {
            val attrs = try { Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                catch (_: NoSuchFileException) { return }
            require(attrs.isDirectory && !attrs.isSymbolicLink)
            var visited = 0
            Files.walk(directory.toPath()).use { stream ->
                stream.peek { guard(); require(++visited <= 4096) }.sorted(Comparator.reverseOrder()).forEach {
                    guard()
                    Files.delete(it)
                }
            }
        }
    }
}
