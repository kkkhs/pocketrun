package io.github.kkkhs.pocketrun.taskhost

internal data class BackendDeathBinding(val requestNonce: String, val backendEpoch: String)

internal fun DisplayTarget.backendEpoch(): String =
    "display:$displayEpoch:$displayId:$packageUid"

internal object TaskHostBackendDeath {
    fun binding(status: HostStatus, deadTarget: DisplayTarget): BackendDeathBinding? {
        if (status.phase != HostPhase.PENDING_APPROVAL && status.phase != HostPhase.EXECUTING) return null
        val nonce = status.requestNonce ?: return null
        val epoch = deadTarget.backendEpoch()
        if (status.backendEpoch != epoch) return null
        return BackendDeathBinding(nonce, epoch)
    }
}
