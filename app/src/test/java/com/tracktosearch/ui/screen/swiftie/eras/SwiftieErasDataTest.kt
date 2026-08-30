package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import org.junit.Test

class SwiftieErasDataTest {

    @Test
    fun trackCountsMatchTheDurationLedger() {
        // 账本按这些数字算出 94360ms。抄漏一首这里就红
        assertThat(SwiftieErasData.ALL.map { it.tracks.size })
            .isEqualTo(SwiftieTimeline.ERA_TRACK_COUNTS)
    }

    @Test
    fun twelveErasInReleaseOrder() {
        assertThat(SwiftieErasData.ALL).hasSize(12)
        val dates = SwiftieErasData.ALL.map { it.releaseDate }
        assertThat(dates).isInOrder()
        assertThat(dates.first()).isEqualTo("2006-10-24")
        assertThat(dates.last()).isEqualTo("2025-10-03")
    }

    @Test
    fun officialCasingIsPreserved() {
        val names = SwiftieErasData.ALL.map { it.name }
        assertThat(names[5]).isEqualTo("reputation")
        assertThat(names[7]).isEqualTo("folklore")
        assertThat(names[8]).isEqualTo("evermore")
        assertThat(names[4]).isEqualTo("1989")
        // loml 与 ME! 是最容易被自动纠正掉的两个
        assertThat(SwiftieErasData.ALL[10].tracks).contains("loml")
        assertThat(SwiftieErasData.ALL[6].tracks).contains("ME!")
    }

    @Test
    fun loverIsTheSeventhAndTheAnchor() {
        assertThat(SwiftieErasData.LOVER_INDEX).isEqualTo(6)
        assertThat(SwiftieErasData.ALL[SwiftieErasData.LOVER_INDEX].name).isEqualTo("Lover")
        // 起点与归宿加时的两张，与账本一致
        assertThat(SwiftieTimeline.ANCHOR_INDICES).containsExactly(0, SwiftieErasData.LOVER_INDEX)
    }

    @Test
    fun everyEraHasItsOwnFontAndMotif() {
        assertThat(SwiftieErasData.ALL.map { it.motif }.toSet()).hasSize(12)
        // folklore 与 evermore 共用 IM Fell DW Pica，所以字体只有 11 个
        assertThat(SwiftieErasData.ALL.map { it.fontResId }.toSet()).hasSize(11)
        assertThat(SwiftieErasData.ALL[7].fontResId).isEqualTo(SwiftieErasData.ALL[8].fontResId)
    }

    @Test
    fun noTrackCarriesFeatureCreditsOrEmptyText() {
        SwiftieErasData.ALL.forEach { era ->
            era.tracks.forEach { track ->
                assertThat(track).isNotEmpty()
                // 客串信息不列，横向空间留给标题本身
                assertThat(track).doesNotContain("feat.")
                assertThat(track).doesNotContain("featuring")
            }
        }
    }

    @Test
    fun cardSegmentsSumToTheLedgerBase() {
        // 400 长出 + 5000 停留 + 400 回落 + 100 段间 = 5900，与账本的固定开销一致
        assertThat(CARD_GROW_MS + CARD_HOLD_MS + CARD_RECEDE_MS + CARD_GAP_MS)
            .isEqualTo(SwiftieTimeline.CARD_BASE_MS)
    }
}
