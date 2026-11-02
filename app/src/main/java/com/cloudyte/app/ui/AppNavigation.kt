package com.cloudyte.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cloudyte.app.api.CloudFile
import com.cloudyte.app.api.FileRepository
import com.cloudyte.app.features.player.AudioPlayerScreen as FeatureAudioPlayerScreen
import com.cloudyte.app.features.player.ImageViewerScreen as FeatureImageViewerScreen
import com.cloudyte.app.features.player.PdfViewerScreen as FeaturePdfViewerScreen
import com.cloudyte.app.features.player.TextEditorScreen as FeatureTextEditorScreen
import com.cloudyte.app.features.player.VideoPlayerScreen as FeatureVideoPlayerScreen
import com.cloudyte.app.ui.navigation.NavigationTransitions
import com.cloudyte.app.ui.navigation.navigateOnce
import com.cloudyte.app.ui.screens.*

fun openFileInViewer(
    file: CloudFile,
    accountId: String,
    navController: NavController,
    currentList: List<CloudFile>? = null
) {
    val fid = file.effectiveId
    if (fid.isEmpty()) return
    val resolvedAccountId = when {
        file.effectiveAccountId.isNotBlank() -> file.effectiveAccountId
        accountId.isNotBlank() && accountId != "all" && accountId != "root" -> accountId
        else -> {
            val db = com.cloudyte.app.db.AppDatabase.getInstance()
            runCatching {
                kotlinx.coroutines.runBlocking {
                    db?.cloudFileDao()?.getFileById(fid)?.accountId
                        ?: db?.accountDao()?.getAllAccounts()?.firstOrNull()?.id
                }
            }.getOrNull()?.takeIf { it.isNotBlank() && it != "all" && it != "root" } ?: accountId
        }
    }
    if (currentList != null && currentList.isNotEmpty()) {
        FileRepository.setActiveList(currentList, resolvedAccountId)
    }
    when {
        file.isVideo -> navController.navigateOnce("video_player/$resolvedAccountId/$fid")
        file.isImage -> navController.navigateOnce("image_viewer/$resolvedAccountId/$fid")
        file.isAudio -> navController.navigateOnce("audio_player/$resolvedAccountId/$fid")
        file.isTextOrCode -> navController.navigateOnce("text_editor/$resolvedAccountId/$fid")
        file.isPdf -> navController.navigateOnce("pdf_viewer/$resolvedAccountId/$fid")
        else -> navController.navigateOnce("file_detail/$resolvedAccountId/$fid")
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
            FeatureVideoPlayerScreen(navController, accountId, fileId)
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
            FeatureImageViewerScreen(navController, accountId, fileId)
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
            FeatureAudioPlayerScreen(navController, accountId, fileId)
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
            FeatureTextEditorScreen(navController, accountId, fileId)
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
            FeaturePdfViewerScreen(navController, accountId, fileId)
        }
    }
}
