package io.github.kkkhs.pocketrun

/** The Native Promise must settle only after its busy slot is released. */
internal class LinuxOperationGate {
    val lock = Any()
    var closed = false
        private set
    var busy = false
        private set
    private var generation = 0L

    fun begin(): Long = synchronized(lock) {
        check(!closed) { "Linux 模块已关闭。" }
        check(!busy) { "请等待当前 Linux 操作结束。" }
        busy = true
        ++generation
    }

    fun complete(epoch: Long): Boolean = synchronized(lock) {
        if (closed || generation != epoch) return false
        busy = false
        true
    }

    fun close() = synchronized(lock) { closed = true; busy = false; generation++ }

    companion object {
        fun supportsRuntime(sdk: Int, app64Bit: Boolean, arm64Supported: Boolean) =
            sdk >= 34 && app64Bit && arm64Supported
    }
}
