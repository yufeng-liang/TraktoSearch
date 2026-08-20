package com.tracktosearch.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.LocalHazeBlurStyle

internal const val AppHazeDefaultNoiseFactor = 0.10f

private val AppHazeDefaultBlurStyle = HazeBlurStyle {
    noiseFactor(AppHazeDefaultNoiseFactor)
}

/**
 * 统一的 Switch 颜色配置：关闭态轨道/边框用主题色透明度，确保关闭态颜色统一跟随主题色。
 * 所有 Switch 都应使用此函数，避免每处重复声明。
 * 使用方式：Switch(..., colors = appSwitchColors())
 */
@Composable
fun appSwitchColors(): SwitchColors = SwitchDefaults.colors(
    uncheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
    uncheckedThumbColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
)

private val DarkColorScheme = darkColorScheme(
    primary = Red500,
    onPrimary = Color.White,
    primaryContainer = Red700,
    secondary = QuarkBlue,
    onSecondary = Color.White,
    background = CinemaBackground,
    onBackground = Color.White,
    surface = CinemaSurface,
    onSurface = Color.White,
    surfaceVariant = CinemaCard,
    onSurfaceVariant = LightGray,
    outline = Color(0xFF3A3A5A),
    outlineVariant = Color(0xFF1E1E32),
)

private val LightColorScheme = lightColorScheme(
    primary = Red700,
    onPrimary = Color.White,
    primaryContainer = Red500,
    secondary = QuarkBlue,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = DarkGray,
    surface = LightSurface,
    onSurface = DarkGray,
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE8E8E8),
)

internal fun vintageTicketColorScheme(dark: Boolean): androidx.compose.material3.ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFFC48763),
        onPrimary = Color(0xFF3E2518),
        primaryContainer = Color(0xFF754832),
        onPrimaryContainer = Color(0xFFFFDBCA),
        secondary = Color(0xFFE5B5C1),
        onSecondary = Color(0xFF44232D),
        // 显式覆盖 secondaryContainer，避免 Material3 baseline 紫色 fallback 导致 FilterChip 选中态变紫
        secondaryContainer = Color(0xFF754832),
        onSecondaryContainer = Color(0xFFFFDBCA),
        background = CinemaBackground,
        onBackground = Color(0xFFF7EDE3),
        // Haze 默认使用 surface 作为填充色，保持与其他主题一致。
        surface = CinemaSurface,
        onSurface = Color(0xFFF7EDE3),
        surfaceVariant = Color(0xFF4A382E),
        onSurfaceVariant = LightGray,
        outline = Color(0xFFA98A74),
        outlineVariant = Color(0xFF685244),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF9A6242),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE9E2D4),
        onPrimaryContainer = Color(0xFF5D4638),
        secondary = Color(0xFFD9D2C6),
        onSecondary = Color.White,
        // 显式覆盖 secondaryContainer，避免 Material3 baseline 紫色 fallback 导致 FilterChip 选中态变紫
        secondaryContainer = Color(0xFFE9E2D4),
        onSecondaryContainer = Color(0xFF5D4638),
        background = LightBackground,
        onBackground = Color(0xFF5D4638),
        // Haze 默认使用 surface 作为填充色，保持与其他主题一致。
        surface = LightSurface,
        onSurface = Color(0xFF5D4638),
        surfaceVariant = Color(0xFFEFE9DF),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFFC9C0B2),
        outlineVariant = Color(0xFFD9D2C6),
    )
}

/**
 * 根据种子色生成 Material You 标准 TonalSpot colorScheme。
 * 使用 TonalPalette 标准映射（Hct 色彩空间），替代旧的手写 alpha 合成方案。
 * 品牌背景/表面色保持项目定制值不变。
 */
