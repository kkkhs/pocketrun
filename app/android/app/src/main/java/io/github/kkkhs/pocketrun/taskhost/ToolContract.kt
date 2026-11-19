package io.github.kkkhs.pocketrun.taskhost

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The complete Chapter 9 tool surface. No dynamic tool registration is allowed. */
enum class PocketRunTool(val wireName: String) {
    ANDROID_DEVICE_INFO("android.device_info"),
    LINUX_COUNT_DEMO_CSV("linux.count_demo_csv"),
    ANDROID_VIRTUAL_DISPLAY("android.virtual_display_observe_or_demo_input");

    companion object {
        fun fromWireName(value: String): PocketRunTool = entries.singleOrNull { it.wireName == value }
            ?: throw ToolValidationException("UNKNOWN_TOOL", "Unknown tool.")
    }
}

data class RawToolCall(val tool: String, val arguments: Map<String, Any?>)

class ToolValidationException(val code: String, message: String) : IllegalArgumentException(message)

internal sealed interface ParsedAction {
    val tool: PocketRunTool
    val approvalRequired: Boolean

    data object DeviceInfo : ParsedAction {
        override val tool = PocketRunTool.ANDROID_DEVICE_INFO
        override val approvalRequired = false
    }

    data object CountDemoCsv : ParsedAction {
        override val tool = PocketRunTool.LINUX_COUNT_DEMO_CSV
        override val approvalRequired = true
    }

    sealed interface Display : ParsedAction {
        val expectedDisplayId: Int
        val expectedDisplayEpoch: Long
        override val tool get() = PocketRunTool.ANDROID_VIRTUAL_DISPLAY

        data class Observe(
            override val expectedDisplayId: Int,
            override val expectedDisplayEpoch: Long,
        ) : Display {
            override val approvalRequired = false
        }

        data class DemoTap(
            override val expectedDisplayId: Int,
            override val expectedDisplayEpoch: Long,
            val x: Int,
            val y: Int,
        ) : Display {
            override val approvalRequired = true
        }

        data class DemoText(
            override val expectedDisplayId: Int,
            override val expectedDisplayEpoch: Long,
            val text: String,
        ) : Display {
            override val approvalRequired = true
        }
    }
}

internal object ToolSchema {
    const val MAX_INPUT_BYTES = 4096
    private const val MAX_TOOL_BYTES = 96
    private const val MAX_KEY_BYTES = 64
    private const val MAX_ARGUMENTS = 8
    private const val MAX_TEXT_BYTES = 64
    private val DISPLAY_BASE = setOf("action", "expectedDisplayId", "expectedDisplayEpoch")

    fun validate(call: RawToolCall): ParsedAction {
        val toolBytes = utf8Length(call.tool)
        reject(toolBytes in 1..MAX_TOOL_BYTES, "INVALID_TOOL_NAME", "Invalid tool name.")
        reject(call.arguments.size <= MAX_ARGUMENTS, "INPUT_TOO_LARGE", "Too many arguments.")

        var measured = toolBytes + 16
        val copied = LinkedHashMap<String, Any?>(call.arguments.size)
        for ((key, value) in call.arguments) {
            val keyBytes = utf8Length(key)
            reject(keyBytes in 1..MAX_KEY_BYTES, "INVALID_ARGUMENT_KEY", "Invalid argument key.")
            measured = checkedAdd(measured, keyBytes + measuredValueBytes(value) + 8)
            reject(measured <= MAX_INPUT_BYTES, "INPUT_TOO_LARGE", "Tool input exceeds 4096 bytes.")
            copied[key] = value
        }

        return when (PocketRunTool.fromWireName(call.tool)) {
            PocketRunTool.ANDROID_DEVICE_INFO -> {
                exactKeys(copied, emptySet())
                ParsedAction.DeviceInfo
            }
            PocketRunTool.LINUX_COUNT_DEMO_CSV -> {
                exactKeys(copied, emptySet())
                ParsedAction.CountDemoCsv
            }
            PocketRunTool.ANDROID_VIRTUAL_DISPLAY -> parseDisplay(copied)
        }
    }

