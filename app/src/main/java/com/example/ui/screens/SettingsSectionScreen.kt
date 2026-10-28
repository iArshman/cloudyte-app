package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.api.ApiClient
import com.example.api.OfflineFileManager
import com.example.data.ThemeMode
import com.example.data.UserPreferences
import com.example.data.ViewMode
import com.example.ui.components.ChoiceDialog
import com.example.ui.components.ConfirmDialog
import com.example.ui.components.GroupedList
import com.example.ui.components.GroupedListItem
import com.example.ui.components.SettingsClickRow
import com.example.ui.components.SettingsSectionTitle
import com.example.ui.components.SettingsSliderRow
import com.example.ui.components.SettingsSwitchRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScreen(
    section: SettingsSectionType,
    onBack: () -> Unit,
    onNavigateExclusions: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsState by UserPreferences.observe(context).collectAsState(initial = com.example.data.UserPreferencesState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    var showConfirmLogout by remember { mutableStateOf(false) }
    var showConfirmClearCache by remember { mutableStateOf(false) }
    var showConfirmClearDownloads by remember { mutableStateOf(false) }
    var showConfirmResetSettings by remember { mutableStateOf(false) }

    // Dialog state handlers
    var activeDialogType by remember { mutableStateOf<String?>(null) }

    val isScrolled = scrollState.value > 0
    val topBarContainerColor = if (isScrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(section.titleRes), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarContainerColor
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            when (section) {
                SettingsSectionType.ACCOUNT -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_account))
                    val accountItems = listOf(
                        GroupedListItem(key = "server_url") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_server_url),
                                subtitle = ApiClient.API_BASE_URL,
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Server URL", ApiClient.API_BASE_URL))
                                    Toast.makeText(context, context.getString(R.string.settings_server_copied), Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        GroupedListItem(key = "storage_quota") {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_storage_quota),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_quota_used_format, "12.4 GB", "50 GB"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { 0.25f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        GroupedListItem(key = "logout") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_logout),
                                titleColor = MaterialTheme.colorScheme.error,
                                onClick = { showConfirmLogout = true }
                            )
                        }
                    )
                    GroupedList(items = accountItems)
                }

                SettingsSectionType.BACKUP -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_backup))
                    val schedules = stringArrayResource(R.array.schedule_options)
                    val networks = stringArrayResource(R.array.network_options)

                    val backupItems = listOf(
                        GroupedListItem(key = "auto_backup") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_auto_backup),
                                subtitle = stringResource(R.string.settings_auto_backup_sub),
                                checked = prefsState.autoBackupEnabled,
                                onChange = { scope.launch { UserPreferences.setAutoBackupEnabled(context, it) } }
                            )
                        },
                        GroupedListItem(key = "schedule") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_backup_schedule),
                                subtitle = schedules[0],
                                onClick = { activeDialogType = "SCHEDULE" }
                            )
                        },
                        GroupedListItem(key = "exclusions") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_exclusions),
                                subtitle = stringResource(R.string.settings_exclusions_sub),
                                onClick = onNavigateExclusions
                            )
                        },
                        GroupedListItem(key = "max_size") {
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_max_file_size),
                                value = prefsState.backupMaxFileSizeMb.toFloat(),
                                range = 1f..100f,
                                onChange = { scope.launch { UserPreferences.setBackupMaxFileSizeMb(context, it.toInt()) } },
                                valueLabel = "${prefsState.backupMaxFileSizeMb} MB"
                            )
                        },
                        GroupedListItem(key = "network") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_network_type),
                                subtitle = if (prefsState.backupWifiOnly) networks[0] else networks[1],
                                onClick = { activeDialogType = "NETWORK" }
                            )
                        },
                        GroupedListItem(key = "delete_after_upload") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_delete_after_upload),
                                checked = prefsState.deleteAfterUpload,
                                onChange = { scope.launch { UserPreferences.setDeleteAfterUpload(context, it) } }
                            )
                        }
                    )
                    GroupedList(items = backupItems)
                }

                SettingsSectionType.STORAGE -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_storage))
                    val downloadDirs = stringArrayResource(R.array.download_dir_options)

                    val storageItems = listOf(
                        GroupedListItem(key = "clear_cache") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_clear_cache),
                                subtitle = "Thumbnails & HTTP cache",
                                onClick = { showConfirmClearCache = true }
                            )
                        },
                        GroupedListItem(key = "clear_downloads") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_clear_downloads),
                                subtitle = "${OfflineFileManager.getOfflineCount(context)} saved offline files",
                                onClick = { showConfirmClearDownloads = true }
                            )
                        },
                        GroupedListItem(key = "download_dir") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_download_directory),
                                subtitle = if (prefsState.downloadDirectory == "INTERNAL") downloadDirs[0] else downloadDirs[1],
                                onClick = { activeDialogType = "DOWNLOAD_DIR" }
                            )
                        },
                        GroupedListItem(key = "auto_clean") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_auto_clean_cache),
                                checked = prefsState.autoCleanCache,
                                onChange = { scope.launch { UserPreferences.setAutoCleanCache(context, it) } }
                            )
                        }
                    )
                    GroupedList(items = storageItems)
                }

                SettingsSectionType.PERMISSIONS -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_permissions))
                    val permItems = listOf(
                        GroupedListItem(key = "perm_media") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_perm_media),
                                subtitle = stringResource(R.string.settings_perm_granted),
                                onClick = {}
                            )
                        },
                        GroupedListItem(key = "perm_notif") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_perm_notifications),
                                subtitle = stringResource(R.string.settings_perm_granted),
                                onClick = {}
                            )
                        }
                    )
                    GroupedList(items = permItems)
                }

                SettingsSectionType.SECURITY -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_security))
                    val timeouts = stringArrayResource(R.array.lock_timeout_options)

                    val secItems = listOf(
                        GroupedListItem(key = "app_lock") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_app_lock),
                                checked = prefsState.appLockEnabled,
                                onChange = { scope.launch { UserPreferences.setAppLockEnabled(context, it) } }
                            )
                        },
                        GroupedListItem(key = "lock_timeout", visible = prefsState.appLockEnabled) {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_lock_timeout),
                                subtitle = when (prefsState.autoLockMinutes) {
                                    0 -> timeouts[0]
                                    1 -> timeouts[1]
                                    5 -> timeouts[2]
                                    else -> timeouts[3]
                                },
                                onClick = { activeDialogType = "LOCK_TIMEOUT" }
                            )
                        },
                        GroupedListItem(key = "block_screenshots") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_block_screenshots),
                                subtitle = stringResource(R.string.settings_block_screenshots_sub),
                                checked = prefsState.blockScreenshots,
                                onChange = { scope.launch { UserPreferences.setBlockScreenshots(context, it) } }
                            )
                        }
                    )
                    GroupedList(items = secItems)
                }

                SettingsSectionType.APPEARANCE -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_appearance))
                    val themes = stringArrayResource(R.array.theme_options)
                    val viewModes = stringArrayResource(R.array.view_mode_options)
                    val densities = stringArrayResource(R.array.density_options)

                    val appItems = listOf(
                        GroupedListItem(key = "theme") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_theme_mode),
                                subtitle = when (prefsState.themeMode) {
                                    ThemeMode.SYSTEM -> themes[0]
                                    ThemeMode.LIGHT -> themes[1]
                                    ThemeMode.DARK -> themes[2]
                                },
                                onClick = { activeDialogType = "THEME" }
                            )
                        },
                        GroupedListItem(key = "dynamic_color") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_dynamic_color),
                                checked = prefsState.dynamicColor,
                                onChange = { scope.launch { UserPreferences.setDynamicColor(context, it) } }
                            )
                        },
                        GroupedListItem(key = "view_mode") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_view_mode),
                                subtitle = when (prefsState.viewMode) {
                                    ViewMode.GRID -> viewModes[0]
                                    ViewMode.LIST -> viewModes[1]
                                    ViewMode.COMPACT_LIST -> viewModes[2]
                                },
                                onClick = { activeDialogType = "VIEW_MODE" }
                            )
                        },
                        GroupedListItem(key = "grid_columns", visible = prefsState.viewMode == ViewMode.GRID) {
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_grid_columns),
                                value = prefsState.gridColumns.toFloat(),
                                range = 2f..5f,
                                steps = 2,
                                onChange = { scope.launch { UserPreferences.setGridColumns(context, it.toInt()) } },
                                valueLabel = "${prefsState.gridColumns}"
                            )
                        },
                        GroupedListItem(key = "density") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_layout_density),
                                subtitle = if (prefsState.layoutDensity == "COMFORTABLE") densities[0] else densities[1],
                                onClick = { activeDialogType = "DENSITY" }
                            )
                        },
                        GroupedListItem(key = "recent_files") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_show_recent_files),
                                checked = prefsState.showRecentFiles,
                                onChange = { scope.launch { UserPreferences.setShowRecentFiles(context, it) } }
                            )
                        }
                    )
                    GroupedList(items = appItems)
                }

                SettingsSectionType.PLAYBACK -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_playback))
                    val playItems = listOf(
                        GroupedListItem(key = "stream_before_download") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_stream_before_download),
                                checked = prefsState.streamBeforeDownload,
                                onChange = { scope.launch { UserPreferences.setStreamBeforeDownload(context, it) } }
                            )
                        },
                        GroupedListItem(key = "background_audio") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_background_audio),
                                checked = prefsState.backgroundPlayback,
                                onChange = { scope.launch { UserPreferences.setBackgroundPlayback(context, it) } }
                            )
                        },
                        GroupedListItem(key = "auto_play_video") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_auto_play_video),
                                checked = prefsState.autoPlayVideo,
                                onChange = { scope.launch { UserPreferences.setAutoPlayVideo(context, it) } }
                            )
                        },
                        GroupedListItem(key = "crossfade") {
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_audio_crossfade),
                                value = prefsState.audioCrossfadeSeconds.toFloat(),
                                range = 0f..10f,
                                steps = 9,
                                onChange = { scope.launch { UserPreferences.setAudioCrossfadeSeconds(context, it.toInt()) } },
                                valueLabel = stringResource(R.string.settings_audio_crossfade_label, prefsState.audioCrossfadeSeconds)
                            )
                        }
                    )
                    GroupedList(items = playItems)
                }

                SettingsSectionType.NOTIFICATIONS -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_notifications))
                    val notifItems = listOf(
                        GroupedListItem(key = "enable_notif") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_enable_notifications),
                                checked = prefsState.notificationsEnabled,
                                onChange = { scope.launch { UserPreferences.setNotificationsEnabled(context, it) } }
                            )
                        },
                        GroupedListItem(key = "progress_alerts", visible = prefsState.notificationsEnabled) {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_progress_alerts),
                                checked = prefsState.progressAlerts,
                                onChange = { scope.launch { UserPreferences.setProgressAlerts(context, it) } }
                            )
                        },
                        GroupedListItem(key = "failure_alerts", visible = prefsState.notificationsEnabled) {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_failure_alerts),
                                checked = prefsState.failureNotifications,
                                onChange = { scope.launch { UserPreferences.setFailureNotifications(context, it) } }
                            )
                        },
                        GroupedListItem(key = "sound", visible = prefsState.notificationsEnabled) {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_sound),
                                checked = prefsState.soundEnabled,
                                onChange = { scope.launch { UserPreferences.setSoundEnabled(context, it) } }
                            )
                        },
                        GroupedListItem(key = "vibration", visible = prefsState.notificationsEnabled) {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_vibration),
                                checked = prefsState.vibrationEnabled,
                                onChange = { scope.launch { UserPreferences.setVibrationEnabled(context, it) } }
                            )
                        }
                    )
                    GroupedList(items = notifItems)
                }

                SettingsSectionType.ADVANCED -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_advanced))
                    val advItems = listOf(
                        GroupedListItem(key = "concurrency") {
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_transfer_concurrency),
                                value = prefsState.transferConcurrency.toFloat(),
                                range = 1f..5f,
                                steps = 3,
                                onChange = { scope.launch { UserPreferences.setTransferConcurrency(context, it.toInt()) } },
                                valueLabel = "${prefsState.transferConcurrency}"
                            )
                        },
                        GroupedListItem(key = "retries") {
                            SettingsSliderRow(
                                title = stringResource(R.string.settings_max_retries),
                                value = prefsState.transferRetryCount.toFloat(),
                                range = 1f..10f,
                                steps = 8,
                                onChange = { scope.launch { UserPreferences.setTransferRetryCount(context, it.toInt()) } },
                                valueLabel = "${prefsState.transferRetryCount}"
                            )
                        },
                        GroupedListItem(key = "metered") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_metered_transfers),
                                checked = prefsState.allowMeteredTransfers,
                                onChange = { scope.launch { UserPreferences.setAllowMeteredTransfers(context, it) } }
                            )
                        },
                        GroupedListItem(key = "debug_logging") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_debug_logging),
                                checked = prefsState.debugLogging,
                                onChange = { scope.launch { UserPreferences.setDebugLogging(context, it) } }
                            )
                        },
                        GroupedListItem(key = "reset_all") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_reset_all),
                                titleColor = MaterialTheme.colorScheme.error,
                                onClick = { showConfirmResetSettings = true }
                            )
                        }
                    )
                    GroupedList(items = advItems)
                }

                SettingsSectionType.ABOUT -> {
                    SettingsSectionTitle(stringResource(R.string.settings_section_about))
                    val aboutItems = listOf(
                        GroupedListItem(key = "version") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_app_version),
                                subtitle = stringResource(R.string.settings_app_version_sub),
                                onClick = {}
                            )
                        },
                        GroupedListItem(key = "check_updates") {
                            SettingsClickRow(
                                title = stringResource(R.string.action_check_updates),
                                onClick = {
                                    Toast.makeText(context, context.getString(R.string.settings_updates_up_to_date), Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        GroupedListItem(key = "tos") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_terms_of_service),
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cloudyte.example/tos")))
                                    }
                                }
                            )
                        },
                        GroupedListItem(key = "privacy") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_privacy_policy),
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://cloudyte.example/privacy")))
                                    }
                                }
                            )
                        },
                        GroupedListItem(key = "licenses") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_open_source_licenses),
                                onClick = {}
                            )
                        }
                    )
                    GroupedList(items = aboutItems)
                }
            }
            Spacer(modifier = Modifier.height(24.dp + com.example.ui.components.LocalBottomBarInset.current))
        }
    }

    // Dialogs
    when (activeDialogType) {
        "SCHEDULE" -> {
            val schedules = stringArrayResource(R.array.schedule_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_backup_schedule),
                options = schedules.toList(),
                selectedIndex = 0,
                onSelect = {},
                onDismiss = { activeDialogType = null }
            )
        }
        "NETWORK" -> {
            val networks = stringArrayResource(R.array.network_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_network_type),
                options = networks.toList(),
                selectedIndex = if (prefsState.backupWifiOnly) 0 else 1,
                onSelect = { scope.launch { UserPreferences.setBackupWifiOnly(context, it == 0) } },
                onDismiss = { activeDialogType = null }
            )
        }
        "DOWNLOAD_DIR" -> {
            val downloadDirs = stringArrayResource(R.array.download_dir_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_download_directory),
                options = downloadDirs.toList(),
                selectedIndex = if (prefsState.downloadDirectory == "INTERNAL") 0 else 1,
                onSelect = { scope.launch { UserPreferences.setDownloadDirectory(context, if (it == 0) "INTERNAL" else "EXTERNAL") } },
                onDismiss = { activeDialogType = null }
            )
        }
        "LOCK_TIMEOUT" -> {
            val timeouts = stringArrayResource(R.array.lock_timeout_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_lock_timeout),
                options = timeouts.toList(),
                selectedIndex = when (prefsState.autoLockMinutes) {
                    0 -> 0
                    1 -> 1
                    5 -> 2
                    else -> 3
                },
                onSelect = { idx ->
                    val mins = when (idx) {
                        0 -> 0
                        1 -> 1
                        2 -> 5
                        else -> 15
                    }
                    scope.launch { UserPreferences.setAutoLockMinutes(context, mins) }
                },
                onDismiss = { activeDialogType = null }
            )
        }
        "THEME" -> {
            val themes = stringArrayResource(R.array.theme_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_theme_mode),
                options = themes.toList(),
                selectedIndex = when (prefsState.themeMode) {
                    ThemeMode.SYSTEM -> 0
                    ThemeMode.LIGHT -> 1
                    ThemeMode.DARK -> 2
                },
                onSelect = { idx ->
                    val mode = when (idx) {
                        0 -> ThemeMode.SYSTEM
                        1 -> ThemeMode.LIGHT
                        else -> ThemeMode.DARK
                    }
                    scope.launch { UserPreferences.setThemeMode(context, mode) }
                },
                onDismiss = { activeDialogType = null }
            )
        }
        "VIEW_MODE" -> {
            val viewModes = stringArrayResource(R.array.view_mode_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_view_mode),
                options = viewModes.toList(),
                selectedIndex = when (prefsState.viewMode) {
                    ViewMode.GRID -> 0
                    ViewMode.LIST -> 1
                    ViewMode.COMPACT_LIST -> 2
                },
                onSelect = { idx ->
                    val mode = when (idx) {
                        0 -> ViewMode.GRID
                        1 -> ViewMode.LIST
                        else -> ViewMode.COMPACT_LIST
                    }
                    scope.launch { UserPreferences.setViewMode(context, mode) }
                },
                onDismiss = { activeDialogType = null }
            )
        }
        "DENSITY" -> {
            val densities = stringArrayResource(R.array.density_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_layout_density),
                options = densities.toList(),
                selectedIndex = if (prefsState.layoutDensity == "COMFORTABLE") 0 else 1,
                onSelect = { scope.launch { UserPreferences.setLayoutDensity(context, if (it == 0) "COMFORTABLE" else "COMPACT") } },
                onDismiss = { activeDialogType = null }
            )
        }
    }

    if (showConfirmLogout) {
        ConfirmDialog(
            title = stringResource(R.string.settings_logout_confirm_title),
            message = stringResource(R.string.settings_logout_confirm_msg),
            confirmLabel = stringResource(R.string.settings_logout),
            onConfirm = { onLogout() },
            onDismiss = { showConfirmLogout = false }
        )
    }

    if (showConfirmClearCache) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_cache),
            message = stringResource(R.string.settings_clear_cache_confirm),
            onConfirm = {
                OfflineFileManager.clearAllOfflineCache(context)
                Toast.makeText(context, context.getString(R.string.settings_all_cache_cleared), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showConfirmClearCache = false }
        )
    }

    if (showConfirmClearDownloads) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_downloads),
            message = stringResource(R.string.settings_clear_downloads_confirm),
            onConfirm = {
                OfflineFileManager.clearAllOfflineCache(context)
                Toast.makeText(context, context.getString(R.string.settings_all_cache_cleared), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showConfirmClearDownloads = false }
        )
    }

    if (showConfirmResetSettings) {
        ConfirmDialog(
            title = stringResource(R.string.settings_reset_confirm_title),
            message = stringResource(R.string.settings_reset_confirm_msg),
            confirmLabel = stringResource(R.string.action_reset),
            onConfirm = {
                scope.launch {
                    UserPreferences.resetAllSettings(context)
                }
            },
            onDismiss = { showConfirmResetSettings = false }
        )
    }
}
