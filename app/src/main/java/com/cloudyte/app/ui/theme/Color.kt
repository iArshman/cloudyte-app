package com.cloudyte.app.ui.theme

import androidx.compose.ui.graphics.Color

// Brand Constant (can be swapped)
val BrandPrimary = Color(0xFF4F5BF5)

// DARK Palette Tokens (Tailwind slate and blue)
val DarkBackground = Color(0xFF020617)
val DarkSurface = Color(0xFF020617)
val DarkOnBackground = Color(0xFFF1F5F9)
val DarkOnSurface = Color(0xFFF1F5F9)
val DarkSurfaceContainerLowest = Color(0xFF020617)
val DarkSurfaceContainerLow = Color(0xFF0B1120)
val DarkSurfaceContainer = Color(0xFF0F172A)
val DarkSurfaceContainerHigh = Color(0xFF1E293B)
val DarkSurfaceContainerHighest = Color(0xFF1E293B)
val DarkSurfaceVariant = Color(0xFF1E293B)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)
val DarkOutline = Color(0xFF475569)
val DarkOutlineVariant = Color(0xFF1E293B)
val DarkPrimary = Color(0xFF3B82F6) // blue-500
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF172554) // blue-950 tint
val DarkOnPrimaryContainer = Color(0xFFBFDBFE)
val DarkSecondary = Color(0xFF94A3B8)
val DarkSecondaryContainer = Color(0xFF1E293B)
val DarkOnSecondaryContainer = Color(0xFFF1F5F9)
val DarkError = Color(0xFFF87171)
val DarkErrorContainer = Color(0xFF450A0A)
val DarkOnError = Color(0xFFFFFFFF)
val DarkOnErrorContainer = Color(0xFFFECACA)
val DarkInverseSurface = Color(0xFFF1F5F9)
val DarkInverseOnSurface = Color(0xFF0F172A)

// LIGHT Palette Tokens (Header + rounded sheet style)
val LightBackground = Color(0xFFF3F4FB)
val LightSurface = Color(0xFFF3F4FB)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF8F9FE)
val LightSurfaceContainer = Color(0xFFFFFFFF) // sheet & cards #FFFFFF
val LightSurfaceContainerHigh = Color(0xFFEDEFFF)
val LightSurfaceContainerHighest = Color(0xFFE6E8F4)
val LightSurfaceVariant = Color(0xFFE8EAFE)
val LightOnSurface = Color(0xFF14186B) // deep navy
val LightOnSurfaceVariant = Color(0xFF8B90B5) // secondary text
val LightOutline = Color(0xFF8B90B5)
val LightOutlineVariant = Color(0xFFE6E8F4)
val LightPrimary = BrandPrimary // #4F5BF5
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE8EAFE)
val LightOnPrimaryContainer = Color(0xFF2B33B5)
val LightSecondary = Color(0xFF8B90B5)
val LightSecondaryContainer = Color(0xFFE8EAFE)
val LightOnSecondaryContainer = Color(0xFF14186B)
val LightError = Color(0xFFE5484D)
val LightErrorContainer = Color(0xFFFFEBEE)
val LightOnError = Color(0xFFFFFFFF)
val LightOnErrorContainer = Color(0xFFB71C1C)

// File-type colors
val FileVideo = Color(0xFFF0584F) // video/media red
val FilePdf = Color(0xFFF0584F) // pdf red
val FileDoc = Color(0xFFF5B63F) // documents amber
val FileZip = Color(0xFFA45BD9) // zip purple
val FileCode = Color(0xFF5BC98A) // other / code green
val FileAudio = Color(0xFF5BC98A) // audio green
val FileImage = Color(0xFF4F5BF5) // images blue
val FileUnknown = Color(0xFFBFC3DC) // unknown gray

// Cloud Storage Provider Colors
val ProviderGoogle = Color(0xFF4285F4)
val ProviderOneDrive = Color(0xFF0078D4)
val ProviderTelegram = Color(0xFF2AABEE)

// Status Colors
val StatusSuccess = Color(0xFF5BC98A)
val StatusWarning = Color(0xFFF5B63F)
val StatusError = Color(0xFFE5484D)

// Backwards-compatibility aliases
val AccentBlue = DarkPrimary
val AccentSky = ProviderTelegram
val BackgroundDark = DarkBackground
val SurfaceDark = DarkSurface
val SurfaceVariantDark = DarkSurfaceVariant
val OutlineDark = DarkOutline
val TextPrimary = DarkOnSurface
val TextSecondary = DarkOnSurfaceVariant
val BackgroundLight = LightBackground
val SurfaceLight = LightSurface
val SurfaceVariantLight = LightSurfaceVariant
val OutlineLight = LightOutline
val TextPrimaryLight = LightOnSurface
val TextSecondaryLight = LightOnSurfaceVariant
