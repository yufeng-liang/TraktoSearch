package com.tracktosearch.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.theme.ThemeTestSupport.AA_NORMAL
import com.tracktosearch.ui.theme.ThemeTestSupport.allSchemes
import com.tracktosearch.ui.theme.ThemeTestSupport.contrast
import org.junit.Test

/**
 * 不随主题变化的那几套固定色板：影视状态、评分平台品牌色、反馈类型/状态。
 *
 * 这些色不参与主题化（换了就丢识别性），所以它们**必须自己**在每个主题的底上都读得清 ——
 * 这正是之前漏测的一块：主题色那两个测试类只遍历 colorScheme 里的槽位，
 * 固定色板一个没测，于是状态绑带的白字（7 个里 5 个不到 AA）和
 * Metacritic 橙当文字（浅卡片上 1.72:1）一直没被发现。
 *
 * 判定分两类，别混：
 * - **当底色**（状态绑带、IMDb/TMDB 字标）：前景取 [onColorFor]，验它够不够 AA。
 * - **当文字**（豆瓣/烂番茄/Metacritic 平台名、反馈类型色）：先过 [readableOn] 调明度，
 *   再验结果压在卡片上够不够 AA。
 */
class ThemeSemanticPaletteTest {

    // ==================== 影视状态色：当底色用 ====================

    /**
     * 状态绑带是 10sp Bold 白字压在状态色上，原先整排硬编码 [Color.White]，
     * 7 个色里 5 个不到 AA（制作中的橙 #FF9800 只有 2.16:1）。
     * 现在走 [onColorFor]，这条验的是「按亮度选完黑白之后确实够了」。
     */
    @Test
    fun 每个影视状态色配上自动前景色都达到AA() {
        for ((name, color) in statusColors) {
            val ink = onColorFor(color)
            assertWithMessage("状态色 $name 配上 ${if (ink == Color.Black) "黑" else "白"}字仍不足")
                .that(contrast(ink, color)).isAtLeast(AA_NORMAL)
        }
    }

    /**
     * 绑带底是 `alpha = 0.9f` 压在海报上，实际底色会朝海报偏 10%。
     * 状态色的亮度必须离 [WcagBlackWhiteCrossover] 足够远，否则这 10% 就能把黑白判反 ——
     * 未知状态原先用 Grey 600 (#757575)，亮度 0.1779 正好卡在交叉点上，两边都只有 4.6:1。
     */
    @Test
    fun 状态色亮度不卡在黑白交叉点上() {
        for ((name, color) in statusColors) {
            val gap = kotlin.math.abs(color.luminance() - WcagBlackWhiteCrossover)
            assertWithMessage(
                "状态色 $name 的亮度 ${"%.4f".format(color.luminance())} 离交叉点只有 " +
                    "${"%.4f".format(gap)}，半透明压在海报上会把黑白判反"
            ).that(gap).isAtLeast(MIN_CROSSOVER_MARGIN)
        }
    }

    // ==================== 品牌色：字标当底色用 ====================

    /**
     * IMDb 与 TMDB 画成「品牌色底 + 反色字标」，配套前景色是显式的 On* 常量。
     * TMDB 原先配白字只有 2.43:1。
     */
    @Test
    fun 品牌字标的配套前景色达到AA() {
        val wordmarks = mapOf(
            "IMDb" to (OnBrandImdb to BrandImdb),
            "TMDB" to (OnBrandTmdb to BrandTmdb),
        )
        for ((name, pair) in wordmarks) {
            assertWithMessage("$name 字标的前景色不足")
                .that(contrast(pair.first, pair.second)).isAtLeast(AA_NORMAL)
        }
    }

    // ==================== 品牌色 / 反馈色：当文字用 ====================

    /**
     * 豆瓣、烂番茄、Metacritic 画成「图标 + 品牌色平台名」，10sp Bold。
     * 原色全部不到 AA，靠 [readableOn] 压到卡片底上够读。
     *
     * 遍历所有主题是必要的：卡片底是 `surfaceVariant`，13 个色调 × 明暗两档
     * 有 26 种底色，某个主题下够了不代表都够。
     */
    @Test
    fun 品牌色当文字在每个主题的卡片上都达到AA() {
        for (case in allSchemes) {
            val card = case.scheme.surfaceVariant
            for ((name, brand) in textBrandColors) {
                val adjusted = readableOn(brand, card)
                assertWithMessage("${case.label} 的卡片上 $name 平台名不足")
                    .that(contrast(adjusted, card)).isAtLeast(AA_NORMAL)
            }
        }
    }

