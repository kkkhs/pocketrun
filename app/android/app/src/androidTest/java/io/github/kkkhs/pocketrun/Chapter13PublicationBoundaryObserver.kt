package io.github.kkkhs.pocketrun

import android.os.Build
import android.os.FileObserver
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Explicit owned-emulator helper, not an instrumentation test or product executor.
 * Run as the App UID with CLASSPATH=<installed App APK>:<installed Test APK> and
 * app_process -Djava.library.path=<App APK parent>/lib/arm64 /system/bin <this class>.
 * Args: emulator-5556 <App UID> <live main PID> <startTicks> <App APK path> <App APK SHA256>.
 * Wait for ARMED, publish through the real phone/model flow, then force-stop the owned package
 * after STOPPED. MOVED_TO and an actual stopped process are evidence, not journal success.
 */
object Chapter13PublicationBoundaryObserver {
    private const val PACKAGE = "io.github.kkkhs.pocketrun"
    private const val SERIAL = "emulator-5556"
    private const val RESULT = "result.txt"
    private const val MAX_APK_BYTES = 256L * 1024 * 1024

    @JvmStatic fun main(args: Array<String>) {
        val gate = Any()
        val event = CountDownLatch(1)
        var closed = false
        var stopAttempted = false
        var callbackFailure: Throwable? = null
        var pidfd = -1
        var expected: LinuxProcessControl.Identity? = null
        var observer: FileObserver? = null
        var failure: String? = null
        var cleanupUnproven = false
        var cleanup = "not_stopped"
        var stage = "device"
        try {
            check(BuildConfig.DEBUG && Build.VERSION.SDK_INT == 36 && Build.HARDWARE == "ranchu" &&
                Build.PRODUCT == "sdk_phone64_arm64" && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
            require(args.size == 6 && args[0] == SERIAL)
            val uid = args[1].toInt().also { require(it in 10000..19999 && it == Process.myUid()) }
            val pid = args[2].toInt().also { require(it > 1 && it != Process.myPid()) }
            val ticks = args[3].toLong().also { require(it > 0) }
            val apk = installedApk(args[4], PACKAGE)
            val sha = args[5].also { require(Regex("[a-f0-9]{64}").matches(it)) }
            val classpath = checkNotNull(System.getenv("CLASSPATH")).split(':')
            check(classpath.size == 2 && classpath[0] == apk.path)
            installedApk(classpath[1], "$PACKAGE.test")
            val nativeDirectory = File(apk.parentFile, "lib/arm64").canonicalFile
            check(checkNotNull(System.getProperty("java.library.path")).split(File.pathSeparator)
                .any { File(it).canonicalFile == nativeDirectory })
            val nativeLibrary = File(nativeDirectory, "libpocketrun_pty.so")
            check(OsConstants.S_ISREG(Os.lstat(nativeLibrary.path).st_mode) && !nativeLibrary.canWrite())

            stage = "identity"
            val firstText = String(proc("/proc/$pid/stat", 4096), Charsets.US_ASCII)
            val identity = LinuxProcessControl.parseStat(firstText, Os.lstat("/proc/$pid").st_uid)
            check(identity.pid == pid && identity.uid == uid && identity.startTicks == ticks && identity.ppid > 0)
            expected = identity
            // Own the kernel handle before subsequent identity checks; no numeric PID is ever signalled.
            pidfd = Chapter9HoldTestNative.nativePidfdOpen(pid)
            check(pidfd >= 3)
            check(prove(identity) != "T")
            val setupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            stage = "apk_maps"
            val apkIdentity = hashApk(apk, sha, setupDeadline)
            val maps = String(proc("/proc/$pid/maps", 2 * 1024 * 1024), Charsets.US_ASCII)
            check(maps.lineSequence().any { it.substringAfterLast(' ') == apk.path })
            check(prove(identity) != "T")

            // Only the trusted platform App root is normalized; all derived children must be canonical.
            stage = "workspace"
            val root = File("/data/user/0/$PACKAGE").canonicalFile
            privateDirectory(root, uid)
            // Android owns filesDir's mode (0771 here); validate identity, never chmod its root.
            privateDirectory(File(root, "files"), uid, platformFiles = true)
            privateDirectory(File(root, "files/workspaces"), uid)
            val directory = File(root, "files/workspaces/demo")
            val directoryIdentity = privateDirectory(directory, uid).also { check(it.st_mode and 0xfff == 0x1c0) }
            check(LinuxDirectoryLease.readAttributes(File(directory, RESULT)) == null)
            val eventDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            observer = object : FileObserver(directory, MOVED_TO or DELETE_SELF or MOVE_SELF) {
                override fun onEvent(eventMask: Int, path: String?) {
                    synchronized(gate) {
                        if (closed || stopAttempted || callbackFailure != null) return
                        try {
                            check(eventMask and (DELETE_SELF or MOVE_SELF) == 0)
                            if (eventMask and MOVED_TO == 0 || path != RESULT) return
                            check(System.nanoTime() < eventDeadline)
                            // Callback work is one non-waiting syscall plus latch publication: no /proc,
                            // disk hashing, sleeping, or stopped-state polling on FileObserver's shared thread.
                            stopAttempted = true // Retain the resume obligation even when the signal/proof fails.
                            check(Chapter9HoldTestNative.nativePidfdSendSignal(pidfd, OsConstants.SIGSTOP))
                        } catch (error: Throwable) { callbackFailure = error }
                        event.countDown()
                    }
                }
            }
            stage = "observe"
            observer.startWatching()
            check(sameDirectory(directoryIdentity, privateDirectory(directory, uid)))
            check(LinuxDirectoryLease.readAttributes(File(directory, RESULT)) == null)
            check(sameFile(apkIdentity, Os.lstat(apk.path)) && prove(identity) != "T")
            emit("ARMED", identity, sha)
            check(event.await(maxOf(0, eventDeadline - System.nanoTime()), TimeUnit.NANOSECONDS))
            synchronized(gate) { callbackFailure?.let { throw it }; check(stopAttempted) }
            val stopDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
            while (!stopped(identity)) {
                check(System.nanoTime() < stopDeadline)
                Thread.sleep(1)
            }
            check(sameDirectory(directoryIdentity, privateDirectory(directory, uid)))
            val output = File(directory, RESULT)
            check(output.absoluteFile == output.canonicalFile)
            val outputStat = Os.lstat(output.path)
            check(OsConstants.S_ISREG(outputStat.st_mode) && outputStat.st_uid == uid &&
                outputStat.st_mode and 0xfff == 0x180 && outputStat.st_nlink == 1L && outputStat.st_size in 1..65)
            check(sameFile(apkIdentity, Os.lstat(apk.path)))
            emit("STOPPED", identity, sha)
            Thread.sleep(2000)
        } catch (error: Throwable) { failure = safeClass(error) }
        finally {
            // This short fence waits for any in-flight signal and prevents callbacks after FD close.
            synchronized(gate) { closed = true }
            try { observer?.stopWatching() }
            catch (_: Throwable) { cleanupUnproven = true }
            if (pidfd >= 3) {
                try { if (stopAttempted) cleanup = resume(pidfd, checkNotNull(expected)) }
                catch (_: Throwable) { cleanupUnproven = true }
                finally {
                    val descriptor = pidfd
                    pidfd = -1 // One-shot close, including EINTR; never retry a possibly recycled FD.
                    try { Chapter9HoldTestNative.nativePidfdClose(descriptor) }
                    catch (_: Throwable) { cleanupUnproven = true }
                }
            }
        }
        println("CLEANUP state=$cleanup unproven=$cleanupUnproven")
        if (failure != null) println("FAILED kind=$failure stage=$stage")
        System.out.flush()
        exitProcess(if (failure != null || cleanupUnproven) 1 else 0)
    }

    private fun installedApk(path: String, packageName: String): File {
        val file = File(path)
        require(Regex("^/data/app/(?:[^/]+/)?${Regex.escape(packageName)}-[^/]+/base\\.apk$").matches(path))
        check(file.absoluteFile == file.canonicalFile)
        val stat = Os.lstat(path)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_mode and 0x12 == 0 && !file.canWrite())
        return file
    }

