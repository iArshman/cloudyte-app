import re

# FileDetailScreen.kt
with open('app/src/main/java/com/example/ui/screens/FileDetailScreen.kt', 'r') as f:
    text = f.read()

text = text.replace('file.hasThumbnail || file.isImage', 'file.isImage')
text = text.replace('file.isDirectory', 'file.isFolderItem')
text = text.replace('file.mimeType.contains("pdf")', 'file.isPdf')

with open('app/src/main/java/com/example/ui/screens/FileDetailScreen.kt', 'w') as f:
    f.write(text)

# ImageViewerScreen.kt
with open('app/src/main/java/com/example/ui/screens/ImageViewerScreen.kt', 'r') as f:
    text = f.read()
text = text.replace('SubcomposeAsyncImageContent', 'coil.compose.SubcomposeAsyncImageContent')
with open('app/src/main/java/com/example/ui/screens/ImageViewerScreen.kt', 'w') as f:
    f.write(text)

# TextEditorScreen.kt
with open('app/src/main/java/com/example/ui/screens/TextEditorScreen.kt', 'w') as f:
    f.write("""package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.DownloadHelper
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    navController: NavController,
    accountId: String,
    fileId: String,
    files: List<CloudFile>
) {
    val context = LocalContext.current
    val file = files.find { it.effectiveId == fileId }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text(file?.effectiveName ?: "Text Viewer", color = TextPrimary) },
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
            Text("Text reading is not implemented yet.", color = TextPrimary)
        }
    }
}
""")

