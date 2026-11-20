package io.github.kkkhs.pocketrun

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkkhs.pocketrun.taskhost.AndroidTaskCapabilities
import io.github.kkkhs.pocketrun.taskhost.HostPhase
import io.github.kkkhs.pocketrun.taskhost.RawToolCall
import io.github.kkkhs.pocketrun.taskhost.TaskHost
import io.github.kkkhs.pocketrun.taskhost.TaskHostJson
import io.github.kkkhs.pocketrun.taskhost.ToolValidationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Chapter9TaskHostIntegrationTest {
    @Test
    fun actualDeviceInfoComesFromThisAppProcess() {
        val adapter = adapter()
        val host = TaskHost(adapter)
        try {
            val state = host.submit(RawToolCall("android.device_info", emptyMap()))
            assertEquals(HostPhase.SUCCEEDED, state.phase)
            assertEquals(Process.myUid().toString(), state.result?.fields?.get("packageUid"))
            assertEquals(ApplicationProvider.getApplicationContext<android.content.Context>().packageName,
                state.result?.fields?.get("packageName"))
            assertNotNull(state.result?.fields?.get("androidVersion"))
        } finally {
            host.close()
            adapter.close()
        }
    }

    @Test
    fun absentAppOwnedDisplayBackendIsExplicitlyRejected() {
        val adapter = adapter()
        val host = TaskHost(adapter)
        try {
            val error = try {
                host.submit(RawToolCall("android.virtual_display_observe_or_demo_input", mapOf(
                    "action" to "observe", "expectedDisplayId" to 1,
                    "expectedDisplayEpoch" to 1L)))
                null
            } catch (failure: ToolValidationException) { failure }
            assertEquals("BACKEND_NOT_READY", error?.code)
            assertEquals(HostPhase.INVALID_INPUT, host.state().phase)
        } finally {
            host.close()
            adapter.close()
        }
    }

    @Test
    fun fixedLinuxCsvStopsAtPendingAppUiApproval() {
        val adapter = adapter()
        val host = TaskHost(adapter)
        try {
            val target = adapter.prepareLinuxEnvironment()
            val pending = host.submit(RawToolCall("linux.count_demo_csv", emptyMap()))
            assertEquals(HostPhase.PENDING_APPROVAL, pending.phase)
            assertEquals("app_phone_ui", pending.approvalActor)
            assertTrue(pending.target.orEmpty().contains(target.rootfsGeneration))
            // Instrumentation does not impersonate the phone UI approval actor.
            assertTrue(host.cancelFromAppUi(checkNotNull(pending.requestNonce)))
            assertEquals(HostPhase.CANCELLED, host.state().phase)
        } finally {
            host.close()
            adapter.close()
        }
    }

    @Test
    fun nativeBoundaryRejectsFixedBadSamplesWithoutRunningATool() {
        // This validates the actual Android native entry boundary, not a JS click or phone approval.
        assertEquals("duplicate", failureText {
            TaskHostJson.parseObject("{\"x\":1,\"x\":2}")
        }.substringBefore(' '))
        val adapter = adapter()
        val host = TaskHost(adapter)
        try {
            assertCode("UNKNOWN_TOOL") { host.submit(RawToolCall("linux.shell", emptyMap())) }
            for (key in listOf("path", "argv", "env")) {
                assertCode("UNKNOWN_ARGUMENT") {
                    host.submit(RawToolCall("linux.count_demo_csv", mapOf(key to "forbidden")))
                }
            }
            assertCode("INVALID_DISPLAY_ID") {
                host.submit(RawToolCall("android.virtual_display_observe_or_demo_input", mapOf(
                    "action" to "observe", "expectedDisplayId" to 0,
                    "expectedDisplayEpoch" to 1L)))
            }
            assertCode("INVALID_TAP") {
                host.submit(RawToolCall("android.virtual_display_observe_or_demo_input", mapOf(
                    "action" to "demo_tap", "expectedDisplayId" to 1,
                    "expectedDisplayEpoch" to 1L, "x" to 720, "y" to 0)))
            }
            assertCode("INVALID_TEXT") {
                host.submit(RawToolCall("android.virtual_display_observe_or_demo_input", mapOf(
                    "action" to "demo_text", "expectedDisplayId" to 1,
                    "expectedDisplayEpoch" to 1L, "text" to "bad\ntext")))
            }
        } finally {
            host.close()
            adapter.close()
        }
    }

    private fun assertCode(expected: String, action: () -> Unit) {
        val error = try { action(); null } catch (failure: ToolValidationException) { failure }
        assertEquals(expected, error?.code)
    }

    private fun failureText(action: () -> Unit): String = try {
        action()
        "missing failure"
    } catch (error: IllegalArgumentException) {
        error.message.orEmpty().lowercase()
    }

    private fun adapter() = AndroidTaskCapabilities(
        ApplicationProvider.getApplicationContext())
}
