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
    val time: Long,
    val isFolder: Boolean,
    val path: String,
    val accountId: String? // To filter by account
) {
    fun toCloudFile(): CloudFile {
        return CloudFile(
            id = this.id,
            name = this.name,
            type = this.type,
            size = this.size,
            time = this.time,
            isFolder = this.isFolder,
            path = this.path
        )
    }

    companion object {
        fun fromCloudFile(file: CloudFile, accountId: String?): LocalCloudFile {
            return LocalCloudFile(
                id = file.effectiveId,
                name = file.effectiveName,
                type = file.effectiveType,
                size = file.formattedSize,
                time = file.time ?: 0L,
                isFolder = file.isFolderItem,
                path = file.path ?: "",
                accountId = accountId
            )
        }
    }
}
