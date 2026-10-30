package com.cloudyte.app.features.player

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.cloudyte.app.ui.screens.AudioPlayerScreen as LegacyAudioPlayerScreen
import com.cloudyte.app.ui.screens.ImageViewerScreen as LegacyImageViewerScreen
import com.cloudyte.app.ui.screens.PdfViewerScreen as LegacyPdfViewerScreen
import com.cloudyte.app.ui.screens.TextEditorScreen as LegacyTextEditorScreen
import com.cloudyte.app.ui.screens.VideoPlayerScreen as LegacyVideoPlayerScreen

@Composable
fun AudioPlayerScreen(navController: NavController, accountId: String, fileId: String) =
    LegacyAudioPlayerScreen(navController, accountId, fileId)

@Composable
fun ImageViewerScreen(navController: NavController, accountId: String, fileId: String) =
    LegacyImageViewerScreen(navController, accountId, fileId)

@Composable
fun PdfViewerScreen(navController: NavController, accountId: String, fileId: String) =
    LegacyPdfViewerScreen(navController, accountId, fileId)

@Composable
fun TextEditorScreen(navController: NavController, accountId: String, fileId: String) =
    LegacyTextEditorScreen(navController, accountId, fileId)

@Composable
fun VideoPlayerScreen(navController: NavController, accountId: String, fileId: String) =
    LegacyVideoPlayerScreen(navController, accountId, fileId)
