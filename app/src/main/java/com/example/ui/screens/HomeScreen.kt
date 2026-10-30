package com.example.ui.screens

import android.app.Application
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
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
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.R
import com.example.api.Account
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.FileRepository
import com.example.api.FolderRequest
import com.example.api.StorageSummary
import com.example.api.UserProfile
import com.example.data.ALL_ACCOUNTS_STORAGE_ID
import com.example.data.CloudFileRepository
import com.example.data.StorageCategoryType
import com.example.data.UserPreferences
import com.example.data.aggregateStorageSummaries
import com.example.data.calculateSegmentProportions
import com.example.data.formatStorageSize
import com.example.data.formatStorageUsageText
import com.example.db.AppDatabase
import com.example.db.LocalAccount
import com.example.db.LocalCloudFile
import com.example.ui.components.AppLoadingIndicator
import com.example.ui.components.FileThumb
import com.example.ui.components.LocalBottomBarInset
import com.example.ui.openFileInViewer
import com.example.ui.theme.BrandPrimary
import com.example.ui.theme.DarkPrimaryContainer
import com.example.ui.theme.FileAudio
import com.example.ui.theme.FileDoc
import com.example.ui.theme.FileImage
import com.example.ui.theme.FileUnknown
import com.example.ui.theme.FileVideo
import com.example.ui.theme.FileZip
import com.example.ui.theme.ProviderGoogle
import com.example.ui.theme.ProviderOneDrive
import com.example.ui.theme.ProviderTelegram
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
    private val repository = CloudFileRepository(db, ApiClient.apiService)

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _currentAccountId = MutableStateFlow<String?>(null)
    val currentAccountId: StateFlow<String?> = _currentAccountId.asStateFlow()

    // Recent files come from Room immediately — no waiting for the network
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
        .flatMapLatest { accountId -> repository.observeStorage(accountId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isStorageRefreshing: StateFlow<Boolean> = combine(
        selectedStorageAccountId,
        repository.storageRefreshing
    ) { accountId, refreshingSet -> accountId in refreshingSet }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val storageRefreshError: StateFlow<String?> = combine(
        selectedStorageAccountId,
        repository.storageRefreshErrors
    ) { accountId, errorsMap -> errorsMap[accountId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        loadData()
    }

    fun selectStorageAccount(accountId: String) {
        viewModelScope.launch {
            UserPreferences.setHomeStorageAccountId(getApplication(), accountId)
        }
    }

    fun loadData(forceStorageRefresh: Boolean = false) {
        viewModelScope.launch {
            // Show cached Room data immediately — no spinner while cache exists
            val cachedAccounts = db.accountDao().getAllAccounts()
            if (cachedAccounts.isNotEmpty()) {
                val accs = cachedAccounts.map { it.toAccount() }
                _accounts.value = accs
                val defaultAcc = accs.firstOrNull { it.isDefault == true } ?: accs.firstOrNull()
                val accId = defaultAcc?.effectiveId?.takeIf { it.isNotBlank() && it != "root" }
                _currentAccountId.value = accId
                if (accId != null && _recentFiles.value.isEmpty()) {
                    // Emit Room files instantly — UI shows them before any API call
                    val cached = db.cloudFileDao().getFilesForFolder(accId, "root")
                    if (cached.isNotEmpty()) {
                        _recentFiles.value = cached.map { it.toCloudFile() }.take(8)
                    }
                }
            }

            // Only show loading indicator if there's truly nothing cached to show
            val showSpinner = _recentFiles.value.isEmpty()
            if (showSpinner) _isLoading.value = true
            _errorMessage.value = null

            try {
                // Profile — non-critical, swallow errors
                try { _profile.value = ApiClient.apiService.getProfile() } catch (_: Exception) {}

                // Accounts — sync then update Room
                val fetchedAccs = try {
                    ApiClient.apiService.getAccounts()
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Accounts call failed: ${e.message}")
                    emptyList()
                }
                if (fetchedAccs.isNotEmpty()) {
                    _accounts.value = fetchedAccs
                    db.accountDao().insertAccounts(fetchedAccs.map { LocalAccount.fromAccount(it) })
                }

                val defaultAcc = _accounts.value.firstOrNull { it.isDefault == true } ?: _accounts.value.firstOrNull()
                val accId = defaultAcc?.effectiveId?.takeIf { it.isNotBlank() && it != "root" }
                _currentAccountId.value = accId

                if (forceStorageRefresh) {
                    repository.refreshStorage(selectedStorageAccountId.value, force = true)
                }

                // Sync files via repository — Room gets updated, UI observes via _recentFiles
                if (accId != null) {
                    val result = repository.refresh(accId, null)
                    if (result.isSuccess) {
                        val fresh = db.cloudFileDao().getFilesForFolder(accId, "root")
                        if (fresh.isNotEmpty()) {
                            _recentFiles.value = fresh.map { it.toCloudFile() }.take(8)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "General data loading error", e)
                if (_recentFiles.value.isEmpty()) {
                    _errorMessage.value = e.localizedMessage ?: "Failed to load cloud data"
                }
            } finally {
                if (showSpinner) _isLoading.value = false
            }
        }
    }

    fun createFolder(name: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val accId = _currentAccountId.value
                val response = ApiClient.apiService.createFolder(accId, FolderRequest(name))
                if (response.isSuccessful) {
                    loadData(forceStorageRefresh = true)
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Create folder error", e)
            }
        }
    }
}

@Composable
fun HomeScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: HomeViewModel = viewModel()
) {
    val profile by viewModel.profile.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val currentAccountId by viewModel.currentAccountId.collectAsState()
    val recentFiles by viewModel.recentFiles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val selectedStorageAccountId by viewModel.selectedStorageAccountId.collectAsState()
    val storageSummary by viewModel.storageSummary.collectAsState()
    val isStorageRefreshing by viewModel.isStorageRefreshing.collectAsState()
    val storageRefreshError by viewModel.storageRefreshError.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val accId = currentAccountId ?: accounts.firstOrNull()?.effectiveId
            if (!accId.isNullOrEmpty()) {
                isUploading = true
                coroutineScope.launch {
                    val uploadResult = UploadHelper.uploadFile(context, accId, null, it)
                    isUploading = false
                    if (uploadResult.isSuccess) viewModel.loadData(forceStorageRefresh = true)
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.home_create_folder_title), color = MaterialTheme.colorScheme.onSurface) },
            text = {
                OutlinedTextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = { Text(stringResource(R.string.home_folder_name_label), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
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
                                showCreateFolderDialog = false; folderNameInput = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text(stringResource(R.string.action_create), color = MaterialTheme.colorScheme.onPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    val isDark = isSystemInDarkTheme()
    val headerBgColor = if (isDark) DarkPrimaryContainer else BrandPrimary

    val effectiveSummary: StorageSummary? = remember(storageSummary, accounts, selectedStorageAccountId, isStorageRefreshing) {
        storageSummary ?: if (!isStorageRefreshing && accounts.isNotEmpty()) {
            deriveSummaryFromAccounts(accounts, selectedStorageAccountId)
        } else null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (isUploading) {
                FloatingActionButton(onClick = {}, containerColor = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = LocalBottomBarInset.current)) {
                    AppLoadingIndicator(color = MaterialTheme.colorScheme.onPrimary, size = 24.dp)
                }
            } else {
                FloatingActionButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(bottom = LocalBottomBarInset.current).testTag("home_upload_fab")
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_upload_file)) }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = LocalBottomBarInset.current + 88.dp)
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().background(headerBgColor)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding()
                            .padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 44.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(stringResource(R.string.home_welcome_back), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                                Text("My Files", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = { viewModel.loadData(forceStorageRefresh = true) },
                                    modifier = Modifier.size(40.dp).background(Color.White.copy(alpha = 0.16f), CircleShape).testTag("home_refresh_button")
                                ) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh), tint = Color.White) }
                                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.22f), modifier = Modifier.size(40.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text((profile?.displayUsername ?: "U").take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        StorageOverviewCard(
                            summary = effectiveSummary,
                            isLoading = effectiveSummary == null && (isStorageRefreshing || isLoading),
                            refreshError = storageRefreshError,
                            accounts = accounts,
                            selectedAccountId = selectedStorageAccountId,
                            onSelectAccount = { viewModel.selectStorageAccount(it) }
                        )
                    }
                }
            }

            item {
                Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
                        if (errorMessage != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(errorMessage ?: "", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                    Button(onClick = { viewModel.loadData(forceStorageRefresh = true) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                                        Text(stringResource(R.string.action_retry), fontSize = 12.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                        Text(stringResource(R.string.home_quick_actions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(16.dp))
                        QuickActionsRow(
                            onUploadClick = { filePickerLauncher.launch("*/*") },
                            onNewFolderClick = { showCreateFolderDialog = true },
                            onSearchClick = { navController.navigateSingleTopRestore("files") },
                            onDrivesClick = { navController.navigateSingleTopRestore("accounts") }
                        )
                        if (recentFiles.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(28.dp))
                            Text("Recent Files", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }

            if (recentFiles.isNotEmpty()) {
                items(recentFiles, key = { it.effectiveId }) { file ->
                    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
                        FileListItem(file = file, navController = rootNavController, accountId = currentAccountId ?: accounts.firstOrNull()?.effectiveId.orEmpty(), files = recentFiles)
                    }
                }
            }
        }
    }
}

private fun deriveSummaryFromAccounts(accounts: List<Account>, selectedAccountId: String): StorageSummary {
    val perAccountSummaries = accounts.map { acc ->
        val isTg = acc.effectiveType.contains("tg", ignoreCase = true) || acc.effectiveType.contains("tele", ignoreCase = true)
        StorageSummary(accountId = acc.effectiveId, accountType = acc.effectiveType, usedBytes = acc.usageBytes, totalBytes = if (isTg || acc.totalBytes <= 0L) null else acc.totalBytes, fileCount = acc.fileCount, categories = null)
    }
    return if (selectedAccountId == ALL_ACCOUNTS_STORAGE_ID) aggregateStorageSummaries(perAccountSummaries)
    else perAccountSummaries.firstOrNull { it.accountId == selectedAccountId } ?: aggregateStorageSummaries(perAccountSummaries)
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
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth()
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(28.dp), ambientColor = BrandPrimary.copy(alpha = 0.14f), spotColor = BrandPrimary.copy(alpha = 0.14f))
            .testTag("home_storage_card")
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (isLoading && summary == null) {
                StorageCardSkeleton(hasMultipleAccounts = accounts.size > 1)
            } else {
                val activeSummary = summary ?: StorageSummary(accountId = selectedAccountId, accountType = "unknown", usedBytes = 0L, totalBytes = null, categories = null)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatStorageUsageText(activeSummary), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("storage_usage_text"))
                        if (refreshError != null) {
                            Text("Couldn't refresh", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.testTag("storage_refresh_error"))
                        }
                    }
                }

                // Account switcher — now uses FilterChip for consistency with FilesScreen
                if (accounts.size > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    StorageAccountChipRow(accounts = accounts, selectedAccountId = selectedAccountId, onSelectAccount = onSelectAccount)
                }

                val categories = activeSummary.categories
                val totalBytes = activeSummary.totalBytes
                val showSingleBar = categories == null && !activeSummary.isUnlimited && totalBytes != null && totalBytes > 0L

                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appeared = true }

                if (categories != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val segments = remember(categories, activeSummary.usedBytes) { calculateSegmentProportions(categories, activeSummary.usedBytes) }
                    val animatedProportions = segments.map { segment ->
                        val anim by animateFloatAsState(targetValue = if (appeared) segment.proportion else 0f, animationSpec = tween(durationMillis = 600, easing = EmphasizedDecelerate), label = "segment_${segment.category.name}")
                        anim
                    }
                    val trackColor = MaterialTheme.colorScheme.surfaceVariant
                    Canvas(modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(trackColor).testTag("storage_segmented_bar")) {
                        var currentX = 0f
                        segments.forEachIndexed { index, segment ->
                            val prop = animatedProportions[index].coerceIn(0f, 1f)
                            val segWidth = (prop * size.width).coerceAtMost(size.width - currentX)
                            if (segWidth > 0f) {
                                drawRect(color = categoryColor(segment.category), topLeft = Offset(currentX, 0f), size = Size(segWidth, size.height))
                                currentX += segWidth
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("storage_category_legend")) {
                        segments.chunked(3).forEach { rowSegments ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowSegments.forEach { segment ->
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.width(4.dp).height(26.dp).clip(CircleShape).background(categoryColor(segment.category)))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(segment.category.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(formatStorageSize(segment.bytes), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (showSingleBar && totalBytes != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val targetFraction = (activeSummary.usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    val animatedFraction by animateFloatAsState(targetValue = if (appeared) targetFraction else 0f, animationSpec = tween(durationMillis = 600, easing = EmphasizedDecelerate), label = "single_storage_bar")
                    val trackColor = MaterialTheme.colorScheme.surfaceVariant
                    val barColor = MaterialTheme.colorScheme.primary
                    Canvas(modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(trackColor).testTag("storage_single_bar")) {
                        val fillWidth = (animatedFraction * size.width).coerceIn(0f, size.width)
                        if (fillWidth > 0f) drawRect(color = barColor, topLeft = Offset.Zero, size = Size(fillWidth, size.height))
                    }
                }
            }
        }
    }
}

// Uses FilterChip — consistent with FilesScreen AccountSwitcherChipRow
@Composable
private fun StorageAccountChipRow(
    accounts: List<Account>,
    selectedAccountId: String,
    onSelectAccount: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("storage_account_chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            FilterChip(
                selected = selectedAccountId == ALL_ACCOUNTS_STORAGE_ID,
                onClick = { onSelectAccount(ALL_ACCOUNTS_STORAGE_ID) },
                label = { Text("All", style = MaterialTheme.typography.labelMedium, fontWeight = if (selectedAccountId == ALL_ACCOUNTS_STORAGE_ID) FontWeight.Bold else FontWeight.Medium) },
                leadingIcon = {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(BrandPrimary.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text("A", color = BrandPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, containerColor = MaterialTheme.colorScheme.surfaceContainer, labelColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.testTag("storage_chip_all")
            )
        }
        items(accounts, key = { it.effectiveId }) { account ->
            val accId = account.effectiveId
            val pColor = when {
                account.effectiveType.contains("google", ignoreCase = true) || account.effectiveType.contains("gdrive", ignoreCase = true) -> ProviderGoogle
                account.effectiveType.contains("onedrive", ignoreCase = true) -> ProviderOneDrive
                else -> ProviderTelegram
            }
            val initial = account.effectiveName.take(1).uppercase()
            FilterChip(
                selected = selectedAccountId == accId,
                onClick = { onSelectAccount(accId) },
                label = { Text(account.effectiveName, style = MaterialTheme.typography.labelMedium, fontWeight = if (selectedAccountId == accId) FontWeight.Bold else FontWeight.Medium, maxLines = 1) },
                leadingIcon = {
                    Box(Modifier.size(16.dp).clip(CircleShape).background(pColor.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Text(initial, color = pColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer, containerColor = MaterialTheme.colorScheme.surfaceContainer, labelColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.testTag("storage_chip_$accId")
            )
        }
    }
}

@Composable
private fun StorageCardSkeleton(hasMultipleAccounts: Boolean) {
    val transition = rememberInfiniteTransition(label = "storage_skeleton")
    val alpha by transition.animateFloat(initialValue = 0.35f, targetValue = 0.75f, animationSpec = infiniteRepeatable(animation = tween(durationMillis = 850), repeatMode = RepeatMode.Reverse), label = "skeleton_alpha")
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    Column(modifier = Modifier.testTag("storage_card_skeleton")) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(76.dp).height(20.dp).clip(RoundedCornerShape(6.dp)).background(placeholderColor))
            Box(Modifier.width(136.dp).height(14.dp).clip(RoundedCornerShape(6.dp)).background(placeholderColor))
        }
        if (hasMultipleAccounts) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { Box(Modifier.width(88.dp).height(28.dp).clip(CircleShape).background(placeholderColor)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(placeholderColor))
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(4.dp).height(26.dp).clip(CircleShape).background(placeholderColor))
                            Spacer(Modifier.width(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(Modifier.width(48.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(placeholderColor))
                                Box(Modifier.width(56.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(placeholderColor))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun categoryColor(category: StorageCategoryType): Color = when (category) {
    StorageCategoryType.IMAGES -> FileImage
    StorageCategoryType.DOCUMENTS -> FileDoc
    StorageCategoryType.VIDEOS -> FileVideo
    StorageCategoryType.ARCHIVES -> FileZip
    StorageCategoryType.AUDIO -> FileAudio
    StorageCategoryType.OTHER -> FileUnknown
}

@Composable
fun QuickActionsRow(onUploadClick: () -> Unit, onNewFolderClick: () -> Unit, onSearchClick: () -> Unit, onDrivesClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        QuickActionButton(Icons.Default.Upload, stringResource(R.string.action_upload), onUploadClick)
        QuickActionButton(Icons.Default.CreateNewFolder, stringResource(R.string.action_new_folder), onNewFolderClick)
        QuickActionButton(Icons.Default.Search, stringResource(R.string.action_search), onSearchClick)
        QuickActionButton(Icons.Default.Cloud, stringResource(R.string.action_drives), onDrivesClick)
    }
}

@Composable
fun QuickActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 6.dp, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

@Composable
fun FileListItem(file: CloudFile, navController: NavController, accountId: String, files: List<CloudFile>? = null) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth().clickable { openFileInViewer(file, accountId, navController, files) }
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (file.isFolderItem) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary) }
                }
            } else {
                FileThumb(file = file, accountId = accountId, sizeDp = 48.dp, cornerRadius = 12.dp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.effectiveName, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (file.isFolderItem) stringResource(R.string.files_type_folder) else file.formattedSize, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    if (!file.isFolderItem) Text(" • ${file.effectiveType.uppercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
            if (!file.isFolderItem) {
                IconButton(onClick = { navController.navigate("file_detail/${accountId}/${file.effectiveId}") }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
