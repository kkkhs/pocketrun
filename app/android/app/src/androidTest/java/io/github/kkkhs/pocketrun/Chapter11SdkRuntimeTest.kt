package io.github.kkkhs.pocketrun

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.JsonValue
import com.anthropic.core.LogLevel
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.helpers.MessageAccumulator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/** No network call. Android/Jackson/SDK linkage smoke check with synthetic SSE event objects. */
@RunWith(AndroidJUnit4::class)
class Chapter11SdkRuntimeTest {
    @Test fun constructsOfficialSdkAndAccumulatesSyntheticMessageOnAndroid() {
        val client = AnthropicOkHttpClientAsync.builder().apiKey("demo-not-a-real-api-credential")
            .baseUrl("https://api.anthropic.com").timeout(Duration.ofSeconds(1))
            .maxRetries(0).logLevel(LogLevel.OFF).openTelemetry(false).build()
        try {
            val request = MessageCreateParams.builder().model("claude-opus-5-5").maxTokens(16)
                .addMessage(MessageParam.ofUser("demo")).build()
            assertEquals("claude-opus-5-5", request.model().asString())
            val accumulator = MessageAccumulator.create()
            val message = mapOf(
                "id" to "msg_demo", "type" to "message", "role" to "assistant", "model" to "claude-opus-5-5",
                "content" to emptyList<Any>(), "container" to null, "diagnostics" to null, "stop_details" to null,
                "stop_reason" to "end_turn", "stop_sequence" to null,
                "usage" to mapOf("input_tokens" to 1, "output_tokens" to 0, "cache_creation" to null,
                    "cache_creation_input_tokens" to 0, "cache_read_input_tokens" to 0, "inference_geo" to null,
                    "server_tool_use" to null, "service_tier" to "standard", "output_tokens_details" to mapOf("thinking_tokens" to 0)),
            )
            accumulator.accumulate(JsonValue.from(mapOf("type" to "message_start", "message" to message))
                .convert(RawMessageStreamEvent::class.java)!!)
            accumulator.accumulate(JsonValue.from(mapOf("type" to "message_stop")).convert(RawMessageStreamEvent::class.java)!!)
            assertEquals("msg_demo", accumulator.message().id())
            assertNotNull(accumulator.message().toParam())
        } finally { client.close() }
    }
}
