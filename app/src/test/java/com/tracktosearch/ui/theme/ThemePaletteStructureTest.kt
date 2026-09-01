package com.tracktosearch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.data.util.mcu.hct.Hct
import kotlin.math.abs
import kotlin.math.min
import org.junit.Test

/**
 * 配色体系的结构约束：色调之间够不够分得开、surface 阶梯有没有排错、
 * 票根主色的两处真值有没有漂移。
 *
 * 和 [ThemeColorContrastTest] 分开是因为这里测的是「色板长什么样」而不是「读不读得清」——
 * 两类问题的修法不一样，红在哪一边一眼能看出来。
 */
class ThemePaletteStructureTest {

    // ==================== 色调之间不能是重色 ====================

    /**
     * 去重回归。曾经 14 个色调里有 4 对色相只差 2°-9° 且彩度接近，
     * 在弹窗里挨着看就是同一个颜色（麦田金黄/干草堆金、睡莲绿/日本桥绿、
     * 鲁昂蓝紫/睡莲紫、教堂蓝灰/星夜蓝）。
     *
     * 判定用「或」而不是只看色相：教堂蓝灰和星夜蓝色相只差几度，
     * 但一个彩度 9 一个 34，一个基本是灰一个是饱和蓝，肉眼分得很清。
     * 所以彩度差足够大也算过 —— 这条约束描述的是「能不能区分」，
     * 不是「色相必须隔多远」。
     */
    @Test
    fun 任意两个色调要么色相拉开要么彩度拉开() {
        val accents = MonetAccent.entries
        for (i in accents.indices) {
            for (j in i + 1 until accents.size) {
                val a = Hct.fromInt(accents[i].light.toArgb())
                val b = Hct.fromInt(accents[j].light.toArgb())
                val hueGap = hueDistance(a.hue, b.hue)
                val chromaRatio = maxOf(a.chroma, b.chroma) / minOf(a.chroma, b.chroma)
                assertWithMessage(
                    "${accents[i].name} 和 ${accents[j].name} 太像了：" +
                        "色相差 ${"%.0f".format(hueGap)}°，彩度 ${"%.0f".format(a.chroma)} vs " +
                        "${"%.0f".format(b.chroma)}"
                ).that(hueGap >= MIN_HUE_GAP || chromaRatio >= MIN_CHROMA_RATIO).isTrue()
            }
        }
    }

    @Test
    fun 色调按色相升序排列() {
        // 设置页色块网格直接按 entries 顺序铺，乱序就排不出色环
        val hues = MonetAccent.entries.map { Hct.fromInt(it.light.toArgb()).hue }
        assertThat(hues).isInOrder()
    }

    @Test
    fun 色板里有一个近中性色可选() {
        // 「不想要颜色」得有得选。少了这个只能退回壁纸取色
        val lowestChroma = MonetAccent.entries.minOf { Hct.fromInt(it.light.toArgb()).chroma }
        assertThat(lowestChroma).isLessThan(MAX_NEUTRAL_CHROMA)
    }

    // ==================== surface 阶梯 ====================

