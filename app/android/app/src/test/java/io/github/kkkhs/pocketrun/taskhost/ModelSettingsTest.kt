package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.*
import org.junit.Test

class ModelSettingsTest {
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
