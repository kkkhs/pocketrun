package io.github.kkkhs.pocketrun.taskhost

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class DemoCsvReport(val inputDigest: String, val items: Int, val quantity: Int) {
    init {
        require(inputDigest.length == 64 && inputDigest.all { it in '0'..'9' || it in 'a'..'f' }) { "INVALID_CSV_DIGEST" }
        require(items in 0..MAX_RECORDS && quantity in 0..items * MAX_QUANTITY) { "INVALID_CSV_REPORT" }
    }

    val text: String get() = "items=$items;quantity=$quantity"
    val fileText: String get() = "$text\n"

    companion object {
        const val MAX_BYTES = 1024
        private const val MAX_RECORDS = 32
        private const val MAX_QUANTITY = 1_000_000
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
        private val QUANTITY = Regex("0|[1-9][0-9]*")

        // ponytail: this practice accepts two unquoted columns; use a CSV parser for additional dialects.
        fun parse(bytes: ByteArray): DemoCsvReport {
            require(bytes.size in 1..MAX_BYTES) { "DEMO_CSV_BUDGET" }
            val source = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require('\r' !in source) { "INVALID_DEMO_CSV" }
            val lines = source.removeSuffix("\n").split('\n')
            require(lines.first() == "name,quantity" && lines.size - 1 in 0..MAX_RECORDS) { "INVALID_DEMO_CSV" }
            var total = 0
            for (line in lines.drop(1)) {
                val fields = line.split(',')
                require(fields.size == 2 && NAME.matches(fields[0]) &&
                    fields[1].length in 1..7 && QUANTITY.matches(fields[1])) { "INVALID_DEMO_CSV" }
                val quantity = fields[1].toIntOrNull()
                require(quantity != null && quantity in 0..MAX_QUANTITY) { "INVALID_DEMO_QUANTITY" }
                total = Math.addExact(total, quantity)
            }
            return DemoCsvReport(digest(bytes), lines.size - 1, total)
        }

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
