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
    background = Color.White,
    onBackground = DarkGray,
    surface = Color.White,
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
        background = Color.White,
        onBackground = Color(0xFF5D4638),
        // Haze 默认使用 surface 作为填充色，保持与其他主题一致。
        surface = Color.White,
        onSurface = Color(0xFF5D4638),
        surfaceVariant = Color(0xFFEFE9DF),
        onSurfaceVariant = Color(0xFF49454F),
        outline = Color(0xFFC9C0B2),
        outlineVariant = Color(0xFFD9D2C6),
    )
}

/** 根据种子色生成自定义 colorScheme */
private fun monetColorScheme(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    val primary = seed
    val onPrimary = Color.White
    val primaryContainer = seed.copy(alpha = 0.8f)
    val secondary = seed.copy(alpha = 0.7f)
    val onSecondary = Color.White
    val secondaryContainer = seed.copy(alpha = 0.15f)
    val onSecondaryContainer = seed
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            background = CinemaBackground,
            onBackground = Color.White,
            surface = CinemaSurface,
            onSurface = Color.White,
            surfaceVariant = CinemaCard,
            onSurfaceVariant = LightGray,
            outline = Color(0xFF3A3A5A),
            outlineVariant = Color(0xFF1E1E32),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            background = Color.White,
            onBackground = DarkGray,
            surface = Color.White,
            onSurface = DarkGray,
            surfaceVariant = Color(
                red = seed.red * 0.08f + 0.92f,
                green = seed.green * 0.08f + 0.92f,
                blue = seed.blue * 0.08f + 0.92f
            ),
            outline = Color(0xFFBDBDBD),
            outlineVariant = Color(0xFFE8E8E8),
        )
    }
}

@Composable
fun TraktoSearchTheme(
    themeMode: String = "system",
    accentColor: MonetAccent? = null,
    visualEffectMode: VisualEffectMode = VisualEffectMode.BLUR,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = when {
        accentColor == MonetAccent.VINTAGE_TICKET -> vintageTicketColorScheme(darkTheme)
        // 自定义莫奈主题色：直接用种子色生成 scheme，忽略动态壁纸
        accentColor != null -> monetColorScheme(
            seed = if (darkTheme) accentColor.dark else accentColor.light,
            dark = darkTheme
        )
        // 默认：Android 12+ 使用壁纸动态颜色
        Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
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
            content = content
        )
    }
}
