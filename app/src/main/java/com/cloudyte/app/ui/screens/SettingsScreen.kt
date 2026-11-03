package com.cloudyte.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.ui.components.GroupedList
import com.cloudyte.app.ui.components.GroupedListItem
import com.cloudyte.app.ui.components.LocalBottomBarInset
import com.cloudyte.app.ui.components.SectionRow

@Composable
fun SettingsScreen(
    bottomNavController: NavController? = null,
    rootNavController: NavController? = null,
    onNavigateSection: (SettingsSectionType) -> Unit = {},
    onNavigateExclusions: () -> Unit = {},
    onNavigateTransfers: () -> Unit = {},
    onLogout: () -> Unit = {
        ApiClient.saveToken(null)
        rootNavController?.navigate("login") {
            popUpTo("main") { inclusive = true }
        }
    }
) {
    val context = LocalContext.current

    var selectedSection by remember { mutableStateOf<SettingsSectionType?>(null) }
    var currentSubScreen by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = currentSubScreen != null || selectedSection != null) {
        when {
            currentSubScreen != null -> currentSubScreen = null
            else -> selectedSection = null
        }
    }

    if (currentSubScreen == "EXCLUSIONS") {
        ExclusionsScreen(onBack = { currentSubScreen = null })
        return
    }

    if (currentSubScreen == "TRANSFERS") {
        TransfersScreen(onBack = { currentSubScreen = null })
        return
    }

    if (currentSubScreen == "ACCOUNTS") {
        AccountsScreen(
            navController = bottomNavController ?: remember { androidx.navigation.NavHostController(context) },
            rootNavController = rootNavController ?: remember { androidx.navigation.NavHostController(context) }
        )
        return
    }

    if (currentSubScreen == "DIAGNOSTICS") {
        DiagnosticsScreen(onBack = { currentSubScreen = null })
        return
    }

    selectedSection?.let { section ->
        SettingsSectionScreen(
            section = section,
            onBack = { selectedSection = null },
            onNavigateExclusions = { currentSubScreen = "EXCLUSIONS" },
            onNavigateAccounts = {
                // Accounts are managed from Settings, not the primary navigation bar.
                currentSubScreen = "ACCOUNTS"
            },
            onLogout = onLogout
        )
        return
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            // Spacer for status bar
            Spacer(modifier = Modifier.statusBarsPadding())

            // Leafit-style header: big title + subtitle left, icon buttons right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Settings",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 36.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Appearance, language and more",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(42.dp),
                    onClick = { currentSubScreen = "DIAGNOSTICS" }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Open diagnostics",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val sectionItems = SettingsSectionType.entries.map { section ->
                GroupedListItem(key = section.name) {
                    SectionRow(
                        section = section,
                        onClick = {
                            selectedSection = section
                            onNavigateSection(section)
                        }
                    )
                }
            }

            GroupedList(items = sectionItems)
            Spacer(modifier = Modifier.height(24.dp + LocalBottomBarInset.current))
        }
    }
}
