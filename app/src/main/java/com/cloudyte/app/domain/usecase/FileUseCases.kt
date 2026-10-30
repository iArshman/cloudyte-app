package com.cloudyte.app.domain.usecase

import com.cloudyte.app.core.common.AppResult
import com.cloudyte.app.domain.model.CloudFile
import com.cloudyte.app.domain.repository.FileRepository
import kotlinx.coroutines.flow.Flow

class ObserveFiles(private val repository: FileRepository) {
    operator fun invoke(accountId: String?, folderId: String?): Flow<List<CloudFile>> =
        repository.observeFolder(accountId, folderId)
}

class RefreshFolder(private val repository: FileRepository) {
    suspend operator fun invoke(accountId: String?, folderId: String?, force: Boolean = false): AppResult<Unit> =
        repository.refreshFolder(accountId, folderId, force)
}

class RenameFile(private val repository: FileRepository) {
    suspend operator fun invoke(fileId: String, accountId: String?, newName: String): AppResult<Unit> =
        repository.renameFile(fileId, accountId, newName)
}

class DeleteFile(private val repository: FileRepository) {
    suspend operator fun invoke(fileId: String, accountId: String?): AppResult<Unit> =
        repository.deleteFile(fileId, accountId)
}

class CreateFolder(private val repository: FileRepository) {
    suspend operator fun invoke(name: String, accountId: String?, parentFolderId: String?): AppResult<Unit> =
        repository.createFolder(name, accountId, parentFolderId)
}