    private fun parseDisplay(arguments: Map<String, Any?>): ParsedAction.Display {
        val action = strictString(arguments, "action", 24)
        val expected = when (action) {
            "observe" -> DISPLAY_BASE
            "demo_tap" -> DISPLAY_BASE + setOf("x", "y")
            "demo_text" -> DISPLAY_BASE + "text"
            else -> throw ToolValidationException("UNKNOWN_ACTION", "Unknown virtual-display action.")
        }
        exactKeys(arguments, expected)
        val displayId = strictInt(arguments, "expectedDisplayId")
        val epoch = strictLong(arguments, "expectedDisplayEpoch")
        reject(displayId > 0, "INVALID_DISPLAY_ID", "expectedDisplayId must be positive.")
        reject(epoch > 0, "INVALID_DISPLAY_EPOCH", "expectedDisplayEpoch must be positive.")
        return when (action) {
            "observe" -> ParsedAction.Display.Observe(displayId, epoch)
            "demo_tap" -> {
                val x = strictInt(arguments, "x")
                val y = strictInt(arguments, "y")
                reject(x in 0 until 720 && y in 0 until 1280,
                    "INVALID_TAP", "Demo tap is outside the owned virtual display.")
                ParsedAction.Display.DemoTap(displayId, epoch, x, y)
            }
            "demo_text" -> {
                val text = strictString(arguments, "text", MAX_TEXT_BYTES)
                reject(text.isNotEmpty() && text.all { it.code in 0x20..0x7e },
                    "INVALID_TEXT", "Demo text must be 1..64 printable ASCII bytes.")
                ParsedAction.Display.DemoText(displayId, epoch, text)
            }
            else -> error("Action was checked above.")
        }
    }

    private fun exactKeys(arguments: Map<String, Any?>, expected: Set<String>) {
        val extras = arguments.keys - expected
        val missing = expected - arguments.keys
        reject(extras.isEmpty(), "UNKNOWN_ARGUMENT", "Unknown argument key.")
        reject(missing.isEmpty(), "MISSING_ARGUMENT", "Missing required argument.")
    }

    private fun strictString(arguments: Map<String, Any?>, key: String, maxBytes: Int): String {
        val value = arguments[key]
        reject(value is String, "WRONG_ARGUMENT_TYPE", "$key must be a string.")
        value as String
        reject(utf8Length(value) <= maxBytes, "STRING_TOO_LONG", "$key is too long.")
        return value
    }

    private fun strictInt(arguments: Map<String, Any?>, key: String): Int {
        val value = arguments[key]
        reject(value is Int, "WRONG_ARGUMENT_TYPE", "$key must be an Int.")
        return value as Int
    }

    private fun strictLong(arguments: Map<String, Any?>, key: String): Long {
        val value = arguments[key]
        reject(value is Long, "WRONG_ARGUMENT_TYPE", "$key must be a Long.")
        return value as Long
    }

    private fun measuredValueBytes(value: Any?): Int = when (value) {
        is String -> checkedAdd(utf8Length(value), 2)
        is Int, is Long, is Boolean -> 24
        null -> 4
        else -> throw ToolValidationException("WRONG_ARGUMENT_TYPE", "Nested or unsupported argument type.")
    }

    private fun checkedAdd(left: Int, right: Int): Int = try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        throw ToolValidationException("INPUT_TOO_LARGE", "Tool input is too large.")
    }

    private fun utf8Length(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size
    private fun reject(condition: Boolean, code: String, message: String) {
        if (!condition) throw ToolValidationException(code, message)
    }
}

internal object RequestHasher {
    fun hash(parts: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((key, value) in parts) {
            field(digest, key)
            field(digest, value)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun field(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(byteArrayOf(
            (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
            (bytes.size ushr 8).toByte(), bytes.size.toByte(),
        ))
        digest.update(bytes)
    }
}
