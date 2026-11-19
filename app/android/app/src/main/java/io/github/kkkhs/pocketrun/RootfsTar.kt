package io.github.kkkhs.pocketrun

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/** The pinned data-only archive uses ordinary ustar entries, not pax/GNU extensions. */
internal object RootfsTar {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_FILE = 1024 * 1024L
    private const val MAX_ENTRIES = 2048
    private val directoryMode = PosixFilePermission.values().filter { it.name.startsWith("OWNER_") }.toSet()
    private val fileMode = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    fun privateDirectory(file: File) {
        val attrs = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: NoSuchFileException) {
                try { Files.createDirectory(file.toPath()) } catch (_: FileAlreadyExistsException) { /* revalidate the winner */ }
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            }
        require(attrs.isDirectory && !attrs.isSymbolicLink) { "Linux 私有目录无效或是符号链接。" }
        // One chmod operation; never remove owner search permission before restoring it.
        Files.setPosixFilePermissions(file.toPath(), directoryMode)
    }

    fun extract(source: InputStream, root: File, guard: () -> Unit = {}) {
        require(root.isDirectory && root.list().orEmpty().isEmpty()) { "安装目录必须是新的空目录。" }
        val seen = HashSet<String>()
        val links = ArrayList<Pair<File, String>>()
        var consumed = 0
        var entries = 0
        GZIPInputStream(source).use { input ->
            fun readExact(size: Int): ByteArray {
                guard()
                require(size >= 0 && consumed.toLong() + size <= MAX_BYTES) { "rootfs 解压超过上限。" }
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    guard()
                    val count = input.read(bytes, offset, size - offset)
                    require(count > 0) { "rootfs tar 已截断。" }
                    offset += count
                }
                consumed += size
                return bytes
            }
            while (true) {
                val header = readExact(512)
                if (header.all { it == 0.toByte() }) {
                    require(readExact(512).all { it == 0.toByte() }) { "tar 结束标记无效。" }
                    // Read through the gzip trailer (CRC) and reject hidden trailing entries.
                    while (true) {
                        guard()
                        val byte = input.read()
                        if (byte < 0) break
                        consumed++
                        require(consumed <= MAX_BYTES && byte == 0) { "tar 尾部或大小无效。" }
                    }
                    break
                }
                require(++entries <= MAX_ENTRIES) { "rootfs 条目超过上限。" }
                val checksum = octal(header, 148, 8)
                val actual = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }
                require(checksum == actual.toLong()) { "tar 校验和无效。" }
                require(text(header, 257, 6) == "ustar") { "仅接受固定 ustar 格式。" }
                val prefix = text(header, 345, 155)
                val name = text(header, 0, 100)
                val path = relativePath(if (prefix.isEmpty()) name else "$prefix/$name")
                val kind = header[156].toInt().toChar()
                val size = octal(header, 124, 12)
                val mode = octal(header, 100, 8)
                require(size in 0..MAX_FILE && kind in listOf('0', '\u0000', '5', '2')) { "拒绝特殊或过大的 tar 条目。" }
                require(seen.add(path)) { "拒绝重复 tar 路径。" }
                if (path.isEmpty()) {
                    require(kind == '5' && size == 0L) { "rootfs 根条目无效。" }
                    continue
                }
                val file = File(root, path)
                ensureParents(root, file.parentFile!!)
                require(!Files.exists(file.toPath(), NOFOLLOW_LINKS)) { "tar 路径已存在。" }
                when (kind) {
                    '5' -> { require(size == 0L); privateDirectory(file) }
                    '2' -> {
                        require(size == 0L)
                        val target = text(header, 157, 100)
                        require(target.isNotEmpty() && '\\' !in target && !target.any { it.code < 32 })
                        // Absolute link targets describe guest paths, never Android host paths.
                        val guest = if (target.startsWith('/')) root.toPath().resolve(target.removePrefix("/"))
                            else file.parentFile!!.toPath().resolve(target)
                        val normalized = guest.normalize()
                        require(normalized.startsWith(root.toPath()) && normalized != root.toPath()) { "symlink 目标逃逸 rootfs。" }
                        links += file to file.parentFile!!.toPath().relativize(normalized).toString()
                    }
                    else -> {
                        require(mode and 73L == 0L) { "data-only tar 不能包含可执行普通文件。" }
                        val bytes = readExact(size.toInt())
                        require(!isElf(bytes)) { "data-only tar 不能包含 ELF。" }
                        FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
                        Files.setPosixFilePermissions(file.toPath(), fileMode)
                        val padding = ((512 - size % 512) % 512).toInt()
                        if (padding > 0) readExact(padding)
                    }
                }
            }
        }
        // No archive symlink exists until every regular-file write has finished.
        links.forEach { (file, target) ->
            guard()
            ensureParents(root, file.parentFile!!)
            require(!Files.exists(file.toPath(), NOFOLLOW_LINKS))
            Files.createSymbolicLink(file.toPath(), File(target).toPath())
        }
    }

    internal fun relativePath(raw: String): String {
        require(!raw.startsWith('/') && '\\' !in raw && !raw.any { it.code < 32 }) { "tar 路径无效。" }
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." } && raw.length <= 256) { "tar 路径逃逸 rootfs。" }
        return parts.joinToString("/")
    }

    private fun ensureParents(root: File, parent: File) {
        require(parent.toPath().startsWith(root.toPath()))
        if (parent == root) return
        ensureParents(root, parent.parentFile!!)
        privateDirectory(parent)
    }

    private fun text(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() } ?: offset + length
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(bytes: ByteArray, offset: Int, length: Int): Long {
        val value = text(bytes, offset, length).trim()
        require(value.isNotEmpty() && value.all { it in '0'..'7' }) { "tar 数值格式无效。" }
        return value.toLong(8)
    }

    internal fun isElf(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 127.toByte() &&
        bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()
}
