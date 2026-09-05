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
 * 配色的数值约束。纯 JVM，不需要 Robolectric —— 走的都是 Color 的值类运算和
 * TonalPalette 的 Hct 换算，没有 Android 运行时依赖。
 *
 * 这些测试盯的是「改色值时容易顺手破坏的东西」：
 * - 前景色够不够亮：`onPrimary` 阈值曾经写成 0.5，害得黄金色系按钮只有 2.30:1
 * - surface 阶梯有没有漏设：漏一个就拿到 Material 3 紫调基线，落在暖色主题上是块冷补丁
 * - 色调之间有没有重复：删掉的三个当初就是 2°-9° 色差的重色，别再加回来
 * - 票根主色的两处真值有没有漂移：枚举喂桌面小组件，scheme 喂 app 内
 *
 * 不随主题走的固定色板（状态 / 品牌 / 反馈）在 [ThemeSemanticPaletteTest]，
 * 色板本身的结构约束在 [ThemePaletteStructureTest]。
 */
class ThemeColorContrastTest {

    // ==================== onPrimary / onSecondary 可读性 ====================

    @Test
    fun 每个色调的按钮前景色在两档下都达到AA() {
        for (case in allSchemes) {
            val ratio = contrast(case.scheme.onPrimary, case.scheme.primary)
            assertWithMessage("${case.label} 的 onPrimary 对比度不足")
                .that(ratio).isAtLeast(AA_NORMAL)
        }
    }

    @Test
    fun 每个色调的次要色前景在两档下都达到AA() {
        for (case in allSchemes) {
            assertWithMessage("${case.label} 的 onSecondary 对比度不足")
                .that(contrast(case.scheme.onSecondary, case.scheme.secondary)).isAtLeast(AA_NORMAL)
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
        for (case in allSchemes) {
            val scheme = case.scheme
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
                assertWithMessage("${case.label} 的 $name 上文字对比度不足")
                    .that(contrast(foreground, surfaceColor)).isAtLeast(AA_NORMAL)
            }
        }
    }

    @Test
    fun 容器色和自己的前景色成对达到AA() {
        for (case in allSchemes) {
            val scheme = case.scheme
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
                assertWithMessage("${case.label} 的 $name 对比度不足")
                    .that(contrast(pair.first, pair.second)).isAtLeast(AA_NORMAL)
            }
        }
    }
}
