package com.cloudyte.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "file_mutations",
    indices = [
        Index(value = ["status", "nextAttemptAt", "createdAt"]),
        Index(value = ["accountId", "parentFolderId"]),
        Index(value = ["accountId", "remoteFileId", "status"])
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
    val providerType: String? = null,
    val remoteFileId: String? = fileId,
    val payloadJson: String? = null,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val correlationId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val RENAME = "RENAME"
        const val DELETE = "DELETE"
        const val CREATE_FOLDER = "CREATE_FOLDER"
        const val MOVE = "MOVE"
        const val COPY = "COPY"
        const val PENDING = "PENDING"
        const val RUNNING = "RUNNING"
        const val WAITING_FOR_AUTH = "WAITING_FOR_AUTH"
        const val CONFLICT = "CONFLICT"
        const val FAILED = "FAILED"
        const val FAILED_PERMANENT = "FAILED_PERMANENT"
    }
}
