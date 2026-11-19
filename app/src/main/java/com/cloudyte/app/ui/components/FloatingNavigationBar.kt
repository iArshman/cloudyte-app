package com.cloudyte.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.cloudyte.app.R
import com.cloudyte.app.ui.theme.MetricLabelTextStyle
import com.cloudyte.app.ui.theme.cloudyteColors
import kotlin.math.roundToInt

val LocalBottomBarInset = staticCompositionLocalOf { 0.dp }

enum class ActiveTransferDirection {
    UPLOAD,
    DOWNLOAD,
    BOTH
}

data class ActiveTransferSummary(
    val direction: ActiveTransferDirection,
    val progress: Float,
    val activeCount: Int
)

data class FloatingNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)

@Composable
fun FloatingNavigationBar(
    navController: NavController,
    visible: Boolean = true,
    showNavText: Boolean = true,
    activeTransferSummary: ActiveTransferSummary? = null,
    modifier: Modifier = Modifier,
    onNavigate: ((String) -> Unit)? = null
) {
    val items = listOf(
        FloatingNavItem("home", stringResource(R.string.nav_home), Icons.Outlined.Home, Icons.Filled.Home),
        FloatingNavItem("files", stringResource(R.string.nav_files), Icons.Outlined.Folder, Icons.Filled.Folder),
        FloatingNavItem("transfers", stringResource(R.string.nav_transfers), Icons.Outlined.SwapVert, Icons.Filled.SwapVert),
        FloatingNavItem("settings", stringResource(R.string.nav_settings), Icons.Outlined.Settings, Icons.Filled.Settings)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"
    val selectedIndex = items.indexOfFirst { it.route == currentRoute }
    val ext = MaterialTheme.cloudyteColors

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("floating_bottom_bar"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selectedIndex
                    val isTransfersTab = item.route == "transfers"
                    val hasActiveTransfer = isTransfersTab && activeTransferSummary != null

                    val targetWeight = when {
                        isSelected && showNavText -> 2.25f
                        hasActiveTransfer -> 1.45f
                        else -> 1f
                    }
                    val itemWeight by animateFloatAsState(
                        targetValue = targetWeight,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "navItemWeight_${item.route}"
                    )

                    val transferTint = when (activeTransferSummary?.direction) {
                        ActiveTransferDirection.DOWNLOAD -> ext.mintSuccess
                        ActiveTransferDirection.UPLOAD,
                        ActiveTransferDirection.BOTH,
                        null -> MaterialTheme.colorScheme.primary
                    }

                    val pillColor by animateColorAsState(
                        targetValue = when {
                            isSelected -> MaterialTheme.colorScheme.primary
                            hasActiveTransfer -> transferTint.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        },
                        label = "navPillColor_${item.route}"
                    )

                    val contentColor = when {
                        isSelected -> MaterialTheme.colorScheme.onPrimary
                        hasActiveTransfer -> if (activeTransferSummary?.direction == ActiveTransferDirection.DOWNLOAD) {
                            ext.mintSuccess
                        } else {
                            ext.lightAccent
                        }
                        else -> ext.navInactive
                    }

                    val resolvedIcon = if (hasActiveTransfer) {
                        when (activeTransferSummary?.direction) {
                            ActiveTransferDirection.UPLOAD -> Icons.Filled.ArrowUpward
                            ActiveTransferDirection.DOWNLOAD -> Icons.Filled.ArrowDownward
                            ActiveTransferDirection.BOTH, null -> Icons.Filled.SwapVert
                        }
                    } else if (isSelected) {
                        item.selectedIcon
                    } else {
                        item.icon
                    }

                    val animatedProgress by animateFloatAsState(
                        targetValue = (activeTransferSummary?.progress ?: 0f).coerceIn(0.04f, 1f),
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow
                        ),
                        label = "transferNavRingProgress"
                    )
                    val percentValue = ((activeTransferSummary?.progress ?: 0f) * 100f).roundToInt().coerceIn(0, 100)
                    val directionSymbol = when (activeTransferSummary?.direction) {
                        ActiveTransferDirection.UPLOAD -> "↑"
                        ActiveTransferDirection.DOWNLOAD -> "↓"
                        ActiveTransferDirection.BOTH -> "⇅"
                        null -> ""
                    }

                    Row(
                        modifier = Modifier
                            .weight(itemWeight)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(pillColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                role = Role.Tab
                            ) {
                                if (!isSelected) {
                                    onNavigate?.invoke(item.route)
                                }
                            }
                            .padding(horizontal = if (isSelected || hasActiveTransfer) 10.dp else 4.dp)
                            .semantics {
                                contentDescription = if (hasActiveTransfer) {
                                    "${item.label} $directionSymbol $percentValue%"
                                } else {
                                    item.label
                                }
                            }
                            .testTag("nav_item_${item.route}"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasActiveTransfer) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(30.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier.size(30.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else contentColor,
                                    trackColor = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f)
                                    } else {
                                        contentColor.copy(alpha = 0.22f)
                                    },
                                    strokeWidth = 2.4.dp,
                                    strokeCap = StrokeCap.Round
                                )
                                Icon(
                                    imageVector = resolvedIcon,
                                    contentDescription = null,
                                    tint = contentColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = resolvedIcon,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        AnimatedVisibility(
                            visible = (isSelected && showNavText) || hasActiveTransfer,
                            enter = fadeIn() + expandHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ),
                            exit = fadeOut() + shrinkHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(modifier = Modifier.width(6.dp))
                                if (hasActiveTransfer) {
                                    Text(
                                        text = if (isSelected && showNavText) {
                                            "$directionSymbol $percentValue%"
                                        } else {
                                            "$percentValue%"
                                        },
                                        color = contentColor,
                                        style = MetricLabelTextStyle,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip
                                    )
                                } else {
                                    Text(
                                        text = item.label,
                                        color = contentColor,
                                        fontSize = 13.sp,
                                        lineHeight = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

