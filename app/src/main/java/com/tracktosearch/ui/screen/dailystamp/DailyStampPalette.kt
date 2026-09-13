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
 * 整屏的底与字色映射当前 [MaterialTheme] 的中性色，格子那张纸不映射——纸是固定的
 * 纸黄，见 [tile]。字段名沿用旧组件用的 ink 一套，换色不必牵动排版代码。
 *
 * 台词卡片不走这里——那是一页开屏日签纸，见 [rememberDailyStampCardPalette]。
 */
@Immutable
internal data class DailyStampPalette(
    /** 整屏底色 */
    val paper: Color,
    val ink: Color,
    val inkSoft: Color,
    /**
     * 小字文案色：星期表头、统计标签、底部提示、「你来之前」那些天的日期数字。
     *
     * [inkSoft] 收到 0.80，压在 [paper] 上 4.9:1（浅）/ 9.4:1（深），过 WCAG AA 的 4.5:1。
     * 收 alpha 而不是直接用 [inkSoft]：这些字本来就该比正文轻一档，只是不能轻到看不见。
     *
     * 再淡就只剩装饰用的 [inkMuted]。**不要拿 Material 的 `outlineVariant` 当文字色**：
     * 那是画线用的角色，压在 [paper] 上只有 1.1:1（浅）到 1.2:1（深）。
     */
    val inkHint: Color,
    /**
     * 比 [inkHint] 再淡一档：纯装饰的年月刻度和隔点。
     *
     * 0.64 是浅色主题下还能保住 3:1 的下限（3.3:1 / 6.3:1），装饰性文字按这条线走。
     */
    val inkMuted: Color,
    /**
     * 格子那张纸的正面。
     *
     * 不跟随主题深浅：日历上的一格就是一张浅色纸片，深色主题下它照旧是纸，只是压在
     * 更暗的桌面上。跟着主题走的话深色下格子会退成一块比底色高一点的深灰，一整月看过去
     * 什么都没有——那正是这一屏最早的样子。
     *
     * 三档纸色而不是一档纸色配三档透明度：透明度是拿底色兑出来的，深色主题下兑到 0.2
     * 就几乎是桌面色，写在上面的墨字直接看不见。三档都是不透明的纸，墨色永远算得准。
     */
    val tile: Color,
    /** 错过那天那张纸，比正面旧一档 */
    val tileMissed: Color,
    /** 还没到那天那张纸，介于两者之间 */
    val tileLatent: Color,
    /** 折角翻过来那一面，纸背比正面深 */
    val tileBack: Color,
    /** 纸上的线：纸边、折痕、错过那圈虚线 */
    val tileEdge: Color,
    /** 纸上的字：日期数字 */
    val tileInk: Color,
    /** 纸上的字，淡一档：关键词 */
    val tileInkSoft: Color,
    /** 今天那一格的印色。纸上要用固定的朱红，主题强调色在深色下是亮色，压在纸上看不见 */
    val tileSeal: Color,
)

@Composable
internal fun rememberDailyStampPalette(): DailyStampPalette {
    val colors = MaterialTheme.colorScheme
    return DailyStampPalette(
        paper = colors.background,
        ink = colors.onSurface,
        inkSoft = colors.onSurfaceVariant,
        inkHint = colors.onSurfaceVariant.copy(alpha = 0.80f),
        inkMuted = colors.onSurfaceVariant.copy(alpha = 0.64f),
        tile = TILE_PAPER,
        tileMissed = TILE_PAPER_MISSED,
        tileLatent = TILE_PAPER_LATENT,
        tileBack = TILE_PAPER_BACK,
        tileEdge = TILE_EDGE,
        tileInk = TILE_INK,
        tileInkSoft = TILE_INK_SOFT,
        tileSeal = TILE_SEAL,
    )
}

/**
 * 格子那几档纸色，与台词卡片的纸（[SplashPalette.Light]）同一族，压深一点。
 *
 * 卡片是单独一张、离得近，纸可以白得像新纸；格子是三十一张排在一起、离得远，
 * 太白会连成一片发光的墙，把海报都压下去。
 *
 * 墨色对纸色的对比度：[TILE_INK] 8.1:1、[TILE_INK_SOFT] 5.7:1，都过 WCAG AA 的 4.5:1；
 * 最旧那档纸 [TILE_PAPER_MISSED] 上分别是 7.0:1 和 4.9:1，也还在线上。
 */
private val TILE_PAPER = Color(0xFFF3E7D1)
private val TILE_PAPER_LATENT = Color(0xFFEDE0C8)
private val TILE_PAPER_MISSED = Color(0xFFE2D5BB)
private val TILE_PAPER_BACK = Color(0xFFD8C6A4)
private val TILE_EDGE = Color(0xFF8A6A45)
private val TILE_INK = Color(0xFF5C3D26)
private val TILE_INK_SOFT = Color(0xFF7F5137)
private val TILE_SEAL = Color(0xFFB4472F)

/**
 * 台词卡片用的纸色板。
 *
 * 卡片是开屏那一页日签（见 StampPage），纸感是它的主体，所以不跟随主题色，只分深浅。
 * 深浅看当前主题的明度而不是系统深色模式：应用内的主题设置可以和系统相反。
 */
@Composable
internal fun rememberDailyStampCardPalette(): SplashPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        SplashPalette.Dark
    } else {
        SplashPalette.Light
    }
