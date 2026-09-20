package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import org.junit.Test

/**
 * 轴上的四个纯函数。
 *
 * 它们把「时间」换算成「横向比例」，是拖播放头与卡片缩放轴心的唯一真相来源 ——
 * 算错了不会崩，只会让播放头与色带错位、卡片从别的段长出来，而这种错在 104 秒的
 * 动画里几乎看不出来。所以必须有断言守着。
 */
class SwiftieErasAxisTest {

    private val eraCount = SwiftieTimeline.ERA_TRACK_COUNTS.size

    @Test
    fun edges_areThirteenValuesFromZeroToOne() {
        assertThat(SWIFTIE_ERA_EDGES).hasSize(eraCount + 1)
        assertThat(SWIFTIE_ERA_EDGES.first()).isEqualTo(0f)
        assertThat(SWIFTIE_ERA_EDGES.last()).isEqualTo(1f)
    }

    @Test
    fun edges_areStrictlyIncreasing() {
        SWIFTIE_ERA_EDGES.zipWithNext().forEach { (from, to) ->
            assertThat(to).isGreaterThan(from)
        }
    }

    @Test
    fun edges_widthsAreProportionalToCardDurations() {
        // 宽度按时长成比例是「播放头匀速」的前提。等宽色带会让 18 首的 Lover 段看着卡住
        SWIFTIE_ERA_EDGES.zipWithNext().forEachIndexed { index, (from, to) ->
            val expected = SwiftieTimeline
                .cardDurationMs(index, SwiftieTimeline.ERA_TRACK_COUNTS[index])
                .toFloat() / SwiftieTimeline.ERAS_CARDS_MS
            assertThat(to - from).isWithin(1e-5f).of(expected)
        }
    }

    @Test
    fun centerFraction_liesInsideItsOwnBand() {
        repeat(eraCount) { index ->
            val center = swiftieEraCenterFraction(index)
            assertThat(center).isGreaterThan(SWIFTIE_ERA_EDGES[index])
            assertThat(center).isLessThan(SWIFTIE_ERA_EDGES[index + 1])
        }
    }

    @Test
    fun centerFraction_clampsOutOfRangeIndices() {
        // 越界不该抛 —— 倒滑与绽放会拿钉死的 LOVER_INDEX 反复调它
        assertThat(swiftieEraCenterFraction(-5)).isEqualTo(swiftieEraCenterFraction(0))
        assertThat(swiftieEraCenterFraction(99)).isEqualTo(swiftieEraCenterFraction(eraCount - 1))
    }

    @Test
    fun indexAtFraction_roundTripsWithCenterFraction() {
        // 吸附的核心不变式：从第 i 段中心按下去，必须还是第 i 段
        repeat(eraCount) { index ->
            assertThat(swiftieEraIndexAtFraction(swiftieEraCenterFraction(index))).isEqualTo(index)
        }
    }

    @Test
    fun indexAtFraction_clampsOutsideZeroToOne() {
        assertThat(swiftieEraIndexAtFraction(-1f)).isEqualTo(0)
        assertThat(swiftieEraIndexAtFraction(0f)).isEqualTo(0)
        assertThat(swiftieEraIndexAtFraction(1f)).isEqualTo(eraCount - 1)
        assertThat(swiftieEraIndexAtFraction(2f)).isEqualTo(eraCount - 1)
    }

    @Test
    fun playheadFraction_isZeroBeforeCardsAndOneAfter() {
        assertThat(swiftiePlayheadFraction(0L)).isEqualTo(0f)
        assertThat(swiftiePlayheadFraction(SwiftieTimeline.ERAS_CARDS_START)).isEqualTo(0f)
        assertThat(swiftiePlayheadFraction(SwiftieTimeline.ERAS_CARDS_END)).isEqualTo(1f)
        // 终局与倒滑都在卡片段之后，夹到 1f 而不是继续往外跑
        assertThat(swiftiePlayheadFraction(SwiftieTimeline.REWIND_START)).isEqualTo(1f)
        assertThat(swiftiePlayheadFraction(SwiftieTimeline.TOTAL_MS)).isEqualTo(1f)
    }

    @Test
    fun playheadFraction_matchesEdgeAtEveryCardBoundary() {
        // 第 i 张卡片开始那一刻，播放头必须正好压在第 i 条刻度上
        repeat(eraCount) { index ->
            assertThat(swiftiePlayheadFraction(SwiftieTimeline.eraStartMs(index)))
                .isWithin(1e-5f)
                .of(SWIFTIE_ERA_EDGES[index])
        }
    }

    @Test
    fun playheadFraction_isMonotonic() {
        var previous = -1f
        var at = SwiftieTimeline.ERAS_CARDS_START
        while (at <= SwiftieTimeline.ERAS_CARDS_END) {
            val current = swiftiePlayheadFraction(at)
            assertThat(current).isAtLeast(previous)
            previous = current
            at += 997L
        }
    }
}
