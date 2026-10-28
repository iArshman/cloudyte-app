package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.api.StorageSummary
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
        val parsedCategories: Map<String, Long>? = categoriesJson
            ?.takeIf { it.isNotBlank() }
            ?.let { raw ->
                try {
                    val json = JSONObject(raw)
                    val map = LinkedHashMap<String, Long>()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = json.optLong(key, 0L)
                    }
                    map
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
            fallbackAccountId: String,
            updatedAt: Long = System.currentTimeMillis()
        ): LocalStorageSummary {
            val resolvedAccountId = summary.accountId.ifBlank { fallbackAccountId }
            val serializedCategories = summary.categories?.let { map ->
                val json = JSONObject()
                map.forEach { (k, v) -> json.put(k, v) }
                json.toString()
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
