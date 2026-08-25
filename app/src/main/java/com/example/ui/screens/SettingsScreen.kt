package com.example.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.api.Account
import com.example.api.ApiClient
import com.example.api.UserProfile
import com.example.sync.BackupWorker
import com.example.sync.SyncPreferences
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    val autoBackupEnabled = SyncPreferences.getAutoBackupEnabled(application)
    val backupUri = SyncPreferences.getBackupUri(application)
    val backupAccountId = SyncPreferences.getBackupAccountId(application)

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                _profile.value = ApiClient.apiService.getProfile()
                val accs = ApiClient.apiService.getAccounts()
                _accounts.value = accs
            } catch (e: Exception) {
                Log.w("SettingsViewModel", "Profile error: ${e.message}")
            }
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            SyncPreferences.setAutoBackupEnabled(context, enabled)
            if (enabled) {
                val workRequest = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "AutoBackup",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest
                )
            } else {
                WorkManager.getInstance(context).cancelUniqueWork("AutoBackup")
            }
        }
    }

    fun syncNow() {
        val context = getApplication<Application>()
        val workRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "SyncNow",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    fun setBackupUri(uri: String?) {
        viewModelScope.launch {
            SyncPreferences.setBackupUri(getApplication(), uri)
        }
    }

    fun setBackupAccountId(accountId: String?) {
        viewModelScope.launch {
            SyncPreferences.setBackupAccountId(getApplication(), accountId)
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
    
    var showAccountSelector by remember { mutableStateOf(false) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, flags)
            viewModel.setBackupUri(it.toString())
        }
    }
    
    if (showAccountSelector) {
        AlertDialog(
            onDismissRequest = { showAccountSelector = false },
            title = { Text("Select Backup Drive", color = TextPrimary) },
            containerColor = SurfaceDark,
            text = {
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
            },
            confirmButton = {
                TextButton(onClick = { showAccountSelector = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
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

        item {
            Text("Auto Backup & Sync", color = PurpleGradientStart, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Auto Backup", color = TextPrimary, fontSize = 16.sp)
                            Text("Syncs selected folder automatically in background", color = TextSecondary, fontSize = 12.sp)
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
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { folderPickerLauncher.launch(null) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = PurpleGradientStart)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local Backup Folder", color = TextPrimary, fontSize = 16.sp)
                            val folderName = backupUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } ?: "Not selected"
                            Text(folderName, color = TextSecondary, fontSize = 13.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { showAccountSelector = true }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = PurpleGradientStart)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Target Cloud Drive", color = TextPrimary, fontSize = 16.sp)
                            val driveName = accounts.find { it.effectiveId == backupAccountId }?.effectiveName ?: "Not selected"
                            Text(driveName, color = TextSecondary, fontSize = 13.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.syncNow() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart)
                    ) {
                        Text("Sync Now", color = Color.White)
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
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
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.15f), contentColor = Color.Red),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Logout", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
