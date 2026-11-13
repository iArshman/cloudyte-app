package com.cloudyte.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cloudyte.app.BuildConfig
import com.cloudyte.app.R
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.data.ColorTheme
import com.cloudyte.app.data.ThemeMode
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.data.ViewMode
import com.cloudyte.app.sync.AutoSyncManager
import com.cloudyte.app.ui.components.ChoiceDialog
import com.cloudyte.app.ui.components.ConfirmDialog
import com.cloudyte.app.ui.components.GroupedList
import com.cloudyte.app.ui.components.GroupedListItem
import com.cloudyte.app.ui.components.SettingsClickRow
import com.cloudyte.app.ui.components.SettingsSectionTitle
import com.cloudyte.app.ui.components.SettingsSliderRow
import com.cloudyte.app.ui.components.SettingsSwitchRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSectionScreen(
    section: SettingsSectionType,
    onBack: () -> Unit,
    onNavigateExclusions: () -> Unit = {},
    onNavigateAccounts: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsState by UserPreferences.observe(context).collectAsState(initial = com.cloudyte.app.data.UserPreferencesState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollState = rememberScrollState()

    var showConfirmLogout by remember { mutableStateOf(false) }
    var showConfirmClearCache by remember { mutableStateOf(false) }
    var showConfirmClearDownloads by remember { mutableStateOf(false) }
    var showConfirmResetSettings by remember { mutableStateOf(false) }

    var profile by remember { mutableStateOf<com.cloudyte.app.api.UserProfile?>(null) }
    var accounts by remember { mutableStateOf<List<com.cloudyte.app.api.Account>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(section) {
        if (section == SettingsSectionType.ACCOUNT) {
            val db = com.cloudyte.app.db.AppDatabase.getDatabase(context)
            try {
                val cached = db.accountDao().getAllAccounts()
                if (cached.isNotEmpty()) {
                    accounts = cached.map { it.toAccount() }
                }
            } catch (_: Exception) {}
            try {
                profile = ApiClient.apiService.getProfile()
            } catch (_: Exception) {}
            try {
                val fetched = ApiClient.apiService.getAccounts()
                if (fetched.isNotEmpty()) {
                    accounts = fetched
                    db.accountDao().insertAccounts(fetched.map { com.cloudyte.app.db.LocalAccount.fromAccount(it) })
                }
            } catch (_: Exception) {}
        }
    }

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
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = com.cloudyte.app.ui.components.LocalBottomBarInset.current)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            when (section) {
                SettingsSectionType.ACCOUNT -> {
                    val username = profile?.displayUsername ?: "User"
                    val userId = profile?.id ?: profile?._id ?: "Active Session"
                    val email = profile?.email?.takeIf { it.isNotBlank() } ?: "Not set"
                    val roleLabel = if (profile?.isAdmin == true) "Administrator" else "Standard User"
                    val usedTotal = accounts.sumOf { it.usageBytes.coerceAtLeast(0L) }
                    val finiteTotals = accounts.map { it.totalBytes }.filter { it > 0L }
                    val hasUnlimited = accounts.any {
                        it.totalBytes <= 0L ||
                            it.effectiveType.contains("tg", ignoreCase = true) ||
                            it.effectiveType.contains("tele", ignoreCase = true)
                    }
                    val totalCap = if (hasUnlimited || finiteTotals.isEmpty()) 0L else finiteTotals.sum()
                    val quotaText = if (totalCap > 0L) {
                        "${com.cloudyte.app.data.formatStorageSize(usedTotal)} of ${com.cloudyte.app.data.formatStorageSize(totalCap)} used"
                    } else {
                        "${com.cloudyte.app.data.formatStorageSize(usedTotal)} used • Unlimited"
                    }
                    val progressFraction = if (totalCap > 0L) {
                        (usedTotal.toFloat() / totalCap.toFloat()).coerceIn(0f, 1f)
                    } else {
                        null
                    }
                    var drivesExpanded by remember { mutableStateOf(false) }

                    SettingsSectionTitle("User Profile")
                    val profileItems = buildList {
                        add(
                            GroupedListItem(key = "username") {
                                SettingsClickRow(
                                    title = "Username",
                                    subtitle = username,
                                    onClick = {}
                                )
                            }
                        )
                        add(
                            GroupedListItem(key = "account_role") {
                                SettingsClickRow(
                                    title = "Account Type",
                                    subtitle = roleLabel,
                                    onClick = {}
                                )
                            }
                        )
                        add(
                            GroupedListItem(key = "user_id") {
                                SettingsClickRow(
                                    title = "Account ID",
                                    subtitle = userId,
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Account ID", userId))
                                        Toast.makeText(context, "Account ID copied", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        )
                        if (email != "Not set") {
                            add(
                                GroupedListItem(key = "email") {
                                    SettingsClickRow(
                                        title = "Email",
                                        subtitle = email,
                                        onClick = {}
                                    )
                                }
                            )
                        }
                        add(
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
                            }
                        )
                    }
                    GroupedList(items = profileItems)

                    SettingsSectionTitle("Storage & Linked Drives")
                    val driveItems = buildList {
                        add(
                            GroupedListItem(key = "storage_quota") {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_storage_quota),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (progressFraction == null) {
                                            Surface(
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = "Unlimited",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = quotaText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (progressFraction != null) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LinearProgressIndicator(
                                            progress = { progressFraction },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        )
                        add(
                            GroupedListItem(key = "drives_dropdown_toggle") {
                                SettingsClickRow(
                                    title = "Linked Storage Drives (${accounts.size})",
                                    subtitle = if (drivesExpanded) "Tap to collapse connected drives" else "Tap to view connected drives",
                                    trailing = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = if (drivesExpanded) "Hide" else "Show",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Icon(
                                                imageVector = if (drivesExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = if (drivesExpanded) "Collapse drives" else "Expand drives",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    onClick = { drivesExpanded = !drivesExpanded }
                                )
                            }
                        )
                        if (drivesExpanded) {
                            accounts.forEach { acc ->
                                val accSub = buildString {
                                    append(acc.effectiveType)
                                    val detail = acc.email?.takeIf { it.isNotBlank() }
                                        ?: acc.channelId?.takeIf { it.isNotBlank() }?.let { "Channel: $it" }
                                    if (!detail.isNullOrBlank()) {
                                        append(" • ")
                                        append(detail)
                                    }
                                    append(" • ")
                                    append(com.cloudyte.app.data.formatStorageSize(acc.usageBytes))
                                    if (acc.isDefault == true) {
                                        append(" (Default)")
                                    }
                                }
                                add(
                                    GroupedListItem(key = "drive_${acc.effectiveId}") {
                                        SettingsClickRow(
                                            title = acc.effectiveName,
                                            subtitle = accSub,
                                            onClick = onNavigateAccounts
                                        )
                                    }
                                )
                            }
                            add(
                                GroupedListItem(key = "manage_drives") {
                                    SettingsClickRow(
                                        title = "Manage / Add Storage Drives",
                                        subtitle = "Connect Telegram, Google Drive, or OneDrive",
                                        titleColor = MaterialTheme.colorScheme.primary,
                                        onClick = onNavigateAccounts
                                    )
                                }
                            )
                        }
                        add(
                            GroupedListItem(key = "logout") {
                                SettingsClickRow(
                                    title = stringResource(R.string.settings_logout),
                                    titleColor = MaterialTheme.colorScheme.error,
                                    onClick = { showConfirmLogout = true }
                                )
                            }
                        )
                    }
                    GroupedList(items = driveItems)
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
                                onChange = { enabled ->
                                    scope.launch {
                                        UserPreferences.setAutoBackupEnabled(context, enabled)
                                        runCatching { AutoSyncManager.setAutoBackup(context, enabled) }
                                    }
                                }
                            )
                        },
                        GroupedListItem(key = "schedule") {
                            val scheduleSubtitle = when {
                                !prefsState.autoBackupEnabled -> schedules[0]
                                prefsState.backupIntervalHours <= 1 -> schedules[1]
                                prefsState.backupIntervalHours <= 24 -> schedules[2]
                                else -> schedules[3]
                            }
                            SettingsClickRow(
                                title = stringResource(R.string.settings_backup_schedule),
                                subtitle = scheduleSubtitle,
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
                    val settingsVm: SettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                    val keyStatus by settingsVm.encryptionKeyStatus.collectAsState()
                    val backupStatus by settingsVm.recoveryBackupStatus.collectAsState()
                    val generatedBackupJson by settingsVm.generatedRecoveryBackupJson.collectAsState()
                    val securityFeedback by settingsVm.securityFeedback.collectAsState()

                    var showCreateBackupDialog by remember { mutableStateOf(false) }
                    var showRestoreBackupDialog by remember { mutableStateOf(false) }
                    var createPassphrase by remember { mutableStateOf("") }
                    var confirmPassphrase by remember { mutableStateOf("") }
                    var restorePassphrase by remember { mutableStateOf("") }
                    var restoreBackupJson by remember { mutableStateOf("") }

                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        settingsVm.refreshKeyStatus()
                    }

                    androidx.compose.runtime.LaunchedEffect(securityFeedback) {
                        securityFeedback?.let {
                            snackbarHostState.showSnackbar(it)
                            settingsVm.clearSecurityFeedback()
                        }
                    }

                    if (showCreateBackupDialog) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = {
                                showCreateBackupDialog = false
                                createPassphrase = ""
                                confirmPassphrase = ""
                            },
                            title = { Text(stringResource(R.string.settings_create_recovery_backup)) },
                            text = {
                                Column {
                                    Text(
                                        text = "Enter a strong passphrase to encrypt your recovery key backup. The server never receives this passphrase.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    androidx.compose.material3.OutlinedTextField(
                                        value = createPassphrase,
                                        onValueChange = { createPassphrase = it },
                                        label = { Text("Recovery passphrase") },
                                        singleLine = true,
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    androidx.compose.material3.OutlinedTextField(
                                        value = confirmPassphrase,
                                        onValueChange = { confirmPassphrase = it },
                                        label = { Text("Confirm passphrase") },
                                        singleLine = true,
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                androidx.compose.material3.Button(
                                    onClick = {
                                        val p1 = createPassphrase
                                        val p2 = confirmPassphrase
                                        createPassphrase = ""
                                        confirmPassphrase = ""
                                        showCreateBackupDialog = false
                                        settingsVm.createRecoveryBackup(p1, p2)
                                    }
                                ) {
                                    Text(stringResource(R.string.action_create))
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        showCreateBackupDialog = false
                                        createPassphrase = ""
                                        confirmPassphrase = ""
                                    }
                                ) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                        )
                    }

                    if (generatedBackupJson != null) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { settingsVm.clearGeneratedRecoveryBackup() },
                            title = { Text("Recovery Key Backup Ready") },
                            text = {
                                Column {
                                    Text(
                                        text = "Copy and store this encrypted key backup in a safe place (e.g., password manager).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    androidx.compose.material3.OutlinedTextField(
                                        value = generatedBackupJson ?: "",
                                        onValueChange = {},
                                        readOnly = true,
                                        maxLines = 6,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                androidx.compose.material3.Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Cloudyte Key Backup", generatedBackupJson))
                                        Toast.makeText(context, "Recovery backup copied to clipboard", Toast.LENGTH_SHORT).show()
                                        settingsVm.clearGeneratedRecoveryBackup()
                                    }
                                ) {
                                    Text("Copy Backup")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { settingsVm.clearGeneratedRecoveryBackup() }
                                ) {
                                    Text(stringResource(R.string.action_close))
                                }
                            }
                        )
                    }

                    if (showRestoreBackupDialog) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = {
                                showRestoreBackupDialog = false
                                restorePassphrase = ""
                                restoreBackupJson = ""
                            },
                            title = { Text(stringResource(R.string.settings_restore_recovery_backup)) },
                            text = {
                                Column {
                                    Text(
                                        text = "Paste your recovery backup JSON and enter the recovery passphrase to restore your encryption key.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    androidx.compose.material3.OutlinedTextField(
                                        value = restorePassphrase,
                                        onValueChange = { restorePassphrase = it },
                                        label = { Text("Recovery passphrase") },
                                        singleLine = true,
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    androidx.compose.material3.OutlinedTextField(
                                        value = restoreBackupJson,
                                        onValueChange = { restoreBackupJson = it },
                                        label = { Text("Recovery backup JSON") },
                                        maxLines = 5,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                androidx.compose.material3.Button(
                                    onClick = {
                                        val pass = restorePassphrase
                                        val json = restoreBackupJson
                                        restorePassphrase = ""
                                        restoreBackupJson = ""
                                        showRestoreBackupDialog = false
                                        settingsVm.restoreRecoveryBackup(pass, json)
                                    }
                                ) {
                                    Text("Restore")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        showRestoreBackupDialog = false
                                        restorePassphrase = ""
                                        restoreBackupJson = ""
                                    }
                                ) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            }
                        )
                    }

                    SettingsSectionTitle(stringResource(R.string.settings_section_security))
                    val timeouts = stringArrayResource(R.array.lock_timeout_options)

                    val secItems = listOf(
                        GroupedListItem(key = "app_lock") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_app_lock),
                                checked = prefsState.appLockEnabled,
                                onChange = { settingsVm.setAppLockEnabled(it) }
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

                    SettingsSectionTitle("End-to-End File Encryption")
                    val encryptionStatusSubtitle = buildString {
                        if (prefsState.fullPrivacyMode) {
                            append("Full privacy mode active")
                        } else if (prefsState.encryptNewUploads || prefsState.encryptOfflineFiles) {
                            append("Enabled")
                        } else {
                            append("Disabled")
                        }
                        append(" • Key: ")
                        append(keyStatus)
                    }
                    val encryptionItems = listOf(
                        GroupedListItem(key = "encrypt_new_uploads") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_encrypt_new_uploads),
                                subtitle = stringResource(R.string.settings_encrypt_new_uploads_sub),
                                checked = prefsState.isUploadEncryptionEnabled,
                                onChange = { settingsVm.setEncryptNewUploads(it) }
                            )
                        },
                        GroupedListItem(key = "encrypt_offline_files") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_encrypt_offline_files),
                                subtitle = stringResource(R.string.settings_encrypt_offline_files_sub),
                                checked = prefsState.isOfflineEncryptionEnabled,
                                onChange = { settingsVm.setEncryptOfflineFiles(it) }
                            )
                        },
                        GroupedListItem(key = "full_privacy_mode") {
                            SettingsSwitchRow(
                                title = stringResource(R.string.settings_full_privacy_mode),
                                subtitle = stringResource(R.string.settings_full_privacy_mode_sub),
                                checked = prefsState.fullPrivacyMode,
                                onChange = { settingsVm.setFullPrivacyMode(it) }
                            )
                        },
                        GroupedListItem(key = "encryption_status") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_encryption_status),
                                subtitle = encryptionStatusSubtitle,
                                onClick = { settingsVm.refreshKeyStatus() }
                            )
                        },
                        GroupedListItem(key = "recovery_backup_status") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_recovery_backup_status),
                                subtitle = backupStatus,
                                onClick = {}
                            )
                        },
                        GroupedListItem(key = "create_recovery_backup") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_create_recovery_backup),
                                subtitle = stringResource(R.string.settings_create_recovery_backup_sub),
                                onClick = { showCreateBackupDialog = true }
                            )
                        },
                        GroupedListItem(key = "restore_recovery_backup") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_restore_recovery_backup),
                                subtitle = stringResource(R.string.settings_restore_recovery_backup_sub),
                                onClick = { showRestoreBackupDialog = true }
                            )
                        }
                    )
                    GroupedList(items = encryptionItems)
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
                        GroupedListItem(key = "color_theme") {
                            val colorThemes = stringArrayResource(R.array.color_theme_options)
                            SettingsClickRow(
                                title = stringResource(R.string.settings_color_theme),
                                subtitle = when {
                                    prefsState.dynamicColor -> stringResource(R.string.settings_color_theme_dynamic_note)
                                    prefsState.colorTheme == ColorTheme.NOIR -> colorThemes[1]
                                    else -> colorThemes[0]
                                },
                                onClick = { activeDialogType = "COLOR_THEME" }
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
                                subtitle = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText("App Version", "Cloudyte v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                                    )
                                    Toast.makeText(context, "Version copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
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
                                onClick = { activeDialogType = "TOS_INFO" }
                            )
                        },
                        GroupedListItem(key = "privacy") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_privacy_policy),
                                onClick = { activeDialogType = "PRIVACY_INFO" }
                            )
                        },
                        GroupedListItem(key = "licenses") {
                            SettingsClickRow(
                                title = stringResource(R.string.settings_open_source_licenses),
                                onClick = { activeDialogType = "LICENSES_INFO" }
                            )
                        }
                    )
                    GroupedList(items = aboutItems)
                }
            }
            Spacer(modifier = Modifier.height(24.dp + com.cloudyte.app.ui.components.LocalBottomBarInset.current))
        }
    }

    // Dialogs
    when (activeDialogType) {
        "SCHEDULE" -> {
            val schedules = stringArrayResource(R.array.schedule_options)
            val currentScheduleIndex = when {
                !prefsState.autoBackupEnabled -> 0
                prefsState.backupIntervalHours <= 1 -> 1
                prefsState.backupIntervalHours <= 24 -> 2
                else -> 3
            }
            ChoiceDialog(
                title = stringResource(R.string.settings_backup_schedule),
                options = schedules.toList(),
                selectedIndex = currentScheduleIndex,
                onSelect = { idx ->
                    scope.launch {
                        if (idx == 0) {
                            UserPreferences.setAutoBackupEnabled(context, false)
                            runCatching { AutoSyncManager.setAutoBackup(context, false) }
                        } else {
                            val hours = when (idx) {
                                1 -> 1
                                2 -> 24
                                else -> 168
                            }
                            UserPreferences.setBackupIntervalHours(context, hours)
                            UserPreferences.setAutoBackupEnabled(context, true)
                            runCatching { AutoSyncManager.setAutoBackup(context, true) }
                        }
                    }
                },
                onDismiss = { activeDialogType = null }
            )
        }
        "TOS_INFO" -> {
            AlertDialog(
                onDismissRequest = { activeDialogType = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = {
                    Text(
                        text = stringResource(R.string.settings_terms_of_service),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Cloudyte is a client for managing your own connected cloud storage providers and self-hosted Cloudyte server. You are responsible for the credentials, keys, and content stored on your linked drives.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(onClick = { activeDialogType = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            )
        }
        "PRIVACY_INFO" -> {
            AlertDialog(
                onDismissRequest = { activeDialogType = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = {
                    Text(
                        text = stringResource(R.string.settings_privacy_policy),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "End-to-end encryption keys are stored locally in Android Keystore and never transmitted to the server. Redacted diagnostics are only exported when you explicitly choose to copy or share them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(onClick = { activeDialogType = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            )
        }
        "LICENSES_INFO" -> {
            AlertDialog(
                onDismissRequest = { activeDialogType = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                title = {
                    Text(
                        text = stringResource(R.string.settings_open_source_licenses),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Built with open-source software:\n• Jetpack Compose & Material 3 (Apache-2.0)\n• AndroidX Room, WorkManager & Security (Apache-2.0)\n• Retrofit & OkHttp (Apache-2.0)\n• AndroidX Media3 ExoPlayer (Apache-2.0)\n• Coil Image Loading (Apache-2.0)\n• Kotlinx Serialization & Coroutines (Apache-2.0)\n• Plus Jakarta Sans & JetBrains Mono (OFL-1.1)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    TextButton(onClick = { activeDialogType = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
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
        "COLOR_THEME" -> {
            val colorThemes = stringArrayResource(R.array.color_theme_options)
            ChoiceDialog(
                title = stringResource(R.string.settings_color_theme),
                options = colorThemes.toList(),
                selectedIndex = if (prefsState.colorTheme == ColorTheme.NOIR) 1 else 0,
                onSelect = { idx ->
                    val theme = if (idx == 1) ColorTheme.NOIR else ColorTheme.DEFAULT
                    scope.launch { UserPreferences.setColorTheme(context, theme) }
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
