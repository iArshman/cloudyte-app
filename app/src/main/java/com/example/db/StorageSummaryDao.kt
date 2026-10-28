package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StorageSummaryDao {
    @Query("SELECT * FROM storage_summary WHERE accountId = :accountId LIMIT 1")
    fun observeByAccountId(accountId: String): Flow<LocalStorageSummary?>

    @Query("SELECT * FROM storage_summary WHERE accountId = :accountId LIMIT 1")
    suspend fun getByAccountId(accountId: String): LocalStorageSummary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: LocalStorageSummary)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: LocalStorageSummary)

    @Query("DELETE FROM storage_summary WHERE accountId = :accountId")
    suspend fun deleteByAccountId(accountId: String)

    @Query("DELETE FROM storage_summary")
    suspend fun clearAll()
}
