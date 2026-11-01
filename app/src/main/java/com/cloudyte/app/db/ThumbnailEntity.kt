package com.cloudyte.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "thumbnail_cache",
    indices = [
        Index(value = ["accountId", "lastAccessAt"]),
        Index(value = ["providerType", "remoteId"]),
        Index(value = ["lastAccessAt"])
    ]
)
data class ThumbnailEntity(
    @PrimaryKey val cacheKey: String,
    val accountId: String,
    val providerType: String,
    val remoteId: String,
    val sourceRevision: String? = null,
    val path: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long,
    val isEncrypted: Boolean = false,
    val generatedAt: Long,
    val lastAccessAt: Long,
    val failureCount: Int = 0,
    val nextRetryAt: Long? = null
)
