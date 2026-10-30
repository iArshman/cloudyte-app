package com.cloudyte.app.domain.usecase

import com.cloudyte.app.domain.repository.TransferRepository

class RetryTransfer(private val repository: TransferRepository) {
    suspend operator fun invoke(id: String) = repository.retry(id)
}

class PauseTransfer(private val repository: TransferRepository) {
    suspend operator fun invoke(id: String) = repository.pause(id)
}

class ResumeTransfer(private val repository: TransferRepository) {
    suspend operator fun invoke(id: String) = repository.resume(id)
}

class CancelTransfer(private val repository: TransferRepository) {
    suspend operator fun invoke(id: String) = repository.cancel(id)
}
