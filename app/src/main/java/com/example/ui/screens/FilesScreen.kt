package com.example.ui.screens

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.example.api.CloudyteApi
import com.example.api.FolderRequest
import com.example.api.RenameRequest
import com.example.data.CloudFileRepository
import com.example.data.SortDirection
import com.example.data.SortField
import com.example.data.UserPreferences
import com.example.data.UserPreferencesState
import com.example.data.ViewMode
import com.example.db.AppDatabase
import com.example.db.LocalAccount
import com.example.ui.components.AppLoadingIndicator
import com.example.ui.components.FileThumb
import com.example.ui.components.LocalBottomBarInset
import com.example.ui.navigation.NavigationTransitions
import com.example.ui.openFileInViewer
import com.example.ui.theme.ProviderGoogle
import com.example.ui.theme.ProviderOneDrive
import com.example.ui.theme.ProviderTelegram
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BreadcrumbItem(val id: String?, val name: String)

private val CloudFile.formattedDate: String
    get() {
        val t = time ?: updatedAt
        if (t != null && t > 0L) {
            val millis = if (t < 100000000000L) t * 1000L else t
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
            return sdf.format(java.util.Date(millis))
        }
        if (!createdAt.isNullOrBlank()) {
            return createdAt.take(10)
        }
        return "Recent"
    }

enum class FilesNavDirection { FORWARD, BACKWARD }

