package io.github.kkkhs.pocketrun

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Process
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kkkhs.pocketrun.taskhost.TaskHostJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit host-driven fixture, not a tool/approval implementation. Prepare the real Linux tool first;
 * after ARM the host submits/approves it through TaskHostPanel, then cancels after HELD or waits for
 * the original five-second deadline. The host must separately verify the real product terminal state.
 *
 * Selector: io.github.kkkhs.pocketrun.Chapter9ProductLinuxHoldTest#armOwnedProductLinuxHold
 * Args: chapter9HoldMode=cancel|deadline, authorizedSerial=emulator-5556, authorizedApi=36,
 * authorizedAbi=arm64-v8a, chapter9HoldNonce=<fresh 64 lowercase hex>, sourceApkSha256=<installed APK>.
 * Fixed files/device-bindings/chapter9-linux-hold.json (0700 parent, 0600 single-link file):
 * {"schema_v":1,"nonce":"<same nonce>","mode":"cancel|deadline","serial":"emulator-5556",
 *  "api":36,"abi":"arm64-v8a","app_uid":<UID>,"boot_count":<current device boot count>,
 *  "source_apk_sha256":"<same hash>","expires_at_epoch":<device epoch + 1..120 seconds>}
 * Serial is the host's owned-AVD attestation, never a privileged hardware identifier/bypass.
 */
@RunWith(AndroidJUnit4::class)
class Chapter9ProductLinuxHoldTest {
    @Test fun armOwnedProductLinuxHold() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit Chapter9 class selector required", args.getString("class") in setOf(CLASS, "$CLASS#armOwnedProductLinuxHold"))
        var authorized: Guard? = null
        val cleanupErrors = mutableListOf<Throwable>()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val guard = authorize(context, args).also { authorized = it }
            val files = context.filesDir.canonicalFile // Normalize only the trusted platform files root.
            val runs = File(files, "linux/runs")
            privateDirectory(files, null) // Android owns the platform files-root mode; never chmod it.
            privateDirectory(File(files, "linux"))
            privateDirectory(runs) // No fixture preparation: the original product UI must prepare Linux.
            val prior = checkNotNull(runs.list()).toSet()
            val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so").canonicalFile
            val lock = Any()
            val ready = CountDownLatch(1)
            val observers = mutableListOf<FileObserver>()
            var closed = false
            var selected: File? = null
            var held: Held? = null
            var confirmed = false
            var failure: Throwable? = null
            var primary: Throwable? = null

