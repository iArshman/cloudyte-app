package com.cloudyte.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cloudyte.app.core.transfer.TransferState

@Entity(
    tableName = "transfers",
    indices = [
        Index(value = ["status", "updatedAt"]),
        Index(value = ["accountId", "createdAt"]),
        Index(value = ["idempotencyKey"]),
        Index(value = ["state", "priority", "createdAt"]),
        Index(value = ["leaseExpiresAt"]),
        Index(value = ["providerType", "remoteId"])
    ]
)
data class LocalTransfer(
    @PrimaryKey val id: String,
    val accountId: String,
    val fileId: String? = null,
    val fileName: String,
    val localPath: String? = null,
    val remotePath: String? = null,
    val direction: String = TransferDirection.DOWNLOAD.name,
    /** Legacy UI/API status retained during the staged migration. */
    val status: String = TransferStatus.PENDING.name,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val providerSessionId: String? = null,
    val chunkSize: Long = 0L,
    val nextChunkIndex: Int = 0,
    val checksum: String? = null,
    val idempotencyKey: String? = null,
    val attemptCount: Int = 0,
    val state: String = TransferState.QUEUED.name,
    val priority: Int = 0,
    val operationType: String = direction,
    val providerType: String? = null,
    val remoteId: String? = null,
    val parentFolderId: String? = null,
    val localUri: String? = null,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val lastProgressAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val nextAttemptAt: Long? = null,
    val leaseOwner: String? = null,
    val leaseExpiresAt: Long? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val lastHttpStatus: Int? = null,
    val correlationId: String? = null,
    val isEncrypted: Boolean = false,
    val encryptionFormatVersion: Int? = null,
    val encryptionKeyId: String? = null,
    val encryptedMetadata: String? = null,
    val encryptedSizeBytes: Long? = null,
    val encryptedChecksum: String? = null,
    val opaqueRemoteName: String? = null,
    val encryptedStagePath: String? = null,
    val plaintextSizeBytes: Long? = null,
    val originalMetadataAvailable: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val encryptionEnabled: Boolean
        get() = isEncrypted
}

enum class TransferDirection { UPLOAD, DOWNLOAD, BACKUP }
enum class TransferStatus { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
