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
    /**
     * 描边色：分隔线、格子边框、禁用态图标。
     *
     * 它映射的是 `outlineVariant`，Material 把这个角色定义为「画线用」而不是「写字用」，
     * 压在 [paper] 上只有 1.1:1（浅色主题）到 1.2:1（深色主题）。**不要拿它当文字色**，
     * 小字文案用 [inkHint]、更淡一档用 [inkMuted]。
     */
    val inkFaint: Color,
    /**
     * 小字文案色：星期表头、统计标签、底部提示、签到过的日期数字。
     *
     * [inkSoft] 收到 0.80，压在 [paper] 上 4.9:1（浅）/ 9.4:1（深），过 WCAG AA 的 4.5:1。
     * 收 alpha 而不是直接用 [inkSoft]：这些字本来就该比正文轻一档，只是不能轻到看不见。
     */
    val inkHint: Color,
    /**
     * 比 [inkHint] 再淡一档：纯装饰的刻度、以及未来日期那种「在这儿但还没到」的数字。
     *
     * 0.64 是浅色主题下还能保住 3:1 的下限（3.3:1 / 6.3:1），装饰性文字按这条线走。
     */
    val inkMuted: Color,
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
        inkHint = colors.onSurfaceVariant.copy(alpha = 0.80f),
        inkMuted = colors.onSurfaceVariant.copy(alpha = 0.64f),
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
