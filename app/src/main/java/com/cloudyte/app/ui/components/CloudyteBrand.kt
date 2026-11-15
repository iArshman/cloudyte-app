package com.cloudyte.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.cloudyte.app.ui.theme.CategoryDocuments
import com.cloudyte.app.ui.theme.CategoryMedia
import com.cloudyte.app.ui.theme.MintSuccess
import com.cloudyte.app.ui.theme.RobotoFlexFontFamily
import com.cloudyte.app.ui.theme.cloudyteColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * 8-point rounded starburst brand shape drawn cleanly at any size.
 */
@Composable
fun ExpressiveStarburstBadge(
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    containerColor: Color = MaterialTheme.cloudyteColors.brandStarburst,
    spinSlowly: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val rotation = if (spinSlowly) {
        val transition = rememberInfiniteTransition(label = "starburst_spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 24000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "starburst_angle"
        )
        angle
    } else {
        0f
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val rectSize = w * 0.78f
            val corner = rectSize * (26f / 80f)
            val topLeft = Offset((w - rectSize) / 2f, (h - rectSize) / 2f)

            rotate(degrees = rotation, pivot = Offset(w / 2f, h / 2f)) {
                drawRoundRect(
                    color = containerColor,
                    topLeft = topLeft,
                    size = Size(rectSize, rectSize),
                    cornerRadius = CornerRadius(corner, corner)
                )
                rotate(degrees = 45f, pivot = Offset(w / 2f, h / 2f)) {
                    drawRoundRect(
                        color = containerColor,
                        topLeft = topLeft,
                        size = Size(rectSize, rectSize),
                        cornerRadius = CornerRadius(corner, corner)
                    )
                }
            }
        }
        content()
    }
}

/**
 * Cloudyte brand mark: 8-point starburst + white cloud + optional 3 multi-cloud dots.
 */
@Composable
fun CloudyteLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    showDots: Boolean = size > 32.dp,
    spinStarburst: Boolean = false,
    floatMotion: Boolean = false,
    starburstColor: Color = MaterialTheme.cloudyteColors.brandStarburst,
    cloudColor: Color = Color.White,
    contentDescription: String? = "Cloudyte Logo"
) {
    val transition = rememberInfiniteTransition(label = "cloudyte_logo")
    val spinAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 28000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "logo_spin"
    )
    val floatOffset by transition.animateFloat(
        initialValue = -0.025f,
        targetValue = 0.025f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_float"
    )

    val activeSpin = if (spinStarburst) spinAngle else 0f
    val activeFloat = if (floatMotion) floatOffset else 0f

    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    ) {
        val unit = this.size.minDimension / 120f
        val center = Offset(60f * unit, 60f * unit)
        val dy = activeFloat * this.size.minDimension

        // 8-point rounded starburst
        rotate(degrees = activeSpin, pivot = center) {
            val rectTopLeft = Offset(20f * unit, 20f * unit + dy)
            val rectSz = Size(80f * unit, 80f * unit)
            val corner = CornerRadius(26f * unit, 26f * unit)
            drawRoundRect(
                color = starburstColor,
                topLeft = rectTopLeft,
                size = rectSz,
                cornerRadius = corner
            )
            rotate(degrees = 45f, pivot = Offset(60f * unit, 60f * unit + dy)) {
                drawRoundRect(
                    color = starburstColor,
                    topLeft = rectTopLeft,
                    size = rectSz,
                    cornerRadius = corner
                )
            }
        }

        // White cloud
        drawCircle(
            color = cloudColor,
            radius = 12f * unit,
            center = Offset(46f * unit, 64f * unit + dy)
        )
        drawCircle(
            color = cloudColor,
            radius = 16f * unit,
            center = Offset(62f * unit, 54f * unit + dy)
        )
        drawCircle(
            color = cloudColor,
            radius = 12f * unit,
            center = Offset(78f * unit, 63f * unit + dy)
        )
        drawRoundRect(
            color = cloudColor,
            topLeft = Offset(38f * unit, 62f * unit + dy),
            size = Size(52f * unit, 14f * unit),
            cornerRadius = CornerRadius(7f * unit, 7f * unit)
        )

        // 3 storage dots (mint green, amber, coral red)
        if (showDots) {
            val dotRadius = 3.6f * unit
            drawCircle(
                color = MintSuccess,
                radius = dotRadius,
                center = Offset(50f * unit, 90f * unit + dy)
            )
            drawCircle(
                color = CategoryDocuments,
                radius = dotRadius,
                center = Offset(62f * unit, 90f * unit + dy)
            )
            drawCircle(
                color = CategoryMedia,
                radius = dotRadius,
                center = Offset(74f * unit, 90f * unit + dy)
            )
        }
    }
}

