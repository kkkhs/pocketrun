package io.github.kkkhs.pocketrun

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenshotCodecTest {
    private fun packet(jpeg: ByteArray = byteArrayOf(1)) = ScreenshotCodec.Packet(7, 720, 1280, 123456L, jpeg)

    @Test fun metadataAndJpegRoundTripTogether() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 0xff.toByte(), 0xd9.toByte())
        val original = packet(jpeg)
        val decoded = ScreenshotCodec.decode(ScreenshotCodec.encode(original), 7)
        assertEquals(original.displayId, decoded.displayId)
        assertEquals(original.width, decoded.width)
        assertEquals(original.height, decoded.height)
        assertEquals(original.frameTimestampNanos, decoded.frameTimestampNanos)
        assertArrayEquals(jpeg, decoded.jpeg)
    }

    @Test fun badHeaderAndLengthAreRejected() {
        val bytes = ScreenshotCodec.encode(packet())
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.decode(bytes.copyOf().apply { this[0] = 0 }) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotCodec.decode(bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(4, 2) })
        }
        for (length in listOf(-1, 0, 2, ScreenshotCodec.MAX_JPEG_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                ScreenshotCodec.decode(bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(28, length) })
            }
        }
    }

    @Test fun mainOrStaleDisplayIdentityIsRejected() {
        val bytes = ScreenshotCodec.encode(packet())
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.decode(bytes, 0) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.decode(bytes, 8) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.encode(packet().copy(displayId = 0)) }
        assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.encode(packet().copy(displayId = -1)) }
    }

    @Test fun dimensionsTimestampAndPayloadAreBounded() {
        for (invalid in listOf(packet().copy(width = 0), packet().copy(height = -1),
            packet().copy(frameTimestampNanos = 0), packet().copy(frameTimestampNanos = -1),
            packet(byteArrayOf()), packet(ByteArray(ScreenshotCodec.MAX_JPEG_BYTES + 1)))) {
            assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.encode(invalid) }
        }
        val max = packet(ByteArray(ScreenshotCodec.MAX_JPEG_BYTES)).copy(frameTimestampNanos = Long.MAX_VALUE)
        val decoded = ScreenshotCodec.decode(ScreenshotCodec.encode(max))
        assertEquals(Long.MAX_VALUE, decoded.frameTimestampNanos)
        assertEquals(ScreenshotCodec.MAX_JPEG_BYTES, decoded.jpeg.size)
    }

    @Test fun incompleteFixedHeaderIsRejected() {
        for (length in 0 until 32) {
            assertThrows(IllegalArgumentException::class.java) { ScreenshotCodec.decode(ByteArray(length)) }
        }
    }
}
