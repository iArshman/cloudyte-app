package com.cloudyte.app.domain.model

import com.cloudyte.app.api.CloudFile as RemoteCloudFile
import com.cloudyte.app.api.Account as RemoteAccount
import com.cloudyte.app.api.StorageSummary as RemoteStorageSummary
import com.cloudyte.app.db.LocalTransfer

data class CloudFile(
    val id: String,
    val name: String,
    val type: String,
    val sizeBytes: Long,
    val isFolder: Boolean,
    val mimeType: String? = null,
    val updatedAt: Long? = null,
    val thumbUrl: String? = null
) {
    companion object {
        fun fromRemote(file: RemoteCloudFile) = CloudFile(
            id = file.effectiveId,
            name = file.effectiveName,
            type = file.effectiveType,
            sizeBytes = file.sizeBytes,
            isFolder = file.isFolderItem,
            mimeType = file.mimeType,
            updatedAt = file.updatedAt,
            thumbUrl = file.thumbUrl
        )
    }
}

data class CloudAccount(
    val id: String,
    val name: String,
    val type: String,
    val isDefault: Boolean = false,
    val usageBytes: Long = 0L,
    val totalBytes: Long = 0L
) {
    companion object {
        fun fromRemote(account: RemoteAccount) = CloudAccount(
            id = account.effectiveId,
            name = account.effectiveName,
            type = account.effectiveType,
            isDefault = account.isDefault == true,
            usageBytes = account.usageBytes,
            totalBytes = account.totalBytes
        )
    }
}

data class StorageSummary(
    val accountId: String,
    val accountType: String,
    val usedBytes: Long,
    val totalBytes: Long?,
    val fileCount: Int?
) {
    companion object {
        fun fromRemote(summary: RemoteStorageSummary) = StorageSummary(
            accountId = summary.accountId,
            accountType = summary.accountType,
            usedBytes = summary.usedBytes,
            totalBytes = summary.totalBytes,
            fileCount = summary.fileCount
        )
    }
}

data class Transfer(
    val id: String,
    val accountId: String,
    val fileName: String,
    val direction: String,
    val status: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val errorMessage: String?,
    val updatedAt: Long
) {
    companion object {
        fun fromLocal(value: LocalTransfer) = Transfer(
            id = value.id,
            accountId = value.accountId,
            fileName = value.fileName,
            direction = value.direction,
            status = value.status,
            bytesTransferred = value.bytesTransferred,
            totalBytes = value.totalBytes,
            errorMessage = value.errorMessage,
            updatedAt = value.updatedAt
        )
    }
}
