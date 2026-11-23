package io.github.kkkhs.pocketrun

/** Incremental UTF-8 decoding with a lifetime output cap measured in UTF-8 bytes. */
internal class PtyCodec(private val maximumBytes: Int = MAX_OUTPUT_BYTES) {
    data class Chunk(val text: String, val truncated: Boolean)

    private var pending = ByteArray(0)
    private var emittedBytes = 0
    private var stopped = false

    init { require(maximumBytes in 1..MAX_OUTPUT_BYTES) }

    @Synchronized fun consume(input: ByteArray): Chunk = decode(input, endOfInput = false)

    @Synchronized fun finish(): Chunk = decode(ByteArray(0), endOfInput = true)

    @Synchronized fun isTruncated(): Boolean = stopped

    private fun decode(input: ByteArray, endOfInput: Boolean): Chunk {
        if (stopped) return Chunk("", true)
        require(input.size <= MAX_READ_BYTES) { "PTY read chunk exceeds the fixed bound." }
        val bytes = ByteArray(pending.size + input.size)
        pending.copyInto(bytes)
        input.copyInto(bytes, pending.size)
        pending = ByteArray(0)
        val output = StringBuilder(bytes.size.coerceAtMost(4096))
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val length = when {
                first <= 0x7f -> 1
                first in 0xc2..0xdf -> 2
                first in 0xe0..0xef -> 3
                first in 0xf0..0xf4 -> 4
                else -> 0
            }
            if (length > 1 && index + length > bytes.size && !endOfInput) {
                pending = bytes.copyOfRange(index, bytes.size)
                break
            }
            val codePoint = if (length == 0 || index + length > bytes.size) null
                else decodeCodePoint(bytes, index, length)
            val consumed: Int
            val utf8Cost: Int
            if (codePoint == null) {
                consumed = 1
                utf8Cost = REPLACEMENT_UTF8_BYTES
            } else {
                consumed = length
                utf8Cost = length
            }
            if (emittedBytes + utf8Cost > maximumBytes) {
                stopped = true
                pending = ByteArray(0)
                break
            }
            if (codePoint == null) output.append(REPLACEMENT)
            else if (codePoint <= 0xffff) output.append(codePoint.toChar())
            else output.append(Character.toChars(codePoint))
            emittedBytes += utf8Cost
            index += consumed
        }
        return Chunk(output.toString(), stopped)
    }

    private fun decodeCodePoint(bytes: ByteArray, offset: Int, length: Int): Int? {
        fun continuation(at: Int): Int? {
            val value = bytes[at].toInt() and 0xff
            return if (value in 0x80..0xbf) value and 0x3f else null
        }
        if (length == 1) return bytes[offset].toInt() and 0x7f
        val second = continuation(offset + 1) ?: return null
        val first = bytes[offset].toInt() and 0xff
        if (length == 2) return ((first and 0x1f) shl 6) or second
        val third = continuation(offset + 2) ?: return null
        if (first == 0xe0 && second < 0x20 || first == 0xed && second >= 0x20) return null
        if (length == 3) return ((first and 0x0f) shl 12) or (second shl 6) or third
        val fourth = continuation(offset + 3) ?: return null
        if (first == 0xf0 && second < 0x10 || first == 0xf4 && second >= 0x10) return null
        return ((first and 0x07) shl 18) or (second shl 12) or (third shl 6) or fourth
    }

    companion object {
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val MAX_READ_BYTES = 8 * 1024
        private const val REPLACEMENT_UTF8_BYTES = 3
        private const val REPLACEMENT = '�'
    }
}
