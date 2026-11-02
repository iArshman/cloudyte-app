package com.cloudyte.app.core.sync

import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.LocalFileTombstone

/** Rules shared by refreshers: partial pages may upsert, only complete snapshots may remove. */
object ReconciliationPolicy {
    data class Snapshot(
        val files: List<LocalCloudFile>,
        val isComplete: Boolean,
        val continuationToken: String? = null,
        val remoteRevision: String? = null
    ) {
        val isCompleteSnapshot: Boolean
            get() = isComplete && continuationToken.isNullOrBlank()
    }

    fun staleIds(existingIds: List<String>, snapshot: Snapshot): List<String> {
        if (!snapshot.isCompleteSnapshot) return emptyList()
        val present = snapshot.files.mapTo(hashSetOf()) { it.id }
        return existingIds.filterNot(present::contains)
    }

    fun tombstonesFor(
        existingIds: List<String>,
        snapshot: Snapshot,
        accountKey: String,
        providerType: String,
        folderId: String,
        deletedAt: Long
    ): List<LocalFileTombstone> {
        val stale = staleIds(existingIds, snapshot)
        if (stale.isEmpty()) return emptyList()
        return stale.map { remoteId ->
            LocalFileTombstone(
                tombstoneId = "$accountKey:$folderId:$remoteId",
                accountId = accountKey,
                providerType = providerType,
                remoteId = remoteId,
                folderId = folderId,
                deletedAt = deletedAt,
                sourceRevision = snapshot.remoteRevision
            )
        }
    }
}

