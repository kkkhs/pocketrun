package io.github.kkkhs.pocketrun

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Chapter7LinuxDemoTest {
    @Test fun actualInstallationMappingAndRepeatedAlpinePipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File.createTempFile("chapter7-demo-", ".dir", context.filesDir).canonicalFile
        assertTrue(fixture.delete() && fixture.mkdir())
        try {
            val runtime = LinuxRuntimeManifest.read(context.assets.open("linux/runtime.json"))
            val installer = RootfsInstaller(fixture, File(context.applicationInfo.nativeLibraryDir), runtime, context.assets::open)
            LinuxSession(fixture, installer, Process.myUid(), Process.myPid(),
                canRemoveRun = LinuxRecoveryProbe::isUnusedByAppProcesses).use { linux ->
                val first = linux.prepare(linux.reservePrepare("install-first"))
                assertTrue(first.installedNow)
                assertEquals("3.22.8", first.alpineVersion)
                assertEquals("/workspace", first.guestCwd)
                assertEquals(Process.myUid(), first.appUid)
                assertFalse(linux.prepare(linux.reservePrepare("install-reuse")).installedNow)
                repeat(2) { index ->
                    val result = linux.runDemo(linux.reserve("demo-$index"))
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("chapter7_demo", JSONObject().put("status", result.status)
                            .put("stdout", result.stdout).put("stderr", result.stderr)
                            .put("appUid", result.appUid).put("guestUid", result.guestUid)
                            .put("cwd", result.cwd).put("count", result.lineCount)
                            .put("stageExits", result.stageExitCodes.joinToString(","))
                            .put("tracerExits", result.exitCodes.joinToString(",")).toString())
                    })
                    assertEquals(result.stderr, "completed", result.status)
                    assertEquals("done", result.cleanupStatus)
                    assertEquals("3.22.8", result.alpineVersion)
                    assertEquals(Process.myUid(), result.guestUid)
                    assertEquals("/workspace", result.cwd)
                    assertEquals(3, result.lineCount)
                    assertEquals(listOf(0), result.exitCodes)
                    assertEquals(listOf(0, 0, 0, 0, 0), result.stageExitCodes)
                }
                val data = File(fixture, "workspaces/demo/items.csv")
                assertEquals(WorkspaceSession.DEMO_CSV, data.readText())
                linux.close()
                assertTrue(data.isFile)
            }
        } finally {
            assertTrue(fixture.deleteRecursively())
        }
    }
}
