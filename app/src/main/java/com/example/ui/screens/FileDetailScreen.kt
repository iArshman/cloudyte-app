package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.ui.openFileInViewer
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileDetailScreen(
    navController: NavController,
    accountId: String,
    fileId: String,
    viewModel: FileDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val file by viewModel.file.collectAsState()
    val shareUrl by viewModel.shareUrl.collectAsState()
    val isDeleting by viewModel.isDeleting.collectAsState()
    var showRenameDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMoveCopyDialog by remember { mutableStateOf(false) }
    var isMoveAction by remember { mutableStateOf(true) }
    var actionFeedbackMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionFeedbackMessage) {
        actionFeedbackMessage?.let {
            snackbarHostState.showSnackbar(it)
            actionFeedbackMessage = null
        }
    }

    LaunchedEffect(fileId) {
        viewModel.loadFile(fileId)
    }

    if (showMoveCopyDialog && file != null) {
        MoveCopyDialog(
            file = file!!,
            sourceAccountId = accountId,
            isMove = isMoveAction,
            onDismiss = { showMoveCopyDialog = false },
            onSuccess = { msg ->
                showMoveCopyDialog = false
                actionFeedbackMessage = msg
                if (isMoveAction) {
                    navController.popBackStack()
                }
            }
        )
    }

    if (shareUrl != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearShareUrl() },
            containerColor = SurfaceDark,
            title = { Text("Share Link", color = TextPrimary) },
            text = { Text(shareUrl!!, color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Link"))
                        viewModel.clearShareUrl()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart)
                ) {
                    Text("Share", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearShareUrl() }) {
                    Text("Close", color = TextSecondary)
                }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = SurfaceDark,
            title = { Text("Delete File", color = TextPrimary) },
            text = { Text("Are you sure you want to delete this file?", color = TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        val fid = file?.effectiveId ?: fileId
                        viewModel.deleteFile(accountId, fid) {
                            navController.popBackStack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Delete", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                }
                Text("File Details", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = TextPrimary)
                }
            }
        }
    ) { innerPadding ->
        val nonNullFileForDialog = file
        if (showRenameDialog && nonNullFileForDialog != null) {
            if (newFileName.isEmpty()) newFileName = nonNullFileForDialog.effectiveName
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename File", color = TextPrimary) },
                text = {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = PurpleGradientStart,
                            unfocusedBorderColor = TextSecondary
                        )
                    )
                },
                containerColor = SurfaceDark,
                confirmButton = {
                    TextButton(onClick = {
                        if (newFileName.isNotBlank()) {
                            viewModel.renameFile(accountId, nonNullFileForDialog.effectiveId, newFileName) {
                                showRenameDialog = false
                            }
                        }
                    }) {
                        Text("Save", color = PurpleGradientStart)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }

        if (file == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PurpleGradientStart)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading file details...", color = TextSecondary)
                }
            }
            return@Scaffold
        }

        val nonNullFile = file!!
        val previewUrl = remember(nonNullFile.effectiveId, accountId) {
            ApiClient.getFileUrl(accountId, nonNullFile.effectiveId, preview = true)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            // Media Preview Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDark)
                    .clickable {
                        openFileInViewer(nonNullFile, accountId, navController)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (nonNullFile.isImage || nonNullFile.isVideo) {
                    SubcomposeAsyncImage(
                        model = previewUrl,
                        contentDescription = "File Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    ) {
                        val state = painter.state
                        if (state is AsyncImagePainter.State.Loading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PurpleGradientStart, modifier = Modifier.size(24.dp))
                            }
                        } else if (state is AsyncImagePainter.State.Error) {
                            Icon(
                                imageVector = if (nonNullFile.isVideo) Icons.Default.Movie else Icons.Default.Image,
                                contentDescription = null,
                                tint = PurpleGradientStart,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            SubcomposeAsyncImageContent()
                        }
                    }
                    if (nonNullFile.isVideo) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                } else if (nonNullFile.isFolderItem) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(64.dp))
                } else if (nonNullFile.isAudio) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(64.dp))
                } else if (nonNullFile.isPdf) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(64.dp))
                } else if (nonNullFile.isTextOrCode) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(64.dp))
                } else {
                    Icon(Icons.Default.Description, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(64.dp))
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    nonNullFile.effectiveName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("${nonNullFile.formattedSize} • ${nonNullFile.effectiveType.uppercase()}", color = TextSecondary, fontSize = 14.sp)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val dateFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
            val dateStr = nonNullFile.time?.let { dateFormat.format(Date(it)) }
                ?: nonNullFile.createdAt
                ?: "Recent"

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Details", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    DetailRow("Location", nonNullFile.location ?: nonNullFile.path ?: "Root")
                    DetailRow("Uploaded", dateStr)
                    DetailRow("Size", nonNullFile.formattedSize)
                    DetailRow("Type", nonNullFile.effectiveType.uppercase())
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Action button: Open in viewer / player / editor
            Button(
                onClick = {
                    openFileInViewer(nonNullFile, accountId, navController)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Brush.linearGradient(listOf(PurpleGradientStart, BlueGradientEnd)), RoundedCornerShape(14.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Icon(
                    imageVector = when {
                        nonNullFile.isVideo -> Icons.Default.PlayArrow
                        nonNullFile.isImage -> Icons.Default.Visibility
                        nonNullFile.isAudio -> Icons.Default.MusicNote
                        nonNullFile.isTextOrCode -> Icons.Default.Edit
                        nonNullFile.isPdf -> Icons.Default.PictureAsPdf
                        else -> Icons.Default.OpenInNew
                    },
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        nonNullFile.isVideo -> "Play Video"
                        nonNullFile.isImage -> "View Image"
                        nonNullFile.isAudio -> "Play Audio"
                        nonNullFile.isTextOrCode -> "Open Editor"
                        nonNullFile.isPdf -> "View PDF"
                        else -> "Open File"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Move & Copy actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        isMoveAction = true
                        showMoveCopyDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Icon(Icons.Default.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp), tint = PurpleGradientStart)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Move", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        isMoveAction = false
                        showMoveCopyDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp), tint = BlueGradientEnd)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = {
                        val token = ApiClient.getToken()
                        val downloadUrl = "${ApiClient.API_BASE_URL}/api/download/$accountId/${nonNullFile.effectiveId}?token=$token"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                OutlinedButton(
                    onClick = { viewModel.shareFile(accountId, nonNullFile.effectiveId) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
