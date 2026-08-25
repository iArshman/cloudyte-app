package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.example.api.Account
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.FileRepository
import com.example.api.FolderRequest
import com.example.ui.openFileInViewer
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BreadcrumbItem(val id: String?, val name: String)

class FilesViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    private val _files = MutableStateFlow<List<CloudFile>>(emptyList())
    val files: StateFlow<List<CloudFile>> = _files.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _currentAccountId = MutableStateFlow<String?>(null)
    val currentAccountId: StateFlow<String?> = _currentAccountId.asStateFlow()

    private val _breadcrumbs = MutableStateFlow<List<BreadcrumbItem>>(listOf(BreadcrumbItem(null, "My Drive")))
    val breadcrumbs: StateFlow<List<BreadcrumbItem>> = _breadcrumbs.asStateFlow()

    val currentFolderId: String? get() = _breadcrumbs.value.lastOrNull()?.id

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadFiles()
    }

    fun selectAccount(accountId: String?) {
        _currentAccountId.value = accountId
        val accName = _accounts.value.find { it.effectiveId == accountId }?.effectiveName?.ifBlank { "My Drive" } ?: "My Drive"
        _breadcrumbs.value = listOf(BreadcrumbItem(null, accName))
        fetchFilesForCurrentAccount()
    }

    fun loadFiles() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val accList = try {
                    ApiClient.apiService.getAccounts()
                } catch (e: Exception) {
                    Log.w("FilesViewModel", "Failed to fetch accounts: ${e.message}")
                    emptyList()
                }
                _accounts.value = accList

                if (_currentAccountId.value == null && accList.isNotEmpty()) {
                    val defaultAcc = accList.firstOrNull { it.isDefault == true } ?: accList.firstOrNull()
                    _currentAccountId.value = defaultAcc?.effectiveId
                    val initialName = defaultAcc?.effectiveName?.ifBlank { "My Drive" } ?: "My Drive"
                    if (_breadcrumbs.value.size <= 1) {
                        _breadcrumbs.value = listOf(BreadcrumbItem(null, initialName))
                    }
                }

                fetchFilesForCurrentAccount()
            } catch (e: Exception) {
                Log.e("FilesViewModel", "Error in loadFiles", e)
                _errorMessage.value = e.localizedMessage ?: "Failed to load files"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun fetchFilesForCurrentAccount() {
        viewModelScope.launch {
            val accId = _currentAccountId.value
            val db = com.example.db.AppDatabase.getDatabase(getApplication())
            if (currentFolderId == null) {
                val cached = db.cloudFileDao().getFilesForAccount(accId).map { it.toCloudFile() }
                if (cached.isNotEmpty()) {
                    _files.value = cached
                }
            }
            
            _isLoading.value = true
            try {
                val fetched = ApiClient.apiService.getFiles(accId, currentFolderId, null)
                if (currentFolderId == null) {
                    db.cloudFileDao().deleteFilesForAccount(accId)
                    db.cloudFileDao().insertFiles(fetched.map { com.example.db.LocalCloudFile.fromCloudFile(it, accId) })
                }
                FileRepository.cacheFiles(fetched)
                _files.value = fetched
            } catch (e: Exception) {
                Log.e("FilesViewModel", "Failed to fetch files for account ${_currentAccountId.value}", e)
                if (_files.value.isEmpty()) {
                    _errorMessage.value = e.localizedMessage
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun createFolder(name: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val accId = _currentAccountId.value
                val response = ApiClient.apiService.createFolder(accId, FolderRequest(name, currentFolderId))
                if (response.isSuccessful) {
                    fetchFilesForCurrentAccount()
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("FilesViewModel", "Create folder error", e)
            }
        }
    }

    fun enterFolder(folderId: String, folderName: String) {
        _breadcrumbs.value = _breadcrumbs.value + BreadcrumbItem(folderId, folderName)
        fetchFilesForCurrentAccount()
    }

    fun navigateToBreadcrumb(index: Int) {
        if (index in _breadcrumbs.value.indices) {
            _breadcrumbs.value = _breadcrumbs.value.take(index + 1)
            fetchFilesForCurrentAccount()
        }
    }

    fun goBack(): Boolean {
        val crumbs = _breadcrumbs.value
        if (crumbs.size > 1) {
            _breadcrumbs.value = crumbs.dropLast(1)
            fetchFilesForCurrentAccount()
            return true
        }
        return false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: FilesViewModel = viewModel()
) {
    val files by viewModel.files.collectAsState()
    val accounts by viewModel.accounts.collectAsState()
    val currentAccountId by viewModel.currentAccountId.collectAsState()
    val breadcrumbs by viewModel.breadcrumbs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var showFilterDropdown by remember { mutableStateOf(false) }
    var showAccountDropdown by remember { mutableStateOf(false) }
    var fileForMoveCopy by remember { mutableStateOf<Pair<CloudFile, Boolean>?>(null) }
    var showAddAccountDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }

    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("cloudyte_ui_prefs", Context.MODE_PRIVATE) }
    var isGridView by remember { mutableStateOf(sharedPrefs.getBoolean("is_grid_view", false)) }

    val coroutineScope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val accId = currentAccountId ?: accounts.firstOrNull()?.effectiveId
            if (!accId.isNullOrEmpty()) {
                isUploading = true
                coroutineScope.launch {
                    val success = UploadHelper.uploadFile(context, accId, viewModel.currentFolderId, it)
                    isUploading = false
                    if (success) {
                        viewModel.loadFiles()
                    }
                }
            }
        }
    }

    BackHandler(enabled = viewModel.currentFolderId != null) {
        viewModel.goBack()
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = SurfaceDark,
            title = { Text("New Folder", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PurpleGradientStart,
                        unfocusedBorderColor = TextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.createFolder(newFolderName.trim()) {
                                showCreateFolderDialog = false
                                newFolderName = ""
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart)
                ) {
                    Text("Create", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    if (showAddAccountDialog) {
        AddAccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onSuccess = {
                showAddAccountDialog = false
                viewModel.loadFiles()
                snackbarMessage = "Drive connected successfully"
            }
        )
    }

    val fileMoveCopyState = fileForMoveCopy
    if (fileMoveCopyState != null) {
        val (targetFile, isMove) = fileMoveCopyState
        MoveCopyDialog(
            file = targetFile,
            sourceAccountId = currentAccountId ?: accounts.firstOrNull()?.effectiveId ?: "root",
            isMove = isMove,
            onDismiss = { fileForMoveCopy = null },
            onSuccess = { msg ->
                fileForMoveCopy = null
                snackbarMessage = msg
                viewModel.loadFiles()
            }
        )
    }

    val filteredFiles = remember(files, searchQuery, selectedFilter) {
        files.filter { file ->
            val matchesSearch = searchQuery.isBlank() || file.effectiveName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "Folders" -> file.isFolderItem
                "Videos" -> file.isVideo
                "Images" -> file.isImage
                "Audio" -> file.isAudio
                "Docs" -> file.isPdf || file.isTextOrCode
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    val folders = filteredFiles.filter { it.isFolderItem }
    val regularFiles = filteredFiles.filter { !it.isFolderItem }
    val effectiveAccountId = currentAccountId ?: accounts.firstOrNull()?.effectiveId ?: "root"
    val selectedAccount = accounts.find { it.effectiveId == currentAccountId } ?: accounts.firstOrNull()

    Scaffold(
        containerColor = BackgroundDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = { showCreateFolderDialog = true },
                    containerColor = SurfaceDark,
                    contentColor = PurpleGradientStart,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (isUploading) {
                    FloatingActionButton(onClick = { }, containerColor = PurpleGradientStart) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    }
                } else {
                    FloatingActionButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        containerColor = PurpleGradientStart
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Upload File", tint = Color.White)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(top = 16.dp)
        ) {
            // Single-Row Top Bar: Search Bar + Grid/List Switcher + Filter + Drive/Account Avatar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = if (selectedAccount != null) "Search in ${selectedAccount.effectiveName}..." else "Search files...",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, tint = TextSecondary, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            IconButton(onClick = { viewModel.loadFiles() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PurpleGradientStart,
                        unfocusedBorderColor = SurfaceDark,
                        focusedContainerColor = SurfaceDark,
                        unfocusedContainerColor = SurfaceDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )

                // Layout Style Switcher (List vs Grid)
                IconButton(
                    onClick = {
                        isGridView = !isGridView
                        sharedPrefs.edit().putBoolean("is_grid_view", isGridView).apply()
                    },
                    modifier = Modifier
                        .size(42.dp)
                        .background(SurfaceDark, CircleShape)
                ) {
                    Icon(
                        imageVector = if (isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                        contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View",
                        tint = PurpleGradientStart,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Filter Dropdown Icon Button
                Box {
                    IconButton(
                        onClick = { showFilterDropdown = true },
                        modifier = Modifier
                            .size(42.dp)
                            .background(
                                if (selectedFilter != "All") PurpleGradientStart.copy(alpha = 0.18f) else SurfaceDark,
                                CircleShape
                            )
                    ) {
                        BadgedBox(
                            badge = {
                                if (selectedFilter != "All") {
                                    Badge(
                                        containerColor = PurpleGradientStart,
                                        modifier = Modifier.size(8.dp)
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filter files",
                                tint = if (selectedFilter != "All") PurpleGradientStart else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showFilterDropdown,
                        onDismissRequest = { showFilterDropdown = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        Text(
                            text = "Filter Files By",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(color = Color(0xFF2C2C3E))

                        val filterOptions = listOf(
                            Triple("All", "All Files", Icons.Default.FolderOpen),
                            Triple("Folders", "Folders Only", Icons.Default.Folder),
                            Triple("Images", "Photos & Images", Icons.Default.Image),
                            Triple("Videos", "Videos & Movies", Icons.Default.Movie),
                            Triple("Audio", "Audio & Music", Icons.Default.MusicNote),
                            Triple("Docs", "Documents & Code", Icons.Default.Description)
                        )

                        filterOptions.forEach { (filterKey, filterLabel, filterIcon) ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = filterIcon,
                                            contentDescription = null,
                                            tint = if (selectedFilter == filterKey) PurpleGradientStart else TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = filterLabel,
                                            color = if (selectedFilter == filterKey) PurpleGradientStart else TextPrimary,
                                            fontWeight = if (selectedFilter == filterKey) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 14.sp
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (selectedFilter == filterKey) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = PurpleGradientStart,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    selectedFilter = filterKey
                                    showFilterDropdown = false
                                }
                            )
                        }
                    }
                }

                // Account / Drive Avatar Style Button
                Box {
                    val providerColor = when {
                        selectedAccount?.effectiveType?.contains("google", ignoreCase = true) == true -> Color(0xFF4285F4)
                        selectedAccount?.effectiveType?.contains("one", ignoreCase = true) == true -> Color(0xFF0078D4)
                        selectedAccount?.effectiveType?.contains("tele", ignoreCase = true) == true -> Color(0xFF24A1DE)
                        else -> PurpleGradientStart
                    }
                    val initialLetter = (selectedAccount?.effectiveName?.firstOrNull() ?: 'C').uppercaseChar().toString()

                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(providerColor.copy(alpha = 0.2f))
                            .border(1.5.dp, providerColor, CircleShape)
                            .clickable { showAccountDropdown = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initialLetter,
                            color = providerColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    DropdownMenu(
                        expanded = showAccountDropdown,
                        onDismissRequest = { showAccountDropdown = false },
                        modifier = Modifier
                            .background(SurfaceDark)
                            .widthIn(min = 260.dp)
                    ) {
                        Text(
                            text = "Connected Cloud Drives",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(color = Color(0xFF2C2C3E))
                        accounts.forEach { acc ->
                            val isSelected = acc.effectiveId == currentAccountId
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CloudQueue,
                                            contentDescription = null,
                                            tint = if (isSelected) PurpleGradientStart else TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = acc.effectiveName,
                                                color = if (isSelected) PurpleGradientStart else TextPrimary,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = acc.effectiveType,
                                                color = TextSecondary.copy(alpha = 0.7f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Active",
                                            tint = PurpleGradientStart,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectAccount(acc.effectiveId)
                                    showAccountDropdown = false
                                }
                            )
                        }
                        HorizontalDivider(color = Color(0xFF2C2C3E))
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = PurpleGradientStart,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Link New Cloud Drive...",
                                        color = PurpleGradientStart,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            onClick = {
                                showAccountDropdown = false
                                showAddAccountDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Manage Accounts in Settings",
                                        color = TextSecondary,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            onClick = {
                                showAccountDropdown = false
                                navController.navigate("accounts")
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Active Filter Pill indicator if not "All"
            if (selectedFilter != "All") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = { selectedFilter = "All" },
                        label = { Text("Filter: $selectedFilter  ✕", color = PurpleGradientStart, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = PurpleGradientStart.copy(alpha = 0.15f)),
                        border = null,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Google Drive Style Folder Breadcrumbs
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(breadcrumbs) { index, item ->
                    val isLast = index == breadcrumbs.lastIndex
                    val isRoot = index == 0

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(!isLast) {
                                viewModel.navigateToBreadcrumb(index)
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = if (isRoot) Icons.Default.Cloud else Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (isLast) PurpleGradientStart else TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = item.name,
                            color = if (isLast) TextPrimary else TextSecondary,
                            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }

                    if (!isLast) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Subfolder",
                            tint = TextSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PurpleGradientStart)
                }
            } else if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No files match '$searchQuery'" else "No files found",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (accounts.isEmpty()) "Link an account in Accounts tab" else "Tap + to upload a file to this drive",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (isGridView) {
                // GRID VIEW
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (folders.isNotEmpty() && (selectedFilter == "All" || selectedFilter == "Folders")) {
                        item(span = { GridItemSpan(2) }) {
                            Text("Folders", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                        items(folders) { folder ->
                            FileGridItem(
                                file = folder,
                                navController = rootNavController,
                                accountId = effectiveAccountId,
                                onClick = { viewModel.enterFolder(folder.effectiveId, folder.effectiveName) },
                                onMoveClick = { fileForMoveCopy = Pair(folder, true) },
                                onCopyClick = { fileForMoveCopy = Pair(folder, false) }
                            )
                        }
                    }

                    if (regularFiles.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            Text("Files", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                        items(regularFiles) { file ->
                            FileGridItem(
                                file = file,
                                navController = rootNavController,
                                accountId = effectiveAccountId,
                                onClick = { openFileInViewer(file, effectiveAccountId, rootNavController, regularFiles) },
                                onMoveClick = { fileForMoveCopy = Pair(file, true) },
                                onCopyClick = { fileForMoveCopy = Pair(file, false) }
                            )
                        }
                    }
                }
            } else {
                // LIST VIEW
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp)
                ) {
                    if (folders.isNotEmpty() && (selectedFilter == "All" || selectedFilter == "Folders")) {
                        item {
                            Text("Folders", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        items(folders) { folder ->
                            FileRow(
                                file = folder,
                                navController = rootNavController,
                                accountId = effectiveAccountId,
                                onClick = { viewModel.enterFolder(folder.effectiveId, folder.effectiveName) },
                                onMoveClick = { fileForMoveCopy = Pair(folder, true) },
                                onCopyClick = { fileForMoveCopy = Pair(folder, false) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    if (regularFiles.isNotEmpty()) {
                        item {
                            Text("Files", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        items(regularFiles) { file ->
                            FileRow(
                                file = file,
                                navController = rootNavController,
                                accountId = effectiveAccountId,
                                onClick = { openFileInViewer(file, effectiveAccountId, rootNavController, regularFiles) },
                                onMoveClick = { fileForMoveCopy = Pair(file, true) },
                                onCopyClick = { fileForMoveCopy = Pair(file, false) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(
    file: CloudFile,
    navController: NavController,
    accountId: String,
    onClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null
) {
    val previewUrl = remember(file.effectiveId, accountId) {
        ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)
    }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (onClick != null) {
                    onClick()
                } else {
                    openFileInViewer(file, accountId, navController)
                }
            }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Media Preview Thumbnail / Icon Box
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF262636)),
                contentAlignment = Alignment.Center
            ) {
                if (file.isImage || file.isVideo) {
                    SubcomposeAsyncImage(
                        model = previewUrl,
                        contentDescription = file.effectiveName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    ) {
                        val state = painter.state
                        if (state is AsyncImagePainter.State.Loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PurpleGradientStart, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        } else if (state is AsyncImagePainter.State.Error) {
                            Icon(
                                imageVector = if (file.isVideo) Icons.Default.Movie else Icons.Default.Image,
                                contentDescription = null,
                                tint = PurpleGradientStart,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            SubcomposeAsyncImageContent()
                        }
                    }
                    if (file.isVideo) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                } else if (file.isFolderItem) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(28.dp))
                } else if (file.isAudio) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(26.dp))
                } else if (file.isPdf) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(26.dp))
                } else if (file.isTextOrCode) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(26.dp))
                } else {
                    Text(
                        text = file.effectiveType.take(4).uppercase(),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.effectiveName,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (file.isFolderItem) "Folder" else file.formattedSize,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    if (!file.isFolderItem) {
                        Text(
                            text = " • ${file.effectiveType.uppercase()}",
                            color = TextSecondary.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = TextSecondary)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(SurfaceDark)
                ) {
                    DropdownMenuItem(
                        text = { Text("Open / View", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showMenu = false
                            if (onClick != null) onClick() else openFileInViewer(file, accountId, navController)
                        }
                    )
                    if (onMoveClick != null) {
                        DropdownMenuItem(
                            text = { Text("Move to...", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                onMoveClick()
                            }
                        )
                    }
                    if (onCopyClick != null) {
                        DropdownMenuItem(
                            text = { Text("Copy to...", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                onCopyClick()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("File Details", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
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

@Composable
fun FileGridItem(
    file: CloudFile,
    navController: NavController,
    accountId: String,
    onClick: (() -> Unit)? = null,
    onMoveClick: (() -> Unit)? = null,
    onCopyClick: (() -> Unit)? = null
) {
    val previewUrl = remember(file.effectiveId, accountId) {
        ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)
    }
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clickable {
                if (onClick != null) {
                    onClick()
                } else {
                    openFileInViewer(file, accountId, navController)
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Preview Thumbnail Header (top 60% of card)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f)
                    .background(Color(0xFF262636)),
                contentAlignment = Alignment.Center
            ) {
                if (file.isImage || file.isVideo) {
                    SubcomposeAsyncImage(
                        model = previewUrl,
                        contentDescription = file.effectiveName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    ) {
                        val state = painter.state
                        if (state is AsyncImagePainter.State.Loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PurpleGradientStart, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else if (state is AsyncImagePainter.State.Error) {
                            Icon(
                                imageVector = if (file.isVideo) Icons.Default.Movie else Icons.Default.Image,
                                contentDescription = null,
                                tint = PurpleGradientStart,
                                modifier = Modifier.size(36.dp)
                            )
                        } else {
                            SubcomposeAsyncImageContent()
                        }
                    }
                    if (file.isVideo) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                } else if (file.isFolderItem) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(44.dp))
                } else if (file.isAudio) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(40.dp))
                } else if (file.isPdf) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(40.dp))
                } else if (file.isTextOrCode) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(40.dp))
                } else {
                    Text(
                        text = file.effectiveType.take(4).uppercase(),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            // Info Details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.effectiveName,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Text(
                        text = if (file.isFolderItem) "Folder" else file.formattedSize,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(SurfaceDark)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open / View", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                showMenu = false
                                if (onClick != null) onClick() else openFileInViewer(file, accountId, navController)
                            }
                        )
                        if (onMoveClick != null) {
                            DropdownMenuItem(
                                text = { Text("Move to...", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showMenu = false
                                    onMoveClick()
                                }
                            )
                        }
                        if (onCopyClick != null) {
                            DropdownMenuItem(
                                text = { Text("Copy to...", color = TextPrimary) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    showMenu = false
                                    onCopyClick()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("File Details", color = TextPrimary) },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
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
}
