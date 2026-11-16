package com.cloudyte.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.ui.components.LocalBottomBarInset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val app = context.applicationContext as CloudyteApplication
    val diagnostics = app.container.transferDiagnostics
    val snapshot = diagnostics.snapshot()
    val report = remember(snapshot) { diagnostics.export(context, 16) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("App Logger & Diagnostics", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy logs", tint = Color.White)
                    }
                    IconButton(onClick = {
                        val intent = Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Cloudyte App Logs")
                                putExtra(Intent.EXTRA_TEXT, report)
                            },
                            "Share logs"
                        )
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share logs", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 16.dp + LocalBottomBarInset.current
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF111111),
                    border = BorderStroke(1.dp, Color(0xFF222222))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("LOGGER STATUS: ACTIVE", color = Color(0xFF4CAF50), fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text("Started: ${snapshot.started} | Retried: ${snapshot.retried} | Paused: ${snapshot.paused} | Cancelled: ${snapshot.cancelled}", color = Color.LightGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("Total Retained Events: ${snapshot.events.size} / 300", color = Color.Gray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (snapshot.events.isEmpty()) {
                item {
                    Text(
                        "No log events recorded in this session yet.",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 32.dp)
                    )
                }
            } else {
                items(snapshot.events.reversed(), key = { it.timestamp.toString() + it.correlationId }) { event ->
                    val timeFormatted = remember(event.timestamp) {
                        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(event.timestamp))
                    }
                    val isError = event.errorCode != null || event.httpStatus?.let { it >= 400 } == true
                    val badgeColor = when {
                        isError -> Color(0xFFEF5350)
                        event.operationType == "command" -> Color(0xFF42A5F5)
                        event.operationType.contains("finish") -> Color(0xFF66BB6A)
                        else -> Color(0xFFFFB74D)
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0A0A0A),
                        border = BorderStroke(1.dp, Color(0xFF1E1E1E)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "[$timeFormatted] ${event.operationType.uppercase()}",
                                    color = badgeColor,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                if (event.httpStatus != null) {
                                    Text(
                                        text = "HTTP ${event.httpStatus}",
                                        color = if (event.httpStatus in 200..299) Color(0xFF66BB6A) else Color(0xFFEF5350),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (!event.transferId.isNullOrBlank()) {
                                Text(
                                    text = "transfer_id: ${event.transferId}",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (!event.state.isNullOrBlank()) {
                                Text(
                                    text = "state: ${event.state}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (!event.errorCode.isNullOrBlank()) {
                                Text(
                                    text = "error: ${event.errorCode}",
                                    color = Color(0xFFEF5350),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (!event.networkState.isNullOrBlank()) {
                                Text(
                                    text = "network: ${event.networkState}",
                                    color = Color(0xFF90CAF9),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
