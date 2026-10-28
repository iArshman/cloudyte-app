package com.example.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.api.CloudFile
import com.example.api.FileRepository
import com.example.ui.navigation.NavigationTransitions
import com.example.ui.navigation.navigateOnce
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
        file.isVideo -> navController.navigateOnce("video_player/$accountId/$fid")
        file.isImage -> navController.navigateOnce("image_viewer/$accountId/$fid")
        file.isAudio -> navController.navigateOnce("audio_player/$accountId/$fid")
        file.isTextOrCode -> navController.navigateOnce("text_editor/$accountId/$fid")
        file.isPdf -> navController.navigateOnce("pdf_viewer/$accountId/$fid")
        else -> navController.navigateOnce("file_detail/$accountId/$fid")
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    NavHost(
        navController = navController,
        startDestination = "splash",
        enterTransition = { NavigationTransitions.fadeThroughEnter() },
        exitTransition = { NavigationTransitions.fadeThroughExit() }
    ) {
        composable("splash") { SplashScreen(navController) }
        composable("login") { LoginScreen(navController) }
        composable("main") { MainScreen(navController) }
        
        composable(
            route = "file_detail/{accountId}/{fileId}",
            enterTransition = { NavigationTransitions.sharedAxisEnter(true) },
            exitTransition = { NavigationTransitions.sharedAxisExit(true) },
            popEnterTransition = { NavigationTransitions.sharedAxisEnter(false) },
            popExitTransition = { NavigationTransitions.sharedAxisExit(false) }
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            FileDetailScreen(navController, accountId, fileId)
        }
        
        composable(
            route = "video_player/{accountId}/{fileId}",
            enterTransition = { NavigationTransitions.previewEnter() },
            exitTransition = { NavigationTransitions.previewExit() },
            popEnterTransition = { NavigationTransitions.previewEnter() },
            popExitTransition = { NavigationTransitions.previewExit() }
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            VideoPlayerScreen(navController, accountId, fileId)
        }
        
        composable(
            route = "image_viewer/{accountId}/{fileId}",
            enterTransition = { NavigationTransitions.previewEnter() },
            exitTransition = { NavigationTransitions.previewExit() },
            popEnterTransition = { NavigationTransitions.previewEnter() },
            popExitTransition = { NavigationTransitions.previewExit() }
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            ImageViewerScreen(navController, accountId, fileId)
        }
        
        composable(
            route = "audio_player/{accountId}/{fileId}",
            enterTransition = { NavigationTransitions.sharedAxisEnter(true) },
            exitTransition = { NavigationTransitions.sharedAxisExit(true) },
            popEnterTransition = { NavigationTransitions.sharedAxisEnter(false) },
            popExitTransition = { NavigationTransitions.sharedAxisExit(false) }
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            AudioPlayerScreen(navController, accountId, fileId)
        }
        
        composable(
            route = "text_editor/{accountId}/{fileId}",
            enterTransition = { NavigationTransitions.sharedAxisEnter(true) },
            exitTransition = { NavigationTransitions.sharedAxisExit(true) },
            popEnterTransition = { NavigationTransitions.sharedAxisEnter(false) },
            popExitTransition = { NavigationTransitions.sharedAxisExit(false) }
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            TextEditorScreen(navController, accountId, fileId)
        }
        
        composable(
            route = "pdf_viewer/{accountId}/{fileId}",
            enterTransition = { NavigationTransitions.sharedAxisEnter(true) },
            exitTransition = { NavigationTransitions.sharedAxisExit(true) },
            popEnterTransition = { NavigationTransitions.sharedAxisEnter(false) },
            popExitTransition = { NavigationTransitions.sharedAxisExit(false) }
        ) { backStackEntry ->
            val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
            val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
            PdfViewerScreen(navController, accountId, fileId)
        }
    }
}
