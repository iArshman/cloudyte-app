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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.cloudyte.app.R
import com.cloudyte.app.data.AccountSessionHelper
import com.cloudyte.app.data.UserPreferences
import com.cloudyte.app.data.UserPreferencesState
import com.cloudyte.app.ui.components.GroupedList
import com.cloudyte.app.ui.components.GroupedListItem
import com.cloudyte.app.ui.components.LocalBottomBarInset
import com.cloudyte.app.ui.components.SectionRow
import com.cloudyte.app.ui.theme.cloudyteColors
import kotlinx.coroutines.launch

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

@Composable
fun SettingsScreen(
    bottomNavController: NavController? = null,
    rootNavController: NavController? = null,
    onNavigateSection: (SettingsSectionType) -> Unit = {},
    onNavigateExclusions: () -> Unit = {},
    onNavigateTransfers: () -> Unit = {},
    onLogout: (() -> Unit)? = null,
    onSubScreenChanged: (Boolean) -> Unit = {}
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
    val isInSubSection = selectedSection != null || currentSubScreen != null

    LaunchedEffect(isInSubSection) {
        onSubScreenChanged(isInSubSection)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSubScreenChanged(false)
        }
    }

    BackHandler(enabled = isInSubSection) {
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
            onNavigateAccounts = { currentSubScreen = "ACCOUNTS" },
            onNavigateBackup = { selectedSection = SettingsSectionType.BACKUP },
            onLogout = effectiveLogout
        )
        return
    }

    val scrollState = rememberScrollState()
    val ext = MaterialTheme.cloudyteColors
    val prefsState by UserPreferences.observe(context).collectAsState(initial = UserPreferencesState())

    val groups = remember {
        listOf(
            listOf(
                SettingsSectionType.ACCOUNT,
                SettingsSectionType.BACKUP,
                SettingsSectionType.STORAGE
            ),
            listOf(
                SettingsSectionType.PERMISSIONS,
                SettingsSectionType.SECURITY
            ),
            listOf(
                SettingsSectionType.APPEARANCE,
                SettingsSectionType.PLAYBACK,
                SettingsSectionType.NOTIFICATIONS
            ),
            listOf(
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
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                if (prefsState.debugLogging) {
                    IconButton(
                        onClick = { currentSubScreen = "DIAGNOSTICS" },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = "Open diagnostics",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            groups.forEachIndexed { groupIndex, sections ->
                Spacer(modifier = Modifier.height(if (groupIndex == 0) 8.dp else 16.dp))
                val items = sections.mapIndexed { index, section ->
                    GroupedListItem(key = section.name) {
                        SectionRow(
                            section = section,
                            index = index,
                            onClick = {
                                selectedSection = section
                                onNavigateSection(section)
                            }
                        )
                    }
                }
                GroupedList(items = items)
            }

            Spacer(modifier = Modifier.height(28.dp + LocalBottomBarInset.current))
        }
    }
}
