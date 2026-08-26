package com.example.ui.screens

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.api.Account
import com.example.api.ApiClient
import com.example.api.OfflineFileManager
import com.example.api.UserProfile
import com.example.db.AppDatabase
import com.example.db.LocalAccount
import com.example.sync.AutoSyncManager
import com.example.sync.BackupWorker
import com.example.sync.SyncLog
import com.example.sync.SyncLogLevel
import com.example.sync.SyncLogger
import com.example.sync.SyncPreferences
import com.example.sync.SyncState
import com.example.sync.SyncStatusInfo
import com.example.ui.theme.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    val autoBackupEnabled = SyncPreferences.getAutoBackupEnabled(application)
    val backupUri = SyncPreferences.getBackupUri(application)
    val backupAccountId = SyncPreferences.getBackupAccountId(application)

    val syncStatus: StateFlow<SyncStatusInfo> = SyncLogger.statusFlow
    val syncLogs: StateFlow<List<SyncLog>> = SyncLogger.logsFlow

    init {
        SyncLogger.init(application)
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            // Load accounts from DB immediately for instant UI
            val cachedAccounts = db.accountDao().getAllAccounts()
            if (cachedAccounts.isNotEmpty()) {
                _accounts.value = cachedAccounts.map { it.toAccount() }
            }

            try {
                _profile.value = ApiClient.apiService.getProfile()
                val accs = ApiClient.apiService.getAccounts()
                _accounts.value = accs
                db.accountDao().insertAccounts(accs.map { LocalAccount.fromAccount(it) })
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "Profile error: ${e.message}")
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        AutoSyncManager.setAutoBackup(context, enabled)
    }

    fun syncNow(forceReupload: Boolean = false) {
        val context = getApplication<Application>()
        val modeText = if (forceReupload) "Force Re-upload All Files" else "Smart Sync (Upload New & Modified Only)"
        SyncLogger.log("Manual Sync requested: $modeText", SyncLogLevel.INFO)
        AutoSyncManager.triggerImmediateSync(context, forceReupload = forceReupload, isManual = true)
    }

    fun clearLogs() {
        SyncLogger.clearLogs()
    }

    fun setBackupUri(uri: String?) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            SyncPreferences.setBackupUri(context, uri)
            AutoSyncManager.onFolderOrAccountChanged(context)
            SyncLogger.log("Local backup folder updated.", SyncLogLevel.INFO)
        }
    }

    fun setBackupAccountId(accountId: String?) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            SyncPreferences.setBackupAccountId(context, accountId)
            AutoSyncManager.onFolderOrAccountChanged(context)
            SyncLogger.log("Target Cloud Drive updated.", SyncLogLevel.INFO)
        }
    }

    fun logout(onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                ApiClient.apiService.logout()
            } catch (e: Exception) {
            } finally {
                ApiClient.saveToken(null)
                onSuccess()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, rootNavController: NavController, viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsState(initial = false)
    val backupUri by viewModel.backupUri.collectAsState(initial = null)
    val backupAccountId by viewModel.backupAccountId.collectAsState(initial = null)
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncLogs by viewModel.syncLogs.collectAsState()

    var showAccountSelector by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showSyncOptionsDialog by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, flags)
            } catch (e: Exception) {
                Log.w("SettingsScreen", "Failed to persist URI permission", e)
            }
            viewModel.setBackupUri(it.toString())
        }
    }

    if (showSyncOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showSyncOptionsDialog = false },
            containerColor = SurfaceDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sync, contentDescription = null, tint = PurpleGradientStart)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Sync Backup Options", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Select your preferred synchronization mode for backing up local files to your cloud drive:",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    // Option 1: Smart Sync
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BackgroundDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSyncOptionsDialog = false
                                viewModel.syncNow(forceReupload = false)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(PurpleGradientStart.copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Smart Sync (Incremental)", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Uploads new & modified files only. Skips already uploaded files for maximum speed.", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }

                    // Option 2: Full Re-Upload
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BackgroundDark),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSyncOptionsDialog = false
                                viewModel.syncNow(forceReupload = true)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(0xFFFFA726).copy(alpha = 0.2f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFFFFA726), modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Force Re-upload All", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("Re-uploads all local files to the drive regardless of local cache.", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSyncOptionsDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showAccountSelector) {
        AlertDialog(
            onDismissRequest = { showAccountSelector = false },
            title = { Text("Select Backup Drive", color = TextPrimary) },
            containerColor = SurfaceDark,
            text = {
                if (accounts.isEmpty()) {
                    Text("No connected cloud accounts found. Please link an account first.", color = TextSecondary)
                } else {
                    LazyColumn {
                        items(accounts) { acc ->
                            ListItem(
                                headlineContent = { Text(acc.effectiveName, color = TextPrimary) },
                                supportingContent = { Text(acc.effectiveType, color = TextSecondary) },
                                modifier = Modifier.clickable {
                                    viewModel.setBackupAccountId(acc.effectiveId)
                                    showAccountSelector = false
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountSelector = false }) {
                    Text("Close", color = PurpleGradientStart)
                }
            }
        )
    }

    if (showLogsDialog) {
        SyncLogsDialog(
            logs = syncLogs,
            onDismiss = { showLogsDialog = false },
            onClear = { viewModel.clearLogs() }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Profile Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(60.dp).background(PurpleGradientStart, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        val name = profile?.displayUsername ?: "Cloud User"
                        Text(name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(profile?.email ?: "$name@cloudyte.app", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Cloud Accounts Management
        item {
            Text("Storage Accounts", color = PurpleGradientStart, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        navController.navigate("accounts")
                    }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(PurpleGradientStart.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.CloudQueue,
                            contentDescription = "Cloud Accounts",
                            tint = PurpleGradientStart,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Linked Cloud Accounts",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val accCount = accounts.size
                        Text(
                            text = if (accCount > 0) "$accCount connected cloud drive${if (accCount > 1) "s" else ""}" else "Connect Google Drive, OneDrive, Telegram",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "View Accounts",
                        tint = TextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Auto Backup & Sync Section
        item {
            Text("Auto Backup & Sync", color = PurpleGradientStart, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Auto Backup", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("Syncs selected folder in the background periodically", color = TextSecondary, fontSize = 12.sp)
                        }
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { viewModel.setAutoBackupEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PurpleGradientStart)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = BackgroundDark)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Local Folder
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { folderPickerLauncher.launch(null) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = PurpleGradientStart)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local Backup Folder", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val folderName = backupUri?.let {
                                try {
                                    DocumentFile.fromTreeUri(context, Uri.parse(it))?.name
                                } catch (_: Exception) {
                                    null
                                }
                            } ?: "Tap to select folder"
                            Text(folderName, color = if (backupUri != null) TextSecondary else Color(0xFFFFA726), fontSize = 13.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Target Cloud Drive
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAccountSelector = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = PurpleGradientStart)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Target Cloud Drive", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val driveName = accounts.find { it.effectiveId == backupAccountId }?.effectiveName
                            Text(driveName ?: "Tap to choose target drive", color = if (driveName != null) TextSecondary else Color(0xFFFFA726), fontSize = 13.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = BackgroundDark)
                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Status Box
                    SyncStatusCard(syncStatus = syncStatus)

                    Spacer(modifier = Modifier.height(16.dp))

                    val isSyncing = syncStatus.state == SyncState.SYNCING || syncStatus.state == SyncState.SCANNING

                    Button(
                        onClick = { showSyncOptionsDialog = true },
                        enabled = !isSyncing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PurpleGradientStart,
                            disabledContainerColor = PurpleGradientStart.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Syncing Files...", color = Color.White, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sync Now (Smart / Force)", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Sync Activity Logger Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sync Activity Logs", color = PurpleGradientStart, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (syncLogs.isNotEmpty()) {
                    TextButton(onClick = { showLogsDialog = true }) {
                        Text("View All (${syncLogs.size})", color = PurpleGradientStart, fontSize = 13.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (syncLogs.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("No sync activity logged yet", color = TextSecondary, fontSize = 13.sp)
                        }
                    } else {
                        val recentLogs = syncLogs.takeLast(4)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF13131F), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            recentLogs.forEach { logItem ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    val (badgeColor, badgeIcon) = when (logItem.level) {
                                        SyncLogLevel.SUCCESS -> Color(0xFF4CAF50) to "✓"
                                        SyncLogLevel.ERROR -> Color(0xFFEF5350) to "✗"
                                        SyncLogLevel.WARNING -> Color(0xFFFFA726) to "!"
                                        SyncLogLevel.INFO -> Color(0xFF42A5F5) to "•"
                                    }
                                    Text(
                                        text = logItem.timeFormatted,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(55.dp)
                                    )
                                    Text(
                                        text = "$badgeIcon ",
                                        color = badgeColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = logItem.message,
                                        color = when (logItem.level) {
                                            SyncLogLevel.ERROR -> Color(0xFFFF8A80)
                                            SyncLogLevel.SUCCESS -> Color(0xFFA5D6A7)
                                            SyncLogLevel.WARNING -> Color(0xFFFFE082)
                                            else -> TextPrimary
                                        },
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { viewModel.clearLogs() }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Clear Logs", color = TextSecondary, fontSize = 12.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = { showLogsDialog = true },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Logger", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // About & Logout
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("App Info", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Cloudyte Multi-Cloud Mobile v1.2", color = TextSecondary, fontSize = 13.sp)
                    Text("Unified Telegram, Google Drive & OneDrive Client", color = TextSecondary, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            Button(
                onClick = {
                    viewModel.logout {
                        rootNavController.navigate("login") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log Out", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun SyncStatusCard(syncStatus: SyncStatusInfo) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val isWorking = syncStatus.state == SyncState.SYNCING || syncStatus.state == SyncState.SCANNING

    val (statusColor, statusTitle) = when (syncStatus.state) {
        SyncState.IDLE -> TextSecondary to "Ready"
        SyncState.SCANNING -> PurpleGradientStart to "Scanning Local Files..."
        SyncState.SYNCING -> PurpleGradientStart to "Syncing to Cloud Drive..."
        SyncState.SUCCESS -> Color(0xFF4CAF50) to "Sync Complete"
        SyncState.ERROR -> Color(0xFFEF5350) to "Sync Failed"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161622), RoundedCornerShape(12.dp))
            .border(1.dp, if (isWorking) PurpleGradientStart.copy(alpha = pulseAlpha) else Color(0xFF232332), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor.copy(alpha = if (isWorking) pulseAlpha else 1f), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusTitle,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            if (syncStatus.lastSyncTime > 0) {
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(syncStatus.lastSyncTime))
                Text("Last: $timeStr", color = TextSecondary, fontSize = 11.sp)
            }
        }

        if (syncStatus.state == SyncState.SYNCING && syncStatus.totalFilesCount > 0) {
            Spacer(modifier = Modifier.height(10.dp))
            val progress = (syncStatus.uploadedFilesCount.toFloat() / syncStatus.totalFilesCount.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PurpleGradientStart,
                trackColor = Color(0xFF2C2C3E),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${syncStatus.uploadedFilesCount} of ${syncStatus.totalFilesCount} files",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = PurpleGradientStart,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        if (syncStatus.currentFile.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Processing: ${syncStatus.currentFile}",
                color = TextSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (syncStatus.lastSyncSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = syncStatus.lastSyncSummary,
                color = if (syncStatus.state == SyncState.ERROR) Color(0xFFFF8A80) else TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun SyncLogsDialog(
    logs: List<SyncLog>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F0F18)),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync Logger Terminal", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF07070D), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF1E1E2F), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text("No logs recorded yet.", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(logs) { log ->
                                val (badgeColor, badgeIcon) = when (log.level) {
                                    SyncLogLevel.SUCCESS -> Color(0xFF4CAF50) to "✓"
                                    SyncLogLevel.ERROR -> Color(0xFFEF5350) to "✗"
                                    SyncLogLevel.WARNING -> Color(0xFFFFA726) to "!"
                                    SyncLogLevel.INFO -> Color(0xFF42A5F5) to "•"
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = "${log.timeFormatted} ",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "$badgeIcon ",
                                        color = badgeColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = log.message,
                                        color = when (log.level) {
                                            SyncLogLevel.ERROR -> Color(0xFFFF8A80)
                                            SyncLogLevel.SUCCESS -> Color(0xFFA5D6A7)
                                            SyncLogLevel.WARNING -> Color(0xFFFFE082)
                                            else -> Color(0xFFD4D4D8)
                                        },
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            val allLogsText = logs.joinToString("\n") { "[${it.timeFormatted}] ${it.level.name}: ${it.message}" }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Sync Logs", allLogsText))
                            Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy All", color = TextSecondary, fontSize = 12.sp)
                    }

                    Row {
                        TextButton(onClick = onClear) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFEF5350))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", color = Color(0xFFEF5350), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Close", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
