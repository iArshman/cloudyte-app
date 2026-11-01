package com.cloudyte.app.data

import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferDao
import com.cloudyte.app.db.TransferDirection
import com.cloudyte.app.db.TransferStatus
import com.cloudyte.app.data.providers.ProviderRegistry
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class TransferRepository(
    private val transferDao: TransferDao,
    private val providers: ProviderRegistry? = null
) {

    companion object {
        const val STALE_TRANSFER_TIMEOUT_MS = 15 * 60 * 1000L
    }

    fun observeAll(): Flow<List<LocalTransfer>> = transferDao.observeAllTransfers()

    fun observeActive(): Flow<List<LocalTransfer>> = transferDao.observeActiveTransfers()

    fun observeByStatus(status: String): Flow<List<LocalTransfer>> = transferDao.observeTransfersByStatus(status)

    suspend fun enqueueTransfer(
        accountId: String,
        fileName: String,
        localPath: String? = null,
        remotePath: String? = null,
        direction: TransferDirection = TransferDirection.DOWNLOAD,
        totalBytes: Long = 0L,
        fileId: String? = null
    ): String {
        val id = UUID.randomUUID().toString()
        val transfer = LocalTransfer(
            id = id,
            accountId = accountId,
            fileId = fileId,
            fileName = fileName,
            localPath = localPath,
            remotePath = remotePath,
            direction = direction.name,
            operationType = if (direction == TransferDirection.BACKUP) "BACKUP" else direction.name,
            localUri = localPath,
            parentFolderId = remotePath,
            remoteId = fileId,
            status = TransferStatus.PENDING.name,
            bytesTransferred = 0L,
            totalBytes = totalBytes,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        transferDao.insert(transfer)
        return id
    }

    suspend fun queueTransfer(id: String, direction: TransferDirection, localUri: String, parentFolderId: String?, providerType: String?, totalBytes: Long): Boolean =
        transferDao.queueTransfer(id, direction.name, if (direction == TransferDirection.BACKUP) "BACKUP" else direction.name, localUri, parentFolderId, providerType, totalBytes) == 1

    suspend fun enqueueOrGetOfflineDownload(accountId: String, fileId: String, fileName: String, destinationPath: String, totalBytes: Long): String {
        val stableId = "offline:$accountId:$fileId"
        val existing = transferDao.getById(stableId)
        if (existing != null && existing.state !in setOf("COMPLETED", "CANCELLED", "EXPIRED", "FAILED_PERMANENT")) return stableId
        val id = if (existing == null) stableId else "$stableId:${UUID.randomUUID()}"
        transferDao.insertIfAbsent(
            LocalTransfer(
                id = id,
                accountId = accountId,
                fileId = fileId,
                remoteId = fileId,
                fileName = fileName,
                localPath = destinationPath,
                localUri = destinationPath,
                direction = TransferDirection.DOWNLOAD.name,
                operationType = "DOWNLOAD",
                totalBytes = totalBytes
            )
        )
        return id
    }

    suspend fun pauseTransfer(id: String) {
        val transfer = transferDao.getById(id) ?: return
        if (transfer.status == TransferStatus.RUNNING.name || transfer.status == TransferStatus.PENDING.name) {
            transferDao.updateStatus(id, TransferStatus.PAUSED.name)
        }
    }

    suspend fun resumeTransfer(id: String) {
        val transfer = transferDao.getById(id) ?: return
        if (transfer.status == TransferStatus.PAUSED.name || transfer.status == TransferStatus.FAILED.name) {
            transferDao.updateStatus(id, TransferStatus.PENDING.name)
        }
    }

    suspend fun retryTransfer(id: String) {
        val transfer = transferDao.getById(id) ?: return
        if (transfer.state in setOf("FAILED_RETRYABLE", "WAITING_FOR_AUTH", "WAITING_FOR_NETWORK")) {
            transferDao.updateStatus(id, TransferStatus.PENDING.name, error = null)
            transferDao.incrementAttempt(id)
        }
    }

    suspend fun cancelTransfer(id: String) {
        val transfer = transferDao.getById(id) ?: return
        if (transfer.state in setOf("COMPLETED", "CANCELLED", "EXPIRED", "FAILED_PERMANENT")) return
        val sessionId = transfer.providerSessionId
        val providerRegistry = providers
        if (!sessionId.isNullOrBlank() && providerRegistry != null && transfer.direction != TransferDirection.DOWNLOAD.name) {
            try {
                val provider = providerRegistry.forAccountType(transfer.providerType)
                val response = provider.cancelUploadSession(sessionId)
                // Do not claim cancellation is terminal when the provider/server could not
                // confirm that its session stopped; leave the row visible for retry/recovery.
                if (!response.isSuccessful) return
            } catch (_: Exception) {
                return
            }
        }
        transferDao.updateStatus(id, TransferStatus.CANCELLED.name, error = "Cancelled")
    }

    suspend fun clearCompleted() {
        transferDao.deleteCompletedTransfers()
    }

    suspend fun updateProgress(id: String, bytesTransferred: Long, totalBytes: Long) {
        transferDao.updateProgress(
            id = id,
            status = TransferStatus.RUNNING.name,
            bytes = bytesTransferred,
            total = totalBytes
        )
    }

    suspend fun markRunning(id: String, totalBytes: Long) {
        transferDao.markRunning(id, totalBytes)
    }

    suspend fun recoverStaleTransfers(now: Long = System.currentTimeMillis()): Int =
        transferDao.recoverStaleRunning(now - STALE_TRANSFER_TIMEOUT_MS)

    suspend fun markCancelled(id: String) {
        transferDao.updateStatus(id, TransferStatus.CANCELLED.name, error = "Cancelled")
    }

    suspend fun markCompleted(id: String) {
        val transfer = transferDao.getById(id) ?: return
        transferDao.updateProgress(
            id = id,
            status = TransferStatus.COMPLETED.name,
            bytes = if (transfer.totalBytes > 0) transfer.totalBytes else transfer.bytesTransferred,
            total = if (transfer.totalBytes > 0) transfer.totalBytes else transfer.bytesTransferred
        )
    }

    suspend fun markFailed(id: String, errorMessage: String?) {
        transferDao.updateStatus(id, TransferStatus.FAILED.name, error = errorMessage)
    }

    suspend fun saveResumeMetadata(
        id: String,
        sessionId: String?,
        chunkSize: Long,
        nextChunkIndex: Int,
        checksum: String?,
        idempotencyKey: String?
    ) {
        transferDao.updateResumeMetadata(
            id = id,
            sessionId = sessionId,
            chunkSize = chunkSize,
            nextChunkIndex = nextChunkIndex,
            checksum = checksum,
            idempotencyKey = idempotencyKey
        )
    }

    suspend fun saveResumeMetadataOwned(
        id: String,
        leaseOwner: String,
        sessionId: String?,
        chunkSize: Long,
        nextChunkIndex: Int,
        checksum: String?,
        idempotencyKey: String?
    ): Boolean = transferDao.updateResumeMetadataOwned(
        id, leaseOwner, sessionId, chunkSize, nextChunkIndex, checksum, idempotencyKey
    ) == 1

    suspend fun updateResumableProgress(id: String, bytesTransferred: Long, nextChunkIndex: Int) {
        transferDao.updateResumableProgress(id, bytesTransferred, nextChunkIndex)
    }

    suspend fun updateResumableProgressOwned(
        id: String,
        leaseOwner: String,
        bytesTransferred: Long,
        nextChunkIndex: Int
    ): Boolean = transferDao.updateResumableProgressOwned(
        id, leaseOwner, bytesTransferred, nextChunkIndex
    ) == 1
}
