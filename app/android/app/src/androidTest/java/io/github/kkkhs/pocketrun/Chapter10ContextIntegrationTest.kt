package io.github.kkkhs.pocketrun

import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kkkhs.pocketrun.taskhost.AndroidTaskCapabilities
import io.github.kkkhs.pocketrun.taskhost.ContextPhase
import io.github.kkkhs.pocketrun.taskhost.RawToolCall
import io.github.kkkhs.pocketrun.taskhost.SingleTaskContext
import io.github.kkkhs.pocketrun.taskhost.TaskHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Chapter10ContextIntegrationTest {
    @Test fun realDeviceStepsShareOneGoalWithoutOverwritingEarlierResult() {
        val adapter = AndroidTaskCapabilities(ApplicationProvider.getApplicationContext())
        val task = SingleTaskContext(TaskHost(adapter))
        try {
            val id = task.begin("Observe this demo App twice").taskId!!
            repeat(2) { task.submit(id, RawToolCall("android.device_info", emptyMap())) }
            val completed = task.finish(id)
            assertEquals(ContextPhase.COMPLETED, completed.phase)
            assertEquals(2, completed.steps.size)
            completed.steps.forEach { step ->
                assertEquals(Process.myUid().toString(), step.status.result!!.fields["packageUid"])
            }
            assertNotEquals(completed.steps[0].status.requestNonce, completed.steps[1].status.requestNonce)
        } finally {
            task.close()
            adapter.close()
        }
    }
}
