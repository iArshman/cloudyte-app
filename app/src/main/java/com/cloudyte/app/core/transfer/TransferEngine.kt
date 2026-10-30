package com.cloudyte.app.core.transfer

interface TransferEngine {
    suspend fun execute(command: TransferCommand): TransferState
}
