package com.cloudyte.app.core.transfer

sealed interface TransferState {
    data object Pending : TransferState
    data class Running(val progress: TransferProgress) : TransferState
    data object Paused : TransferState
    data class Failed(val message: String, val canRetry: Boolean = true) : TransferState
    data object Completed : TransferState
    data object Cancelled : TransferState
}

sealed interface TransferCommand {
    val transferId: String

    data class Start(override val transferId: String) : TransferCommand
    data class Retry(override val transferId: String) : TransferCommand
    data class Pause(override val transferId: String) : TransferCommand
    data class Resume(override val transferId: String) : TransferCommand
    data class Cancel(override val transferId: String) : TransferCommand
}

data class TransferProgress(
    val transferId: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val status: String
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let {
            (bytesTransferred.toFloat() / it.toFloat()).coerceIn(0f, 1f)
        }
}
