package com.example.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.api.Account
import com.example.api.ApiClient
import com.example.db.AppDatabase
import com.example.db.LocalAccount
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadAccounts()
    }

    fun loadAccounts() {
        viewModelScope.launch {
            val cached = db.accountDao().getAllAccounts()
            if (cached.isNotEmpty()) {
                _accounts.value = cached.map { it.toAccount() }
            }

            _isLoading.value = cached.isEmpty()
            _errorMessage.value = null
            try {
                val accs = ApiClient.apiService.getAccounts()
                _accounts.value = accs
                db.accountDao().insertAccounts(accs.map { LocalAccount.fromAccount(it) })
            } catch (e: Exception) {
                Log.e("AccountsViewModel", "Failed to load accounts", e)
                if (_accounts.value.isEmpty()) {
                    _errorMessage.value = e.localizedMessage ?: "Failed to load accounts"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteAccount(id: String) {
        viewModelScope.launch {
            try {
                val res = ApiClient.apiService.deleteAccount(id)
                if (res.isSuccessful) {
                    db.accountDao().deleteAccountById(id)
                    loadAccounts()
                }
            } catch (e: Exception) {
                Log.e("AccountsViewModel", "Delete account error", e)
            }
        }
    }
}

@Composable
fun AccountsScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: AccountsViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    if (showAddDialog) {
        AddAccountDialog(
            onDismiss = { showAddDialog = false },
            onSuccess = {
                showAddDialog = false
                viewModel.loadAccounts()
            }
        )
    }

    Scaffold(
        containerColor = BackgroundDark
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (!navController.popBackStack()) {
                                    navController.navigate("settings")
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Storage Accounts",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { viewModel.loadAccounts() }) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PurpleGradientStart)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = errorMessage ?: "Failed to connect",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { viewModel.loadAccounts() },
                                colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart)
                            ) {
                                Text("Retry", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            if (accounts.isEmpty() && !isLoading) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = PurpleGradientStart,
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "No Linked Accounts",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Link your Telegram Channel as cloud storage with unlimited capacity.",
                                color = TextSecondary,
                                fontSize = 14.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Add TG Drive Account", color = Color.White)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else {
                items(accounts) { account ->
                    AccountCard(
                        account = account,
                        onDelete = { viewModel.deleteAccount(account.effectiveId) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                item {
                    AddAccountCard(onClick = { showAddDialog = true })
                }
            }
        }
    }
}

@Composable
fun AccountCard(account: Account, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val providerColor = remember(account.effectiveType) {
        when {
            account.effectiveType.contains("google", ignoreCase = true) -> Color(0xFF4285F4)
            account.effectiveType.contains("one", ignoreCase = true) -> Color(0xFF0078D4)
            else -> Color(0xFF229ED9)
        }
    }

    val providerIcon = remember(account.effectiveType) {
        when {
            account.effectiveType.contains("google", ignoreCase = true) -> Icons.Default.Cloud
            account.effectiveType.contains("one", ignoreCase = true) -> Icons.Default.Cloud
            else -> Icons.AutoMirrored.Filled.Send
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = SurfaceDark,
            title = { Text("Unlink Account", color = TextPrimary) },
            text = { Text("Are you sure you want to remove '${account.effectiveName}'?", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Remove", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(providerColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(providerIcon, contentDescription = null, tint = providerColor, modifier = Modifier.size(26.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(account.effectiveName, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = account.email ?: when {
                            account.effectiveType.contains("google", ignoreCase = true) -> "Google Drive"
                            account.effectiveType.contains("one", ignoreCase = true) -> "OneDrive"
                            else -> "Telegram Storage"
                        },
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
                if (account.isDefault == true) {
                    Box(
                        modifier = Modifier
                            .background(PurpleGradientStart.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Default", color = PurpleGradientStart, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = TextSecondary.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            }

            val percent = account.effectivePercentage
            if (percent > 0f || account.usageBytes > 0L) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { (percent / 100f).coerceIn(0.01f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = providerColor,
                    trackColor = BackgroundDark,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${formatBytes(account.usageBytes)} used",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        if (account.totalBytes > 0L) "${formatBytes(account.totalBytes)} total" else "Unlimited",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    return when {
        bytes >= 1024L * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f TB", bytes.toDouble() / (1024L * 1024 * 1024 * 1024))
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / (1024L * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024L * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024L)
        else -> "$bytes B"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountCard(onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add", tint = PurpleGradientStart)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add New Drive Account", color = PurpleGradientStart, fontWeight = FontWeight.SemiBold)
        }
    }
}