/**
 * Lowercase "cloudyte" wordmark with two-tone brand coloring.
 */
@Composable
fun CloudyteWordmark(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 26.sp,
    prefixColor: Color = MaterialTheme.colorScheme.onBackground,
    suffixColor: Color = MaterialTheme.cloudyteColors.lightAccent
) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = prefixColor)) { append("cloud") }
            withStyle(SpanStyle(color = suffixColor)) { append("yte") }
        },
        fontFamily = RobotoFlexFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = fontSize,
        lineHeight = fontSize * 1.05f,
        letterSpacing = (-0.05).em,
        modifier = modifier
    )
}

/**
 * Material 3 Expressive horizontal wavy progress bar matching the Cloudyte Home storage card.
 */
@Composable
fun WavyProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    indicatorColor: Color = MaterialTheme.cloudyteColors.mintSuccess,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    strokeWidth: Dp = 5.dp,
    waveAmplitude: Dp = 3.5.dp,
    waveLength: Dp = 20.dp
) {
    val clamped = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
    ) {
        val centerY = size.height / 2f
        val strokePx = strokeWidth.toPx()
        val ampPx = waveAmplitude.toPx()
        val waveLenPx = waveLength.toPx().coerceAtLeast(8f)
        val usableWidth = (size.width - strokePx).coerceAtLeast(0f)
        val startX = strokePx / 2f
        val fillEndX = startX + usableWidth * clamped

        // Track portion (from fillEndX or full width softly underneath)
        if (clamped < 1f) {
            drawLine(
                color = trackColor,
                start = Offset(if (clamped > 0.02f) fillEndX + strokePx * 0.5f else startX, centerY),
                end = Offset(size.width - strokePx / 2f, centerY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round
            )
        }

        if (clamped > 0f) {
            val activeWidth = (fillEndX - startX).coerceAtLeast(1f)
            val path = Path()
            val step = 2f
            var x = 0f
            path.moveTo(startX, centerY)
            while (x <= activeWidth) {
                val y = centerY + sin((x / waveLenPx) * 2.0 * PI).toFloat() * ampPx
                path.lineTo(startX + x, y)
                x += step
            }
            val endY = centerY + sin((activeWidth / waveLenPx) * 2.0 * PI).toFloat() * ampPx
            path.lineTo(startX + activeWidth, endY)

            drawPath(
                path = path,
                color = indicatorColor,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * Animated wavy horizontal loading bar for Splash & inline loading surfaces.
 */
@Composable
fun AnimatedWavyLoader(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.cloudyteColors.accentBlue,
    trackColor: Color = color.copy(alpha = 0.22f),
    strokeWidth: Dp = 4.5.dp
) {
    val transition = rememberInfiniteTransition(label = "wavy_loader")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    Canvas(
        modifier = modifier.height(16.dp)
    ) {
        val strokePx = strokeWidth.toPx()
        val ampPx = (size.height * 0.28f).coerceAtLeast(2.5f)
        val waveLenPx = (size.width / 3.2f).coerceAtLeast(14f)
        val centerY = size.height / 2f

        drawLine(
            color = trackColor,
            start = Offset(strokePx / 2f, centerY),
            end = Offset(size.width - strokePx / 2f, centerY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )

        val path = Path()
        val startX = strokePx / 2f
        val endX = size.width - strokePx / 2f
        var x = startX
        while (x <= endX) {
            val y = centerY + sin(((x - startX) / waveLenPx) * 2f * PI.toFloat() - phase) * ampPx
            if (x == startX) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2f
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

/**
 * Subtle decorative rotated squircle used in backgrounds of Splash, Login, and App Lock.
 */
@Composable
fun AmbientCornerSquircle(
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    cornerRadius: Dp = 64.dp,
    rotationDegrees: Float = 24f,
    color: Color = MaterialTheme.cloudyteColors.ambientShape
) {
    Box(
        modifier = modifier
            .size(size)
            .rotate(rotationDegrees)
            .clip(RoundedCornerShape(cornerRadius))
            .background(color)
    )
}
