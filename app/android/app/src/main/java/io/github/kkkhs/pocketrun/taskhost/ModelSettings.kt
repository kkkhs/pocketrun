package io.github.kkkhs.pocketrun.taskhost

import java.net.URI

data class ModelSettings(val endpoint: String, val model: String) {
    init {
        require(endpoint.length in 1..512) { "Invalid model endpoint." }
        val uri = URI(endpoint)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 &&
            uri.normalize() == uri && !endpoint.contains('\\')) { "Use an HTTPS endpoint without credentials, query or fragment." }
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Invalid model identifier." }
    }

    companion object {
        val DEFAULT = ModelSettings("https://api.anthropic.com", "claude-opus-5-5")
        fun validateCredential(value: String) {
            require(value.length in 16..4096 && value.all { it.code in 0x21..0x7e }) { "Invalid API credential." }
        }
    }
}

internal class ModelCredential(val settings: ModelSettings, val apiKey: String) {
    override fun toString() = "ModelCredential(redacted)"
}
