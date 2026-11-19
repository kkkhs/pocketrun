package io.github.kkkhs.pocketrun

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real JVM children + procfs. Android Os/SELinux and PRoot EXITKILL remain device acceptance work. */
class LinuxProcessControlTest {
    private val python = File("/usr/bin/python3").canonicalFile

    private class ProcAccess : LinuxProcessControl.Access {
        override val parentPid = File("/proc/self/stat").readText().substringBefore(" (").toInt()
        override val uid = Files.getAttribute(File("/proc/self").toPath(), "unix:uid") as Int
        val killed = ArrayList<Int>()
        @Volatile var ownedProcess: Process? = null
        var denyProc = false
        var afterProcessRead: (() -> Unit)? = null
        override fun validateDirectory(directory: File) {
            require(directory.absoluteFile == directory.canonicalFile)
            val attrs = Files.readAttributes(directory.toPath(), "unix:uid,mode", NOFOLLOW_LINKS)
            require(attrs["uid"] == uid && (attrs["mode"] as Int and 511) == 448)
        }
        override fun readRecord(file: File): ByteArray? {
            val path = file.toPath()
            try {
                val attrs = Files.readAttributes(path, "unix:uid,mode,nlink,size,isRegularFile", NOFOLLOW_LINKS)
                require(attrs["uid"] == uid && (attrs["mode"] as Int and 511) == 384 && attrs["nlink"] == 1 &&
                    attrs["isRegularFile"] == true && attrs["size"] as Long in 0..4096)
                FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.allocate(4097)
                    while (buffer.hasRemaining() && channel.read(buffer) > 0) { /* bounded */ }
                    require(buffer.position() <= 4096)
                    return buffer.array().copyOf(buffer.position())
                }
            } catch (_: NoSuchFileException) { return null }
        }
        override fun readProcess(pid: Int): LinuxProcessControl.Identity? {
            if (denyProc) throw IOException("proc permission unknown")
            try {
                val path = File("/proc/$pid/stat").toPath()
                val text = Files.readString(path)
                val owner = Files.getAttribute(File("/proc/$pid").toPath(), "unix:uid", NOFOLLOW_LINKS) as Int
                val identity = LinuxProcessControl.parseStat(text, owner)
                afterProcessRead?.let { action -> afterProcessRead = null; action() }
                return identity
            } catch (_: NoSuchFileException) { return null }
        }
        override fun validateExecutable(pid: Int, expected: File) {
            require(expected.absoluteFile == expected.canonicalFile && !Files.isWritable(expected.toPath()))
            require(Files.readSymbolicLink(File("/proc/$pid/exe").toPath()).toString() == expected.path)
        }
        override fun validateRun(pid: Int, ownedDirectory: File) {
            val arguments = String(Files.readAllBytes(File("/proc/$pid/cmdline").toPath()), Charsets.UTF_8).split(0.toChar())
            require(arguments.contains(File(ownedDirectory, "tracer.identity").path)) { "Wrong owned run command identity." }
        }
        override fun kill(pid: Int) {
            killed += pid
            // Test-only backend signals the actual supplied JVM child, not an arbitrary PID or an Android stub.
            checkNotNull(ownedProcess).destroyForcibly()
        }
    }

    private fun directory() = Files.createTempDirectory("pocketrun-owned-tracer",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toFile()
    private fun command(record: File, mode: String = "hold", delay: Int = 0) = listOf(python.path, "-I", "-c", SCRIPT,
        record.path, mode, delay.toString())
    private fun child(record: File, mode: String = "hold", delay: Int = 0): Process =
        ProcessBuilder(command(record, mode, delay)).start()
    private fun dispose(process: Process) {
        if (process.isAlive) process.destroyForcibly()
        assertTrue(process.waitFor(2, TimeUnit.SECONDS))
        process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
    }
    private fun awaitRecord(record: File) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while ((!record.isFile || record.length() == 0L) && System.nanoTime() < until) Thread.sleep(5)
        assertTrue(record.isFile && record.length() > 0)
    }

    @Test fun sigtermIgnoringOwnedChildIsKilledAndReapedExactlyOnce() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val process = child(control.tracerRecord).also { access.ownedProcess = it }
        try {
            control.registerOwned(process)
            val pid = LinuxProcessControl.parseIdentity(control.tracerRecord.readBytes()).pid
            process.destroy() // the real child explicitly ignores SIGTERM.
            Thread.sleep(30)
            assertTrue(process.isAlive)
            control.terminateOwned(process); control.terminateOwned(process)
            assertFalse(process.isAlive)
            assertEquals(listOf(pid), access.killed)
        } finally { dispose(process); dir.deleteRecursively() }
    }

    @Test fun timeoutAndOutputLimitUseOwnedTerminationAndPermitNextRunOnlyAfterProof() {
        for ((mode, expected) in listOf("hold" to "timeout", "output" to "output_limit")) {
            val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
            val pipe = CommandPipeline(); val run = pipe.reserve()
            try {
                val result = pipe.execute(run, dir, listOf(command(control.tracerRecord, mode)), 150,
                    onStarted = control::registerOwned, terminateOwned = { process -> access.ownedProcess = process; control.terminateOwned(process) })
                assertEquals(expected, result.status)
                assertTrue(result.cleanupFailure.orEmpty(), result.cleanupProven)
                assertTrue(run.processes.none { it.isAlive })
                assertTrue(result.exitCodes.none { it == -1 })
                assertEquals(1, access.killed.size)
                assertTrue(result.stdout.toByteArray().size <= CommandPipeline.MAX_OUTPUT_BYTES)
                val next = pipe.execute(pipe.reserve(), dir, listOf(listOf("/usr/bin/id", "-u")))
                assertEquals("completed", next.status)
            } finally { run.processes.forEach(::dispose); pipe.abandon(run); pipe.close(); dir.deleteRecursively() }
        }
    }

    @Test fun cancellationDuringRegistrationWaitsForOwnedRecordWithoutBlindKill() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val pipe = CommandPipeline(); val run = pipe.reserve(); val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<CommandPipeline.Result> {
                pipe.execute(run, dir, listOf(command(control.tracerRecord, delay = 100)),
                    onStarted = control::registerOwned, terminateOwned = { process -> access.ownedProcess = process; control.terminateOwned(process) })
            }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (pipe.runningProcesses().isEmpty() && System.nanoTime() < until) Thread.sleep(5)
            assertTrue(pipe.runningProcesses().isNotEmpty())
            assertTrue(pipe.cancel()); assertTrue(pipe.cancel())
            val result = future.get(3, TimeUnit.SECONDS)
            assertEquals("cancelled", result.status)
            assertTrue(result.cleanupFailure.orEmpty(), result.cleanupProven)
            assertEquals(1, access.killed.size)
        } finally { run.processes.forEach(::dispose); worker.shutdownNow(); pipe.abandon(run); pipe.close(); dir.deleteRecursively() }
    }

    @Test fun cachedIdentityCannotBeRetargetedByRecordRewrite() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val process = child(control.tracerRecord).also { access.ownedProcess = it }
        val otherDir = directory(); val other = child(File(otherDir, "tracer.identity"))
        try {
            control.registerOwned(process); awaitRecord(File(otherDir, "tracer.identity"))
            val pid = LinuxProcessControl.parseIdentity(control.tracerRecord.readBytes()).pid
            control.tracerRecord.writeBytes(File(otherDir, "tracer.identity").readBytes())
            control.terminateOwned(process)
            assertFalse(process.isAlive); assertTrue(other.isAlive)
            assertEquals(listOf(pid), access.killed)
        } finally { dispose(process); dispose(other); dir.deleteRecursively(); otherDir.deleteRecursively() }
    }

    @Test fun ownerTicksExecutableAndProcUnknownNeverAuthorizeSignal() {
        for (failure in listOf("parent", "uid", "ticks", "exe", "proc")) {
            val dir = directory(); val access = ProcAccess()
            val control = LinuxProcessControl(dir, if (failure == "exe") File("/usr/bin/true").canonicalFile else python, access)
            val record = control.tracerRecord; val process = child(record)
            try {
                awaitRecord(record)
                val original = LinuxProcessControl.parseIdentity(record.readBytes())
                val bad = when (failure) {
                    "parent" -> original.copy(ppid = original.ppid + 1)
                    "uid" -> original.copy(uid = original.uid + 1)
                    "ticks" -> original.copy(startTicks = original.startTicks + 1)
                    else -> original
                }
                record.writeText(json(bad))
                access.denyProc = failure == "proc"
                assertThrows(IOException::class.java) { control.terminateOwned(process) }
                assertTrue(process.isAlive); assertTrue(access.killed.isEmpty())
            } finally { dispose(process); dir.deleteRecursively() }
        }
    }

    @Test fun missingRecordRetainsBusySlotEvenAfterJavaChildIsReaped() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val pipe = CommandPipeline(); val run = pipe.reserve()
        try {
            val result = pipe.execute(run, dir, listOf(listOf("/usr/bin/true")),
                onStarted = control::registerOwned, terminateOwned = { process -> access.ownedProcess = process; control.terminateOwned(process) })
            assertFalse(result.cleanupProven)
            assertTrue(result.cleanupFailure.orEmpty().contains("registration"))
            assertTrue(run.processes.none { it.isAlive })
            assertThrows(IllegalStateException::class.java) { pipe.reserve() }
            pipe.abandon(run)
            assertThrows(IllegalStateException::class.java) { pipe.reserve() }
            assertTrue(access.killed.isEmpty())
        } finally { run.processes.forEach(::dispose); pipe.close(); dir.deleteRecursively() }
    }

    @Test fun registrationDeadlineFailureLeavesRealLiveChildOwnedAndBusyNotFakeGone() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val pipe = CommandPipeline(); val run = pipe.reserve()
        try {
            val started = System.nanoTime()
            val result = pipe.execute(run, dir, listOf(command(control.tracerRecord, delay = 4000)),
                onStarted = control::registerOwned, terminateOwned = { process -> access.ownedProcess = process; control.terminateOwned(process) })
            assertFalse(result.cleanupProven)
            assertTrue(run.processes.single().isAlive)
            assertEquals(listOf(-1), result.exitCodes)
            assertTrue(access.killed.isEmpty())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500)
            assertThrows(IllegalStateException::class.java) { pipe.reserve() }
        } finally { run.processes.forEach(::dispose); pipe.close(); dir.deleteRecursively() }
    }

    @Test fun naturallyExitedRegisteredChildIsReapedWithoutSignal() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val process = child(control.tracerRecord, "exit")
        try {
            assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            control.terminateOwned(process); control.terminateOwned(process)
            assertTrue(access.killed.isEmpty())
        } finally { dispose(process); dir.deleteRecursively() }
    }

    @Test fun symlinkOversizeAndNonprivateRecordsCannotAuthorizeSignal() {
        for (kind in listOf("symlink", "oversize", "mode", "directory")) {
            val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
            val process = child(control.tracerRecord).also { access.ownedProcess = it }
            try {
                awaitRecord(control.tracerRecord)
                when (kind) {
                    "symlink" -> {
                        val copy = File(dir, "copy"); control.tracerRecord.copyTo(copy)
                        control.tracerRecord.delete(); Files.createSymbolicLink(control.tracerRecord.toPath(), copy.toPath())
                    }
                    "oversize" -> control.tracerRecord.writeText("x".repeat(4097))
                    "mode" -> Files.setPosixFilePermissions(control.tracerRecord.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
                    else -> Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
                }
                assertThrows(IOException::class.java) { control.terminateOwned(process) }
                assertTrue(process.isAlive); assertTrue(access.killed.isEmpty())
            } finally { dispose(process); dir.deleteRecursively() }
        }
    }

    @Test fun completeRecordIsRequiredNotMerelyFileExistence() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val process = child(control.tracerRecord, "partial").also { access.ownedProcess = it }
        try {
            control.registerOwned(process); control.terminateOwned(process)
            assertFalse(process.isAlive); assertEquals(1, access.killed.size)
        } finally { dispose(process); dir.deleteRecursively() }
    }

    @Test fun siblingRecordBeforeFirstCacheDoesNotAuthorizeKillingAnotherRun() {
        val dir = directory(); val otherDir = directory(); val access = ProcAccess()
        val control = LinuxProcessControl(dir, python, access)
        val process = child(control.tracerRecord).also { access.ownedProcess = it }; val otherRecord = File(otherDir, "tracer.identity")
        val other = child(otherRecord)
        try {
            awaitRecord(control.tracerRecord); awaitRecord(otherRecord)
            control.tracerRecord.writeBytes(otherRecord.readBytes())
            assertThrows(IOException::class.java) { control.terminateOwned(process) }
            assertTrue(process.isAlive); assertTrue(other.isAlive); assertTrue(access.killed.isEmpty())
        } finally { dispose(process); dispose(other); dir.deleteRecursively(); otherDir.deleteRecursively() }
    }

    @Test fun childExitingDuringKernelRegistrationIsReapedNotMarkedFailed() {
        val dir = directory(); val access = ProcAccess(); val control = LinuxProcessControl(dir, python, access)
        val process = child(control.tracerRecord, "controlled-exit")
        try {
            access.afterProcessRead = {
                process.outputStream.write(1); process.outputStream.flush()
                assertTrue(process.waitFor(2, TimeUnit.SECONDS))
            }
            control.registerOwned(process); control.terminateOwned(process)
            assertEquals(0, process.exitValue()); assertTrue(access.killed.isEmpty())
        } finally { dispose(process); dir.deleteRecursively() }
    }

    @Test fun preexistingRecordCannotBeAdoptedAsNewRun() {
        val dir = directory(); val record = File(dir, "tracer.identity")
        try {
            record.writeText("{}"); Files.setPosixFilePermissions(record.toPath(), PosixFilePermissions.fromString("rw-------"))
            assertThrows(IllegalArgumentException::class.java) { LinuxProcessControl(dir, python, ProcAccess()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun actualIOExecutorIsRecheckedBeforeAnUnprovenSlotCanBeReleased() {
        val dir = directory(); val pipe = CommandPipeline(); val run = pipe.reserve()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val result = pipe.execute(run, dir, listOf(listOf("/usr/bin/true")), onStarted = {
                checkNotNull(run.ioExecutor).submit {
                    started.countDown()
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { /* test-owned task stays until release */ }
                    }
                }
                assertTrue(started.await(1, TimeUnit.SECONDS))
            })
            assertFalse(result.cleanupProven)
            assertEquals("failed", result.status)
            assertTrue(result.cleanupFailure.orEmpty().contains("IO threads"))
            assertThrows(IllegalStateException::class.java) { pipe.reserve() }
            assertThrows(IllegalStateException::class.java) { pipe.execute(run, dir, listOf(listOf("/usr/bin/true"))) }
            release.countDown(); pipe.abandon(run)
            assertTrue(run.ioCleanupProven)
            assertEquals(null, run.cleanupFailure.get())
            assertEquals("completed", pipe.execute(pipe.reserve(), dir, listOf(listOf("/usr/bin/true"))).status)
        } finally { release.countDown(); run.processes.forEach(::dispose); pipe.abandon(run); pipe.close(); dir.deleteRecursively() }
    }

    @Test fun aSuccessfulFinalCleanupProofClearsEarlierTransientFailure() {
        val dir = directory(); val pipe = CommandPipeline(); val run = pipe.reserve(); val calls = AtomicInteger()
        try {
            val result = pipe.execute(run, dir, listOf(command(File(dir, "tracer.identity"), "output")), 150,
                terminateOwned = { process ->
                    if (calls.incrementAndGet() == 1) throw IOException("test transient termination error")
                    if (process.isAlive) process.destroyForcibly()
                    assertTrue(process.waitFor(1, TimeUnit.SECONDS))
                })
            assertTrue(calls.get() >= 2)
            assertTrue(result.cleanupProven)
            assertEquals(null, result.cleanupFailure)
            assertEquals("output_limit", result.status)
            assertTrue(run.processes.none { it.isAlive })
        } finally { run.processes.forEach(::dispose); pipe.abandon(run); pipe.close(); dir.deleteRecursively() }
    }

    @Test fun registrationRejectsDuplicateUnknownAndTruncatedFields() {
        val valid = "{\"schemaVersion\":1,\"pid\":20,\"ppid\":10,\"uid\":42,\"startTicks\":1234}"
        assertEquals(LinuxProcessControl.Identity(20, 10, 42, 1234), LinuxProcessControl.parseIdentity(valid.toByteArray()))
        for (bad in listOf(valid.dropLast(1), valid.replace("1234", "-1"), valid.replace("1234", "1.5"),
            valid.replace("schemaVersion\":1", "schemaVersion\":2"), valid.dropLast(1) + ",\"pid\":21}",
            valid.replace("\"uid\"", "\"other\""))) {
            assertThrows(Exception::class.java) { LinuxProcessControl.parseIdentity(bad.toByteArray()) }
        }
    }

    @Test fun procStatCommWithSpacesAndParenthesesUsesField22() {
        val text = "20 (tracer ) tricky name) S 10 " + List(17) { "0" }.joinToString(" ") + " 1234 0 0"
        assertEquals(LinuxProcessControl.Identity(20, 10, 42, 1234), LinuxProcessControl.parseStat(text, 42))
    }

    private fun json(i: LinuxProcessControl.Identity) =
        "{\"schemaVersion\":1,\"pid\":${i.pid},\"ppid\":${i.ppid},\"uid\":${i.uid},\"startTicks\":${i.startTicks}}"
    companion object {
        private val SCRIPT = """
            import json, os, signal, sys, time
            path, mode, delay = sys.argv[1:]
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            time.sleep(int(delay)/1000)
            stat = open('/proc/self/stat').read().rsplit(')', 1)[1].split()
            identity = dict(schemaVersion=1, pid=os.getpid(), ppid=os.getppid(), uid=os.getuid(), startTicks=int(stat[19]))
            data = json.dumps(identity).encode('ascii')
            fd = os.open(path, os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW, 0o600)
            if mode == 'partial':
                os.write(fd, data[:10]); time.sleep(.1); os.write(fd, data[10:])
            else: os.write(fd, data)
            os.fsync(fd); os.close(fd)
            if mode == 'exit': sys.exit(0)
            if mode == 'controlled-exit': os.read(0, 1); sys.exit(0)
            if mode == 'output':
                while True: os.write(1, b'x'*4096)
            while True: time.sleep(1)
        """.trimIndent()
    }
}
