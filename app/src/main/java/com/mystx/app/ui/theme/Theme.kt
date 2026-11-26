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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import com.mystx.app.R
import com.mystx.app.model.PrefKeys

val MystAqua = Color(0xFF0A84FF) // iOS Blue Dark
val MystTeal = Color(0xFF007AFF) // iOS Blue secondary
val MystAmber = Color(0xFFFF9F0A) // iOS Orange Dark

fun mystBrandGradient() = listOf(MystAqua, MystTeal)

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
        label = "Liquid Glass",
        subtitle = "Frosted glass & aurora glow",
        previewBg = Color(0xFF070B14),
        previewAccent = Color(0xFF0A84FF),
        cardCornerDp = 24
    ),
    LINEAR_PRO(
        key = "linear_pro",
        label = "Linear Pro",
        subtitle = "Graphite & hairline precision",
        previewBg = Color(0xFF0B0C0F),
        previewAccent = Color(0xFF6366F1),
        cardCornerDp = 12
    ),
    NOTHING_OS(
        key = "nothing_os",
        label = "Nothing OS",
        subtitle = "Pure OLED black & signal red",
        previewBg = Color(0xFF000000),
        previewAccent = Color(0xFFD71921),
        cardCornerDp = 16
    ),
    MATERIAL_YOU(
        key = "material_you",
        label = "Material You",
        subtitle = "Dynamic wallpaper squircles",
        previewBg = Color(0xFF10201E),
        previewAccent = Color(0xFF5EEAD4),
        cardCornerDp = 28
    ),
    WARM_EDITORIAL(
        key = "warm_editorial",
        label = "Warm Editorial",
        subtitle = "Alabaster paper & serif warmth",
        previewBg = Color(0xFFF7F5F0),
        previewAccent = Color(0xFFC25634),
        cardCornerDp = 18,
        isLight = true
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
    val base = Typography()
    val headingFamily = when (style) {
        MystUiStyle.WARM_EDITORIAL -> FontFamily.Serif
        MystUiStyle.NOTHING_OS -> FontFamily.Monospace
        else -> Poppins
    }
    val bodyFamily = when (style) {
        MystUiStyle.NOTHING_OS -> FontFamily.Monospace
        else -> Poppins
    }
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = headingFamily),
        displayMedium = base.displayMedium.copy(fontFamily = headingFamily),
        displaySmall = base.displaySmall.copy(fontFamily = headingFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = headingFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = headingFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = headingFamily),
        titleLarge = base.titleLarge.copy(fontFamily = headingFamily),
        titleMedium = base.titleMedium.copy(fontFamily = headingFamily),
        titleSmall = base.titleSmall.copy(fontFamily = bodyFamily),
        bodyLarge = base.bodyLarge.copy(fontFamily = bodyFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = bodyFamily),
        bodySmall = base.bodySmall.copy(fontFamily = bodyFamily),
        labelLarge = base.labelLarge.copy(fontFamily = bodyFamily),
        labelMedium = base.labelMedium.copy(fontFamily = bodyFamily),
        labelSmall = base.labelSmall.copy(fontFamily = bodyFamily)
    )
}

private val LiquidGlassColorScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0x33FFFFFF),
    surfaceVariant = Color(0x26FFFFFF),
    surfaceContainerHigh = Color(0x40FFFFFF),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0x99EBEBF5),
    outline = Color(0x33FFFFFF),
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF004080),
    onPrimaryContainer = Color(0xFFE5F1FF),
    secondary = Color(0xFF30D158),
    tertiary = Color(0xFFFF9F0A),
    tertiaryContainer = Color(0xFF4D2F00),
    error = Color(0xFFFF453A),
    errorContainer = Color(0xFF4D1511)
)

private val LinearProColorScheme = darkColorScheme(
    background = Color(0xFF0B0C0F),
    surface = Color(0xFF13151B),
    surfaceVariant = Color(0xFF1B1E27),
    surfaceContainerHigh = Color(0xFF202430),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF282C38),
    primary = Color(0xFF6366F1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF23254D),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF34D399),
    tertiary = Color(0xFF38BDF8),
    tertiaryContainer = Color(0xFF0C2D48),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF451A1A)
)

private val NothingOsColorScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF090909),
    surfaceVariant = Color(0xFF141414),
    surfaceContainerHigh = Color(0xFF1C1C1C),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0x66FFFFFF),
    primary = Color(0xFFD71921),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3B090C),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFD71921),
    tertiaryContainer = Color(0xFF2A090B),
    error = Color(0xFFFF453A),
    errorContainer = Color(0xFF4D1511)
)

private val MaterialYouFallbackColorScheme = darkColorScheme(
    background = Color(0xFF0E1716),
    surface = Color(0xFF172624),
    surfaceVariant = Color(0xFF213532),
    surfaceContainerHigh = Color(0xFF28403D),
    onBackground = Color(0xFFE2F1EE),
    onSurface = Color(0xFFE2F1EE),
    onSurfaceVariant = Color(0xFFA7C4BF),
    outline = Color(0xFF3D5A55),
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Color(0xFF99F6E4),
    tertiary = Color(0xFF86EFAC),
    tertiaryContainer = Color(0xFF144226),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF690005)
)

private val WarmEditorialColorScheme = lightColorScheme(
    background = Color(0xFFF7F5F0),
    surface = Color(0xFFFFFDF9),
    surfaceVariant = Color(0xFFEDE8DF),
    surfaceContainerHigh = Color(0xFFE6DFD3),
    onBackground = Color(0xFF1C1917),
    onSurface = Color(0xFF1C1917),
    onSurfaceVariant = Color(0xFF57534E),
    outline = Color(0xFFD6CEC2),
    primary = Color(0xFFC25634),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFCE7DF),
    onPrimaryContainer = Color(0xFF5E210F),
    secondary = Color(0xFF3F6B57),
    tertiary = Color(0xFF3F6B57),
    tertiaryContainer = Color(0xFFDCEEE5),
    error = Color(0xFFB91C1C),
    errorContainer = Color(0xFFFEE2E2)
)

private val IosLightColorScheme = lightColorScheme(
    background = Color(0xFFF2F2F7),
    surface = Color(0xB3FFFFFF),
    surfaceVariant = Color(0x80FFFFFF),
    surfaceContainerHigh = Color(0xE6FFFFFF),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
    onSurfaceVariant = Color(0xFF3C3C43).copy(alpha = 0.6f),
    outline = Color(0x33000000),
    primary = Color(0xFF007AFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF004080),
    secondary = Color(0xFF34C759),
    tertiary = Color(0xFFFF9500),
    tertiaryContainer = Color(0xFFFFEAD1),
    error = Color(0xFFFF3B30),
    errorContainer = Color(0xFFFFD8D6)
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
        IosLightColorScheme
    } else {
        when (resolvedStyle) {
            MystUiStyle.LIQUID_GLASS -> LiquidGlassColorScheme
            MystUiStyle.LINEAR_PRO -> LinearProColorScheme
            MystUiStyle.NOTHING_OS -> NothingOsColorScheme
            MystUiStyle.MATERIAL_YOU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicDarkColorScheme(context)
                } else {
                    MaterialYouFallbackColorScheme
                }
            }
            MystUiStyle.WARM_EDITORIAL -> WarmEditorialColorScheme
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
