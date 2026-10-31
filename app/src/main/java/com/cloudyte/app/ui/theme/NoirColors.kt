package com.cloudyte.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * "Noir": monochrome black & white Material 3 scheme (Tailwind gray/neutral values).
 * White primary in dark mode, black primary in light mode; no brand colour.
 */
val NoirLightColorScheme = lightColorScheme(
    primary = Color(0xFF000000), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF000000), onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF111827), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3F4F6), onSecondaryContainer = Color(0xFF111827),
    tertiary = Color(0xFF4B5563), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5E7EB), onTertiaryContainer = Color(0xFF111827),
    error = Color(0xFFDC2626), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF8FAFC), onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6), onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFF9CA3AF), outlineVariant = Color(0xFFE5E7EB),
    inverseSurface = Color(0xFF111111), inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE5E7EB), surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF9FAFB),
    surfaceContainer = Color(0xFFF3F4F6), surfaceContainerHigh = Color(0xFFE5E7EB),
    surfaceContainerHighest = Color(0xFFE5E7EB)
)

val NoirDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFFFFFFFF), onPrimaryContainer = Color(0xFF0A0A0A),
    secondary = Color(0xFFE5E7EB), onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF262626), onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFA3A3A3), onTertiary = Color(0xFF171717),
    tertiaryContainer = Color(0xFF1F1F1F), onTertiaryContainer = Color(0xFFF5F5F5),
    error = Color(0xFFEF4444), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF450A0A), onErrorContainer = Color(0xFFFECACA),
    background = Color(0xFF0A0A0A), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF111111), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF171717), onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF404040), outlineVariant = Color(0xFF262626),
    inverseSurface = Color(0xFFFFFFFF), inverseOnSurface = Color(0xFF0A0A0A),
    inversePrimary = Color(0xFF000000),
    surfaceDim = Color(0xFF0A0A0A), surfaceBright = Color(0xFF262626),
    surfaceContainerLowest = Color(0xFF0A0A0A), surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF171717), surfaceContainerHigh = Color(0xFF1F1F1F),
    surfaceContainerHighest = Color(0xFF262626)
)
