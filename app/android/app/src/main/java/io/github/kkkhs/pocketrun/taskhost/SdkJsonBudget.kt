package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.core.JsonValue
import com.fasterxml.jackson.databind.JsonNode

/** Measure the SDK's serialized JSON, including escaping and full signed assistant blocks. */
internal object SdkJsonBudget {
    fun bytes(value: Any): Int = checkNotNull(JsonValue.from(value).convert(JsonNode::class.java))
        .toString().toByteArray(Charsets.UTF_8).size
}
