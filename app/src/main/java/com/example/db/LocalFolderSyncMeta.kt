package com.example.db

import androidx.room.Entity

/**
 * Tracks synchronization metadata per (accountKey, folderId) so folder freshness
 * and conditional ETag requests survive across ViewModel instances and app restarts.
 */
@Entity(
    tableName = "folder_sync_meta",
    primaryKeys = ["accountKey", "folderId"]
)
data class LocalFolderSyncMeta(
    val accountKey: String,
    val folderId: String,
    val etag: String?,
    val lastSyncedAt: Long
)
