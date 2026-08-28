package com.example.ui.screens
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavController
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.DownloadHelper
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
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
            val url = ApiClient.getFileUrl(accountId, file.effectiveId, preview = false)
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(file?.effectiveName ?: "Audio Player", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (file != null) {
                            val url = ApiClient.getFileUrl(accountId, file.effectiveId, preview = false)
                            DownloadHelper.downloadFile(context, url, file.effectiveName, ApiClient.getToken())
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Playing Audio...", color = TextPrimary)
        }
    }
}
