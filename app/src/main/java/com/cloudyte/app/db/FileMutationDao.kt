package com.cloudyte.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FileMutationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mutation: LocalFileMutation)

    @Query("SELECT * FROM file_mutations WHERE status = 'PENDING' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now) ORDER BY accountId ASC, createdAt ASC")
    suspend fun pendingDue(now: Long = System.currentTimeMillis()): List<LocalFileMutation>

    suspend fun pending(): List<LocalFileMutation> = pendingDue()

    @Query("SELECT * FROM file_mutations WHERE status IN ('PENDING','RUNNING','WAITING_FOR_AUTH') AND accountId IS :accountId AND parentFolderId = :parentFolderId ORDER BY createdAt ASC")
    suspend fun pendingForFolder(accountId: String?, parentFolderId: String): List<LocalFileMutation>

    @Query("SELECT EXISTS(SELECT 1 FROM file_mutations WHERE status IN ('PENDING','RUNNING','CONFLICT') AND operation = 'DELETE' AND fileId = :fileId)")
    suspend fun hasPendingDelete(fileId: String): Boolean

    @Query("UPDATE file_mutations SET status = 'RUNNING', attemptCount = attemptCount + 1, retryCount = retryCount + 1, updatedAt = :now WHERE operationId = :operationId AND status = 'PENDING' AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)")
    suspend fun claim(operationId: String, now: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM file_mutations WHERE operationId = :operationId")
    suspend fun remove(operationId: String)

    @Query("UPDATE file_mutations SET status = 'PENDING', nextAttemptAt = :nextAttemptAt, lastError = :message, lastErrorCode = :code, lastErrorMessage = :message, updatedAt = :now WHERE operationId = :operationId AND status = 'RUNNING'")
    suspend fun recordRetry(operationId: String, message: String?, code: String? = "RETRYABLE", nextAttemptAt: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE file_mutations SET status = 'CONFLICT', lastError = :message, lastErrorCode = :code, lastErrorMessage = :message, updatedAt = :now WHERE operationId = :operationId")
    suspend fun markConflict(operationId: String, message: String?, code: String = "CONFLICT", now: Long = System.currentTimeMillis())

    @Query("UPDATE file_mutations SET status = 'FAILED_PERMANENT', lastError = :message, lastErrorCode = :code, lastErrorMessage = :message, updatedAt = :now WHERE operationId = :operationId")
    suspend fun markPermanent(operationId: String, message: String?, code: String = "VALIDATION_ERROR", now: Long = System.currentTimeMillis())

    @Query("UPDATE file_mutations SET status = 'WAITING_FOR_AUTH', nextAttemptAt = NULL, lastErrorCode = 'AUTH_REQUIRED', lastErrorMessage = :message, updatedAt = :now WHERE accountId IS :accountId AND status = 'RUNNING'")
    suspend fun pauseAccount(accountId: String?, message: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE file_mutations SET status = 'PENDING', nextAttemptAt = :now, lastErrorCode = NULL, lastErrorMessage = NULL, updatedAt = :now WHERE accountId IS :accountId AND status = 'WAITING_FOR_AUTH'")
    suspend fun resumeAccount(accountId: String?, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM file_mutations WHERE operationId = :operationId LIMIT 1")
    suspend fun get(operationId: String): LocalFileMutation?
}
