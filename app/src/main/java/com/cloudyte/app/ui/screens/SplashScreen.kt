package com.cloudyte.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.cloudyte.app.R
import com.cloudyte.app.api.ApiClient
import com.cloudyte.app.ui.components.AmbientCornerSquircle
import com.cloudyte.app.ui.components.AnimatedWavyLoader
import com.cloudyte.app.ui.components.CloudyteLogoMark
import com.cloudyte.app.ui.components.CloudyteWordmark
import com.cloudyte.app.ui.theme.cloudyteColors

@Composable
fun SplashScreen(navController: NavController) {
    LaunchedEffect(Unit) {
        val token = ApiClient.getToken()
        if (!token.isNullOrEmpty()) {
            try {
                ApiClient.apiService.getProfile()
                navController.navigate("main") {
                    popUpTo("splash") { inclusive = true }
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401 || e.code() == 403) {
                    ApiClient.saveToken(null)
                    navController.navigate("login") {
                        popUpTo("splash") { inclusive = true }
                    }
                } else {
                    navController.navigate("main") {
                        popUpTo("splash") { inclusive = true }
                    }
                }
            } catch (e: Exception) {
                navController.navigate("main") {
                    popUpTo("splash") { inclusive = true }
                }
            }
        } else {
            navController.navigate("login") {
                popUpTo("splash") { inclusive = true }
            }
        }
    }

    val ext = MaterialTheme.cloudyteColors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Expressive decorative background squircles & dots
        AmbientCornerSquircle(
            size = 240.dp,
            cornerRadius = 72.dp,
            rotationDegrees = 26f,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 84.dp, y = (-72).dp)
        )
        AmbientCornerSquircle(
            size = 220.dp,
            cornerRadius = 64.dp,
            rotationDegrees = -18f,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-78).dp, y = 78.dp)
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 44.dp, y = 132.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(ext.mintSuccess.copy(alpha = 0.7f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-38).dp, y = (-84).dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(ext.categoryDocs.copy(alpha = 0.7f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-54).dp, y = (-168).dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(ext.categoryMedia.copy(alpha = 0.7f))
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CloudyteLogoMark(
                size = 188.dp,
                showDots = true,
                spinStarburst = true,
                floatMotion = true,
                contentDescription = stringResource(R.string.app_name)
            )
            Spacer(modifier = Modifier.height(28.dp))
            CloudyteWordmark(fontSize = 52.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        AnimatedWavyLoader(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 52.dp)
                .width(96.dp),
            color = ext.accentBlue
        )
    }
}
