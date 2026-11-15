package com.cloudyte.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.cloudyte.app.data.ColorTheme
import com.cloudyte.app.data.ThemeMode

@Immutable
data class CloudyteExtendedColors(
    val ambientShape: Color,
    val accentBlue: Color,
    val lightAccent: Color,
    val brandStarburst: Color,
    val onBrandStarburst: Color,
    val avatarBg: Color,
    val unknownFileBg: Color,
    val dragHandle: Color,
    val errorDeepBox: Color,
    val mintSuccess: Color,
    val onMintSuccess: Color,
    val mintContainer: Color,
    val categoryImages: Color,
    val onCategoryImages: Color,
    val categoryDocs: Color,
    val onCategoryDocs: Color,
    val docsContainer: Color,
    val categoryMedia: Color,
    val onCategoryMedia: Color,
    val mediaContainer: Color,
    val categoryZipped: Color,
    val onCategoryZipped: Color,
    val zippedContainer: Color,
    val categoryUnknown: Color,
    val onCategoryUnknown: Color,
    val unknownContainer: Color,
    val providerTelegram: Color,
    val providerTelegramBg: Color,
    val providerGoogle: Color,
    val providerGoogleBg: Color,
    val providerOneDrive: Color,
    val providerOneDriveBg: Color,
    val navInactive: Color,
    val tertiaryLabel: Color
)

private val DarkExtendedColors = CloudyteExtendedColors(
    ambientShape = AmbientBlueDark,
    accentBlue = BrandPrimaryAccent,
    lightAccent = BrandPrimaryLight,
    brandStarburst = BrandPrimary,
    onBrandStarburst = Color.White,
    avatarBg = AvatarCircleDark,
    unknownFileBg = UnknownContainerDark,
    dragHandle = DarkOutline,
    errorDeepBox = DarkErrorDeepBox,
    mintSuccess = MintSuccess,
    onMintSuccess = OnMintSuccess,
    mintContainer = MintContainerDark,
    categoryImages = CategoryImages,
    onCategoryImages = OnCategoryImages,
    categoryDocs = CategoryDocuments,
    onCategoryDocs = OnCategoryDocuments,
    docsContainer = DocsContainerDark,
    categoryMedia = CategoryMedia,
    onCategoryMedia = OnCategoryMedia,
    mediaContainer = MediaContainerDark,
    categoryZipped = CategoryZipped,
    onCategoryZipped = OnCategoryZipped,
    zippedContainer = ZippedContainerDark,
    categoryUnknown = CategoryUnknown,
    onCategoryUnknown = OnCategoryUnknown,
    unknownContainer = UnknownContainerDark,
    providerTelegram = ProviderTelegram,
    providerTelegramBg = ProviderTelegramBgDark,
    providerGoogle = ProviderGoogle,
    providerGoogleBg = ProviderGoogleBgDark,
    providerOneDrive = ProviderOneDrive,
    providerOneDriveBg = ProviderOneDriveBgDark,
    navInactive = NavInactiveDark,
    tertiaryLabel = TertiaryLabelDark
)

private val LightExtendedColors = CloudyteExtendedColors(
    ambientShape = LightSurfaceContainerLow,
    accentBlue = BrandPrimary,
    lightAccent = BrandPrimary,
    brandStarburst = BrandPrimary,
    onBrandStarburst = Color.White,
    avatarBg = AvatarCircleLight,
    unknownFileBg = UnknownContainerLight,
    dragHandle = LightOutline,
    errorDeepBox = LightErrorDeepBox,
    mintSuccess = MintSuccess,
    onMintSuccess = OnMintSuccess,
    mintContainer = MintContainerLight,
    categoryImages = CategoryImages,
    onCategoryImages = OnCategoryImages,
    categoryDocs = CategoryDocuments,
    onCategoryDocs = OnCategoryDocuments,
    docsContainer = DocsContainerLight,
    categoryMedia = CategoryMedia,
    onCategoryMedia = OnCategoryMedia,
    mediaContainer = MediaContainerLight,
    categoryZipped = CategoryZipped,
    onCategoryZipped = OnCategoryZipped,
    zippedContainer = ZippedContainerLight,
    categoryUnknown = CategoryUnknown,
    onCategoryUnknown = OnCategoryUnknown,
    unknownContainer = UnknownContainerLight,
    providerTelegram = ProviderTelegram,
    providerTelegramBg = ProviderTelegramBgLight,
    providerGoogle = ProviderGoogle,
    providerGoogleBg = ProviderGoogleBgLight,
    providerOneDrive = ProviderOneDrive,
    providerOneDriveBg = ProviderOneDriveBgLight,
    navInactive = NavInactiveLight,
    tertiaryLabel = TertiaryLabelLight
)

val LocalCloudyteColors = staticCompositionLocalOf { DarkExtendedColors }

