package com.cloudyte.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cloudyte.app.api.StorageCategories
import com.cloudyte.app.api.StorageSummary
import org.json.JSONObject

@Entity(tableName = "storage_summary")
data class LocalStorageSummary(
    @PrimaryKey val accountId: String,
    val accountType: String,
    val usedBytes: Long,
    val totalBytes: Long?,
    val fileCount: Int?,
    val categoriesJson: String?,
    val updatedAt: Long
) {
    fun toStorageSummary(): StorageSummary {
        val parsedCategories: StorageCategories? = categoriesJson
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                try {
                    val json = JSONObject(raw)
                    StorageCategories(
                        images = json.optLong("images", 0L),
                        documents = json.optLong("documents", 0L),
                        videos = json.optLong("videos", 0L),
                        archives = json.optLong("archives", 0L),
                        audio = json.optLong("audio", 0L),
                        other = json.optLong("other", 0L)
                    )
                } catch (_: Exception) {
                    null
                }
            }

        return StorageSummary(
            accountId = accountId,
            accountType = accountType,
            usedBytes = usedBytes,
            totalBytes = totalBytes,
            fileCount = fileCount,
            categories = parsedCategories
        )
    }

    companion object {
        fun fromStorageSummary(
            summary: StorageSummary,
            fallbackAccountId: String = "",
            updatedAt: Long = System.currentTimeMillis()
        ): LocalStorageSummary {
            val resolvedAccountId = summary.accountId.ifBlank { fallbackAccountId.ifBlank { "all" } }
            val serializedCategories = summary.categories?.let { cats ->
                JSONObject().apply {
                    put("images", cats.images)
                    put("documents", cats.documents)
                    put("videos", cats.videos)
                    put("archives", cats.archives)
                    put("audio", cats.audio)
                    put("other", cats.other)
                }.toString()
            }
            return LocalStorageSummary(
                accountId = resolvedAccountId,
                accountType = summary.accountType,
                usedBytes = summary.usedBytes,
                totalBytes = summary.totalBytes,
                fileCount = summary.fileCount,
                categoriesJson = serializedCategories,
                updatedAt = updatedAt
            )
        }
    }
}