enum class FileSortOrder(val displayName: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    SIZE_DESC("Largest First"),
    SIZE_ASC("Smallest First")
}

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModel(
    application: Application,
    private val db: AppDatabase,
    private val api: CloudyteApi,
    private val repo: CloudFileRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        db = AppDatabase.getDatabase(application),
        api = ApiClient.apiService,
        repo = CloudFileRepository(AppDatabase.getDatabase(application), ApiClient.apiService)
    )

    private val prefs = application.getSharedPreferences("cloudyte_drive_state", Context.MODE_PRIVATE)

    val currentAccountId = MutableStateFlow<String?>(prefs.getString("last_active_account_id", null))
    val currentFolderId = MutableStateFlow<String?>(null)

    // Observe files directly from Room — always instant, no waiting for network
    val files: StateFlow<List<CloudFile>> = combine(currentAccountId, currentFolderId) { accId, folderId ->
        accId to folderId
    }.flatMapLatest { (accId, folderId) ->
        repo.observeFolder(accId, folderId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<Account>> = db.accountDao().getAllAccountsFlow()
        .map { list -> list.map { it.toAccount() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _breadcrumbs = MutableStateFlow<List<BreadcrumbItem>>(listOf(BreadcrumbItem(null, "Files")))
    val breadcrumbs: StateFlow<List<BreadcrumbItem>> = _breadcrumbs.asStateFlow()

    // isRefreshing is only true during an explicit user-triggered pull-to-refresh or when the
    // cache is completely empty. Background syncs after folder navigation never set this flag.
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // True only while an upload is in progress
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _uploadProgress = MutableStateFlow(0)
    val uploadProgress: StateFlow<Int> = _uploadProgress.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private val _userEvents = MutableSharedFlow<String>()
    val userEvents: SharedFlow<String> = _userEvents.asSharedFlow()

    var refreshJob: Job? = null
        private set

    init {
        viewModelScope.launch {
            try {
                val cached = db.accountDao().getAllAccounts()
                if (currentAccountId.value == null && cached.isNotEmpty()) {
                    val def = cached.find { it.isDefault == true } ?: cached.first()
                    currentAccountId.value = def.id
                }
                val fetched = api.getAccounts()
                if (fetched.isNotEmpty()) {
                    db.accountDao().insertAccounts(fetched.map { LocalAccount.fromAccount(it) })
                    if (currentAccountId.value == null) {
                        val def = fetched.find { it.isDefault == true } ?: fetched.first()
                        currentAccountId.value = def.effectiveId
                    }
                }
            } catch (_: Exception) {}
            // Only show spinner if cache is empty (first-ever open)
            refresh(showSpinner = files.value.isEmpty())
        }
        viewModelScope.launch {
            accounts.collect { accs ->
                if (currentAccountId.value == null && accs.isNotEmpty()) {
                    val defaultAcc = accs.find { it.isDefault == true } ?: accs.first()
                    currentAccountId.value = defaultAcc.effectiveId
                }
            }
        }
    }

    fun selectAccount(accountId: String) {
        if (currentAccountId.value != accountId) {
            currentAccountId.value = accountId
            currentFolderId.value = null
            _breadcrumbs.value = listOf(BreadcrumbItem(null, "Files"))
            prefs.edit().putString("last_active_account_id", accountId).apply()
            // Cache for the new account may already be present — sync silently
            refresh(showSpinner = false, force = true)
        }
    }

    fun enterFolder(folderId: String, folderName: String) {
        currentFolderId.value = folderId
        _breadcrumbs.value = _breadcrumbs.value + BreadcrumbItem(folderId, folderName)
        // Folder cache may already exist — sync in background without spinner
        refresh(showSpinner = false)
    }

    fun navigateBack(): Boolean {
        val currentCrumbs = _breadcrumbs.value
        if (currentCrumbs.size > 1) {
            val updated = currentCrumbs.dropLast(1)
            _breadcrumbs.value = updated
            currentFolderId.value = updated.last().id
            // Cache for the parent folder is very likely populated — silent sync
            refresh(showSpinner = false)
            return true
        }
        return false
    }

    fun navigateToBreadcrumb(index: Int) {
        val currentCrumbs = _breadcrumbs.value
        if (index in currentCrumbs.indices) {
            val updated = currentCrumbs.take(index + 1)
            _breadcrumbs.value = updated
            currentFolderId.value = updated.last().id
            refresh(showSpinner = false)
        }
    }

    /** Pull-to-refresh triggered by the user — always shows the spinner. */
    fun forceRefresh() = refresh(showSpinner = true, force = true)

    /**
     * Internal sync. [showSpinner] drives the visible loading indicator:
     * - true  → full-screen spinner (first load / empty cache / explicit pull)
     * - false → silent background sync; UI keeps showing cached data
     */
    private fun refresh(showSpinner: Boolean, force: Boolean = false) {
        val accId = currentAccountId.value ?: accounts.value.firstOrNull()?.effectiveId
        val folderId = currentFolderId.value
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (showSpinner) _isRefreshing.value = true
            try {
                repo.refresh(accId, folderId)
                _isOffline.value = false
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w("FilesViewModel", "Sync failed: ${e.message}")
                    _isOffline.value = true
                }
            } finally {
                if (showSpinner) _isRefreshing.value = false
            }
        }
    }

    fun deleteFile(fileId: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val accId = currentAccountId.value
            try {
                // Repo does optimistic delete then reverts on failure
                repo.delete(fileId, accId)
            } catch (e: Exception) {
                Log.e("FilesViewModel", "Delete failed", e)
            } finally {
                onComplete()
            }
        }
    }

    fun renameFile(fileId: String, newName: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val accId = currentAccountId.value
            try {
                // Repo does optimistic rename then reverts on failure
                repo.rename(fileId, accId, newName)
            } catch (e: Exception) {
                Log.e("FilesViewModel", "Rename failed", e)
            } finally {
                onComplete()
            }
        }
    }

    fun createFolder(name: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            val accId = currentAccountId.value
            try {
                // Repo does optimistic insert then reverts on failure
                repo.createFolder(name, accId, currentFolderId.value)
            } catch (e: Exception) {
                Log.e("FilesViewModel", "Create folder failed", e)
            } finally {
                onComplete()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilesScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: FilesViewModel = viewModel()
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val files by viewModel.files.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val currentAccountId by viewModel.currentAccountId.collectAsState()
    val currentFolderId by viewModel.currentFolderId.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    // isRefreshing is now only true for pull-to-refresh / empty-cache first load
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    val isOffline by viewModel.isOffline.collectAsState()

    val prefsState by UserPreferences.observe(context).collectAsState(initial = UserPreferencesState())
    val isGridView = prefsState.viewMode == ViewMode.GRID

    val gridCells = remember(prefsState.gridColumns) {
        if (prefsState.gridColumns > 0) GridCells.Fixed(prefsState.gridColumns)
        else GridCells.Adaptive(minSize = 108.dp)
    }

    var selectedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectionMode = selectedFileIds.isNotEmpty()

    fun toggleSelection(fileId: String) {
        selectedFileIds = if (selectedFileIds.contains(fileId)) selectedFileIds - fileId else selectedFileIds + fileId
    }

    BackHandler(enabled = isSelectionMode || breadcrumbs.size > 1) {
        if (isSelectionMode) selectedFileIds = emptySet()
        else if (breadcrumbs.size > 1) viewModel.navigateBack()
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showSortDropdown by remember { mutableStateOf(false) }

    val sortOrder = remember(prefsState.sortField, prefsState.sortDirection) {
        when {
            prefsState.sortField == SortField.NAME && prefsState.sortDirection == SortDirection.ASC -> FileSortOrder.NAME_ASC
            prefsState.sortField == SortField.NAME && prefsState.sortDirection == SortDirection.DESC -> FileSortOrder.NAME_DESC
            prefsState.sortField == SortField.SIZE && prefsState.sortDirection == SortDirection.ASC -> FileSortOrder.SIZE_ASC
            prefsState.sortField == SortField.SIZE && prefsState.sortDirection == SortDirection.DESC -> FileSortOrder.SIZE_DESC
            prefsState.sortField == SortField.DATE && prefsState.sortDirection == SortDirection.ASC -> FileSortOrder.DATE_ASC
            else -> FileSortOrder.DATE_DESC
        }
    }

    val filteredAndSortedFiles = remember(files, searchQuery, sortOrder) {
        val filtered = files.filter { searchQuery.isBlank() || it.effectiveName.contains(searchQuery, ignoreCase = true) }
        when (sortOrder) {
            FileSortOrder.DATE_DESC -> filtered.sortedWith(compareByDescending<CloudFile> { it.isFolderItem }.thenByDescending { it.time ?: 0L })
            FileSortOrder.DATE_ASC  -> filtered.sortedWith(compareByDescending<CloudFile> { it.isFolderItem }.thenBy { it.time ?: 0L })
            FileSortOrder.NAME_ASC  -> filtered.sortedWith(compareByDescending<CloudFile> { it.isFolderItem }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.effectiveName })
            FileSortOrder.NAME_DESC -> filtered.sortedWith(compareByDescending<CloudFile> { it.isFolderItem }.thenByDescending(String.CASE_INSENSITIVE_ORDER) { it.effectiveName })
            FileSortOrder.SIZE_DESC -> filtered.sortedWith(compareByDescending<CloudFile> { it.isFolderItem }.thenByDescending { it.sizeBytes })
            FileSortOrder.SIZE_ASC  -> filtered.sortedWith(compareByDescending<CloudFile> { it.isFolderItem }.thenBy { it.sizeBytes })
        }
    }

    val folders = remember(filteredAndSortedFiles) {
        filteredAndSortedFiles.filter { it.isFolderItem }.distinctBy { it.effectiveId.ifEmpty { it.effectiveName } }
    }
    val regularFiles = remember(filteredAndSortedFiles) {
        filteredAndSortedFiles.filter { !it.isFolderItem }.distinctBy { it.effectiveId.ifEmpty { it.effectiveName } }
    }

    val effectiveAccountId = currentAccountId ?: accounts.firstOrNull()?.effectiveId ?: "root"

    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            snackbarMessage = null
        }
    }

    var fileForRename by remember { mutableStateOf<CloudFile?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var fileForDelete by remember { mutableStateOf<CloudFile?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showAddAccountDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { snackbarMessage = "Uploading file..." }
    }
    val mediaPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { snackbarMessage = "Uploading media..." }
    }

    // Dialogs
    if (fileForRename != null) {
        AlertDialog(
            onDismissRequest = { fileForRename = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("Rename item") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("New name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = fileForRename ?: return@Button
                    if (renameInput.isNotBlank()) {
                        val newName = renameInput.trim()
                        fileForRename = null
                        viewModel.renameFile(target.effectiveId, newName) { snackbarMessage = "Renamed" }
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { fileForRename = null }) { Text("Cancel") }
            }
        )
    }

    if (fileForDelete != null) {
        AlertDialog(
            onDismissRequest = { fileForDelete = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("Delete item?") },
            text = { Text("Are you sure you want to delete '${fileForDelete?.effectiveName}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = fileForDelete ?: return@Button
                        fileForDelete = null
                        viewModel.deleteFile(target.effectiveId) { snackbarMessage = "Deleted" }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileForDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newFolderName.isNotBlank()) {
                        val name = newFolderName.trim()
                        showCreateFolderDialog = false
                        newFolderName = ""
                        viewModel.createFolder(name) { snackbarMessage = "Folder created" }
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddAccountDialog) {
        AddAccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onSuccess = {
                showAddAccountDialog = false
                viewModel.forceRefresh()
                snackbarMessage = "Drive connected"
            }
        )
    }

    var navDirection by remember { mutableStateOf(FilesNavDirection.FORWARD) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val canScrollBackward by remember {
        derivedStateOf { if (isGridView) gridState.canScrollBackward else listState.canScrollBackward }
    }
    val topBarContainerColor by animateColorAsState(
        targetValue = if (canScrollBackward) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        label = "TopBarContainerColor"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = {
                        Text("${selectedFileIds.size} selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedFileIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            val allIds = filteredAndSortedFiles.map { it.effectiveId }.toSet()
                            selectedFileIds = if (selectedFileIds.size == allIds.size) emptySet() else allIds
                        }) { Icon(Icons.Default.SelectAll, contentDescription = "Select all") }
                        IconButton(onClick = {
                            val toDelete = files.filter { selectedFileIds.contains(it.effectiveId) }
                            selectedFileIds = emptySet()
                            coroutineScope.launch {
                                toDelete.forEach { f -> viewModel.deleteFile(f.effectiveId) {} }
                                snackbarMessage = "${toDelete.size} items deleted"
                            }
                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarContainerColor)
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = if (breadcrumbs.size > 1) breadcrumbs.last().name else "Files",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        if (breadcrumbs.size > 1) {
                            IconButton(onClick = {
                                navDirection = FilesNavDirection.BACKWARD
                                viewModel.navigateBack()
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = !isSearchActive }) {
                            Icon(
                                if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (isSearchActive) "Close search" else "Search"
                            )
                        }
                        // Refresh icon shown only when no cache data exists at all
                        if (files.isEmpty()) {
                            IconButton(onClick = { viewModel.forceRefresh() }) {
                                if (isRefreshing) AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.primary)
                                else Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = topBarContainerColor)
                )
            }
        },
        floatingActionButton = {
            if (isUploading) {
                FloatingActionButton(onClick = {}, containerColor = MaterialTheme.colorScheme.primary) {
                    AppLoadingIndicator(color = MaterialTheme.colorScheme.onPrimary, size = 24.dp)
                }
            } else {
                FloatingActionButtonMenu(
                    expanded = fabMenuExpanded,
                    button = {
                        ToggleFloatingActionButton(checked = fabMenuExpanded, onCheckedChange = { fabMenuExpanded = it }) {
                            val rotation by animateFloatAsState(targetValue = if (fabMenuExpanded) 45f else 0f, label = "FabRotation")
                            Icon(Icons.Default.Add, contentDescription = if (fabMenuExpanded) "Close" else "Add", modifier = Modifier.rotate(rotation))
                        }
                    }
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = { fabMenuExpanded = false; filePickerLauncher.launch("*/*") },
                        text = { Text("Upload file") },
                        icon = { Icon(Icons.Default.InsertDriveFile, null) }
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            mediaPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                        text = { Text("Upload photo/video") },
                        icon = { Icon(Icons.Default.AddPhotoAlternate, null) }
                    )
                    FloatingActionButtonMenuItem(
                        onClick = { fabMenuExpanded = false; showCreateFolderDialog = true },
                        text = { Text("New folder") },
                        icon = { Icon(Icons.Default.CreateNewFolder, null) }
                    )
                }
            }
        }
    ) { innerPadding ->
        // Pull-to-refresh only shows the indicator when user explicitly pulls down
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.forceRefresh() },
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentFolderId,
                transitionSpec = {
                    NavigationTransitions.sharedAxisEnter(forward = navDirection == FilesNavDirection.FORWARD) togetherWith
                        NavigationTransitions.sharedAxisExit(forward = navDirection == FilesNavDirection.FORWARD)
                },
                label = "FolderNavigationTransition",
                modifier = Modifier.fillMaxSize()
            ) { _ ->
                val sharedHeaders: @Composable (isGrid: Boolean) -> Unit = { _ ->
                    // Intentionally unused – headers are inlined into each list below
                }
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = gridCells,
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = LocalBottomBarInset.current + 88.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isSearchActive) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                                SearchBar(query = searchQuery, onQueryChange = { searchQuery = it }, modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "account_chips") {
                            AccountSwitcherChipRow(accounts = accounts, currentAccountId = currentAccountId, onSelectAccount = { viewModel.selectAccount(it) }, onAddDrive = { showAddAccountDialog = true })
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "controls") {
                            ControlsRow(sortOrder = sortOrder, showSortDropdown = showSortDropdown, onToggleSortDropdown = { showSortDropdown = it }, onSelectSortOrder = { newOrder ->
                                coroutineScope.launch {
                                    val (f, d) = sortOrderToPrefs(newOrder)
                                    UserPreferences.setSortField(context, f); UserPreferences.setSortDirection(context, d)
                                }
                            }, isGridView = true, onToggleViewMode = { coroutineScope.launch { UserPreferences.setViewMode(context, if (it) ViewMode.GRID else ViewMode.LIST) } })
                        }
                        if (breadcrumbs.size > 1) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "breadcrumbs") {
                                BreadcrumbsRow(breadcrumbs = breadcrumbs, onBreadcrumbClick = { idx -> navDirection = FilesNavDirection.BACKWARD; viewModel.navigateToBreadcrumb(idx) })
                            }
                        }
                        if (isOffline && files.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "offline") { OfflineBanner() }
                        }
                        if (folders.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "folders_header") {
                                SectionHeader("Folders", Modifier.padding(top = 8.dp, bottom = 2.dp))
                            }
                            items(folders, key = { it.effectiveId.ifEmpty { "folder_${it.effectiveName}" } }) { folder ->
                                FolderGridItem(
                                    folder = folder, navController = rootNavController, accountId = effectiveAccountId,
                                    isSelected = selectedFileIds.contains(folder.effectiveId), isSelectionMode = isSelectionMode,
                                    onToggleSelect = { toggleSelection(folder.effectiveId) },
                                    onClick = { navDirection = FilesNavDirection.FORWARD; viewModel.enterFolder(folder.effectiveId, folder.effectiveName) },
                                    onRenameClick = { fileForRename = folder; renameInput = folder.effectiveName },
                                    onDeleteClick = { fileForDelete = folder }, modifier = Modifier.animateItem()
                                )
                            }
                        }
                        if (regularFiles.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "files_header") {
                                SectionHeader("Files", Modifier.padding(top = if (folders.isEmpty()) 8.dp else 12.dp, bottom = 2.dp))
                            }
                            items(regularFiles, key = { it.effectiveId.ifEmpty { "file_${it.effectiveName}" } }) { file ->
                                FileGridItem(
                                    file = file, navController = rootNavController, accountId = effectiveAccountId,
                                    isSelected = selectedFileIds.contains(file.effectiveId), isSelectionMode = isSelectionMode,
                                    onToggleSelect = { toggleSelection(file.effectiveId) },
                                    onClick = { openFileInViewer(file, effectiveAccountId, rootNavController, regularFiles) },
                                    onRenameClick = { fileForRename = file; renameInput = file.effectiveName },
                                    onDeleteClick = { fileForDelete = file }, modifier = Modifier.animateItem()
                                )
                            }
                        }
                        if (folders.isEmpty() && regularFiles.isEmpty() && !isRefreshing) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                                FilesEmptyState(isSearch = searchQuery.isNotBlank(), onCreateFolder = { showCreateFolderDialog = true }, onUploadFile = { filePickerLauncher.launch("*/*") })
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = LocalBottomBarInset.current + 88.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isSearchActive) {
                            item(key = "search") {
                                SearchBar(query = searchQuery, onQueryChange = { searchQuery = it }, modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                        item(key = "account_chips") {
                            AccountSwitcherChipRow(accounts = accounts, currentAccountId = currentAccountId, onSelectAccount = { viewModel.selectAccount(it) }, onAddDrive = { showAddAccountDialog = true })
                        }
                        item(key = "controls") {
                            ControlsRow(sortOrder = sortOrder, showSortDropdown = showSortDropdown, onToggleSortDropdown = { showSortDropdown = it }, onSelectSortOrder = { newOrder ->
                                coroutineScope.launch {
                                    val (f, d) = sortOrderToPrefs(newOrder)
                                    UserPreferences.setSortField(context, f); UserPreferences.setSortDirection(context, d)
                                }
                            }, isGridView = false, onToggleViewMode = { coroutineScope.launch { UserPreferences.setViewMode(context, if (it) ViewMode.GRID else ViewMode.LIST) } })
                        }
                        if (breadcrumbs.size > 1) {
                            item(key = "breadcrumbs") {
                                BreadcrumbsRow(breadcrumbs = breadcrumbs, onBreadcrumbClick = { idx -> navDirection = FilesNavDirection.BACKWARD; viewModel.navigateToBreadcrumb(idx) })
                            }
                        }
                        if (isOffline && files.isNotEmpty()) {
                            item(key = "offline") { OfflineBanner() }
                        }
                        if (folders.isNotEmpty()) {
                            item(key = "folders_header") {
                                SectionHeader("Folders", Modifier.padding(top = 8.dp, bottom = 2.dp))
                            }
                            items(folders, key = { it.effectiveId.ifEmpty { "folder_${it.effectiveName}" } }) { folder ->
                                FileRow(
                                    file = folder, navController = rootNavController, accountId = effectiveAccountId,
                                    isSelected = selectedFileIds.contains(folder.effectiveId), isSelectionMode = isSelectionMode,
                                    onToggleSelect = { toggleSelection(folder.effectiveId) },
                                    onClick = { navDirection = FilesNavDirection.FORWARD; viewModel.enterFolder(folder.effectiveId, folder.effectiveName) },
                                    onRenameClick = { fileForRename = folder; renameInput = folder.effectiveName },
                                    onDeleteClick = { fileForDelete = folder }, modifier = Modifier.animateItem()
                                )
                            }
                        }
                        if (regularFiles.isNotEmpty()) {
                            item(key = "files_header") {
                                SectionHeader("Files", Modifier.padding(top = if (folders.isEmpty()) 8.dp else 12.dp, bottom = 2.dp))
                            }
                            items(regularFiles, key = { it.effectiveId.ifEmpty { "file_${it.effectiveName}" } }) { file ->
                                FileRow(
                                    file = file, navController = rootNavController, accountId = effectiveAccountId,
                                    isSelected = selectedFileIds.contains(file.effectiveId), isSelectionMode = isSelectionMode,
                                    onToggleSelect = { toggleSelection(file.effectiveId) },
                                    onClick = { openFileInViewer(file, effectiveAccountId, rootNavController, regularFiles) },
                                    onRenameClick = { fileForRename = file; renameInput = file.effectiveName },
                                    onDeleteClick = { fileForDelete = file }, modifier = Modifier.animateItem()
                                )
                            }
                        }
                        if (folders.isEmpty() && regularFiles.isEmpty() && !isRefreshing) {
                            item(key = "empty") {
                                FilesEmptyState(isSearch = searchQuery.isNotBlank(), onCreateFolder = { showCreateFolderDialog = true }, onUploadFile = { filePickerLauncher.launch("*/*") })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sortOrderToPrefs(order: FileSortOrder): Pair<SortField, SortDirection> = when (order) {
    FileSortOrder.DATE_DESC -> SortField.DATE to SortDirection.DESC
    FileSortOrder.DATE_ASC  -> SortField.DATE to SortDirection.ASC
    FileSortOrder.NAME_ASC  -> SortField.NAME to SortDirection.ASC
    FileSortOrder.NAME_DESC -> SortField.NAME to SortDirection.DESC
    FileSortOrder.SIZE_DESC -> SortField.SIZE to SortDirection.DESC
    FileSortOrder.SIZE_ASC  -> SortField.SIZE to SortDirection.ASC
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search files…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountSwitcherChipRow(
    accounts: List<Account>,
    currentAccountId: String?,
    onSelectAccount: (String) -> Unit,
    onAddDrive: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(accounts, key = { it.effectiveId }) { acc ->
            val isSelected = acc.effectiveId == currentAccountId
            val providerColor = when {
                acc.effectiveType.contains("google", true) -> ProviderGoogle
                acc.effectiveType.contains("one", true)    -> ProviderOneDrive
                else                                       -> ProviderTelegram
            }
            val initial = (acc.effectiveName.firstOrNull() ?: 'D').uppercaseChar().toString()

            // Use M3 FilterChip for proper token-driven selection state
            FilterChip(
                selected = isSelected,
                onClick = { onSelectAccount(acc.effectiveId) },
                label = {
                    Text(
                        text = acc.effectiveName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                },
                leadingIcon = {
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape).background(providerColor.copy(alpha = if (isSelected) 0.3f else 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = initial, color = providerColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onAddDrive,
                label = { Text("Add drive", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ControlsRow(
    sortOrder: FileSortOrder,
    showSortDropdown: Boolean,
    onToggleSortDropdown: (Boolean) -> Unit,
    onSelectSortOrder: (FileSortOrder) -> Unit,
    isGridView: Boolean,
    onToggleViewMode: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box {
            // Sort chip — use same shape token as FilterChip for consistency
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.clickable { onToggleSortDropdown(true) }
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = when (sortOrder) {
                        FileSortOrder.NAME_ASC, FileSortOrder.NAME_DESC -> "Name"
                        FileSortOrder.DATE_DESC, FileSortOrder.DATE_ASC -> "Date"
                        FileSortOrder.SIZE_DESC, FileSortOrder.SIZE_ASC -> "Size"
                    }
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowDropDown, "Sort", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            DropdownMenu(expanded = showSortDropdown, onDismissRequest = { onToggleSortDropdown(false) }) {
                Text("Sort by", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                HorizontalDivider()
                FileSortOrder.entries.forEach { order ->
                    val isSel = sortOrder == order
                    DropdownMenuItem(
                        text = { Text(order.displayName, color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) },
                        trailingIcon = { if (isSel) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) },
                        onClick = { onToggleSortDropdown(false); onSelectSortOrder(order) }
                    )
                }
            }
        }

        ButtonGroup {
            ToggleButton(checked = !isGridView, onCheckedChange = { if (isGridView) onToggleViewMode(false) }) {
                Icon(Icons.AutoMirrored.Filled.ViewList, "List view", modifier = Modifier.size(18.dp))
            }
            ToggleButton(checked = isGridView, onCheckedChange = { if (!isGridView) onToggleViewMode(true) }) {
                Icon(Icons.Default.GridView, "Grid view", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun BreadcrumbsRow(breadcrumbs: List<BreadcrumbItem>, onBreadcrumbClick: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(breadcrumbs) { index, item ->
            val isLast = index == breadcrumbs.lastIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(!isLast) { onBreadcrumbClick(index) }.padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Icon(if (index == 0) Icons.Default.Cloud else Icons.Default.Folder, null, tint = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(item.name, color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal, fontSize = 13.sp, maxLines = 1)
            }
            if (!isLast) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
fun OfflineBanner() {
    Row(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text("Offline — showing cached files", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

// ─── Grid Items ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderGridItem(
    folder: CloudFile, navController: NavController, accountId: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false, isSelectionMode: Boolean = false,
    onToggleSelect: (() -> Unit)? = null, onClick: (() -> Unit)? = null,
    onRenameClick: (() -> Unit)? = null, onDeleteClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "FolderGridBg"
    )

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        // Use 0.dp elevation — tonal surface colour provides the visual lift; avoids shadow fighting
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
            // Unified aspect ratio: same as FileGridItem so the grid looks homogeneous
            .aspectRatio(1f)
            .combinedClickable(
                onClick = { if (isSelectionMode && onToggleSelect != null) onToggleSelect() else onClick?.invoke() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleSelect?.invoke() }
            )
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Icon badge top-left
            Box(
                modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer).align(Alignment.TopStart),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            }

            // Top-right: checkmark or overflow
            if (isSelected) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).align(Alignment.TopEnd),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            } else {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; onRenameClick?.invoke() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDeleteClick?.invoke() })
                    }
                }
            }

            // Bottom: name + subtitle
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(folder.effectiveName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(
    file: CloudFile, navController: NavController, accountId: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false, isSelectionMode: Boolean = false,
    onToggleSelect: (() -> Unit)? = null, onClick: (() -> Unit)? = null,
    onRenameClick: (() -> Unit)? = null, onDeleteClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    val cardBg by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "FileGridBg"
    )

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
            // Unified aspect ratio: matches FolderGridItem now
            .aspectRatio(1f)
            .combinedClickable(
                onClick = { if (isSelectionMode && onToggleSelect != null) onToggleSelect() else onClick?.invoke() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleSelect?.invoke() }
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top ~65%: thumbnail
            Box(modifier = Modifier.fillMaxWidth().weight(0.65f)) {
                FileThumb(file = file, accountId = accountId, sizeDp = 120.dp, cornerRadius = 20.dp, modifier = Modifier.fillMaxSize())
                if (isSelected) {
                    Box(
                        modifier = Modifier.padding(6.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).align(Alignment.TopEnd),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
                } else {
                    Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f), modifier = Modifier.size(26.dp)) {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.fillMaxSize()) {
                                Icon(Icons.Default.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(15.dp))
                            }
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { showMenu = false; navController.navigate("file_detail/$accountId/${file.effectiveId}") })
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; onRenameClick?.invoke() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDeleteClick?.invoke() })
                        }
                    }
                }
            }
            // Bottom ~35%: text
            Column(
                modifier = Modifier.fillMaxWidth().weight(0.35f).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(file.effectiveName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(file.formattedSize, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

// ─── List Row ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: CloudFile, navController: NavController, accountId: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false, isSelectionMode: Boolean = false,
    onToggleSelect: (() -> Unit)? = null, onClick: (() -> Unit)? = null,
    onRenameClick: (() -> Unit)? = null, onDeleteClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    val rowBg by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "RowBg"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = rowBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
            // No hardcoded height — let content dictate height so text never clips
            .wrapContentHeight()
            .combinedClickable(
                onClick = { if (isSelectionMode && onToggleSelect != null) onToggleSelect() else onClick?.invoke() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggleSelect?.invoke() }
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier.padding(end = 10.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            }
            FileThumb(file = file, accountId = accountId, sizeDp = 46.dp, cornerRadius = 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.effectiveName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (file.isFolderItem) "${file.formattedDate} • Folder" else "${file.formattedDate} • ${file.formattedSize}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Details") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { showMenu = false; navController.navigate("file_detail/$accountId/${file.effectiveId}") })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { showMenu = false; onRenameClick?.invoke() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDeleteClick?.invoke() })
                }
            }
        }
    }
}

// ─── Empty State ─────────────────────────────────────────────────────────────

@Composable
fun FilesEmptyState(isSearch: Boolean, onCreateFolder: () -> Unit, onUploadFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (isSearch) Icons.Default.Search else Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(if (isSearch) "No results" else "Nothing here yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            if (isSearch) "Try a different search" else "Upload files or create a folder to get started",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!isSearch) {
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onUploadFile) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Upload")
                }
                Button(
                    onClick = onCreateFolder,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                ) {
                    Icon(Icons.Default.CreateNewFolder, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New folder")
                }
            }
        }
    }
}
