package com.example.ui.screens

import android.util.Log
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Code
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import com.example.ui.openFileInViewer
import com.example.api.Account
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.FileRepository
import com.example.api.FolderRequest
import com.example.api.UserProfile
import com.example.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class HomeViewModel(application: android.app.Application) : androidx.lifecycle.AndroidViewModel(application) {
    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _currentAccountId = MutableStateFlow<String?>("root")
    val currentAccountId: StateFlow<String?> = _currentAccountId.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                val db = com.example.db.AppDatabase.getDatabase(getApplication())
                val cachedAccounts = db.accountDao().getAllAccounts()
                if (cachedAccounts.isNotEmpty()) {
                    _accounts.value = cachedAccounts.map { it.toAccount() }
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
                    db.accountDao().insertAccounts(accs.map { com.example.db.LocalAccount.fromAccount(it) })
                }

                val defaultAcc = _accounts.value.firstOrNull { it.isDefault == true } ?: _accounts.value.firstOrNull()
                val accId = defaultAcc?.effectiveId?.ifEmpty { null }
                _currentAccountId.value = accId
                
                val files = try {
                    ApiClient.apiService.getFiles(accId, null, null)
                } catch (e: Exception) {
                    Log.w("HomeViewModel", "Files call failed: ${e.message}")
                    emptyList()
                }
                if (files.isNotEmpty()) {
                    db.cloudFileDao().insertFiles(files.map { com.example.db.LocalCloudFile.fromCloudFile(it, accId, "root") })
                    FileRepository.cacheFiles(files)
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
                val response = ApiClient.apiService.createFolder(accId, FolderRequest(name))
                if (response.isSuccessful) {
                    loadData()
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
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isUploading by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderNameInput by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val accId = currentAccountId ?: accounts.firstOrNull()?.effectiveId
            if (!accId.isNullOrEmpty()) {
                isUploading = true
                coroutineScope.launch {
                    val uploadResult = UploadHelper.uploadFile(context, accId, null, it)
                    isUploading = false
                    if (uploadResult.isSuccess) {
                        viewModel.loadData()
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Create New Folder", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = folderNameInput,
                    onValueChange = { folderNameInput = it },
                    label = { Text("Folder Name", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentBlue,
                        unfocusedBorderColor = TextSecondary
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
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
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

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), 
        containerColor = BackgroundDark,
        floatingActionButton = {
            if (isUploading) {
                FloatingActionButton(onClick = { }, containerColor = AccentBlue) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                }
            } else {
                FloatingActionButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    containerColor = AccentBlue,
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Upload File")
                }
            }
        }
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
                    Column {
                        Text(
                            text = "Welcome Back 👋",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = profile?.displayUsername ?: "Cloud User",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Multi-cloud drive manager",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = { viewModel.loadData() },
                        modifier = Modifier.background(SurfaceDark, CircleShape)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlue)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
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
                                text = errorMessage ?: "Connection error",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { viewModel.loadData() },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                            ) {
                                Text("Retry", fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            item {
                StorageOverviewCard(accounts = accounts)
                Spacer(modifier = Modifier.height(28.dp))
            }

            item {
                Text(
                    text = "Quick Actions",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                QuickActionsRow(
                    onUploadClick = { filePickerLauncher.launch("*/*") },
                    onNewFolderClick = { showCreateFolderDialog = true },
                    onSearchClick = { navController.navigate("files") },
                    onDrivesClick = { navController.navigate("accounts") }
                )
                Spacer(modifier = Modifier.height(28.dp))
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(AccentBlue, AccentSky)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Premium Features Active",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Enjoy unlimited storage, zero-compression media streaming, and offline auto-caching.",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StorageOverviewCard(accounts: List<Account>) {
    val totalUsageBytes = accounts.sumOf { it.usageBytes }
    val totalCapacityBytes = accounts.sumOf { it.totalBytes }
    val totalFilesReported = accounts.sumOf { it.fileCount ?: 0 }
    val connectedDrivesCount = accounts.size

    val usageFormatted = formatBytes(totalUsageBytes)
    val percentage = if (totalCapacityBytes > 0L) {
        (totalUsageBytes.toFloat() / totalCapacityBytes.toFloat()) * 100f
    } else {
        accounts.firstOrNull()?.effectivePercentage ?: 0f
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Storage Overview", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokeWidth = 10.dp.toPx()
                        drawArc(
                            color = Color(0xFF2C2C2E),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(strokeWidth)
                        )
                        val sweep = (percentage / 100f).coerceIn(0.05f, 1f) * 360f
                        drawArc(
                            brush = Brush.linearGradient(listOf(AccentBlue, AccentSky)),
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            style = Stroke(strokeWidth, cap = StrokeCap.Round)
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(usageFormatted, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Used", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.width(24.dp))
                Column {
                    Text("Total Capacity", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    if (totalCapacityBytes > 0L) {
                        Text(formatBytes(totalCapacityBytes), color = AccentBlue, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("∞", color = AccentBlue, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                        Text("Unlimited TG Storage", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = BackgroundDark, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Total Files", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("$totalFilesReported", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Linked Drives", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("$connectedDrivesCount", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
        QuickActionButton(Icons.Default.Upload, "Upload", onUploadClick)
        QuickActionButton(Icons.Default.CreateNewFolder, "New Folder", onNewFolderClick)
        QuickActionButton(Icons.Default.Search, "Search", onSearchClick)
        QuickActionButton(Icons.Default.Cloud, "Drives", onDrivesClick)
    }
}

@Composable
fun QuickActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(SurfaceDark, CircleShape)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = AccentBlue)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(label, color = TextPrimary, fontSize = 12.sp)
    }
}

@Composable
fun FileListItem(
    file: CloudFile,
    navController: NavController,
    accountId: String,
    files: List<CloudFile>? = null
) {
    val previewUrl = remember(file.effectiveId, accountId) {
        ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                openFileInViewer(file, accountId, navController, files)
            }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    ) {
                        val state = painter.state
                        if (state is AsyncImagePainter.State.Loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        } else if (state is AsyncImagePainter.State.Error) {
                            Icon(
                                imageVector = if (file.isVideo) Icons.Default.Movie else Icons.Default.Image,
                                contentDescription = null,
                                tint = AccentBlue,
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
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                } else if (file.isFolderItem) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(28.dp))
                } else if (file.isAudio) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = AccentSky, modifier = Modifier.size(26.dp))
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
                    file.effectiveName,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (file.isFolderItem) "Folder" else file.formattedSize,
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
            if (!file.isFolderItem) {
                IconButton(onClick = { navController.navigate("file_detail/${accountId}/${file.effectiveId}") }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TextSecondary)
                }
            }
        }
    }
}
