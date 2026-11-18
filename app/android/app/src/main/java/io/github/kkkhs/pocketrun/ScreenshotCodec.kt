package io.github.kkkhs.pocketrun

import android.os.SharedMemory
import android.system.OsConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ScreenshotCodec {
    private const val MAGIC = 0x50525353
    private const val VERSION = 1
    private const val HEADER_BYTES = 32
    const val MAX_JPEG_BYTES = 2 * 1024 * 1024

    data class Packet(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val frameTimestampNanos: Long,
        val jpeg: ByteArray,
    )

    fun encode(packet: Packet): ByteArray {
        require(packet.displayId > 0 && packet.width > 0 && packet.height > 0) { "截图元数据无效。" }
        require(packet.frameTimestampNanos > 0) { "截图时间戳无效。" }
        require(packet.jpeg.size in 1..MAX_JPEG_BYTES) { "截图数据长度无效。" }
        return ByteBuffer.allocate(HEADER_BYTES + packet.jpeg.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC)
            .putInt(VERSION)
            .putInt(packet.displayId)
            .putInt(packet.width)
            .putInt(packet.height)
            .putLong(packet.frameTimestampNanos)
            .putInt(packet.jpeg.size)
            .put(packet.jpeg)
            .array()
    }

    fun decode(bytes: ByteArray): Packet {
        require(bytes.size >= HEADER_BYTES) { "截图数据过短。" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC && buffer.int == VERSION) { "截图数据格式无效。" }
        val displayId = buffer.int
        val width = buffer.int
        val height = buffer.int
        val timestamp = buffer.long
        val length = buffer.int
        require(displayId > 0 && width > 0 && height > 0 && timestamp > 0) { "截图元数据无效。" }
        require(length in 1..MAX_JPEG_BYTES && length == buffer.remaining()) { "截图数据长度无效。" }
        return Packet(displayId, width, height, timestamp, ByteArray(length).also(buffer::get))
    }

    fun decode(bytes: ByteArray, expectedDisplayId: Int): Packet {
        require(expectedDisplayId > 0) { "截图 expectedDisplayId 无效。" }
        return decode(bytes).also { require(it.displayId == expectedDisplayId) { "截图 displayId 不匹配。" } }
    }

    fun toSharedMemory(packet: Packet): SharedMemory {
        val bytes = encode(packet)
        val memory = SharedMemory.create("pocketrun-screenshot", bytes.size)
        try {
            val buffer = memory.mapReadWrite()
            try {
                buffer.put(bytes)
            } finally {
                SharedMemory.unmap(buffer)
            }
            check(memory.setProtect(OsConstants.PROT_READ)) { "无法锁定截图共享内存。" }
            return memory
        } catch (error: Throwable) {
            memory.close()
            throw error
        }
    }

    fun fromSharedMemory(memory: SharedMemory): Packet {
        require(memory.size in HEADER_BYTES + 1..HEADER_BYTES + MAX_JPEG_BYTES) { "截图共享内存长度无效。" }
        val buffer = memory.mapReadOnly()
        return try {
            ByteArray(memory.size).also(buffer::get).let(::decode)
        } finally {
            SharedMemory.unmap(buffer)
        }
    }

    fun fromSharedMemory(memory: SharedMemory, expectedDisplayId: Int): Packet {
        require(expectedDisplayId > 0) { "截图 expectedDisplayId 无效。" }
        return fromSharedMemory(memory).also {
            require(it.displayId == expectedDisplayId) { "截图 displayId 不匹配。" }
        }
    }
}
