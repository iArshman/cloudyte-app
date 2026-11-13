package com.cloudyte.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cloudyte.app.R
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.data.UserPreferencesState
import com.cloudyte.app.features.accounts.AccountsScreen as FeatureAccountsScreen
import com.cloudyte.app.features.files.FilesScreen as FeatureFilesScreen
import com.cloudyte.app.features.home.HomeScreen as FeatureHomeScreen
import com.cloudyte.app.features.settings.SettingsScreen as FeatureSettingsScreen
import com.cloudyte.app.features.transfers.TransfersScreen as FeatureTransfersScreen
import com.cloudyte.app.ui.components.FloatingNavItem
import com.cloudyte.app.ui.components.FloatingNavigationBar
import com.cloudyte.app.ui.components.LocalBottomBarInset
import com.cloudyte.app.ui.navigation.NavigationTransitions

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
    val context = LocalContext.current
    val bottomNavController = rememberNavController()
    val filesViewModel: FilesViewModel = viewModel()
    val prefsState by UserPreferences.observe(context).collectAsState(initial = UserPreferencesState())

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        val useNavigationRail = maxWidth >= 600.dp
        val bottomInset = if (useNavigationRail) 0.dp else 88.dp

        CompositionLocalProvider(LocalBottomBarInset provides bottomInset) {
            if (useNavigationRail) {
                val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "home"
                val railItems = listOf(
                    FloatingNavItem("home", stringResource(R.string.nav_home), Icons.Default.Home),
                    FloatingNavItem("files", stringResource(R.string.nav_files), Icons.Default.Folder),
                    FloatingNavItem("transfers", stringResource(R.string.nav_transfers), Icons.Default.SwapVert),
                    FloatingNavItem("settings", stringResource(R.string.nav_settings), Icons.Default.Settings)
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        railItems.forEach { item ->
                            NavigationRailItem(
                                selected = currentRoute == item.route,
                                onClick = { bottomNavController.navigateSingleTopRestore(item.route) },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = if (prefsState.showNavText) {
                                    { Text(item.label, style = MaterialTheme.typography.labelMedium) }
                                } else null,
                                modifier = Modifier.testTag("nav_item_${item.route}")
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        MainNavHost(
                            bottomNavController = bottomNavController,
                            rootNavController = rootNavController,
                            filesViewModel = filesViewModel,
                            modifier = Modifier
                                .fillMaxHeight()
                                .widthIn(max = 720.dp)
                                .fillMaxWidth()
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    MainNavHost(
                        bottomNavController = bottomNavController,
                        rootNavController = rootNavController,
                        filesViewModel = filesViewModel,
                        modifier = Modifier.fillMaxSize()
                    )

                    FloatingNavigationBar(
                        navController = bottomNavController,
                        showNavText = prefsState.showNavText,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        onNavigate = { route ->
                            bottomNavController.navigateSingleTopRestore(route)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainNavHost(
    bottomNavController: androidx.navigation.NavHostController,
    rootNavController: NavController,
    filesViewModel: FilesViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = bottomNavController,
        startDestination = "home",
        modifier = modifier,
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
        composable("home") { FeatureHomeScreen(bottomNavController, rootNavController) }
        composable("files") { FeatureFilesScreen(bottomNavController, rootNavController, filesViewModel) }
        composable("transfers") { FeatureTransfersScreen(onBack = { bottomNavController.navigateSingleTopRestore("home") }) }
        composable("accounts") {
            FeatureAccountsScreen(
                navController = bottomNavController,
                rootNavController = rootNavController,
                onBack = { bottomNavController.navigateSingleTopRestore("home") }
            )
        }
        composable("settings") { FeatureSettingsScreen(bottomNavController, rootNavController) }
    }
}
