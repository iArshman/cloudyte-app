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

    @Update
    suspend fun update(transfer: LocalTransfer)

    @Delete
    suspend fun delete(transfer: LocalTransfer)

    @Query("DELETE FROM transfers WHERE status IN ('COMPLETED', 'CANCELLED', 'FAILED')")
    suspend fun deleteCompletedTransfers()

    @Query("UPDATE transfers SET status = :status, bytesTransferred = :bytes, totalBytes = :total, errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: String, status: String, bytes: Long, total: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfers SET status = :status, errorMessage = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfers SET status = 'PENDING', errorMessage = :error, updatedAt = :updatedAt WHERE status = 'RUNNING' AND updatedAt < :staleBefore")
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

    @Query("UPDATE transfers SET bytesTransferred = :bytes, nextChunkIndex = :nextChunkIndex, errorMessage = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateResumableProgress(
        id: String,
        bytes: Long,
        nextChunkIndex: Int,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE transfers SET attemptCount = attemptCount + 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun incrementAttempt(id: String, updatedAt: Long = System.currentTimeMillis())
}
