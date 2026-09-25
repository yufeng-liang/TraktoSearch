package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieTimelineTest {

    @Test
    fun segmentsSumToExactlyTheSoundtrackLength() {
        // swiftie_theme.ogg（Opus）的容器时长是 125.997979s，差一毫秒都算错开
        assertThat(SwiftieTimeline.TOTAL_MS).isEqualTo(125_998L)
        // 淡出**完全跑在配乐之外**：起点就是配乐的最后一帧，终点是序列终点
        assertThat(SwiftieTimeline.FADE_OUT_START).isEqualTo(SwiftieTimeline.TOTAL_MS)
        assertThat(SwiftieTimeline.FADE_OUT_START + SwiftieTimeline.FADE_OUT_MS)
            .isEqualTo(SwiftieTimeline.END_MS)
        assertThat(SwiftieTimeline.END_MS - SwiftieTimeline.TOTAL_MS)
            .isEqualTo(SwiftieTimeline.TAIL_FADE_MS)
        assertThat(SwiftieTimeline.TAIL_FADE_MS).isEqualTo(1_200L)
        // 淡出之前那一大段是水晶球的满亮定格：绽放收束 → 配乐最后一帧，2998ms。
        // 它不是段落、没有自己的相位，只是「这段时间谁也没花」—— 球站着，配乐放着
        assertThat(SwiftieTimeline.FADE_OUT_START - SwiftieTimeline.LOVER_BLOOM_END)
            .isEqualTo(2_998L)
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
        // 2026-09-25：TTPD 前摇从 3200 拉到 4700（钱从定格来），卡片段跟着到 110917
        assertThat(SwiftieTimeline.DIFFUSION_START).isEqualTo(400L)
        assertThat(SwiftieTimeline.ERAS_INTRO_START).isEqualTo(1_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.ERAS_CARDS_END).isEqualTo(110_917L)
        assertThat(SwiftieTimeline.SIGNATURE_START).isEqualTo(110_917L)
        assertThat(SwiftieTimeline.FINAL_HOLD_START).isEqualTo(115_584L)
        assertThat(SwiftieTimeline.LOVER_BLOOM_START).isEqualTo(119_500L)
        // 淡出挪到配乐之后了：起点就是配乐的最后一帧
        assertThat(SwiftieTimeline.FADE_OUT_START).isEqualTo(125_998L)
    }

    @Test
    fun theFinaleIsOneSegmentBecauseTheSignatureAndTheBraceletShareIt() {
        // 签名的账：写字 + 抬笔 + 收笔闪，三个加数必须正好是账本给这一段的长度
        assertThat(SwiftieTimeline.SIGNATURE_MS).isEqualTo(4_667L)
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
        // 那 2998ms 是配乐自己的尾巴，不是淡出 —— 淡出已经挪到配乐之后了
        assertThat(SwiftieTimeline.TOTAL_MS - SwiftieTimeline.LOVER_BLOOM_END).isEqualTo(2_998L)
    }

    @Test
    fun finalHoldAbsorbsTheSlackAndNeverGoesNegative() {
        // 唯一的弹性段：曲目数或 TTPD 前摇一改，误差全落在这里，不许把 Lover 绽放挤出配乐。
        // 2026-09-18：TTPD 逐行打印加 3000ms，从定格挪出同样长度，10590 → 7590
        // 2026-09-20：补齐四张 TV 独有曲目，其余卡片 117ms/首；5380 → 2949。
        // Midnights 删去两条重复版本后，卡片段缩短的 234ms 补给定格
        // 2026-09-25：签名写字再提速 20%（终局段 5400 → 4667）省下的 733ms 到这里，
        // 同一天又挪给 TTPD 前摇 1500ms（打字放慢 900 + 敲完停顿 600），净 3916 → 2416
        assertThat(SwiftieTimeline.FINAL_HOLD_MS).isEqualTo(2_416L)
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
        // 5900 + 117n；只有 Lover 多停 600ms，TTPD 仍按 130n
        assertThat(SwiftieTimeline.cardDurationMs(index = 0, trackCount = 11)).isEqualTo(7_187L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 1, trackCount = 26)).isEqualTo(8_942L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 6, trackCount = 18)).isEqualTo(8_606L)
        // TTPD 用 Anthology 版 31 首，另有 4700ms 打字机前摇 + 3000ms 逐行放慢
        assertThat(SwiftieTimeline.cardDurationMs(index = 10, trackCount = 31)).isEqualTo(17_630L)
        assertThat(SwiftieTimeline.cardDurationMs(index = 11, trackCount = 12)).isEqualTo(7_304L)
    }

    /**
     * TS2 / TS3 / TS5 让页面背景先演 600ms，卡片推迟长出。
     *
     * 时间从这张卡自己的完整停留里扣，所以 [SwiftieTimeline.cardDurationMs] 一毫秒不动 ——
     * 账本总长、卡片边界与配乐钉死的那两个点全都不该跟着抖。
     */
    @Test
    fun backdropSoloDelaysThreeCardsWithoutTouchingTheLedger() {
        assertThat(SwiftieTimeline.BACKDROP_SOLO_INDICES).containsExactly(1, 2, 4).inOrder()
        assertThat(SwiftieTimeline.cardPrerollMs(1)).isEqualTo(600L)
        assertThat(SwiftieTimeline.cardPrerollMs(3)).isEqualTo(0L)
        // TTPD 的前摇是**加在账本上**的另一种前摇，同一个 helper 认人
        assertThat(SwiftieTimeline.cardPrerollMs(SwiftieTimeline.TTPD_INDEX))
            .isEqualTo(SwiftieTimeline.TTPD_PREROLL_MS)
        SwiftieTimeline.ERA_TRACK_COUNTS.forEachIndexed { index, count ->
            val solo = SwiftieTimeline.cardPrerollMs(index)
            if (index == SwiftieTimeline.TTPD_INDEX) return@forEachIndexed
            // 扣完剩下的窗口仍要盖得住「长出 + 停留 + 回落 + 段间停顿」那一整套固定开销，
            // 否则回落会跑到下一张的份里去
            assertThat(SwiftieTimeline.cardDurationMs(index, count) - solo)
                .isGreaterThan(SwiftieTimeline.CARD_BASE_MS)
        }
    }

    /**
     * TTPD 前摇的两笔账：敲字 + 敲完之后那段静置，加起来必须正好是整个前摇。
     *
     * 三个数都写成**字面量**而不是让 `TTPD_PREROLL_MS` 等于前两项之和 ——
     * `scripts/ttpd-shot.sh` 是用正则从源码里抠 `const val X: Long = 数字` 的，派生表达式
     * 它抠出来是空。于是这条加法只能在这里守：谁改了其中一项不改另一项，要么字锤在纸
     * 已经往外走的时候还在敲，要么前摇尾巴上多出一段没主的空白。
     */
    @Test
    fun ttpdPrerollIsTypingPlusTheHoldAfterIt() {
        assertThat(SwiftieTimeline.TTPD_TYPE_MS + SwiftieTimeline.TTPD_DONE_HOLD_MS)
            .isEqualTo(SwiftieTimeline.TTPD_PREROLL_MS)
        // 静置得真有一口气：零宽的话这一拍等于不存在，上面的加法断言也就白守
        assertThat(SwiftieTimeline.TTPD_DONE_HOLD_MS).isEqualTo(600L)
        assertThat(SwiftieTimeline.TTPD_TYPE_MS).isLessThan(SwiftieTimeline.TTPD_PREROLL_MS)
    }

    @Test
    fun twelveCardsFillTheErasSegment() {
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS).hasSize(12)
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS[10]).isEqualTo(31)
        // 四张 TV 补 40 首独有曲目后总数 244；Midnights 再删两条重复版本，总计 242
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS.sum()).isEqualTo(242)
        assertThat(SwiftieTimeline.ERAS_CARDS_MS).isEqualTo(107_817L)
        assertThat(SwiftieTimeline.ERAS_CARDS_START + SwiftieTimeline.ERAS_CARDS_MS)
            .isEqualTo(SwiftieTimeline.ERAS_CARDS_END)
    }

    @Test
    fun eraLookupIsContiguousAndClamped() {
        assertThat(SwiftieTimeline.eraStartMs(0)).isEqualTo(3_100L)
        assertThat(SwiftieTimeline.eraStartMs(1)).isEqualTo(10_287L)
        assertThat(SwiftieTimeline.eraIndexAt(0L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(3_100L)).isEqualTo(0)
        // 首专 11 首的新时长是 5900 + 117 × 11 = 7187ms，最后 1ms 仍属第 1 张
        assertThat(SwiftieTimeline.eraIndexAt(SwiftieTimeline.eraStartMs(1) - 1L)).isEqualTo(0)
        assertThat(SwiftieTimeline.eraIndexAt(SwiftieTimeline.eraStartMs(1))).isEqualTo(1)
        // 第 12 张（TTPD 之后那张）在 TTPD 前摇拉到 4700ms 后从 103613ms 开始
        assertThat(SwiftieTimeline.eraStartMs(11)).isEqualTo(103_613L)
        assertThat(SwiftieTimeline.eraIndexAt(104_000L)).isEqualTo(11)
        // 边界必须是卡片段末尾，不是 REWIND_START —— 终局夹在两者之间
        assertThat(SwiftieTimeline.eraIndexAt(110_917L)).isNull()
        assertThat(SwiftieTimeline.eraIndexAt(112_000L)).isNull()
    }

    @Test
    fun motionPreheatSitsInsideTheSignatureSegment() {
        // 预热始终钉在收笔前 260ms（签名段起点被 TTPD 前摇推到 110917，落到 114324）
        assertThat(SwiftieTimeline.MOTION_PREHEAT_AT).isEqualTo(114_324L)
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
