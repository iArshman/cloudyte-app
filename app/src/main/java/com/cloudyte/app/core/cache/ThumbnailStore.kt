package com.cloudyte.app.core.cache

import android.content.Context
import android.graphics.BitmapFactory
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.db.ThumbnailDao
import com.cloudyte.app.db.ThumbnailEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Single thumbnail policy boundary for UI and background callers.
 * Returned strings are either app-private file paths or a compatibility remote fallback URL.
 */
class ThumbnailStore(
    context: Context,
    private val dao: ThumbnailDao
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "cloudyte_thumbnails").apply { mkdirs() }
    private val quotaManager = CacheQuotaManager(appContext, dao)
    private val requestMutex = Mutex()

    suspend fun resolve(
        accountId: String,
        providerType: String,
        file: CloudFile,
        sizePx: Int = 256,
        localSource: File? = null
    ): String? = withContext(Dispatchers.IO) {
        if (accountId.isBlank() || file.effectiveId.isBlank() || file.isFolderItem) return@withContext null
        val normalizedSize = sizePx.coerceIn(64, 512)
        val key = CacheKeyFactory.thumbnail(accountId, providerType, file, normalizedSize)
        val fallback = fallbackUrl(accountId, file)
        requestMutex.withLock {
            val now = System.currentTimeMillis()
            val existing = dao.get(key)
            if (existing != null) {
                val cached = File(existing.path)
                if (cached.isFile && cached.length() > 0L) {
                    if (now - existing.lastAccessAt >= ACCESS_TOUCH_INTERVAL_MS) dao.touch(key, now)
                    return@withLock cached.absolutePath
                }
                dao.delete(key)
            }

            val failed = existing
            if (failed?.nextRetryAt?.let { it > now } == true) return@withLock fallback

            try {
                val generated = localSource?.takeIf { it.isFile }?.let {
                    generateFromLocal(it, key, accountId, providerType, file, normalizedSize, now)
                }
                generated ?: downloadRemote(
                    sourceUrl = providerThumbUrl(accountId, file, normalizedSize),
                    key = key,
                    accountId = accountId,
                    providerType = providerType,
                    file = file,
                    sizePx = normalizedSize,
                    now = now
                )
            } catch (_: Exception) {
                val count = (failed?.failureCount ?: 0) + 1
                dao.recordFailure(key, count, now + backoffMs(count))
                fallback
            }
        }
    }

    suspend fun clearAccount(accountId: String) = withContext(Dispatchers.IO) {
        quotaManager.clearAccount(accountId)
    }

    suspend fun enforceQuota(maxBytes: Long = CacheQuotaManager.DEFAULT_THUMBNAIL_QUOTA_BYTES) =
        quotaManager.enforceThumbnailQuota(maxBytes)

    private suspend fun generateFromLocal(
        source: File,
        key: String,
        accountId: String,
        providerType: String,
        file: CloudFile,
        sizePx: Int,
        now: Long
    ): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: return null
        val target = targetFile(key)
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.outputStream().use { output ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output))
        }
        bitmap.recycle()
        check(temp.renameTo(target)) { "Unable to commit generated thumbnail" }
        dao.upsert(entity(key, target, accountId, providerType, file, target.length(), bounds.outWidth, bounds.outHeight, now))
        return target.absolutePath
    }

    private suspend fun downloadRemote(
        sourceUrl: String,
        key: String,
        accountId: String,
        providerType: String,
        file: CloudFile,
        sizePx: Int,
        now: Long
    ): String? {
        val request = Request.Builder().url(sourceUrl).build()
        ApiClient.okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("thumbnail HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: error("empty thumbnail response")
            if (bytes.isEmpty()) error("empty thumbnail response")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val target = targetFile(key)
            val temp = File(target.parentFile, ".${target.name}.tmp")
            temp.outputStream().use { it.write(bytes) }
            check(temp.renameTo(target)) { "Unable to commit downloaded thumbnail" }
            dao.upsert(entity(key, target, accountId, providerType, file, bytes.size.toLong(), bounds.outWidth.takeIf { it > 0 }, bounds.outHeight.takeIf { it > 0 }, now))
            return target.absolutePath
        }
    }

    private fun entity(
        key: String,
        target: File,
        accountId: String,
        providerType: String,
        file: CloudFile,
        sizeBytes: Long,
        width: Int?,
        height: Int?,
        now: Long
    ) = ThumbnailEntity(
        cacheKey = key,
        accountId = accountId,
        providerType = providerType,
        remoteId = file.effectiveId,
        sourceRevision = file.updatedAt?.toString() ?: file.time?.toString(),
        path = target.absolutePath,
        mimeType = file.mimeType ?: "image/jpeg",
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        generatedAt = now,
        lastAccessAt = now
    )

    private fun targetFile(key: String): File = File(root, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg")

    private fun providerThumbUrl(accountId: String, file: CloudFile, sizePx: Int): String {
        val remote = file.thumbUrl
        if (!remote.isNullOrBlank()) {
            return if (remote.startsWith("http://") || remote.startsWith("https://")) remote
            else "${ApiClient.API_BASE_URL}/${remote.trimStart('/')}"
        }
        return ApiClient.getThumbUrl(accountId, file.effectiveId, sizePx)
    }

    private fun fallbackUrl(accountId: String, file: CloudFile): String? =
        if (file.isImage || file.isVideo) ApiClient.getFileUrl(accountId, file.effectiveId, preview = true) else null

    private fun backoffMs(failureCount: Int): Long =
        (5_000L * (1L shl failureCount.coerceIn(0, 6))).coerceAtMost(60L * 60L * 1000L)

    companion object {
        private const val ACCESS_TOUCH_INTERVAL_MS = 60_000L
    }
}
