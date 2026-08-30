package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieTimelineTest {

    @Test
    fun segmentsSumToExactlyTwoMinutes() {
        // 配乐是 2 分钟，差一毫秒都算错开
        assertThat(SwiftieTimeline.FADE_OUT_START + SwiftieTimeline.FADE_OUT_MS)
            .isEqualTo(SwiftieTimeline.TOTAL_MS)
        assertThat(SwiftieTimeline.TOTAL_MS).isEqualTo(120_000L)
    }

    @Test
    fun segmentBoundariesMatchTheSpec() {
        assertThat(SwiftieTimeline.DIFFUSION_START).isEqualTo(400L)
        assertThat(SwiftieTimeline.ERAS_INTRO_START).isEqualTo(1_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.REWIND_START).isEqualTo(97_460L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_START).isEqualTo(98_960L)
        assertThat(SwiftieTimeline.SIGNATURE_START).isEqualTo(100_460L)
        assertThat(SwiftieTimeline.BRACELET_START).isEqualTo(108_460L)
        assertThat(SwiftieTimeline.FINAL_HOLD_START).isEqualTo(112_960L)
        assertThat(SwiftieTimeline.FADE_OUT_START).isEqualTo(118_460L)
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
        assertThat(SwiftieTimeline.cardDurationMs(index = 11, trackCount = 12)).isEqualTo(7_460L)
    }

    @Test
    fun twelveCardsFillTheErasSegment() {
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS).hasSize(12)
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS.sum()).isEqualTo(172)
        assertThat(SwiftieTimeline.ERAS_CARDS_MS).isEqualTo(94_360L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START + SwiftieTimeline.ERAS_CARDS_MS)
            .isEqualTo(SwiftieTimeline.REWIND_START)
    }

    @Test
    fun eraLookupIsContiguousAndClamped() {
        assertThat(SwiftieTimeline.eraStartMs(0)).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.eraStartMs(1)).isEqualTo(3_100L + 7_930L)
        assertThat(SwiftieTimeline.eraIndexAt(0L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(3_100L)).isEqualTo(0)
        assertThat(SwiftieTimeline.eraIndexAt(3_100L + 7_929L)).isEqualTo(0)
        assertThat(SwiftieTimeline.eraIndexAt(3_100L + 7_930L)).isEqualTo(1)
        assertThat(SwiftieTimeline.eraIndexAt(97_459L)).isEqualTo(11)
        assertThat(SwiftieTimeline.eraIndexAt(97_460L)).isNull()
    }

    @Test
    fun motionPreheatSitsInsideTheSignatureSegment() {
        // 106s 不出帧，写完签名前才预热运动
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT).isEqualTo(107_000L)
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT)
            .isGreaterThan(SwiftieTimeline.SIGNATURE_START)
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT)
            .isLessThan(SwiftieTimeline.BRACELET_START)
    }
}
