package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 12 张卡片的文字都必须读得出来。
 *
 * 这套断言是防回归用的：只要有人把某个时代主色改浅、或者把压暗那一步绕过去，
 * 这里就红 —— 而肉眼在 109 秒的动画里很难发现某一张卡片的曲目名淡了。
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
    fun everyEraCardUsesOneOpaqueEraFill() {
        SwiftieErasData.ALL.forEach { era ->
            val fill = SwiftieEraContrast.cardFill(era.mainColor)
            assertThat(fill.alpha).isEqualTo(1f)
            assertThat(fill).isEqualTo(
                SwiftieEraContrast.composite(era.mainColor, 0.10f, Color.White)
            )
        }
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
            assertThat(ratioOf(era, colors.trackSuffix, SwiftieEraContrast.TRACK_SUFFIX_ALPHA))
                .isAtLeast(SwiftieEraContrast.AA_SMALL)
        }
    }

    @Test
    fun trackSuffixCompositesToItsOwnHalftoneLayer() {
        // 后缀在浅色主色上压到 0.70 后合成亮度与正文接近，靠去饱和体现层级；
        // 但它必须单独算基色，不能复用正文色再压 alpha（那会掉到 AA 以下）
        SwiftieErasData.ALL.forEach { era ->
            val colors = SwiftieEraTextColors(era)
            val background = SwiftieEraContrast.cardBackground(era.mainColor)
            val suffix = SwiftieEraContrast.composite(
                colors.trackSuffix,
                SwiftieEraContrast.TRACK_SUFFIX_ALPHA,
                background
            )
            val body = SwiftieEraContrast.composite(colors.body, 1f, background)
            assertThat(SwiftieEraContrast.contrastRatio(suffix, background))
                .isAtLeast(SwiftieEraContrast.AA_SMALL)
            // 四张 TV 主色浅，后缀与正文亮度几乎相等；但两者不能是完全同一个实色
            assertThat(suffix).isNotEqualTo(body)
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
    fun loverDateUsesReleaseDateUntilRewind() {
        val lover = SwiftieErasData.ALL[SwiftieErasData.LOVER_INDEX]
        assertThat(swiftieEraDateLabel(SwiftieErasData.LOVER_INDEX, lover.releaseDate, false))
            .isEqualTo(lover.releaseDate)
        assertThat(swiftieEraDateLabel(SwiftieErasData.LOVER_INDEX, lover.releaseDate, true))
            .isEqualTo("TS7")
    }

    @Test
    fun nonLoverDatesStayAsReleaseDatesDuringRewind() {
        SwiftieErasData.ALL.filterIndexed { index, _ -> index != SwiftieErasData.LOVER_INDEX }
            .forEach { era ->
                val index = SwiftieErasData.ALL.indexOf(era)
                assertThat(swiftieEraDateLabel(index, era.releaseDate, true))
                    .isEqualTo(era.releaseDate)
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

    @Test
    fun brighteningOnlyEverGoesUp() {
        // readableOnDark 的方向不能反：轴上那些字压在深底上，往下压等于消失
        SwiftieErasData.ALL.forEach { era ->
            val lifted = SwiftieEraContrast.readableOnDark(era.mainColor, Color.Black)
            assertThat(SwiftieEraContrast.luminance(lifted))
                .isAtLeast(SwiftieEraContrast.luminance(era.mainColor))
        }
    }

    @Test
    fun brighteningLeavesAlreadyReadableColorsAlone() {
        // 已达标就原样返回，不许「顺手再提亮一点」—— 那会让浅色主色在深底上一律发白
        val lover = SwiftieErasData.ALL[SwiftieErasData.LOVER_INDEX]
        assertThat(SwiftieEraContrast.readableOnDark(lover.mainColor, Color.Black))
            .isEqualTo(lover.mainColor)
    }

    /**
     * 轴线、播放头与 TS1-12 标签压在**页面背景的下缘**上，配色由
     * [SwiftieEraStage.darkBottomInk] 二选一。这一条同时守两件事：
     * 校正函数本身，以及 `STAGE` 里那 12 个手填的极性 —— 填反了会让二分朝着
     * 底色自己的方向走，怎么分都到不了 4.5:1，这里当场红。
     */
    @Test
    fun axisInkMeetsAaOnEveryBackdropBottom() {
        SwiftieErasData.ALL.forEachIndexed { index, era ->
            val stage = SwiftieErasData.STAGE[index]
            val bottom = stage.backdropColors.last()
            val ink = if (stage.darkBottomInk) {
                SwiftieEraContrast.readable(era.mainColor, bottom)
            } else {
                SwiftieEraContrast.readableOnDark(era.mainColor, bottom)
            }
            // 带上专辑名：只报一个比值的话，12 张里到底哪一张红了还得自己二分去找
            assertWithMessage("${era.name} 的轴墨压在末档 ${bottom.value.toString(16)} 上")
                .that(SwiftieEraContrast.contrastRatio(ink, bottom))
                .isAtLeast(SwiftieEraContrast.AA_SMALL)
        }
    }
}
