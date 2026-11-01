package com.cloudyte.app.core.sync

import com.cloudyte.app.db.LocalCloudFile

/** Rules shared by refreshers: partial pages may upsert, only complete snapshots may remove. */
object ReconciliationPolicy {
    data class Snapshot(
        val files: List<LocalCloudFile>,
        val isComplete: Boolean,
        val continuationToken: String? = null,
        val remoteRevision: String? = null
    )

    fun staleIds(existingIds: List<String>, snapshot: Snapshot): List<String> {
        if (!snapshot.isComplete) return emptyList()
        val present = snapshot.files.mapTo(hashSetOf()) { it.id }
        return existingIds.filterNot(present::contains)
    }
}