private fun monetColorScheme(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val seedArgb = seed.toArgb()
    val tonal = com.tracktosearch.data.util.mcu.palettes.TonalPalette.fromInt(seedArgb)
    val neutral = com.tracktosearch.data.util.mcu.palettes.TonalPalette.fromHueAndChroma(
        com.tracktosearch.data.util.mcu.hct.Hct.fromInt(seedArgb).hue, 4.0
    )
    val neutralVariant = com.tracktosearch.data.util.mcu.palettes.TonalPalette.fromHueAndChroma(
        com.tracktosearch.data.util.mcu.hct.Hct.fromInt(seedArgb).hue, 8.0
    )

    // TonalSpot 标准 tone 值（Material You 官方映射）
    val primary = Color(if (dark) tonal.tone(80.0) else tonal.tone(40.0))
    val onPrimary = Color(if (dark) tonal.tone(20.0) else tonal.tone(100.0))
    val primaryContainer = Color(if (dark) tonal.tone(30.0) else tonal.tone(90.0))
    val onPrimaryContainer = Color(if (dark) tonal.tone(90.0) else tonal.tone(10.0))
    val secondary = Color(if (dark) neutral.tone(80.0) else neutral.tone(40.0))
    val onSecondary = Color(if (dark) neutral.tone(20.0) else neutral.tone(100.0))
    val secondaryContainer = Color(if (dark) neutral.tone(30.0) else neutral.tone(90.0))
    val onSecondaryContainer = Color(if (dark) neutral.tone(90.0) else neutral.tone(10.0))
    val tertiary = Color(if (dark) tonal.tone(80.0) else tonal.tone(40.0))
    val onTertiary = Color(if (dark) tonal.tone(20.0) else tonal.tone(100.0))
    val tertiaryContainer = Color(if (dark) tonal.tone(30.0) else tonal.tone(90.0))
    val onTertiaryContainer = Color(if (dark) tonal.tone(90.0) else tonal.tone(10.0))
    val error = Color(0xFFBA1A1A)
    val onError = Color.White
    val errorContainer = Color(if (dark) tonal.tone(30.0) else tonal.tone(90.0))
    val onErrorContainer = Color(if (dark) tonal.tone(90.0) else tonal.tone(10.0))
    val surfaceVar = Color(if (dark) neutralVariant.tone(30.0) else neutralVariant.tone(90.0))
    val onSurfaceVar = Color(if (dark) neutralVariant.tone(80.0) else neutralVariant.tone(30.0))
    val outline = Color(if (dark) neutralVariant.tone(60.0) else neutralVariant.tone(50.0))
    val outlineVariant = Color(if (dark) neutralVariant.tone(30.0) else neutralVariant.tone(80.0))

    return if (dark) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
            background = CinemaBackground, onBackground = Color.White,
            surface = CinemaSurface, onSurface = Color.White,
            surfaceVariant = surfaceVar, onSurfaceVariant = onSurfaceVar,
            outline = outline, outlineVariant = outlineVariant,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
            background = LightBackground, onBackground = DarkGray,
            surface = LightSurface, onSurface = DarkGray,
            surfaceVariant = surfaceVar, onSurfaceVariant = onSurfaceVar,
            outline = outline, outlineVariant = outlineVariant,
        )
    }
}

@Composable
fun TraktoSearchTheme(
    themeMode: String = "system",
    accentColor: MonetAccent? = null,
    customAccentArgb: Long? = null,
    visualEffectMode: VisualEffectMode = VisualEffectMode.GLASS,
    glassVariant: GlassVariant = GlassVariant.CLEAR,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        customAccentArgb != null -> monetColorScheme(
            seed = Color(customAccentArgb.toInt()),
            dark = darkTheme
        )
        accentColor == MonetAccent.VINTAGE_TICKET -> vintageTicketColorScheme(darkTheme)
        // 自定义莫奈主题色：直接用种子色生成 scheme，忽略动态壁纸
        accentColor != null -> monetColorScheme(
            seed = if (darkTheme) accentColor.dark else accentColor.light,
            dark = darkTheme
        )
        // 默认：Android 12+ 使用壁纸动态颜色
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context).copy(
                    background = LightBackground,
                    surface = LightSurface
                )
            }
        }
        // 低版本：静态配色
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        // 统一覆盖 Haze 未显式设置时的噪点默认值，显式 noiseFactor(0f) 仍然优先。
        CompositionLocalProvider(
            LocalHazeBlurStyle provides AppHazeDefaultBlurStyle,
            LocalVisualEffectMode provides visualEffectMode,
            LocalGlassVariant provides glassVariant,
            content = content
        )
    }
}
