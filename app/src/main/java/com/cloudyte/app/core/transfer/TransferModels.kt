package com.cloudyte.app.core.transfer

import com.cloudyte.app.core.common.AppError

sealed interface TransferExecutionState {
    data object Pending : TransferExecutionState
    data class Running(val progress: TransferProgress) : TransferExecutionState
    data object Paused : TransferExecutionState
    data class Failed(val message: String, val canRetry: Boolean = true) : TransferExecutionState
    data object Completed : TransferExecutionState
    data object Cancelled : TransferExecutionState
}

sealed interface TransferCommand {
    val transferId: String
    val diagnosticName: String
    data class Start(override val transferId: String) : TransferCommand {
        override val diagnosticName: String get() = "Start"
    }
    data class Retry(override val transferId: String) : TransferCommand {
        override val diagnosticName: String get() = "Retry"
    }
    data class Pause(override val transferId: String) : TransferCommand {
        override val diagnosticName: String get() = "Pause"
    }
    data class Resume(override val transferId: String) : TransferCommand {
        override val diagnosticName: String get() = "Resume"
    }
    data class Cancel(override val transferId: String) : TransferCommand {
        override val diagnosticName: String get() = "Cancel"
    }
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

data class TransferProgressSnapshot(
    val transferId: String,
    val state: TransferState,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val currentPart: Int? = null,
    val totalParts: Int? = null,
    val lastCheckpointAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val error: AppError? = null
)
