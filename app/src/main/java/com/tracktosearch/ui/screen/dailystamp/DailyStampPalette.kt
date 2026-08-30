package com.tracktosearch.ui.screen.dailystamp

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.tracktosearch.ui.screen.splash.SplashPalette

/**
 * 日历那一屏的色板。
 *
 * 只映射当前 [MaterialTheme] 的中性色与强调色，不再借开屏的暖黄纸色。字段名沿用
 * 旧组件用的 ink / seal 一套，换色不必牵动排版代码。
 *
 * 台词卡片不走这里——它仍是票根质感的纸，见 [rememberDailyStampCardPalette]。
 */
@Immutable
internal data class DailyStampPalette(
    /** 整屏底色 */
    val paper: Color,
    /** 格子底色，比底色高一档 */
    val cream: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    /** 今天那一格的边框、以及一切需要强调的地方 */
    val seal: Color,
    val isDark: Boolean,
)

@Composable
internal fun rememberDailyStampPalette(): DailyStampPalette {
    val colors = MaterialTheme.colorScheme
    return DailyStampPalette(
        paper = colors.background,
        cream = colors.surfaceVariant,
        ink = colors.onSurface,
        inkSoft = colors.onSurfaceVariant,
        inkFaint = colors.outlineVariant,
        seal = colors.primary,
        isDark = colors.surface.luminance() < 0.5f,
    )
}

/**
 * 台词卡片用的纸色板。
 *
 * 卡片是要保存/分享出去的那张票根，纸感是它的主体，所以不跟随主题色。深浅只看当前
 * 主题的明度而不是系统深色模式：应用内的主题设置可以和系统相反。
 */
@Composable
internal fun rememberDailyStampCardPalette(): SplashPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        SplashPalette.Dark
    } else {
        SplashPalette.Light
    }
