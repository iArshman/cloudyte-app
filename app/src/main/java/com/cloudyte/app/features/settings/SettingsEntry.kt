package com.cloudyte.app.features.settings

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.cloudyte.app.ui.screens.SettingsScreen as LegacySettingsScreen

typealias SettingsViewModel = com.cloudyte.app.ui.screens.SettingsViewModel

typealias SettingsSectionType = com.cloudyte.app.ui.screens.SettingsSectionType

@Composable
fun SettingsScreen(navController: NavController, rootNavController: NavController) {
    LegacySettingsScreen(navController, rootNavController)
}
