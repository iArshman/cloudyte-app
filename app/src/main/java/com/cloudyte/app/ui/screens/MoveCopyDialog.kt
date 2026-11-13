package com.cloudyte.app.ui.screens

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
import androidx.compose.ui.window.Dialog
import com.cloudyte.app.api.Account
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.FolderRequest
import com.cloudyte.app.api.MoveCopyRequest
import com.cloudyte.app.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MoveCopyDialog(
    file: CloudFile,
    sourceAccountId: String,
    isMove: Boolean,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    MoveCopyDialog(
        files = listOf(file),
        sourceAccountId = sourceAccountId,
        isMove = isMove,
        onDismiss = onDismiss,
        onSuccess = onSuccess
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveCopyDialog(
    files: List<CloudFile>,
    sourceAccountId: String,
    isMove: Boolean,
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    if (files.isEmpty()) return
    val excludedFolderIds = remember(files) { files.map { it.effectiveId }.toSet() }
    val headerSubtitle = remember(files) {
        if (files.size == 1) files.first().effectiveName else "${files.size} items selected"
    }
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
                val items = ApiClient.apiService.getFiles(selectedAccountId, currentTargetFolderId, null).body().orEmpty()
                // Filter only folders, and exclude self if any selected item is a folder
                folderItems = items.filter { it.isFolderItem && it.effectiveId !in excludedFolderIds }
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
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isMove) Icons.Default.DriveFileMove else Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (isMove) {
                                    if (files.size == 1) "Move File" else "Move ${files.size} Items"
                                } else {
                                    if (files.size == 1) "Copy File" else "Copy ${files.size} Items"
                                },
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = headerSubtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isExecuting,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target Drive Selector
                Text(
                    text = "Destination Drive",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (accounts.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(accounts) { acc ->
                            val isSelected = acc.effectiveId == selectedAccountId
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        if (!isExecuting && selectedAccountId != acc.effectiveId) {
                                            selectedAccountId = acc.effectiveId
                                            targetFolderBreadcrumbs = listOf(BreadcrumbItem(null, "Root"))
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when {
                                            acc.effectiveType.contains("google", ignoreCase = true) -> Icons.Default.Cloud
                                            acc.effectiveType.contains("one", ignoreCase = true) -> Icons.Default.CloudQueue
                                            else -> Icons.Default.Send
                                        },
                                        contentDescription = null,
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = acc.effectiveName,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (acc.effectiveId == sourceAccountId) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "(Current)",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Target Folder Navigation & Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Destination Directory",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(
                        onClick = { showNewFolderInput = !showNewFolderInput },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = "New Folder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Breadcrumbs inside Dialog
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(targetFolderBreadcrumbs) { index, crumb ->
                        val isLast = index == targetFolderBreadcrumbs.size - 1
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(enabled = !isLast) {
                                    targetFolderBreadcrumbs = targetFolderBreadcrumbs.take(index + 1)
                                }
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = crumb.name,
                                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal
                            )
                            if (!isLast) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
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
                            placeholder = {
                                Text(
                                    "Folder Name",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Text("Create", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Folder List in current destination
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(8.dp)
                ) {
                    if (isLoadingFolders) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            com.cloudyte.app.ui.components.AppLoadingIndicator(size = 24.dp)
                        }
                    } else if (folderItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "No subfolders here",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Item will be placed directly in '${targetFolderBreadcrumbs.lastOrNull()?.name}'",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(folderItems) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .clickable {
                                            targetFolderBreadcrumbs = targetFolderBreadcrumbs + BreadcrumbItem(folder.effectiveId, folder.effectiveName)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = folder.effectiveName,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

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
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Button(
                        onClick = {
                            isExecuting = true
                            errorMessage = null
                            coroutineScope.launch {
                                try {
                                    val targetFolderParam = currentTargetFolderId ?: "root"
                                    var succeeded = 0
                                    var lastErrorCode: Int? = null
                                    for (targetFile in files) {
                                        val req = MoveCopyRequest(
                                            sourceAccountId = targetFile.effectiveAccountId.ifBlank { sourceAccountId },
                                            fileId = targetFile.effectiveId,
                                            targetAccountId = selectedAccountId,
                                            targetFolderId = targetFolderParam
                                        )
                                        val res = if (isMove) {
                                            ApiClient.apiService.moveFile(req)
                                        } else {
                                            ApiClient.apiService.copyFile(req)
                                        }
                                        if (res.isSuccessful) {
                                            succeeded++
                                        } else {
                                            lastErrorCode = res.code()
                                        }
                                    }

                                    if (succeeded == files.size) {
                                        val destDriveName = accounts.find { it.effectiveId == selectedAccountId }?.effectiveName ?: "Drive"
                                        val destFolderName = targetFolderBreadcrumbs.lastOrNull()?.name ?: "Root"
                                        val actionWord = if (isMove) "Moved" else "Copied"
                                        val targetDesc = if (files.size == 1) "'${files.first().effectiveName}'" else "$succeeded items"
                                        onSuccess("$actionWord $targetDesc to $destDriveName ($destFolderName)")
                                    } else if (succeeded > 0) {
                                        val actionWord = if (isMove) "Moved" else "Copied"
                                        onSuccess("$actionWord $succeeded of ${files.size} items (some failed)")
                                    } else {
                                        errorMessage = "Operation failed (HTTP ${lastErrorCode ?: 500})"
                                    }
                                } catch (e: Exception) {
                                    Log.e("MoveCopyDialog", "Move/Copy execution failed", e)
                                    errorMessage = e.localizedMessage ?: "Operation failed"
                                } finally {
                                    isExecuting = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        enabled = !isExecuting
                    ) {
                        if (isExecuting) {
                            com.cloudyte.app.ui.components.AppLoadingIndicator(size = 18.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isMove) "Moving..." else "Copying...",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge
                            )
                        } else {
                            Icon(
                                imageVector = if (isMove) Icons.Default.DriveFileMove else Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isMove) "Move Here" else "Copy Here",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
