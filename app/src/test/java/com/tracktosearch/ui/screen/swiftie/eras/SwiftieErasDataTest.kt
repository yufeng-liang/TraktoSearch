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
        // Showgirl 的 releaseDate 仍是**原版**发行日。The Encore 那个新日期是续章里的
        // 显示态（与 Lover 回退时显示 TS7 同一套路），不进这个字段 —— 否则
        // 上面那条发行顺序断言会因为「同一张专辑有两个日期」而失去意义
        assertThat(dates.last()).isEqualTo("2025-10-03")
    }

    /**
     * The Encore 的四首加曲接在原版 12 首之后，且边界只由 [SwiftieErasData.ENCORE_FIRST_TRACK] 表达。
     *
     * 这四首的**大小写与标点必须逐字对**：`Cleveland!` 带感叹号、`Patient Zero` 两个词 ——
     * 它们不像 `Wi$h Li$t` 那样有美元符号这种显眼的记号，手抄时最容易被「顺手规范化」掉。
     */
    @Test
    fun showgirlAppendsTheEncoreTracksAfterTheOriginalTwelve() {
        val showgirl = SwiftieErasData.ALL[SwiftieErasData.SHOWGIRL_INDEX]
        assertThat(showgirl.tracks).hasSize(16)
        assertThat(showgirl.name).isEqualTo("The Life of a Showgirl")
        // 原版第 12 首仍在第 12 位，加曲从 13 位（下标 12）接上
        assertThat(showgirl.tracks[11]).isEqualTo("The Life of a Showgirl")
        assertThat(showgirl.tracks.subList(12, 16))
            .containsExactly("Patient Zero", "Cleveland!", "Pink Clouding", "Babylon")
            .inOrder()
        assertThat(SwiftieErasData.ENCORE_FIRST_TRACK).isEqualTo(12)
        // 曲目无重复（加曲与任何一首原版同名会让曲目列的逐行落墨认错行）
        assertThat(showgirl.tracks.toSet()).hasSize(16)
    }

    /**
     * 续章那张的曲目数与账本一致，且它的卡片时长**不跟着曲目数走**。
     *
     * 上一条 `trackCountsMatchTheDurationLedger` 只保证「数量对齐」；这条补的是
     * Showgirl 的专属分支：16 首不该把卡片撑长。
     */
    @Test
    fun showgirlEncoreTracksDoNotStretchItsCard() {
        val showgirl = SwiftieErasData.ALL[SwiftieErasData.SHOWGIRL_INDEX]
        assertThat(SwiftieTimeline.cardDurationMs(SwiftieErasData.SHOWGIRL_INDEX, showgirl.tracks.size))
            .isEqualTo(SwiftieTimeline.SHOWGIRL_CARD_MS)
        // 通用公式若被误用到这张上，值会是 5900 + 117×16 = 7772，与本值不同 ——
        // 断言一个「不等于」把这条误接挡住
        assertThat(SwiftieTimeline.SHOWGIRL_CARD_MS).isNotEqualTo(7_772L)
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
    fun onlyThreeErasGoWithoutL2Particles() {
        // Speak Now 与 reputation 的舞台自己在动，刻意不给飘落物；Red 的秋叶则搬到
        // 卡片**之上**那一层（`SwiftieRedLeafFall`），留在 L2 会被半透明白卡片盖住。
        // 12 张全有反而变成同一套「东西在飘」，那正是廉价感的来源
        val without = SwiftieErasData.STAGE.withIndex()
            .filter { it.value.particle == null }
            .map { it.index }
        assertThat(without).containsExactly(2, 3, 5)
        // 剩下 9 张各用一种，没有两张共用
        assertThat(SwiftieErasData.STAGE.mapNotNull { it.particle }.toSet()).hasSize(9)
        assertThat(SwiftieEraParticle.entries).hasSize(9)
    }

    @Test
    fun onlySpeakNowSplitsTheTracklistAndNoTrackIsLost() {
        // 拆栏会改到卡片排版与道具落位，逐张定过；其余 11 张必须与拆栏前逐像素一致
        val split = SwiftieErasData.ALL.withIndex()
            .filter { it.value.leftColumnRows > 0 }
            .map { it.index }
        // 下标 2 = Speak Now（ALL 从 0 数：Taylor Swift · Fearless · Speak Now）。
        // 这里原来写 3，那是 Red —— 拆栏的是 Speak Now，3 是早先一次索引口径改动留下的
        assertThat(split).containsExactly(2)
        SwiftieErasData.ALL.forEach { era ->
            val ranges = trackColumnRanges(era)
            // 一首不多、一首不少、顺序不乱：拆点填错（0、等于 1、超出总数）全在这里红
            assertThat(ranges.flatMap { it.toList() })
                .containsExactlyElementsIn(era.tracks.indices)
                .inOrder()
            // 卡片高度按较长那一栏折算，不是按曲目总数。道具带算在它所在的那一栏上
            val columnRows = ranges.mapIndexed { slot, range ->
                val band = if (ranges.size > 1 && slot == ranges.lastIndex) era.propRowBand else 0
                range.count() + band
            }
            assertThat(era.columnRowCount).isEqualTo(columnRows.max())
            // 拆栏的两栏**行数必须相等**：多出来的那一栏就是卡片白长的高度，
            // 而且它的末行会比另一栏低一行（需求方要的正是 15 与 22 底边对齐）
            if (ranges.size > 1) assertThat(columnRows.distinct()).hasSize(1)
        }
    }

    @Test
    fun propRowBandOnlyOnTheCardThatSplitItsTracklist() {
        // 花束搬到右栏**顶上**，与拆栏是同一件事的两半：不拆栏就没有「右栏上方」这块领地，
        // 带高必须为 0；拆了栏却不给带，花束会压在右栏那几行字上。
        // 带高同时是卡片行预算的一部分（见 [SwiftieEra.columnRowCount]），所以它填几行
        // 直接决定两栏底边齐不齐 —— 上面那条 `columnRows.distinct()` 锁的就是这个
        SwiftieErasData.ALL.forEach { era ->
            if (era.leftColumnRows > 0) {
                assertThat(era.propRowBand).isGreaterThan(0)
            } else {
                assertThat(era.propRowBand).isEqualTo(0)
            }
        }
    }
}
