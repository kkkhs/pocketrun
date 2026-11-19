package io.github.kkkhs.pocketrun

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Must be selected one method at a time by the host SIGKILL protocol, never run without its host. */
@RunWith(AndroidJUnit4::class)
class Chapter6HostDeathTest {
    @Test fun armAppDeath() = armPipeline(killParent = true)
    @Test fun armChildDeath() = armPipeline(killParent = false)

    private fun armPipeline(killParent: Boolean) {
        val root = newRoot()
        WorkspaceSession(root).use { session -> session.prepare() }
        val sample = root.resolve("workspaces/demo/items.csv")
        sample.writeText(SAVED_DATA)
        val worker = Executors.newSingleThreadExecutor()
        try {
            CommandPipeline().use { pipe ->
                val run = pipe.reserve()
                val future = worker.submit<CommandPipeline.Result> {
                    pipe.execute(run, root, listOf(Chapter6WorkspaceIntegrationTest.sleepWithPid()))
                }
                val child = Chapter6WorkspaceIntegrationTest.waitForPid(root)
                publish(root, JSONObject()
                    .put("phase", if (killParent) "app-death-ready" else "child-death-ready")
                    .put("appPid", Process.myPid()).put("appUid", Process.myUid())
                    .put("childPid", child).put("childStartTicks", startTicks(child))
                    .put("appStartTicks", startTicks(Process.myPid())))
                val result = future.get(8, TimeUnit.SECONDS)
                assertFalse("Host did not SIGKILL the App before the pipeline deadline", killParent)
                assertEquals("failed", result.status)
                assertTrue(result.exitCodes.single() != 0)
                assertTrue(run.processes.none { it.isAlive })
                Chapter6WorkspaceIntegrationTest.await("host-killed child was not reaped") {
                    !File("/proc/$child").exists()
                }
                assertEquals(SAVED_DATA, sample.readText())
                val rerun = pipe.execute(pipe.reserve(), root,
                    listOf(Chapter6WorkspaceIntegrationTest.toybox("id", "-u")))
                assertEquals("completed", rerun.status)
                assertEquals(Process.myUid().toString(), rerun.stdout.trim())
                publish(root, JSONObject().put("phase", "child-death-verified").put("exitCode", result.exitCodes.single()))
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun recoverAfterAppDeath() {
        val root = existingRoot()
        val state = JSONObject(root.resolve("protocol.json").readText())
        assertEquals("app-death-ready", state.getString("phase"))
        val oldApp = state.getInt("appPid")
        val child = state.getInt("childPid")
        assertFalse("Killed App is still live", sameProcess(oldApp, state.getString("appStartTicks")))
        assertFalse("SIGKILL left an orphan child PID $child (force-stop is not a substitute)",
            sameProcess(child, state.getString("childStartTicks")))
        WorkspaceSession(root).use { session ->
            val info = session.prepare()
            assertFalse(info.sampleCreated)
            assertEquals(SAVED_DATA, File(info.sampleFile).readText())
            val result = session.runDemo(session.reserve())
            assertEquals("completed", result.status)
            assertEquals("2", result.stdout.trim())
            assertEquals(listOf(0, 0), result.exitCodes)
        }
        publish(root, JSONObject().put("phase", "app-death-recovered").put("newAppPid", Process.myPid()))
    }

    @Test fun armPublicationDeath() {
        val root = newRoot()
        WorkspaceSession(root, beforeSamplePublish = {
            val directory = root.resolve("workspaces/demo")
            val temporary = checkNotNull(directory.listFiles()).single {
                it.name.startsWith(".pocketrun-sample-") && it.name.endsWith(".tmp")
            }
            assertEquals(WorkspaceSession.DEMO_CSV, temporary.readText())
            assertFalse(directory.resolve("items.csv").exists())
            publish(root, JSONObject().put("phase", "publication-death-ready")
                .put("appPid", Process.myPid()).put("appUid", Process.myUid())
                .put("appStartTicks", startTicks(Process.myPid())).put("temporaryName", temporary.name))
            Thread.sleep(15000) // Real prepare is paused after fsync, before the no-replace link.
            throw AssertionError("Host did not SIGKILL the App at the actual publication boundary")
        }).use { session -> session.prepare() }
    }

    @Test fun recoverAfterPublicationDeath() {
        val root = existingRoot()
        val state = JSONObject(root.resolve("protocol.json").readText())
        assertEquals("publication-death-ready", state.getString("phase"))
        assertFalse(sameProcess(state.getInt("appPid"), state.getString("appStartTicks")))
        val directory = root.resolve("workspaces/demo")
        val staged = directory.resolve(state.getString("temporaryName"))
        assertTrue("SIGKILL must leave the fsynced unpublished real staging file", staged.isFile)
        assertEquals(WorkspaceSession.DEMO_CSV, staged.readText())
        assertFalse("First sample was not linked before hard death", directory.resolve("items.csv").exists())
        WorkspaceSession(root).use { session ->
            val info = session.prepare()
            assertTrue(info.sampleCreated)
            assertEquals(WorkspaceSession.DEMO_CSV, File(info.sampleFile).readText())
            assertFalse(staged.exists())
            assertTrue(checkNotNull(directory.listFiles()).none { it.name.startsWith(".pocketrun-sample-") })
            val result = session.runDemo(session.reserve())
            assertEquals("completed", result.status)
            assertEquals("3", result.stdout.trim())
            assertEquals(listOf(0, 0), result.exitCodes)
        }
        publish(root, JSONObject().put("phase", "publication-death-recovered").put("newAppPid", Process.myPid()))
    }

    private fun token(): String {
        val token = InstrumentationRegistry.getArguments().getString("chapter6_host_token")
        require(token != null && token.matches(Regex("[a-f0-9]{16}"))) {
            "Select this test via the Chapter6 host driver with a unique chapter6_host_token."
        }
        return token
    }

    private fun existingRoot() = File(Chapter6WorkspaceIntegrationTest.contextFiles(), "chapter6-host-${token()}")
        .also { check(it.isDirectory) { "Missing host protocol directory" } }

    private fun newRoot() = File(Chapter6WorkspaceIntegrationTest.contextFiles(), "chapter6-host-${token()}")
        .also { check(it.mkdir()) { "Refusing to reuse a previous host attempt" } }

    private fun publish(root: File, state: JSONObject) {
        val temporary = root.resolve("protocol.next")
        temporary.writeText(state.put("token", token()).toString())
        check(temporary.renameTo(root.resolve("protocol.json")))
    }

    companion object {
        private const val SAVED_DATA = "existing,user-data\nretain,7\n"
        internal fun startTicks(pid: Int): String {
            val stat = File("/proc/$pid/stat").readText()
            // Linux comm can contain spaces and parentheses; fields after its last ')' start at field 3.
            return stat.substring(stat.lastIndexOf(')') + 2).trim().split(Regex("\\s+"))[19]
        }
        private fun sameProcess(pid: Int, start: String): Boolean {
            if (!File("/proc/$pid").exists()) return false
            // An unreadable live PID is a test failure, never proof of successful cleanup.
            return startTicks(pid) == start
        }
    }
}
