package com.cloudyte.app.core.sync

import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.db.LocalFileTombstone

/** Rules shared by refreshers: partial pages may upsert, only complete snapshots may remove. */
object ReconciliationPolicy {
    data class Snapshot(
        val files: List<LocalCloudFile>,
        val isComplete: Boolean,
        val continuationToken: String? = null,
        val remoteRevision: String? = null,
        val isDeltaMode: Boolean = false,
        val explicitDeletedIds: Set<String> = emptySet()
    ) {
        val isCompleteSnapshot: Boolean
            get() = isComplete && continuationToken.isNullOrBlank()
    }

    data class DeltaResult(
        val toUpsert: List<LocalCloudFile>,
        val staleIds: List<String>,
        val unchangedCount: Int
    ) {
        val hasChanges: Boolean
            get() = toUpsert.isNotEmpty() || staleIds.isNotEmpty()
    }

    fun isSameContent(existing: LocalCloudFile, incoming: LocalCloudFile): Boolean {
        return existing.id == incoming.id &&
            existing.name == incoming.name &&
            existing.type == incoming.type &&
            existing.size == incoming.size &&
            existing.rawSize == incoming.rawSize &&
            existing.time == incoming.time &&
            existing.isFolder == incoming.isFolder &&
            existing.path == incoming.path &&
            existing.folderId == incoming.folderId &&
            existing.accountId == incoming.accountId &&
            existing.mimeType == incoming.mimeType &&
            existing.updatedAt == incoming.updatedAt &&
            existing.thumbUrl == incoming.thumbUrl &&
            existing.isEncrypted == incoming.isEncrypted &&
            existing.encryptionFormatVersion == incoming.encryptionFormatVersion &&
            existing.encryptionKeyId == incoming.encryptionKeyId &&
            existing.encryptedMetadata == incoming.encryptedMetadata &&
            existing.encryptedSizeBytes == incoming.encryptedSizeBytes &&
            existing.encryptedChecksum == incoming.encryptedChecksum &&
            existing.opaqueRemoteName == incoming.opaqueRemoteName &&
            existing.plaintextSizeBytes == incoming.plaintextSizeBytes &&
            existing.originalMetadataAvailable == incoming.originalMetadataAvailable
    }

    fun computeDelta(
        existingRows: List<LocalCloudFile>,
        snapshot: Snapshot
    ): DeltaResult {
        val existingById = existingRows.associateBy { it.id }
        val toUpsert = ArrayList<LocalCloudFile>(snapshot.files.size)
        var unchangedCount = 0

        for (incoming in snapshot.files) {
            val prev = existingById[incoming.id]
            if (prev != null && isSameContent(prev, incoming)) {
                unchangedCount++
            } else {
                toUpsert.add(incoming)
            }
        }

        val existingIds = existingRows.map { it.id }
        val stale = when {
            snapshot.isDeltaMode -> snapshot.explicitDeletedIds.filter { it in existingById }
            snapshot.isCompleteSnapshot -> staleIds(existingIds, snapshot)
            else -> emptyList()
        }

        return DeltaResult(
            toUpsert = toUpsert,
            staleIds = stale,
            unchangedCount = unchangedCount
        )
    }

    fun staleIds(existingIds: List<String>, snapshot: Snapshot): List<String> {
        if (snapshot.isDeltaMode) {
            val existingSet = existingIds.toHashSet()
            return snapshot.explicitDeletedIds.filter(existingSet::contains)
        }
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
