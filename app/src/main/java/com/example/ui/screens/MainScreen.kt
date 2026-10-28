package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.FloatingNavigationBar
import com.example.ui.components.LocalBottomBarInset
import com.example.ui.navigation.NavigationTransitions

fun NavController.navigateSingleTopRestore(route: String) {
    val startDestination = graph.findStartDestination().route ?: "home"
    navigate(route) {
        popUpTo(startDestination) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private val tabOrder = listOf("home", "files", "transfers", "accounts", "settings")

@Composable
fun MainScreen(rootNavController: NavController) {
    val bottomNavController = rememberNavController()
    val filesViewModel: FilesViewModel = viewModel()

    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"

    CompositionLocalProvider(LocalBottomBarInset provides 80.dp) {
        Scaffold(
            bottomBar = {
                FloatingNavigationBar(
                    navController = bottomNavController,
                    onNavigate = { route ->
                        bottomNavController.navigateSingleTopRestore(route)
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                NavHost(
                    navController = bottomNavController,
                    startDestination = "home",
                    enterTransition = {
                        val initialRoute = initialState.destination.route ?: "home"
                        val targetRoute = targetState.destination.route ?: "home"
                        val fromIndex = tabOrder.indexOf(initialRoute).coerceAtLeast(0)
                        val toIndex = tabOrder.indexOf(targetRoute).coerceAtLeast(0)
                        NavigationTransitions.tabEnter(toIndex >= fromIndex)
                    },
                    exitTransition = {
                        val initialRoute = initialState.destination.route ?: "home"
                        val targetRoute = targetState.destination.route ?: "home"
                        val fromIndex = tabOrder.indexOf(initialRoute).coerceAtLeast(0)
                        val toIndex = tabOrder.indexOf(targetRoute).coerceAtLeast(0)
                        NavigationTransitions.tabExit(toIndex >= fromIndex)
                    }
                ) {
                    composable("home") { HomeScreen(bottomNavController, rootNavController) }
                    composable("files") { FilesScreen(bottomNavController, rootNavController, filesViewModel) }
                    composable("transfers") { TransfersScreen(onBack = { bottomNavController.navigateSingleTopRestore("home") }) }
                    composable("accounts") { AccountsScreen(bottomNavController, rootNavController) }
                    composable("settings") { SettingsScreen(bottomNavController, rootNavController) }
                }
            }
        }
    }
}
