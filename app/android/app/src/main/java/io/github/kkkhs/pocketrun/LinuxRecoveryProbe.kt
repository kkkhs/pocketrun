package io.github.kkkhs.pocketrun

import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Conservative recovery observation. Unknown procfs access never authorizes deletion. */
internal object LinuxRecoveryProbe {
    data class Failure(val directory: String, val reason: String, val pid: Int?, val fd: String?, val errno: Int?)
    private val failure = ThreadLocal<Failure?>()

    fun lastFailure(directory: File): Failure? = failure.get()?.takeIf { it.directory == directory.path }

    fun isUnusedByAppProcesses(directory: File, timeoutMillis: Long = 1500): Boolean {
        failure.remove()
        var inspectingPid: Int? = null
        var inspectingFd: String? = null
        var phase = "directory_validation"
        fun unproven(reason: String, error: Exception? = null): Boolean {
            val errno = (error as? ErrnoException)?.errno ?: (error?.cause as? ErrnoException)?.errno
            failure.set(Failure(directory.path, reason, inspectingPid, inspectingFd, errno))
            return false
        }
        return try {
            require(timeoutMillis in 1..1500)
            require(directory.canonicalFile == directory.absoluteFile)
            val prefix = directory.path.trimEnd('/') + "/"
            val uid = Process.myUid()
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            phase = "process_enumeration"
            val processes = checkNotNull(File("/proc").listFiles()).filter { it.name.toIntOrNull() != null }
            require(processes.size <= 20000)
            for (process in processes) {
                inspectingPid = process.name.toInt()
                inspectingFd = null
                if (SystemClock.elapsedRealtime() >= deadline) return unproven("budget_exhausted")
                phase = "process_lstat"
                if (absent(process)) continue
                phase = "process_status"
                val status = try { bounded(File(process, "status")) } catch (error: Exception) {
                    if (absent(process)) continue else return unproven("status_unknown", error)
                }
                val ids = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.map { it.toInt() }
                    ?: return unproven("uid_missing")
                if (uid !in ids) continue
                phase = "process_maps"
                val maps = try { bounded(File(process, "maps")) } catch (error: Exception) {
                    if (absent(process)) continue else return unproven("maps_unknown", error)
                }
                // A surviving tracer/guest may use its tmp later even with no currently open tmp FD.
                if (GUEST_FILES.any(maps::contains)) {
                    phase = "guest_run_association"
                    val processTicks = startTicks(process)
                    val tracerPid = status.lineSequence().firstOrNull { it.startsWith("TracerPid:") }
                        ?.substringAfter(':')?.trim()?.toIntOrNull()
                        ?: return unproven("tracer_identity_unknown")
                    val root = if (tracerPid > 0) File("/proc/$tracerPid") else process
                    val rootTicks = startTicks(root)
                    val rootStatus = bounded(File(root, "status"))
                    val rootUid = rootStatus.lineSequence().firstOrNull { it.startsWith("Uid:") }
                        ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.map { it.toInt() }
                        ?: return unproven("tracer_uid_unknown")
                    if (rootUid.size != 4 || rootUid.any { it != uid }) return unproven("tracer_uid_unknown")
                    val command = bounded(File(root, "cmdline"))
                    val temporary = temporaryBinding(command) ?: return unproven("guest_run_unknown")
                    if (startTicks(root) != rootTicks || startTicks(process) != processTicks) {
                        return unproven("guest_identity_changed")
                    }
                    if (temporary.canonicalFile == File(directory, "tmp").canonicalFile ||
                        temporary.canonicalFile.toPath().startsWith(directory.toPath())) {
                        return unproven("owned_guest_mapping_present")
                    }
                }
                if (maps.contains(prefix)) return unproven("directory_mapping_present")
                phase = "process_cwd"
                val cwd = try { Os.readlink(File(process, "cwd").path) } catch (error: Exception) {
                    if (absent(process)) continue else return unproven("cwd_unknown", error)
                }
                if (cwd == directory.path || cwd.startsWith(prefix)) return unproven("directory_cwd_present")
                phase = "fd_enumeration"
                val fds = try { checkNotNull(File(process, "fd").listFiles()) } catch (error: Exception) {
                    if (absent(process)) continue else return unproven("fds_unknown", error)
                }
                require(fds.size <= 4096)
                for (fd in fds) {
                    inspectingFd = fd.name
                    if (SystemClock.elapsedRealtime() >= deadline) return unproven("budget_exhausted")
                    phase = "fd_readlink"
                    val target = try { Os.readlink(fd.path) } catch (error: Exception) {
                        if (absent(fd)) continue else return unproven("fd_unknown", error)
                    }
                    if (target == directory.path || target.startsWith(prefix)) return unproven("directory_fd_present")
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) unproven("budget_exhausted") else true
        } catch (error: Exception) {
            unproven(phase + "_error", error)
        }
    }

    internal fun temporaryBinding(command: String): File? {
        val arguments = command.split('\u0000').filter { it.isNotEmpty() }
        if (arguments.isEmpty() || File(arguments.first()).name != "libproot.so") return null
        val values = arguments.indices.filter { arguments[it] == "-b" }.mapNotNull { index ->
            arguments.getOrNull(index + 1)?.takeIf { it.endsWith(":/tmp") }?.removeSuffix(":/tmp")
        }
        return values.singleOrNull()?.let(::File)?.takeIf { it.isAbsolute }
    }

    private fun startTicks(process: File): String {
        val text = bounded(File(process, "stat"))
        require(text.substringBefore(' ') == process.name && text.lastIndexOf(')') > 0)
        val fields = text.substring(text.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))
        require(fields.size >= 20 && fields[19].all(Char::isDigit))
        return fields[19]
    }

    private fun absent(file: File): Boolean = try {
        Os.lstat(file.path)
        false
    } catch (error: ErrnoException) {
        error.errno == OsConstants.ENOENT
    }

    private fun bounded(file: File): String = file.inputStream().use { input ->
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(bytes.size() + count <= 1024 * 1024)
            bytes.write(buffer, 0, count)
        }
        bytes.toString(Charsets.UTF_8.name())
    }

    private val GUEST_FILES = listOf("libproot.so", "libproot_loader.so",
        "libalpine_bin_busybox.so", "libalpine_lib_ld_musl_aarch64_so_1.so")
}
