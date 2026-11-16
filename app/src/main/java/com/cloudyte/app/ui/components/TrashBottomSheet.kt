package com.cloudyte.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.TrashRestoreRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashBottomSheet(
    accountId: String?,
    onDismiss: () -> Unit,
    onRestoredOrChanged: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val queryAccId = remember(accountId) {
        accountId?.takeIf { it.isNotBlank() && it != "root" && it != "all" }
    }

    var trashItems by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var busyFileId by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }

    fun loadTrash() {
        coroutineScope.launch {
            isLoading = true
            errorText = null
            try {
                trashItems = ApiClient.apiService.getTrash(queryAccId)
            } catch (e: Exception) {
                errorText = e.localizedMessage ?: "Could not load Trash"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(queryAccId) {
        loadTrash()
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Empty Trash permanently?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "All ${trashItems.size} item(s) in Trash will be permanently deleted and cannot be recovered.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmptyConfirm = false
                        coroutineScope.launch {
                            isLoading = true
                            try {
                                val resp = ApiClient.apiService.emptyTrash(queryAccId)
                                if (resp.isSuccessful) {
                                    trashItems = emptyList()
                                    onRestoredOrChanged("Trash emptied")
                                } else {
                                    errorText = "Failed to empty Trash (HTTP ${resp.code()})"
                                }
                            } catch (e: Exception) {
                                errorText = e.localizedMessage ?: "Failed to empty Trash"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Empty Trash", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Trash",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Items are automatically purged after 30 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (trashItems.isNotEmpty() && !isLoading) {
                    OutlinedButton(
                        onClick = { showEmptyConfirm = true },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("empty_trash_button"),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Empty")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (errorText != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = errorText.orEmpty(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                trashItems.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Trash is empty",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Deleted files appear here for 30 days before permanent removal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(trashItems, key = { "${it.effectiveAccountId}_${it.effectiveId}" }) { item ->
                            val itemAccId = item.effectiveAccountId.ifBlank { queryAccId.orEmpty() }
                            val isBusy = busyFileId == item.effectiveId
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FileThumb(
                                        file = item,
                                        accountId = itemAccId,
                                        sizeDp = 44.dp,
                                        cornerRadius = 12.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.effectiveName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = item.formattedSize,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isBusy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .padding(horizontal = 12.dp)
                                                .size(22.dp),
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        IconButton(
                                            onClick = {
                                                if (itemAccId.isBlank()) return@IconButton
                                                coroutineScope.launch {
                                                    busyFileId = item.effectiveId
                                                    try {
                                                        val resp = ApiClient.apiService.restoreFromTrash(
                                                            TrashRestoreRequest(
                                                                accountId = itemAccId,
                                                                fileId = item.effectiveId
                                                            )
                                                        )
                                                        if (resp.isSuccessful) {
                                                            trashItems = trashItems.filterNot { it.effectiveId == item.effectiveId }
                                                            onRestoredOrChanged("Restored '${item.effectiveName}'")
                                                        } else {
                                                            errorText = "Restore failed (HTTP ${resp.code()})"
                                                        }
                                                    } catch (e: Exception) {
                                                        errorText = e.localizedMessage ?: "Restore failed"
                                                    } finally {
                                                        busyFileId = null
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("trash_restore_${item.effectiveId}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.RestoreFromTrash,
                                                contentDescription = "Restore ${item.effectiveName}",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (itemAccId.isBlank()) return@IconButton
                                                coroutineScope.launch {
                                                    busyFileId = item.effectiveId
                                                    try {
                                                        val resp = ApiClient.apiService.deleteFromTrash(
                                                            fileId = item.effectiveId,
                                                            accountId = itemAccId
                                                        )
                                                        if (resp.isSuccessful) {
                                                            trashItems = trashItems.filterNot { it.effectiveId == item.effectiveId }
                                                            onRestoredOrChanged("Permanently deleted '${item.effectiveName}'")
                                                        } else {
                                                            errorText = "Permanent delete failed (HTTP ${resp.code()})"
                                                        }
                                                    } catch (e: Exception) {
                                                        errorText = e.localizedMessage ?: "Permanent delete failed"
                                                    } finally {
                                                        busyFileId = null
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(48.dp)
                                                .testTag("trash_delete_${item.effectiveId}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.DeleteForever,
                                                contentDescription = "Delete permanently ${item.effectiveName}",
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
