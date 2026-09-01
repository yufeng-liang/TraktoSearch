package com.tracktosearch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 配色的数值约束。纯 JVM，不需要 Robolectric —— 走的都是 Color 的值类运算和
 * TonalPalette 的 Hct 换算，没有 Android 运行时依赖。
 *
 * 这些测试盯的是「改色值时容易顺手破坏的东西」：
 * - 前景色够不够亮：`onPrimary` 阈值曾经写成 0.5，害得黄金色系按钮只有 2.30:1
 * - surface 阶梯有没有漏设：漏一个就拿到 Material 3 紫调基线，落在暖色主题上是块冷补丁
 * - 色调之间有没有重复：删掉的三个当初就是 2°-9° 色差的重色，别再加回来
 * - 票根主色的两处真值有没有漂移：枚举喂桌面小组件，scheme 喂 app 内
 */
class ThemeColorContrastTest {

    // ==================== onPrimary / onSecondary 可读性 ====================

    @Test
    fun 每个色调的按钮前景色在两档下都达到AA() {
        for (accent in MonetAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = schemeFor(accent, dark)
                val ratio = contrast(scheme.onPrimary, scheme.primary)
                assertWithMessage("${accent.name} dark=$dark 的 onPrimary 对比度不足")
                    .that(ratio).isAtLeast(AA_NORMAL)
            }
        }
    }

    @Test
    fun 每个色调的次要色前景在两档下都达到AA() {
        for (accent in MonetAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = schemeFor(accent, dark)
                assertWithMessage("${accent.name} dark=$dark 的 onSecondary 对比度不足")
                    .that(contrast(scheme.onSecondary, scheme.secondary)).isAtLeast(AA_NORMAL)
            }
        }
    }

    @Test
    fun onColorFor取黑白里对比度更高的那个() {
        // 阈值写错时这条最先红：0.5 会让干草堆金拿到白字
        val haystack = MonetAccent.HAYSTACK.light
        assertThat(onColorFor(haystack)).isEqualTo(Color.Black)
        assertThat(contrast(Color.Black, haystack)).isGreaterThan(contrast(Color.White, haystack))

        // 交叉点两侧各验一个：低于阈值该给白字
        val vintage = MonetAccent.VINTAGE_TICKET.light
        assertThat(vintage.luminance()).isLessThan(WcagBlackWhiteCrossover)
        assertThat(onColorFor(vintage)).isEqualTo(Color.White)
        assertThat(contrast(Color.White, vintage)).isGreaterThan(contrast(Color.Black, vintage))
    }

    // ==================== 文字压在每一级表面上 ====================

    @Test
    fun 正文和次要文字压在所有表面层级上都达到AA() {
        for (accent in MonetAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = schemeFor(accent, dark)
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
                for ((name, surfaceColor) in surfaces) {
                    // surfaceVariant 有自己的配对前景色，其余层级共用 onSurface
                    val foreground =
                        if (name == "surfaceVariant") scheme.onSurfaceVariant else scheme.onSurface
                    assertWithMessage("${accent.name} dark=$dark 的 $name 上文字对比度不足")
                        .that(contrast(foreground, surfaceColor)).isAtLeast(AA_NORMAL)
                }
            }
        }
    }

    @Test
    fun 容器色和自己的前景色成对达到AA() {
        for (accent in MonetAccent.entries) {
            for (dark in listOf(false, true)) {
                val scheme = schemeFor(accent, dark)
                val pairs = listOf(
                    "primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "tertiary" to (scheme.onTertiary to scheme.tertiary),
                    "errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
                    "error" to (scheme.onError to scheme.error),
                    "inverseSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
                )
                for ((name, pair) in pairs) {
                    assertWithMessage("${accent.name} dark=$dark 的 $name 对比度不足")
                        .that(contrast(pair.first, pair.second)).isAtLeast(AA_NORMAL)
                }
            }
        }
    }

    private companion object {
        /** WCAG AA 普通字号。按钮文字多是 labelLarge 14sp 不加粗，按普通字号要求。 */
        const val AA_NORMAL = 4.5

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
    }
}
