package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.api.CloudFile

@Entity(tableName = "cloud_files")
data class LocalCloudFile(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val size: String,
    val rawSize: Long = 0L,
    val time: Long = 0L,
    val isFolder: Boolean = false,
    val path: String = "",
    val folderId: String = "root",
    val accountId: String? = null,
    val mimeType: String? = null,
    val shareId: String? = null,
    val isShared: Boolean = false,
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toCloudFile(): CloudFile {
        return CloudFile(
            id = this.id,
            name = this.name,
            type = this.type,
            size = this.size,
            time = this.time,
            isFolder = this.isFolder,
            path = this.path,
            mimeType = this.mimeType
        )
    }

    companion object {
        fun fromCloudFile(file: CloudFile, accountId: String?, folderId: String? = null): LocalCloudFile {
            val safeFolderId = if (folderId.isNullOrBlank() || folderId == "root") "root" else folderId
            return LocalCloudFile(
                id = if (file.effectiveId.isNotBlank()) file.effectiveId else "${accountId}_${safeFolderId}_${file.effectiveName}",
                name = file.effectiveName,
                type = file.effectiveType,
                size = file.formattedSize,
                rawSize = file.sizeBytes,
                time = file.time ?: 0L,
                isFolder = file.isFolderItem,
                path = file.path ?: "",
                folderId = safeFolderId,
                accountId = accountId,
                mimeType = file.mimeType,
                cachedAt = System.currentTimeMillis()
            )
        }
    }
}
