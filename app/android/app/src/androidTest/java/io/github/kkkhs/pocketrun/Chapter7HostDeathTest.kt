package io.github.kkkhs.pocketrun

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** External host protocols. Explicit class#method only; excluded from ordinary connected-test discovery. */
@RunWith(AndroidJUnit4::class)
class Chapter7HostDeathTest {
    @Test fun armAppDeath() = armTree("app", false)
    @Test fun armFallbackAppDeath() = armTree("app", true)
    @Test fun armTracerDeath() = armTree("tracer", false)
    @Test fun armFallbackTracerDeath() = armTree("tracer", true)
    @Test fun armGuestDeath() = armTree("guest", false)
    @Test fun armFallbackGuestDeath() = armTree("guest", true)

    private fun armTree(target: String, fallback: Boolean) {
        val root = newRoot()
        Chapter7TestSupport.Tree(root, fallback).use { tree ->
            File(tree.workspace, "items.csv").writeText(SAVED_DATA)
            tree.start()
            val identities = tree.capture()
            val app = Chapter7TestSupport.identity("app", Process.myPid())
            publish(root, JSONObject().put("phase", "tree-death-ready").put("killRole", target)
                .put("fallback", fallback).put("appPid", app.pid).put("appUid", app.uid)
                .put("identities", JSONArray((listOf(app) + identities).map { it.json() }))
                .put("runtimeGeneration", tree.installer.runtime.generation)
                .put("nativeDirectory", tree.installer.nativeDirectory.path)
                .put("runDirectory", tree.runDirectory.path).put("markers", tree.markers.path)
                .put("tracerRecord", tree.tracerRecord.path))
            val value = tree.result()
            val aliveAtReturn = tree.run.processes.any { it.isAlive }
            assertNotEquals("Host did not kill App while PRoot was alive", "app", target)
            Chapter7TestSupport.result("host-$target-death", value)
            if (aliveAtReturn || value.exitCodes.any { it == -1 } || value.status != "failed")
                Chapter7TestSupport.procEvidence("host-$target-unreaped", identities)
            assertEquals("failed", value.status)
            assertFalse("Host SIGKILL returned before root reaping", aliveAtReturn)
            assertEquals(listOf(137), value.exitCodes)
            assertTrue(tree.run.processes.none { it.isAlive })
            Chapter7TestSupport.assertCleanup(value, tree.run)
            Chapter7TestSupport.assertGone(identities)
            assertEquals(SAVED_DATA, File(tree.workspace, "items.csv").readText())
            tree.rerun()
            publish(root, JSONObject().put("phase", "tree-death-verified").put("killRole", target)
                .put("fallback", fallback).put("exits", JSONArray(value.exitCodes)))
        }
    }

    @Test fun recoverAfterAppDeath() {
        val root = existingRoot()
        val state = JSONObject(root.resolve("protocol.json").readText())
        assertEquals("tree-death-ready", state.getString("phase"))
        assertEquals("app", state.getString("killRole"))
        val entries = state.getJSONArray("identities")
        val old = (0 until entries.length()).map { decode(entries.getJSONObject(it)) }
        // A live/unknown old tracer, task or detached guest makes recovery fail, not a cleanup shortcut.
        Chapter7TestSupport.assertGone(old)
        val runDirectory = File(state.getString("runDirectory"))
        val markers = File(state.getString("markers"))
        require(runDirectory.parentFile == File(root, "linux/runs") && runDirectory.name.startsWith("pocketrun-run-"))
        require(markers.parentFile == File(root, "workspaces/demo") && markers.name.startsWith(".chapter7-tree-"))
        Chapter7TestSupport.session(root).use { session ->
            val info = session.prepare(session.reservePrepare("host-recovery-prepare"))
            assertEquals(state.getString("runtimeGeneration"), info.installationGeneration)
            assertFalse(info.installedNow)
            assertFalse(info.sampleCreated)
            assertFalse("Production recovery must reclaim the proven-dead RUN lease", runDirectory.exists())
            assertTrue(Chapter7TestSupport.payloadNames(File(root, "linux/runs")).isEmpty())
            assertEquals(SAVED_DATA, File(info.workspaceDirectory, "items.csv").readText())
            val result = session.runDemo(session.reserve("host-recovery-run"))
            assertEquals("completed", result.status)
            assertEquals("done", result.cleanupStatus)
            assertEquals(Process.myUid(), result.guestUid)
            assertEquals(2, result.lineCount)
            assertEquals(listOf(0, 0, 0, 0, 0), result.stageExitCodes)
        }
        // Only test-owned PID marker files are removed here; the RUN directory was reclaimed by production.
        RootfsInstaller.removeOwned(markers)
        publish(root, JSONObject().put("phase", "app-death-recovered").put("newAppPid", Process.myPid()))
    }

