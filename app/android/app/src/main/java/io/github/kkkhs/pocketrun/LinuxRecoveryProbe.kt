package io.github.kkkhs.pocketrun

import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Conservative recovery observation. Unknown procfs access never authorizes deletion. */
internal object LinuxRecoveryProbe {
    fun isUnusedByAppProcesses(directory: File, timeoutMillis: Long = 1500): Boolean {
        return try {
        require(timeoutMillis in 1..1500)
        require(directory.canonicalFile == directory.absoluteFile)
        val prefix = directory.path.trimEnd('/') + "/"
        val uid = Process.myUid()
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val processes = checkNotNull(File("/proc").listFiles()).filter { it.name.toIntOrNull() != null }
        require(processes.size <= 20000)
        for (process in processes) {
            if (SystemClock.elapsedRealtime() >= deadline) return false
            if (absent(process)) continue
            val status = try { bounded(File(process, "status")) } catch (_: Exception) {
                if (absent(process)) continue else return false
            }
            val ids = status.lineSequence().firstOrNull { it.startsWith("Uid:") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.map { it.toInt() }
                ?: return false
            if (uid !in ids) continue
            val maps = try { bounded(File(process, "maps")) } catch (_: Exception) {
                if (absent(process)) continue else return false
            }
            // A surviving tracer/guest may use its tmp later even with no currently open tmp FD.
            if (GUEST_FILES.any(maps::contains)) return false
            if (maps.contains(prefix)) return false
            val cwd = try { Os.readlink(File(process, "cwd").path) } catch (_: Exception) {
                if (absent(process)) continue else return false
            }
            if (cwd == directory.path || cwd.startsWith(prefix)) return false
            val fds = try { checkNotNull(File(process, "fd").listFiles()) } catch (_: Exception) {
                if (absent(process)) continue else return false
            }
            require(fds.size <= 4096)
            for (fd in fds) {
                if (SystemClock.elapsedRealtime() >= deadline) return false
                val target = try { Os.readlink(fd.path) } catch (_: Exception) {
                    if (absent(fd)) continue else return false
                }
                if (target == directory.path || target.startsWith(prefix)) return false
            }
        }
        SystemClock.elapsedRealtime() < deadline
        } catch (_: Exception) { false }
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
