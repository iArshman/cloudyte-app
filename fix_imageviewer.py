with open('app/src/main/java/com/example/ui/screens/ImageViewerScreen.kt', 'w') as f:
    f.write("""package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import coil.compose.AsyncImagePainter
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.DownloadHelper
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    navController: NavController,
    accountId: String,
    initialFileId: String,
    files: List<CloudFile>
) {
    val context = LocalContext.current
    val imageFiles = files.filter { it.isImage }
    val initialPage = imageFiles.indexOfFirst { it.effectiveId == initialFileId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { imageFiles.size })
    var currentFile by remember { mutableStateOf(imageFiles.getOrNull(initialPage)) }

    LaunchedEffect(pagerState.currentPage) {
        currentFile = imageFiles.getOrNull(pagerState.currentPage)
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = currentFile?.effectiveName ?: "Image Viewer",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = currentFile ?: return@IconButton
                        val url = ApiClient.getFileUrl(accountId, file.effectiveId, preview = false)
                        DownloadHelper.downloadFile(context, url, file.effectiveName, ApiClient.getToken())
                    }) {
                        Icon(Icons.Default.Info, contentDescription = "Download", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f)
                )
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) { page ->
            val file = imageFiles[page]
            val fileUrl = remember(file.effectiveId) {
                ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)
            }
            
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SubcomposeAsyncImage(
                    model = fileUrl,
                    contentDescription = file.effectiveName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                ) {
                    val state = painter.state
                    when (state) {
                        is AsyncImagePainter.State.Loading -> {
                            CircularProgressIndicator(color = Color.White)
                        }
                        is AsyncImagePainter.State.Error -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Failed to load image", color = Color.Gray)
                            }
                        }
                        else -> {
                            SubcomposeAsyncImageContent()
                        }
                    }
                }
            }
        }
    }
}
""")
