package com.example.ui.components

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.ui.theme.FileAudio
import com.example.ui.theme.FileCode
import com.example.ui.theme.FileDoc
import com.example.ui.theme.FilePdf
import com.example.ui.theme.FileVideo

@Composable
fun FileThumb(
    file: CloudFile,
    accountId: String,
    sizeDp: Dp = 48.dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val context = LocalContext.current
    val isPreviewable = file.isImage || file.isVideo

    val thumbUrl = remember(file.effectiveId, file.thumbUrl, accountId, isPreviewable) {
        if (!isPreviewable) {
            null
        } else {
            val raw = file.thumbUrl
            if (!raw.isNullOrBlank()) {
                if (raw.startsWith("http://") || raw.startsWith("https://")) {
                    raw
                } else {
                    val path = if (raw.startsWith("/")) raw else "/$raw"
                    "${ApiClient.API_BASE_URL}$path"
                }
            } else {
                ApiClient.getThumbUrl(accountId = accountId, fileId = file.effectiveId, size = 256)
            }
        }
    }

    val cacheKey = remember(file.effectiveId, file.updatedAt) {
        "thumb:${file.effectiveId}:${file.updatedAt}"
    }

    val imageRequest = remember(context, thumbUrl, cacheKey) {
        thumbUrl?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .size(256, 256)
                .crossfade(true)
                .build()
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
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (imageRequest != null) {
            val painter = rememberAsyncImagePainter(model = imageRequest)
            val state = painter.state

            when (state) {
                is AsyncImagePainter.State.Loading,
                is AsyncImagePainter.State.Empty -> {
                    // Plain surfaceVariant background while loading, NO CircularProgressIndicator
                }
                is AsyncImagePainter.State.Error -> {
                    FileTypeFallbackIcon(file = file, iconSize = iconSize)
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

            if (file.isVideo) {
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
            FileTypeFallbackIcon(file = file, iconSize = iconSize)
        }
    }
}

@Composable
private fun FileTypeFallbackIcon(file: CloudFile, iconSize: Dp) {
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
