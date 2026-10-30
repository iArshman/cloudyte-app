package com.cloudyte.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import com.cloudyte.app.R

enum class SettingsSectionType(
    @get:StringRes val titleRes: Int,
    @get:StringRes val subtitleRes: Int,
    val icon: ImageVector
) {
    ACCOUNT(R.string.settings_section_account, R.string.settings_section_account_sub, Icons.Default.AccountCircle),
    BACKUP(R.string.settings_section_backup, R.string.settings_section_backup_sub, Icons.Default.Backup),
    STORAGE(R.string.settings_section_storage, R.string.settings_section_storage_sub, Icons.Default.Storage),
    PERMISSIONS(R.string.settings_section_permissions, R.string.settings_section_permissions_sub, Icons.Default.Security),
    SECURITY(R.string.settings_section_security, R.string.settings_section_security_sub, Icons.Default.Lock),
    APPEARANCE(R.string.settings_section_appearance, R.string.settings_section_appearance_sub, Icons.Default.Palette),
    PLAYBACK(R.string.settings_section_playback, R.string.settings_section_playback_sub, Icons.Default.PlayArrow),
    NOTIFICATIONS(R.string.settings_section_notifications, R.string.settings_section_notifications_sub, Icons.Default.Notifications),
    ADVANCED(R.string.settings_section_advanced, R.string.settings_section_advanced_sub, Icons.Default.Build),
    ABOUT(R.string.settings_section_about, R.string.settings_section_about_sub, Icons.Default.Info)
}
