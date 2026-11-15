package com.cloudyte.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudyte.app.R
import com.cloudyte.app.ui.components.AmbientCornerSquircle
import com.cloudyte.app.ui.components.CloudyteLogoMark
import com.cloudyte.app.ui.components.CloudyteWordmark
import com.cloudyte.app.ui.components.ExpressiveStarburstBadge

@Composable
fun CloudyteAppLockContent(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AmbientCornerSquircle(
            size = 220.dp,
            cornerRadius = 64.dp,
            rotationDegrees = 22f,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 84.dp, y = (-68).dp)
        )
        AmbientCornerSquircle(
            size = 200.dp,
            cornerRadius = 56.dp,
            rotationDegrees = -22f,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-78).dp, y = 72.dp)
        )

        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .align(Alignment.Center)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Brand Lockup
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                CloudyteLogoMark(size = 32.dp, showDots = false)
                CloudyteWordmark(fontSize = 20.sp)
            }

            // Center 176.dp starburst rosette + 76.dp lock tile + typography
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ExpressiveStarburstBadge(
                    size = 176.dp,
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(76.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(R.string.lock_screen_title),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.lock_screen_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            // Bottom full-width 64.dp pill Unlock CTA
            Button(
                onClick = onUnlock,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .testTag("lock_unlock_button")
            ) {
                Text(
                    text = stringResource(R.string.lock_screen_unlock_button),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun LockScreen(
    onUnlock: () -> Unit
) {
    CloudyteAppLockContent(onUnlock = onUnlock)
}
