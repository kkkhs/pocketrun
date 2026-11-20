package io.github.kkkhs.pocketrun.taskhost

import io.github.kkkhs.pocketrun.BuildConfig
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class ModelSettingsTest {
    @Test fun onlyTheFixedDebugLoopbackGatewayCanUseHttp() {
        val local = URI("http://127.0.0.1:8790")
        assertTrue(ModelSettings.allowsProtocol(local, true))
        assertFalse(ModelSettings.allowsProtocol(local, false))
        if (BuildConfig.DEBUG) assertEquals(local.toString(), ModelSettings(local.toString(), "demo-model").endpoint)
        else assertThrows(IllegalArgumentException::class.java) { ModelSettings(local.toString(), "demo-model") }
        assertTrue(ModelSettings.allowsProtocol(URI("https://api.anthropic.com"), false))
        listOf("http://localhost:8790", "http://127.0.0.1:8791", "http://example.com:8790",
            "http://127.0.0.1:8790/v1", "http://127.0.0.1.evil.example:8790").forEach {
            assertFalse(ModelSettings.allowsProtocol(URI(it), true))
        }
        listOf("http://user:secret@127.0.0.1:8790", "http://127.0.0.1:8790?key=demo",
            "http://127.0.0.1:8790#secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelSettings(it, "demo-model") }
        }
    }

    @Test fun httpsEndpointRejectsSecretsRedirectInputsAndMalformedModels() {
        assertEquals("claude-opus-5-5", ModelSettings.DEFAULT.model)
        listOf("http://example.com", "https://user:secret@example.com", "https://example.com?api_key=demo",
            "https://example.com#secret", "https://example.com/a/../b", "https://example.com\\secret").forEach { endpoint ->
            assertThrows(Exception::class.java) { ModelSettings(endpoint, "demo-model") }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelSettings("https://example.com", "bad\nheader") }
        assertThrows(IllegalArgumentException::class.java) { ModelSettings.validateCredential("demo\r\nheader") }
        assertThrows(IllegalArgumentException::class.java) { ModelSettings.validateCredential("x".repeat(4097)) }
    }
}
