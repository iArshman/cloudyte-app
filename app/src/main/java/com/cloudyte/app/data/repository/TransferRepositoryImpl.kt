package com.cloudyte.app.data.repository

import com.cloudyte.app.data.TransferRepository as LegacyTransferRepository
import com.cloudyte.app.domain.model.Transfer
import com.cloudyte.app.domain.repository.TransferRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TransferRepositoryImpl(
    private val delegate: LegacyTransferRepository
) : TransferRepository {
    override fun observeAll(): Flow<List<Transfer>> =
        delegate.observeAll().map { values -> values.map(Transfer::fromLocal) }

    override fun observeActive(): Flow<List<Transfer>> =
        delegate.observeActive().map { values -> values.map(Transfer::fromLocal) }

    override suspend fun retry(id: String) = delegate.retryTransfer(id)
    override suspend fun pause(id: String) = delegate.pauseTransfer(id)
    override suspend fun resume(id: String) = delegate.resumeTransfer(id)
    override suspend fun cancel(id: String) = delegate.cancelTransfer(id)
}
