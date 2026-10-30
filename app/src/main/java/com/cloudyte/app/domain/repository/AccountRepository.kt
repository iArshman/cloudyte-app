package com.cloudyte.app.domain.repository

import com.cloudyte.app.core.common.AppResult
import com.cloudyte.app.domain.model.CloudAccount

interface AccountRepository {
    suspend fun getAccounts(): AppResult<List<CloudAccount>>
}
