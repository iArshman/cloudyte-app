package com.cloudyte.app.features.home

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.cloudyte.app.ui.screens.HomeScreen as LegacyHomeScreen

typealias HomeViewModel = com.cloudyte.app.ui.screens.HomeViewModel

@Composable
fun HomeScreen(navController: NavController, rootNavController: NavController, viewModel: HomeViewModel? = null) {
    if (viewModel == null) LegacyHomeScreen(navController, rootNavController)
    else LegacyHomeScreen(navController, rootNavController, viewModel)
}
