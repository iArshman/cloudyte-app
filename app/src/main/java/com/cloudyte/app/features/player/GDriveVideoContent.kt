package com.cloudyte.app.features.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.core.cache.PreviewFetcher
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.data.UserPreferencesState
import com.cloudyte.app.ui.theme.MetricLabelTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun GDriveVideoContent(
    file: CloudFile,
    accountId: String,
    isActive: Boolean,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    bottomPaddingExtra: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isBuffering by remember { mutableStateOf(true) }
    var statusNote by remember { mutableStateOf<String?>(null) }
    var playbackError by remember(file.effectiveId) { mutableStateOf<String?>(null) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isMuted by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var attemptedFallback by remember(file.effectiveId) { mutableStateOf(false) }
    var retryCounter by remember { mutableIntStateOf(0) }
    var autoPlayVideo by remember { mutableStateOf(true) }
    var isControlsLocked by remember { mutableStateOf(false) }
    var seekOverlayText by remember { mutableStateOf<String?>(null) }
    var seekOverlayIsForward by remember { mutableStateOf(true) }
    var resumedFromMs by remember(file.effectiveId) { mutableStateOf<Long?>(null) }
    var didApplyInitialResume by remember(file.effectiveId) { mutableStateOf(false) }

    val playbackPrefs = remember(context) {
        context.getSharedPreferences("cloudyte_video_playback", Context.MODE_PRIVATE)
    }

    LaunchedEffect(seekOverlayText) {
        if (seekOverlayText != null) {
            delay(650L)
            seekOverlayText = null
        }
    }

    LaunchedEffect(resumedFromMs) {
        if (resumedFromMs != null) {
            delay(5000L)
            resumedFromMs = null
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(MediaStreamCache.createMediaSourceFactory(context))
            .setLoadControl(MediaStreamCache.createFastLoadControl())
            .build()
    }

    // Pause immediately if swiped off page inside HorizontalPager.
    LaunchedEffect(isActive) {
        if (!isActive && exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else if (isActive && autoPlayVideo && exoPlayer.playbackState == Player.STATE_READY) {
            exoPlayer.play()
        }
    }

    LaunchedEffect(file.effectiveId, accountId, file.isEncrypted, isActive, retryCounter) {
        if (!isActive) return@LaunchedEffect
        playbackError = null
        isBuffering = true
        val prefs = runCatching { UserPreferences.observe(context).first() }.getOrDefault(UserPreferencesState())
        autoPlayVideo = prefs.autoPlayVideo
        val acc = PreviewFetcher.resolveAccountId(context, accountId, file)
        if (acc.isBlank()) {
            isBuffering = false
            playbackError = "Drive account unavailable"
            return@LaunchedEffect
        }

        val cachedLocal = PreviewFetcher.resolveCachedOnly(context, acc, file)
        if (cachedLocal != null && cachedLocal.isFile && cachedLocal.length() > 0L) {
            statusNote = null
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(cachedLocal)))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = isActive && prefs.autoPlayVideo
        } else if (file.isEncrypted && prefs.streamBeforeDownload) {
            statusNote = null
            val cyteFactory = CyteStreamingDataSource.Factory(context, acc, file)
            val streamItem = MediaItem.fromUri(ApiClient.getFileUrl(acc, file.effectiveId, preview = true))
            val mediaSource = DefaultMediaSourceFactory(cyteFactory).createMediaSource(streamItem)
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = isActive && prefs.autoPlayVideo
        } else if (file.isEncrypted || !prefs.streamBeforeDownload) {
            statusNote = if (file.isEncrypted) "Decrypting video…" else "Preparing preview…"
            val ready = PreviewFetcher.resolvePreviewFile(context, acc, file).getOrNull()
            statusNote = null
            if (ready != null && ready.isFile && ready.length() > 0L) {
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(ready)))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = isActive && prefs.autoPlayVideo
            } else if (file.isEncrypted) {
                isBuffering = false
                playbackError = "Unable to prepare encrypted video"
            } else {
                val streamUrl = ApiClient.resolveSignedMediaUrl(acc, file.effectiveId, preview = true)
                exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = isActive && prefs.autoPlayVideo
            }
        } else {
            statusNote = null
            val streamUrl = ApiClient.resolveSignedMediaUrl(acc, file.effectiveId, preview = true)
            exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = isActive && prefs.autoPlayVideo
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
                    val totalDur = exoPlayer.duration.coerceAtLeast(0L)
                    duration = totalDur
                    if (!didApplyInitialResume && file.effectiveId.isNotBlank() && totalDur > 10_000L) {
                        didApplyInitialResume = true
                        val savedPos = playbackPrefs.getLong("pos_${file.effectiveId}", 0L)
                        if (savedPos in 3_000L..(totalDur - 5_000L)) {
                            exoPlayer.seekTo(savedPos)
                            currentPosition = savedPos
                            resumedFromMs = savedPos
                        }
                    }
                } else if (state == Player.STATE_ENDED && file.effectiveId.isNotBlank()) {
                    playbackPrefs.edit().remove("pos_${file.effectiveId}").apply()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!attemptedFallback) {
                    attemptedFallback = true
                    scope.launch {
                        val acc = PreviewFetcher.resolveAccountId(context, accountId, file)
                        if (acc.isBlank()) {
                            isBuffering = false
                            playbackError = error.localizedMessage ?: "Video playback failed"
                            return@launch
                        }
                        statusNote = if (file.isEncrypted) "Decrypting local preview…" else "Buffering local preview…"
                        val localFile = PreviewFetcher.resolvePreviewFile(context, acc, file).getOrNull()
                        statusNote = null
                        if (localFile != null && localFile.isFile && localFile.length() > 0L) {
                            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(localFile)))
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = isActive && autoPlayVideo
                        } else {
                            isBuffering = false
                            playbackError = error.localizedMessage ?: "Video playback failed"
                        }
                    }
                } else {
                    isBuffering = false
                    playbackError = error.localizedMessage ?: "Video playback failed"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            if (file.effectiveId.isNotBlank()) {
                val pos = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                if (dur > 10_000L && pos in 3_000L..(dur - 5_000L)) {
                    playbackPrefs.edit().putLong("pos_${file.effectiveId}", pos).apply()
                } else if (dur > 0L && pos >= dur - 5_000L) {
                    playbackPrefs.edit().remove("pos_${file.effectiveId}").apply()
                }
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        var tick = 0
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            duration = exoPlayer.duration.coerceAtLeast(0L)
            tick++
            if (tick % 5 == 0 && file.effectiveId.isNotBlank() && duration > 10_000L && currentPosition > 3_000L) {
                playbackPrefs.edit().putLong("pos_${file.effectiveId}", currentPosition).apply()
            }
            delay(400)
        }
    }

    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = ViewerSheetContainer,
            titleContentColor = Color.White,
            title = { Text("Playback speed") },
            text = {
                Column {
                    speeds.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = playbackSpeed == speed,
                                onClick = {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = Color.White.copy(alpha = 0.6f)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (speed == 1f) "Normal" else "${speed}x",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {}
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ViewerDarkBackdrop)
            .pointerInput(isControlsLocked) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        if (isControlsLocked) return@detectTapGestures
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val fraction = offset.x / width
                        when {
                            fraction < 0.38f -> {
                                val newPos = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                                seekOverlayIsForward = false
                                seekOverlayText = "-10s"
                            }
                            fraction > 0.62f -> {
                                val maxPos = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                val newPos = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxPos)
                                exoPlayer.seekTo(newPos)
                                currentPosition = newPos
                                seekOverlayIsForward = true
                                seekOverlayText = "+10s"
                            }
                            else -> {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        if (playbackError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = playbackError ?: "Playback error",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        attemptedFallback = false
                        retryCounter++
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry")
                }
            }
        } else if (isBuffering || !statusNote.isNullOrBlank()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(40.dp)
                )
                if (!statusNote.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = statusNote.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = seekOverlayText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (seekOverlayIsForward) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 40.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = CircleShape
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (seekOverlayIsForward) Icons.Default.Forward10 else Icons.Default.Replay10,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = seekOverlayText.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showControls && isControlsLocked && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { isControlsLocked = false }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Controls locked • Tap to unlock",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showControls && !isControlsLocked && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Center playback cluster (clean Google Drive translucent circles)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    if (onPrevious != null) {
                        IconButton(
                            onClick = onPrevious,
                            enabled = hasPrevious,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    Color.Black.copy(alpha = if (hasPrevious) 0.56f else 0.22f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "Previous video",
                                tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.35f)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.Black.copy(alpha = 0.56f), CircleShape)
                            .testTag("video_rewind_button")
                    ) {
                        Icon(
                            Icons.Default.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .background(Color.Black.copy(alpha = 0.68f), CircleShape)
                            .testTag("video_play_pause_button")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val maxPos = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val newPos = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxPos)
                            exoPlayer.seekTo(newPos)
                            currentPosition = newPos
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color.Black.copy(alpha = 0.56f), CircleShape)
                            .testTag("video_forward_button")
                    ) {
                        Icon(
                            Icons.Default.Forward10,
                            contentDescription = "Forward 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    if (onNext != null) {
                        IconButton(
                            onClick = onNext,
                            enabled = hasNext,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    Color.Black.copy(alpha = if (hasNext) 0.56f else 0.22f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Next video",
                                tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f)
                            )
                        }
                    }
                }

                // Bottom scrubber + compact controls bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                            )
                        )
                        .navigationBarsPadding()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 18.dp,
                            bottom = if (bottomPaddingExtra) 44.dp else 12.dp
                        )
                ) {
                    var isDragging by remember { mutableStateOf(false) }
                    var dragFraction by remember { mutableFloatStateOf(0f) }

                    resumedFromMs?.let { resumedMs ->
                        Surface(
                            color = Color.Black.copy(alpha = 0.78f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Resumed from ${formatVideoTime(resumedMs)}",
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "Start over",
                                    color = Color(0xFF8AB4F8),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable {
                                        exoPlayer.seekTo(0L)
                                        currentPosition = 0L
                                        resumedFromMs = null
                                    }
                                )
                            }
                        }
                    }

                    val sliderValue = if (isDragging) {
                        dragFraction
                    } else if (duration > 0L) {
                        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            isDragging = true
                            dragFraction = it
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            if (duration > 0L) {
                                val target = (dragFraction * duration).toLong()
                                exoPlayer.seekTo(target)
                                currentPosition = target
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color(0xFF8AB4F8),
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatVideoTime(currentPosition)} / ${formatVideoTime(duration)}",
                            color = Color.White.copy(alpha = 0.9f),
                            style = MetricLabelTextStyle,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            IconButton(
                                onClick = { isControlsLocked = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = "Lock controls",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    isMuted = !isMuted
                                    exoPlayer.volume = if (isMuted) 0f else 1f
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = if (isMuted) "Unmute" else "Mute",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Surface(
                                color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .clickable { showSpeedDialog = true }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (playbackSpeed == 1f) "1x" else "${playbackSpeed}x",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
                            ) {
                                IconButton(
                                    onClick = {
                                        context.findHostActivity()?.let { activity ->
                                            runCatching {
                                                val params = PictureInPictureParams.Builder()
                                                    .setAspectRatio(Rational(16, 9))
                                                    .build()
                                                activity.enterPictureInPictureMode(params)
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("video_pip_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Picture-in-Picture",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    resizeMode = when (resizeMode) {
                                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                                        Icons.Default.FullscreenExit
                                    } else {
                                        Icons.Default.Fullscreen
                                    },
                                    contentDescription = "Toggle zoom fit",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

private fun formatVideoTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
