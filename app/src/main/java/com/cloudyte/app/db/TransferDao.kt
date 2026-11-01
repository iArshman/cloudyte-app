package com.cloudyte.app.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    fun observeAllTransfers(): Flow<List<LocalTransfer>>

    @Query("SELECT * FROM transfers WHERE status IN ('PENDING', 'RUNNING', 'PAUSED') ORDER BY createdAt DESC")
    fun observeActiveTransfers(): Flow<List<LocalTransfer>>

    @Query("SELECT * FROM transfers WHERE status = :status ORDER BY createdAt DESC")
    fun observeTransfersByStatus(status: String): Flow<List<LocalTransfer>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun getById(id: String): LocalTransfer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: LocalTransfer)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(transfer: LocalTransfer): Long

    @Query("UPDATE transfers SET status='PENDING', direction=:direction, operationType=:operationType, localPath=:localUri, localUri=:localUri, remotePath=:parentFolderId, parentFolderId=:parentFolderId, providerType=:providerType, totalBytes=:totalBytes, errorMessage=NULL, lastErrorCode=NULL, lastErrorMessage=NULL, updatedAt=:now WHERE id=:id AND state='QUEUED'")
    suspend fun queueTransfer(id: String, direction: String, operationType: String, localUri: String, parentFolderId: String?, providerType: String?, totalBytes: Long, now: Long = System.currentTimeMillis()): Int

    @Update
    suspend fun update(transfer: LocalTransfer)

    @Delete
    suspend fun delete(transfer: LocalTransfer)

    @Query("DELETE FROM transfers WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED')")
    suspend fun deleteCompletedTransfers()

    @Query("UPDATE transfers SET status = :status, state = CASE :status WHEN 'PENDING' THEN 'QUEUED' WHEN 'RUNNING' THEN 'RUNNING' WHEN 'PAUSED' THEN 'PAUSED' WHEN 'COMPLETED' THEN 'COMPLETED' WHEN 'FAILED' THEN 'FAILED_RETRYABLE' WHEN 'CANCELLED' THEN 'CANCELLED' ELSE state END, bytesTransferred = :bytes, totalBytes = :total, errorMessage = NULL, lastProgressAt = :updatedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: String, status: String, bytes: Long, total: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfers SET status = 'RUNNING', state = 'RUNNING', totalBytes = :total, lastAttemptAt = :updatedAt, errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markRunning(id: String, total: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfers SET status=:status, state=CASE :status WHEN 'PENDING' THEN 'QUEUED' WHEN 'RUNNING' THEN 'RUNNING' WHEN 'PAUSED' THEN 'PAUSED' WHEN 'COMPLETED' THEN 'COMPLETED' WHEN 'FAILED' THEN 'FAILED_RETRYABLE' WHEN 'CANCELLED' THEN 'CANCELLED' ELSE state END, errorMessage=:error, lastErrorMessage=:error, leaseOwner=CASE WHEN :status IN ('PENDING','PAUSED','CANCELLED','FAILED','COMPLETED') THEN NULL ELSE leaseOwner END, leaseExpiresAt=CASE WHEN :status IN ('PENDING','PAUSED','CANCELLED','FAILED','COMPLETED') THEN NULL ELSE leaseExpiresAt END, nextAttemptAt=CASE WHEN :status='PENDING' THEN NULL ELSE nextAttemptAt END, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateStatus(id: String, status: String, error: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfers SET status = 'PENDING', state = 'QUEUED', leaseOwner = NULL, leaseExpiresAt = NULL, errorMessage = :error, lastErrorCode = 'WORKER_INTERRUPTED', lastErrorMessage = :error, updatedAt = :updatedAt WHERE status = 'RUNNING' AND updatedAt < :staleBefore")
    suspend fun recoverStaleRunning(staleBefore: Long, error: String = "Recovered after interrupted transfer", updatedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE transfers SET providerSessionId = :sessionId, chunkSize = :chunkSize, nextChunkIndex = :nextChunkIndex, checksum = :checksum, idempotencyKey = :idempotencyKey, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateResumeMetadata(
        id: String,
        sessionId: String?,
        chunkSize: Long,
        nextChunkIndex: Int,
        checksum: String?,
        idempotencyKey: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE transfers SET providerSessionId=:sessionId, chunkSize=:chunkSize, nextChunkIndex=:nextChunkIndex, checksum=:checksum, idempotencyKey=:idempotencyKey, updatedAt=:now WHERE id=:id AND state='RUNNING' AND leaseOwner=:leaseOwner AND leaseExpiresAt > :now")
    suspend fun updateResumeMetadataOwned(
        id: String,
        leaseOwner: String,
        sessionId: String?,
        chunkSize: Long,
        nextChunkIndex: Int,
        checksum: String?,
        idempotencyKey: String?,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE transfers SET bytesTransferred = :bytes, nextChunkIndex = :nextChunkIndex, errorMessage = NULL, lastProgressAt = :updatedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateResumableProgress(
        id: String,
        bytes: Long,
        nextChunkIndex: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE transfers SET bytesTransferred=:bytes, nextChunkIndex=:nextChunkIndex, errorMessage=NULL, lastProgressAt=:now, updatedAt=:now WHERE id=:id AND state='RUNNING' AND leaseOwner=:leaseOwner AND leaseExpiresAt > :now")
    suspend fun updateResumableProgressOwned(
        id: String,
        leaseOwner: String,
        bytes: Long,
        nextChunkIndex: Int,
        now: Long = System.currentTimeMillis()
    ): Int

    @Query("UPDATE transfers SET attemptCount = attemptCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementAttempt(id: String, updatedAt: Long = System.currentTimeMillis())
}
