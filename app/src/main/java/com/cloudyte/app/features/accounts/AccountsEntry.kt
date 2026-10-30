package com.cloudyte.app.features.accounts

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.cloudyte.app.ui.screens.AccountsScreen as LegacyAccountsScreen

typealias AccountsViewModel = com.cloudyte.app.ui.screens.AccountsViewModel

@Composable
fun AccountsScreen(navController: NavController, rootNavController: NavController, viewModel: AccountsViewModel? = null) {
    if (viewModel == null) LegacyAccountsScreen(navController, rootNavController)
    else LegacyAccountsScreen(navController, rootNavController, viewModel)
}
