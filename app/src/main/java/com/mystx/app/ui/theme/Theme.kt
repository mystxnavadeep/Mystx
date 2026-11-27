package com.mystx.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mystx.app.R
import com.mystx.app.model.PrefKeys

// Signature Emerald-Teal brand tokens (replaces generic royal blue)
val MystAqua = Color(0xFF14B8A6)
val MystTeal = Color(0xFF0F766E)
val MystAmber = Color(0xFFF59E0B)

fun mystBrandGradient() = listOf(MystTeal, MystAqua)

enum class MystUiStyle(
    val key: String,
    val label: String,
    val subtitle: String,
    val previewBg: Color,
    val previewAccent: Color,
    val cardCornerDp: Int,
    val isLight: Boolean = false
) {
    LIQUID_GLASS(
        key = "liquid_glass",
        label = "Obsidian",
        subtitle = "Deep slate & emerald teal",
        previewBg = Color(0xFF090B0E),
        previewAccent = Color(0xFF14B8A6),
        cardCornerDp = 14
    ),
    WARM_EDITORIAL(
        key = "warm_editorial",
        label = "Warm Paper",
        subtitle = "Ivory canvas & forest teal",
        previewBg = Color(0xFFF7F6F2),
        previewAccent = Color(0xFF0F766E),
        cardCornerDp = 14,
        isLight = true
    ),
    LINEAR_PRO(
        key = "linear_pro",
        label = "Carbon Amber",
        subtitle = "Matte carbon & warm gold",
        previewBg = Color(0xFF0C0D10),
        previewAccent = Color(0xFFF59E0B),
        cardCornerDp = 12
    ),
    NOTHING_OS(
        key = "nothing_os",
        label = "OLED Mono",
        subtitle = "Pure black & crimson signal",
        previewBg = Color(0xFF000000),
        previewAccent = Color(0xFFE11D48),
        cardCornerDp = 12
    ),
    MATERIAL_YOU(
        key = "material_you",
        label = "Material You",
        subtitle = "Matches your phone wallpaper",
        previewBg = Color(0xFF0E1716),
        previewAccent = Color(0xFF2DD4BF),
        cardCornerDp = 16
    );

    companion object {
        fun fromKey(key: String?): MystUiStyle =
            entries.firstOrNull { it.key == key } ?: LIQUID_GLASS
    }
}

val LocalMystUiStyle = staticCompositionLocalOf { MystUiStyle.LIQUID_GLASS }

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold)
)

private fun mystTypography(style: MystUiStyle): Typography {
    val family = if (style == MystUiStyle.NOTHING_OS) FontFamily.Monospace else FontFamily.SansSerif
    return Typography(
        displayLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
        displayMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
        displaySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
        headlineLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
        headlineMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
        headlineSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp),
        titleSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
        labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp)
    )
}

private val ObsidianColorScheme = darkColorScheme(
    background = Color(0xFF090B0E),
    surface = Color(0xFF12161C),
    surfaceVariant = Color(0xFF191F28),
    surfaceContainerHigh = Color(0xFF202733),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF252D3A),
    primary = Color(0xFF14B8A6),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF113330),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF22C55E),
    tertiary = Color(0xFF22C55E),
    tertiaryContainer = Color(0xFF123524),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF451A1A)
)

private val WarmPaperColorScheme = lightColorScheme(
    background = Color(0xFFF7F6F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEFEDE7),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111827),
    onSurface = Color(0xFF111827),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE2DFD6),
    primary = Color(0xFF0F766E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF115E59),
    secondary = Color(0xFF15803D),
    tertiary = Color(0xFF15803D),
    tertiaryContainer = Color(0xFFDCFCE7),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2)
)

private val CarbonAmberColorScheme = darkColorScheme(
    background = Color(0xFF0C0D10),
    surface = Color(0xFF14161B),
    surfaceVariant = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF232730),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF282C36),
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF1C1302),
    primaryContainer = Color(0xFF3A2807),
    onPrimaryContainer = Color(0xFFFEF3C7),
    secondary = Color(0xFF10B981),
    tertiary = Color(0xFF10B981),
    tertiaryContainer = Color(0xFF0F352A),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF451A1A)
)

private val OledMonoColorScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF0C0C0E),
    surfaceVariant = Color(0xFF16161A),
    surfaceContainerHigh = Color(0xFF1E1E24),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF27272E),
    primary = Color(0xFFE11D48),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3F0B19),
    onPrimaryContainer = Color(0xFFFFE4E6),
    secondary = Color(0xFF22C55E),
    tertiary = Color(0xFF22C55E),
    tertiaryContainer = Color(0xFF123524),
    error = Color(0xFFFF453A),
    errorContainer = Color(0xFF4D1511)
)

private val MaterialYouFallbackColorScheme = darkColorScheme(
    background = Color(0xFF0E1716),
    surface = Color(0xFF152220),
    surfaceVariant = Color(0xFF1E2F2D),
    surfaceContainerHigh = Color(0xFF253A37),
    onBackground = Color(0xFFE2F1EE),
    onSurface = Color(0xFFE2F1EE),
    onSurfaceVariant = Color(0xFF9FBAB5),
    outline = Color(0xFF2F4743),
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF4ADE80),
    tertiary = Color(0xFF4ADE80),
    tertiaryContainer = Color(0xFF144226),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF690005)
)

private tailrec fun Context.findActivityForTheme(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityForTheme()
    else -> null
}

@Composable
fun MystxTheme(
    forceLight: Boolean = false,
    uiStyle: MystUiStyle? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val resolvedStyle = uiStyle ?: remember(context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        MystUiStyle.fromKey(prefs.getString(PrefKeys.APP_UI_STYLE, PrefKeys.DEFAULT_UI_STYLE))
    }

    val colorScheme: ColorScheme = if (forceLight) {
        WarmPaperColorScheme
    } else {
        when (resolvedStyle) {
            MystUiStyle.LIQUID_GLASS -> ObsidianColorScheme
            MystUiStyle.WARM_EDITORIAL -> WarmPaperColorScheme
            MystUiStyle.LINEAR_PRO -> CarbonAmberColorScheme
            MystUiStyle.NOTHING_OS -> OledMonoColorScheme
            MystUiStyle.MATERIAL_YOU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context)
                } else {
                    MaterialYouFallbackColorScheme
                }
            }
        }
    }

    val isLightBars = colorScheme.background.luminance() > 0.5f
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivityForTheme() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = isLightBars
            controller.isAppearanceLightNavigationBars = isLightBars
        }
    }

    CompositionLocalProvider(LocalMystUiStyle provides resolvedStyle) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = mystTypography(resolvedStyle),
            content = content
        )
    }
}
