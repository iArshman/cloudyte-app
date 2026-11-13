package com.cloudyte.app.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.core.security.InvalidPassphraseException
import com.cloudyte.app.core.security.KeyUnavailableException
import com.cloudyte.app.data.ThemeMode
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.data.UserPreferencesState
import com.cloudyte.app.data.ViewMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as CloudyteApplication
    private val securityPrefs = application.getSharedPreferences("cloudyte_security_meta", Context.MODE_PRIVATE)

    private val _prefsState = MutableStateFlow(UserPreferencesState())
    val prefsState: StateFlow<UserPreferencesState> = _prefsState.asStateFlow()

    private val _currentSection = MutableStateFlow<SettingsSectionType?>(null)
    val currentSection: StateFlow<SettingsSectionType?> = _currentSection.asStateFlow()

    private val _encryptionKeyStatus = MutableStateFlow("Not initialized")
    val encryptionKeyStatus: StateFlow<String> = _encryptionKeyStatus.asStateFlow()

    private val _recoveryBackupStatus = MutableStateFlow(
        securityPrefs.getString("recovery_backup_status", "Not created") ?: "Not created"
    )
    val recoveryBackupStatus: StateFlow<String> = _recoveryBackupStatus.asStateFlow()

    private val _generatedRecoveryBackupJson = MutableStateFlow<String?>(null)
    val generatedRecoveryBackupJson: StateFlow<String?> = _generatedRecoveryBackupJson.asStateFlow()

    private val _securityFeedback = MutableStateFlow<String?>(null)
    val securityFeedback: StateFlow<String?> = _securityFeedback.asStateFlow()

    init {
        viewModelScope.launch {
            UserPreferences.observe(getApplication()).collectLatest { state ->
                _prefsState.value = state
            }
        }
        refreshKeyStatus()
    }

    fun refreshKeyStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val keyManager = app.container.contentKeyManager
            val status = if (!keyManager.hasWrappedKeyFile()) {
                "Not created yet (generated automatically on first encrypted upload)"
            } else {
                try {
                    val keyMaterial = keyManager.getExistingContentKey()
                    val id = keyMaterial.keyId
                    keyMaterial.destroy()
                    "Available (ID: ${id.take(12)}…)"
                } catch (_: KeyUnavailableException) {
                    "Unavailable — restore from recovery backup"
                } catch (_: Exception) {
                    "Unavailable — restore from recovery backup"
                }
            }
            _encryptionKeyStatus.value = status
            _recoveryBackupStatus.value =
                securityPrefs.getString("recovery_backup_status", "Not created") ?: "Not created"
        }
    }

    fun selectSection(section: SettingsSectionType?) {
        _currentSection.value = section
        if (section == SettingsSectionType.SECURITY) {
            refreshKeyStatus()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            UserPreferences.setThemeMode(getApplication(), mode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            UserPreferences.setDynamicColor(getApplication(), enabled)
        }
    }

    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch {
            UserPreferences.setViewMode(getApplication(), mode)
        }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch {
            UserPreferences.setGridColumns(getApplication(), columns)
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            UserPreferences.setAutoBackupEnabled(getApplication(), enabled)
        }
    }

    fun setBackupWifiOnly(wifiOnly: Boolean) {
        viewModelScope.launch {
            UserPreferences.setBackupWifiOnly(getApplication(), wifiOnly)
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            UserPreferences.setAppLockEnabled(getApplication(), enabled)
        }
    }

    fun setEncryptNewUploads(enabled: Boolean) {
        viewModelScope.launch {
            UserPreferences.setEncryptNewUploads(getApplication(), enabled)
            if (enabled) {
                withContext(Dispatchers.IO) {
                    runCatching { app.container.contentKeyManager.getOrCreateContentKey() }
                }
                refreshKeyStatus()
            }
        }
    }

    fun setEncryptOfflineFiles(enabled: Boolean) {
        viewModelScope.launch {
            UserPreferences.setEncryptOfflineFiles(getApplication(), enabled)
            if (enabled) {
                withContext(Dispatchers.IO) {
                    runCatching { app.container.contentKeyManager.getOrCreateContentKey() }
                }
                refreshKeyStatus()
            }
        }
    }

    fun setFullPrivacyMode(enabled: Boolean) {
        viewModelScope.launch {
            UserPreferences.setFullPrivacyMode(getApplication(), enabled)
            if (enabled) {
                UserPreferences.setEncryptNewUploads(getApplication(), true)
                UserPreferences.setEncryptOfflineFiles(getApplication(), true)
                withContext(Dispatchers.IO) {
                    runCatching { app.container.contentKeyManager.getOrCreateContentKey() }
                }
                refreshKeyStatus()
            }
        }
    }

    fun createRecoveryBackup(passphrase: String, confirmPassphrase: String) {
        if (passphrase.isBlank()) {
            _securityFeedback.value = "Passphrase cannot be empty"
            return
        }
        if (passphrase != confirmPassphrase) {
            _securityFeedback.value = "Passphrases do not match"
            return
        }
        viewModelScope.launch {
            try {
                val backupBytes = withContext(Dispatchers.IO) {
                    val chars = passphrase.toCharArray()
                    try {
                        app.container.keyBackupCodec.createKeyBackup(chars)
                    } finally {
                        chars.fill('\u0000')
                    }
                }
                val json = String(backupBytes, Charsets.UTF_8)
                _generatedRecoveryBackupJson.value = json
                securityPrefs.edit().putString("recovery_backup_status", "Created").apply()
                _recoveryBackupStatus.value = "Created"
                refreshKeyStatus()
                _securityFeedback.value = "Recovery backup created. Store it safely."
            } catch (e: Exception) {
                _securityFeedback.value = e.message ?: "Could not create recovery backup"
            }
        }
    }

    fun restoreRecoveryBackup(passphrase: String, backupJson: String) {
        if (passphrase.isBlank()) {
            _securityFeedback.value = "Enter your recovery passphrase"
            return
        }
        if (backupJson.isBlank()) {
            _securityFeedback.value = "Paste your recovery backup JSON"
            return
        }
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    val chars = passphrase.toCharArray()
                    try {
                        val material = app.container.keyBackupCodec.restoreKeyBackupDetailed(
                            passphrase = chars,
                            backup = backupJson.trim().toByteArray(Charsets.UTF_8)
                        )
                        app.container.contentKeyManager.importRecoveredContentKey(
                            keyId = material.keyId,
                            rawKeyBytes = material.contentKeyBytes,
                            createdAt = material.createdAt
                        ).destroy()
                        material
                    } finally {
                        chars.fill('\u0000')
                    }
                }
                restored.destroy()
                val decryptedCount = withContext(Dispatchers.IO) {
                    app.container.cloudFileRepository.reevaluateEncryptedMetadata()
                }
                securityPrefs.edit().putString("recovery_backup_status", "Restored").apply()
                _recoveryBackupStatus.value = "Restored"
                refreshKeyStatus()
                _securityFeedback.value = if (decryptedCount > 0) {
                    "Encryption key restored ($decryptedCount encrypted files unlocked)"
                } else {
                    "Encryption key restored successfully"
                }
            } catch (_: InvalidPassphraseException) {
                _securityFeedback.value = "Incorrect recovery passphrase or tampered backup"
            } catch (e: Exception) {
                _securityFeedback.value = e.message ?: "Failed to restore recovery backup"
            }
        }
    }

    fun clearGeneratedRecoveryBackup() {
        _generatedRecoveryBackupJson.value = null
    }

    fun clearSecurityFeedback() {
        _securityFeedback.value = null
    }

    fun resetAllSettings() {
        viewModelScope.launch {
            UserPreferences.resetAllSettings(getApplication())
        }
    }
}
