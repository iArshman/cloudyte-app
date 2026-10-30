package com.cloudyte.app.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
    BACKUP
}

enum class TransferStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(
    tableName = "transfers",
    indices = [
        Index(value = ["status", "updatedAt"]),
        Index(value = ["accountId", "createdAt"])
    ]
)
data class LocalTransfer(
    @PrimaryKey
    val id: String,
    val accountId: String,
    val fileId: String? = null,
    val fileName: String,
    val localPath: String? = null,
    val remotePath: String? = null,
    val direction: String = TransferDirection.DOWNLOAD.name,
    val status: String = TransferStatus.PENDING.name,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
