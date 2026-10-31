package com.cloudyte.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "file_mutations",
    indices = [
        Index(value = ["status", "createdAt"]),
        Index(value = ["accountId", "parentFolderId"])
    ]
)
data class LocalFileMutation(
    @PrimaryKey val operationId: String,
    val accountId: String?,
    val operation: String,
    val fileId: String? = null,
    val name: String? = null,
    @ColumnInfo(defaultValue = "'root'") val parentFolderId: String = "root",
    val optimisticFileId: String? = null,
    @ColumnInfo(defaultValue = "'PENDING'") val status: String = PENDING,
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val RENAME = "RENAME"
        const val DELETE = "DELETE"
        const val CREATE_FOLDER = "CREATE_FOLDER"
        const val PENDING = "PENDING"
        const val FAILED = "FAILED"
    }
}
