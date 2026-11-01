package com.cloudyte.app.core.transfer

data class TransferExecutionLease(
    val transferId: String,
    val ownerId: String,
    val acquiredAt: Long,
    val expiresAt: Long
)
