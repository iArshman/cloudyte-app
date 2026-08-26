package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.api.Account

@Entity(tableName = "local_accounts")
data class LocalAccount(
    @PrimaryKey val id: String,
    val name: String,
    val driveName: String?,
    val email: String?,
    val accountType: String,
    val usageBytes: Long,
    val totalBytes: Long,
    val percentage: Float,
    val fileCount: Int,
    val isDefault: Boolean,
    val channelId: String?,
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toAccount(): Account {
        return Account(
            id = this.id,
            name = this.name,
            driveName = this.driveName,
            email = this.email,
            accountType = this.accountType,
            usage = this.usageBytes,
            total = this.totalBytes,
            percentage = this.percentage,
            fileCount = this.fileCount,
            isDefault = this.isDefault,
            channelId = this.channelId
        )
    }

    companion object {
        fun fromAccount(account: Account): LocalAccount {
            return LocalAccount(
                id = account.effectiveId,
                name = account.effectiveName,
                driveName = account.driveName,
                email = account.email,
                accountType = account.effectiveType,
                usageBytes = account.usageBytes,
                totalBytes = account.totalBytes,
                percentage = account.effectivePercentage,
                fileCount = account.fileCount ?: 0,
                isDefault = account.isDefault == true,
                channelId = account.channelId
            )
        }
    }
}
