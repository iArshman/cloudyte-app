package com.cloudyte.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.cloudyte.app.CloudyteApplication
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.OfflineFileManager
import com.cloudyte.app.core.cache.CacheKeyFactory
import com.cloudyte.app.ui.theme.FileAudio
import com.cloudyte.app.ui.theme.FileCode
import com.cloudyte.app.ui.theme.FileDoc
import com.cloudyte.app.ui.theme.FilePdf
import com.cloudyte.app.ui.theme.FileVideo
import java.io.File

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

@Composable
fun FileThumb(
    file: CloudFile,
    accountId: String,
    sizeDp: Dp = 48.dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    showTypeBadgeInGrid: Boolean = false,
    localSource: File? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as? CloudyteApplication
    val downloadedFiles by OfflineFileManager.downloadedFiles.collectAsState()
    val isDownloadedOffline = file.effectiveId in downloadedFiles

    var dbAccountId by remember(file.effectiveId) { mutableStateOf("") }
    LaunchedEffect(file.effectiveId, accountId) {
        if (file.effectiveAccountId.isBlank() && (accountId.isBlank() || accountId == "all" || accountId == "root") && app != null) {
            val local = app.container.database.cloudFileDao().getFileById(file.effectiveId)
            val accId = local?.accountId?.takeIf { it.isNotBlank() && it != "all" && it != "root" }
                ?: app.container.database.accountDao().getAllAccounts().firstOrNull()?.id
            if (!accId.isNullOrBlank()) {
                dbAccountId = accId
            }
        }
    }

    val effectiveAccountId = remember(accountId, file.effectiveAccountId, dbAccountId) {
        when {
            file.effectiveAccountId.isNotBlank() -> file.effectiveAccountId
            accountId.isNotBlank() && accountId != "root" && accountId != "all" -> accountId
            dbAccountId.isNotBlank() -> dbAccountId
            else -> ""
        }
    }

    val isPreviewable = if (file.isEncrypted) {
        file.originalMetadataAvailable && (file.isImage || file.isVideo) &&
            file.effectiveId.isNotBlank() && !file.isFolderItem
    } else {
        (file.isImage || file.isVideo || !file.thumbUrl.isNullOrBlank()) &&
            file.effectiveId.isNotBlank() && !file.isFolderItem
    }

    var cachedThumbFile by remember(file.effectiveId, file.isEncrypted) {
        mutableStateOf<File?>(
            if (file.isEncrypted) null else localSource?.takeIf { it.isFile && it.length() > 0L }
        )
    }

    LaunchedEffect(effectiveAccountId, file.effectiveId, isPreviewable, isDownloadedOffline, localSource?.absolutePath) {
        if (isPreviewable && app != null) {
            val resolvedAccId = effectiveAccountId.ifBlank {
                app.container.database.accountDao().getAllAccounts().firstOrNull()?.id.orEmpty()
            }
            if (resolvedAccId.isBlank()) return@LaunchedEffect
            val account = app.container.database.accountDao().getAccountById(resolvedAccId)
            val providerType = account?.accountType ?: "tgdrive"
            val previewRevision = file.updatedAt?.toString() ?: file.time?.toString()
            val resolvedLocalSource = localSource?.takeIf { it.isFile && it.length() > 0L }
                ?: OfflineFileManager.findOfflineFile(context, resolvedAccId, file.effectiveId)
                ?: app.container.previewStore.get(
                    CacheKeyFactory.preview(resolvedAccId, providerType, file.effectiveId, previewRevision)
                )
                ?: file.path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                ?: file.location?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.length() > 0L }

            val resolvedPath = app.container.thumbnailStore.resolve(
                accountId = resolvedAccId,
                providerType = providerType,
                file = file,
                sizePx = (sizeDp.value * 4).toInt().coerceIn(64, 512),
                localSource = resolvedLocalSource
            )
            if (!resolvedPath.isNullOrBlank() && resolvedPath.startsWith("/")) {
                val f = File(resolvedPath)
                if (f.isFile && f.length() > 0L) {
                    cachedThumbFile = f
                }
            }
        }
    }

    val remoteThumbUrl = remember(file.isEncrypted, file.thumbUrl, effectiveAccountId, file.effectiveId, sizeDp) {
        if (file.isEncrypted) {
            null
        } else {
            val raw = file.thumbUrl
            val token = ApiClient.getToken()
            when {
                !raw.isNullOrBlank() -> {
                    val full = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "${ApiClient.API_BASE_URL}/${raw.trimStart('/')}"
                    if (!token.isNullOrBlank() && !full.contains("token=")) {
                        val sep = if (full.contains("?")) "&" else "?"
                        "$full${sep}token=${Uri.encode(token.orEmpty())}"
                    } else {
                        full
                    }
                }
                effectiveAccountId.isNotBlank() && file.effectiveId.isNotBlank() -> {
                    ApiClient.getThumbUrl(effectiveAccountId, file.effectiveId, (sizeDp.value * 4).toInt().coerceIn(64, 512))
                }
                else -> null
            }
        }
    }

    val imageModel: Any? = remember(file.isEncrypted, cachedThumbFile, localSource, remoteThumbUrl) {
        when {
            cachedThumbFile != null && cachedThumbFile!!.isFile && cachedThumbFile!!.length() > 0L -> cachedThumbFile
            !file.isEncrypted && localSource != null && localSource.isFile && localSource.length() > 0L -> localSource
            !file.isEncrypted && !remoteThumbUrl.isNullOrBlank() -> remoteThumbUrl
            else -> null
        }
    }

    val containerModifier = if (modifier == Modifier) Modifier.size(sizeDp) else modifier.heightIn(min = sizeDp)
    val iconSize = (sizeDp * 0.52f).coerceIn(22.dp, 64.dp)
    val badgeSize = (sizeDp * 0.42f).coerceIn(20.dp, 48.dp)
    val playIconSize = (badgeSize * 0.62f).coerceIn(12.dp, 28.dp)

    Box(
        modifier = containerModifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        if (isPreviewable && imageModel != null) {
            val imageRequest = remember(imageModel, context) {
                coil.request.ImageRequest.Builder(context)
                    .data(imageModel)
                    .crossfade(150)
                    .build()
            }
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = file.effectiveName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            ) {
                val state = painter.state
                when (state) {
                    is AsyncImagePainter.State.Success -> {
                        SubcomposeAsyncImageContent()
                    }
                    else -> {
                        FileTypeFallbackContent(file, iconSize, showTypeBadgeInGrid)
                    }
                }
            }

            if (file.isVideo) {
                Box(
                    Modifier.size(badgeSize).background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(playIconSize))
                }
            }
        } else {
            FileTypeFallbackContent(file, iconSize, showTypeBadgeInGrid)
        }
    }
}

@Composable
private fun FileTypeFallbackContent(file: CloudFile, iconSize: Dp, showTypeBadgeInGrid: Boolean) {
    if (file.isEncrypted && !file.originalMetadataAvailable) {
        Icon(Icons.Default.Lock, file.effectiveName, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize))
    } else if (showTypeBadgeInGrid && !file.isFolderItem && !file.isImage && !file.isVideo) {
        val rawLabel = file.effectiveType.uppercase().take(4).ifBlank { "FILE" }
        Text(rawLabel, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    } else {
        val (icon, tint) = when {
            file.isFolderItem -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
            file.isVideo -> Icons.Default.Movie to FileVideo
            file.isImage -> Icons.Default.Image to FileDoc
            file.isAudio -> Icons.Default.MusicNote to FileAudio
            file.isPdf -> Icons.Default.PictureAsPdf to FilePdf
            file.isTextOrCode -> Icons.Default.Code to FileCode
            file.isEncrypted -> Icons.Default.Lock to MaterialTheme.colorScheme.primary
            else -> Icons.Default.Description to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, file.effectiveName, tint = tint, modifier = Modifier.size(iconSize))
    }
}