    /**
     * 阶梯必须单调：浅色档层级越高越暗，深色档越高越亮。
     *
     * 排错的典型症状是对话框和页面底谁在上谁在下反了，看起来像是塌进去一块。
     * 漏设某一级则会拿到 Material 3 的紫调基线中性色，在暖色主题上是块冷补丁 ——
     * 那种情况这条也会红，因为基线值和主题的阶梯不在同一条线上。
     */
    @Test
    fun 表面阶梯按层级单调() {
        for (accent in MonetAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = schemeFor(accent, dark)
                val rungs = listOf(
                    "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                    "surfaceContainerLow" to scheme.surfaceContainerLow,
                    "surfaceContainer" to scheme.surfaceContainer,
                    "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                    "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                )
                for (k in 1 until rungs.size) {
                    val prev = rungs[k - 1].second.luminance()
                    val cur = rungs[k].second.luminance()
                    val message = "${accent.name} dark=$dark 的 ${rungs[k].first} " +
                        "相对 ${rungs[k - 1].first} 方向反了"
                    if (dark) {
                        assertWithMessage(message).that(cur).isGreaterThan(prev)
                    } else {
                        assertWithMessage(message).that(cur).isLessThan(prev)
                    }
                }
            }
        }
    }

    /**
     * 卡片压在页面上、填充输入框压在卡片上，这两处必须看得出边界。
     *
     * 这两个场景在本项目里是硬绑定的：对话框和卡片显式用 surfaceVariant（70+ 处），
     * 填充输入框吃 Material 3 默认的 surfaceContainerHighest（20 处）。
     * 底部弹层和对话框有 scrim 兜底所以不在这条里，输入框没有任何兜底。
     */
    @Test
    fun 卡片和输入框在各自底色上看得出边界() {
        for (accent in MonetAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = schemeFor(accent, dark)
                assertWithMessage("${accent.name} dark=$dark 的卡片和页面底分不开")
                    .that(contrast(scheme.surfaceVariant, scheme.background))
                    .isAtLeast(MIN_SURFACE_SEPARATION)
                assertWithMessage("${accent.name} dark=$dark 的输入框和卡片分不开")
                    .that(contrast(scheme.surfaceContainerHighest, scheme.surfaceVariant))
                    .isAtLeast(MIN_SURFACE_SEPARATION)
            }
        }
    }

    // ==================== 票根主色的单一真值 ====================

    /**
     * [MonetAccent.VINTAGE_TICKET] 的两个种子色同时喂桌面小组件和设置页色块，
     * `vintageTicketColorScheme` 必须读同一份而不是另写字面量 ——
     * 写两遍的话改一处就漂移，小组件和 app 内的主色对不上。
     */
    @Test
    fun 票根主题的主色取自枚举种子色() {
        assertThat(vintageTicketColorScheme(dark = false).primary)
            .isEqualTo(MonetAccent.VINTAGE_TICKET.light)
        assertThat(vintageTicketColorScheme(dark = true).primary)
            .isEqualTo(MonetAccent.VINTAGE_TICKET.dark)
    }

    /**
     * 票根是暖纸主题，整套 surface 都得是暖的。
     *
     * 漏设任何一级会拿到 Material 3 的紫调基线（HCT 色相约 300°），
     * 这条就会抓住它 —— 单看单调性测不出来，因为基线自己也是单调的。
     */
    @Test
    fun 票根主题的每一级表面都是暖色() {
        for (dark in listOf(false, true)) {
            val scheme = vintageTicketColorScheme(dark)
            val surfaces = mapOf(
                "background" to scheme.background,
                "surface" to scheme.surface,
                "surfaceVariant" to scheme.surfaceVariant,
                "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                "surfaceBright" to scheme.surfaceBright,
                "surfaceDim" to scheme.surfaceDim,
            )
            for ((name, color) in surfaces) {
                val hct = Hct.fromInt(color.toArgb())
                // 近白/近黑的色相是纯噪声：纯白 #FFFFFF 在 HCT 里报出的是色相 209°、彩度约 3，
                // 按色相判会误报。这两端本来也不可能是紫调基线，直接跳过。
                if (hct.tone >= NEAR_EXTREME_TONE_HIGH || hct.tone <= NEAR_EXTREME_TONE_LOW) continue
                if (hct.chroma < 1.0) continue
                val message = "票根 dark=$dark 的 $name 色相 ${"%.0f".format(hct.hue)}° 不是暖色，" +
                    "大概是漏设了这一级、落回了 Material 3 紫调基线"
                assertWithMessage(message).that(hct.hue).isAtLeast(WARM_HUE_MIN)
                assertWithMessage(message).that(hct.hue).isAtMost(WARM_HUE_MAX)
            }
        }
    }

    @Test
    fun 票根主题的错误色不跟着主题走() {
        // errorContainer 曾经接在主色的 TonalPalette 上，「确认删除」会变成棕色容器
        assertThat(vintageTicketColorScheme(dark = false).errorContainer)
            .isEqualTo(ErrorContainerLight)
        assertThat(vintageTicketColorScheme(dark = true).errorContainer)
            .isEqualTo(ErrorContainerDark)
        assertThat(monetColorScheme(MonetAccent.STARRY_NIGHT.light, dark = false).errorContainer)
            .isEqualTo(ErrorContainerLight)
    }

    private companion object {
        /** 色相差到这个度数就算分得开（被删的四对都在 2°-9°）。 */
        const val MIN_HUE_GAP = 12.0

        /** 色相接近但彩度差到这个倍数也算分得开（灰 vs 饱和色）。 */
        const val MIN_CHROMA_RATIO = 1.8

        /**
         * 「近中性」的彩度上限。用的是 HCT/CAM16 彩度，比 CIELAB 的 C* 数值大一截 ——
         * 教堂蓝灰 #6A7180 的 Lab C* 是 9.2，HCT chroma 是 13.2，同一个颜色两套刻度。
         * 阈值按 HCT 定，因为这里读的是 [Hct.chroma]。
         */
        const val MAX_NEUTRAL_CHROMA = 16.0

        /** 相邻表面的最低可辨对比度。M3 自己的阶梯每级也就这个量级。 */
        const val MIN_SURFACE_SEPARATION = 1.10

        /**
         * HCT 暖色区间：赭红墨到牛皮纸都落在这里。
         *
         * 区间给得宽是有意的 —— 这条要抓的是「漏设槽位、落回 Material 3 紫调基线」，
         * 那个偏差有 200° 之大。而近中性的纸色彩度只有 3-5，色相在数值上本来就飘
         * （#F7F3EC 算出来 110°），卡在 110 这种边界上只会测出噪声。
         */
        const val WARM_HUE_MIN = 15.0
        const val WARM_HUE_MAX = 125.0

        /**
         * 超出这两档就是近白/近黑，色相是噪声，不参与暖色判定。
         *
         * 卡在 98.5 / 3.5 而不是取整：Material 3 紫调基线的两个极端档
         * （浅色 surfaceBright #FEF7FF tone 98.0、深色 surfaceContainerLowest #0F0D13 tone 3.9）
         * 必须仍落在检查范围内，否则这条测试就抓不住「漏设最外侧那一级」。
         */
        const val NEAR_EXTREME_TONE_HIGH = 98.5
        const val NEAR_EXTREME_TONE_LOW = 3.5

        fun schemeFor(accent: MonetAccent, dark: Boolean): ColorScheme =
            if (accent == MonetAccent.VINTAGE_TICKET) {
                vintageTicketColorScheme(dark)
            } else {
                monetColorScheme(if (dark) accent.dark else accent.light, dark)
            }

        fun contrast(a: Color, b: Color): Double {
            val la = a.luminance().toDouble()
            val lb = b.luminance().toDouble()
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        /** 色相是环形的，359° 和 1° 只差 2°。 */
        fun hueDistance(a: Double, b: Double): Double {
            val d = abs(a - b) % 360.0
            return min(d, 360.0 - d)
        }
    }
}
