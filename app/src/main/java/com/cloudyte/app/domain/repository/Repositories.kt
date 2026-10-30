package com.cloudyte.app.domain.repository

import com.cloudyte.app.core.common.AppResult
import com.cloudyte.app.domain.model.CloudFile
import com.cloudyte.app.domain.model.Transfer
import kotlinx.coroutines.flow.Flow

interface FileRepository {
    fun observeFolder(accountId: String?, folderId: String?): Flow<List<CloudFile>>
    suspend fun refreshFolder(accountId: String?, folderId: String?, force: Boolean = false): AppResult<Unit>
    suspend fun renameFile(fileId: String, accountId: String?, newName: String): AppResult<Unit>
    suspend fun deleteFile(fileId: String, accountId: String?): AppResult<Unit>
    suspend fun createFolder(name: String, accountId: String?, parentFolderId: String?): AppResult<Unit>
}

interface TransferRepository {
    fun observeAll(): Flow<List<Transfer>>
    fun observeActive(): Flow<List<Transfer>>
    suspend fun retry(id: String)
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
}
