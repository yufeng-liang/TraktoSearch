package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 12 张卡片的文字都必须读得出来。
 *
 * 这套断言是防回归用的：只要有人把某个时代主色改浅、或者把压暗那一步绕过去，
 * 这里就红 —— 而肉眼在 96.3 秒的动画里很难发现某一张卡片的曲目名淡了。
 */
class SwiftieEraContrastTest {

    private fun ratioOf(era: SwiftieEra, color: Color, alpha: Float) =
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

    @Test
    fun cardBackgroundModelsTheMotifLayer() {
        // cardBackground 必须把母题层算进去。曾经漏掉它，于是 folklore 的松树、
        // Showgirl 的羽毛铺在曲目名底下，实测把 4.5:1 拉到 3.0–3.5:1 而测试全绿。
        //
        // 判据：对深色主色的时代，含母题的那个候选一定比只有薄底的那档更暗，
        // 所以 cardBackground 的结果必须严格暗于「白纸 + 薄底」。
        val paper = SwiftieEraContrast.composite(Color.White, 0.86f, Color(0xFFFBE4EE))
        listOf(
            SwiftieErasData.ALL[5],  // reputation #111111
            SwiftieErasData.ALL[7],  // folklore #8C8C8C
            SwiftieErasData.ALL[11]  // Showgirl #E8620F
        ).forEach { era ->
            val tintOnly = SwiftieEraContrast.composite(era.mainColor, 0.10f, paper)
            assertThat(SwiftieEraContrast.luminance(SwiftieEraContrast.cardBackground(era.mainColor)))
                .isLessThan(SwiftieEraContrast.luminance(tintOnly))
        }
    }

    @Test
    fun darkeningNeverCollapsesToBlack() {
        // 底色模型变暗之后，压暗那一步不能滑到「一律纯黑」—— 那样 12 个时代
        // 的辨识度就全丢了，而对比度断言仍然会绿
        SwiftieErasData.ALL.forEach { era ->
            val body = SwiftieEraTextColors(era).body
            if (SwiftieEraContrast.luminance(era.textColor) > 0.05f) {
                assertThat(SwiftieEraContrast.luminance(body)).isGreaterThan(0.01f)
            }
        }
    }
}
