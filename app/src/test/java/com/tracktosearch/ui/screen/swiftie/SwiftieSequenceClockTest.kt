package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieSequenceClockTest {

    @Test
    fun phaseCoversTheWholeTimelineWithoutGaps() {
        assertThat(swiftiePhaseAt(0L)).isEqualTo(SwiftieSequencePhase.SOLVE)
        assertThat(swiftiePhaseAt(399L)).isEqualTo(SwiftieSequencePhase.SOLVE)
        assertThat(swiftiePhaseAt(400L)).isEqualTo(SwiftieSequencePhase.DIFFUSION)
        assertThat(swiftiePhaseAt(1_100L)).isEqualTo(SwiftieSequencePhase.ERAS_INTRO)
        assertThat(swiftiePhaseAt(3_100L)).isEqualTo(SwiftieSequencePhase.ERAS_CARDS)
        assertThat(swiftiePhaseAt(97_460L)).isEqualTo(SwiftieSequencePhase.REWIND)
        assertThat(swiftiePhaseAt(98_960L)).isEqualTo(SwiftieSequencePhase.LOVER_BLOOM)
        assertThat(swiftiePhaseAt(100_460L)).isEqualTo(SwiftieSequencePhase.SIGNATURE)
        assertThat(swiftiePhaseAt(108_460L)).isEqualTo(SwiftieSequencePhase.BRACELET)
        assertThat(swiftiePhaseAt(112_960L)).isEqualTo(SwiftieSequencePhase.FINAL_HOLD)
        assertThat(swiftiePhaseAt(118_460L)).isEqualTo(SwiftieSequencePhase.FADE_OUT)
        assertThat(swiftiePhaseAt(120_000L)).isEqualTo(SwiftieSequencePhase.DONE)
    }

    @Test
    fun pausedClockDoesNotAdvance() {
        val clock = SwiftieSequenceClock()
        // 步长必须 ≤ MAX_FRAME_DELTA_MS，否则会被钳制（钳制本身由下一个测试覆盖）
        clock.advance(50L)
        assertThat(clock.elapsedMs).isEqualTo(50L)
        clock.paused = true
        clock.advance(50L)
        assertThat(clock.elapsedMs).isEqualTo(50L)
        clock.paused = false
        clock.advance(30L)
        assertThat(clock.elapsedMs).isEqualTo(80L)
    }

    @Test
    fun advanceClampsHitchesAndStopsAtTotal() {
        val clock = SwiftieSequenceClock()
        // 单帧卡了 3s 也只推进 100ms，否则一次掉帧就把时间轴瞬移出去
        clock.advance(3_000L)
        assertThat(clock.elapsedMs).isEqualTo(SwiftieSequenceClock.MAX_FRAME_DELTA_MS)
        clock.seekTo(SwiftieTimeline.TOTAL_MS - 10L)
        clock.advance(100L)
        assertThat(clock.elapsedMs).isEqualTo(SwiftieTimeline.TOTAL_MS)
        assertThat(clock.finished).isTrue()
    }

    @Test
    fun seekingSnapsToEraStartAndFlagsManualControl() {
        val clock = SwiftieSequenceClock()
        assertThat(clock.userSeeked).isFalse()
        clock.seekToEra(6)
        assertThat(clock.elapsedMs).isEqualTo(SwiftieTimeline.eraStartMs(6))
        // 碰过播放头就取消自动续播，转全手动（Spec §6.1）
        assertThat(clock.userSeeked).isTrue()
    }

    @Test
    fun seekIsClampedAndSkipLandsOnFinalHold() {
        val clock = SwiftieSequenceClock()
        clock.seekTo(-5_000L)
        assertThat(clock.elapsedMs).isEqualTo(0L)
        clock.seekTo(999_999L)
        assertThat(clock.elapsedMs).isEqualTo(SwiftieTimeline.TOTAL_MS)
        // 「跳过」不是直接关掉，而是跳到定格合影，仍然看得到签名与手链
        clock.skipToFinalHold()
        assertThat(clock.elapsedMs).isEqualTo(SwiftieTimeline.FINAL_HOLD_START)
    }
}
