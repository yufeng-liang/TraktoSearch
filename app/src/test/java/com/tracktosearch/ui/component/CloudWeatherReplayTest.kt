package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.component.CloudThemeManager.Companion.NEVER_PLAYED_WEATHER
import com.tracktosearch.ui.component.CloudThemeManager.Companion.WEATHER_REPLAY_MIN_INTERVAL_MS
import com.tracktosearch.ui.component.CloudThemeManager.Companion.shouldReplayWeather
import org.junit.Test

/**
 * 天气动画「每次可见完整播一次 + 定格帧」的两条判据。
 *
 * 播放本身（LottieAnimatable.animate 挂到播完才返回）测不了，这里钉的是它外面的闸门：
 * 节流窗口和按主题查的定格帧。后者是一张手抄的表，改错一个值不会有任何编译或运行期
 * 症状，只会在真机上变成「某个天气停得怪怪的」。
 */
class CloudWeatherReplayTest {

    private val interval = WEATHER_REPLAY_MIN_INTERVAL_MS

    @Test
    fun firstAppearanceAlwaysPlays() {
        assertThat(shouldReplayWeather(false, nowMs = 1_000L, lastStartMs = NEVER_PLAYED_WEATHER))
            .isTrue()
    }

    @Test
    fun replayOpensExactlyAtTheIntervalBoundary() {
        val last = 50_000L
        assertThat(shouldReplayWeather(false, nowMs = last + interval - 1, lastStartMs = last))
            .isFalse()
        assertThat(shouldReplayWeather(false, nowMs = last + interval, lastStartMs = last))
            .isTrue()
    }

    /**
     * 静态用户压根没起播，就不能占掉那 8 秒：判据必须是 false，
     * 而 `claimWeatherPlay` 只在 true 时盖章 —— 顺序反了就会白占。
     */
    @Test
    fun reducedMotionNeverOpensTheGate() {
        assertThat(
            shouldReplayWeather(true, nowMs = 999_000L, lastStartMs = NEVER_PLAYED_WEATHER)
        ).isFalse()
    }

    @Test
    fun clockGoingBackwardsDoesNotLoudTheGateOpen() {
        // uptime 单调，但 -1 起底与「now 比上次小」都会给出负数间隔，不能因此放行
        assertThat(shouldReplayWeather(false, nowMs = 10_000L, lastStartMs = 20_000L)).isFalse()
    }

    /**
     * 表里每个值都是在 docs/previews/cloud-frame 逐主题挑出来的：多数主题的 Lottie
     * 本来就按无缝循环做（首末帧逐像素相同）所以停末态；只有 5 个刻意回了首帧或半程。
     */
    @Test
    fun weatherFreezeFrameMatchesThePickerTable() {
        val actual = CloudTheme.values().associate { it.name to it.freezeProgress }
        assertThat(actual).containsExactlyEntriesIn(
            mapOf(
                "SUNNY" to 0f,
                "RAINY" to 1f,
                "SNOWY" to 1f,
                "THUNDER" to 1f,
                "CHRISTMAS" to 1f,
                "SPRING_FESTIVAL" to 1f,
                "HALLOWEEN" to 1f,
                "CLOUDY" to 0.5f,
                "OVERCAST" to 0f,
                "MIST" to 0f,
                "NIGHT" to 0.5f,
                "NATIONAL" to 1f,
                "MIDAUTUMN" to 1f,
                "DRAGONBOAT" to 1f,
                "LABOR" to 1f,
            )
        )
    }
}
