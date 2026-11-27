package io.github.kkkhs.pocketrun.taskhost

import io.github.kkkhs.pocketrun.BuildConfig
import java.net.URI

enum class ModelProtocol(val wireName: String) {
    ANTHROPIC("anthropic"),
    OPENAI("openai");

    companion object {
        fun fromWireName(value: String?): ModelProtocol = when (value?.lowercase()) {
            null, "", "anthropic" -> ANTHROPIC
            "openai" -> OPENAI
            else -> throw IllegalArgumentException("Unsupported model protocol.")
        }
    }
}

data class ModelSettings(
    val endpoint: String,
    val model: String,
    val protocol: ModelProtocol = ModelProtocol.ANTHROPIC,
) {
    init {
        require(endpoint.length in 1..512) { "Invalid model endpoint." }
        val uri = URI(endpoint)
        require(allowsProtocol(uri, BuildConfig.DEBUG) && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.port in -1..65535 &&
            uri.normalize() == uri && !endpoint.contains('\\')) {
            "Use HTTPS, or the fixed loopback gateway in a debug build; credentials, query and fragment are forbidden."
        }
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))) { "Invalid model identifier." }
    }

    companion object {
        val DEFAULT = ModelSettings("https://api.anthropic.com", "claude-opus-5-5", ModelProtocol.ANTHROPIC)

        // ponytail: one task-owned Ferry port for local acceptance; release endpoints remain HTTPS.
        internal fun allowsProtocol(uri: URI, debugBuild: Boolean) = uri.scheme == "https" ||
            (debugBuild && uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 8790 &&
                uri.path in listOf("", "/"))

        fun validateCredential(value: String) {
            require(value.length in 16..4096 && value.all { it.code in 0x21..0x7e }) { "Invalid API credential." }
        }
    }
}

internal class ModelCredential(val settings: ModelSettings, val apiKey: String) {
    override fun toString() = "ModelCredential(redacted)"
}
