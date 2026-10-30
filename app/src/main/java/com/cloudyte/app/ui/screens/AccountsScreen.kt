package com.cloudyte.app.ui.screens

import android.app.Application
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.cloudyte.app.R
import com.cloudyte.app.api.Account
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.UserSettingsRequest
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.ui.components.AppLoadingIndicator
import com.cloudyte.app.ui.components.LocalBottomBarInset
import com.cloudyte.app.ui.theme.ProviderGoogle
import com.cloudyte.app.ui.theme.ProviderOneDrive
import com.cloudyte.app.ui.theme.ProviderTelegram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// EmphasizedDecelerate standard easing
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadAccounts(initial = true)
    }

    fun loadAccounts(initial: Boolean = false) {
        viewModelScope.launch {
            if (initial) {
                val cached = db.accountDao().getAllAccounts()
                if (cached.isNotEmpty()) {
                    _accounts.value = cached.map { it.toAccount() }
                    _isLoading.value = false
                }
            } else {
                _isRefreshing.value = true
            }

            _errorMessage.value = null
            try {
                val accs = ApiClient.apiService.getAccounts()
                _accounts.value = accs
                db.accountDao().insertAccounts(accs.map { LocalAccount.fromAccount(it) })
            } catch (e: Exception) {
                Log.e("AccountsViewModel", "Failed to load accounts", e)
                if (_accounts.value.isEmpty()) {
                    _errorMessage.value = e.localizedMessage ?: "Failed to connect to accounts"
                }
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
            }
        }
    }

    fun deleteAccount(id: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val res = ApiClient.apiService.deleteAccount(id)
                if (res.isSuccessful) {
                    db.accountDao().deleteAccountById(id)
                    _accounts.value = _accounts.value.filter { it.effectiveId != id }
                }
            } catch (e: Exception) {
                Log.e("AccountsViewModel", "Delete account error", e)
                db.accountDao().deleteAccountById(id)
                _accounts.value = _accounts.value.filter { it.effectiveId != id }
            } finally {
                onComplete()
            }
        }
    }

    fun setDefaultAccount(id: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                ApiClient.apiService.updateSettings(UserSettingsRequest(backupEnabled = false, backupAccId = null, isDefaultAccId = id))
            } catch (e: Exception) {
                Log.e("AccountsViewModel", "Set default account error", e)
            }
            // Update local memory & cache
            _accounts.value = _accounts.value.map {
                it.copy(isDefault = (it.effectiveId == id))
            }
            db.accountDao().insertAccounts(_accounts.value.map { LocalAccount.fromAccount(it) })
            onComplete()
        }
    }

    fun renameAccount(id: String, newName: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            _accounts.value = _accounts.value.map {
                if (it.effectiveId == id) it.copy(name = newName, driveName = newName) else it
            }
            db.accountDao().insertAccounts(_accounts.value.map { LocalAccount.fromAccount(it) })
            onComplete()
        }
    }
}

private sealed interface AccountsScreenUiState {
    data object Loading : AccountsScreenUiState
    data class Error(val message: String) : AccountsScreenUiState
    data object Empty : AccountsScreenUiState
    data class Success(val accounts: List<Account>) : AccountsScreenUiState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountsScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: AccountsViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var selectedAddProvider by remember { mutableStateOf(CloudProviderType.GOOGLE_DRIVE) }
    var fabMenuExpanded by remember { mutableStateOf(false) }

    var accountForDisconnect by remember { mutableStateOf<Account?>(null) }
    var accountForRename by remember { mutableStateOf<Account?>(null) }
    var renameInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val canScrollBackward by remember { derivedStateOf { listState.canScrollBackward } }

    val topBarContainerColor = if (canScrollBackward) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    // UI state determination
    val uiState: AccountsScreenUiState = when {
        isLoading && accounts.isEmpty() -> AccountsScreenUiState.Loading
        errorMessage != null && accounts.isEmpty() -> AccountsScreenUiState.Error(errorMessage ?: "Connection failed")
        accounts.isEmpty() -> AccountsScreenUiState.Empty
        else -> AccountsScreenUiState.Success(accounts)
    }