    private fun privateDirectory(file: File, uid: Int, platformFiles: Boolean = false): StructStat {
        check(file.absoluteFile == file.canonicalFile)
        var parent: File? = file
        while (parent != null) {
            check(OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode))
            parent = parent.parentFile
        }
        return Os.lstat(file.path).also { check(it.st_uid == uid && (platformFiles || it.st_mode and 0x12 == 0)) }
    }

    private fun hashApk(file: File, expected: String, deadline: Long): StructStat {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            val before = Os.fstat(fd)
            check(OsConstants.S_ISREG(before.st_mode) && before.st_size in 1..MAX_APK_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                check(System.nanoTime() < deadline)
                val count = Os.read(fd, bytes, 0, bytes.size)
                if (count == 0) break
                total += count
                check(count > 0 && total <= MAX_APK_BYTES)
                digest.update(bytes, 0, count)
            }
            check(total == before.st_size && sameFile(before, Os.fstat(fd)) && RootfsInstaller.hex(digest.digest()) == expected)
            return before
        } finally { Os.close(fd) }
    }

    private fun proc(path: String, limit: Int): ByteArray {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { return read(fd, limit) } finally { Os.close(fd) }
    }

    private fun read(fd: FileDescriptor, limit: Int): ByteArray {
        val bytes = ByteArray(limit + 1)
        var size = 0
        while (size < bytes.size) {
            val count = Os.read(fd, bytes, size, bytes.size - size)
            if (count == 0) break
            check(count > 0)
            size += count
        }
        check(size in 1..limit)
        return bytes.copyOf(size)
    }

    private fun prove(expected: LinuxProcessControl.Identity): String {
        fun stat(): Pair<LinuxProcessControl.Identity, String> {
            val text = String(proc("/proc/${expected.pid}/stat", 4096), Charsets.US_ASCII)
            val actual = LinuxProcessControl.parseStat(text, Os.lstat("/proc/${expected.pid}").st_uid)
            check(actual == expected)
            return actual to text.substring(text.lastIndexOf(')') + 2).substringBefore(' ')
        }
        stat()
        val status = String(proc("/proc/${expected.pid}/status", 16384), Charsets.US_ASCII)
        val uids = status.lineSequence().single { it.startsWith("Uid:") }.substringAfter(':').trim().split(Regex("\\s+"))
        check(uids.size == 4 && uids.all { it.toInt() == expected.uid })
        val command = proc("/proc/${expected.pid}/cmdline", 4096)
        val name = PACKAGE.toByteArray(Charsets.US_ASCII)
        check(command.size > name.size && command.copyOfRange(0, name.size).contentEquals(name) &&
            command.drop(name.size).all { it == 0.toByte() })
        return stat().second
    }

    private fun stopped(expected: LinuxProcessControl.Identity): Boolean {
        if (prove(expected) != "T") return false
        val threads = checkNotNull(File("/proc/${expected.pid}/task").listFiles())
        check(threads.size in 1..256)
        val allStopped = threads.all { thread ->
            check(thread.name.toIntOrNull()?.let { it > 0 } == true)
            val text = String(proc("${thread.path}/stat", 4096), Charsets.US_ASCII)
            val identity = LinuxProcessControl.parseStat(text, Os.lstat(thread.path).st_uid)
            check(identity.pid == thread.name.toInt() && identity.uid == expected.uid)
            text.substring(text.lastIndexOf(')') + 2).substringBefore(' ') == "T"
        }
        return allStopped && prove(expected) == "T"
    }

    private fun resume(pidfd: Int, expected: LinuxProcessControl.Identity): String {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                // The already-proven kernel handle remains safe even if /proc is now unknown.
                // ESRCH refers to that original process, never a replacement numeric PID.
                if (!Chapter9HoldTestNative.nativePidfdSendSignal(pidfd, OsConstants.SIGCONT)) return "gone"
                try { if (prove(expected) != "T") return "resumed" }
                catch (error: Exception) { if (System.nanoTime() >= until) throw error }
                check(System.nanoTime() < until)
                try { Thread.sleep(5) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    private fun sameDirectory(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_uid == b.st_uid && a.st_mode == b.st_mode

    private fun sameFile(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_uid == b.st_uid && a.st_mode == b.st_mode && a.st_size == b.st_size && a.st_nlink == b.st_nlink &&
        a.st_mtim.tv_sec == b.st_mtim.tv_sec && a.st_mtim.tv_nsec == b.st_mtim.tv_nsec &&
        a.st_ctim.tv_sec == b.st_ctim.tv_sec && a.st_ctim.tv_nsec == b.st_ctim.tv_nsec

    private fun safeClass(error: Throwable) = error.javaClass.simpleName.takeIf { Regex("[A-Za-z0-9_]{1,96}").matches(it) } ?: "Throwable"
    private fun emit(stage: String, identity: LinuxProcessControl.Identity, sha: String) {
        println("$stage uid=${identity.uid} pid=${identity.pid} startTicks=${identity.startTicks} sourceApkSha256=$sha")
        System.out.flush()
    }
}
