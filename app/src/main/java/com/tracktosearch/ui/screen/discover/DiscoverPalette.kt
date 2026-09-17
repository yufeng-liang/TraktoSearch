package com.tracktosearch.ui.screen.discover

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 发现页那几张渐变卡片的色板。
 *
 * **不跟随强调色**：这些渐变是分类的身份标记 —— 热门是橙、片单是玫红、豆瓣是绿。
 * 跟随强调色的话六张卡片会变成同一色系的六块方块，只剩标题能区分，扫一眼定位不了。
 * 和 [com.tracktosearch.ui.screen.statistics.rememberAnalyticsPalette] 同一个取舍：
 * 牺牲一点主题一致性，换来「这张卡片是什么」永远认得出。
 *
 * **hero 卡片刻意走浅色**：五个分类快捷入口卡的渐变此前为满足「白字 ≥ 4.5:1」被压暗，
 * 现按用户要求恢复到明亮浅色（保住色相、只动明度的原始设计），白字可读性随之放宽，
 * 见 DiscoverPaletteTest 的分档断言。
 * **登录引导卡与玫瑰提示卡仍维持深色**，以保住白字 AA。
 *
 * 改这里的色值时：保住色相、只动明度（见 DiscoverPaletteTest）。
 */
internal data class GradientColors(val start: Color, val end: Color) {

    /**
     * 卡片背景用的斜向渐变，左下到右上。
     *
     * 每张卡片都在 remember 里建一次 Brush —— 渐变对象本身不可变，
     * 但 Compose 每次重组新建实例会让 background 的比较失效、白重绘一次。
     */
    fun toBrush(): Brush = Brush.linearGradient(
        colors = listOf(start, end),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )
}

/** 热门 — 橙到芥黄。 */
internal val DiscoverPopularGradient = GradientColors(Color(0xFFF06A2F), Color(0xFFE6AA35))

/** 即将上映 — 玫红到紫。 */
internal val DiscoverUpcomingGradient = GradientColors(Color(0xFFCB6C98), Color(0xFF9C6BD1))

/** 推荐 — 蓝到青。 */
internal val DiscoverRecommendGradient = GradientColors(Color(0xFF5C89E0), Color(0xFF36AFC7))

/** 豆瓣新片 — 绿到青。 */
internal val DiscoverDoubanGradient = GradientColors(Color(0xFF42B87C), Color(0xFF43A9C2))

/** 热门片单 — 玫红到砖褐。 */
internal val DiscoverListsGradient = GradientColors(Color(0xFFE56B89), Color(0xFFC67A6B))

/** 豆瓣登录引导卡 — 与 [DiscoverDoubanGradient] 同族，稍亮一点以区分「入口」和「引导」。 */
internal val DiscoverDoubanLoginGradient = GradientColors(Color(0xFF348457), Color(0xFF328089))

/** Trakt 登录引导卡 — 深靛蓝，本来就够暗，白字有 7.73:1，不用压。 */
internal val DiscoverTraktLoginGradient = GradientColors(Color(0xFF283593), Color(0xFF3949AB))

/** 「去豆瓣登录」同款玫瑰紫，用在缺 Cookie 的提示卡上。 */
internal val DiscoverRoseGradient = GradientColors(Color(0xFF9D5CAD), Color(0xFFB6576D))
