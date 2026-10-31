package com.cloudyte.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FileMutationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mutation: LocalFileMutation)

    @Query("SELECT * FROM file_mutations WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun pending(): List<LocalFileMutation>

    @Query("SELECT * FROM file_mutations WHERE status = 'PENDING' AND accountId IS :accountId AND parentFolderId = :parentFolderId ORDER BY createdAt ASC")
    suspend fun pendingForFolder(accountId: String?, parentFolderId: String): List<LocalFileMutation>

    @Query("SELECT EXISTS(SELECT 1 FROM file_mutations WHERE status = 'PENDING' AND operation = 'DELETE' AND fileId = :fileId)")
    suspend fun hasPendingDelete(fileId: String): Boolean

    @Query("DELETE FROM file_mutations WHERE operationId = :operationId")
    suspend fun remove(operationId: String)

    @Query("UPDATE file_mutations SET retryCount = retryCount + 1, lastError = :error, updatedAt = :updatedAt WHERE operationId = :operationId")
    suspend fun recordRetry(operationId: String, error: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE file_mutations SET status = 'FAILED', lastError = :error, updatedAt = :updatedAt WHERE operationId = :operationId")
    suspend fun markPermanent(operationId: String, error: String?, updatedAt: Long = System.currentTimeMillis())
}
