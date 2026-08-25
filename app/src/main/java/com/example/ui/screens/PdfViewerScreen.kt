package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.api.ApiClient
import com.example.api.FileRepository
import com.example.ui.theme.PurpleGradientStart
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    navController: NavController,
    accountId: String,
    fileId: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val file = remember(fileId) { FileRepository.getFile(fileId) }
    val fileName = file?.effectiveName ?: "Document.pdf"

    val pdfUrl = remember(accountId, fileId) {
        ApiClient.getFileUrl(accountId, fileId, preview = false)
    }

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset += panChange
    }

    suspend fun renderPage(pageIdx: Int) {
        val r = renderer ?: return
        if (pageIdx < 0 || pageIdx >= r.pageCount) return
        withContext(Dispatchers.IO) {
            val page = r.openPage(pageIdx)
            val density = 2
            val bmp = Bitmap.createBitmap(
                page.width * density,
                page.height * density,
                Bitmap.Config.ARGB_8888
            )
            // Fill background white
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            withContext(Dispatchers.Main) {
                currentBitmap = bmp
                currentPageIndex = pageIdx
                scale = 1f
                offset = Offset.Zero
            }
        }
    }

    LaunchedEffect(fileId) {
        isLoading = true
        errorMessage = null
        try {
            val cachedPdfFile = withContext(Dispatchers.IO) {
                val tempFile = File(context.cacheDir, "pdf_view_${fileId.replace(Regex("[^a-zA-Z0-9]"), "_")}.pdf")
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    val token = ApiClient.getToken()
                    val requestBuilder = Request.Builder().url(pdfUrl)
                    if (!token.isNullOrEmpty()) {
                        requestBuilder.addHeader("Authorization", "Bearer $token")
                    }
                    val response = ApiClient.okHttpClient.newCall(requestBuilder.build()).execute()
                    if (!response.isSuccessful) {
                        throw Exception("Failed to download PDF (HTTP ${response.code})")
                    }
                    val body = response.body ?: throw Exception("Empty PDF response")
                    FileOutputStream(tempFile).use { out ->
                        body.byteStream().copyTo(out)
                    }
                }
                tempFile
            }

            val desc = ParcelFileDescriptor.open(cachedPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = desc
            val r = PdfRenderer(desc)
            renderer = r
            pageCount = r.pageCount
            renderPage(0)
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Failed to open PDF"
        } finally {
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                renderer?.close()
                pfd?.close()
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        containerColor = Color(0xFF1A1A24),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        if (pageCount > 0) {
                            Text(
                                text = "Page ${currentPageIndex + 1} of $pageCount",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, pdfUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share PDF Link"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = TextPrimary)
                    }
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pdfUrl))
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PurpleGradientStart)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading PDF pages...", color = TextSecondary)
                }
            } else if (errorMessage != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color.Red, modifier = Modifier.size(56.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Cannot preview PDF file", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage ?: "", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pdfUrl))
                            context.startActivity(intent)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart)
                    ) {
                        Text("Open Externally", color = Color.White)
                    }
                }
            } else if (currentBitmap != null) {
                // Rendered PDF page bitmap with pinch to zoom
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .transformable(state = transformState)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = currentBitmap!!.asImageBitmap(),
                        contentDescription = "PDF Page ${currentPageIndex + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = ContentScale.Fit
                    )
                }

                // Page Navigation Floating Bar
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    coroutineScope.launch { renderPage(currentPageIndex - 1) }
                                }
                            },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous Page",
                                tint = if (currentPageIndex > 0) Color.White else Color.Gray
                            )
                        }

                        Text(
                            text = "${currentPageIndex + 1} / $pageCount",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )

                        IconButton(
                            onClick = {
                                if (currentPageIndex < pageCount - 1) {
                                    coroutineScope.launch { renderPage(currentPageIndex + 1) }
                                }
                            },
                            enabled = currentPageIndex < pageCount - 1
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next Page",
                                tint = if (currentPageIndex < pageCount - 1) Color.White else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}
