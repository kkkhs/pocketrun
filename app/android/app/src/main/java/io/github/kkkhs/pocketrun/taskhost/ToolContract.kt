package io.github.kkkhs.pocketrun.taskhost

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The complete Chapter 9 tool surface. No dynamic tool registration is allowed. */
enum class PocketRunTool(val wireName: String) {
    ANDROID_DEVICE_INFO("android.device_info"),
    LINUX_COUNT_DEMO_CSV("linux.count_demo_csv"),
    LINUX_RUN_SHELL("linux.run_shell"),
    WORKSPACE_READ_DEMO_CSV("workspace.read_demo_csv"),
    WORKSPACE_PUBLISH_DEMO_RESULT("workspace.publish_demo_result"),
    WORKSPACE_LIST_FILES("workspace.list_files"),
    WORKSPACE_READ_FILE("workspace.read_file"),
    WORKSPACE_WRITE_FILE("workspace.write_file"),
    APP_LIST_APPS("app.list_apps"),
    APP_OPEN("app.open"),
    APP_OBSERVE("app.observe"),
    APP_TAP("app.tap"),
    APP_SWIPE("app.swipe"),
    APP_INPUT_TEXT("app.input_text"),
    APP_PRESS_KEY("app.press_key"),
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

    data class RunLinuxShell(val command: String, val timeoutSec: Int) : ParsedAction {
        override val tool = PocketRunTool.LINUX_RUN_SHELL
        override val approvalRequired = true
    }

    data object ReadDemoCsv : ParsedAction {
        override val tool = PocketRunTool.WORKSPACE_READ_DEMO_CSV
        override val approvalRequired = true
    }

    data class PublishDemoResult(val text: String, val expectedInputDigest: String) : ParsedAction {
        override val tool = PocketRunTool.WORKSPACE_PUBLISH_DEMO_RESULT
        override val approvalRequired = true
    }

    data object ListWorkspaceFiles : ParsedAction {
        override val tool = PocketRunTool.WORKSPACE_LIST_FILES
        override val approvalRequired = false
    }

    data class ReadWorkspaceFile(val path: String) : ParsedAction {
        override val tool = PocketRunTool.WORKSPACE_READ_FILE
        override val approvalRequired = true
    }

    data class WriteWorkspaceFile(val path: String, val content: String) : ParsedAction {
        override val tool = PocketRunTool.WORKSPACE_WRITE_FILE
        override val approvalRequired = true
    }

    data class ListApps(val query: String?) : ParsedAction {
        override val tool = PocketRunTool.APP_LIST_APPS
        override val approvalRequired = false
    }

    data class OpenApp(val packageName: String) : ParsedAction {
        override val tool = PocketRunTool.APP_OPEN
        override val approvalRequired = true
    }

    data object ObserveApp : ParsedAction {
        override val tool = PocketRunTool.APP_OBSERVE
        override val approvalRequired = true
    }

    data class AppTap(val x: Int, val y: Int) : ParsedAction {
        override val tool = PocketRunTool.APP_TAP
        override val approvalRequired = true
    }

