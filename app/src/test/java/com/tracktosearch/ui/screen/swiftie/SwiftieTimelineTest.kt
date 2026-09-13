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
        assertThat(SwiftieTimeline.DIFFUSION_START).isEqualTo(400L)
        assertThat(SwiftieTimeline.ERAS_INTRO_START).isEqualTo(1_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_END).isEqualTo(102_010L)
        assertThat(SwiftieTimeline.SIGNATURE_START).isEqualTo(102_010L)
        assertThat(SwiftieTimeline.FINAL_HOLD_START).isEqualTo(107_410L)
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
        // 2026-09-13 手链并进签名、签名提速之后，这里从 4690 涨到 10590
        assertThat(SwiftieTimeline.FINAL_HOLD_MS).isEqualTo(10_590L)
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
        // 5900 + 130n；第 1、7 张 +600
        assertThat(SwiftieTimeline.cardDurationMs(index = 0, trackCount = 11)).isEqualTo(7_930L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 1, trackCount = 13)).isEqualTo(7_590L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 6, trackCount = 18)).isEqualTo(8_840L)
        // TTPD 用 Anthology 版 31 首，还要加 2600ms 打字机前摇，是最长的一张
        assertThat(SwiftieTimeline.cardDurationMs(index = 10, trackCount = 31)).isEqualTo(12_530L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 11, trackCount = 12)).isEqualTo(7_460L)
    }

    @Test
    fun twelveCardsFillTheErasSegment() {
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS).hasSize(12)
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS[10]).isEqualTo(31)
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS.sum()).isEqualTo(187)
        assertThat(SwiftieTimeline.ERAS_CARDS_MS).isEqualTo(98_910L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START + SwiftieTimeline.ERAS_CARDS_MS)
            .isEqualTo(SwiftieTimeline.ERAS_CARDS_END)
    }

    @Test
    fun eraLookupIsContiguousAndClamped() {
        assertThat(SwiftieTimeline.eraStartMs(0)).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.eraStartMs(1)).isEqualTo(3_100L + 7_930L)
        assertThat(SwiftieTimeline.eraIndexAt(0L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(3_100L)).isEqualTo(0)
        assertThat(SwiftieTimeline.eraIndexAt(3_100L + 7_929L)).isEqualTo(0)
        assertThat(SwiftieTimeline.eraIndexAt(3_100L + 7_930L)).isEqualTo(1)
        // 第 12 张（TTPD 之后那张）也变长了 —— 前摇加在 TTPD 身上，它后面那张跟着往后挪
        assertThat(SwiftieTimeline.eraIndexAt(100_809L)).isEqualTo(11)
        // 边界必须是卡片段末尾，不是 REWIND_START —— 终局夹在两者之间
        assertThat(SwiftieTimeline.eraIndexAt(102_010L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(112_000L)).isNull()
    }

    @Test
    fun motionPreheatSitsInsideTheSignatureSegment() {
        // 106s 不出帧，写完签名前 1260ms 才预热运动
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT).isEqualTo(106_150L)
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
