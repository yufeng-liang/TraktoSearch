package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
        }
    }

    @Test
    fun tvBadgeIsAVisibleButSoftMarkOnEveryEraWithTv() {
        // 标签底相对纸色的比值有两个边界，都要守：
        //   下界 —— 低于它标签与纸面糊在一起，看不见边界；
        //   上界 —— 高于它读作「这一行被选中」的实心色块，抢过曲目名。
        // 需求方在真机上过的第三轮（2026-09-20）把它定在「一半浓度」，
        // 所以这里判的是「看得见但柔和」，而不是能不能达到 AA
        val erasWithTv = SwiftieErasData.ALL.filter { era ->
            era.tracks.any { it.contains(TV_SUFFIX) }
        }
        assertWithMessage("带 (TV) 的专辑").that(erasWithTv).isNotEmpty()
        erasWithTv.forEach { era ->
            val card = SwiftieEraContrast.cardFill(era.mainColor)
            val ratio = SwiftieEraContrast.contrastRatio(
                SwiftieEraTextColors(era).badgeFill,
                card
            )
            assertWithMessage("${era.name} 的 TV 标签底")
                .that(ratio).isAtLeast(1.6f)
            assertWithMessage("${era.name} 的 TV 标签底压过了曲目名")
                .that(ratio).isLessThan(3.0f)
        }
    }

    @Test
    fun tvBadgeKnockoutIsTheCardPaperItself() {
        // 字身必须**就是**卡片填充色 —— 那是「镂空露出纸面」这个语义本身。
        // 换成别的浅色就成了另一种设计，而对比度断言仍然可能绿
        SwiftieErasData.ALL.forEach { era ->
            assertThat(SwiftieEraTextColors(era).badgeKnockout)
                .isEqualTo(SwiftieEraContrast.cardFill(era.mainColor))
        }
    }

    @Test
    fun tvBadgeStaysLighterThanTheTrackTitle() {
        // 标签是**标记**不是按钮：底必须比曲目名浅，才不会读成「这一行被选中」。
        // 上一条判的是底 vs 纸，这一条判的是底 vs 正文 —— 两件事
        SwiftieErasData.ALL.forEach { era ->
            val colors = SwiftieEraTextColors(era)
            assertWithMessage("${era.name} 的 TV 标签底比曲目名还深")
                .that(SwiftieEraContrast.luminance(colors.badgeFill))
                .isGreaterThan(SwiftieEraContrast.luminance(colors.body))
        }
    }

    @Test
    fun tvBadgeKeepsTheEraHue() {
        // 标签底是主色压深再退回来的，不能为了对比度被压成一律的灰：
        // 四个有 TV 的时代仍要认得出是金 / 紫 / 红 / 蓝
        listOf(
            SwiftieErasData.ALL[1],  // Fearless 金黄
            SwiftieErasData.ALL[2],  // Speak Now 紫
            SwiftieErasData.ALL[3],  // Red 红
            SwiftieErasData.ALL[4]   // 1989 淡天蓝
        ).forEach { era ->
            val fill = SwiftieEraTextColors(era).badgeFill
            val card = SwiftieEraContrast.cardFill(era.mainColor)
            // 与纸面相比，三个通道里至少要有一个明显偏离，否则就是没上色。
            // 单看某一个通道会误判：1989 的蓝主要压在红/绿通道上
            val delta = maxOf(
                Math.abs(fill.red - card.red),
                Math.abs(fill.green - card.green),
                Math.abs(fill.blue - card.blue)
            )
            assertWithMessage("${era.name} 的 TV 标签底与卡片填充色同色了")
                .that(delta).isGreaterThan(0.02f)
        }
    }

    @Test
    fun tvBadgeGeometryScalesWithRowHeight() {
        // 标签的尺寸全部按行高比例给：大屏 16dp、小屏压到 9dp 都要成立。
        // 写死 dp 的话小屏上标签会比行还高，上下边被布局框裁掉
        val corners = listOf(16.dp, 12.dp, 9.dp).map { row ->
            val height = row * SwiftieEraContrast.TV_BADGE_HEIGHT_RATIO
            val corner = height * SwiftieEraContrast.TV_BADGE_CORNER_RATIO
            // 圆角不能超过半高，否则 RoundedCornerShape 会把标签画成胶囊 ——
            // 需求方明确否掉了全胶囊
            assertWithMessage("行高 $row 的圆角")
                .that(corner).isLessThan(height / 2f)
            corner
        }
        // 圆角随行高单调增，且都是「小圆角」而不是胶囊
        assertThat(corners[0]).isGreaterThan(corners[1])
        assertThat(corners[1]).isGreaterThan(corners[2])
    }

    @Test
    fun tvBadgeTypeIsAStepDownFromTheTrackTitle() {
        // 需求方在真机上指出标签「太显眼、和曲目名不协调」——
        // 标签字号必须**明显小于**曲目名，曲名才是这一行的主体。
        // 这条守的就是那个主次关系：两者同大时（曾经都是 0.75）它会红
        val title = TRACK_TITLE_FONT_RATIO
        val badge = SwiftieEraContrast.TV_BADGE_FONT_RATIO
        assertWithMessage("标签字号 $badge 相对曲目名 $title")
            .that(badge).isLessThan(title * 0.8f)
        // 但也不能小到读不出：至少要有曲名的三分之二
        assertThat(badge).isAtLeast(title * 0.66f)
        // 标签高与字号同比例缩放 —— 只缩字不缩盒会让字在盒子里吊着
        val height = SwiftieEraContrast.TV_BADGE_HEIGHT_RATIO
        assertWithMessage("标签高 $height 与字号 $badge 应当同档")
            .that(height).isLessThan(0.65f)
    }

    @Test
    fun tvBadgeTextIsPlainTvAndSuffixStillParses() {
        // 标签外形已经与曲名分开，括号是多余的 —— 屏幕上印 TV。
        // 但**解析**用的后缀仍然必须是带括号与空格的形态：少一个字符就切不下来，
        // 于是那 36 首带标记的曲目会整个变成普通曲名（标记静默消失）
        assertThat(TV_BADGE_TEXT).isEqualTo("TV")
        assertThat(TV_SUFFIX).isEqualTo(" (TV)")
        assertThat(TV_SUFFIX).contains(TV_BADGE_TEXT)
        // 曲目数据里的每一条标记都必须能被这个后缀切下来
        val marked = SwiftieErasData.ALL.flatMap { it.tracks }.filter { it.contains("(TV)") }
        assertThat(marked).isNotEmpty()
        marked.forEach { title ->
            assertWithMessage("「$title」切不出 TV 后缀")
                .that(title.lastIndexOf(TV_SUFFIX)).isAtLeast(0)
            // 切剩的曲名不能是空的：空标题会排出一个空 Text，标签孤零零地贴在行尾
            assertThat(title.substring(0, title.lastIndexOf(TV_SUFFIX))).isNotEmpty()
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
