package com.cloudyte.app.ui.screens

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
import androidx.compose.ui.window.Dialog
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.TgDriveRegisterRequest
import com.cloudyte.app.ui.theme.*
import kotlinx.coroutines.launch

enum class CloudProviderType(val displayName: String, val subtitle: String) {
    GOOGLE_DRIVE("Google Drive", "Connect via Google OAuth"),
    ONEDRIVE("OneDrive", "Connect via Microsoft OAuth"),
    TELEGRAM("Telegram Cloud", "Unlimited storage via Telegram Channel")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountDialog(
    initialProvider: CloudProviderType = CloudProviderType.GOOGLE_DRIVE,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedProvider by remember(initialProvider) { mutableStateOf(initialProvider) }

    // Telegram inputs
    var channelId by remember { mutableStateOf("") }
    var driveName by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var oauthWaitingForReturn by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Link Cloud Drive",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Add a new storage provider to Cloudyte",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Provider Selection Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CloudProviderType.values().forEach { provider ->
                        val isSelected = provider == selectedProvider
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .clickable {
                                    if (!isLoading) {
                                        selectedProvider = provider
                                        errorMsg = null
                                        oauthWaitingForReturn = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (provider) {
                                    CloudProviderType.GOOGLE_DRIVE -> "Google"
                                    CloudProviderType.ONEDRIVE -> "OneDrive"
                                    CloudProviderType.TELEGRAM -> "Telegram"
                                },
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (errorMsg != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        Text(
                            text = errorMsg!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
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
                .background(ProviderGoogle.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Cloud, contentDescription = "Google Drive", tint = ProviderGoogle, modifier = Modifier.size(32.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Link Google Drive",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Securely connect your personal or workspace Google Drive account via OAuth 2.0.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (oauthWaiting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Browser window opened. Once you approve permissions, tap 'Done' below to refresh your linked drives.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onVerifyCompleted,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Done / Refresh Drives",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        } else {
            Button(
                onClick = onConnectClick,
                colors = ButtonDefaults.buttonColors(containerColor = ProviderGoogle, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    com.cloudyte.app.ui.components.AppLoadingIndicator(size = 20.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Opening Google...", color = Color.White, style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect Google Drive", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
                .background(ProviderOneDrive.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CloudQueue, contentDescription = "OneDrive", tint = ProviderOneDrive, modifier = Modifier.size(32.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Link Microsoft OneDrive",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Access Microsoft OneDrive and Office 365 cloud files seamlessly with Microsoft Graph.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (oauthWaiting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Browser window opened. Once you approve permissions, tap 'Done' below to refresh your linked drives.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onVerifyCompleted,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Done / Refresh Drives",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        } else {
            Button(
                onClick = onConnectClick,
                colors = ButtonDefaults.buttonColors(containerColor = ProviderOneDrive, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    com.cloudyte.app.ui.components.AppLoadingIndicator(size = 20.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Opening Microsoft...", color = Color.White, style = MaterialTheme.typography.labelLarge)
                } else {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connect OneDrive", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, contentDescription = null, tint = ProviderTelegram, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ensure the Cloudyte Bot is added as an Administrator with upload permissions in your Telegram channel.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedTextField(
            value = driveName,
            onValueChange = onDriveNameChange,
            label = { Text("Drive Name (Optional)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) },
            placeholder = { Text("e.g. My Personal Cloud", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = ProviderTelegram,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = channelId,
            onValueChange = onChannelIdChange,
            label = { Text("Channel ID *", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) },
            placeholder = { Text("e.g. -1001987654321", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedBorderColor = ProviderTelegram,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            colors = ButtonDefaults.buttonColors(containerColor = ProviderTelegram, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                com.cloudyte.app.ui.components.AppLoadingIndicator(size = 20.dp, color = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Registering...", color = Color.White, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Register Telegram Drive", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
