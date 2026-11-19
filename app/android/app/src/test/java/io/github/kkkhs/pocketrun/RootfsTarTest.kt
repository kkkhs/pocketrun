package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RootfsTarTest {
    @Test fun realPinnedArchiveExtractsOnlyDataAndGuestRelativeLinks() = temporary { root ->
        val asset = mainDirectory().resolve("assets/linux/alpine-data.bin")
        assertEquals("8c4ac18ae5babcbd3f692e1c4bed6710eb965967da792fc6ad597def4a1eff06", RootfsInstaller.hash(asset))
        asset.inputStream().use { RootfsTar.extract(it, root) }
        assertEquals("3.22.8\n", File(root, "etc/alpine-release").readText())
        assertEquals("busybox", Files.readSymbolicLink(File(root, "bin/sh").toPath()).toString())
        assertEquals("busybox", Files.readSymbolicLink(File(root, "bin/wc").toPath()).toString())
        assertTrue(File(root, "bin/busybox").isFile)
        assertEquals(0L, File(root, "bin/busybox").length()) // Empty placeholder, never a writable ELF.
        assertEquals("ld-musl-aarch64.so.1", Files.readSymbolicLink(File(root, "lib/libc.musl-aarch64.so.1").toPath()).toString())
        assertFalse(File(root, "sbin/apk").exists())
        val nullPlaceholder = File(root, "dev/null")
        assertTrue(Files.isRegularFile(nullPlaceholder.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(0L, nullPlaceholder.length())
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(nullPlaceholder.toPath()))
    }

    @Test fun rejectsAbsoluteTraversalSpecialElfExecutableAndDuplicateEntries() {
        listOf(
            listOf(Entry("/escaped")), listOf(Entry("a/../escaped")), listOf(Entry("device", kind = '3')),
            listOf(Entry("elf", bytes = byteArrayOf(127, 69, 76, 70))),
            listOf(Entry("script", mode = 493)), listOf(Entry("same"), Entry("same")),
            listOf(Entry("link", kind = '2', link = "../../escaped")),
            listOf(Entry("hard", kind = '1', link = "target")),
        ).forEach { entries -> temporary { root -> expectFailure { RootfsTar.extract(tar(entries).inputStream(), root) } } }
    }

    @Test fun cannotWriteThroughAnArchiveSymlinkOrEscapeViaLateLink() = temporary { root ->
        val outside = File(root.parentFile, "outside-${root.name}")
        try {
            expectFailure { RootfsTar.extract(tar(listOf(Entry("alias", kind = '2', link = "/outside"), Entry("alias/payload"))).inputStream(), root) }
            assertFalse(outside.exists())
        } finally { outside.delete() }
    }

    @Test fun rejectsBadChecksumTruncationTrailerAndExpansionLimit() {
        val valid = tar(listOf(Entry("file", bytes = "abc".toByteArray())))
        temporary { root -> expectFailure { RootfsTar.extract(valid.copyOf(valid.size - 5).inputStream(), root) } }
        val badChecksum = rawTar(listOf(Entry("file"))).also { it[0] = 'x'.code.toByte() }
        temporary { root -> expectFailure { RootfsTar.extract(gzip(badChecksum).inputStream(), root) } }
        temporary { root -> expectFailure { RootfsTar.extract(gzip(rawTar(emptyList()) + byteArrayOf(1)).inputStream(), root) } }
        temporary { root -> expectFailure { RootfsTar.extract(gzip(ByteArray(5 * 1024 * 1024)).inputStream(), root) } }
    }

    @Test fun privateDirectoryKeepsOwnerSearchPermissionWhileAnotherReaderIsActive() = temporary { root ->
        RootfsTar.privateDirectory(root)
        val sample = File(root, "sample").apply { writeText("retained") }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val reading = worker.submit { repeat(200) { assertEquals("retained", sample.readText()) } }
            repeat(200) { RootfsTar.privateDirectory(root) }
            reading.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val expected = setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE, java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
            assertEquals(expected, Files.getPosixFilePermissions(root.toPath()))
            assertFalse(Files.getPosixFilePermissions(sample.toPath()).any { it.name.endsWith("EXECUTE") })
        } finally { worker.shutdownNow(); worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    @Test fun cancellationIsCheckedWhileReading() = temporary { root ->
        var checks = 0
        expectFailure { RootfsTar.extract(tar(listOf(Entry("file", bytes = ByteArray(20000)))).inputStream(), root) {
            if (++checks == 4) throw java.util.concurrent.CancellationException()
        } }
        assertEquals(4, checks)
    }

    private data class Entry(val name: String, val kind: Char = '0', val mode: Int = 384,
        val bytes: ByteArray = byteArrayOf(), val link: String = "")
    private fun tar(entries: List<Entry>) = gzip(rawTar(entries))
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
    private fun rawTar(entries: List<Entry>): ByteArray = ByteArrayOutputStream().also { output ->
        entries.forEach { entry ->
            val header = ByteArray(512)
            fun put(offset: Int, text: String) { text.toByteArray().copyInto(header, offset) }
            put(0, entry.name); put(100, entry.mode.toString(8).padStart(7, '0'))
            put(124, entry.bytes.size.toString(8).padStart(11, '0'))
            header[156] = entry.kind.code.toByte(); put(157, entry.link); put(257, "ustar")
            (148..155).forEach { header[it] = 32 }
            put(148, header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ")
            output.write(header); output.write(entry.bytes)
            output.write(ByteArray((512 - entry.bytes.size % 512) % 512))
        }
        output.write(ByteArray(1024))
    }.toByteArray()

    companion object {
        internal fun mainDirectory(): File = listOf("src/main", "app/src/main", "android/app/src/main", "app/android/app/src/main")
            .map(::File).firstOrNull { File(it, "assets/linux/alpine-data.bin").isFile }
            ?: error("Packaged pinned chapter-7 asset is required; this test must not skip it.")
        internal fun temporary(action: (File) -> Unit) {
            val root = Files.createTempDirectory("pocketrun-linux-test-").toFile()
            try { action(root) } finally { RootfsInstaller.removeOwned(root) }
        }
        internal fun expectFailure(action: () -> Unit) {
            try { action(); fail("Expected rejection") } catch (error: Exception) { assertNotNull(error) }
        }
    }
}
