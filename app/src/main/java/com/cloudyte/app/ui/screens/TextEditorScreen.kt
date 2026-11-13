package com.cloudyte.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    navController: NavController,
    accountId: String,
    fileId: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val file = remember(fileId) { FileRepository.getFile(fileId) }
    val fileName = file?.effectiveName ?: "Document"

    var textContent by remember { mutableStateOf("") }
    var originalContent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(false) }
    var fontSize by remember { mutableFloatStateOf(13f) }
    var showSearchBar by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val effectiveAccId = remember(accountId, file?.effectiveAccountId) {
        when {
            file?.effectiveAccountId?.isNotBlank() == true -> file.effectiveAccountId
            accountId.isNotBlank() && accountId != "all" && accountId != "root" -> accountId
            else -> ""
        }
    }

    val fileUrl = remember(effectiveAccId, fileId) {
        val acc = effectiveAccId.ifBlank { accountId }
        ApiClient.getFileUrl(acc, fileId, preview = false)
    }

    // Load file content
    LaunchedEffect(fileId, effectiveAccId, file?.isEncrypted) {
        isLoading = true
        errorMessage = null
        try {
            val content = withContext(Dispatchers.IO) {
                val acc = effectiveAccId.ifBlank { accountId }
                if (file?.isEncrypted == true) {
                    val decryptedFile = OfflineFileManager.prepareDecryptedFileForViewer(context, acc, file).getOrThrow()
                    decryptedFile.readText()
                } else {
                    val offlineFile = if (acc.isNotBlank() && acc != "all" && acc != "root") {
                        if (file != null) {
                            OfflineFileManager.prepareDecryptedFileForViewer(context, acc, file).getOrNull()
                        } else {
                            OfflineFileManager.findOfflineFile(context, acc, fileId)
                        }
                    } else null
                    if (offlineFile != null && offlineFile.exists() && offlineFile.length() > 0) {
                        offlineFile.readText()
                    } else {
                        val token = ApiClient.getToken()
                        val requestBuilder = Request.Builder().url(fileUrl)
                        if (!token.isNullOrEmpty()) {
                            requestBuilder.header("Authorization", "Bearer $token")
                        }
                        val response = ApiClient.okHttpClient.newCall(requestBuilder.build()).execute()
                        if (response.isSuccessful) {
                            val text = response.body?.string() ?: ""
                            file?.let {
                                OfflineFileManager.autoCacheOnView(context, accountId, it)
                            }
                            text
                        } else {
                            throw Exception("Server returned HTTP ${response.code}")
                        }
                    }
                }
            }
            textContent = content
            originalContent = content
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Failed to read file"
        } finally {
            isLoading = false
        }
    }

    fun saveContent() {
        if (isSaving) return
        isSaving = true
        coroutineScope.launch {
            try {
                val queueDirectory = java.io.File(context.filesDir, "queued-text").apply { mkdirs() }
                val stagedFile = java.io.File.createTempFile("cloudyte-queued-text-", ".txt", queueDirectory)
                stagedFile.writeText(textContent)
                val app = context.applicationContext as com.cloudyte.app.CloudyteApplication
                val shouldEncrypt = file?.isEncrypted == true || runCatching {
                    com.cloudyte.app.data.UserPreferences.observe(context).first().isUploadEncryptionEnabled
                }.getOrDefault(false)
                val transferId = app.container.transferRepository.enqueueTransfer(
                    accountId = accountId,
                    fileName = fileName,
                    localPath = Uri.fromFile(stagedFile).toString(),
                    direction = com.cloudyte.app.db.TransferDirection.UPLOAD,
                    totalBytes = stagedFile.length(),
                    isEncrypted = shouldEncrypt,
                    originalName = if (shouldEncrypt) fileName else null,
                    originalMimeType = if (shouldEncrypt) (file?.mimeType ?: "text/plain") else null,
                    originalSize = if (shouldEncrypt) stagedFile.length() else null
                )
                app.container.transferCoordinator.dispatch(com.cloudyte.app.core.transfer.TransferCommand.Start(transferId))
                originalContent = textContent
                isEditMode = false
                Toast.makeText(context, "Save queued for upload", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(context, error.message ?: "Failed to queue save", Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }

    val lines = remember(textContent) { textContent.lines() }
    val lineCount = lines.size
    val wordCount = remember(textContent) {
        if (textContent.isBlank()) 0 else textContent.trim().split(Regex("\\s+")).size
    }
    val charCount = textContent.length

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = if (isEditMode) "Editing mode" else "View mode",
                            color = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Font size down
                    IconButton(onClick = { if (fontSize > 9f) fontSize -= 2f }) {
                        Text("A-", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }

                    // Font size up
                    IconButton(onClick = { if (fontSize < 24f) fontSize += 2f }) {
                        Text("A+", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }

                    // Mode Toggle (View / Edit)
                    IconButton(onClick = { isEditMode = !isEditMode }) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = "Toggle Edit",
                            tint = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Save button if changed or in edit mode
                    if (isEditMode || textContent != originalContent) {
                        IconButton(onClick = { saveContent() }) {
                            if (isSaving) {
                                com.cloudyte.app.ui.components.AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Icon(Icons.Default.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else {
                        // Copy all button
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(fileName, textContent)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            AnimatedVisibility(visible = showSearchBar) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Find in document...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotBlank()) {
                            val matches = lines.count { it.contains(searchQuery, ignoreCase = true) }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "$matches lines",
                                color = MaterialTheme.colorScheme.primary,
                                style = MetricLabelTextStyle,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                com.cloudyte.app.ui.components.LoadingState(message = "Reading document...")
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Unable to display document", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Download File", color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            } else {
                // Text / Code Container
                val verticalScroll = rememberScrollState()
                val horizontalScroll = rememberScrollState()

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScroll)
                    ) {
                        // Line numbers gutter
                        Column(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (i in 1..lineCount) {
                                Text(
                                    text = "$i",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    fontSize = fontSize.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    lineHeight = (fontSize * 1.4f).sp
                                )
                            }
                        }

                        // Content editor / viewer
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(horizontalScroll)
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            if (isEditMode) {
                                BasicTextField(
                                    value = textContent,
                                    onValueChange = { textContent = it },
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontSize = fontSize.sp,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        lineHeight = (fontSize * 1.4f).sp
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    text = textContent,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = fontSize.sp,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    lineHeight = (fontSize * 1.4f).sp
                                )
                            }
                        }
                    }
                }

                // Bottom Status Bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lines: $lineCount • Words: $wordCount • Chars: $charCount",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MetricLabelTextStyle
                        )
                        Text(
                            text = file?.effectiveType?.uppercase() ?: "TEXT",
                            color = MaterialTheme.colorScheme.primary,
                            style = MetricLabelTextStyle,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