    @Test fun armInstallDeath() {
        val root = newRoot()
        WorkspaceSession(root).use { File(it.prepare().sampleFile).writeText(SAVED_DATA) }
        val foreign = File(root, "linux/stage/foreign-attempt").apply { mkdirs() }
        foreign.resolve("keep").writeText("not this install attempt")
        val runtime = Chapter7TestSupport.runtime()
        val installer = RootfsInstaller(root, File(Chapter7TestSupport.context.applicationInfo.nativeLibraryDir).canonicalFile,
            runtime, Chapter7TestSupport.context.assets::open, publish = { stage, target ->
                assertTrue(stage.resolve("installed.manifest").isFile)
                assertEquals("3.22.8", stage.resolve("data/etc/alpine-release").readText().trim())
                assertFalse(target.exists())
                val app = Chapter7TestSupport.identity("app", Process.myPid())
                publish(root, JSONObject().put("phase", "stage-death-ready").put("killRole", "app")
                    .put("appPid", app.pid).put("appUid", app.uid).put("identities", JSONArray(listOf(app.json())))
                    .put("stagePath", stage.path).put("runtimeGeneration", runtime.generation))
                Thread.sleep(15000)
                throw AssertionError("Host did not SIGKILL at real fsynced installation publication boundary")
            })
        Chapter7TestSupport.session(root, installer).use { it.prepare(it.reservePrepare("host-stage-arm")) }
    }

    @Test fun recoverAfterInstallDeath() {
        val root = existingRoot()
        val state = JSONObject(root.resolve("protocol.json").readText())
        assertEquals("stage-death-ready", state.getString("phase"))
        val entries = state.getJSONArray("identities")
        Chapter7TestSupport.assertGone((0 until entries.length()).map { decode(entries.getJSONObject(it)) })
        val stage = File(state.getString("stagePath"))
        require(stage.parentFile == File(root, "linux/stage"))
        assertTrue("SIGKILL left the real fully fsynced unpublished stage", stage.resolve("installed.manifest").isFile)
        assertFalse(File(root, "linux/rootfs-${state.getString("runtimeGeneration")}").exists())
        Chapter7TestSupport.session(root).use { session ->
            val info = session.prepare(session.reservePrepare("host-stage-recovery"))
            assertTrue(info.installedNow)
            assertEquals(state.getString("runtimeGeneration"), info.installationGeneration)
            assertEquals(SAVED_DATA, File(info.workspaceDirectory, "items.csv").readText())
            assertFalse("Production installer must reclaim its proven-dead owned stage", stage.exists())
            assertEquals("not this install attempt", File(root, "linux/stage/foreign-attempt/keep").readText())
            val value = session.runDemo(session.reserve("stage-recovery-rerun"))
            assertEquals("completed", value.status)
            assertEquals("done", value.cleanupStatus)
        }
        publish(root, JSONObject().put("phase", "stage-death-recovered").put("newAppPid", Process.myPid()))
    }

    private fun token(): String = checkNotNull(InstrumentationRegistry.getArguments().getString("chapter7_host_token"))
        .also { require(it.matches(Regex("[a-f0-9]{16}"))) { "Use the explicit Chapter7 host driver protocol" } }
    private fun root() = File(Chapter7TestSupport.context.filesDir, "chapter7-host-${token()}")
    private fun newRoot(): File {
        val created = root()
        check(created.mkdir()) { "Never reuse a previous host attempt" }
        return created.canonicalFile.also(RootfsTar::privateDirectory)
    }
    private fun existingRoot(): File {
        val existing = root()
        check(existing.isDirectory)
        require(!Files.isSymbolicLink(existing.toPath())) { "Host fixture root cannot be a substituted symlink" }
        // Only the known platform-created root gets alias normalization, never linux/stage/workspace children.
        return existing.canonicalFile
    }
    private fun publish(root: File, state: JSONObject) {
        val next = root.resolve("protocol.next")
        next.writeText(state.put("token", token()).toString())
        check(next.renameTo(root.resolve("protocol.json")))
    }
    private fun decode(json: JSONObject) = Chapter7TestSupport.Identity(json.getString("role"), json.getInt("pid"),
        json.getString("startTicks"), json.getInt("uid"), json.getInt("ppid"), json.getInt("sid"),
        json.getInt("tracerPid"), json.getInt("tgid"), json.getString("cmdline"))
    companion object { private const val SAVED_DATA = "retained,user\nitem,7\n" }
}
