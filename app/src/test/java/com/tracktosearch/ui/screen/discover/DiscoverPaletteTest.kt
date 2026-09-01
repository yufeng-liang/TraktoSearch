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
 * 所以这里不管「跟不跟主题」，只钉住一件事：白字压在渐变的任何位置上都要有 AA。
 *
 * 这层护栏是有来历的：原先的热门橙 #E6AA35 上白字只有 2.07:1，
 * 六张卡片里五张不合格，而且没有任何测试会红。
 *
 * 现有八条都是照着 AA 线卡着压暗的，最差 4.57:1 只剩 0.07 余量 ——
 * 想把哪个端点调亮一点，会先在这里红。
 */
class DiscoverPaletteTest {

    private val gradients: List<Pair<String, GradientColors>> = listOf(
        "热门" to DiscoverPopularGradient,
        "即将上映" to DiscoverUpcomingGradient,
        "推荐" to DiscoverRecommendGradient,
        "豆瓣新片" to DiscoverDoubanGradient,
        "热门片单" to DiscoverListsGradient,
        "豆瓣登录引导" to DiscoverDoubanLoginGradient,
        "Trakt登录引导" to DiscoverTraktLoginGradient,
        "玫瑰提示卡" to DiscoverRoseGradient
    )

    /**
     * 沿整条渐变采样，而不是只验两个端点。
     *
     * 采样按 sRGB 逐通道混色，这是 [androidx.compose.ui.graphics.Brush.linearGradient]
     * 在 Android 上最终落到的 `android.graphics.LinearGradient` 的做法。
     *
     * 刻意不用 Compose 的 `lerp(Color, Color, Float)` 取样：那个函数走 Oklab，
     * 中间段会比两端亮（热门橙的 0.5 处是 #AC6424，比 #C05526 和 #966F22 都亮），
     * 拿它当基准会把色板往「为一条没人走的插值路径让步」的方向压。
     *
     * 顺带说明为什么不能只验端点：端点最坏这个直觉只在逐通道线性插值下成立 ——
     * 那时通道值落在两端之间，sRGB 到线性的转换单调，亮度也落在两端之间。
     * 换个插值空间这个前提就没了，所以直接采样比推理省心。
     */
    @Test
    fun `沿整条渐变采样压白字都达到AA`() {
        for ((name, gradient) in gradients) {
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
        for ((name, gradient) in gradients) {
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
    }
}
