package com.cloudyte.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector
import com.cloudyte.app.R

enum class SettingsSectionType(
    @get:StringRes val titleRes: Int,
    @get:StringRes val subtitleRes: Int,
    val icon: ImageVector
) {
    ACCOUNT(R.string.settings_section_account, R.string.settings_section_account_sub, Icons.Outlined.AccountCircle),
    BACKUP(R.string.settings_section_backup, R.string.settings_section_backup_sub, Icons.Outlined.Backup),
    STORAGE(R.string.settings_section_storage, R.string.settings_section_storage_sub, Icons.Outlined.Storage),
    PERMISSIONS(R.string.settings_section_permissions, R.string.settings_section_permissions_sub, Icons.Outlined.Shield),
    SECURITY(R.string.settings_section_security, R.string.settings_section_security_sub, Icons.Outlined.Lock),
    APPEARANCE(R.string.settings_section_appearance, R.string.settings_section_appearance_sub, Icons.Outlined.Palette),
    PLAYBACK(R.string.settings_section_playback, R.string.settings_section_playback_sub, Icons.Outlined.PlayCircle),
    NOTIFICATIONS(R.string.settings_section_notifications, R.string.settings_section_notifications_sub, Icons.Outlined.Notifications),
    ADVANCED(R.string.settings_section_advanced, R.string.settings_section_advanced_sub, Icons.Outlined.Build),
    ABOUT(R.string.settings_section_about, R.string.settings_section_about_sub, Icons.Outlined.Info)
}
