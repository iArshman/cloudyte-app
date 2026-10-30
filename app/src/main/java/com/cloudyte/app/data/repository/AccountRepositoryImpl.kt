package com.cloudyte.app.data.repository

import com.cloudyte.app.api.CloudyteApi
import com.cloudyte.app.core.common.AppError
import com.cloudyte.app.core.common.AppResult
import com.cloudyte.app.domain.model.CloudAccount
import com.cloudyte.app.domain.repository.AccountRepository

class AccountRepositoryImpl(
    private val api: CloudyteApi
) : AccountRepository {
    override suspend fun getAccounts(): AppResult<List<CloudAccount>> = try {
        AppResult.Success(api.getAccounts().map(CloudAccount::fromRemote))
    } catch (throwable: Throwable) {
        AppResult.Failure(AppError.from(throwable))
    }
}