val MaterialTheme.cloudyteColors: CloudyteExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCloudyteColors.current

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSurface,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = BrandPrimaryAccent,
    onTertiary = DarkOnPrimary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    onError = DarkOnError,
    onErrorContainer = DarkOnErrorContainer,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSurface,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = BrandPrimaryAccent,
    onTertiary = LightOnPrimary,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    errorContainer = LightErrorContainer,
    onError = LightOnError,
    onErrorContainer = LightOnErrorContainer,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkOnSurface
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CloudyteTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    dynamicColor: Boolean = false,
    colorTheme: ColorTheme = ColorTheme.DEFAULT,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        colorTheme == ColorTheme.NOIR && useDarkTheme -> NoirDarkColorScheme
        colorTheme == ColorTheme.NOIR -> NoirLightColorScheme
        useDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val baseExtended = if (useDarkTheme) DarkExtendedColors else LightExtendedColors
    val extendedColors = when {
        colorTheme == ColorTheme.NOIR && useDarkTheme -> baseExtended.copy(
            ambientShape = Color(0xFF141414),
            accentBlue = Color(0xFFF5F5F5),
            lightAccent = Color(0xFFE5E5E5),
            brandStarburst = Color(0xFFFFFFFF),
            onBrandStarburst = Color(0xFF0A0A0A),
            avatarBg = Color(0xFF2A2A2A),
            unknownFileBg = Color(0xFF1F1F1F),
            dragHandle = Color(0xFF404040),
            mintSuccess = Color(0xFFF5F5F5),
            onMintSuccess = Color(0xFF0A0A0A),
            mintContainer = Color(0xFF262626),
            categoryImages = Color(0xFF2A2A2A),
            onCategoryImages = Color(0xFFFFFFFF),
            categoryDocs = Color(0xFF2E2E2E),
            onCategoryDocs = Color(0xFFF5F5F5),
            docsContainer = Color(0xFF1E1E1E),
            categoryMedia = Color(0xFF383838),
            onCategoryMedia = Color(0xFFFFFFFF),
            mediaContainer = Color(0xFF202020),
            categoryZipped = Color(0xFF444444),
            onCategoryZipped = Color(0xFFFFFFFF),
            zippedContainer = Color(0xFF242424),
            categoryUnknown = Color(0xFF222222),
            onCategoryUnknown = Color(0xFFD4D4D4),
            unknownContainer = Color(0xFF1A1A1A),
            providerTelegram = Color(0xFFF5F5F5),
            providerTelegramBg = Color(0xFF2A2A2A),
            providerGoogle = Color(0xFFE5E5E5),
            providerGoogleBg = Color(0xFF262626),
            providerOneDrive = Color(0xFFD4D4D4),
            providerOneDriveBg = Color(0xFF222222),
            navInactive = Color(0xFFA3A3A3),
            tertiaryLabel = Color(0xFFD4D4D4)
        )
        colorTheme == ColorTheme.NOIR -> baseExtended.copy(
            ambientShape = Color(0xFFE5E7EB),
            accentBlue = Color(0xFF111827),
            lightAccent = Color(0xFF1F2937),
            brandStarburst = Color(0xFF111827),
            onBrandStarburst = Color(0xFFFFFFFF),
            avatarBg = Color(0xFFE2E5EA),
            unknownFileBg = Color(0xFFE5E7EB),
            dragHandle = Color(0xFFA3A3A3),
            mintSuccess = Color(0xFF111827),
            onMintSuccess = Color(0xFFFFFFFF),
            mintContainer = Color(0xFFE5E7EB),
            categoryImages = Color(0xFFE2E5EA),
            onCategoryImages = Color(0xFF111827),
            categoryDocs = Color(0xFFE5E7EB),
            onCategoryDocs = Color(0xFF111827),
            docsContainer = Color(0xFFF3F4F6),
            categoryMedia = Color(0xFFD1D5DB),
            onCategoryMedia = Color(0xFF111827),
            mediaContainer = Color(0xFFE5E7EB),
            categoryZipped = Color(0xFF374151),
            onCategoryZipped = Color(0xFFFFFFFF),
            zippedContainer = Color(0xFFE5E7EB),
            categoryUnknown = Color(0xFFDADDE3),
            onCategoryUnknown = Color(0xFF1F2937),
            unknownContainer = Color(0xFFF3F4F6),
            providerTelegram = Color(0xFF111827),
            providerTelegramBg = Color(0xFFE5E7EB),
            providerGoogle = Color(0xFF1F2937),
            providerGoogleBg = Color(0xFFDADDE3),
            providerOneDrive = Color(0xFF374151),
            providerOneDriveBg = Color(0xFFE5E7EB),
            navInactive = Color(0xFF525252),
            tertiaryLabel = Color(0xFF404040)
        )
        dynamicColor -> baseExtended.copy(
            accentBlue = colorScheme.primary,
            lightAccent = colorScheme.primary,
            brandStarburst = colorScheme.primary,
            onBrandStarburst = colorScheme.onPrimary
        )
        else -> baseExtended
    }

    CompositionLocalProvider(LocalCloudyteColors provides extendedColors) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
