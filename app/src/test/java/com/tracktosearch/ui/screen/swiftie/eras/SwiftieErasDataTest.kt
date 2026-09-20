package com.tracktosearch.ui.screen.swiftie.eras

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import org.junit.Test

class SwiftieErasDataTest {

    @Test
    fun trackCountsMatchTheDurationLedger() {
        // 账本按 `SwiftieTimeline.ERA_TRACK_COUNTS` 对齐每张卡片的曲目数量。
        // 抄漏一首这里就红
        assertThat(SwiftieErasData.ALL.map { it.tracks.size })
            .isEqualTo(SwiftieTimeline.ERA_TRACK_COUNTS)
    }

    @Test
    fun torturedPoetsUsesTheAnthologyEdition() {
        val ttpd = SwiftieErasData.ALL[10]
        assertThat(ttpd.name).isEqualTo("The Tortured Poets Department")
        assertThat(ttpd.tracks).hasSize(31)
        // 标准版最后一首在第 16 位，加曲从第 17 位接上
        assertThat(ttpd.tracks[15]).isEqualTo("Clara Bow")
        assertThat(ttpd.tracks[16]).isEqualTo("The Black Dog")
        assertThat(ttpd.tracks.last()).isEqualTo("The Manuscript")
        // 官方大小写刻意拼出 KIM，自动纠错最容易把它改掉
        assertThat(ttpd.tracks).contains("thanK you aIMee")
        assertThat(ttpd.tracks).contains("imgonnagetyouback")
        assertThat(ttpd.tracks.toSet()).hasSize(31)
    }

    @Test
    fun midnightsUsesTheTilDawnEditionWithoutDuplicateVersions() {
        val midnights = SwiftieErasData.ALL[9]
        assertThat(midnights.tracks).hasSize(22)
        assertThat(midnights.tracks.last()).isEqualTo("You're Losing Me")
        assertThat(midnights.tracks).doesNotContain("Snow on the Beach (More Lana)")
        assertThat(midnights.tracks).doesNotContain("Karma (Ice Spice)")
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
    fun taylorVersionExclusiveTracksAreCovered() {
        // 四张重录：只列各自 Taylor's Version 的独有曲目，旧曲保持裸名
        val expected = mapOf(
            1 to listOf("Jump Then Fall (TV)", "Bye Bye Baby (TV)"),
            2 to listOf("Ours (TV)", "Timeless (TV)"),
            3 to listOf("The Moment I Knew (TV)", "All Too Well (10 Minute Version) (TV)"),
            4 to listOf("\"Slut!\" (TV)", "Is It Over Now? (TV)"),
        )
        expected.forEach { (index, tracks) ->
            assertThat(SwiftieErasData.ALL[index].tracks).containsAtLeastElementsIn(tracks)
        }
        assertThat(SwiftieErasData.ALL.map { it.tracks.count { track -> track.endsWith("(TV)") } })
            .isEqualTo(listOf(0, 13, 8, 14, 5, 0, 0, 0, 0, 0, 0, 0))
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

    @Test
    fun everyEraHasOneStage() {
        // STAGE 与 ALL 按下标一一对应，全靠这条守 —— 中间插一张专辑而漏改 STAGE，
        // 后 11 张的背景就整体错位一格，而屏幕上只是「颜色怪怪的」
        assertThat(SwiftieErasData.STAGE).hasSize(SwiftieErasData.ALL.size)
        assertThat(SwiftieErasData.STAGE.map { it.backdrop }.toSet()).hasSize(12)
    }

    @Test
    fun everyStageGivesThreeGradientStopsFromLightToDark() {
        SwiftieErasData.STAGE.forEach { stage ->
            // L0 是 verticalGradient(top, mid, bottom)，少一个 stop 就渐变不出来
            assertThat(stage.backdropColors).hasSize(3)
        }
    }

    @Test
    fun onlyTwoErasGoWithoutParticles() {
        // Speak Now 的舞台与 reputation 的黑白报纸刻意不给飘落物：
        // 12 张全有反而变成同一套「东西在飘」，那正是廉价感的来源
        val without = SwiftieErasData.STAGE.withIndex()
            .filter { it.value.particle == null }
            .map { it.index }
        assertThat(without).containsExactly(2, 5)
        // 剩下 10 张各用一种，没有两张共用
        assertThat(SwiftieErasData.STAGE.mapNotNull { it.particle }.toSet()).hasSize(10)
        assertThat(SwiftieEraParticle.entries).hasSize(10)
    }
}
