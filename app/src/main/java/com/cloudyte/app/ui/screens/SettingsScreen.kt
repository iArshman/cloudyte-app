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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.cloudyte.app.data.AccountSessionHelper
import com.cloudyte.app.ui.components.GroupedList
import com.cloudyte.app.ui.components.GroupedListItem
import com.cloudyte.app.ui.components.LocalBottomBarInset
import com.cloudyte.app.ui.components.SectionRow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    bottomNavController: NavController? = null,
    rootNavController: NavController? = null,
    onNavigateSection: (SettingsSectionType) -> Unit = {},
    onNavigateExclusions: () -> Unit = {},
    onNavigateTransfers: () -> Unit = {},
    onLogout: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val effectiveLogout: () -> Unit = onLogout ?: {
        scope.launch {
            val result = AccountSessionHelper.performLogout(context)
            result.warningMessage?.let { msg ->
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
            rootNavController?.navigate("login") {
                popUpTo("main") { inclusive = true }
            }
        }
    }

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
            rootNavController = rootNavController ?: remember { androidx.navigation.NavHostController(context) },
            onBack = { currentSubScreen = null }
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
            onNavigateBackup = { selectedSection = SettingsSectionType.BACKUP },
            onLogout = effectiveLogout
        )
        return
    }

    val scrollState = rememberScrollState()

    val sectionGroups = remember {
        listOf(
            listOf(
                SettingsSectionType.ACCOUNT,
                SettingsSectionType.STORAGE
            ),
            listOf(
                SettingsSectionType.APPEARANCE,
                SettingsSectionType.PLAYBACK,
                SettingsSectionType.BACKUP,
                SettingsSectionType.NOTIFICATIONS
            ),
            listOf(
                SettingsSectionType.SECURITY,
                SettingsSectionType.PERMISSIONS,
                SettingsSectionType.ADVANCED,
                SettingsSectionType.ABOUT
            )
        )
    }

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
            Spacer(modifier = Modifier.statusBarsPadding())

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 36.sp,
                        lineHeight = 44.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp),
                    onClick = { currentSubScreen = "DIAGNOSTICS" }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.BugReport,
                            contentDescription = "Open diagnostics",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            sectionGroups.forEachIndexed { groupIndex, group ->
                val groupItems = group.map { section ->
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
                GroupedList(items = groupItems)
                if (groupIndex < sectionGroups.lastIndex) {
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp + LocalBottomBarInset.current))
        }
    }
}
