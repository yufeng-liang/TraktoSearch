package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 12 张卡片的文字都必须读得出来。
 *
 * 这套断言是防回归用的：只要有人把某个时代主色改浅、或者把压暗那一步绕过去，
 * 这里就红 —— 而肉眼在 94 秒的动画里很难发现某一张卡片的曲目名淡了。
 */
class SwiftieEraContrastTest {

    private fun ratioOf(era: SwiftieEra, color: androidx.compose.ui.graphics.Color, alpha: Float) =
        SwiftieEraContrast.cardBackground(era.mainColor).let { background ->
            SwiftieEraContrast.contrastRatio(
                SwiftieEraContrast.composite(color, alpha, background),
                background
            )
        }

    @Test
    fun everyEraBodyTextMeetsAaOnItsCard() {
        SwiftieErasData.ALL.forEach { era ->
            val colors = SwiftieEraTextColors(era)
            assertThat(ratioOf(era, colors.body, 1f))
                .isAtLeast(SwiftieEraContrast.AA_SMALL)
        }
    }

    @Test
    fun everyEraNumberAndDateMeetAaAfterTheirAlpha() {
        SwiftieErasData.ALL.forEach { era ->
            val colors = SwiftieEraTextColors(era)
            assertThat(ratioOf(era, colors.number, SwiftieEraContrast.NUMBER_ALPHA))
                .isAtLeast(SwiftieEraContrast.AA_SMALL)
            assertThat(ratioOf(era, colors.date, SwiftieEraContrast.DATE_ALPHA))
                .isAtLeast(SwiftieEraContrast.AA_SMALL)
        }
    }

    @Test
    fun alreadyDarkErasAreLeftUntouched() {
        // reputation / Midnights / TTPD 本来就够黑，压暗那一步不该动它们，
        // 否则「reputation 是纯黑」这个时代特征就没了
        listOf(5, 9, 10).forEach { index ->
            val era = SwiftieErasData.ALL[index]
            assertThat(SwiftieEraTextColors(era).body).isEqualTo(era.textColor)
        }
    }

    @Test
    fun palePresetsGetDarkenedButKeepTheirHue() {
        // 1989 的淡天蓝压暗后仍必须是蓝的：色相不动、只降明度
        val nineteenEightyNine = SwiftieErasData.ALL[4]
        val body = SwiftieEraTextColors(nineteenEightyNine).body
        assertThat(body).isNotEqualTo(nineteenEightyNine.textColor)
        assertThat(SwiftieEraContrast.luminance(body))
            .isLessThan(SwiftieEraContrast.luminance(nineteenEightyNine.textColor))
        assertThat(body.blue).isGreaterThan(body.red)
        assertThat(body.blue).isGreaterThan(body.green)
    }
}
