package io.github.kkkhs.pocketrun

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

/** Kernel leases, validated ownership records, and bounded conservative recovery. */
internal class LinuxDirectoryLease private constructor(
    val directory: File,
    private val leaseFile: File,
    private val kind: Kind,
    private val channel: FileChannel,
    private val lease: FileLock,
    private val unused: (File, Long) -> Boolean,
    private val inspect: (File) -> BasicFileAttributes?,
) : Closeable {
    enum class Kind(val label: String) { STAGE("stage"), RUN("run") }
    private var closed = false
    var cleanupProven = false
        private set

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try {
                coordinated(leaseFile.parentFile!!, {}) { budget ->
                    val attrs = inspect(leaseFile)
                    if (attrs == null || !attrs.isRegularFile ||
                        !validRecord(channel, leaseFile.parentFile!!, directory.name, kind, processGeneration)) return@coordinated
                    val directoryAttrs = inspect(directory)
                    if (directoryAttrs == null || removable(directory, directoryAttrs, kind, unused, budget, {})) {
                        if (directoryAttrs != null) deleteOwned(directory, budget, {})
                        Files.delete(leaseFile.toPath())
                        cleanupProven = true
                    }
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try { try { lease.release() } finally { channel.close() } }
                catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    companion object {
        private const val RECOVERY_MS = 1500L
        private const val MAX_SCAN_ENTRIES = 128
        private val coordinator = ReentrantLock()
        private val processGeneration = UUID.randomUUID().toString()
        private val uuid = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
        private data class Owner(val uid: Long, val pid: Long, val startTicks: Long)
        private val owner: Owner by lazy {
            val stat = File("/proc/self/stat").readText()
            val pid = stat.substringBefore(' ').toLong()
            val fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
            val uid = Regex("(?m)^Uid:\\s+(\\d+)").find(File("/proc/self/status").readText())!!.groupValues[1].toLong()
            Owner(uid, pid, fields[19].toLong())
        }

        private class Budget {
            private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECOVERY_MS)
            fun remainingMillis() = minOf(RECOVERY_MS, maxOf(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))
            fun check(guard: () -> Unit) {
                guard()
                if (remainingMillis() <= 0) throw TimeoutException("Linux 目录恢复预算耗尽。")
            }
        }
        private class RecoveryExpired : Exception()

        internal fun readAttributes(file: File): BasicFileAttributes? = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { null } // Permission/I/O errors are not absence.

        fun allocate(parent: File, kind: Kind, unused: (File, Long) -> Boolean = { _, _ -> false },
            guard: () -> Unit = {}, inspect: (File) -> BasicFileAttributes? = ::readAttributes): LinuxDirectoryLease =
            coordinated(parent, guard) { budget ->
                recoverLocked(parent, kind, unused, budget, guard, inspect)
                budget.check(guard)
                val name = "pocketrun-${kind.label}-${UUID.randomUUID()}"
                val leaseFile = File(parent, ".$name.lease")
                val record = record(parent, name, kind).toByteArray(Charsets.UTF_8)
                // A full, fsynced identity record precedes mkdir; a torn record can never authorize deletion.
                val directory = File(parent, name)
                var lease: FileLock? = null
                var created = false
                var transferred = false
                var failure: Throwable? = null
                val channel = FileChannel.open(leaseFile.toPath(), CREATE_NEW, READ, WRITE)
                try {
                    lease = channel.tryLock() ?: error("无法取得新的 Linux 目录租约。")
                    val bytes = ByteBuffer.wrap(record)
                    while (bytes.hasRemaining()) { budget.check(guard); channel.write(bytes) }
                    channel.force(true)
                    budget.check(guard)
                    Files.createDirectory(directory.toPath())
                    created = true
                    RootfsTar.privateDirectory(directory)
                    LinuxDirectoryLease(directory, leaseFile, kind, channel, lease, unused, inspect).also { transferred = true }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    if (!transferred) {
                        try {
                            try {
                                if (created) RootfsInstaller.removeOwned(directory)
                                Files.deleteIfExists(leaseFile.toPath())
                            } finally { try { lease?.release() } finally { channel.close() } }
                        } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
                    }
                }
            }

        fun recover(parent: File, kind: Kind, unused: (File, Long) -> Boolean = { _, _ -> false },
            guard: () -> Unit = {}, inspect: (File) -> BasicFileAttributes? = ::readAttributes) {
            coordinated(parent, guard) { budget -> recoverLocked(parent, kind, unused, budget, guard, inspect) }
        }

        private fun recoverLocked(parent: File, kind: Kind, unused: (File, Long) -> Boolean,
            budget: Budget, guard: () -> Unit, inspect: (File) -> BasicFileAttributes?) {
            val pattern = Regex("\\.(pocketrun-${kind.label}-$uuid)\\.lease")
            var scanned = 0
            Files.newDirectoryStream(parent.toPath()).use { entries ->
                for (path in entries) {
                    guard()
                    if (++scanned > MAX_SCAN_ENTRIES || budget.remainingMillis() <= 0) return
                    val file = path.toFile()
                    val name = pattern.matchEntire(file.name)?.groupValues?.get(1) ?: continue
                    try {
                        val attrs = inspect(file) ?: continue
                        if (!attrs.isRegularFile) continue
                        var examined = false
                        FileChannel.open(path, READ, WRITE, NOFOLLOW_LINKS).use channelScope@ { channel ->
                            val lease = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                            if (lease == null) return@channelScope
                            lease.use {
                                if (!validRecord(channel, parent, name, kind)) return@channelScope
                                examined = true
                                val directory = File(parent, name)
                                val directoryAttrs = inspect(directory)
                                if (directoryAttrs == null || removable(directory, directoryAttrs, kind, unused, budget, guard)) {
                                    if (directoryAttrs != null) deleteOwned(directory, budget, guard)
                                    Files.delete(path)
                                }
                            }
                        }
                        // At most one unlocked, valid candidate per pass: no N x 1500 ms scan convoy.
                        if (examined) return
                    } catch (_: IOException) {
                        // Unknown attributes or unreadable records remain registered for a later pass.
                    } catch (_: UnsupportedOperationException) {
                        // An unsupported filesystem attribute is unknown, never proof of death.
                    } catch (_: RecoveryExpired) { return }
                }
            }
        }

        private fun removable(directory: File, attrs: BasicFileAttributes, kind: Kind,
            unused: (File, Long) -> Boolean, budget: Budget, guard: () -> Unit): Boolean {
            if (!attrs.isDirectory || attrs.isSymbolicLink) return false
            guard()
            val remaining = budget.remainingMillis()
            if (remaining <= 0) return false
            val proven = if (kind == Kind.STAGE) true else try { unused(directory, remaining) } catch (_: Exception) { false }
            guard()
            return proven && budget.remainingMillis() > 0
        }

        private fun deleteOwned(directory: File, budget: Budget, guard: () -> Unit) {
            RootfsInstaller.removeOwned(directory) {
                guard()
                if (budget.remainingMillis() <= 0) throw RecoveryExpired()
            }
        }

        private fun parentIdentity(parent: File) = RootfsInstaller.hex(MessageDigest.getInstance("SHA-256")
            .digest(parent.canonicalPath.toByteArray(Charsets.UTF_8)))
        private fun filesystemOwner(file: File) = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Files.getOwner(file.toPath(), NOFOLLOW_LINKS).name.toByteArray(Charsets.UTF_8))
        private fun record(parent: File, name: String, kind: Kind) =
            "pocketrun-linux-lease-v2\n$name\n${kind.label}\n$processGeneration\n${owner.uid}\n${owner.pid}\n${owner.startTicks}\n${parentIdentity(parent)}\n${filesystemOwner(parent)}\n"

        private fun validUuid(value: String): Boolean = try {
            val parsed = UUID.fromString(value)
            parsed.toString() == value && parsed.version() == 4 && parsed.variant() == 2
        } catch (_: IllegalArgumentException) { false }

        private fun validRecord(channel: FileChannel, parent: File, name: String, kind: Kind, generation: String? = null): Boolean {
            val bytes = ByteBuffer.allocate(1025)
            channel.position(0)
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) <= 0) break
            }
            if (bytes.position() > 1024) return false
            val lines = String(bytes.array(), 0, bytes.position(), Charsets.UTF_8).split('\n')
            if (lines.size != 10 || lines[0] != "pocketrun-linux-lease-v2" || lines[1] != name ||
                lines[2] != kind.label || !validUuid(name.removePrefix("pocketrun-${kind.label}-")) ||
                !validUuid(lines[3]) || (generation != null && lines[3] != generation) ||
                lines[4].toLongOrNull() != owner.uid || (lines[5].toLongOrNull() ?: 0) <= 0 ||
                (lines[6].toLongOrNull() ?: 0) <= 0 || (generation != null &&
                    (lines[5] != owner.pid.toString() || lines[6] != owner.startTicks.toString())) || lines[7] != parentIdentity(parent) ||
                lines[8] != filesystemOwner(parent) || lines[9].isNotEmpty()) return false
            val file = File(parent, ".$name.lease")
            return filesystemOwner(file) == lines[8]
        }

        private fun <T> coordinated(parent: File, guard: () -> Unit, action: (Budget) -> T): T {
            val budget = Budget()
            while (true) {
                budget.check(guard)
                if (coordinator.tryLock(5, TimeUnit.MILLISECONDS)) break
            }
            try {
                RootfsTar.privateDirectory(parent)
                val registry = File(parent, ".pocketrun-recovery.lock")
                val attrs = readAttributes(registry)
                require(attrs == null || attrs.isRegularFile) { "Linux 恢复注册锁无效。" }
                return FileChannel.open(registry.toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                    var lock: FileLock? = null
                    try {
                        while (lock == null) {
                            budget.check(guard)
                            lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                            if (lock == null) Thread.sleep(5)
                        }
                        action(budget)
                    } finally { lock?.release() }
                }
            } finally { coordinator.unlock() }
        }
    }
}
