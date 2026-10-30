package com.cloudyte.app.data.repository

import com.cloudyte.app.core.common.AppResult
import com.cloudyte.app.core.common.toAppResult
import com.cloudyte.app.data.CloudFileRepository as LegacyCloudFileRepository
import com.cloudyte.app.domain.model.CloudFile
import com.cloudyte.app.domain.repository.FileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FileRepositoryImpl(
    private val delegate: LegacyCloudFileRepository
) : FileRepository {
    override fun observeFolder(accountId: String?, folderId: String?): Flow<List<CloudFile>> =
        delegate.observeFolder(accountId, folderId).map { files -> files.map(CloudFile::fromRemote) }

    override suspend fun refreshFolder(accountId: String?, folderId: String?, force: Boolean): AppResult<Unit> =
        delegate.refresh(accountId, folderId, force).toAppResult()

    override suspend fun renameFile(fileId: String, accountId: String?, newName: String): AppResult<Unit> =
        delegate.rename(fileId, accountId, newName).toAppResult()

    override suspend fun deleteFile(fileId: String, accountId: String?): AppResult<Unit> =
        delegate.delete(fileId, accountId).toAppResult()

    override suspend fun createFolder(name: String, accountId: String?, parentFolderId: String?): AppResult<Unit> =
        delegate.createFolder(name, accountId, parentFolderId).toAppResult()
}
