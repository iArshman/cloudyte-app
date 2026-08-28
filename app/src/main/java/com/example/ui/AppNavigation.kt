package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.api.CloudFile
import com.example.api.FileRepository
import com.example.ui.screens.*

fun openFileInViewer(
    file: CloudFile,
    accountId: String,
    navController: NavController,
    currentList: List<CloudFile>? = null
) {
    val fid = file.effectiveId
    if (fid.isEmpty()) return
    if (currentList != null && currentList.isNotEmpty()) {
        FileRepository.setActiveList(currentList, accountId)
    }
    when {
        file.isVideo -> navController.navigate("video_player/$accountId/$fid")
        file.isImage -> navController.navigate("image_viewer/$accountId/$fid")
        file.isAudio -> navController.navigate("audio_player/$accountId/$fid")
        file.isTextOrCode -> navController.navigate("text_editor/$accountId/$fid")
        file.isPdf -> navController.navigate("pdf_viewer/$accountId/$fid")
        else -> navController.navigate("file_detail/$accountId/$fid")
    }
}


@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController, 
        startDestination = "splash",
        enterTransition = { fadeIn(animationSpec = tween(300)) },
        exitTransition = { fadeOut(animationSpec = tween(300)) },
        popEnterTransition = { fadeIn(animationSpec = tween(300)) },
        popExitTransition = { fadeOut(animationSpec = tween(300)) }
    ) {
        composable("splash") { SplashScreen(navController) }
        composable("login") { LoginScreen(navController) }
        composable("main") { MainScreen(navController) }
        composable("file_detail/{accountId}/{fileId}") { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            FileDetailScreen(navController, accountId, fileId, FileRepository.getActiveList())
        }
        composable("video_player/{accountId}/{fileId}") { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            VideoPlayerScreen(navController, accountId, fileId, FileRepository.getActiveList())
        }
        composable("image_viewer/{accountId}/{fileId}") { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            ImageViewerScreen(navController, accountId, fileId, FileRepository.getActiveList())
        }
        composable("audio_player/{accountId}/{fileId}") { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            AudioPlayerScreen(navController, accountId, fileId, FileRepository.getActiveList())
        }
        composable("text_editor/{accountId}/{fileId}") { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            TextEditorScreen(navController, accountId, fileId, FileRepository.getActiveList())
        }
        composable("pdf_viewer/{accountId}/{fileId}") { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            PdfViewerScreen(navController, accountId, fileId, FileRepository.getActiveList())
        }
    }
}
