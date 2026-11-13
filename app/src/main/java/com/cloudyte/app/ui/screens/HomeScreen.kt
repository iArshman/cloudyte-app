package com.cloudyte.app.ui.screens

import android.app.Application
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.cloudyte.app.R
import com.cloudyte.app.api.Account
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.api.FolderRequest
import com.cloudyte.app.api.StorageCategories
import com.cloudyte.app.api.StorageSummary
import com.cloudyte.app.api.UserProfile
import com.cloudyte.app.data.ALL_ACCOUNTS_STORAGE_ID
import com.cloudyte.app.data.CloudFileRepository
import com.cloudyte.app.data.FileCryptoActions
import com.cloudyte.app.data.StorageCategoryType
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.data.UserPreferencesState
import com.cloudyte.app.data.aggregateStorageSummaries
import com.cloudyte.app.data.calculateSegmentProportions
import com.cloudyte.app.data.formatStorageSize
import com.cloudyte.app.data.formatStorageUsageText
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalAccount
import com.cloudyte.app.db.LocalCloudFile
import com.cloudyte.app.ui.components.AppLoadingIndicator
import com.cloudyte.app.ui.components.FileThumb
import com.cloudyte.app.ui.components.LocalBottomBarInset
import com.cloudyte.app.ui.openFileInViewer
import com.cloudyte.app.ui.theme.BrandPrimary
import com.cloudyte.app.ui.theme.CategoryDocuments
import com.cloudyte.app.ui.theme.CategoryImages
import com.cloudyte.app.ui.theme.CategoryMedia
import com.cloudyte.app.ui.theme.CategoryOther
import com.cloudyte.app.ui.theme.CategoryUnknown
import com.cloudyte.app.ui.theme.CategoryZipped
import com.cloudyte.app.ui.theme.MetricLabelTextStyle
import com.cloudyte.app.ui.theme.MetricLargeTextStyle
import com.cloudyte.app.ui.theme.MetricTextStyle
import com.cloudyte.app.ui.theme.ProviderGoogle
import com.cloudyte.app.ui.theme.ProviderOneDrive
import com.cloudyte.app.ui.theme.ProviderTelegram
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repository = (application as? com.cloudyte.app.CloudyteApplication)?.container?.cloudFileRepository
        ?: CloudFileRepository(db, ApiClient.apiService)

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _currentAccountId = MutableStateFlow<String?>(null)
    val currentAccountId: StateFlow<String?> = _currentAccountId.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<CloudFile>>(emptyList())
    val recentFiles: StateFlow<List<CloudFile>> = _recentFiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val preferredStorageAccountFlow = UserPreferences.observe(application)
        .map { it.homeStorageAccountId }
        .distinctUntilChanged()

    val selectedStorageAccountId: StateFlow<String> = combine(
        preferredStorageAccountFlow,
        _accounts
    ) { preferredId, accs ->
        when {
            !preferredId.isNullOrBlank() && (preferredId == ALL_ACCOUNTS_STORAGE_ID || accs.isEmpty() || accs.any { it.effectiveId == preferredId }) ->
                preferredId
            accs.size > 1 -> ALL_ACCOUNTS_STORAGE_ID
            accs.size == 1 -> accs.first().effectiveId.ifBlank { ALL_ACCOUNTS_STORAGE_ID }
            else -> ALL_ACCOUNTS_STORAGE_ID
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ALL_ACCOUNTS_STORAGE_ID)

    val storageSummary: StateFlow<StorageSummary?> = selectedStorageAccountId
        .flatMapLatest { accountId ->
            repository.observeStorage(accountId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isStorageRefreshing: StateFlow<Boolean> = combine(
        selectedStorageAccountId,
        repository.storageRefreshing
    ) { accountId, refreshingSet ->
        accountId in refreshingSet
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val storageRefreshError: StateFlow<String?> = combine(
        selectedStorageAccountId,
        repository.storageRefreshErrors
    ) { accountId, errorsMap ->
        errorsMap[accountId]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectStorageAccount(accountId: String) {
        viewModelScope.launch {
            UserPreferences.setHomeStorageAccountId(getApplication(), accountId)
        }
    }

    fun loadData(forceStorageRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val cachedAccounts = db.accountDao().getAllAccounts()
                if (cachedAccounts.isNotEmpty()) {
                    _accounts.value = cachedAccounts.map { it.toAccount() }
                }

                val cachedDefault = _accounts.value.firstOrNull { it.isDefault == true } ?: _accounts.value.firstOrNull()
                val cachedAccountId = cachedDefault?.effectiveId?.takeIf { it.isNotBlank() && it != "root" }
                _currentAccountId.value = cachedAccountId
                val cachedFiles = db.cloudFileDao().getRecentFiles(8)
                if (cachedFiles.isNotEmpty()) {
                    _recentFiles.value = cachedFiles.map { it.toCloudFile() }.take(8)
                    FileRepository.cacheFiles(_recentFiles.value)
                }

                try {
                    val p = ApiClient.apiService.getProfile()
                    _profile.value = p
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Profile call failed: ${e.message}")
                }

                val accs = try {
                    ApiClient.apiService.getAccounts()
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Accounts call failed: ${e.message}")
                    emptyList()
                }
                if (accs.isNotEmpty()) {
                    _accounts.value = accs
                    db.accountDao().insertAccounts(accs.map { LocalAccount.fromAccount(it) })
                }

                val defaultAcc = _accounts.value.firstOrNull { it.isDefault == true } ?: _accounts.value.firstOrNull()
                val accId = defaultAcc?.effectiveId?.takeIf { it.isNotBlank() && it != "root" }
                _currentAccountId.value = accId

                if (forceStorageRefresh) {
                    repository.refreshStorage(selectedStorageAccountId.value, force = true)
                }

                repository.refresh(accId, "root", force = forceStorageRefresh)
                val updatedFiles = db.cloudFileDao().getRecentFiles(8).map { it.toCloudFile() }
                if (updatedFiles.isNotEmpty()) {
                    _recentFiles.value = updatedFiles.take(8)
                    FileRepository.cacheFiles(updatedFiles)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "General data loading error", e)
                _errorMessage.value = e.localizedMessage ?: "Failed to load cloud data"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createFolder(name: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val accId = _currentAccountId.value
                val res = repository.createFolder(name, accId, "root")
                if (res.isSuccess) {
                    loadData(forceStorageRefresh = false)
                    onSuccess()
                } else {
                    _errorMessage.value = res.exceptionOrNull()?.message ?: "Failed to create folder"
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Create folder error", e)
                _errorMessage.value = e.localizedMessage ?: "Failed to create folder"
            }
        }
    }

    fun createEncryptedCopy(accountId: String, targetFile: CloudFile, onFeedback: (String) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>() as? com.cloudyte.app.CloudyteApplication ?: return@launch
            try {
                val feedback = FileCryptoActions.createEncryptedCopy(
                    context = app,
                    accountId = accountId,
                    targetFile = targetFile,
                    fallbackParentFolderId = null,
                    cloudFileRepository = repository,
                    transferRepository = app.container.transferRepository,
                    transferCoordinator = app.container.transferCoordinator
                )
                onFeedback(feedback)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Encrypt copy failed", e)
                onFeedback(e.message ?: "Could not queue encrypted copy")
            }
        }
    }

    fun decryptAndExportFile(accountId: String, targetFile: CloudFile, onFeedback: (String) -> Unit) {
        viewModelScope.launch {
            val app = getApplication<Application>() as? com.cloudyte.app.CloudyteApplication ?: return@launch
            try {
                val feedback = FileCryptoActions.decryptAndExportFile(
                    context = app,
                    accountId = accountId,
                    targetFile = targetFile,
                    transferRepository = app.container.transferRepository,
                    transferCoordinator = app.container.transferCoordinator
                )
                onFeedback(feedback)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Decrypt export failed", e)
                onFeedback(e.message ?: "Failed to decrypt file")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: HomeViewModel = viewModel(),
    onSearchClick: (() -> Unit)? = null
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val currentAccountId by viewModel.currentAccountId.collectAsStateWithLifecycle()
    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val selectedStorageAccountId by viewModel.selectedStorageAccountId.collectAsStateWithLifecycle()
    val storageSummary by viewModel.storageSummary.collectAsStateWithLifecycle()
    val isStorageRefreshing by viewModel.isStorageRefreshing.collectAsStateWithLifecycle()
    val storageRefreshError by viewModel.storageRefreshError.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefsState by UserPreferences.observe(context).collectAsState(initial = UserPreferencesState())
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var isUploading by remember { mutableStateOf(false) }
    var isManualRefreshing by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }

    LaunchedEffect(isLoading) {
        if (!isLoading) isManualRefreshing = false
    }

    LaunchedEffect(Unit) {
        viewModel.loadData(forceStorageRefresh = false)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val accId = currentAccountId ?: accounts.firstOrNull()?.effectiveId
            if (!accId.isNullOrEmpty()) {
                isUploading = true
                coroutineScope.launch {
                    try {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        var name = "upload_${System.currentTimeMillis()}"
                        var size = 0L
                        context.contentResolver.query(it, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                            }
                        }
                        val app = context.applicationContext as com.cloudyte.app.CloudyteApplication
                        val encryptUploads = prefsState.isUploadEncryptionEnabled
                        val mimeType = runCatching { context.contentResolver.getType(it) }.getOrNull()
                        val transferId = app.container.transferRepository.enqueueTransfer(
                            accountId = accId,
                            fileName = name,
                            localPath = it.toString(),
                            direction = com.cloudyte.app.db.TransferDirection.UPLOAD,
                            totalBytes = size,
                            isEncrypted = encryptUploads,
                            originalName = if (encryptUploads) name else null,
                            originalMimeType = if (encryptUploads) mimeType else null,
                            originalSize = if (encryptUploads) size else null
                        )
                        app.container.transferCoordinator.dispatch(com.cloudyte.app.core.transfer.TransferCommand.Start(transferId))
                        snackbarHostState.showSnackbar("$name added to transfers")
                    } catch (error: Exception) {
                        snackbarHostState.showSnackbar(error.message ?: "Could not queue upload")
                    } finally {
                        isUploading = false
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = {
                Text(
                    text = stringResource(R.string.home_create_folder_title),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                OutlinedTextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = {
                        Text(
                            text = stringResource(R.string.home_folder_name_label),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderNameInput.isNotBlank()) {
                            viewModel.createFolder(folderNameInput.trim()) {
                                showCreateFolderDialog = false
                                folderNameInput = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = stringResource(R.string.action_create),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    val effectiveSummary: StorageSummary? = remember(storageSummary, accounts, selectedStorageAccountId, isStorageRefreshing, recentFiles) {
        val base = storageSummary ?: if (!isStorageRefreshing && accounts.isNotEmpty()) {
            deriveSummaryFromAccounts(accounts, selectedStorageAccountId, recentFiles)
        } else {
            null
        }
        if (base != null && base.categories == null) {
            base.copy(categories = deriveCategoriesFromFiles(recentFiles, base.usedBytes))
        } else {
            base
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            androidx.compose.material3.SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = LocalBottomBarInset.current)
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isManualRefreshing && isLoading,
            onRefresh = {
                isManualRefreshing = true
                viewModel.loadData(forceStorageRefresh = true)
            },
            indicator = {},
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Loading indicator pinned just below top bar
            AnimatedVisibility(
                visible = isLoading,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = LocalBottomBarInset.current + 88.dp
                )
            ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.home_welcome_back),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = profile?.displayUsername ?: stringResource(R.string.home_default_user),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { navController.navigateSingleTopRestore("settings") }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.nav_settings),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // Storage Card
            item {
                StorageOverviewCard(
                    summary = effectiveSummary,
                    isLoading = effectiveSummary == null && (isStorageRefreshing || isLoading),
                    refreshError = storageRefreshError,
                    accounts = accounts,
                    selectedAccountId = selectedStorageAccountId,
                    onSelectAccount = { viewModel.selectStorageAccount(it) }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(
                                onClick = { viewModel.loadData(forceStorageRefresh = true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(
                                    text = stringResource(R.string.action_retry),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Quick Actions Section
            item {
                Text(
                    text = stringResource(R.string.home_quick_actions),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(16.dp))
                QuickActionsRow(
                    onUploadClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onNewFolderClick = { showCreateFolderDialog = true },
                    onSearchClick = {
                        onSearchClick?.invoke() ?: navController.navigateSingleTopRestore("files")
                    },
                    onDrivesClick = { navController.navigateSingleTopRestore("accounts") }
                )
            }

            if (prefsState.showRecentFiles && recentFiles.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Recent Files",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(recentFiles, key = { it.effectiveId.ifEmpty { it.effectiveName } }) { file ->
                    val resolvedAccountId = file.accountId?.takeIf { it.isNotBlank() && it != "all" && it != "root" }
                        ?: currentAccountId?.takeIf { it.isNotBlank() && it != "all" && it != "root" }
                        ?: accounts.firstOrNull()?.effectiveId.orEmpty()
                    Box(modifier = Modifier.padding(vertical = 4.dp)) {
                        FileListItem(
                            file = file,
                            navController = rootNavController,
                            accountId = resolvedAccountId,
                            files = recentFiles,
                            onEncryptClick = {
                                viewModel.createEncryptedCopy(resolvedAccountId, file) { msg ->
                                    coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            },
                            onDecryptExportClick = {
                                viewModel.decryptAndExportFile(resolvedAccountId, file) { msg ->
                                    coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            }
                        )
                    }
                }
            }
        }
        }
    }
}

private fun deriveCategoriesFromFiles(
    files: List<CloudFile>,
    totalUsedBytes: Long
): StorageCategories {
    var images = 0L
    var documents = 0L
    var videos = 0L
    var archives = 0L
    var audio = 0L
    var other = 0L

    files.filter { !it.isFolderItem }.forEach { file ->
        val bytes = file.sizeBytes.coerceAtLeast(0L)
        val ext = file.effectiveType.lowercase()
        when {
            file.isImage -> images += bytes
            file.isVideo -> videos += bytes
            file.isAudio -> audio += bytes
            file.isPdf || file.isTextOrCode || ext in listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "rtf", "odt") ->
                documents += bytes
            ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso") ->
                archives += bytes
            else -> other += bytes
        }
    }

    val sum = images + documents + videos + archives + audio + other
    if (totalUsedBytes > sum) {
        other += (totalUsedBytes - sum)
    }

    return StorageCategories(
        images = images,
        documents = documents,
        videos = videos,
        archives = archives,
        audio = audio,
        other = other
    )
}

private fun deriveSummaryFromAccounts(
    accounts: List<Account>,
    selectedAccountId: String,
    recentFiles: List<CloudFile> = emptyList()
): StorageSummary {
    val perAccountSummaries = accounts.map { acc ->
        val isTg = acc.effectiveType.contains("tg", ignoreCase = true) ||
            acc.effectiveType.contains("tele", ignoreCase = true)
        StorageSummary(
            accountId = acc.effectiveId,
            accountType = acc.effectiveType,
            usedBytes = acc.usageBytes,
            totalBytes = if (isTg || acc.totalBytes <= 0L) null else acc.totalBytes,
            fileCount = acc.fileCount,
            categories = null
        )
    }
    val aggregated = if (selectedAccountId == ALL_ACCOUNTS_STORAGE_ID) {
        aggregateStorageSummaries(perAccountSummaries)
    } else {
        perAccountSummaries.firstOrNull { it.accountId == selectedAccountId }
            ?: aggregateStorageSummaries(perAccountSummaries)
    }
    return aggregated.copy(
        categories = aggregated.categories ?: deriveCategoriesFromFiles(recentFiles, aggregated.usedBytes)
    )
}

@Composable
fun StorageOverviewCard(
    summary: StorageSummary?,
    isLoading: Boolean = false,
    refreshError: String? = null,
    accounts: List<Account> = emptyList(),
    selectedAccountId: String = ALL_ACCOUNTS_STORAGE_ID,
    onSelectAccount: (String) -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_storage_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (isLoading && summary == null) {
                StorageCardSkeleton(hasMultipleAccounts = accounts.size > 1)
            } else {
                val activeSummary = summary ?: StorageSummary(
                    accountId = selectedAccountId,
                    accountType = "unknown",
                    usedBytes = 0L,
                    totalBytes = null,
                    categories = StorageCategories()
                )

                // Top Row: "Storage" on left, usage on right
                val defaultAccount = accounts.firstOrNull { it.isDefault == true } ?: accounts.firstOrNull()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Storage",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatStorageUsageText(activeSummary),
                            style = MetricLabelTextStyle,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("storage_usage_text")
                        )
                        if (refreshError != null && summary == null) {
                            Text(
                                text = "Couldn't refresh",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("storage_refresh_error")
                            )
                        }
                    }
                }

                if (defaultAccount != null) {
                    var accountsExpanded by remember { mutableStateOf(false) }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Tappable default account row — expands chip row (48dp min touch target)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { accountsExpanded = !accountsExpanded }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = defaultAccount.effectiveName.take(1).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = defaultAccount.effectiveName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (accounts.size > 1) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${accounts.size} accounts",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Icon(
                                    imageVector = if (accountsExpanded) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Account Switcher Chip Row (shown on tap when user has multiple drives)
                    if (accountsExpanded && accounts.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        StorageAccountChipRow(
                            accounts = accounts,
                            selectedAccountId = selectedAccountId,
                            onSelectAccount = onSelectAccount
                        )
                    }
                }

                val effectiveCategories = activeSummary.categories ?: deriveCategoriesFromFiles(emptyList(), activeSummary.usedBytes)
                val segments = remember(effectiveCategories, activeSummary.usedBytes) {
                    calculateSegmentProportions(effectiveCategories, activeSummary.usedBytes)
                }

                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    appeared = true
                }

                val animatedProportions = segments.map { segment ->
                    val anim by animateFloatAsState(
                        targetValue = if (appeared) segment.proportion else 0f,
                        animationSpec = tween(durationMillis = 600, easing = EmphasizedDecelerate),
                        label = "segment_${segment.category.name}"
                    )
                    anim
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Multi-segment horizontal storage bar
                val trackColor = CategoryUnknown.copy(alpha = 0.28f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(CircleShape)
                        .background(trackColor)
                        .testTag("storage_segmented_bar")
                ) {
                    var currentX = 0f
                    segments.forEachIndexed { index, segment ->
                        val prop = animatedProportions[index].coerceIn(0f, 1f)
                        val segWidth = (prop * size.width).coerceAtMost(size.width - currentX)
                        if (segWidth > 0f) {
                            drawRect(
                                color = categoryColor(segment.category),
                                topLeft = Offset(currentX, 0f),
                                size = Size(segWidth, size.height)
                            )
                            currentX += segWidth
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 2 rows x 3 columns Category Legend Grid
                val byCategory = remember(segments) { segments.associateBy { it.category } }
                val row1Items = listOf(
                    Triple("Images", CategoryImages, byCategory[StorageCategoryType.IMAGES]?.bytes ?: 0L),
                    Triple("Documents", CategoryDocuments, byCategory[StorageCategoryType.DOCUMENTS]?.bytes ?: 0L),
                    Triple("Media", CategoryMedia, byCategory[StorageCategoryType.VIDEOS]?.bytes ?: 0L)
                )
                val row2Items = listOf(
                    Triple("Other", CategoryOther, byCategory[StorageCategoryType.OTHER]?.bytes ?: 0L),
                    Triple("Zipped", CategoryZipped, byCategory[StorageCategoryType.ARCHIVES]?.bytes ?: 0L),
                    Triple("Unknown", CategoryUnknown, byCategory[StorageCategoryType.AUDIO]?.bytes ?: 0L)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.testTag("storage_category_legend")
                ) {
                    listOf(row1Items, row2Items).forEach { rowList ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowList.forEach { (label, pillColor, bytes) ->
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(6.dp)
                                            .height(28.dp)
                                            .clip(CircleShape)
                                            .background(pillColor)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatStorageSize(bytes),
                                            style = MetricTextStyle,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (accounts.isNotEmpty() || (activeSummary.fileCount != null && activeSummary.fileCount > 0)) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.home_total_files),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${activeSummary.fileCount ?: accounts.sumOf { it.fileCount ?: 0 }}",
                                style = MetricLargeTextStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = stringResource(R.string.home_linked_drives),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${accounts.size}",
                                style = MetricLargeTextStyle,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageAccountChipRow(
    accounts: List<Account>,
    selectedAccountId: String,
    onSelectAccount: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("storage_account_chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StorageAccountChip(
            label = "All accounts",
            avatarLetter = "A",
            avatarColor = BrandPrimary,
            selected = selectedAccountId == ALL_ACCOUNTS_STORAGE_ID,
            onClick = { onSelectAccount(ALL_ACCOUNTS_STORAGE_ID) },
            testTag = "storage_chip_all"
        )
        accounts.forEach { account ->
            val accId = account.effectiveId
            val pColor = when {
                account.effectiveType.contains("google", ignoreCase = true) ||
                    account.effectiveType.contains("gdrive", ignoreCase = true) -> ProviderGoogle
                account.effectiveType.contains("onedrive", ignoreCase = true) -> ProviderOneDrive
                else -> ProviderTelegram
            }
            StorageAccountChip(
                label = account.effectiveName,
                avatarLetter = account.effectiveName.take(1).uppercase(),
                avatarColor = pColor,
                selected = selectedAccountId == accId,
                onClick = { onSelectAccount(accId) },
                testTag = "storage_chip_$accId"
            )
        }
    }
}

@Composable
private fun StorageAccountChip(
    label: String,
    avatarLetter: String,
    avatarColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = CircleShape,
        color = bgColor,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(avatarColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatarLetter,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StorageCardSkeleton(hasMultipleAccounts: Boolean) {
    val transition = rememberInfiniteTransition(label = "storage_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    Column(modifier = Modifier.testTag("storage_card_skeleton")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor)
            )
            Box(
                modifier = Modifier
                    .width(136.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor)
            )
        }

        if (hasMultipleAccounts) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .width(88.dp)
                            .height(28.dp)
                            .clip(CircleShape)
                            .background(placeholderColor)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
                .background(placeholderColor)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(3) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(6.dp)
                                    .height(28.dp)
                                    .clip(CircleShape)
                                    .background(placeholderColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                    .background(placeholderColor)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(56.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(placeholderColor)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryColor(category: StorageCategoryType): Color = when (category) {
    StorageCategoryType.IMAGES -> CategoryImages
    StorageCategoryType.DOCUMENTS -> CategoryDocuments
    StorageCategoryType.VIDEOS -> CategoryMedia
    StorageCategoryType.OTHER -> CategoryOther
    StorageCategoryType.ARCHIVES -> CategoryZipped
    StorageCategoryType.AUDIO -> CategoryUnknown
}

@Composable
fun QuickActionsRow(
    onUploadClick: () -> Unit,
    onNewFolderClick: () -> Unit,
    onSearchClick: () -> Unit,
    onDrivesClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        QuickActionButton(Icons.Default.Upload, stringResource(R.string.action_upload), onUploadClick)
        QuickActionButton(Icons.Default.CreateNewFolder, stringResource(R.string.action_new_folder), onNewFolderClick)
        QuickActionButton(Icons.Default.Search, stringResource(R.string.action_search), onSearchClick)
        QuickActionButton(Icons.Default.Cloud, stringResource(R.string.action_drives), onDrivesClick)
    }
}

@Composable
fun QuickActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun FileListItem(
    file: CloudFile,
    navController: NavController,
    accountId: String,
    files: List<CloudFile>? = null,
    onEncryptClick: (() -> Unit)? = null,
    onDecryptExportClick: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                openFileInViewer(file, accountId, navController, files)
            }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (file.isFolderItem) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                FileThumb(
                    file = file,
                    accountId = accountId,
                    sizeDp = 48.dp,
                    cornerRadius = 12.dp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.effectiveName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (file.isFolderItem) stringResource(R.string.files_type_folder) else file.formattedSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
                    )
                    if (!file.isFolderItem) {
                        Text(
                            text = "• ${file.effectiveType.uppercase()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp)
                        )
                    }
                    if (file.isEncrypted) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Encrypted",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    text = "E2EE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.action_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = when {
                                    file.isEncrypted -> "Decrypt / Export"
                                    file.isFolderItem -> "Encrypt to new folder"
                                    else -> "Create encrypted copy"
                                },
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (file.isEncrypted) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            showMenu = false
                            if (file.isEncrypted) {
                                onDecryptExportClick?.invoke()
                            } else {
                                onEncryptClick?.invoke()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Details", color = MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {
                            showMenu = false
                            navController.navigate("file_detail/$accountId/${file.effectiveId}")
                        }
                    )
                }
            }
        }
    }
}
