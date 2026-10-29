package com.example.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FolderSyncMetaDao {
    @Query("SELECT * FROM folder_sync_meta WHERE accountKey = :accountKey AND folderId = :folderId LIMIT 1")
    suspend fun getMeta(accountKey: String, folderId: String): LocalFolderSyncMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: LocalFolderSyncMeta)

    @Query("UPDATE folder_sync_meta SET etag = NULL WHERE accountKey = :accountKey AND folderId = :folderId")
    suspend fun clearEtag(accountKey: String, folderId: String)

    @Query("DELETE FROM folder_sync_meta WHERE accountKey = :accountKey AND folderId = :folderId")
    suspend fun deleteForFolder(accountKey: String, folderId: String)

    @Query("DELETE FROM folder_sync_meta WHERE accountKey = :accountKey")
    suspend fun deleteForAccount(accountKey: String)

    @Query("DELETE FROM folder_sync_meta")
    suspend fun clearAll()
}
