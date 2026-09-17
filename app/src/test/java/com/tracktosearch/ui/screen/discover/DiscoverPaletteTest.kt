package com.tracktosearch.ui.screen.discover

import androidx.compose.ui.graphics.Color
import com.tracktosearch.ui.theme.ThemeTestSupport.AA_NORMAL
import com.tracktosearch.ui.theme.ThemeTestSupport.contrast
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发现页渐变卡片的可读性护栏。
 *
 * 卡片上全是白字，而渐变色值是分类的身份标记（为什么不跟主题见 [GradientColors] 的文档），
 * 所以这里不管「跟不跟主题」，只钉住一件事：白字压在渐变的任何位置上都要可读。
 *
 * 护栏分两档：
 * - **[heroGradients] 是用户要求恢复的浅色视觉效果**，白字对比度刻意放宽到
 *   [HERO_READABLE]（低于 WCAG AA，属有意取舍 —— 比文本色淡到认不出的退化强）。
 * - **[aaGradients]（登录引导卡、玫瑰提示卡）仍维持深色**，白字必须保住 AA。
 *
 * AA 护栏有来历：原先的热门橙 #E6AA35 上白字只有 2.07:1，
 * 六张卡片里五张不合格，而且没有任何测试会红。这部分由登录/玫瑰档继续兜住。
 */
class DiscoverPaletteTest {

    /** 浅色 hero 卡（热门/即将上映/推荐/豆瓣新片/热门片单）—— 只守最底线可读。 */
    private val heroGradients: List<Pair<String, GradientColors>> = listOf(
        "热门" to DiscoverPopularGradient,
        "即将上映" to DiscoverUpcomingGradient,
        "推荐" to DiscoverRecommendGradient,
        "豆瓣新片" to DiscoverDoubanGradient,
        "热门片单" to DiscoverListsGradient
    )

    /** 深色引导/提示卡（豆瓣登录引导、Trakt 登录引导、玫瑰提示）—— 白字须保 AA。 */
    private val aaGradients: List<Pair<String, GradientColors>> = listOf(
        "豆瓣登录引导" to DiscoverDoubanLoginGradient,
        "Trakt登录引导" to DiscoverTraktLoginGradient,
        "玫瑰提示卡" to DiscoverRoseGradient
    )

    private val allGradients = heroGradients + aaGradients

    /**
     * 沿整条渐变采样，而不是只验两个端点。
     *
     * 采样按 sRGB 逐通道混色，这是 [androidx.compose.ui.graphics.Brush.linearGradient]
     * 在 Android 上最终落到的 `android.graphics.LinearGradient` 的做法。
     *
     * 刻意不用 Compose 的 `lerp(Color, Color, Float)` 取样：那个函数走 Oklab，
     * 中间段会比两端亮（热门橙的 0.5 处是 #AC6424，比两端都亮），
     * 拿它当基准会把色板往「为一条没人走的插值路径让步」的方向压。
     *
     * 顺带说明为什么不能只验端点：端点最坏这个直觉只在逐通道线性插值下成立 ——
     * 那时通道值落在两端之间，sRGB 到线性的转换单调，亮度也落在两端之间。
     * 换个插值空间这个前提就没了，所以直接采样比推理省心。
     */
    @Test
    fun `沿整条渐变采样白字都达到2比1可读底线`() {
        val threshold = HERO_READABLE
        for ((name, gradient) in heroGradients) {
            for (step in 0..RAMP_STEPS) {
                val fraction = step.toFloat() / RAMP_STEPS
                val sample = gradient.sampleSrgb(fraction)
                val ratio = contrast(Color.White, sample)
                assertTrue(
                    "$name 在 $fraction 处的 ${sample.hex()} 上白字只有 " +
                        "${"%.2f".format(ratio)}:1，低于浅色 hero 的可读底线 $threshold:1。" +
                        "若确需更浅，请同步上调阈值或改用深色档",
                    ratio >= threshold
                )
            }
        }
    }

    @Test
    fun `深色引导与提示卡白字保持AA`() {
        for ((name, gradient) in aaGradients) {
            for (step in 0..RAMP_STEPS) {
                val fraction = step.toFloat() / RAMP_STEPS
                val sample = gradient.sampleSrgb(fraction)
                val ratio = contrast(Color.White, sample)
                assertTrue(
                    "$name 在 $fraction 处的 ${sample.hex()} 上白字只有 " +
                        "${"%.2f".format(ratio)}:1，需要 $AA_NORMAL:1。" +
                        "压暗端点 —— 保住色相、只动明度",
                    ratio >= AA_NORMAL
                )
            }
        }
    }

    @Test
    fun `每条渐变的两端不是同一个色值`() {
        for ((name, gradient) in allGradients) {
            assertTrue(
                "$name 的两端都是 ${gradient.start.hex()}，渐变退化成单色了",
                gradient.start != gradient.end
            )
        }
    }

    /** shader 的混色方式：在 sRGB 编码值上逐通道线性插值。 */
    private fun GradientColors.sampleSrgb(fraction: Float): Color = Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction
    )

    private fun Color.hex(): String =
        "#%02X%02X%02X".format(
            (red * 255).roundToInt(),
            (green * 255).roundToInt(),
            (blue * 255).roundToInt()
        )

    private companion object {
        /** 采样密度。8 段在 0.125 一档，比渐变卡片的实际像素宽度粗得多，但足够抓住峰值。 */
        const val RAMP_STEPS = 8

        /** 浅色 hero 卡的白字可读底线（低于 WCAG AA，浅色视觉下的刻意取舍）。 */
        const val HERO_READABLE = 2f
    }
}