package com.example.data

import com.example.api.StorageCategories
import com.example.api.StorageSummary
import java.util.Locale

const val ALL_ACCOUNTS_STORAGE_ID = "all"

enum class StorageCategoryType(val label: String) {
    IMAGES("Images"),
    DOCUMENTS("Documents"),
    VIDEOS("Videos"),
    ARCHIVES("Archives"),
    AUDIO("Audio"),
    OTHER("Other")
}

data class StorageSegment(
    val category: StorageCategoryType,
    val bytes: Long,
    val proportion: Float
)

/**
 * Formats byte counts with one decimal place for KB, MB, GB, TB, and integer B for < 1024.
 */
fun formatStorageSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    val tb = gb * 1024.0
    return when {
        bytes >= tb -> String.format(Locale.US, "%.1f TB", bytes / tb)
        bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

/**
 * Formats the usage headline for the Storage card:
 * - "52.0 GB of 64.0 GB used" when totalBytes is known and finite
 * - "12.4 GB used · Unlimited" when totalBytes is null/0 or the drive is unlimited
 */
fun formatStorageUsageText(summary: StorageSummary): String {
    val usedFormatted = formatStorageSize(summary.usedBytes)
    val total = summary.totalBytes
    return if (!summary.isUnlimited && total != null && total > 0L) {
        "$usedFormatted of ${formatStorageSize(total)} used"
    } else {
        "$usedFormatted used · Unlimited"
    }
}

/**
 * Calculates the 6 category segments in proportion to usedBytes (or total category bytes if larger).
 * Returns all 6 categories in canonical display order:
 * Images, Documents, Videos, Archives, Audio, Other.
 */
fun calculateSegmentProportions(
    categories: StorageCategories,
    usedBytes: Long
): List<StorageSegment> {
    val categorySum = categories.totalCategoryBytes
    val denominator = when {
        usedBytes >= categorySum && usedBytes > 0L -> usedBytes
        categorySum > 0L -> categorySum
        else -> 0L
    }

    fun prop(value: Long): Float {
        if (denominator <= 0L || value <= 0L) return 0f
        return (value.toDouble() / denominator.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    return listOf(
        StorageSegment(StorageCategoryType.IMAGES, categories.images, prop(categories.images)),
        StorageSegment(StorageCategoryType.DOCUMENTS, categories.documents, prop(categories.documents)),
        StorageSegment(StorageCategoryType.VIDEOS, categories.videos, prop(categories.videos)),
        StorageSegment(StorageCategoryType.ARCHIVES, categories.archives, prop(categories.archives)),
        StorageSegment(StorageCategoryType.AUDIO, categories.audio, prop(categories.audio)),
        StorageSegment(StorageCategoryType.OTHER, categories.other, prop(categories.other))
    )
}

/**
 * Sums usedBytes and sums categories across accounts for the "All accounts" view,
 * marking totalBytes as null (Unlimited) when any account is unlimited.
 */
fun aggregateStorageSummaries(summaries: List<StorageSummary>): StorageSummary {
    if (summaries.isEmpty()) {
        return StorageSummary(
            accountId = ALL_ACCOUNTS_STORAGE_ID,
            accountType = "all",
            usedBytes = 0L,
            totalBytes = null,
            fileCount = 0,
            categories = null
        )
    }

    val totalUsed = summaries.sumOf { it.usedBytes.coerceAtLeast(0L) }
    val anyUnlimited = summaries.any { it.isUnlimited }
    val totalCapacity = if (anyUnlimited) {
        null
    } else {
        summaries.sumOf { (it.totalBytes ?: 0L).coerceAtLeast(0L) }
    }

    val totalFileCount = if (summaries.any { it.fileCount != null }) {
        summaries.sumOf { it.fileCount ?: 0 }
    } else {
        null
    }

    val categorized = summaries.mapNotNull { it.categories }
    val summedCategories = if (categorized.isNotEmpty()) {
        StorageCategories(
            images = categorized.sumOf { it.images.coerceAtLeast(0L) },
            documents = categorized.sumOf { it.documents.coerceAtLeast(0L) },
            videos = categorized.sumOf { it.videos.coerceAtLeast(0L) },
            archives = categorized.sumOf { it.archives.coerceAtLeast(0L) },
            audio = categorized.sumOf { it.audio.coerceAtLeast(0L) },
            other = categorized.sumOf { it.other.coerceAtLeast(0L) }
        )
    } else {
        null
    }

    return StorageSummary(
        accountId = ALL_ACCOUNTS_STORAGE_ID,
        accountType = if (anyUnlimited) "telegram" else "combined",
        usedBytes = totalUsed,
        totalBytes = totalCapacity,
        fileCount = totalFileCount,
        categories = summedCategories
    )
}
