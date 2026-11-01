package com.cloudyte.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudyte.app.CloudyteApplication

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val app = context.applicationContext as CloudyteApplication
    val diagnostics = app.container.transferDiagnostics
    val snapshot = diagnostics.snapshot()
    val report = remember(snapshot) { diagnostics.export(context, CURRENT_SCHEMA_VERSION) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy diagnostics")
                    }
                    IconButton(onClick = {
                        val intent = Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Cloudyte diagnostics")
                                putExtra(Intent.EXTRA_TEXT, report)
                            },
                            "Share diagnostics"
                        )
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share diagnostics")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Diagnostics contain redacted state only. Credentials, raw file names, URLs, and file contents are excluded.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Command summary", fontWeight = FontWeight.SemiBold)
                        SummaryRow("Started", snapshot.started.toString())
                        SummaryRow("Retried", snapshot.retried.toString())
                        SummaryRow("Paused", snapshot.paused.toString())
                        SummaryRow("Resumed", snapshot.resumed.toString())
                        SummaryRow("Cancelled", snapshot.cancelled.toString())
                        SummaryRow("Events retained", snapshot.events.size.toString())
                    }
                }
            }
            if (snapshot.events.isEmpty()) {
                item { Text("No transfer events recorded in this app session.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                item { Text("Recent events", fontWeight = FontWeight.SemiBold) }
                items(snapshot.events.asReversed(), key = { it.timestamp.toString() + it.correlationId }) { event ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(event.operationType, fontWeight = FontWeight.SemiBold)
                            Text(
                                "state=${event.state ?: "-"}  transfer=${event.transferId ?: "-"}  retry=${event.retryCount}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "correlation=${event.correlationId}  account=${event.accountHash ?: "-"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item { Button(onClick = { clipboard.setText(AnnotatedString(report)) }, modifier = Modifier.fillMaxWidth()) { Text("Copy full report") } }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private const val CURRENT_SCHEMA_VERSION = 13
