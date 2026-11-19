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
    primaryContainer = Color(0xFFE5E7EB), onPrimaryContainer = Color(0xFF111827),
    secondary = Color(0xFF111827), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E7EB), onSecondaryContainer = Color(0xFF111827),
    tertiary = Color(0xFF4B5563), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDADDE3), onTertiaryContainer = Color(0xFF111827),
    error = Color(0xFFDC2626), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFE8ECF1), onBackground = Color(0xFF0F172A),
    surface = Color(0xFFE8ECF1), onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFD8DEE7), onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF8894A6), outlineVariant = Color(0xFFBCC5D3),
    inverseSurface = Color(0xFF111111), inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8DEE7), surfaceBright = Color(0xFFF8FAFC),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFF8FAFC), surfaceContainerHigh = Color(0xFFD8DEE7),
    surfaceContainerHighest = Color(0xFFCCD4E0)
)

val NoirDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF0A0A0A),
    primaryContainer = Color(0xFF262626), onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFE5E7EB), onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF262626), onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFFA3A3A3), onTertiary = Color(0xFF171717),
    tertiaryContainer = Color(0xFF242424), onTertiaryContainer = Color(0xFFF5F5F5),
    error = Color(0xFFEF4444), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF450A0A), onErrorContainer = Color(0xFFFECACA),
    background = Color(0xFF0A0A0A), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF111111), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1A1A1A), onSurfaceVariant = Color(0xFFA3A3A3),
    outline = Color(0xFF404040), outlineVariant = Color(0xFF262626),
    inverseSurface = Color(0xFFFFFFFF), inverseOnSurface = Color(0xFF0A0A0A),
    inversePrimary = Color(0xFF000000),
    surfaceDim = Color(0xFF0A0A0A), surfaceBright = Color(0xFF262626),
    surfaceContainerLowest = Color(0xFF0A0A0A), surfaceContainerLow = Color(0xFF121212),
    surfaceContainer = Color(0xFF181818), surfaceContainerHigh = Color(0xFF222222),
    surfaceContainerHighest = Color(0xFF2C2C2C)
)
