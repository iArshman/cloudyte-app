package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.api.ApiClient
import com.example.api.TgDriveRegisterRequest
import com.example.ui.theme.*
import kotlinx.coroutines.launch

enum class CloudProviderType(val displayName: String, val subtitle: String) {
    GOOGLE_DRIVE("Google Drive", "Connect via Google OAuth"),
    ONEDRIVE("OneDrive", "Connect via Microsoft OAuth"),
    TELEGRAM("Telegram Cloud", "Unlimited storage via Telegram Channel")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedProvider by remember { mutableStateOf(CloudProviderType.GOOGLE_DRIVE) }

    // Telegram inputs
    var channelId by remember { mutableStateOf("") }
    var driveName by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var oauthWaitingForReturn by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Link Cloud Drive",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "Add a new storage provider to Cloudyte",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Provider Selection Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF20202E))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CloudProviderType.values().forEach { provider ->
                        val isSelected = provider == selectedProvider
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) PurpleGradientStart else Color.Transparent)
                                .clickable {
                                    if (!isLoading) {
                                        selectedProvider = provider
                                        errorMsg = null
                                        oauthWaitingForReturn = false
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (provider) {
                                    CloudProviderType.GOOGLE_DRIVE -> "Google"
                                    CloudProviderType.ONEDRIVE -> "OneDrive"
                                    CloudProviderType.TELEGRAM -> "Telegram"
                                },
                                color = if (isSelected) Color.White else TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                if (errorMsg != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Text(
                            text = errorMsg!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                when (selectedProvider) {
                    CloudProviderType.GOOGLE_DRIVE -> {
                        GoogleDriveLinkPanel(
                            isLoading = isLoading,
                            oauthWaiting = oauthWaitingForReturn,
                            onConnectClick = {
                                isLoading = true
                                errorMsg = null
                                coroutineScope.launch {
                                    try {
                                        val resp = ApiClient.apiService.getGoogleDriveLink()
                                        val url = resp.url
                                        if (!url.isNullOrBlank()) {
                                            oauthWaitingForReturn = true
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } else {
                                            errorMsg = "Server did not return a valid Google authorization URL."
                                        }
                                    } catch (e: Exception) {
                                        Log.e("AddAccountDialog", "Google link error", e)
                                        errorMsg = e.localizedMessage ?: "Failed to initiate Google Drive linking."
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            onVerifyCompleted = {
                                onSuccess()
                            }
                        )
                    }
                    CloudProviderType.ONEDRIVE -> {
                        OneDriveLinkPanel(
                            isLoading = isLoading,
                            oauthWaiting = oauthWaitingForReturn,
                            onConnectClick = {
                                isLoading = true
                                errorMsg = null
                                coroutineScope.launch {
                                    try {
                                        val resp = ApiClient.apiService.getOneDriveLink()
                                        val url = resp.url
                                        if (!url.isNullOrBlank()) {
                                            oauthWaitingForReturn = true
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        } else {
                                            errorMsg = "Server did not return a valid OneDrive authorization URL."
                                        }
                                    } catch (e: Exception) {
                                        Log.e("AddAccountDialog", "OneDrive link error", e)
                                        errorMsg = e.localizedMessage ?: "Failed to initiate OneDrive linking."
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            onVerifyCompleted = {
                                onSuccess()
                            }
                        )
                    }
                    CloudProviderType.TELEGRAM -> {
                        TelegramLinkPanel(
                            driveName = driveName,
                            onDriveNameChange = { driveName = it },
                            channelId = channelId,
                            onChannelIdChange = { channelId = it },
                            isLoading = isLoading,
                            onSubmit = {
                                if (channelId.isBlank()) {
                                    errorMsg = "Channel ID is required (e.g. -1001987654321)"
                                    return@TelegramLinkPanel
                                }
                                isLoading = true
                                errorMsg = null
                                coroutineScope.launch {
                                    try {
                                        val response = ApiClient.apiService.registerTgDrive(
                                            TgDriveRegisterRequest(channelId.trim(), driveName.trim())
                                        )
                                        if (response.isSuccessful) {
                                            onSuccess()
                                        } else {
                                            errorMsg = "Failed to register Telegram Drive. Please verify the bot is an admin in your channel."
                                        }
                                    } catch (e: Exception) {
                                        Log.e("AddAccountDialog", "TG register error", e)
                                        errorMsg = e.localizedMessage ?: "Network error"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GoogleDriveLinkPanel(
    isLoading: Boolean,
    oauthWaiting: Boolean,
    onConnectClick: () -> Unit,
    onVerifyCompleted: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFF4285F4).copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Cloud, contentDescription = "Google Drive", tint = Color(0xFF4285F4), modifier = Modifier.size(32.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Link Google Drive",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Securely connect your personal or workspace Google Drive account via OAuth 2.0.",
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        if (oauthWaiting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF262636)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Browser window opened. Once you approve permissions, tap 'Done' below to refresh your linked drives.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onVerifyCompleted,
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Done / Refresh Drives", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        } else {
            Button(
                onClick = onConnectClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4)),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Opening Google...", color = Color.White)
                } else {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect Google Drive", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun OneDriveLinkPanel(
    isLoading: Boolean,
    oauthWaiting: Boolean,
    onConnectClick: () -> Unit,
    onVerifyCompleted: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFF0078D4).copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CloudQueue, contentDescription = "OneDrive", tint = Color(0xFF0078D4), modifier = Modifier.size(32.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Link Microsoft OneDrive",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Access Microsoft OneDrive and Office 365 cloud files seamlessly with Microsoft Graph.",
            color = TextSecondary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        if (oauthWaiting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF262636)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Browser window opened. Once you approve permissions, tap 'Done' below to refresh your linked drives.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onVerifyCompleted,
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Done / Refresh Drives", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        } else {
            Button(
                onClick = onConnectClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0078D4)),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Opening Microsoft...", color = Color.White)
                } else {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect OneDrive", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun TelegramLinkPanel(
    driveName: String,
    onDriveNameChange: (String) -> Unit,
    channelId: String,
    onChannelIdChange: (String) -> Unit,
    isLoading: Boolean,
    onSubmit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF262636)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Ensure the Cloudyte Bot is added as an Administrator with upload permissions in your Telegram channel.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        OutlinedTextField(
            value = driveName,
            onValueChange = onDriveNameChange,
            label = { Text("Drive Name (Optional)", color = TextSecondary, fontSize = 12.sp) },
            placeholder = { Text("e.g. My Personal Cloud", color = TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = PurpleGradientStart,
                unfocusedBorderColor = TextSecondary
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = channelId,
            onValueChange = onChannelIdChange,
            label = { Text("Channel ID *", color = TextSecondary, fontSize = 12.sp) },
            placeholder = { Text("e.g. -1001987654321", color = TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedBorderColor = PurpleGradientStart,
                unfocusedBorderColor = TextSecondary
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Registering...", color = Color.White)
            } else {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Register Telegram Drive", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
