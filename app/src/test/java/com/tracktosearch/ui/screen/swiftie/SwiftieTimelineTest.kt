package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieTimelineTest {

    @Test
    fun segmentsSumToExactlyTheSoundtrackLength() {
        // swiftie_theme.ogg（Opus）的容器时长是 125.997979s，差一毫秒都算错开
        assertThat(SwiftieTimeline.MUSIC_MS).isEqualTo(125_998L)
        assertThat(SwiftieTimeline.FADE_OUT_START + SwiftieTimeline.FADE_OUT_MS)
            .isEqualTo(SwiftieTimeline.TOTAL_MS)
        // 配乐在前奏第一帧就起播，账本只占音轨扣掉前奏之后的那一段。
        // 两者相加必须正好还原音轨长度 —— 序列最后一帧与音乐收尾同时到
        assertThat(SwiftieTimeline.PREROLL_MS + SwiftieTimeline.TOTAL_MS)
            .isEqualTo(SwiftieTimeline.MUSIC_MS)
        assertThat(SwiftieTimeline.PREROLL_MS).isEqualTo(1_500L)
        assertThat(SwiftieTimeline.TOTAL_MS).isEqualTo(124_498L)
    }

    @Test
    fun theTwoAudioPinsAreExactlyWhereTheTrackSingsLover() {
        // 配乐 1:58–2:02 唱 Lover。这两个常量存的是**时钟**值，加回前奏才是音轨时间戳；
        // 下面两条就是「那句 Lover 压在绽放上」的全部保证，改前奏时长会先撞在这里
        assertThat(SwiftieTimeline.REWIND_START + SwiftieTimeline.PREROLL_MS)
            .isEqualTo(118_000L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_END + SwiftieTimeline.PREROLL_MS)
            .isEqualTo(123_000L)
        assertThat(SwiftieTimeline.REWIND_START).isEqualTo(116_500L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_END).isEqualTo(121_500L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_START + SwiftieTimeline.LOVER_BLOOM_MS)
            .isEqualTo(SwiftieTimeline.LOVER_BLOOM_END)
    }

    @Test
    fun segmentBoundariesMatchTheSpec() {
        assertThat(SwiftieTimeline.DIFFUSION_START).isEqualTo(400L)
        assertThat(SwiftieTimeline.ERAS_INTRO_START).isEqualTo(1_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_END).isEqualTo(99_410L)
        assertThat(SwiftieTimeline.SIGNATURE_START).isEqualTo(99_410L)
        assertThat(SwiftieTimeline.BRACELET_START).isEqualTo(107_410L)
        assertThat(SwiftieTimeline.FINAL_HOLD_START).isEqualTo(111_910L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_START).isEqualTo(118_000L)
        assertThat(SwiftieTimeline.FADE_OUT_START).isEqualTo(121_500L)
    }

    @Test
    fun finaleRunsBeforeTheRewindSoLoverLandsOnTheClosingBars() {
        // 绽放收在音轨 2:03，之后只剩 2998ms —— 17s 的终局塞不进去，只能排在倒滑之前
        assertThat(SwiftieTimeline.SIGNATURE_START).isLessThan(SwiftieTimeline.REWIND_START)
        assertThat(SwiftieTimeline.FINAL_HOLD_START).isLessThan(SwiftieTimeline.REWIND_START)
        assertThat(SwiftieTimeline.FADE_OUT_MS).isEqualTo(2_998L)
    }

    @Test
    fun finalHoldAbsorbsTheSlackAndNeverGoesNegative() {
        // 唯一的弹性段：曲目数一改、前奏一长，误差全落在这里，
        // 不许把 Lover 绽放挤出配乐
        assertThat(SwiftieTimeline.FINAL_HOLD_MS).isEqualTo(4_590L)
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
        // TTPD 用 Anthology 版 31 首，是最长的一张
        assertThat(SwiftieTimeline.cardDurationMs(index = 10, trackCount = 31)).isEqualTo(9_930L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 11, trackCount = 12)).isEqualTo(7_460L)
    }

    @Test
    fun twelveCardsFillTheErasSegment() {
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS).hasSize(12)
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS[10]).isEqualTo(31)
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS.sum()).isEqualTo(187)
        assertThat(SwiftieTimeline.ERAS_CARDS_MS).isEqualTo(96_310L)
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
        assertThat(SwiftieTimeline.eraIndexAt(99_409L)).isEqualTo(11)
        // 边界必须是卡片段末尾，不是 REWIND_START —— 终局夹在两者之间
        assertThat(SwiftieTimeline.eraIndexAt(99_410L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(112_000L)).isNull()
    }

    @Test
    fun motionPreheatSitsInsideTheSignatureSegment() {
        // 105s 不出帧，写完签名前 1460ms 才预热运动
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT).isEqualTo(105_950L)
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT)
            .isGreaterThan(SwiftieTimeline.SIGNATURE_START)
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT)
            .isLessThan(SwiftieTimeline.BRACELET_START)
    }
}
