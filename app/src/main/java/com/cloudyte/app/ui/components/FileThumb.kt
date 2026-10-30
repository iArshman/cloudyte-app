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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import coil.request.ImageRequest
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.ui.theme.FileAudio
import com.cloudyte.app.ui.theme.FileCode
import com.cloudyte.app.ui.theme.FileDoc
import com.cloudyte.app.ui.theme.FilePdf
import com.cloudyte.app.ui.theme.FileVideo

@Composable
fun FileThumb(
    file: CloudFile,
    accountId: String,
    sizeDp: Dp = 48.dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    showTypeBadgeInGrid: Boolean = false
) {
    val context = LocalContext.current
    val validAccount = accountId.isNotBlank() && accountId != "root" && accountId != "all"
    val isPreviewable = (file.isImage || file.isVideo || !file.thumbUrl.isNullOrBlank()) &&
        file.effectiveId.isNotBlank() &&
        (validAccount || !file.thumbUrl.isNullOrBlank())

    val fallbackPreviewUrl = remember(file.effectiveId, accountId, validAccount) {
        if (validAccount && file.effectiveId.isNotBlank()) {
            ApiClient.getFileUrl(accountId = accountId, fileId = file.effectiveId, preview = true)
        } else {
            null
        }
    }

    val primaryThumbUrl = remember(file.effectiveId, file.thumbUrl, fallbackPreviewUrl, isPreviewable) {
        if (!isPreviewable) {
            null
        } else {
            val raw = file.thumbUrl
            if (!raw.isNullOrBlank()) {
                val fullUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    raw
                } else {
                    val path = if (raw.startsWith("/")) raw else "/$raw"
                    "${ApiClient.API_BASE_URL}$path"
                }
                val token = ApiClient.getToken()
                if (!token.isNullOrEmpty() && fullUrl.startsWith(ApiClient.API_BASE_URL) && !fullUrl.contains("token=")) {
                    val sep = if (fullUrl.contains("?")) "&" else "?"
                    "$fullUrl${sep}token=$token"
                } else {
                    fullUrl
                }
            } else {
                fallbackPreviewUrl
            }
        }
    }

    var useFallbackUrl by remember(primaryThumbUrl) { mutableStateOf(false) }
    val activeUrl = if (useFallbackUrl && fallbackPreviewUrl != null && fallbackPreviewUrl != primaryThumbUrl) {
        fallbackPreviewUrl
    } else {
        primaryThumbUrl
    }

    val cacheKey = remember(file.effectiveId, file.updatedAt, useFallbackUrl) {
        "thumb:${file.effectiveId}:${file.updatedAt}:$useFallbackUrl"
    }

    val imageRequest = remember(context, activeUrl, cacheKey) {
        activeUrl?.let { url ->
            val builder = ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .crossfade(true)
            val token = ApiClient.getToken()
            if (!token.isNullOrEmpty()) {
                builder.addHeader("Authorization", "Bearer $token")
            }
            builder.build()
        }
    }

    val containerModifier = if (modifier == Modifier) {
        Modifier.size(sizeDp)
    } else {
        modifier.heightIn(min = sizeDp)
    }

    val iconSize = (sizeDp * 0.52f).coerceIn(22.dp, 64.dp)
    val badgeSize = (sizeDp * 0.42f).coerceIn(20.dp, 48.dp)
    val playIconSize = (badgeSize * 0.62f).coerceIn(12.dp, 28.dp)

    Box(
        modifier = containerModifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        if (imageRequest != null) {
            val painter = rememberAsyncImagePainter(model = imageRequest)
            val state = painter.state

            when (state) {
                is AsyncImagePainter.State.Loading,
                is AsyncImagePainter.State.Empty -> {
                    FileTypeFallbackContent(
                        file = file,
                        iconSize = iconSize,
                        showTypeBadgeInGrid = showTypeBadgeInGrid
                    )
                }
                is AsyncImagePainter.State.Error -> {
                    if (!useFallbackUrl && fallbackPreviewUrl != null && fallbackPreviewUrl != primaryThumbUrl) {
                        useFallbackUrl = true
                    }
                    FileTypeFallbackContent(
                        file = file,
                        iconSize = iconSize,
                        showTypeBadgeInGrid = showTypeBadgeInGrid
                    )
                }
                is AsyncImagePainter.State.Success -> {
                    Image(
                        painter = painter,
                        contentDescription = file.effectiveName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            if (file.isVideo && state is AsyncImagePainter.State.Success) {
                Box(
                    modifier = Modifier
                        .size(badgeSize)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(playIconSize)
                    )
                }
            }
        } else {
            FileTypeFallbackContent(
                file = file,
                iconSize = iconSize,
                showTypeBadgeInGrid = showTypeBadgeInGrid
            )
        }
    }
}

@Composable
private fun FileTypeFallbackContent(
    file: CloudFile,
    iconSize: Dp,
    showTypeBadgeInGrid: Boolean
) {
    if (showTypeBadgeInGrid && !file.isFolderItem && !file.isImage && !file.isVideo) {
        val rawLabel = (file.type ?: file.effectiveType).uppercase().take(4).ifBlank { "FILE" }
        Text(
            text = rawLabel,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    } else {
        val (icon, tint) = when {
            file.isFolderItem -> Icons.Default.Folder to MaterialTheme.colorScheme.primary
            file.isVideo -> Icons.Default.Movie to FileVideo
            file.isImage -> Icons.Default.Image to FileDoc
            file.isAudio -> Icons.Default.MusicNote to FileAudio
            file.isPdf -> Icons.Default.PictureAsPdf to FilePdf
            file.isTextOrCode -> Icons.Default.Code to FileCode
            else -> Icons.Default.Description to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(
            imageVector = icon,
            contentDescription = file.effectiveName,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}
