package com.cloudyte.app.core.cache

import android.content.Context
import com.cloudyte.app.db.ThumbnailDao
import java.io.File

class CacheQuotaManager(
    context: Context,
    private val thumbnailDao: ThumbnailDao
) {
    private val appContext = context.applicationContext

    suspend fun enforceThumbnailQuota(maxBytes: Long = DEFAULT_THUMBNAIL_QUOTA_BYTES) {
        val now = System.currentTimeMillis()
        thumbnailDao.expired(now - FAILURE_RETENTION_MS).forEach { remove(it) }
        var total = thumbnailDao.totalBytes()
        if (total <= maxBytes) return
        for (entry in thumbnailDao.oldestFirst()) {
            if (total <= maxBytes) break
            total -= entry.sizeBytes.coerceAtLeast(0L)
            remove(entry)
        }
    }

    suspend fun clearAccount(accountId: String) {
        thumbnailDao.oldestFirst()
            .filter { it.accountId == accountId }
            .forEach { remove(it) }
        thumbnailDao.deleteForAccount(accountId)
    }

    private suspend fun remove(entry: com.cloudyte.app.db.ThumbnailEntity) {
        runCatching { File(entry.path).delete() }
        thumbnailDao.delete(entry.cacheKey)
    }

    companion object {
        const val DEFAULT_THUMBNAIL_QUOTA_BYTES = 256L * 1024L * 1024L
        private const val FAILURE_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
