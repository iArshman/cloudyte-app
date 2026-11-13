package com.cloudyte.app.features.home

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.cloudyte.app.ui.screens.HomeScreen as LegacyHomeScreen

typealias HomeViewModel = com.cloudyte.app.ui.screens.HomeViewModel

@Composable
fun HomeScreen(
    navController: NavController,
    rootNavController: NavController,
    viewModel: HomeViewModel? = null,
    onSearchClick: (() -> Unit)? = null
) {
    if (viewModel == null) LegacyHomeScreen(navController, rootNavController, onSearchClick = onSearchClick)
    else LegacyHomeScreen(navController, rootNavController, viewModel, onSearchClick = onSearchClick)
}
