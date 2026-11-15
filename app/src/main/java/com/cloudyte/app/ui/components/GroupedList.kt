package com.cloudyte.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class GroupedListItem(
    val key: String,
    val visible: Boolean = true,
    val content: @Composable () -> Unit
)

@Composable
fun SettingsSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, bottom = 6.dp, end = 20.dp)
            .semantics { heading() }
    )
}

@Composable
fun GroupedList(
    items: List<GroupedListItem>,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    outerCornerRadius: Dp = 20.dp,
    innerCornerRadius: Dp = 4.dp,
    itemSpacing: Dp = 2.dp,
    horizontalPadding: Dp = 16.dp
) {
    val visibleItems = items.filter { it.visible }
    val totalVisible = visibleItems.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
    ) {
        items.forEachIndexed { _, item ->
            val isVisible = item.visible
            val visibleIndex = visibleItems.indexOf(item)

            val isFirst = isVisible && visibleIndex == 0
            val isLast = isVisible && visibleIndex == totalVisible - 1

            val topRadius by animateDpAsState(
                targetValue = if (isFirst) outerCornerRadius else innerCornerRadius,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "topCornerRadius"
            )

            val bottomRadius by animateDpAsState(
                targetValue = if (isLast) outerCornerRadius else innerCornerRadius,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "bottomCornerRadius"
            )

            AnimatedVisibility(
                visible = isVisible,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
            ) {
                Column {
                    Surface(
                        color = containerColor,
                        shape = RoundedCornerShape(
                            topStart = topRadius,
                            topEnd = topRadius,
                            bottomStart = bottomRadius,
                            bottomEnd = bottomRadius
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item.content()
                    }
                    if (visibleIndex in 0 until totalVisible - 1) {
                        Spacer(modifier = Modifier.height(itemSpacing))
                    }
                }
            }
        }
    }
}