            fun guardedEvent(action: () -> Unit) = synchronized(lock) {
                if (!closed && failure == null) {
                    try { action() }
                    catch (error: Throwable) { failure = error; ready.countDown() }
                }
            }
            fun watch(observer: FileObserver) {
                observers += observer // Own it before startWatching, including its failure path.
                observer.startWatching()
            }
            fun hold(directory: File, closeWrite: Boolean) {
                if (confirmed) return
                val before = privateDirectory(directory)
                val record = File(directory, "tracer.identity")
                val bytes = readPrivateRecord(record, closeWrite) ?: run {
                    check(!closeWrite) { "Closed tracer identity disappeared; fast pipeline was missed." }
                    return
                }
                val identity = try { LinuxProcessControl.parseIdentity(bytes) }
                catch (error: Exception) {
                    if (!closeWrite) return // Existing record may still be writing; await its CLOSE_WRITE.
                    throw error
                }
                check(sameFile(before, privateDirectory(directory)))
                privateDirectory(File(directory, "tmp"))
                check(identity.pid > 1 && identity.pid != Process.myPid() && identity.uid == Process.myUid() &&
                    identity.ppid == Process.myPid())
                // Own the handle before any /proc proof. Retain it across a still-open registration
                // and all failures; a recycled numeric PID can never redirect either signal.
                val exact = held ?: Held(identity, proot, File(directory, "tmp").path + ":/tmp", directory.name).also {
                    held = it
                    it.pidfd = Chapter9HoldTestNative.nativePidfdOpen(identity.pid)
                }
                check(exact.identity == identity && exact.runName == directory.name && exact.pidfd >= 3)
                check(prove(exact) != null) { "Fast pipeline exited before hold; this is not a pass." }
                exact.identityProven = true
                if (!closeWrite) {
                    // A complete record can precede close by a few instructions. Only its proven child
                    // is inspected: an open registration FD waits for CLOSE_WRITE, never claims ready.
                    val descriptors = checkNotNull(File("/proc/${identity.pid}/fd").listFiles())
                    for (descriptor in descriptors) {
                        val target = try { Os.readlink(descriptor.path) }
                        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) continue else throw error }
                        if (target == record.path) return
                    }
                }
                guard.live()
                check(prove(exact) != null) { "Fast pipeline exited at the signal gate." }
                // Save the cleanup obligation BEFORE SIGSTOP or any post-signal proof can fail.
                exact.stopAttempted = true
                check(signalPinned(exact, OsConstants.SIGSTOP)) { "Original tracer exited at the pinned signal gate." }
                val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
                while (true) {
                    val state = prove(exact)
                    check(state != null) { "Fast pipeline exited before stopped-state proof." }
                    if (state == "T") break
                    check(System.nanoTime() < until) { "SIGSTOP did not produce an actual stopped tracer." }
                    Thread.sleep(1)
                }
                confirmed = true
                evidence("HELD", guard, exact, prior.size)
                ready.countDown()
            }
            fun select(name: String) {
                if (name in prior || !RUN_NAME.matches(name)) return
                val directory = File(runs, name)
                check(directory.parentFile == runs)
                if (selected != null) {
                    check(selected == directory) { "More than one new product RUN while armed." }
                    return // Exactly one observer per selected run, even for duplicate create/move events.
                }
                selected = directory
                watch(object : FileObserver(directory, CLOSE_WRITE or DELETE_SELF or MOVE_SELF) {
                    override fun onEvent(event: Int, path: String?) = guardedEvent {
                        if (event and (DELETE_SELF or MOVE_SELF) != 0 && !confirmed) {
                            error("New RUN disappeared before hold; fast pipeline was missed.")
                        }
                        if (event and CLOSE_WRITE != 0 && path == "tracer.identity") hold(directory, true)
                    }
                })
                // The native registration may close before this observer is installed. A complete existing
                // record plus live kernel identity is usable; absence/partial data awaits CLOSE_WRITE.
                hold(directory, false)
            }
            try {
                synchronized(lock) {
                    guard.live()
                    watch(object : FileObserver(runs, CREATE or MOVED_TO or DELETE_SELF or MOVE_SELF) {
                        override fun onEvent(event: Int, path: String?) = guardedEvent {
                            check(event and (DELETE_SELF or MOVE_SELF) == 0) { "Product runs parent changed while armed." }
                            if (event and (CREATE or MOVED_TO) != 0 && path != null) select(path)
                        }
                    })
                    evidence("ARM", guard, null, prior.size)
                    // Catch creation between the baseline snapshot and parent-watch installation.
                    checkNotNull(runs.list()).forEach(::select)
                }
                check(ready.await(20, TimeUnit.SECONDS)) { "No new closed product tracer identity; hold was not hit." }
                val exact = synchronized(lock) {
                    failure?.let { throw it }
                    check(confirmed)
                    checkNotNull(held)
                }
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (true) {
                    synchronized(lock) { failure?.let { throw it } }
                    if (observe(exact.identity) == null) break
                    check(System.nanoTime() < until) { "Original product did not reclaim the held tracer." }
                    Thread.sleep(10)
                }
            } catch (error: Throwable) {
                primary = error
            } finally {
                // The framework's FileObserver thread is shared, not ours. Fence callbacks and stop only
                // our owned watches; an observer error must never skip pinned-handle SIGCONT or close.
                val owned = synchronized(lock) { closed = true; observers.toList().asReversed() }
                for (observer in owned) {
                    try { observer.stopWatching() }
                    catch (error: Throwable) { cleanupErrors += error }
                }
                val exact = held
                if (exact != null) {
                    try { if (exact.stopAttempted) resumeIfPresent(exact) }
                    catch (error: Throwable) { cleanupErrors += error }
                    finally {
                        val descriptor = exact.pidfd
                        exact.pidfd = -1 // Relinquish ownership BEFORE one-shot close, even on EINTR.
                        try { if (descriptor >= 3) Chapter9HoldTestNative.nativePidfdClose(descriptor) }
                        catch (error: Throwable) { cleanupErrors += error }
                    }
                }
                if (primary == null) primary = cleanupErrors.firstOrNull() ?: synchronized(lock) { failure }
            }
            primary?.let { throw it }
            evidence("RELEASED", guard, checkNotNull(held), prior.size)
            // Keep the actual App UI available for terminal-state capture before the runner finishes.
            Thread.sleep(8_000)
        } catch (error: Throwable) {
            // Parser/OS exceptions can contain receipt, command, or file contents. Never publish causes.
            val json = JSONObject().put("stage", "FAILED").put("errorClass", errorClass(error))
                .put("cleanup_unproven", cleanupErrors.isNotEmpty())
                .put("cleanupErrorClasses", JSONArray(cleanupErrors.map(::errorClass)))
            authorized?.let { json.put("nonce", it.nonce) }
            try {
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("chapter9_product_hold", json.toString())
                })
            } catch (_: Throwable) { /* The sanitized assertion still carries all cleanup flags. */ }
            throw AssertionError("Chapter9 product hold failed ($json); no product pass claimed.")
        }
    }

    private data class Held(val identity: LinuxProcessControl.Identity, val proot: File, val tmpBinding: String, val runName: String) {
        var pidfd = -1
        var identityProven = false
        var stopAttempted = false
    }
    private data class Guard(val mode: String, val nonce: String, val apk: String, val boot: Int, val expires: Long, val monotonicExpiry: Long) {
        fun live() {
            check(expires - System.currentTimeMillis() / 1000 in 1..120)
            check(System.nanoTime() < monotonicExpiry)
        }
    }

    private fun authorize(context: Context, args: Bundle): Guard {
        check(BuildConfig.DEBUG)
        check(args.getString("authorizedSerial") == SERIAL && args.getString("authorizedApi") == "36" &&
            args.getString("authorizedAbi") == ABI)
        check(Build.VERSION.SDK_INT == 36 && Build.SUPPORTED_ABIS.firstOrNull() == ABI &&
            Build.HARDWARE == "ranchu" && Build.PRODUCT == "sdk_phone64_arm64")
        check(context.packageName == CLASS.substringBeforeLast('.') && context.applicationInfo.uid == Process.myUid())
        val mode = checkNotNull(args.getString("chapter9HoldMode")).also { check(it in setOf("cancel", "deadline")) }
        val nonce = checkNotNull(args.getString("chapter9HoldNonce")).also { check(HEX.matches(it)) }
        val expected = checkNotNull(args.getString("sourceApkSha256")).also { check(HEX.matches(it)) }
        val files = context.filesDir.canonicalFile
        privateDirectory(files, null) // Android owns the platform files-root mode; never chmod it.
        val directory = File(files, "device-bindings")
        val parent = privateDirectory(directory)
        val receipt = File(directory, "chapter9-linux-hold.json")
        val fd = Os.open(receipt.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
        val opened: StructStat
        val bytes: ByteArray
        try {
            opened = privateRecordStat(fd)
            bytes = readBounded(fd, 4096)
            check(bytes.size.toLong() == opened.st_size && sameFile(opened, privateRecordStat(fd)))
        } finally { Os.close(fd) }
        check(bytes.isNotEmpty() && bytes.all { it.toInt() in 9..13 || it.toInt() in 32..126 })
        val fields = TaskHostJson.parseObject(String(bytes, Charsets.US_ASCII))
        check(fields.keys == BINDING_KEYS)
        fun string(key: String): String = checkNotNull(fields[key] as? String)
        fun number(key: String): Long {
            val value = fields[key]
            check(value is Int || value is Long) // No strings, booleans, nulls or floating-point coercion.
            return (value as Number).toLong().also { check(it >= 0) }
        }
        check(number("schema_v") == 1L && string("nonce") == nonce && string("mode") == mode &&
            string("serial") == SERIAL && number("api") == 36L && string("abi") == ABI &&
            number("app_uid") == Process.myUid().toLong())
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        check(boot >= 0 && number("boot_count") == boot.toLong())
        val source = File(context.applicationInfo.sourceDir).canonicalFile
        val apk = source.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= 256L * 1024 * 1024)
                digest.update(buffer, 0, count)
            }
            RootfsInstaller.hex(digest.digest())
        }
        check(apk == expected && string("source_apk_sha256") == apk)
        val expires = number("expires_at_epoch")
        val remaining = expires - System.currentTimeMillis() / 1000
        check(remaining in 1..120)
        val guard = Guard(mode, nonce, apk, boot, expires, System.nanoTime() + TimeUnit.SECONDS.toNanos(remaining))
        synchronized(CONSUMED_NONCES) {
            check(nonce !in CONSUMED_NONCES && sameFile(parent, privateDirectory(directory)))
            val current = Os.lstat(receipt.path)
            check(sameFile(opened, current) && OsConstants.S_ISREG(current.st_mode))
            guard.live()
            check(receipt.delete()) // Unlink the checked regular file BEFORE any observer or signal.
            CONSUMED_NONCES.add(nonce)
        }
        return guard
    }

    private fun privateDirectory(directory: File, mode: Int? = 0x1c0): StructStat {
        check(directory.absoluteFile == directory.canonicalFile)
        var parent: File? = directory.absoluteFile
        while (parent != null) {
            check(OsConstants.S_ISDIR(Os.lstat(parent.path).st_mode)) // lstat: no symlink parents, not just a nofollow leaf.
            parent = parent.parentFile
        }
        return Os.lstat(directory.path).also {
            check(it.st_uid == Process.myUid() && (mode == null || (it.st_mode and 0xfff) == mode))
        }
    }
    private fun sameFile(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_uid == b.st_uid && a.st_mode == b.st_mode && a.st_nlink == b.st_nlink && a.st_size == b.st_size
    private fun privateRecordStat(fd: FileDescriptor) = Os.fstat(fd).also {
        check(OsConstants.S_ISREG(it.st_mode) && it.st_uid == Process.myUid() &&
            (it.st_mode and 0xfff) == 0x180 && it.st_nlink == 1L && it.st_size in 0..4096)
    }
    private fun readPrivateRecord(file: File, closeWrite: Boolean): ByteArray? {
        val fd = try { Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0) }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return null else throw error }
        try {
            val before = privateRecordStat(fd)
            val bytes = readBounded(fd, 4096)
            val after = privateRecordStat(fd)
            if (bytes.size.toLong() != before.st_size || !sameFile(before, after)) {
                check(!closeWrite) { "Closed tracer record changed during observation." }
                return null
            }
            return bytes
        } finally { Os.close(fd) }
    }
    private fun readBounded(fd: FileDescriptor, maximum: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (true) {
            val count = Os.read(fd, buffer, 0, buffer.size)
            if (count == 0) break
            check(count > 0 && out.size() + count <= maximum)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    private fun proc(path: String, maximum: Int): ByteArray {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try { return readBounded(fd, maximum) } finally { Os.close(fd) }
    }

    /** null means proven old identity gone, never permission/parse/I/O uncertainty. */
    private fun observe(expected: LinuxProcessControl.Identity): String? {
        try {
            val text = String(proc("/proc/${expected.pid}/stat", 4096), Charsets.US_ASCII)
            val uid = Os.lstat("/proc/${expected.pid}").st_uid
            val actual = LinuxProcessControl.parseStat(text, uid)
            check(actual.pid == expected.pid)
            if (actual.startTicks != expected.startTicks) return null // Never signal a reused PID.
            check(actual == expected)
            return text.substring(text.lastIndexOf(')') + 2).substringBefore(' ')
        } catch (error: ErrnoException) {
            // A successful open can race an exit/read ESRCH. Two exact ENOENTs prove absence;
            // an existing directory or any other errno retains the original unknown failure.
            repeat(2) {
                val absent = try { Os.lstat("/proc/${expected.pid}"); false }
                catch (missing: ErrnoException) { if (missing.errno == OsConstants.ENOENT) true else throw error }
                if (!absent) throw error
            }
            return null
        }
    }
    private fun prove(held: Held): String? {
        observe(held.identity) ?: return null
        try {
            val executable = Os.lstat(held.proot.path)
            check(OsConstants.S_ISREG(executable.st_mode) && (executable.st_mode and 0x12) == 0 && !held.proot.canWrite())
            check(Os.readlink("/proc/${held.identity.pid}/exe") == held.proot.path)
            val status = String(proc("/proc/${held.identity.pid}/status", 8192), Charsets.US_ASCII)
            val uids = checkNotNull(Regex("(?m)^Uid:\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)$").find(status))
            check(uids.groupValues.drop(1).all { it.toInt() == Process.myUid() })
            val argv = String(proc("/proc/${held.identity.pid}/cmdline", 16384), Charsets.UTF_8).split(0.toChar())
            check(argv.first() == held.proot.path && argv.indices.any {
                it > 0 && argv[it - 1] == "-b" && argv[it] == held.tmpBinding
            })
            return observe(held.identity) // Recheck PID/startTicks/UID/parent after exe and exact tmp-binding proof.
        } catch (error: Exception) {
            if (observe(held.identity) == null) return null
            throw error
        }
    }
    private fun signalPinned(held: Held, signal: Int): Boolean {
        check(held.identityProven && held.pidfd >= 3)
        return Chapter9HoldTestNative.nativePidfdSendSignal(held.pidfd, signal)
    }
    private fun resumeIfPresent(held: Held) {
        check(held.identityProven)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try {
                    prove(held) ?: return
                    if (!signalPinned(held, OsConstants.SIGCONT)) return // ESRCH on the proven original handle only.
                    // Confirmation is required even after a successful signal; uncertainty is not cleanup.
                    val state = observe(held.identity)
                    if (state == null || state != "T") return
                } catch (error: Exception) {
                    if (System.nanoTime() >= until) throw error
                }
                check(System.nanoTime() < until) { "Exact held tracer could not be resumed within cleanup budget." }
                try { Thread.sleep(10) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    private fun errorClass(error: Throwable): String = error.javaClass.simpleName
        .takeIf { Regex("[A-Za-z0-9_]{1,96}").matches(it) } ?: "Throwable"
    private fun evidence(stage: String, guard: Guard, held: Held?, prior: Int) {
        check(stage in setOf("ARM", "HELD", "RELEASED"))
        val json = JSONObject().put("stage", stage).put("mode", guard.mode).put("nonce", guard.nonce).put("serial", SERIAL)
            .put("api", 36).put("abi", ABI).put("bootCount", guard.boot)
            .put("appUid", Process.myUid()).put("appPid", Process.myPid()).put("priorRuns", prior)
            .put("sourceApkSha256", guard.apk)
        held?.let {
            json.put("pid", it.identity.pid).put("uid", it.identity.uid).put("ppid", it.identity.ppid)
                .put("startTicks", it.identity.startTicks).put("run", it.runName)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("chapter9_product_hold", json.toString())
        })
    }
    companion object {
        private const val CLASS = "io.github.kkkhs.pocketrun.Chapter9ProductLinuxHoldTest"
        private const val SERIAL = "emulator-5556"
        private const val ABI = "arm64-v8a"
        private val HEX = Regex("[0-9a-f]{64}")
        private val RUN_NAME = Regex("pocketrun-run-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val BINDING_KEYS = setOf("schema_v", "nonce", "mode", "serial", "api", "abi", "app_uid", "boot_count", "source_apk_sha256", "expires_at_epoch")
        private val CONSUMED_NONCES = mutableSetOf<String>()
    }
}

/** Matching JNI exports exist only in a native Debug build, never in a release APK. */
internal object Chapter9HoldTestNative {
    init { System.loadLibrary("pocketrun_pty") }
    external fun nativePidfdOpen(pid: Int): Int
    external fun nativePidfdSendSignal(fd: Int, signal: Int): Boolean
    external fun nativePidfdClose(fd: Int)
}
