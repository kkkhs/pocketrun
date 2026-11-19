package io.github.kkkhs.pocketrun

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkkhs.pocketrun.taskhost.AndroidTaskCapabilities
import io.github.kkkhs.pocketrun.taskhost.HostPhase
import io.github.kkkhs.pocketrun.taskhost.RawToolCall
import io.github.kkkhs.pocketrun.taskhost.TaskHost
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

    private fun adapter() = AndroidTaskCapabilities(
        ApplicationProvider.getApplicationContext())
}