    // Add Drive Dialog
    if (showAddDialog) {
        AddAccountDialog(
            initialProvider = selectedAddProvider,
            onDismiss = { showAddDialog = false },
            onSuccess = {
                showAddDialog = false
                viewModel.loadAccounts()
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Storage account linked successfully")
                }
            }
        )
    }

    // Disconnect Confirmation Dialog
    if (accountForDisconnect != null) {
        val target = accountForDisconnect
        val providerName = when {
            target?.effectiveType?.contains("google", ignoreCase = true) == true -> "Google Drive"
            target?.effectiveType?.contains("one", ignoreCase = true) == true -> "OneDrive"
            else -> "Telegram"
        }
        AlertDialog(
            onDismissRequest = { accountForDisconnect = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = "Disconnect account?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Remove ${target?.effectiveName}? Your files stay in $providerName.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val acc = target ?: return@Button
                        accountForDisconnect = null
                        viewModel.deleteAccount(acc.effectiveId) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Account disconnected")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountForDisconnect = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // Rename Dialog
    if (accountForRename != null) {
        AlertDialog(
            onDismissRequest = { accountForRename = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = "Rename Drive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("Drive Name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val acc = accountForRename ?: return@Button
                        if (renameInput.isNotBlank()) {
                            viewModel.renameAccount(acc.effectiveId, renameInput.trim()) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Drive renamed")
                                }
                            }
                        }
                        accountForRename = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.onPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountForRename = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Storage Accounts",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = { /* No back arrow on top-level tab */ },
                actions = {
                    IconButton(onClick = { viewModel.loadAccounts() }) {
                        if (isRefreshing) {
                            AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh accounts",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarContainerColor,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabMenuExpanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = fabMenuExpanded,
                        onCheckedChange = { fabMenuExpanded = it },
                        modifier = Modifier.testTag("accounts_add_fab")
                    ) {
                        val rotation by animateFloatAsState(
                            targetValue = if (fabMenuExpanded) 45f else 0f,
                            label = "FabRotation"
                        )
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = if (fabMenuExpanded) "Close add drive menu" else "Add drive",
                            modifier = Modifier.rotate(rotation)
                        )
                    }
                }
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        fabMenuExpanded = false
                        selectedAddProvider = CloudProviderType.TELEGRAM
                        showAddDialog = true
                    },
                    text = { Text("Telegram Cloud") },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = ProviderTelegram) }
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        fabMenuExpanded = false
                        selectedAddProvider = CloudProviderType.GOOGLE_DRIVE
                        showAddDialog = true
                    },
                    text = { Text("Google Drive") },
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null, tint = ProviderGoogle) }
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        fabMenuExpanded = false
                        selectedAddProvider = CloudProviderType.ONEDRIVE
                        showAddDialog = true
                    },
                    text = { Text("OneDrive") },
                    icon = { Icon(Icons.Default.CloudQueue, contentDescription = null, tint = ProviderOneDrive) }
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.loadAccounts() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = uiState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(200))
                },
                label = "AccountsScreenUiStateTransition",
                modifier = Modifier.fillMaxSize()
            ) { state ->
                when (state) {
                    is AccountsScreenUiState.Loading -> {
                        AccountsLoadingSkeleton()
                    }
                    is AccountsScreenUiState.Error -> {
                        AccountsErrorState(
                            message = state.message,
                            onRetry = { viewModel.loadAccounts() }
                        )
                    }
                    is AccountsScreenUiState.Empty -> {
                        AccountsEmptyState(
                            onAddAccount = {
                                selectedAddProvider = CloudProviderType.TELEGRAM
                                showAddDialog = true
                            }
                        )
                    }
                    is AccountsScreenUiState.Success -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(
                                top = 12.dp,
                                bottom = LocalBottomBarInset.current + 88.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = state.accounts,
                                key = { it.effectiveId }
                            ) { account ->
                                AccountCard(
                                    account = account,
                                    onRename = {
                                        renameInput = account.effectiveName
                                        accountForRename = account
                                    },
                                    onSetDefault = {
                                        viewModel.setDefaultAccount(account.effectiveId) {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("${account.effectiveName} set as default drive")
                                            }
                                        }
                                    },
                                    onRefresh = {
                                        viewModel.loadAccounts()
                                    },
                                    onDisconnect = {
                                        accountForDisconnect = account
                                    },
                                    onReconnect = {
                                        selectedAddProvider = when {
                                            account.effectiveType.contains("google", true) -> CloudProviderType.GOOGLE_DRIVE
                                            account.effectiveType.contains("one", true) -> CloudProviderType.ONEDRIVE
                                            else -> CloudProviderType.TELEGRAM
                                        }
                                        showAddDialog = true
                                    },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountCard(
    account: Account,
    onRename: () -> Unit,
    onSetDefault: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val isTelegram = remember(account.effectiveType) {
        account.effectiveType.contains("tele", ignoreCase = true) || account.channelId != null
    }

    val providerColor = remember(account.effectiveType) {
        when {
            account.effectiveType.contains("google", ignoreCase = true) -> ProviderGoogle
            account.effectiveType.contains("one", ignoreCase = true) -> ProviderOneDrive
            else -> ProviderTelegram
        }
    }

    val providerIcon: ImageVector = remember(account.effectiveType) {
        when {
            account.effectiveType.contains("google", ignoreCase = true) -> Icons.Default.Cloud
            account.effectiveType.contains("one", ignoreCase = true) -> Icons.Default.CloudQueue
            else -> Icons.AutoMirrored.Filled.Send
        }
    }

    val isAccountError = false // normal active state unless disconnected
    val percent = account.effectivePercentage
    val progressValue = (percent / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progressValue,
        animationSpec = tween(durationMillis = 600, easing = EmphasizedDecelerate),
        label = "AccountStorageProgress"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Provider Badge + Account Name & Subtitle + Overflow Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 48.dp rounded-square provider badge (12% tint of provider color)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(providerColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = providerIcon,
                        contentDescription = null,
                        tint = providerColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = account.effectiveName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (account.isDefault == true) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = "Default",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val subtitleText = account.email ?: if (isTelegram) {
                        "Channel: ${account.channelId ?: "Connected"}"
                    } else {
                        account.effectiveType
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Three-dot overflow menu
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Account options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        if (account.isDefault != true) {
                            DropdownMenuItem(
                                text = { Text("Set as default", color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuExpanded = false
                                    onSetDefault()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Refresh", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface) },
                            onClick = {
                                menuExpanded = false
                                onRefresh()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(
                            text = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDisconnect()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Storage Section
            if (isTelegram) {
                // Telegram is unlimited storage
                val usageFormatted = if (account.usageBytes > 0L) formatBytes(account.usageBytes) else "61.4 MB"
                Text(
                    text = "$usageFormatted used · Unlimited",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Google Drive / OneDrive bounded storage
                val usageText = formatBytes(account.usageBytes)
                val totalText = if (account.totalBytes > 0L) formatBytes(account.totalBytes) else "10.0 TB"
                val percentText = String.format(Locale.US, "%.1f%%", percent.coerceIn(0f, 100f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$usageText used of $totalText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = percentText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val indicatorColor = if (percent >= 90f) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape),
                    color = indicatorColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
            }

            // Error Chip if account has error
            if (isAccountError) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Disconnected",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = onReconnect,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Reconnect", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun AccountsEmptyState(onAddAccount: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CloudQueue,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No drives connected",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Link your Telegram Channel, Google Drive, or OneDrive to start managing your cloud files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAddAccount,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = MaterialTheme.shapes.large
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add drive", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun AccountsErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Connection issue",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
fun AccountsLoadingSkeleton() {
    val transition = rememberInfiniteTransition(label = "SkeletonTransition")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "SkeletonAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.5f)
                                    .height(18.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.35f)
                                    .height(12.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(14.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
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
