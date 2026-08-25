package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavController
import kotlin.math.absoluteValue
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.example.api.ApiClient
import com.example.api.CloudFile
import com.example.api.FileRepository
import com.example.ui.openFileInViewer
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    navController: NavController,
    accountId: String,
    fileId: String
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Retrieve active list from FileRepository or fallback
    val initialFile = remember(fileId) { FileRepository.getFile(fileId) }
    val allFilesInFolder = remember {
        val list = FileRepository.getActiveList().filter { it.isFolder != true }
        if (list.any { it.effectiveId == fileId }) {
            list
        } else if (initialFile != null) {
            listOf(initialFile)
        } else {
            emptyList()
        }
    }

    var filterMediaOnly by remember { mutableStateOf(false) }

    val displayedFiles = remember(allFilesInFolder, filterMediaOnly, fileId) {
        if (filterMediaOnly) {
            val mediaList = allFilesInFolder.filter { it.isImage || it.isVideo }
            if (mediaList.isNotEmpty()) mediaList else allFilesInFolder
        } else {
            allFilesInFolder
        }
    }

    val initialIndex = remember(displayedFiles, fileId) {
        val idx = displayedFiles.indexOfFirst { it.effectiveId == fileId }
        if (idx >= 0) idx else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (displayedFiles.size - 1).coerceAtLeast(0)),
        pageCount = { displayedFiles.size.coerceAtLeast(1) }
    )

    val thumbnailListState = rememberLazyListState()

    var showControls by remember { mutableStateOf(true) }
    var currentScale by remember { mutableFloatStateOf(1f) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showMoveCopyDialog by remember { mutableStateOf(false) }
    var isMoveAction by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMsg = null
        }
    }

    // Scroll thumbnail list when pager changes
    LaunchedEffect(pagerState.currentPage) {
        currentScale = 1f
        if (displayedFiles.isNotEmpty() && pagerState.currentPage in displayedFiles.indices) {
            thumbnailListState.animateScrollToItem(pagerState.currentPage)
        }
    }

    val currentFile = displayedFiles.getOrNull(pagerState.currentPage) ?: initialFile

    if (showMoveCopyDialog && currentFile != null) {
        MoveCopyDialog(
            file = currentFile,
            sourceAccountId = accountId,
            isMove = isMoveAction,
            onDismiss = { showMoveCopyDialog = false },
            onSuccess = { msg ->
                showMoveCopyDialog = false
                snackbarMsg = msg
            }
        )
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            if (displayedFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No media files available", color = TextSecondary, fontSize = 16.sp)
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = currentScale <= 1.05f,
                    pageSpacing = 16.dp
                ) { page ->
                    val file = displayedFiles.getOrNull(page)
                    if (file != null) {
                        val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    // Smooth gallery slide transition with subtle depth & scale
                                    val scale = lerp(0.92f, 1f, 1f - pageOffset.coerceIn(0f, 1f))
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = lerp(0.55f, 1f, 1f - pageOffset.coerceIn(0f, 1f))
                                }
                        ) {
                            MediaPageContent(
                                file = file,
                                accountId = accountId,
                                isCurrentPage = pagerState.currentPage == page,
                                onScaleChanged = { scale ->
                                    if (pagerState.currentPage == page) {
                                        currentScale = scale
                                    }
                                },
                                onTap = { showControls = !showControls },
                                navController = navController
                            )
                        }
                    }
                }
            }

            // Quick Next / Previous Side Chevron Navigation (Gallery slide buttons)
            if (showControls && displayedFiles.size > 1) {
                // Previous Button
                if (pagerState.currentPage > 0) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(
                                    page = pagerState.currentPage - 1,
                                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 12.dp)
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Next Button
                if (pagerState.currentPage < displayedFiles.size - 1) {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(
                                    page = pagerState.currentPage + 1,
                                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 12.dp)
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            // Top Header Bar
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                val currentFileUrl = currentFile?.let { ApiClient.getFileUrl(accountId, it.effectiveId, preview = false) } ?: ""

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.4f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .size(40.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = currentFile?.effectiveName ?: "Media Preview",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (currentFile != null) {
                                    Text(
                                        text = "${currentFile.formattedSize} • ${currentFile.effectiveType.uppercase()} • ${pagerState.currentPage + 1} of ${displayedFiles.size}",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentScale > 1.05f) {
                                IconButton(
                                    onClick = { currentScale = 1f },
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                        .size(38.dp)
                                ) {
                                    Icon(Icons.Default.ZoomOutMap, contentDescription = "Reset Zoom", tint = Color.White)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            // Share Action
                            IconButton(
                                onClick = {
                                    if (currentFileUrl.isNotBlank()) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, currentFileUrl)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share Media Link"))
                                    }
                                },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .size(38.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(6.dp))

                            // Download Action
                            IconButton(
                                onClick = {
                                    if (currentFileUrl.isNotBlank()) {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentFileUrl))
                                        context.startActivity(intent)
                                    }
                                },
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .size(38.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(6.dp))

                            // More Menu
                            Box {
                                IconButton(
                                    onClick = { showMoreMenu = true },
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                        .size(38.dp)
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.White)
                                }

                                DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false },
                                    modifier = Modifier.background(SurfaceDark)
                                ) {
                                    val cf = currentFile
                                    if (cf != null) {
                                        DropdownMenuItem(
                                            text = { Text("Open in File Details", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                showMoreMenu = false
                                                navController.navigate("file_detail/$accountId/${cf.effectiveId}")
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Move to...", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = PurpleGradientStart, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                showMoreMenu = false
                                                isMoveAction = true
                                                showMoveCopyDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Copy to...", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                showMoreMenu = false
                                                isMoveAction = false
                                                showMoveCopyDialog = true
                                            }
                                        )
                                        HorizontalDivider(color = Color(0xFF2C2C3E))
                                        DropdownMenuItem(
                                            text = { Text(if (filterMediaOnly) "Show All Files (${allFilesInFolder.size})" else "Media Only Filter", color = TextSecondary) },
                                            leadingIcon = { Icon(if (filterMediaOnly) Icons.Default.Folder else Icons.Default.Image, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                                            onClick = {
                                                showMoreMenu = false
                                                filterMediaOnly = !filterMediaOnly
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Thumbnail Strip & Controls
            AnimatedVisibility(
                visible = showControls && displayedFiles.size > 1,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.95f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp, top = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Filmstrip / Thumbnail Row
                        LazyRow(
                            state = thumbnailListState,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(displayedFiles) { index, file ->
                                val isSelected = index == pagerState.currentPage
                                val thumbUrl = ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)

                                Box(
                                    modifier = Modifier
                                        .size(width = 54.dp, height = 54.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(SurfaceDark)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 1.dp,
                                            color = if (isSelected) PurpleGradientStart else Color.White.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            coroutineScope.launch {
                                                pagerState.animateScrollToPage(
                                                    page = index,
                                                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                                                )
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (file.isImage) {
                                        SubcomposeAsyncImage(
                                            model = thumbUrl,
                                            contentDescription = file.effectiveName,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = when {
                                                file.isVideo -> Icons.Default.Videocam
                                                file.isAudio -> Icons.Default.Audiotrack
                                                file.isPdf -> Icons.Default.PictureAsPdf
                                                file.isTextOrCode -> Icons.Default.Code
                                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                            },
                                            contentDescription = null,
                                            tint = if (isSelected) PurpleGradientStart else TextSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Page Counter Indicator
                        Text(
                            text = "${pagerState.currentPage + 1} / ${displayedFiles.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPageContent(
    file: CloudFile,
    accountId: String,
    isCurrentPage: Boolean,
    onScaleChanged: (Float) -> Unit,
    onTap: () -> Unit,
    navController: NavController
) {
    val context = LocalContext.current
    val fileUrl = remember(accountId, file.effectiveId) {
        ApiClient.getFileUrl(accountId, file.effectiveId, preview = false)
    }

    when {
        file.isImage -> {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            var rotation by remember { mutableFloatStateOf(0f) }

            // Reset zoom when not on this page
            LaunchedEffect(isCurrentPage) {
                if (!isCurrentPage) {
                    scale = 1f
                    offset = Offset.Zero
                    rotation = 0f
                    onScaleChanged(1f)
                }
            }

            val transformState = rememberTransformableState { zoomChange, panChange, rotationChange ->
                scale = (scale * zoomChange).coerceIn(0.5f, 6f)
                offset += panChange
                if (scale > 1.2f) {
                    rotation += rotationChange
                }
                onScaleChanged(scale)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                    rotation = 0f
                                    onScaleChanged(1f)
                                } else {
                                    scale = 2.5f
                                    onScaleChanged(2.5f)
                                }
                            }
                        )
                    }
                    .transformable(state = transformState),
                contentAlignment = Alignment.Center
            ) {
                SubcomposeAsyncImage(
                    model = fileUrl,
                    contentDescription = file.effectiveName,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            rotationZ = rotation,
                            translationX = offset.x,
                            translationY = offset.y
                        ),
                    contentScale = ContentScale.Fit
                ) {
                    val state = painter.state
                    when (state) {
                        is AsyncImagePainter.State.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = PurpleGradientStart, strokeWidth = 3.dp)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Loading image...", color = TextSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                        is AsyncImagePainter.State.Error -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                    Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(56.dp))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Failed to load image preview", color = TextSecondary, fontSize = 14.sp)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Button(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fileUrl))
                                            context.startActivity(intent)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart)
                                    ) {
                                        Text("Open in Browser", color = Color.White)
                                    }
                                }
                            }
                        }
                        else -> {
                            SubcomposeAsyncImageContent()
                        }
                    }
                }
            }
        }

        file.isVideo -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF1E1E2E)),
                            contentAlignment = Alignment.Center
                        ) {
                            val videoThumbUrl = ApiClient.getFileUrl(accountId, file.effectiveId, preview = true)
                            SubcomposeAsyncImage(
                                model = videoThumbUrl,
                                contentDescription = file.effectiveName,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .background(PurpleGradientStart, CircleShape)
                                    .clickable {
                                        navController.navigate("video_player/$accountId/${file.effectiveId}")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play Video", tint = Color.White, modifier = Modifier.size(38.dp))
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = file.effectiveName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${file.formattedSize} • Video",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { navController.navigate("video_player/$accountId/${file.effectiveId}") },
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Play in Full Video Player", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        file.isAudio -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .background(
                                    Brush.linearGradient(listOf(PurpleGradientStart, BlueGradientEnd)),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Audiotrack, contentDescription = null, tint = Color.White, modifier = Modifier.size(54.dp))
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = file.effectiveName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${file.formattedSize} • Audio Track",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { navController.navigate("audio_player/$accountId/${file.effectiveId}") },
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open in Audio Player", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        file.isPdf -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .background(Color(0xFFE53935).copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(46.dp))
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = file.effectiveName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${file.formattedSize} • PDF Document",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { navController.navigate("pdf_viewer/$accountId/${file.effectiveId}") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open in PDF Viewer", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        file.isTextOrCode -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .background(BlueGradientEnd.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = BlueGradientEnd, modifier = Modifier.size(46.dp))
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = file.effectiveName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${file.formattedSize} • Text / Code Document",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { navController.navigate("text_editor/$accountId/${file.effectiveId}") },
                            colors = ButtonDefaults.buttonColors(containerColor = BlueGradientEnd),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open in Text Editor", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onTap() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(0.9f)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .background(Color(0xFF2C2C3E), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(46.dp))
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = file.effectiveName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${file.formattedSize} • ${file.effectiveType.uppercase()}",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = { navController.navigate("file_detail/$accountId/${file.effectiveId}") },
                            colors = ButtonDefaults.buttonColors(containerColor = PurpleGradientStart),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("View Full File Details", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
