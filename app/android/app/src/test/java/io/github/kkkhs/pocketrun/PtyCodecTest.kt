package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test

class PtyCodecTest {
    @Test fun splitMultibyteSequencesAreHeldWithoutPrematureReplacement() {
        val codec = PtyCodec()
        val encoded = "A€😀Z".toByteArray()
        assertEquals("A", codec.consume(encoded.copyOfRange(0, 2)).text)
        assertEquals("€", codec.consume(encoded.copyOfRange(2, 5)).text)
        assertEquals("😀Z", codec.consume(encoded.copyOfRange(5, encoded.size)).text)
        assertEquals("", codec.finish().text)
        assertFalse(codec.isTruncated())
    }

    @Test fun incompleteAndMalformedInputUsesBoundedReplacementEncoding() {
        val codec = PtyCodec(12)
        assertEquals("", codec.consume(byteArrayOf(0xe2.toByte())).text)
        assertEquals("�", codec.finish().text)
        val malformed = PtyCodec(12)
        val result = malformed.consume(ByteArray(20) { 0x80.toByte() })
        assertEquals(12, result.text.toByteArray().size)
        assertTrue(result.truncated)
        assertTrue(malformed.finish().truncated)
        assertEquals("", malformed.finish().text)
    }

    @Test fun lifetimeLimitNeverSplitsAValidCodePointOrExpandsPast64KiB() {
        val codec = PtyCodec()
        val chunk = "😀".repeat(PtyCodec.MAX_OUTPUT_BYTES / 4).toByteArray()
        assertEquals(PtyCodec.MAX_OUTPUT_BYTES, chunk.size)
        val output = StringBuilder()
        var complete = PtyCodec.Chunk("", false)
        chunk.asList().chunked(PtyCodec.MAX_READ_BYTES).forEach { part ->
            complete = codec.consume(part.toByteArray())
            output.append(complete.text)
        }
        assertEquals(PtyCodec.MAX_OUTPUT_BYTES, output.toString().toByteArray().size)
        assertFalse(complete.truncated)
        val stopped = codec.consume(byteArrayOf('x'.code.toByte()))
        assertTrue(stopped.truncated)
        assertEquals("", stopped.text)
        assertTrue(output.last().isLowSurrogate())
    }

    @Test fun overlongSurrogateAndOutOfRangeFormsAreNeverDecodedAsValidUnicode() {
        val invalid = byteArrayOf(
            0xc0.toByte(), 0x80.toByte(),
            0xed.toByte(), 0xa0.toByte(), 0x80.toByte(),
            0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte(),
        )
        val text = PtyCodec().consume(invalid).text
        assertFalse(text.contains('\u0000'))
        assertTrue(text.all { it == '�' })
    }

    @Test fun individualNativeReadChunksAreBounded() {
        val codec = PtyCodec()
        try {
            codec.consume(ByteArray(PtyCodec.MAX_READ_BYTES + 1))
            fail("oversized native read must fail")
        } catch (_: IllegalArgumentException) {}
    }
}
