package io.github.kkkhs.pocketrun

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Only a private, pre-guest registration can authorize killing this run's native tracer. */
internal class LinuxProcessControl(
    private val ownedDirectory: File,
    private val installedProot: File,
    private val access: Access = AndroidAccess,
) {
    val tracerRecord = File(ownedDirectory, "tracer.identity")
    private var boundProcess: Process? = null
    private var proven = false
    private var registeredIdentity: Identity? = null
    private var registrationDeadline = 0L

    init {
        access.validateDirectory(ownedDirectory)
        require(access.readRecord(tracerRecord) == null) { "Tracer registration must be fresh before launch." }
    }

    /** Cache the kernel-checked registration immediately after ProcessBuilder.start, before consuming output. */
    @Synchronized fun registerOwned(process: Process) {
        check(boundProcess == null || boundProcess === process) { "Tracer controller cannot be shared across runs." }
        boundProcess = process
        if (registeredIdentity != null || proven) return
        if (registrationDeadline == 0L) registrationDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REGISTRATION_MS)
        val until = registrationDeadline
        var identity: Identity? = null
        while (identity == null) {
            access.validateDirectory(ownedDirectory)
            val bytes = access.readRecord(tracerRecord)
            identity = bytes?.let { try { parseIdentity(it) } catch (_: Exception) { null } }
            if (identity != null) break
            if (!process.isAlive || System.nanoTime() >= until) unproven("Tracer registration missing or incomplete.")
            try { Thread.sleep(10) }
            catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("cleanup_unproven: tracer registration interrupted.", error)
            }
        }
        val registered = checkNotNull(identity)
        if (registered.ppid != access.parentPid || registered.uid != access.uid || registered.pid <= 1 ||
            registered.pid == access.parentPid) unproven("Tracer registration owner mismatch.")
        if (!reapIfDead(process, registered)) {
            try {
                if (access.readProcess(registered.pid) != registered) unproven("Tracer /proc identity mismatch.")
                access.validateExecutable(registered.pid, installedProot)
                access.validateRun(registered.pid, ownedDirectory)
                if (access.readProcess(registered.pid) != registered) unproven("Tracer identity changed during registration.")
            } catch (error: Exception) {
                // /proc may disappear between any two reads for a genuinely short-lived child.
                // Still require an explicit gone identity; permission errors are never absence.
                if (!reapIfDead(process, registered)) throw error
            }
        }
        registeredIdentity = registered
    }

    /** Idempotent for one Java Process; unknown ownership never falls back to destroy/shell/PID discovery. */
    @Synchronized fun terminateOwned(process: Process) {
        check(boundProcess == null || boundProcess === process) { "Tracer controller cannot be shared across runs." }
        boundProcess = process
        if (proven) return
        try {
            registerOwned(process)
            if (proven) return
            val registered = checkNotNull(registeredIdentity)
            // A short-lived Java child may already be reaped. Never signal its possibly reused PID.
            if (reapIfDead(process, registered)) return
            try {
                if (access.readProcess(registered.pid) != registered) unproven("Tracer /proc identity mismatch.")
                access.validateExecutable(registered.pid, installedProot)
                access.validateRun(registered.pid, ownedDirectory)
                if (access.readProcess(registered.pid) != registered) unproven("Tracer identity changed before kill.")
            } catch (error: Exception) {
                if (reapIfDead(process, registered)) return
                throw error
            }
            // Recheck Java liveness immediately before the only signal operation.
            if (reapIfDead(process, registered)) return
            access.kill(registered.pid)
            if (!process.waitFor(REAP_MS, TimeUnit.MILLISECONDS)) unproven("Tracer Java process not reaped after SIGKILL.")
            requireGone(registered)
            proven = true
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("cleanup_unproven: tracer termination interrupted.", error)
        } catch (error: Exception) {
            if (error is IOException && error.message?.startsWith("cleanup_unproven:") == true) throw error
            throw IOException("cleanup_unproven: ${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun reapIfDead(process: Process, identity: Identity): Boolean {
        if (process.isAlive) return false
        if (!process.waitFor(REAP_MS, TimeUnit.MILLISECONDS)) unproven("Exited tracer was not reaped.")
        requireGone(identity)
        proven = true
        return true
    }

    private fun requireGone(identity: Identity) {
        val current = access.readProcess(identity.pid)
        // Different start ticks prove the old identity gone, but never authorize killing the replacement.
        if (current != null && current.startTicks == identity.startTicks) unproven("Tracer identity still present after reap.")
    }

    internal data class Identity(val pid: Int, val ppid: Int, val uid: Int, val startTicks: Long)
    internal interface Access {
        val parentPid: Int
        val uid: Int
        fun validateDirectory(directory: File)
        fun readRecord(file: File): ByteArray?
        fun readProcess(pid: Int): Identity?
        fun validateExecutable(pid: Int, expected: File)
        fun validateRun(pid: Int, ownedDirectory: File)
        fun kill(pid: Int)
    }

    private object AndroidAccess : Access {
        override val parentPid get() = android.os.Process.myPid()
        override val uid get() = android.os.Process.myUid()
        override fun validateDirectory(directory: File) {
            require(directory.absoluteFile == directory.canonicalFile) { "Owned tracer directory alias/symlink." }
            val stat = Os.lstat(directory.path)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid && (stat.st_mode and 511) == 448) {
                "Owned tracer directory is not private to the App UID."
            }
        }
        override fun readRecord(file: File): ByteArray? = readBounded(file, 4096, privateRecord = true)
        override fun readProcess(pid: Int): Identity? {
            val file = File("/proc/$pid/stat")
            try {
                val bytes = readBounded(file, 4096) ?: return null
                val stat = Os.lstat("/proc/$pid")
                return parseStat(String(bytes, Charsets.US_ASCII), stat.st_uid).also { require(it.pid == pid) }
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENOENT) return null
                throw error
            }
        }
        override fun validateExecutable(pid: Int, expected: File) {
            require(expected.absoluteFile == expected.canonicalFile) { "Installed tracer path is not canonical." }
            val stat = Os.lstat(expected.path)
            val writable = try { Os.access(expected.path, OsConstants.W_OK) }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EROFS) false else throw error
            }
            require(OsConstants.S_ISREG(stat.st_mode) && (stat.st_mode and 18) == 0 && !writable) {
                "Installed tracer is not read-only to the App UID."
            }
            require(Os.readlink("/proc/$pid/exe") == expected.path) { "Tracer executable mismatch." }
        }
        override fun validateRun(pid: Int, ownedDirectory: File) {
            val bytes = readBounded(File("/proc/$pid/cmdline"), 16384)
                ?: unproven("Tracer command identity missing.")
            val arguments = String(bytes, Charsets.UTF_8).split(0.toChar())
            val binding = File(ownedDirectory, "tmp").path + ":/tmp"
            require(arguments.indices.any { it > 0 && arguments[it - 1] == "-b" && arguments[it] == binding }) {
                "Tracer command does not belong to this run's private tmp binding."
            }
        }
        override fun kill(pid: Int) = Os.kill(pid, OsConstants.SIGKILL)

        private fun readBounded(file: File, maximum: Int, privateRecord: Boolean = false): ByteArray? {
            val fd = try { Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 0) }
            catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return null else throw error }
            try {
                val stat = Os.fstat(fd)
                require(OsConstants.S_ISREG(stat.st_mode)) { "Tracer identity source is not a regular file." }
                if (privateRecord) require(stat.st_uid == uid && (stat.st_mode and 511) == 384 && stat.st_nlink == 1L &&
                    stat.st_size in 0..maximum.toLong()) { "Tracer record ownership/mode/size invalid." }
                val buffer = ByteArray(maximum + 1)
                var count = 0
                while (count < buffer.size) {
                    val n = Os.read(fd, buffer, count, buffer.size - count)
                    if (n == 0) break
                    count += n
                }
                require(count in (if (privateRecord) 0 else 1)..maximum) { "Tracer identity exceeds bounded input." }
                return buffer.copyOf(count)
            } finally { Os.close(fd) }
        }
    }

    companion object {
        private const val REGISTRATION_MS = 500L
        private const val REAP_MS = 500L
        private fun unproven(message: String): Nothing = throw IOException("cleanup_unproven: $message")

        internal fun parseIdentity(bytes: ByteArray): Identity {
            require(bytes.size in 1..4096 && bytes.all { it.toInt() in 9..13 || it.toInt() in 32..126 }) { "Invalid tracer record encoding." }
            val text = String(bytes, Charsets.US_ASCII).trim()
            require(text.startsWith("{") && text.endsWith("}")) { "Invalid tracer record JSON." }
            val fields = LinkedHashMap<String, Long>()
            for (part in text.substring(1, text.length - 1).split(',')) {
                val match = Regex("\\s*\"(schemaVersion|pid|ppid|uid|startTicks)\"\\s*:\\s*(0|[1-9][0-9]*)\\s*").matchEntire(part)
                    ?: throw IOException("Invalid tracer record field.")
                require(fields.put(match.groupValues[1], match.groupValues[2].toLong()) == null) { "Duplicate tracer record field." }
            }
            require(fields.keys == setOf("schemaVersion", "pid", "ppid", "uid", "startTicks") && fields["schemaVersion"] == 1L) { "Unsupported tracer registration schema." }
            fun integer(key: String): Int = checkNotNull(fields[key]).also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
            return Identity(integer("pid"), integer("ppid"), integer("uid"), checkNotNull(fields["startTicks"]).also { require(it > 0) })
        }

        internal fun parseStat(text: String, uid: Int): Identity {
            val end = text.lastIndexOf(')')
            require(end > 0 && text.getOrNull(end + 1) == ' ') { "Malformed tracer /proc stat." }
            val pid = text.substringBefore(" (").toInt()
            val fields = text.substring(end + 2).trim().split(Regex("\\s+"))
            require(fields.size >= 20) { "Truncated tracer /proc stat." }
            return Identity(pid, fields[1].toInt(), uid, fields[19].toLong().also { require(it > 0) })
        }
    }
}
