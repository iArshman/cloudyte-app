package com.cloudyte.app.domain.usecase

import com.cloudyte.app.core.common.AppResult
import com.cloudyte.app.domain.model.CloudAccount
import com.cloudyte.app.domain.repository.AccountRepository

class GetAccounts(private val repository: AccountRepository) {
    suspend operator fun invoke(): AppResult<List<CloudAccount>> = repository.getAccounts()
}
