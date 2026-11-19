package com.cloudyte.app.features.files

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.cloudyte.app.ui.screens.FilesScreen as LegacyFilesScreen

typealias FilesViewModel = com.cloudyte.app.ui.screens.FilesViewModel

@Composable
fun FilesScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: FilesViewModel? = null,
    onHeaderTintChange: (Boolean) -> Unit = {}
) {
    if (viewModel == null) {
        LegacyFilesScreen(
            navController = navController,
            rootNavController = rootNavController,
            onHeaderTintChange = onHeaderTintChange
        )
    } else {
        LegacyFilesScreen(
            navController = navController,
            rootNavController = rootNavController,
            viewModel = viewModel,
            onHeaderTintChange = onHeaderTintChange
        )
    }
}
