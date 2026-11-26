package io.github.kkkhs.pocketrun

import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkkhs.pocketrun.taskhost.AndroidTaskCapabilities
import io.github.kkkhs.pocketrun.taskhost.DemoCsvReport
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real App-UID file IO and JNI no-replace on isolated task-owned data; no phone approval/model. */
@RunWith(AndroidJUnit4::class)
class Chapter13WorkspaceIntegrationTest {
    @Test fun nativePublicationUsesPrivateAppUidFilesAndConfirmsExactBytes() = isolated { _, root ->
        WorkspaceSession(root).use { session ->
            val directory = File(session.prepare().directory)
            val input = session.readDemoCsvBytes()
            val report = DemoCsvReport.parse(input)
            val info = Os.stat(File(directory, "items.csv").path)
            assertEquals(Process.myUid(), info.st_uid)
            assertTrue(OsConstants.S_ISREG(info.st_mode))
            val first = session.publishDemoResult(report.text, {})
            assertEquals("published", first.status)
            assertArrayEquals(report.fileText.toByteArray(Charsets.UTF_8), session.readDemoResultBytes())
            assertEquals(0x180, Os.stat(File(directory, "result.txt").path).st_mode and 0x1ff)
            assertEquals("already_present", session.publishDemoResult(report.text, {}).status)
            assertTrue(directory.listFiles()!!.none { it.name.startsWith(".pocketrun-result-") })
        }
    }

    @Test fun existingOutputAndNoFollowReadsPreserveOtherData() = isolated { _, root ->
        WorkspaceSession(root).use { session ->
            val directory = File(session.prepare().directory)
            val result = File(directory, "result.txt")
            result.writeText("keep existing output\n")
            assertThrows(IllegalArgumentException::class.java) { session.publishDemoResult("items=2;quantity=5", {}) }
            assertEquals("keep existing output\n", result.readText())
            check(result.delete())
            val outside = File(root, "outside.txt").apply { writeText("keep outside data\n") }
            Os.symlink(outside.path, result.path)
            assertThrows(IllegalArgumentException::class.java) { session.readDemoResultBytes() }
            assertEquals("keep outside data\n", outside.readText())
            check(result.delete())
            File(directory, "items.csv").writeBytes(ByteArray(DemoCsvReport.MAX_BYTES + 1))
            assertThrows(IllegalArgumentException::class.java) { session.readDemoCsvBytes() }
        }
    }

    @Test fun interruptedStagingRequiresReviewAndIsNotSilentlyDeleted() = isolated { context, _ ->
        AndroidTaskCapabilities(context).use { adapter ->
            val target = adapter.prepareWorkspaceEnvironment()
            val staging = target.root.resolve(".pocketrun-result-interrupted.tmp").toFile().apply { writeText("uncertain") }
            assertThrows(IllegalStateException::class.java) { adapter.checkWorkspaceRecovery() }
            assertEquals("uncertain", staging.readText())
            check(staging.delete())
            assertEquals(target.inputDigest, adapter.checkWorkspaceRecovery().inputDigest)
        }
    }

    private fun isolated(action: (Context, File) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.filesDir, "chapter13-workspace-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        try { action(context, root) }
        finally { check(root.deleteRecursively()) { "Owned workspace fixture cleanup failed." } }
    }
}
