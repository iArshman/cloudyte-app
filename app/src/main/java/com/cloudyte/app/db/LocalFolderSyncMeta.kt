package com.cloudyte.app.db

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
    val lastSyncedAt: Long,
    val providerType: String = "unknown",
    val syncState: String = "IDLE",
    val lastRequestedAt: Long? = null,
    val lastSuccessfulAt: Long? = lastSyncedAt,
    val lastAttemptAt: Long? = null,
    val nextAttemptAt: Long? = null,
    val isCompleteSnapshot: Boolean = false,
    val continuationToken: String? = null,
    val remoteRevision: String? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null
)
