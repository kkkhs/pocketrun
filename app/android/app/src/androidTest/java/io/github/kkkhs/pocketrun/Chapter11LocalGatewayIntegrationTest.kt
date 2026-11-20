package io.github.kkkhs.pocketrun

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
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
