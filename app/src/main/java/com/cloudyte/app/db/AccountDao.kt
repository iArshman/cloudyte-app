package com.cloudyte.app.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM local_accounts ORDER BY isDefault DESC, cachedAt DESC")
    fun getAllAccountsFlow(): Flow<List<LocalAccount>>

    @Query("SELECT * FROM local_accounts ORDER BY isDefault DESC, cachedAt DESC")
    suspend fun getAllAccounts(): List<LocalAccount>

    @Query("SELECT * FROM local_accounts WHERE id = :id LIMIT 1")
    suspend fun getAccountById(id: String): LocalAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(accounts: List<LocalAccount>)

    @Query("DELETE FROM local_accounts WHERE id = :id")
    suspend fun deleteAccountById(id: String)

    @Query("DELETE FROM local_accounts")
    suspend fun clearAccounts()
}
