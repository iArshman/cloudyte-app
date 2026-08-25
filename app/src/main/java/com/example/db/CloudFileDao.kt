package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CloudFileDao {
    @Query("SELECT * FROM cloud_files WHERE accountId = :accountId")
    suspend fun getFilesForAccount(accountId: String?): List<LocalCloudFile>

    @Query("SELECT * FROM cloud_files")
    suspend fun getAllFiles(): List<LocalCloudFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<LocalCloudFile>)

    @Query("DELETE FROM cloud_files WHERE accountId = :accountId")
    suspend fun deleteFilesForAccount(accountId: String?)

    @Query("SELECT * FROM cloud_files WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: String): LocalCloudFile?
}
