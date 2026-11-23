package io.github.kkkhs.pocketrun

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An actual App-UID executable/loader probe, not full Chapter 7 acceptance. */
@RunWith(AndroidJUnit4::class)
class Chapter7RuntimeProbeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun installedReadonlyElfStartsUnderAppUid() {
        val manifest = JSONObject(context.assets.open("linux/runtime.json").bufferedReader().use { it.readText() })
        val native = File(context.applicationInfo.nativeLibraryDir)
        assertTrue("Installed native ELF directory must exist", native.isDirectory)
        val artifacts = manifest.getJSONArray("artifacts")
        for (index in 0 until artifacts.length()) {
            val entry = artifacts.getJSONObject(index)
            val file = File(native, entry.getString("name"))
            assertTrue("A verified installed executable is required: ${file.name}", file.isFile)
            assertFalse("App must not be able to rewrite installed ELF: ${file.name}", file.canWrite())
            val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(entry.getString("sha256"), digest)
        }
        val root = File.createTempFile("chapter7-probe-", ".dir", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        try {
            val guest = File(root, "root").apply { mkdir() }
            File(guest, "bin").mkdir()
            File(guest, "lib").mkdir()
            File(guest, "bin/busybox").createNewFile()
            File(guest, "lib/ld-musl-aarch64.so.1").createNewFile()
            val temporary = File(root, "temporary").apply { mkdir() }
            val env = mapOf(
                "POCKETRUN_EXPECTED_PARENT_PID" to Process.myPid().toString(),
                "PROOT_LOADER" to File(native, "libproot_loader.so").path,
                "PROOT_TMP_DIR" to temporary.path,
                "HOME" to "/",
                "PATH" to "/bin:/usr/bin",
            )
            CommandPipeline().use { pipeline ->
                val version = pipeline.execute(pipeline.reserve(), root,
                    listOf(listOf(File(native, "libproot.so").path, "--version")), env = env, clearEnvironment = true)
                evidence("version", version)
                assertEquals(version.stderr, "completed", version.status)
                assertTrue(version.stdout.contains("5.1.107.102"))
                val command = listOf(File(native, "libproot.so").path, "--kill-on-exit", "-r", guest.path,
                    "-b", File(native, "libalpine_bin_busybox.so").path + ":/bin/busybox",
                    "-b", File(native, "libalpine_lib_ld_musl_aarch64_so_1.so").path + ":/lib/ld-musl-aarch64.so.1",
                    "-w", "/", "/bin/busybox", "id", "-u")
                val actual = pipeline.execute(pipeline.reserve(), root, listOf(command), env = env, clearEnvironment = true)
                evidence("alpine_uid", actual)
                assertEquals(actual.stderr, "completed", actual.status)
                assertEquals(Process.myUid().toString(), actual.stdout.trim())
                assertTrue(Process.myUid() != 0 && Process.myUid() != 2000)
                assertTrue(pipeline.runningProcesses().isEmpty())
            }
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    private fun evidence(stage: String, result: CommandPipeline.Result) {
        instrumentation.sendStatus(2, Bundle().apply {
            putString("chapter7_probe", JSONObject().put("stage", stage).put("appUid", Process.myUid())
                .put("status", result.status).put("stdout", result.stdout).put("stderr", result.stderr)
                .put("exits", result.exitCodes.joinToString(",")).toString())
        })
    }
}
