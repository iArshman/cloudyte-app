package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.navigation.NavController
import com.example.api.ApiClient
import com.example.api.FileRepository
import com.example.api.OfflineFileManager
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun AudioPlayerScreen(
    navController: NavController,
    accountId: String,
    fileId: String
) {
    val context = LocalContext.current
    val file = remember(fileId) { FileRepository.getFile(fileId) }
    val fileName = file?.effectiveName ?: "Audio Track"

    val audioUrl = remember(accountId, fileId) {
        ApiClient.getFileUrl(accountId, fileId, preview = false)
    }

    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(true) }
    var isLooping by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    // Auto-cache audio for offline listening in background
    LaunchedEffect(fileId, file) {
        file?.let {
            OfflineFileManager.autoCacheOnView(context, accountId, it)
        }
    }

    val exoPlayer = remember {
        val token = ApiClient.getToken()
        val headers = mutableMapOf<String, String>()
        if (!token.isNullOrEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)

        val offlineFile = OfflineFileManager.findOfflineFile(context, accountId, fileId)
        val initialMediaItem = if (offlineFile != null && offlineFile.exists() && offlineFile.length() > 0) {
            MediaItem.fromUri(Uri.fromFile(offlineFile))
        } else {
            MediaItem.fromUri(audioUrl)
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
                setMediaItem(initialMediaItem)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Position tracker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Disc rotation animation
    val infiniteTransition = rememberInfiniteTransition(label = "disc_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Playback Speed", color = TextPrimary) },
            text = {
                Column {
                    speeds.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = playbackSpeed == speed,
                                onClick = {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = PurpleGradientStart)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (speed == 1f) "Normal (1x)" else "${speed}x",
                                color = TextPrimary,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Audio Player", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, audioUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Audio Link"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = TextPrimary)
                    }
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(audioUrl))
                        context.startActivity(intent)
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Vinyl / Album Graphic
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                PurpleGradientStart.copy(alpha = 0.8f),
                                Color(0xFF1E1B4B),
                                Color(0xFF0F172A)
                            )
                        )
                    )
                    .rotate(if (isPlaying) rotation else 0f),
                contentAlignment = Alignment.Center
            ) {
                // Vinyl grooves
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A1A24)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF242432)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(90.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(PurpleGradientStart, BlueGradientEnd)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }
                }
            }

            // Track info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = fileName,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${file?.formattedSize ?: ""} • ${file?.effectiveType?.uppercase() ?: "AUDIO"}",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }

            // Scrubber
            Column(modifier = Modifier.fillMaxWidth()) {
                var isDragging by remember { mutableStateOf(false) }
                var dragPosition by remember { mutableFloatStateOf(0f) }

                val sliderValue = if (isDragging) dragPosition else {
                    if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                }

                Slider(
                    value = sliderValue,
                    onValueChange = {
                        isDragging = true
                        dragPosition = it
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        if (duration > 0) {
                            val seekTarget = (dragPosition * duration).toLong()
                            exoPlayer.seekTo(seekTarget)
                            currentPosition = seekTarget
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = PurpleGradientStart,
                        activeTrackColor = PurpleGradientStart,
                        inactiveTrackColor = SurfaceDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatAudioTime(currentPosition),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Text(
                        text = formatAudioTime(duration),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            // Playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        isLooping = !isLooping
                        exoPlayer.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    }
                ) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = "Loop",
                        tint = if (isLooping) PurpleGradientStart else TextSecondary
                    )
                }

                IconButton(
                    onClick = {
                        val newPos = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                        exoPlayer.seekTo(newPos)
                        currentPosition = newPos
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = TextPrimary, modifier = Modifier.size(32.dp))
                }

                IconButton(
                    onClick = {
                        if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            Brush.linearGradient(listOf(PurpleGradientStart, BlueGradientEnd)),
                            CircleShape
                        )
                ) {
                    if (isBuffering) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val newPos = (exoPlayer.currentPosition + 10000L).coerceAtMost(exoPlayer.duration)
                        exoPlayer.seekTo(newPos)
                        currentPosition = newPos
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = TextPrimary, modifier = Modifier.size(32.dp))
                }

                IconButton(onClick = { showSpeedDialog = true }) {
                    Text(
                        text = if (playbackSpeed == 1f) "1x" else "${playbackSpeed}x",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun formatAudioTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        val remainingMinutes = minutes % 60
        String.format(Locale.US, "%d:%02d:%02d", hours, remainingMinutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
