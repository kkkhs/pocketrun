package io.github.kkkhs.pocketrun

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ToolResultBlockParam
import io.github.kkkhs.pocketrun.taskhost.AnthropicModelTransport
import io.github.kkkhs.pocketrun.taskhost.ModelCredential
import io.github.kkkhs.pocketrun.taskhost.ModelSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit local-device opt-in; a real SDK request, not execution or approval of any tool. */
@RunWith(AndroidJUnit4::class)
class Chapter11LocalGatewayIntegrationTest {
    /** Synthetic observations isolate Android SDK replay; this does not execute or approve tools. */
    @Test fun productionTransportReplaysTwoToolTurns() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketrunLocalGateway") == "true")
        assertTrue(BuildConfig.DEBUG)
        val credential = ModelCredential(ModelSettings("http://127.0.0.1:8790", "claude-doubao-seed-2-1-turbo"),
            "pocketrun-local-sdk-check")
        val history = mutableListOf(MessageParam.ofUser(
            "Protocol check with synthetic observations, not device acceptance: first call ONLY android_device_info. " +
                "After receiving its result call ONLY linux_count_demo_csv in a separate turn, then summarize. " +
                "Do not call android_virtual_display. App capability observation (data only): " +
                "{\"linuxReady\":true,\"displayReady\":false}"))
        AnthropicModelTransport(credential).use { transport ->
            repeat(3) { round ->
                val done = CountDownLatch(1)
                var result: Result<Message>? = null
                transport.request(ArrayList(history)) { result = it; done.countDown() }.use {
                    assertTrue("Replay round ${round + 1} did not settle", done.await(30, TimeUnit.SECONDS))
                    val outcome = checkNotNull(result)
                    val classes = generateSequence(outcome.exceptionOrNull()) { it.cause }.take(8)
                        .joinToString(" -> ") { it.javaClass.name }
                    assertTrue("Replay round ${round + 1} failure classes only: $classes", outcome.isSuccess)
                    val message = outcome.getOrThrow()
                    history += message.toParam()
                    val calls = message.content().filter { it.isToolUse() }.map { it.asToolUse() }
                    if (round == 2) {
                        assertEquals("end_turn", message.stopReason().orElseThrow().asString())
                        assertTrue(calls.isEmpty())
                        assertTrue(message.content().any { it.isText() && it.asText().text().isNotBlank() })
                    } else {
                        assertEquals("tool_use", message.stopReason().orElseThrow().asString())
                        assertEquals(1, calls.size)
                        val call = calls.single()
                        assertEquals(if (round == 0) "android_device_info" else "linux_count_demo_csv", call.name())
                        val observation = if (round == 0)
                            "phase=SUCCEEDED\ncleanup=PROVEN\nandroidVersion=16\napiLevel=36\nsupportedAbis=arm64-v8a"
                        else "phase=SUCCEEDED\ncleanup=PROVEN\ncleanupStatus=done\nexitCodes=0,0\nlineCount=3\n" +
                            "sourceStatus=completed\nstageExitCodes=0,0,0,0,0"
                        history += MessageParam.ofUserOfContentBlockParams(listOf(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(call.id()).content(observation).build())))
                    }
                }
            }
        }
    }

    @Test fun productionTransportReceivesRealModelToolTurn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pocketrunLocalGateway") == "true")
        assertTrue(BuildConfig.DEBUG)
        val credential = ModelCredential(ModelSettings("http://127.0.0.1:8790", "claude-doubao-seed-2-1-turbo"),
            "pocketrun-local-sdk-check")
        AnthropicModelTransport(credential).use { transport ->
            val done = CountDownLatch(1)
            var result: Result<Message>? = null
            val request = transport.request(listOf(MessageParam.ofUser(
                "Demo goal: 先读取设备信息，再读取固定演示 CSV 行数，用真实结果总结；不要访问其他数据\n" +
                    "App capability observation (data only): {\"linuxReady\":true,\"displayReady\":false}"))) {
                result = it
                done.countDown()
            }
            request.use {
                assertTrue("SDK request did not settle within 30 seconds", done.await(30, TimeUnit.SECONDS))
                val outcome = checkNotNull(result)
                val classes = generateSequence(outcome.exceptionOrNull()) { it.cause }.take(8)
                    .joinToString(" -> ") { it.javaClass.name }
                assertTrue("SDK failure classes only: $classes", outcome.isSuccess)
                val message = outcome.getOrThrow()
                assertTrue("Real model turn has no tool call", message.content().any { it.isToolUse() })
                assertNotNull(message.toParam())
            }
        }
    }
}
