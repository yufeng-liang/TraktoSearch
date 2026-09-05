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
 * **不分明暗档**：卡片上是 17sp Bold 白字加 12sp 白字，两者都得按 WCAG 普通字号算
 * （14pt 粗体即 18.67px 才算大字号，17sp 差一点），所以每个端点的亮度都被
 * 「白字 ≥ 4.5:1」压到 0.183 以下。这个上限一卡，明暗两套算出来只差两三个 RGB 档，
 * 分两套没有意义。原先的亮版热门橙 #E6AA35 上白字只有 2.07:1，六张卡片里五张不合格。
 *
 * 改这里的色值时：保住色相、只动明度，然后确认白字仍有 4.5:1
 * （见 DiscoverPaletteTest）。
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
internal val DiscoverPopularGradient = GradientColors(Color(0xFFC05526), Color(0xFF966F22))

/** 即将上映 — 玫红到紫。 */
internal val DiscoverUpcomingGradient = GradientColors(Color(0xFFAC5B80), Color(0xFF8D61BD))

/** 推荐 — 蓝到青。 */
internal val DiscoverRecommendGradient = GradientColors(Color(0xFF4E74BE), Color(0xFF278091))

/** 豆瓣新片 — 绿到青。 */
internal val DiscoverDoubanGradient = GradientColors(Color(0xFF2F8459), Color(0xFF327F91))

/** 热门片单 — 玫红到砖褐。 */
internal val DiscoverListsGradient = GradientColors(Color(0xFFB7566E), Color(0xFFA46558))

/** 豆瓣登录引导卡 — 与 [DiscoverDoubanGradient] 同族，稍亮一点以区分「入口」和「引导」。 */
internal val DiscoverDoubanLoginGradient = GradientColors(Color(0xFF348457), Color(0xFF328089))

/** Trakt 登录引导卡 — 深靛蓝，本来就够暗，白字有 7.73:1，不用压。 */
internal val DiscoverTraktLoginGradient = GradientColors(Color(0xFF283593), Color(0xFF3949AB))

/** 「去豆瓣登录」同款玫瑰紫，用在缺 Cookie 的提示卡上。 */
internal val DiscoverRoseGradient = GradientColors(Color(0xFF9D5CAD), Color(0xFFB6576D))
