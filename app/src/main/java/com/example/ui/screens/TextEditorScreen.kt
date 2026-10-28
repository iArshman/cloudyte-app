package com.example.ui.screens

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
import com.example.api.ApiClient
import com.example.api.FileRepository
import com.example.api.OfflineFileManager
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
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

    val fileUrl = remember(accountId, fileId) {
        ApiClient.getFileUrl(accountId, fileId, preview = false)
    }

    // Load file content
    LaunchedEffect(fileId) {
        isLoading = true
        errorMessage = null
        try {
            val content = withContext(Dispatchers.IO) {
                val offlineFile = OfflineFileManager.findOfflineFile(context, accountId, fileId)
                if (offlineFile != null && offlineFile.exists() && offlineFile.length() > 0) {
                    offlineFile.readText()
                } else {
                    val token = ApiClient.getToken()
                    val requestBuilder = Request.Builder().url(fileUrl)
                    if (!token.isNullOrEmpty()) {
                        requestBuilder.addHeader("Authorization", "Bearer $token")
                    }
                    val response = ApiClient.okHttpClient.newCall(requestBuilder.build()).execute()
                    if (response.isSuccessful) {
                        val text = response.body?.string() ?: ""
                        // Auto-cache for offline use
                        file?.let {
                            OfflineFileManager.autoCacheOnView(context, accountId, it)
                        }
                        text
                    } else {
                        throw Exception("Server returned HTTP ${response.code}")
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
            val success = UploadHelper.uploadTextContent(
                context = context,
                accountId = accountId,
                folderId = null,
                fileName = fileName,
                content = textContent
            )
            isSaving = false
            if (success) {
                originalContent = textContent
                isEditMode = false
                Toast.makeText(context, "File saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to save file. Please check connection.", Toast.LENGTH_LONG).show()
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
        containerColor = Color(0xFF12121A),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = if (isEditMode) "Editing mode" else "View mode",
                            color = if (isEditMode) AccentBlue else TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (showSearchBar) AccentBlue else TextPrimary
                        )
                    }

                    // Font size down
                    IconButton(onClick = { if (fontSize > 9f) fontSize -= 2f }) {
                        Text("A-", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // Font size up
                    IconButton(onClick = { if (fontSize < 24f) fontSize += 2f }) {
                        Text("A+", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    // Mode Toggle (View / Edit)
                    IconButton(onClick = { isEditMode = !isEditMode }) {
                        Icon(
                            imageVector = if (isEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                            contentDescription = "Toggle Edit",
                            tint = if (isEditMode) AccentBlue else TextPrimary
                        )
                    }

                    // Save button if changed or in edit mode
                    if (isEditMode || textContent != originalContent) {
                        IconButton(onClick = { saveContent() }) {
                            if (isSaving) {
                                com.example.ui.components.AppLoadingIndicator(size = 20.dp, color = MaterialTheme.colorScheme.primary)
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
                            placeholder = { Text("Find in document...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) },
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
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                com.example.ui.components.LoadingState(message = "Reading document...")
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Unable to display document", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage ?: "", color = TextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl))
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
                        ) {
                            Text("Download File", color = Color.White)
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
                                .background(Color(0xFF0D0D14))
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (i in 1..lineCount) {
                                Text(
                                    text = "$i",
                                    color = Color.Gray.copy(alpha = 0.6f),
                                    fontSize = fontSize.sp,
                                    fontFamily = FontFamily.Monospace,
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
                                        color = TextPrimary,
                                        fontSize = fontSize.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = (fontSize * 1.4f).sp
                                    ),
                                    cursorBrush = SolidColor(AccentBlue),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                Text(
                                    text = textContent,
                                    color = TextPrimary,
                                    fontSize = fontSize.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = (fontSize * 1.4f).sp
                                )
                            }
                        }
                    }
                }

                // Bottom Status Bar
                Surface(
                    color = Color(0xFF161622),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lines: $lineCount • Words: $wordCount • Chars: $charCount",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Text(
                            text = file?.effectiveType?.uppercase() ?: "TEXT",
                            color = AccentBlue,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