    data class AppSwipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val durationMs: Int) : ParsedAction {
        override val tool = PocketRunTool.APP_SWIPE
        override val approvalRequired = true
    }

    data class AppInputText(val text: String) : ParsedAction {
        override val tool = PocketRunTool.APP_INPUT_TEXT
        override val approvalRequired = true
    }

    data class AppPressKey(val key: String) : ParsedAction {
        override val tool = PocketRunTool.APP_PRESS_KEY
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

        data class ObserveDemo(
            override val expectedDisplayId: Int,
            override val expectedDisplayEpoch: Long,
        ) : Display {
            override val approvalRequired = true
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
    const val MAX_WRITE_CONTENT_BYTES = 12 * 1024
    private const val MAX_WRITE_INPUT_BYTES = MAX_WRITE_CONTENT_BYTES + 1024
    private const val MAX_PATH_BYTES = 128
    private const val MAX_TOOL_BYTES = 96
    private const val MAX_KEY_BYTES = 64
    private const val MAX_ARGUMENTS = 8
    private const val MAX_TEXT_BYTES = 64
    private const val MAX_APP_TEXT_CHARS = 256
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val ALLOWED_APP_KEYS = setOf("BACK", "HOME", "ENTER", "DEL", "TAB", "UP", "DOWN", "LEFT", "RIGHT")
    private val DISPLAY_BASE = setOf("action", "expectedDisplayId", "expectedDisplayEpoch")

    fun validate(call: RawToolCall): ParsedAction {
        val toolBytes = utf8Length(call.tool)
        reject(toolBytes in 1..MAX_TOOL_BYTES, "INVALID_TOOL_NAME", "Invalid tool name.")
        reject(call.arguments.size <= MAX_ARGUMENTS, "INPUT_TOO_LARGE", "Too many arguments.")

        val maxBudget = if (call.tool == PocketRunTool.WORKSPACE_WRITE_FILE.wireName) MAX_WRITE_INPUT_BYTES else MAX_INPUT_BYTES
        var measured = toolBytes + 16
        val copied = LinkedHashMap<String, Any?>(call.arguments.size)
        for ((key, value) in call.arguments) {
            val keyBytes = utf8Length(key)
            reject(keyBytes in 1..MAX_KEY_BYTES, "INVALID_ARGUMENT_KEY", "Invalid argument key.")
            measured = checkedAdd(measured, keyBytes + measuredValueBytes(value) + 8)
            reject(measured <= maxBudget, "INPUT_TOO_LARGE", "Tool input exceeds allowed size.")
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
            PocketRunTool.LINUX_RUN_SHELL -> {
                val allowed = if (copied.containsKey("timeout_sec")) setOf("command", "timeout_sec") else setOf("command")
                exactKeys(copied, allowed)
                val command = strictString(copied, "command", 1024)
                reject(command.isNotBlank() && !command.contains('\u0000'), "INVALID_COMMAND", "Shell command must be non-empty UTF-8 text.")
                val timeoutSec = if (copied.containsKey("timeout_sec")) strictInt(copied, "timeout_sec") else 10
                reject(timeoutSec in 1..15, "INVALID_TIMEOUT", "timeout_sec must be between 1 and 15.")
                ParsedAction.RunLinuxShell(command, timeoutSec)
            }
            PocketRunTool.WORKSPACE_READ_DEMO_CSV -> {
                exactKeys(copied, emptySet())
                ParsedAction.ReadDemoCsv
            }
            PocketRunTool.WORKSPACE_PUBLISH_DEMO_RESULT -> {
                exactKeys(copied, setOf("text", "expectedInputDigest"))
                val text = strictString(copied, "text", MAX_TEXT_BYTES)
                val digest = strictString(copied, "expectedInputDigest", 64)
                reject(text.isNotEmpty() && text.all { it.code in 0x20..0x7e }, "INVALID_TEXT", "Result text must be bounded printable ASCII.")
                reject(digest.matches(Regex("[a-f0-9]{64}")), "INVALID_INPUT_DIGEST", "Expected input digest is invalid.")
                ParsedAction.PublishDemoResult(text, digest)
            }
            PocketRunTool.WORKSPACE_LIST_FILES -> {
                exactKeys(copied, emptySet())
                ParsedAction.ListWorkspaceFiles
            }
            PocketRunTool.WORKSPACE_READ_FILE -> {
                exactKeys(copied, setOf("path"))
                val path = strictString(copied, "path", MAX_PATH_BYTES)
                reject(path.isNotBlank() && !path.contains('\u0000'), "INVALID_PATH", "Invalid workspace file path.")
                ParsedAction.ReadWorkspaceFile(path)
            }
            PocketRunTool.WORKSPACE_WRITE_FILE -> {
                exactKeys(copied, setOf("path", "content"))
                val path = strictString(copied, "path", MAX_PATH_BYTES)
                val content = strictString(copied, "content", MAX_WRITE_CONTENT_BYTES)
                reject(path.isNotBlank() && !path.contains('\u0000'), "INVALID_PATH", "Invalid workspace file path.")
                reject(!content.contains('\u0000'), "INVALID_TEXT", "File content must be valid UTF-8 text.")
                ParsedAction.WriteWorkspaceFile(path, content)
            }
            PocketRunTool.APP_LIST_APPS -> {
                val allowed = if (copied.containsKey("query")) setOf("query") else emptySet()
                exactKeys(copied, allowed)
                val query = if (copied.containsKey("query")) {
                    strictString(copied, "query", 64).trim().ifEmpty { null }
                } else null
                ParsedAction.ListApps(query)
            }
            PocketRunTool.APP_OPEN -> {
                exactKeys(copied, setOf("package_name"))
                val pkg = strictString(copied, "package_name", 128)
                reject(pkg.length in 3..128 && PACKAGE_PATTERN.matches(pkg), "INVALID_PACKAGE", "Invalid Android package name.")
                ParsedAction.OpenApp(pkg)
            }
            PocketRunTool.APP_OBSERVE -> {
                exactKeys(copied, emptySet())
                ParsedAction.ObserveApp
            }
            PocketRunTool.APP_TAP -> {
                exactKeys(copied, setOf("x", "y"))
                val x = strictInt(copied, "x")
                val y = strictInt(copied, "y")
                reject(x in 0 until 720 && y in 0 until 1280, "INVALID_TAP", "Tap coordinate is outside virtual display.")
                ParsedAction.AppTap(x, y)
            }
            PocketRunTool.APP_SWIPE -> {
                val allowed = if (copied.containsKey("duration_ms")) {
                    setOf("x1", "y1", "x2", "y2", "duration_ms")
                } else {
                    setOf("x1", "y1", "x2", "y2")
                }
                exactKeys(copied, allowed)
                val x1 = strictInt(copied, "x1")
                val y1 = strictInt(copied, "y1")
                val x2 = strictInt(copied, "x2")
                val y2 = strictInt(copied, "y2")
                val durationMs = if (copied.containsKey("duration_ms")) strictInt(copied, "duration_ms") else 320
                reject(
                    x1 in 0 until 720 && y1 in 0 until 1280 && x2 in 0 until 720 && y2 in 0 until 1280,
                    "INVALID_SWIPE",
                    "Swipe coordinates are outside virtual display.",
                )
                reject(durationMs in 100..1500, "INVALID_SWIPE", "duration_ms must be between 100 and 1500.")
                ParsedAction.AppSwipe(x1, y1, x2, y2, durationMs)
            }
            PocketRunTool.APP_INPUT_TEXT -> {
                exactKeys(copied, setOf("text"))
                val text = strictString(copied, "text", 1024)
                reject(text.length in 1..MAX_APP_TEXT_CHARS && !text.contains('\u0000'), "INVALID_TEXT", "Text must be 1..256 UTF-8 chars.")
                ParsedAction.AppInputText(text)
            }
            PocketRunTool.APP_PRESS_KEY -> {
                exactKeys(copied, setOf("key"))
                val key = strictString(copied, "key", 16).uppercase()
                reject(key in ALLOWED_APP_KEYS, "INVALID_KEY", "Unsupported key name.")
                ParsedAction.AppPressKey(key)
            }
            PocketRunTool.ANDROID_VIRTUAL_DISPLAY -> parseDisplay(copied)
        }
    }

    private fun parseDisplay(arguments: Map<String, Any?>): ParsedAction.Display {
        val action = strictString(arguments, "action", 24)
        val expected = when (action) {
            "observe", "observe_demo" -> DISPLAY_BASE
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
            "observe_demo" -> ParsedAction.Display.ObserveDemo(displayId, epoch)
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
