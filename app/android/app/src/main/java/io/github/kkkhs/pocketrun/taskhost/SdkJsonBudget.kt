package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.core.JsonValue
import com.fasterxml.jackson.databind.JsonNode

/** Measure the SDK's serialized JSON, including escaping and full signed assistant blocks. */
internal object SdkJsonBudget {
    fun json(value: Any): String = checkNotNull(JsonValue.from(value).convert(JsonNode::class.java)).toString()
    fun bytes(value: Any): Int = json(value).toByteArray(Charsets.UTF_8).size
}
