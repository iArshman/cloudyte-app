package com.cloudyte.app.ui.theme

import androidx.compose.ui.graphics.Color

// Brand & Expressive Palette Constants
val BrandPrimary = Color(0xFF2F6FEB)
val BrandPrimaryAccent = Color(0xFF4C8DFF)
val BrandPrimaryLight = Color(0xFF6FA2FF)
val AmbientBlueDark = Color(0xFF0B1230)

// DARK Palette Tokens (Deep Midnight Navy Expressive)
val DarkBackground = Color(0xFF060918)
val DarkSurface = Color(0xFF060918)
val DarkOnBackground = Color(0xFFF4F6FC)
val DarkOnSurface = Color(0xFFF4F6FC)
val DarkSurfaceContainerLowest = Color(0xFF060918)
val DarkSurfaceContainerLow = Color(0xFF0D1528)
val DarkSurfaceContainer = Color(0xFF121B2E)
val DarkSurfaceContainerHigh = Color(0xFF1B2538)
val DarkSurfaceContainerHighest = Color(0xFF222E46)
val DarkSurfaceVariant = Color(0xFF222E46)
val DarkOnSurfaceVariant = Color(0xFF9AA4BC)
val DarkOutline = Color(0xFF3A4560)
val DarkOutlineVariant = Color(0xFF1E2940)
val DarkPrimary = BrandPrimary
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkPrimaryContainer = Color(0xFF18275A)
val DarkOnPrimaryContainer = BrandPrimaryLight
val DarkSecondary = Color(0xFF6FA2FF)
val DarkSecondaryContainer = Color(0xFF1B2A5E)
val DarkOnSecondaryContainer = Color(0xFFFFFFFF)
val DarkError = Color(0xFFEF4B4B)
val DarkErrorContainer = Color(0xFF3A1B26)
val DarkErrorDeepBox = Color(0xFF2A1520)
val DarkOnError = Color(0xFFFFFFFF)
val DarkOnErrorContainer = Color(0xFFFF9A9A)
val DarkInverseSurface = Color(0xFFF4F6FC)
val DarkInverseOnSurface = Color(0xFF060918)

// LIGHT Palette Tokens (Cool Slate Canvas — Option 1)
val LightBackground = Color(0xFFE7ECF4)
val LightSurface = Color(0xFFE7ECF4)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF1F5FA)
val LightSurfaceContainer = Color(0xFFF8FAFD)
val LightSurfaceContainerHigh = Color(0xFFD6E0F0)
val LightSurfaceContainerHighest = Color(0xFFC8D5E8)
val LightSurfaceVariant = Color(0xFFD6E0F0)
val LightOnSurface = Color(0xFF0F172A)
val LightOnSurfaceVariant = Color(0xFF475569)
val LightOutline = Color(0xFF7B8CA6)
val LightOutlineVariant = Color(0xFFB5C4D8)
val LightPrimary = BrandPrimary
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD3E2FF)
val LightOnPrimaryContainer = Color(0xFF0F2D7A)
val LightSecondary = Color(0xFF3B7DED)
val LightSecondaryContainer = Color(0xFFD8E5FF)
val LightOnSecondaryContainer = Color(0xFF0F265C)
val LightError = Color(0xFFDC2626)
val LightErrorContainer = Color(0xFFFEE2E2)
val LightErrorDeepBox = Color(0xFFFFF1F2)
val LightOnError = Color(0xFFFFFFFF)
val LightOnErrorContainer = Color(0xFF991B1B)

// Expressive Category & File-Type Semantic Colors
val MintSuccess = Color(0xFF52C592)
val OnMintSuccess = Color(0xFF05261A)
val MintContainerDark = Color(0xFF123A2B)
val MintContainerLight = Color(0xFFD5F5E6)

val CategoryImages = Color(0xFF4361EE)
val OnCategoryImages = Color(0xFFFFFFFF)
val CategoryDocuments = Color(0xFFF5B72E)
val OnCategoryDocuments = Color(0xFF2B1E00)
val CategoryMedia = Color(0xFFEF4B4B)
val OnCategoryMedia = Color(0xFFFFFFFF)
val CategoryOther = MintSuccess
val CategoryZipped = Color(0xFF9B5CE8)
val OnCategoryZipped = Color(0xFFFFFFFF)
val CategoryUnknown = Color(0xFFA7AFC8)
val OnCategoryUnknown = Color(0xFF141A2B)

val DocsContainerDark = Color(0xFF382A0E)
val DocsContainerLight = Color(0xFFFEF3C7)
val MediaContainerDark = Color(0xFF3A1B26)
val MediaContainerLight = Color(0xFFFEE2E2)
val ZippedContainerDark = Color(0xFF2D1B46)
val ZippedContainerLight = Color(0xFFF3E8FF)
val UnknownContainerDark = Color(0xFF1B2640)
val UnknownContainerLight = Color(0xFFE2E8F0)

val FileVideo = CategoryMedia
val FilePdf = CategoryMedia
val FileDoc = CategoryDocuments
val FileZip = CategoryZipped
val FileCode = MintSuccess
val FileAudio = MintSuccess
val FileImage = CategoryImages
val FileUnknown = CategoryUnknown

// Cloud Storage Provider Colors
val ProviderGoogle = Color(0xFF4285F4)
val ProviderGoogleBgDark = Color(0xFF162F5E)
val ProviderGoogleBgLight = Color(0xFFDBEAFE)

val ProviderOneDrive = Color(0xFF3B9BFF)
val ProviderOneDriveBgDark = Color(0xFF0A2F57)
val ProviderOneDriveBgLight = Color(0xFFE0F2FE)

val ProviderTelegram = Color(0xFF2AABEE)
val ProviderTelegramBgDark = Color(0xFF0F3A52)
val ProviderTelegramBgLight = Color(0xFFDCF2FC)

// Supporting Chrome & Typography Tokens
val AvatarCircleDark = Color(0xFF1B2A5E)
val AvatarCircleLight = Color(0xFFDBE6FF)
val NavInactiveDark = Color(0xFFA9B3CA)
val NavInactiveLight = Color(0xFF56607B)
val TertiaryLabelDark = Color(0xFFC9D1E6)
val TertiaryLabelLight = Color(0xFF38425E)

// Status Colors
val StatusSuccess = MintSuccess
val StatusWarning = CategoryDocuments
val StatusError = CategoryMedia

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
