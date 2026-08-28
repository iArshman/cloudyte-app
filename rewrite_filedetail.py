with open('app/src/main/java/com/example/ui/screens/FileDetailScreen.kt', 'w') as f:
    f.write("""package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.DownloadHelper
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailScreen(
    navController: NavController,
    accountId: String,
    fileId: String,
    files: List<CloudFile>
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val file = files.find { it.effectiveId == fileId }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("File Details", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        if (file == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("File not found", color = TextSecondary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Thumbnail or icon
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                if (file.hasThumbnail || file.isImage) {
                    val imageUrl = ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = when {
                            file.isDirectory -> Icons.Default.Folder
                            file.isVideo -> Icons.Default.VideoFile
                            file.isAudio -> Icons.Default.AudioFile
                            file.mimeType.contains("pdf") -> Icons.Default.PictureAsPdf
                            else -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = AccentBlue,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            Text(file.effectiveName, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Size: ${file.size ?: "Unknown"}", color = TextSecondary, fontSize = 14.sp)
            
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val url = ApiClient.getFileUrl(accountId, file.effectiveId, preview = false)
                    DownloadHelper.downloadFile(context, url, file.effectiveName, ApiClient.getToken())
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Download File")
            }
        }
    }
}
""")
