package io.github.kkkhs.pocketrun.taskhost

internal object LinuxCleanupPolicy {
    fun <T : Any> retryOriginal(
        operation: T?,
        retryProcess: (T) -> Boolean,
        recoverRun: () -> Boolean,
    ): Boolean {
        val original = operation ?: return false
        if (!retryProcess(original)) return false
        return recoverRun()
    }
}

internal class RetryableCloseCoordinator {
    private val lock = Any()
    @Volatile var closing = false
        private set
    @Volatile var disposed = false
        private set

    fun close(action: () -> Unit): Unit = synchronized(lock) {
        if (disposed) return
        closing = true
        action() // A failure intentionally leaves closing=true and disposed=false for retry.
        disposed = true
    }
}
