package io.github.kkkhs.pocketrun.taskhost

/** Strict flat JSON object parser for the small TurboModule boundary. */
internal object TaskHostJson {
    private const val MAX_BYTES = 4096

    fun parseObject(source: String): Map<String, Any?> {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "argumentsJson exceeds 4096 bytes." }
        return Parser(source).parse()
    }

    private class Parser(private val source: String) {
        private var offset = 0

        fun parse(): Map<String, Any?> {
            whitespace()
            expect('{')
            whitespace()
            val result = LinkedHashMap<String, Any?>()
            if (take('}')) {
                whitespace()
                require(offset == source.length) { "Trailing JSON data is not allowed." }
                return result
            }
            while (true) {
                whitespace()
                val key = string()
                require(!result.containsKey(key)) { "Duplicate JSON key is not allowed." }
                whitespace()
                expect(':')
                whitespace()
                result[key] = value()
                whitespace()
                if (take('}')) break
                expect(',')
            }
            whitespace()
            require(offset == source.length) { "Trailing JSON data is not allowed." }
            return result
        }

        private fun value(): Any? = when (peek()) {
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> integer()
            else -> throw IllegalArgumentException("Only flat string, integer, boolean, and null JSON values are allowed.")
        }

        private fun integer(): Any {
            val start = offset
            take('-')
            require(offset < source.length) { "Incomplete JSON integer." }
            if (take('0')) {
                require(offset >= source.length || source[offset] !in '0'..'9') { "Leading zero is not allowed." }
            } else {
                require(peek() in '1'..'9') { "Invalid JSON integer." }
                while (offset < source.length && source[offset] in '0'..'9') offset++
            }
            require(offset >= source.length || source[offset] !in charArrayOf('.', 'e', 'E')) {
                "Floating-point JSON values are not allowed."
            }
            val parsed = source.substring(start, offset).toLongOrNull()
                ?: throw IllegalArgumentException("JSON integer is out of range.")
            return if (parsed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) parsed.toInt() else parsed
        }

        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (offset < source.length) {
                val next = source[offset++]
                when {
                    next == '"' -> return result.toString()
                    next == '\\' -> result.append(escape())
                    next.code < 0x20 -> throw IllegalArgumentException("Control character in JSON string.")
                    else -> result.append(next)
                }
                require(result.length <= MAX_BYTES) { "Decoded JSON string is too large." }
            }
            throw IllegalArgumentException("Unterminated JSON string.")
        }

        private fun escape(): Char = when (val value = next()) {
            '"', '\\', '/' -> value
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                require(offset + 4 <= source.length) { "Incomplete JSON Unicode escape." }
                val code = source.substring(offset, offset + 4).toIntOrNull(16)
                    ?: throw IllegalArgumentException("Invalid JSON Unicode escape.")
                offset += 4
                code.toChar()
            }
            else -> throw IllegalArgumentException("Invalid JSON escape.")
        }

        private fun <T> literal(text: String, value: T): T {
            require(source.regionMatches(offset, text, 0, text.length)) { "Invalid JSON literal." }
            offset += text.length
            return value
        }

        private fun whitespace() {
            while (offset < source.length && source[offset] in charArrayOf(' ', '\t', '\r', '\n')) offset++
        }

        private fun peek(): Char = source.getOrNull(offset)
            ?: throw IllegalArgumentException("Unexpected end of JSON.")

        private fun next(): Char = source.getOrNull(offset++)
            ?: throw IllegalArgumentException("Unexpected end of JSON escape.")

        private fun take(expected: Char): Boolean {
            if (offset < source.length && source[offset] == expected) {
                offset++
                return true
            }
            return false
        }

        private fun expect(expected: Char) {
            require(take(expected)) { "Expected '$expected' in JSON object." }
        }
    }
}
