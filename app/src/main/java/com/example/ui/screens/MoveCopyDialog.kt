package com.example.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.api.Account
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.FolderRequest
import com.example.api.MoveCopyRequest
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveCopyDialog(
    file: CloudFile,
    sourceAccountId: String,
    isMove: Boolean,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var selectedAccountId by remember { mutableStateOf(sourceAccountId) }
    var targetFolderBreadcrumbs by remember { mutableStateOf<List<BreadcrumbItem>>(listOf(BreadcrumbItem(null, "Root"))) }
    val currentTargetFolderId = targetFolderBreadcrumbs.lastOrNull()?.id

    var folderItems by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    var isLoadingFolders by remember { mutableStateOf(false) }
    var isExecuting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showNewFolderInput by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    // Load available accounts
    LaunchedEffect(Unit) {
        try {
            val list = ApiClient.apiService.getAccounts()
            accounts = list
            if (list.none { it.effectiveId == selectedAccountId } && list.isNotEmpty()) {
                selectedAccountId = list.first().effectiveId
            }
        } catch (e: Exception) {
            Log.e("MoveCopyDialog", "Error fetching accounts", e)
        }
    }

    // Function to reload folders in target account/directory
    fun loadFolders() {
        if (selectedAccountId.isBlank()) return
        isLoadingFolders = true
        errorMessage = null
        coroutineScope.launch {
            try {
                val items = ApiClient.apiService.getFiles(selectedAccountId, currentTargetFolderId, null)
                // Filter only folders, and exclude self if this item itself is a folder
                folderItems = items.filter { it.isFolderItem && it.effectiveId != file.effectiveId }
            } catch (e: Exception) {
                Log.e("MoveCopyDialog", "Error fetching folders", e)
                errorMessage = "Failed to list folders"
                folderItems = emptyList()
            } finally {
                isLoadingFolders = false
            }
        }
    }

    LaunchedEffect(selectedAccountId, currentTargetFolderId) {
        loadFolders()
    }

    Dialog(onDismissRequest = { if (!isExecuting) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 400.dp, max = 560.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Title & Mode Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (isMove) AccentBlue.copy(alpha = 0.2f) else AccentBlue.copy(alpha = 0.2f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMove) Icons.Default.DriveFileMove else Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = if (isMove) AccentBlue else AccentBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isMove) "Move File" else "Copy File",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                            Text(
                                text = file.effectiveName,
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isExecuting,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target Drive Selector
                Text(
                    text = "Destination Drive",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                if (accounts.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(accounts) { acc ->
                            val isSelected = acc.effectiveId == selectedAccountId
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) AccentBlue.copy(alpha = 0.25f) else Color(0xFF262636),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, AccentBlue) else null,
                                modifier = Modifier.clickable {
                                    if (!isExecuting && selectedAccountId != acc.effectiveId) {
                                        selectedAccountId = acc.effectiveId
                                        targetFolderBreadcrumbs = listOf(BreadcrumbItem(null, "Root"))
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when {
                                            acc.effectiveType.contains("google", ignoreCase = true) -> Icons.Default.Cloud
                                            acc.effectiveType.contains("one", ignoreCase = true) -> Icons.Default.CloudQueue
                                            else -> Icons.Default.Send
                                        },
                                        contentDescription = null,
                                        tint = if (isSelected) AccentBlue else TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = acc.effectiveName,
                                        color = if (isSelected) TextPrimary else TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (acc.effectiveId == sourceAccountId) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "(Current)",
                                            color = TextSecondary.copy(alpha = 0.6f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Target Folder Navigation & Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Destination Directory",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { showNewFolderInput = !showNewFolderInput },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder", tint = AccentBlue, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Breadcrumbs inside Dialog
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF20202E))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(targetFolderBreadcrumbs) { index, crumb ->
                        val isLast = index == targetFolderBreadcrumbs.size - 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = crumb.name,
                                color = if (isLast) AccentBlue else TextSecondary,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    if (index < targetFolderBreadcrumbs.size - 1) {
                                        targetFolderBreadcrumbs = targetFolderBreadcrumbs.take(index + 1)
                                    }
                                }
                            )
                            if (!isLast) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = TextSecondary.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp).padding(horizontal = 2.dp)
                                )
                            }
                        }
                    }
                }

                // New Folder inline input
                AnimatedVisibility(visible = showNewFolderInput) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            placeholder = { Text("Folder Name", color = TextSecondary, fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = TextSecondary
                            ),
                            modifier = Modifier.weight(1f).height(48.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if (newFolderName.isNotBlank()) {
                                    coroutineScope.launch {
                                        try {
                                            val res = ApiClient.apiService.createFolder(
                                                selectedAccountId,
                                                FolderRequest(newFolderName.trim(), currentTargetFolderId)
                                            )
                                            if (res.isSuccessful) {
                                                newFolderName = ""
                                                showNewFolderInput = false
                                                loadFolders()
                                            }
                                        } catch (e: Exception) {
                                            Log.e("MoveCopyDialog", "Create folder error", e)
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("Create", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Folder List in current destination
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E2C))
                        .padding(8.dp)
                ) {
                    if (isLoadingFolders) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(24.dp))
                        }
                    } else if (folderItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("No subfolders here", color = TextSecondary.copy(alpha = 0.7f), fontSize = 12.sp)
                                Text("Item will be placed directly in '${targetFolderBreadcrumbs.lastOrNull()?.name}'", color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp)
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(folderItems) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            targetFolderBreadcrumbs = targetFolderBreadcrumbs + BreadcrumbItem(folder.effectiveId, folder.effectiveName)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder.effectiveName,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(errorMessage!!, color = Color.Red, fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Confirmation / Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isExecuting
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }

                    Button(
                        onClick = {
                            isExecuting = true
                            errorMessage = null
                            coroutineScope.launch {
                                try {
                                    val targetFolderParam = currentTargetFolderId ?: "root"
                                    val req = MoveCopyRequest(
                                        sourceAccountId = sourceAccountId,
                                        fileId = file.effectiveId,
                                        targetAccountId = selectedAccountId,
                                        targetFolderId = targetFolderParam
                                    )
                                    val res = if (isMove) {
                                        ApiClient.apiService.moveFile(req)
                                    } else {
                                        ApiClient.apiService.copyFile(req)
                                    }

                                    if (res.isSuccessful) {
                                        val destDriveName = accounts.find { it.effectiveId == selectedAccountId }?.effectiveName ?: "Drive"
                                        val destFolderName = targetFolderBreadcrumbs.lastOrNull()?.name ?: "Root"
                                        val actionWord = if (isMove) "Moved" else "Copied"
                                        onSuccess("$actionWord '${file.effectiveName}' to $destDriveName ($destFolderName)")
                                    } else {
                                        errorMessage = "Operation failed (HTTP ${res.code()})"
                                    }
                                } catch (e: Exception) {
                                    Log.e("MoveCopyDialog", "Move/Copy execution failed", e)
                                    errorMessage = e.localizedMessage ?: "Operation failed"
                                } finally {
                                    isExecuting = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        enabled = !isExecuting
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isMove) "Moving..." else "Copying...", color = Color.White, fontSize = 13.sp)
                        } else {
                            Icon(
                                imageVector = if (isMove) Icons.Default.DriveFileMove else Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isMove) "Move Here" else "Copy Here",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
