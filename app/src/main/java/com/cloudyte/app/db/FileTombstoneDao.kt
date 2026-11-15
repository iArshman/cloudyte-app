package com.cloudyte.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FileTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tombstones: List<LocalFileTombstone>)

    @Query("SELECT * FROM file_tombstones WHERE accountId = :accountId AND folderId = :folderId")
    suspend fun forFolder(accountId: String, folderId: String): List<LocalFileTombstone>

    @Query("DELETE FROM file_tombstones WHERE accountId = :accountId AND folderId = :folderId AND remoteId IN (:remoteIds)")
    suspend fun removeForRemoteIds(accountId: String, folderId: String, remoteIds: List<String>)
}
