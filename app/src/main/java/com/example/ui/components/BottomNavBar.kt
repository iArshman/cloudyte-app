package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.AccentSky
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun BottomNavBar(navController: NavController) {
    val items = listOf(
        NavItem("home", "Home", Icons.Default.Home),
        NavItem("files", "Files", Icons.Default.Folder),
        NavItem("settings", "Settings", Icons.Default.Settings)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        items.forEach { item ->
            val isSelected = currentRoute == item.route
            NavigationBarItem(
                icon = {
                    if (isSelected) {
                        // Use gradient brush for icon tint if we had custom Modifier.drawWithCache
                        // For simplicity in standard item, we can use the Purple color
                        Icon(imageVector = item.icon, contentDescription = item.label, tint = AccentBlue)
                    } else {
                        Icon(imageVector = item.icon, contentDescription = item.label, tint = TextSecondary)
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        color = if (isSelected) AccentBlue else TextSecondary
                    )
                },
                selected = isSelected,
                onClick = {
                    if (!isSelected) {
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AccentBlue,
                    unselectedIconColor = TextSecondary,
                    selectedTextColor = AccentBlue,
                    unselectedTextColor = TextSecondary,
                    indicatorColor = Color.Transparent
                )
            )
        }
    }
}

data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
