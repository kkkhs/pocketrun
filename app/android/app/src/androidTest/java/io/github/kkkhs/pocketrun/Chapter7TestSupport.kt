package io.github.kkkhs.pocketrun

import android.os.Bundle
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Test-only fixed recipes, using the production argv/binds/environment and real installed APK ELFs. */
internal object Chapter7TestSupport {
    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    fun runtime() = LinuxRuntimeManifest.read(context.assets.open("linux/runtime.json"))
    fun installer(files: File, runtime: LinuxRuntime = runtime()) = RootfsInstaller(files,
        File(context.applicationInfo.nativeLibraryDir).canonicalFile, runtime, context.assets::open)
    fun session(files: File, installer: RootfsInstaller = installer(files)) =
        LinuxSession(files, installer, Process.myUid(), Process.myPid(),
            canRemoveRun = LinuxRecoveryProbe::isUnusedByAppProcesses)
    fun payloadNames(directory: File) = directory.list().orEmpty().filter { it != ".pocketrun-recovery.lock" }
    fun evidence(stage: String, data: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("chapter7_lifecycle", data.put("stage", stage).put("appUid", Process.myUid()).toString())
        })
    }
    fun result(stage: String, value: CommandPipeline.Result) = evidence(stage, JSONObject()
        .put("status", value.status).put("exits", JSONArray(value.exitCodes))
        .put("stdout", value.stdout).put("stderr", value.stderr)
        .put("cleanupProven", value.cleanupProven).put("cleanupFailure", value.cleanupFailure ?: JSONObject.NULL)
        .put("stdoutBytes", value.stdout.toByteArray(Charsets.UTF_8).size)
        .put("stderrBytes", value.stderr.toByteArray(Charsets.UTF_8).size).put("durationMillis", value.durationMillis))
    fun assertCleanup(value: CommandPipeline.Result, run: CommandPipeline.Run) {
        assertTrue("Result cleanup unproven: ${value.cleanupFailure}", value.cleanupProven)
        assertNull(value.cleanupFailure)
        assertTrue("Run cleanup unproven: ${run.cleanupFailure.get()}", run.cleanupProven)
        assertNull(run.cleanupFailure.get())
    }
    fun await(message: String, timeoutMillis: Long = 3000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        fail(message)
    }
    fun fdCount() = checkNotNull(File("/proc/self/fd").list()).size
    fun ioThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "PocketRun-PipeIO" }
    fun inFiles(test: (File) -> Unit) {
        // Normalize only this newly created, trusted platform-root fixture. Child-path guards stay strict.
        val root = Files.createTempDirectory(context.filesDir.toPath(), "chapter7-real-").toFile().canonicalFile
        // A failure may leave a diagnosed tree alive. Never erase its markers before diagnosis/cleanup.
        var passed = false
        try { test(root); passed = true } finally { if (passed) assertTrue(root.deleteRecursively()) }
    }

    data class Identity(val role: String, val pid: Int, val ticks: String, val uid: Int,
        val ppid: Int, val sid: Int, val tracer: Int, val tgid: Int, val cmdline: String) {
        fun json() = JSONObject().put("role", role).put("pid", pid).put("startTicks", ticks).put("uid", uid)
            .put("ppid", ppid).put("sid", sid).put("tracerPid", tracer).put("tgid", tgid).put("cmdline", cmdline)
    }
    private fun stat(pid: Int): List<String>? {
        val path = File("/proc/$pid/stat").toPath()
        val text = try { String(Files.readAllBytes(path), Charsets.UTF_8) } catch (_: NoSuchFileException) {
            // Only an explicit second ENOENT is evidence of absence. Permission/read errors propagate.
            try { String(Files.readAllBytes(path), Charsets.UTF_8) } catch (_: NoSuchFileException) { return null }
        }
        require(text.substringBefore(' ') == pid.toString() && text.lastIndexOf(')') > 0)
        return text.substring(text.lastIndexOf(')') + 2).trim().split(Regex("\\s+")).also { require(it.size >= 20) }
    }
    fun identity(role: String, pid: Int): Identity {
        val fields = checkNotNull(stat(pid)) { "Missing owned $role PID $pid" }
        val status = String(Files.readAllBytes(File("/proc/$pid/status").toPath()), Charsets.UTF_8)
        fun field(name: String) = checkNotNull(Regex("(?m)^$name:\\s+(\\d+)").find(status)).groupValues[1].toInt()
        val uid = field("Uid")
        require(uid == Process.myUid()) { "Refusing a PID outside the App UID" }
        return Identity(role, pid, fields[19], uid, fields[1].toInt(), fields[3].toInt(),
            field("TracerPid"), field("Tgid"), String(Files.readAllBytes(File("/proc/$pid/cmdline").toPath()), Charsets.UTF_8).replace('\u0000', ' '))
    }
    fun same(identity: Identity): Boolean = stat(identity.pid)?.get(19) == identity.ticks
    fun procEvidence(stage: String, identities: List<Identity>) {
        val observations = identities.map { expected ->
            val record = JSONObject().put("expected", expected.json())
            try {
                val fields = stat(expected.pid)
                if (fields == null) record.put("observation", "double-ENOENT")
                else if (fields[19] != expected.ticks) record.put("observation", "pid-reused").put("observedTicks", fields[19])
                else {
                    val actual = identity(expected.role, expected.pid)
                    require(actual.ticks == expected.ticks) { "Identity changed during proc observation" }
                    val count = Files.list(File("/proc/${expected.pid}/fd").toPath()).use { it.count() }
                    val end = stat(expected.pid)
                    require(end != null && end[19] == expected.ticks) { "Identity disappeared during FD observation" }
                    record.put("observation", "same-identity").put("actual", actual.json())
                        .put("state", end[0]).put("fdCount", count)
                }
            } catch (error: Exception) {
                record.put("observation", "unknown").put("reason", "${error.javaClass.simpleName}: ${error.message}".take(256))
            }
            record
        }
        // Diagnostics never authorize cleanup: unknown remains unknown, and the original assertion still fails.
        evidence(stage, JSONObject().put("proc", JSONArray(observations)).put("pipeIoThreads", ioThreads()))
    }
    fun assertGone(identities: List<Identity>) {
        try { await("Owned PRoot/guest PID or tracer TID remains after termination", 2500) { identities.none(::same) } }
        catch (failure: AssertionError) { procEvidence("reclaim-unproven", identities); throw failure }
        catch (failure: Exception) { procEvidence("reclaim-unknown", identities); throw failure }
        evidence("reclaimed", JSONObject().put("identities", JSONArray(identities.map { it.json() }))
            .put("pipeIoThreads", ioThreads()))
        await("Pipe IO threads remain") { ioThreads() == 0 }
    }

    /** Main guest blocks on a FIFO, with an ordinary background sleeper and a real setsid sleeper. */
    class Tree(val files: File, val fallback: Boolean = false) : Closeable {
        val installer = installer(files)
        val installation = installer.prepare()
        val workspace = File(WorkspaceSession(files).use { it.prepare() }.directory)
        val markers = File(workspace, ".chapter7-tree-${UUID.randomUUID()}").apply { RootfsTar.privateDirectory(this) }
        private val owned = LinuxDirectoryLease.allocate(File(files, "linux/runs"), LinuxDirectoryLease.Kind.RUN,
            LinuxRecoveryProbe::isUnusedByAppProcesses)
        val runDirectory = owned.directory
        val tmp = File(runDirectory, "tmp").apply { RootfsTar.privateDirectory(this) }
        private val control = LinuxProcessControl(runDirectory, File(installer.nativeDirectory, "libproot.so"))
        val tracerRecord get() = control.tracerRecord
        val pipe = CommandPipeline()
        val run = pipe.reserve()
        private val worker = Executors.newSingleThreadExecutor()
        private var captured: List<Identity> = emptyList()
        private var future: java.util.concurrent.Future<CommandPipeline.Result>? = null
        fun start(output: String? = null, timeoutMillis: Long = CommandPipeline.TIMEOUT_MS, finishNormally: Boolean = false) {
            check(future == null)
            val marker = "/workspace/${markers.name}"
            val prefix = """
                /bin/busybox sh -c 'echo "${'$'}${'$'}" > $marker/background.pid; exec /bin/busybox sleep 30' &
                /bin/busybox setsid /bin/busybox sh -c 'echo "${'$'}${'$'}" > $marker/detached.pid; exec /bin/busybox sleep 30' &
                echo "${'$'}${'$'}" > $marker/guest.pid;
                /bin/busybox mkfifo /tmp/start || exit 125;
                printf ready > $marker/ready;
                read action < /tmp/start || exit 126;
            """.trimIndent().replace('\n', ' ')
            val suffix = if (finishNormally) {
                require(output == null)
                "exit 0"
            } else if (output == null) "exec /bin/busybox sleep 30" else {
                require(output == "stdout" || output == "stderr")
                "i=0; while [ \"${'$'}i\" -lt 2000 ]; do printf '" + "x".repeat(80) +
                    "\\n'" + (if (output == "stderr") " >&2" else "") + "; i=${'$'}((i+1)); done; exec /bin/busybox sleep 30"
            }
            val argv = LinuxSession.demoArgv(installation.root, workspace, tmp, installer.nativeDirectory,
                installer.runtime.bindings).dropLast(1) + (prefix + suffix)
            val env = environment(tmp, control)
            future = worker.submit<CommandPipeline.Result> {
                pipe.execute(run, workspace, listOf(argv), timeoutMillis, env, true,
                    onStarted = control::registerOwned, terminateOwned = control::terminateOwned)
            }
        }
        fun capture(): List<Identity> {
            await("PRoot tree did not publish all real PID markers") {
                markers.resolve("ready").isFile && listOf("guest", "background", "detached").all {
                    markers.resolve("$it.pid").takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull() != null
                }
            }
            val guests = listOf("guest", "background", "detached").map {
                identity(it, markers.resolve("$it.pid").readText().trim().toInt())
            }
            require(guests.all { it.tracer > 0 }) { "Guest must actually be traced by PRoot" }
            val tracerPid = identity("tracer-tid", guests.first().tracer).tgid
            val tracer = identity("tracer", tracerPid)
            require(tracer.ppid == Process.myPid() && tracer.cmdline.contains(installer.nativeDirectory.path + "/libproot.so"))
            val tids = checkNotNull(File("/proc/$tracerPid/task").list()).map { identity("tracer-tid", it.toInt()) }
            require(guests.all { guest -> tids.any { it.pid == guest.tracer } })
            require(guests.single { it.role == "detached" }.let { it.sid == it.pid }) { "setsid recipe did not detach" }
            captured = (listOf(tracer) + guests + tids).distinctBy { it.pid }
            evidence(if (fallback) "fallback-tree" else "default-tree", JSONObject()
                .put("identities", JSONArray(captured.map { it.json() })).put("appPid", Process.myPid())
                .put("appStartTicks", identity("app", Process.myPid()).ticks)
                .put("markers", markers.path).put("tmp", tmp.path).put("tracerRecord", control.tracerRecord.path))
            return captured
        }
        fun openGate() {
            var descriptor: java.io.FileDescriptor? = null
            await("Guest FIFO reader not available", 1500) {
                try { descriptor = Os.open(File(tmp, "start").path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0); true }
                catch (error: ErrnoException) { if (error.errno == OsConstants.ENXIO) false else throw error }
            }
            val fd = checkNotNull(descriptor)
            try { assertEquals(3, Os.write(fd, "go\n".toByteArray(), 0, 3)) } finally { Os.close(fd) }
        }
        fun result() = checkNotNull(future).get(7, TimeUnit.SECONDS)
        private fun environment(temporary: File, controller: LinuxProcessControl) =
            LinuxSession.demoEnvironment(installer.nativeDirectory, temporary, Process.myPid()) +
                mapOf("POCKETRUN_TRACER_RECORD" to controller.tracerRecord.path) +
                (if (fallback) mapOf("PROOT_NO_SECCOMP" to "1") else emptyMap())

        fun rerun() {
            // Every execution has a fresh real lease/controller/record; an old PID certificate is never reused.
            val retry = LinuxDirectoryLease.allocate(File(files, "linux/runs"), LinuxDirectoryLease.Kind.RUN,
                LinuxRecoveryProbe::isUnusedByAppProcesses)
            try {
                val retryTmp = File(retry.directory, "tmp").apply { RootfsTar.privateDirectory(this) }
                val retryControl = LinuxProcessControl(retry.directory, File(installer.nativeDirectory, "libproot.so"))
                val retryRun = pipe.reserve()
                val value = pipe.execute(retryRun, workspace,
                    listOf(LinuxSession.demoArgv(installation.root, workspace, retryTmp, installer.nativeDirectory, installer.runtime.bindings)),
                    env = environment(retryTmp, retryControl), clearEnvironment = true,
                    onStarted = retryControl::registerOwned, terminateOwned = retryControl::terminateOwned)
                Chapter7TestSupport.result("same-pipe-rerun", value)
                assertEquals(value.stderr, "completed", value.status)
                assertCleanup(value, retryRun)
                assertEquals(Process.myUid(), checkNotNull(LinuxSession.parseDemo(value.stdout)).uid)
                assertTrue(pipe.runningProcesses().isEmpty())
            } finally {
                retry.close()
                evidence("rerun-lease-cleanup", JSONObject().put("runDirectory", retry.directory.path)
                    .put("cleanupStatus", if (retry.cleanupProven) "done" else "unproven"))
                assertTrue("Real rerun cleanup was not proven", retry.cleanupProven)
            }
        }
        override fun close() {
            pipe.close()
            worker.shutdownNow()
            worker.awaitTermination(1, TimeUnit.SECONDS)
            owned.close()
            val probe = LinuxRecoveryProbe.lastFailure(runDirectory)
            evidence("tree-run-lease-cleanup", JSONObject().put("runDirectory", runDirectory.path)
                .put("cleanupStatus", if (owned.cleanupProven) "done" else "unproven")
                .put("cleanupFailureReason", owned.cleanupFailureReason ?: JSONObject.NULL)
                .put("cleanupElapsedMillis", owned.elapsedMillis)
                .put("probeFailureReason", probe?.reason ?: JSONObject.NULL)
                .put("probePid", probe?.pid ?: JSONObject.NULL)
                .put("probeFd", probe?.fd ?: JSONObject.NULL)
                .put("probeErrno", probe?.errno ?: JSONObject.NULL))
            // Never replace the production conservative proof with unconditional deletion.
            assertTrue("Actual LinuxRecoveryProbe could not prove RUN lease cleanup", owned.cleanupProven)
            assertFalse(runDirectory.exists())
            if (captured.isNotEmpty() && captured.none(::same)) RootfsInstaller.removeOwned(markers)
        }
    }
}
