package io.github.kkkhs.pocketrun

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit external-host protocol. App death means this ordinary App-UID PRoot/PTY backend, not UID-2000 Binder. */
@RunWith(AndroidJUnit4::class)
class Chapter8HostDeathTest {
    @Test fun armAppDeath() {
        val root = newRoot()
        val tree = PtyTree(root)
        File(tree.workspace, "items.csv").writeText(SAVED_DATA)
        tree.start(Mode.HOLD)
        val descendants = tree.capture()
        val app = Chapter7TestSupport.identity("app", Process.myPid())
        publish(root, JSONObject().put("phase", "pty-app-death-ready").put("killRole", "app")
            .put("armedNonce", token()).put("appPid", app.pid).put("appStartTicks", app.ticks).put("appUid", app.uid)
            .put("sourceApk", Chapter7TestSupport.context.applicationInfo.sourceDir)
            .put("sourceApkSha256", sha256(File(Chapter7TestSupport.context.applicationInfo.sourceDir)))
            .put("testSourceApk", InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
            .put("testSourceApkSha256", sha256(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)))
            .put("identities", JSONArray((listOf(app) + descendants).map { it.json() }))
            .put("runDirectory", tree.runDirectory.path).put("workspace", tree.workspace.path))
        if (!CountDownLatch(1).await(30, TimeUnit.SECONDS)) {
            tree.close()
            fail("Host did not SIGKILL the armed App")
        }
    }

    @Test fun armRootTracerDeath() {
        val root = newRoot()
        PtyTree(root).use { tree ->
            tree.start(Mode.HOLD)
            val descendants = tree.capture()
            val app = Chapter7TestSupport.identity("app", Process.myPid())
            val tracer = descendants.single { it.role == "tracer" }
            publish(root, JSONObject().put("phase", "pty-tracer-death-ready").put("killRole", "tracer")
                .put("armedNonce", token()).put("appPid", app.pid).put("appStartTicks", app.ticks).put("appUid", app.uid)
                .put("sourceApk", Chapter7TestSupport.context.applicationInfo.sourceDir)
                .put("sourceApkSha256", sha256(File(Chapter7TestSupport.context.applicationInfo.sourceDir)))
                .put("testSourceApk", InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)
                .put("testSourceApkSha256", sha256(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)))
                .put("identities", JSONArray((listOf(app) + descendants).map { it.json() }))
                .put("target", tracer.json()).put("runDirectory", tree.runDirectory.path))
            val exit = tree.exit()
            assertEquals(PtyExit.Kind.SIGNALED, exit.kind)
            assertEquals(9, exit.code)
            tree.proveAndClose(descendants)
            publish(root, JSONObject().put("phase", "pty-tracer-death-verified").put("killRole", "tracer")
                .put("exitKind", exit.kind.name).put("exitCode", exit.code).put("rootReaped", exit.rootReaped))
        }
    }

    @Test fun recoverAfterAppDeathOnlyAfterEveryOldOwnedIdentityIsGone() {
        val root = existingRoot()
        val state = JSONObject(File(root, "protocol.json").readText())
        assertEquals("pty-app-death-ready", state.getString("phase"))
        assertEquals("app", state.getString("killRole"))
        assertEquals(token(), state.getString("armedNonce"))
        assertEquals(Process.myUid(), state.getInt("appUid"))
        assertEquals(sha256(File(Chapter7TestSupport.context.applicationInfo.sourceDir)), state.getString("sourceApkSha256"))
        assertEquals(sha256(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)),
            state.getString("testSourceApkSha256"))
        val encoded = state.getJSONArray("identities")
        val old = (0 until encoded.length()).map { decode(encoded.getJSONObject(it)) }
        assertTrue(old.any { it.role == "app" && it.pid == state.getInt("appPid") && it.ticks == state.getString("appStartTicks") })
        // This is before any recovery instrumentation creates a new PTY tree.
        Chapter7TestSupport.assertGone(old)
        val runDirectory = File(state.getString("runDirectory"))
        require(runDirectory.parentFile == File(root, "linux/runs") && runDirectory.name.startsWith("pocketrun-run-"))
        LinuxDirectoryLease.recover(runDirectory.parentFile!!, LinuxDirectoryLease.Kind.RUN,
            LinuxRecoveryProbe::isUnusedByAppProcesses)
        assertFalse("Old registered RUN directory was not proven reclaimable", runDirectory.exists())
        assertEquals(SAVED_DATA, File(state.getString("workspace"), "items.csv").readText())
        PtyTree(root).use { tree ->
            tree.start(Mode.NORMAL)
            val fresh = tree.capture()
            tree.openGate()
            assertEquals(0, tree.exit().code)
            tree.proveAndClose(fresh)
        }
        publish(root, JSONObject().put("phase", "pty-app-death-recovered").put("newAppPid", Process.myPid())
            .put("oldAppPid", state.getInt("appPid")).put("oldAppStartTicks", state.getString("appStartTicks")))
    }

    private fun token(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("chapter8_host_token"))
        .also { require(it.matches(Regex("[a-f0-9]{16}"))) { "Use the explicit Chapter8 host driver protocol" } }
    private fun root() = File(Chapter7TestSupport.context.filesDir, "chapter8-host-${token()}")
    private fun newRoot(): File {
        val created = root()
        check(created.mkdir()) { "Never reuse a previous host attempt" }
        return created.canonicalFile.also(RootfsTar::privateDirectory)
    }
    private fun existingRoot(): File {
        val existing = root()
        check(existing.isDirectory)
        require(!Files.isSymbolicLink(existing.toPath()))
        return existing.canonicalFile
    }
    private fun publish(root: File, state: JSONObject) {
        val next = File(root, "protocol.next")
        next.writeText(state.put("token", token()).put("armedNonce", token()).toString())
        check(next.renameTo(File(root, "protocol.json")))
    }
    private fun decode(json: JSONObject) = Chapter7TestSupport.Identity(json.getString("role"), json.getInt("pid"),
        json.getString("startTicks"), json.getInt("uid"), json.getInt("ppid"), json.getInt("sid"),
        json.getInt("tracerPid"), json.getInt("tgid"), json.getString("cmdline"))
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= 256L * 1024 * 1024) { "APK exceeds bounded host protocol hash input" }
            digest.update(buffer, 0, count)
        }
        RootfsInstaller.hex(digest.digest())
    }
    companion object { private const val SAVED_DATA = "retained,pty\nitem,8\n" }
}
