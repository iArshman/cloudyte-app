package com.cloudyte.app.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_prefs")

object SyncPreferences {
    private val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    private val BACKUP_URI = stringPreferencesKey("backup_uri")
    private val BACKUP_ACCOUNT_ID = stringPreferencesKey("backup_account_id")

    fun getAutoBackupEnabled(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[AUTO_BACKUP_ENABLED] ?: false
        }
    }

    suspend fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_BACKUP_ENABLED] = enabled
        }
    }

    fun getBackupUri(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[BACKUP_URI]
        }
    }

    suspend fun setBackupUri(context: Context, uri: String?) {
        context.dataStore.edit { preferences ->
            if (uri == null) {
                preferences.remove(BACKUP_URI)
            } else {
                preferences[BACKUP_URI] = uri
            }
        }
    }

    fun getBackupAccountId(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[BACKUP_ACCOUNT_ID]
        }
    }

    suspend fun setBackupAccountId(context: Context, accountId: String?) {
        context.dataStore.edit { preferences ->
            if (accountId == null) {
                preferences.remove(BACKUP_ACCOUNT_ID)
            } else {
                preferences[BACKUP_ACCOUNT_ID] = accountId
            }
        }
    }
}
