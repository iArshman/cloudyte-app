package com.example.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ThemeMode
import com.example.data.UserPreferences
import com.example.data.UserPreferencesState
import com.example.data.ViewMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _prefsState = MutableStateFlow(UserPreferencesState())
    val prefsState: StateFlow<UserPreferencesState> = _prefsState.asStateFlow()

    private val _currentSection = MutableStateFlow<SettingsSectionType?>(null)
    val currentSection: StateFlow<SettingsSectionType?> = _currentSection.asStateFlow()

    init {
        viewModelScope.launch {
            UserPreferences.observe(getApplication()).collectLatest { state ->
                _prefsState.value = state
            }
        }
    }

    fun selectSection(section: SettingsSectionType?) {
        _currentSection.value = section
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

    fun resetAllSettings() {
        viewModelScope.launch {
            UserPreferences.resetAllSettings(getApplication())
        }
    }
}
