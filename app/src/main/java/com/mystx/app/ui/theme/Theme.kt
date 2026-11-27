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

val MystAqua = Color(0xFF2F7CF6)
val MystTeal = Color(0xFF2563EB)
val MystAmber = Color(0xFFF59E0B)

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
        label = "Midnight",
        subtitle = "Clean dark ink & crisp blue",
        previewBg = Color(0xFF0B0D11),
        previewAccent = Color(0xFF2F7CF6),
        cardCornerDp = 16
    ),
    WARM_EDITORIAL(
        key = "warm_editorial",
        label = "Daylight",
        subtitle = "Clean light paper & high contrast",
        previewBg = Color(0xFFF5F6F8),
        previewAccent = Color(0xFF2563EB),
        cardCornerDp = 16,
        isLight = true
    ),
    LINEAR_PRO(
        key = "linear_pro",
        label = "Graphite",
        subtitle = "Matte charcoal & indigo accents",
        previewBg = Color(0xFF0E1015),
        previewAccent = Color(0xFF6366F1),
        cardCornerDp = 12
    ),
    NOTHING_OS(
        key = "nothing_os",
        label = "OLED Mono",
        subtitle = "Pure black & signal red",
        previewBg = Color(0xFF000000),
        previewAccent = Color(0xFFD71921),
        cardCornerDp = 14
    ),
    MATERIAL_YOU(
        key = "material_you",
        label = "Material You",
        subtitle = "Matches your phone wallpaper",
        previewBg = Color(0xFF101918),
        previewAccent = Color(0xFF2DD4BF),
        cardCornerDp = 22
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
    val family = if (style == MystUiStyle.NOTHING_OS) FontFamily.Monospace else Poppins
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family)
    )
}

private val MidnightColorScheme = darkColorScheme(
    background = Color(0xFF0B0D11),
    surface = Color(0xFF14171F),
    surfaceVariant = Color(0xFF1C202B),
    surfaceContainerHigh = Color(0xFF222734),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF262B38),
    primary = Color(0xFF2F7CF6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF162C52),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF22C55E),
    tertiary = Color(0xFF22C55E),
    tertiaryContainer = Color(0xFF123524),
    error = Color(0xFFEF4444),
    errorContainer = Color(0xFF451A1A)
)

private val DaylightColorScheme = lightColorScheme(
    background = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F2F5),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFE2E6ED),
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF16A34A),
    tertiary = Color(0xFF16A34A),
    tertiaryContainer = Color(0xFFDCFCE7),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2)
)

private val GraphiteColorScheme = darkColorScheme(
    background = Color(0xFF0E1015),
    surface = Color(0xFF161922),
    surfaceVariant = Color(0xFF1F2330),
    surfaceContainerHigh = Color(0xFF262B3B),
    onBackground = Color(0xFFF3F4F6),
    onSurface = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF2B3040),
    primary = Color(0xFF6366F1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF242654),
    onPrimaryContainer = Color(0xFFE0E7FF),
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
    outline = Color(0xFF2E2E36),
    primary = Color(0xFFD71921),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF3D0C0F),
    onPrimaryContainer = Color(0xFFFFDAD9),
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
        DaylightColorScheme
    } else {
        when (resolvedStyle) {
            MystUiStyle.LIQUID_GLASS -> MidnightColorScheme
            MystUiStyle.WARM_EDITORIAL -> DaylightColorScheme
            MystUiStyle.LINEAR_PRO -> GraphiteColorScheme
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
