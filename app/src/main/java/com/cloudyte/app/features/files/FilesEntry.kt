package com.cloudyte.app.features.files

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.cloudyte.app.ui.screens.FilesScreen as LegacyFilesScreen

typealias FilesViewModel = com.cloudyte.app.ui.screens.FilesViewModel

@Composable
fun FilesScreen(navController: NavController, rootNavController: NavController, viewModel: FilesViewModel? = null) {
    if (viewModel == null) LegacyFilesScreen(navController, rootNavController)
    else LegacyFilesScreen(navController, rootNavController, viewModel)
}
