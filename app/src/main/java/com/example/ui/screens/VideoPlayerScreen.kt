package com.example.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.DownloadHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    navController: NavController,
    accountId: String,
    fileId: String,
    files: List<CloudFile>
) {
    val context = LocalContext.current
    val file = files.find { it.effectiveId == fileId }
    val exoPlayer = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(fileId) {
        if (file != null) {
            val url = ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text(file?.effectiveName ?: "Video Player", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (file != null) {
                            val url = ApiClient.getFileUrl(accountId, file.effectiveId, preview = false)
                            DownloadHelper.downloadFile(context, url, file.effectiveName, ApiClient.getToken())
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f))
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