    /** 反馈类型/状态色走同一条 [readableOn]，见 FeedbackVisuals.feedbackAccent。 */
    @Test
    fun 反馈色当文字在每个主题的卡片上都达到AA() {
        for (case in allSchemes) {
            val card = case.scheme.surfaceVariant
            for ((name, base) in feedbackColors) {
                val adjusted = readableOn(base, card)
                assertWithMessage("${case.label} 的卡片上反馈色 $name 不足")
                    .that(contrast(adjusted, card)).isAtLeast(AA_NORMAL)
            }
        }
    }

    // ==================== readableOn 本身的性质 ====================

    @Test
    fun readableOn对已达标的颜色原样返回() {
        // 不该无意义地改色：够读就别动，动了就是白掉一点识别性
        val ink = Color(0xFF3E2A1E)
        val paper = Color(0xFFF7F3EC)
        assertThat(contrast(ink, paper)).isAtLeast(AA_NORMAL)
        assertThat(readableOn(ink, paper)).isEqualTo(ink)
    }

    @Test
    fun readableOn朝对比度更高的那一端推() {
        // 浅底往黑推、深底往白推。方向反了会越推越糊，对比度反而掉
        val metacriticOrange = Color(0xFFFF9500)
        val onLightPaper = readableOn(metacriticOrange, Color(0xFFF7F3EC))
        val onDarkPaper = readableOn(metacriticOrange, Color(0xFF141009))
        assertThat(onLightPaper.luminance()).isLessThan(metacriticOrange.luminance())
        assertThat(onDarkPaper.luminance()).isAtLeast(metacriticOrange.luminance())
    }

    /**
     * 目标够不到时要给出「能做到的最好结果」，也就是那一端的纯黑或纯白，而不是死循环。
     *
     * 这里刻意用 21:1 而不是 AA 的 4.5:1 —— **4.5 在任何底色上都够得到**：
     * 黑字够 AA 要求底色亮度 ≥ 0.175，白字够 AA 要求 ≤ 0.1833，两个区间是重叠的，
     * 没有哪个亮度会两边都不够。所以拿 4.5 根本触发不到这条兜底分支。
     * 21:1 只有纯黑压纯白才到得了，任何中间调底色都够不到。
     */
    @Test
    fun readableOn在目标够不到时退回最优端点() {
        val midGray = Color(0xFF7B7B7B)
        val base = Color(0xFF808080)
        val result = readableOn(base, midGray, targetContrast = MAX_POSSIBLE_CONTRAST)
        assertThat(result).isAnyOf(Color.Black, Color.White)
        assertThat(contrast(result, midGray)).isAtLeast(contrast(base, midGray))
    }

    private companion object {
        /**
         * 状态色离黑白交叉点至少要留这么多亮度余量。
         * 0.03 大约是绑带那 10% 海报混色能造成的最大亮度偏移。
         */
        const val MIN_CROSSOVER_MARGIN = 0.03f

        /** WCAG 对比度的理论上限（纯黑压纯白），任何中间调底色都够不到。 */
        const val MAX_POSSIBLE_CONTRAST = 21.0

        val statusColors = mapOf(
            "已上映/连载中" to StatusReleased,
            "制作中/后期" to StatusInProduction,
            "计划中" to StatusPlanned,
            "传闻中" to StatusRumored,
            "已取消" to StatusCanceled,
            "已完结" to StatusEnded,
            "未知" to StatusUnknown,
        )

        val textBrandColors = mapOf(
            "豆瓣" to BrandDouban,
            "烂番茄" to BrandRottenTomatoes,
            "Metacritic" to BrandMetacritic,
        )

        val feedbackColors = mapOf(
            "功能建议" to FeedbackFeature,
            "问题反馈" to FeedbackBug,
            "体验问题" to FeedbackUx,
            "其他" to FeedbackOther,
            "已回复" to FeedbackReplied,
            "开发者" to FeedbackDeveloper,
        )
    }
}
