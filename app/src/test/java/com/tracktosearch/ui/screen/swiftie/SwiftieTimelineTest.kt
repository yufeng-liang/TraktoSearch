package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieTimelineTest {

    @Test
    fun segmentsSumToExactlyTheSoundtrackLength() {
        // swiftie_theme.ogg（Opus）的容器时长是 125.997979s，差一毫秒都算错开
        assertThat(SwiftieTimeline.FADE_OUT_START + SwiftieTimeline.FADE_OUT_MS)
            .isEqualTo(SwiftieTimeline.TOTAL_MS)
        assertThat(SwiftieTimeline.TOTAL_MS).isEqualTo(125_998L)
    }

    @Test
    fun theTwoAudioPinsAreExactlyWhereTheTrackSingsLover() {
        // 配乐 1:58–2:02 唱 Lover。这两个数字由实测配乐钉死，改动必须先改配乐
        assertThat(SwiftieTimeline.REWIND_START).isEqualTo(118_000L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_END).isEqualTo(123_000L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_START + SwiftieTimeline.LOVER_BLOOM_MS)
            .isEqualTo(SwiftieTimeline.LOVER_BLOOM_END)
    }

    @Test
    fun segmentBoundariesMatchTheSpec() {
        // 2026-09-19：1989 换豪华版 16 首，卡片段多 390ms；2026-09-20 再补四首版本差异，105400 → 107220
        assertThat(SwiftieTimeline.DIFFUSION_START).isEqualTo(400L)
        assertThat(SwiftieTimeline.ERAS_INTRO_START).isEqualTo(1_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_END).isEqualTo(107_220L)
        assertThat(SwiftieTimeline.SIGNATURE_START).isEqualTo(107_220L)
        assertThat(SwiftieTimeline.FINAL_HOLD_START).isEqualTo(112_620L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_START).isEqualTo(119_500L)
        assertThat(SwiftieTimeline.FADE_OUT_START).isEqualTo(123_000L)
    }

    @Test
    fun theFinaleIsOneSegmentBecauseTheSignatureAndTheBraceletShareIt() {
        // 签名的账：写字 + 抬笔 + 收笔闪，三个加数必须正好是账本给这一段的长度
        assertThat(SwiftieTimeline.SIGNATURE_MS).isEqualTo(5_400L)
        assertThat(SIGNATURE_WRITE_MS + SIGNATURE_PAUSE_TOTAL_MS + SIGNATURE_FLASH_MS)
            .isEqualTo(SwiftieTimeline.SIGNATURE_MS)
        assertThat(SwiftieTimeline.FINAL_HOLD_START)
            .isEqualTo(SwiftieTimeline.SIGNATURE_START + SwiftieTimeline.SIGNATURE_MS)
        // 手链在签名还没写完时就进场 —— 两件事同场，所以账本上不需要「手链段」，
        // SwiftieSequencePhase 里也没有 BRACELET 这个相位
        assertThat(SwiftieTimeline.SIGNATURE_START + SwiftieTimeline.BRACELET_ENTRY_MS)
            .isGreaterThan(SwiftieTimeline.SIGNATURE_START)
        assertThat(SwiftieTimeline.SIGNATURE_START + SwiftieTimeline.BRACELET_ENTRY_MS)
            .isLessThan(SwiftieTimeline.FINAL_HOLD_START)
    }

    @Test
    fun finaleRunsBeforeTheRewindSoLoverLandsOnTheClosingBars() {
        // 绽放收在 2:03，之后只剩 2998ms —— 终局（签名 + 手链 + 定格）塞不进去，只能排在倒滑之前
        assertThat(SwiftieTimeline.SIGNATURE_START).isLessThan(SwiftieTimeline.REWIND_START)
        assertThat(SwiftieTimeline.FINAL_HOLD_START).isLessThan(SwiftieTimeline.REWIND_START)
        assertThat(SwiftieTimeline.FADE_OUT_MS).isEqualTo(2_998L)
    }

    @Test
    fun finalHoldAbsorbsTheSlackAndNeverGoesNegative() {
        // 唯一的弹性段：曲目数或 TTPD 前摇一改，误差全落在这里，不许把 Lover 绽放挤出配乐。
        // 2026-09-18：TTPD 逐行打印加 3000ms，从定格挪出同样长度，10590 → 7590
        // 2026-09-19：1989 换豪华版 16 首（+130×3ms），7590 → 5510；
        // 2026-09-20：Midnights 加曲再占 130ms，5510 → 5380
        assertThat(SwiftieTimeline.FINAL_HOLD_MS).isEqualTo(5_380L)
        assertThat(SwiftieTimeline.FINAL_HOLD_MS).isGreaterThan(0L)
        assertThat(SwiftieTimeline.FINAL_HOLD_START + SwiftieTimeline.FINAL_HOLD_MS)
            .isEqualTo(SwiftieTimeline.REWIND_START)
    }

    @Test
    fun themeCommitLandsOnTheFrameDiffusionFillsTheScreen() {
        // 早一帧会露出颜色跳变，晚一帧主题就赶不上 Eras 段
        assertThat(SwiftieTimeline.THEME_COMMIT_AT).isEqualTo(SwiftieTimeline.ERAS_INTRO_START)
    }

    @Test
    fun cardDurationsFollowTrackCountWithAnchorBonus() {
        // 5900 + 130n；只有 Lover 多停 600ms
        assertThat(SwiftieTimeline.cardDurationMs(index = 0, trackCount = 11)).isEqualTo(7_330L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 1, trackCount = 13)).isEqualTo(7_590L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 6, trackCount = 18)).isEqualTo(8_840L)
        // TTPD 用 Anthology 版 31 首，另有 3200ms 打字机前摇 + 3000ms 逐行放慢
        assertThat(SwiftieTimeline.cardDurationMs(index = 10, trackCount = 31)).isEqualTo(16_130L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 11, trackCount = 12)).isEqualTo(7_460L)
    }

    @Test
    fun twelveCardsFillTheErasSegment() {
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS).hasSize(12)
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS[10]).isEqualTo(31)
        // 补齐 folklore / evermore 最多版本，并为 Midnights 合辑版加入 You're Losing Me 后总数 204
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS.sum()).isEqualTo(204)
        assertThat(SwiftieTimeline.ERAS_CARDS_MS).isEqualTo(104_120L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START + SwiftieTimeline.ERAS_CARDS_MS)
            .isEqualTo(SwiftieTimeline.ERAS_CARDS_END)
    }

    @Test
    fun eraLookupIsContiguousAndClamped() {
        assertThat(SwiftieTimeline.eraStartMs(0)).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.eraStartMs(1)).isEqualTo(3_100L + 7_330L)
        assertThat(SwiftieTimeline.eraIndexAt(0L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(3_100L)).isEqualTo(0)
        assertThat(SwiftieTimeline.eraIndexAt(3_100L + 7_329L)).isEqualTo(0)
        assertThat(SwiftieTimeline.eraIndexAt(3_100L + 7_330L)).isEqualTo(1)
        // 第 12 张（TTPD 之后那张）也变长了 —— TTPD 的加时加在它身上，它跟着往后挪
        assertThat(SwiftieTimeline.eraIndexAt(104_000L)).isEqualTo(11)
        // 边界必须是卡片段末尾，不是 REWIND_START —— 终局夹在两者之间
        // （补齐版本差异后卡片段末尾是 107220）
        assertThat(SwiftieTimeline.eraIndexAt(107_220L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(112_000L)).isNull()
    }

    @Test
    fun motionPreheatSitsInsideTheSignatureSegment() {
        // 签名段整体后移，预热仍停在收笔前 260ms（补齐版本差异后随段落到 111360）
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT).isEqualTo(111_360L)
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT)
            .isGreaterThan(SwiftieTimeline.SIGNATURE_START)
        // 预热必须在终局段内跑完：写完之后就是定格合影，一帧都不该再出
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT)
            .isLessThan(SwiftieTimeline.FINAL_HOLD_START)
        // 而且落在收笔之前 —— 手链进场是这一段的背景，预热不许挤到它后面去
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT)
            .isLessThan(SwiftieTimeline.SIGNATURE_START + SIGNATURE_WRITE_MS)
    }
}
