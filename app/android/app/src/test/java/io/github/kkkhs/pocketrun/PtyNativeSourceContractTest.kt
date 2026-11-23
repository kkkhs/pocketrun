package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source-level regression only; this does not claim that JNI ran on Android. */
class PtyNativeSourceContractTest {
    @Test fun postExitDrainUsesByteBudgetRatherThanReadCallCount() {
        val source = source()
        assertTrue(source.contains("kMaximumPostExitBytes = 64U * 1024U"))
        assertTrue(source.contains("post_exit_bytes += static_cast<size_t>(count)"))
        assertTrue(source.contains("kMaximumPostExitBytes - handle->post_exit_bytes"))
        assertFalse(source.contains("post_exit_reads"))
        assertFalse(source.contains("kMaximumPostExitReads"))
    }

    @Test fun interactiveHistoryPersistenceIsDisabledInsteadOfFallingBackToInstalledRootfs() {
        val source = source()
        assertTrue(source.contains("HOME=/root"))
        assertTrue(source.contains("\"HISTFILE=\""))
        assertFalse(source.contains("HISTFILE=/root/"))
        assertFalse(source.contains("HISTFILE=/tmp/"))
        assertFalse(source.contains("HISTFILE=/dev/null")) // BusyBox trimming could derive a /dev/null.<pid>.new path.
    }

    @Test fun launchIdentityDoesNotUseDumpabilitySensitiveProcDirectoryOwner() {
        val source = source()
        assertTrue(source.contains("/proc/%d/status"))
        assertTrue(source.contains("Uid:%u%u%u%u"))
        assertTrue(source.contains("exec_pipe"))
        assertTrue(source.contains("PTY exec-success handshake"))
        assertTrue(source.contains("WEXITED | WNOWAIT"))
        assertFalse(source.contains("identity->uid = status.st_uid"))
    }

    private fun source(): String {
        val explicit = System.getProperty("pocketrun.pty.source")?.let(::File)
        val candidates = listOfNotNull(
            explicit,
            File("app/android/app/src/main/cpp/pocketrun_pty.cpp"),
            File("app/src/main/cpp/pocketrun_pty.cpp"),
            File("src/main/cpp/pocketrun_pty.cpp"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("pocketrun_pty.cpp source was not found", file)
        return file!!.readText()
    }
}
