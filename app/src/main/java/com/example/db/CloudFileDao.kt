package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudFileDao {
    @Query("SELECT * FROM cloud_files WHERE accountId = :accountId AND folderId = :folderId ORDER BY isFolder DESC, time DESC, name ASC")
    fun getFilesForFolderFlow(accountId: String?, folderId: String): Flow<List<LocalCloudFile>>

    @Query("SELECT * FROM cloud_files WHERE accountId = :accountId AND folderId = :folderId ORDER BY isFolder DESC, time DESC, name ASC")
    suspend fun getFilesForFolder(accountId: String?, folderId: String): List<LocalCloudFile>

    @Query("SELECT * FROM cloud_files WHERE accountId = :accountId ORDER BY isFolder DESC, time DESC, name ASC")
    suspend fun getFilesForAccount(accountId: String?): List<LocalCloudFile>

    @Query("SELECT * FROM cloud_files WHERE accountId = :accountId AND name LIKE '%' || :query || '%' ORDER BY isFolder DESC, name ASC")
    suspend fun searchFiles(accountId: String?, query: String): List<LocalCloudFile>

    @Query("SELECT * FROM cloud_files")
    suspend fun getAllFiles(): List<LocalCloudFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<LocalCloudFile>)

    @Query("DELETE FROM cloud_files WHERE accountId = :accountId AND folderId = :folderId")
    suspend fun deleteFilesForFolder(accountId: String?, folderId: String)

    @Query("DELETE FROM cloud_files WHERE accountId = :accountId")
    suspend fun deleteFilesForAccount(accountId: String?)

    @Query("DELETE FROM cloud_files WHERE id = :id")
    suspend fun deleteFileById(id: String)

    @Query("SELECT * FROM cloud_files WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: String): LocalCloudFile?

    @Query("DELETE FROM cloud_files")
    suspend fun clearAll()
}
