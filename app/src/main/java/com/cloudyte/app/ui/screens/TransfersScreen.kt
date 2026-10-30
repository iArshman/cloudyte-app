package com.cloudyte.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cloudyte.app.R
import com.cloudyte.app.data.TransferRepository
import com.cloudyte.app.db.AppDatabase
import com.cloudyte.app.db.LocalTransfer
import com.cloudyte.app.db.TransferStatus
import com.cloudyte.app.sync.AutoSyncManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val repo = remember { TransferRepository(db.transferDao()) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.transfers_tab_all),
        stringResource(R.string.transfers_tab_active),
        stringResource(R.string.transfers_tab_completed),
        stringResource(R.string.transfers_tab_failed)
    )

    val allTransfers by repo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())

    val filteredTransfers = when (selectedTab) {
        1 -> allTransfers.filter { it.status == TransferStatus.PENDING.name || it.status == TransferStatus.RUNNING.name || it.status == TransferStatus.PAUSED.name }
        2 -> allTransfers.filter { it.status == TransferStatus.COMPLETED.name }
        3 -> allTransfers.filter { it.status == TransferStatus.FAILED.name || it.status == TransferStatus.CANCELLED.name }
        else -> allTransfers
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val isScrolled by remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    val topBarContainerColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isScrolled) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
        label = "transfersTopBarColor"
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.transfers_title), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { repo.clearCompleted() } }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.transfers_clear_completed))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarContainerColor
                    )
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (filteredTransfers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.transfers_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = com.cloudyte.app.ui.components.LocalBottomBarInset.current + 16.dp
                    )
                ) {
                    items(filteredTransfers, key = { it.id }) { transfer ->
                        TransferItemCard(
                            transfer = transfer,
                            onPause = { scope.launch { repo.pauseTransfer(transfer.id) } },
                            onResume = {
                                scope.launch {
                                    repo.resumeTransfer(transfer.id)
                                    if (transfer.direction == "BACKUP") {
                                        AutoSyncManager.triggerImmediateSync(context, isManual = true)
                                    }
                                }
                            },
                            onRetry = {
                                scope.launch {
                                    repo.retryTransfer(transfer.id)
                                    if (transfer.direction == "BACKUP") {
                                        AutoSyncManager.triggerImmediateSync(context, isManual = true)
                                    }
                                }
                            },
                            onCancel = {
                                scope.launch {
                                    repo.cancelTransfer(transfer.id)
                                    if (transfer.direction == "BACKUP") {
                                        androidx.work.WorkManager.getInstance(context)
                                            .cancelUniqueWork(AutoSyncManager.UNIQUE_MANUAL_WORK)
                                        androidx.work.WorkManager.getInstance(context)
                                            .cancelUniqueWork(AutoSyncManager.UNIQUE_IMMEDIATE_WORK)
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TransferItemCard(
    transfer: LocalTransfer,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (transfer.direction == "UPLOAD" || transfer.direction == "BACKUP") Icons.Default.Upload else Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${transfer.direction} • ${transfer.status}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))

                when (transfer.status) {
                    TransferStatus.RUNNING.name, TransferStatus.PENDING.name -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.action_pause))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                    TransferStatus.PAUSED.name -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_resume))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                    TransferStatus.FAILED.name -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            if (transfer.status == TransferStatus.RUNNING.name || transfer.status == TransferStatus.PENDING.name || transfer.status == TransferStatus.PAUSED.name) {
                Spacer(modifier = Modifier.height(8.dp))
                val progress = if (transfer.totalBytes > 0) {
                    (transfer.bytesTransferred.toFloat() / transfer.totalBytes.toFloat()).coerceIn(0f, 1f)
                } else 0f
                @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
                androidx.compose.material3.LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!transfer.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = transfer.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
