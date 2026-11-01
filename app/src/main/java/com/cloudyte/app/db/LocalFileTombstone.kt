package com.cloudyte.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_tombstones",
    indices = [Index(value = ["accountId", "providerType", "folderId"]), Index(value = ["remoteId"])]
)
data class LocalFileTombstone(
    @PrimaryKey val tombstoneId: String,
    val accountId: String,
    val providerType: String,
    val remoteId: String,
    val folderId: String?,
    val deletedAt: Long,
    val sourceRevision: String? = null
)
