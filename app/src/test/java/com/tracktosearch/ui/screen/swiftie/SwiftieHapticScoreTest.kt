package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_DROP_MS
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_SWAY_MAX_DEG
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_SWAY_MS
import com.tracktosearch.ui.screen.swiftie.bracelet.braceletSwayDegrees
import com.tracktosearch.ui.screen.swiftie.eras.AXIS_TICK_FADE_IN_AT
import com.tracktosearch.ui.screen.swiftie.eras.CARD_GROW_MS
import com.tracktosearch.ui.screen.swiftie.eras.SWIFTIE_ERA_EDGES
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasData
import com.tracktosearch.ui.screen.swiftie.eras.TRACK_REVEAL_START_MS
import com.tracktosearch.ui.screen.swiftie.eras.TRACK_STAGGER_MS
import com.tracktosearch.ui.screen.swiftie.eras.swiftieEraCenterFraction
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 谱子的不变式。
 *
 * 这些断言守的都是**听得见但看不见**的错：多发一记、少发一记、发早发晚几百毫秒，
 * 既不崩也不报错，只是 126 秒里某一下不对味 —— 而这条序列没法逐帧回放去比对。
 * 所以每一行都必须有断言压着。
 */
class SwiftieHapticScoreTest {

    private val score = SwiftieHapticScore()
    private val quiet = SwiftieHapticScore(reducedMotion = true)

    private fun SwiftieHapticScore.of(kind: SwiftieHapticCueKind) = cues.filter { it.kind == kind }

    private fun SwiftieHapticScore.discrete() = cues.filterIsInstance<SwiftieDiscreteCue>()

    private fun SwiftieHapticScore.envelopes() = cues.filterIsInstance<SwiftieEnvelopeCue>()

    private fun SwiftieHapticScore.envelope(kind: SwiftieHapticCueKind) =
        envelopes().single { it.kind == kind }

    private fun SwiftieHapticScore.semantics(kind: SwiftieHapticCueKind) =
        of(kind).filterIsInstance<SwiftieDiscreteCue>().map { it.semantic }

    /** [amplitudes] 里局部峰所在的下标。两端不算峰 —— 单调收尾不是一个摆幅。 */
    private fun peakIndices(amplitudes: List<Float>): List<Int> =
        amplitudes.indices.drop(1).dropLast(1).filter { index ->
            amplitudes[index] > amplitudes[index - 1] && amplitudes[index] > amplitudes[index + 1]
        }

    /** 段内偏移 [offsetMs] 落在包络的第几个控制点上。 */
    private fun controlPointAt(envelope: SwiftieEnvelopeCue, offsetMs: Long): Int {
        var cursor = 0L
        envelope.timingsMs.forEachIndexed { index, ms ->
            cursor += ms
            if (offsetMs < cursor) return index
        }
        return envelope.timingsMs.lastIndex
    }

    // ------------------------------------------------------------------------
    // 最重要的一条：卡片段只有 24 记
    // ------------------------------------------------------------------------

    @Test
    fun cardSegmentHasExactlyTwentyFourCues() {
        val inCards = score.cues.filter {
            it.atMs >= SwiftieTimeline.ERAS_CARDS_START && it.atMs < SwiftieTimeline.ERAS_CARDS_END
        }
        // 12 张卡各一记落地 + 12 段曲目列铺完各一记收尾。别的一记都不许有
        assertThat(inCards).hasSize(24)
        assertThat(score.of(SwiftieHapticCueKind.CARD_LAND)).hasSize(10)
        assertThat(score.of(SwiftieHapticCueKind.CARD_LAND_ANCHOR)).hasSize(2)
        assertThat(score.of(SwiftieHapticCueKind.TRACKLIST_DONE)).hasSize(12)
    }

    @Test
    fun tracklistCuesAreOnePerCardNotOnePerTrack() {
        // 187 首曲目，逐曲目就是 96 秒里 180 多次震动 —— 手会麻，也什么都表达不了
        assertThat(SwiftieTimeline.ERA_TRACK_COUNTS.sum()).isEqualTo(187)
        assertThat(score.of(SwiftieHapticCueKind.TRACKLIST_DONE))
            .hasSize(SwiftieTimeline.ERA_TRACK_COUNTS.size)
    }

    @Test
    fun cardCuesLandWhereTheCardAndItsTracklistDo() {
        SwiftieTimeline.ERA_TRACK_COUNTS.forEachIndexed { index, trackCount ->
            val start = SwiftieTimeline.eraStartMs(index)
            // 落地压在长出走完那一刻，不是起手那一刻
            val landing = score.cues.single {
                it.atMs == start + CARD_GROW_MS &&
                    it.kind != SwiftieHapticCueKind.TRACKLIST_DONE
            }
            assertThat(landing.atMs).isEqualTo(start + CARD_GROW_MS)
            // 曲目列收尾压在最后一行淡入的起点上，与 SwiftieEraTracklist 同一个式子
            assertThat(score.of(SwiftieHapticCueKind.TRACKLIST_DONE)[index].atMs)
                .isEqualTo(start + TRACK_REVEAL_START_MS + TRACK_STAGGER_MS * trackCount)
        }
    }

    @Test
    fun anchorCardsLandHeavierThanTheRest() {
        val anchors = score.of(SwiftieHapticCueKind.CARD_LAND_ANCHOR)
            .filterIsInstance<SwiftieDiscreteCue>()
        // 索引 0（Taylor Swift，起点）与 6（Lover，归宿）
        assertThat(anchors.map { it.atMs }).containsExactly(
            SwiftieTimeline.eraStartMs(0) + CARD_GROW_MS,
            SwiftieTimeline.eraStartMs(6) + CARD_GROW_MS,
        ).inOrder()
        assertThat(SwiftieTimeline.ANCHOR_INDICES).containsExactly(0, 6)
        // CONFIRM 在 tier 1 上是 CLICK + THUD 两笔、tier 0 上退 VIRTUAL_KEY；
        // 其余十张的 GESTURE_END 是半幅 THUD、tier 0 上退 CLOCK_TICK —— 逐层都更轻
        anchors.forEach { assertThat(it.semantic).isEqualTo(HapticSemantic.CONFIRM) }
        score.semantics(SwiftieHapticCueKind.CARD_LAND).forEach {
            assertThat(it).isEqualTo(HapticSemantic.GESTURE_END)
        }
        score.semantics(SwiftieHapticCueKind.TRACKLIST_DONE).forEach {
            assertThat(it).isEqualTo(HapticSemantic.FREQUENT_TICK)
        }
    }

    // ------------------------------------------------------------------------
    // 总预算
    // ------------------------------------------------------------------------

    @Test
    fun wholeSequenceStaysInsideTheDiscreteBudget() {
        // 52 记离散摊在 125.998 秒里。轴线那一段只有一记 —— 12 记拉链在屏幕上没有对应物
        assertThat(score.discrete()).hasSize(52)
        assertThat(score.discrete().size).isAtMost(65)
        assertThat(score.envelopes()).hasSize(5)
    }

    @Test
    fun everyCueSitsInsideTheTimelineAndInAscendingOrder() {
        score.cues.forEach { cue ->
            // 0ms 是提交命中那一帧，答题的 confirm() 已经占了，谱子不许再压一记
            assertThat(cue.atMs).isGreaterThan(0L)
            assertThat(cue.atMs).isAtMost(SwiftieTimeline.TOTAL_MS)
        }
        // 指挥每帧只看 (cursor, elapsed] 这一段，谱子乱序就会漏派发
        assertThat(score.cues.map { it.atMs }).isInOrder()
    }

    // ------------------------------------------------------------------------
    // 两段静默
    // ------------------------------------------------------------------------

    @Test
    fun finalHoldIsCompletelySilentBecauseTheUserIsTakingAScreenshot() {
        val inHold = score.cues.filter {
            it.atMs >= SwiftieTimeline.FINAL_HOLD_START && it.atMs < SwiftieTimeline.REWIND_START
        }
        assertThat(inHold).isEmpty()
        // 也不许有包络从手链段拖进定格段里 —— 手上还在震，人已经在截图了
        score.envelopes()
            .filter { it.atMs < SwiftieTimeline.FINAL_HOLD_START }
            .forEach { envelope ->
                assertThat(envelope.atMs + envelope.durationMs)
                    .isAtMost(SwiftieTimeline.FINAL_HOLD_START)
            }
    }

    @Test
    fun fadeOutIsCompletelySilentBecauseTheMusicStops() {
        val inFade = score.cues.filter { it.atMs >= SwiftieTimeline.FADE_OUT_START }
        assertThat(inFade).isEmpty()
        // 绽放那段包络必须正好收在淡出起点上，不许溢进去
        val bloom = score.envelope(SwiftieHapticCueKind.LOVER_BLOOM)
        assertThat(bloom.atMs + bloom.durationMs).isEqualTo(SwiftieTimeline.FADE_OUT_START)
    }

    // ------------------------------------------------------------------------
    // 减少动效：密集的丢掉，结构性的留着
    // ------------------------------------------------------------------------

    @Test
    fun reducedMotionDropsTheDenseRowsOnly() {
        val dropped = SwiftieHapticCueKind.entries.filter { kind ->
            score.of(kind).isNotEmpty() && quiet.of(kind).isEmpty()
        }
        // 只有摆动余震那段包络与它的替身。AXIS_TICK 不在里面 —— 它收成一记
        // 「标尺出现了」之后就是结构性路标了。逐珠那一行不在谱子里（手指驱动）
        assertThat(dropped).containsExactly(
            SwiftieHapticCueKind.BRACELET_SWAY,
            SwiftieHapticCueKind.BRACELET_SWAY_TICK,
        )
        dropped.forEach { assertThat(it.dense).isTrue() }
    }

    @Test
    fun reducedMotionKeepsEveryStructuralCue() {
        // 结构性的那些：卡片落地、签名闪、Lover 绽放 —— 少了它们就不是同一段编排了
        listOf(
            SwiftieHapticCueKind.DIFFUSION_RISE,
            SwiftieHapticCueKind.DIFFUSION_FILL,
            SwiftieHapticCueKind.AXIS_TICK,
            SwiftieHapticCueKind.CARD_LAND_ANCHOR,
            SwiftieHapticCueKind.CARD_LAND,
            SwiftieHapticCueKind.TRACKLIST_DONE,
            SwiftieHapticCueKind.SIGNATURE_WRITE,
            SwiftieHapticCueKind.SIGNATURE_FLASH,
            SwiftieHapticCueKind.BRACELET_DROP,
            SwiftieHapticCueKind.REWIND_TICK,
            SwiftieHapticCueKind.LOVER_BLOOM,
        ).forEach { kind ->
            assertThat(kind.dense).isFalse()
            assertThat(quiet.of(kind)).hasSize(score.of(kind).size)
        }
        // 卡片段那 24 记一记不少
        assertThat(
            quiet.cues.count {
                it.atMs >= SwiftieTimeline.ERAS_CARDS_START &&
                    it.atMs < SwiftieTimeline.ERAS_CARDS_END
            }
        ).isEqualTo(24)
        assertThat(quiet.discrete()).hasSize(49)
        assertThat(quiet.envelopes()).hasSize(4)
    }

    // ------------------------------------------------------------------------
    // 扩散段与轴线段
    // ------------------------------------------------------------------------

    @Test
    fun diffusionRiseFillsExactlyTheDiffusionWindow() {
        val rise = score.envelope(SwiftieHapticCueKind.DIFFUSION_RISE)
        assertThat(rise.atMs).isEqualTo(SwiftieTimeline.DIFFUSION_START)
        assertThat(rise.durationMs).isEqualTo(SwiftieTimeline.DIFFUSION_MS)
        // 单调上升，末格恰好是设计文档写的 0.9
        rise.amplitudes.zipWithNext { low, high -> assertThat(high).isGreaterThan(low) }
        assertThat(rise.amplitudes.last()).isWithin(1e-6f).of(0.9f)
        // 铺满全屏那一帧一记 confirm()，落在包络收束处
        val fill = score.of(SwiftieHapticCueKind.DIFFUSION_FILL).single()
        assertThat(fill.atMs).isEqualTo(rise.atMs + rise.durationMs)
        assertThat(fill.atMs).isEqualTo(SwiftieTimeline.ERAS_INTRO_START)
    }

    @Test
    fun axisSegmentHasExactlyOneCueBecauseAllThirteenTicksFadeInTogether() {
        // 这条是给以后的人判红用的：SwiftieErasAxis 的 13 条刻度共用同一个 tickPhase
        // alpha，没有按 index 的错开。哪天有人给刻度加了 stagger 动画，这里就该跟着
        // 改成多记 —— 先让这条红，别让手上继续描述一件屏幕上没发生的事
        val ticks = score.of(SwiftieHapticCueKind.AXIS_TICK)
        assertThat(ticks).hasSize(1)
        // 整个轴线段（T1100–3100）就这一记
        val inIntro = score.cues.filter {
            it.atMs >= SwiftieTimeline.ERAS_INTRO_START &&
                it.atMs < SwiftieTimeline.ERAS_CARDS_START
        }
        // 另一记是 ERAS_INTRO_START 上的扩散铺满
        assertThat(inIntro.map { it.kind }).containsExactly(
            SwiftieHapticCueKind.DIFFUSION_FILL,
            SwiftieHapticCueKind.AXIS_TICK,
        ).inOrder()
        // 压在刻度开始淡入那一刻，语义是「标尺出现了」
        val fadeInAtMs = SwiftieTimeline.ERAS_INTRO_START +
            (SwiftieTimeline.ERAS_INTRO_MS * AXIS_TICK_FADE_IN_AT).roundToInt()
        assertThat(ticks.single().atMs).isEqualTo(fadeInAtMs)
        // 不许压在两端的段落边界上：撞 ERAS_INTRO_START 会和扩散铺满并成一记，
        // 撞 ERAS_CARDS_START 会把「卡片段只有 24 记」算成 25
        assertThat(ticks.single().atMs).isGreaterThan(SwiftieTimeline.ERAS_INTRO_START)
        assertThat(ticks.single().atMs).isLessThan(SwiftieTimeline.ERAS_CARDS_START)
        assertThat(score.semantics(SwiftieHapticCueKind.AXIS_TICK))
            .containsExactly(HapticSemantic.SEGMENT_TICK)
    }

    // ------------------------------------------------------------------------
    // 签名段：落笔连续、抬笔静默
    // ------------------------------------------------------------------------

    @Test
    fun signatureEnvelopeIsElevenPenStrokesWithTenLifts() {
        val write = score.envelope(SwiftieHapticCueKind.SIGNATURE_WRITE)
        assertThat(SIGNATURE_STROKE_COUNT).isEqualTo(11)
        // 11 段笔画中间只夹 10 个间隙 = 21 个控制点。设计文档写的 22 是数错了一格
        assertThat(write.timingsMs).hasSize(2 * SIGNATURE_STROKE_COUNT - 1)
        assertThat(write.amplitudes).hasSize(write.timingsMs.size)
        assertThat(write.atMs).isEqualTo(SwiftieTimeline.SIGNATURE_START)
        assertThat(write.durationMs)
            .isEqualTo(SIGNATURE_WRITE_MS + SIGNATURE_PAUSE_MS * (SIGNATURE_STROKE_COUNT - 1))
        write.timingsMs.forEachIndexed { index, ms ->
            if (index % 2 == 0) {
                // 落笔：等幅 0.25，是一支笔在纸上走，不是一记敲击
                assertThat(write.amplitudes[index]).isWithin(1e-6f).of(0.25f)
            } else {
                // 抬笔：归零，而且正好一个 SIGNATURE_PAUSE_MS
                assertThat(ms.toLong()).isEqualTo(SIGNATURE_PAUSE_MS)
                assertThat(write.amplitudes[index]).isEqualTo(0f)
            }
        }
        // 落笔那 11 格加起来正好是书写预算，取整误差由末段吸收
        assertThat(write.timingsMs.filterIndexed { index, _ -> index % 2 == 0 }
            .sumOf { it.toLong() })
            .isEqualTo(SIGNATURE_WRITE_MS)
    }

    @Test
    fun signatureStandInsSitOnTheStartOfEveryStroke() {
        val write = score.envelope(SwiftieHapticCueKind.SIGNATURE_WRITE)
        val strokes = score.of(SwiftieHapticCueKind.SIGNATURE_STROKE)
        assertThat(strokes).hasSize(SIGNATURE_STROKE_COUNT)
        // 替身与包络描述的是同一支笔：第 k 记必须压在第 k 段落笔的起点上
        strokes.forEachIndexed { index, cue ->
            val offsetMs = write.timingsMs.take(2 * index).sumOf { it.toLong() }
            assertThat(cue.atMs).isEqualTo(SwiftieTimeline.SIGNATURE_START + offsetMs)
        }
        score.semantics(SwiftieHapticCueKind.SIGNATURE_STROKE).forEach {
            assertThat(it).isEqualTo(HapticSemantic.SEGMENT_TICK)
        }
    }

    @Test
    fun flashFollowsTheLastStrokeWithoutAGapAndStaysInsideTheSegment() {
        val write = score.envelope(SwiftieHapticCueKind.SIGNATURE_WRITE)
        val flash = score.envelope(SwiftieHapticCueKind.SIGNATURE_FLASH)
        // 写完那一刻就闪，中间不留空 —— 空一格手上就断成两件事
        assertThat(flash.atMs).isEqualTo(write.atMs + write.durationMs)
        assertThat(score.of(SwiftieHapticCueKind.SIGNATURE_FLASH_TICK).single().atMs)
            .isEqualTo(flash.atMs)
        // 视觉的闪光是 700ms 的正弦一进一出，手上只取 200ms：闪是「一下」不是「一段」
        assertThat(flash.durationMs).isLessThan(SIGNATURE_FLASH_MS)
        assertThat(flash.amplitudes.max()).isWithin(1e-6f).of(1f)
        assertThat(flash.amplitudes.last()).isEqualTo(0f)
        // 整段签名的触感不许溢出签名段
        assertThat(flash.atMs + flash.durationMs).isAtMost(SwiftieTimeline.BRACELET_START)
    }

    // ------------------------------------------------------------------------
    // 手链段：三记落地 + 一段余震。35 颗珠子不各响一记
    // ------------------------------------------------------------------------

    @Test
    fun braceletLandsInThreeCuesGettingLighterNotThirtyFiveBeads() {
        val drops = score.of(SwiftieHapticCueKind.BRACELET_DROP)
        assertThat(drops).hasSize(3)
        // 第一记不许压在段落起点：那一刻手链还在画面之外，什么都没落下
        assertThat(drops.first().atMs).isGreaterThan(SwiftieTimeline.BRACELET_START)
        // 末记压在落下走完那一刻 —— 最前那条（13 ♡ 87）着地
        assertThat(drops.last().atMs)
            .isEqualTo(SwiftieTimeline.BRACELET_START + BRACELET_DROP_MS)
        val gaps = drops.map { it.atMs }.zipWithNext { previous, next -> next - previous }
        assertThat(gaps.toSet()).hasSize(1)
        // 逐记转轻：满幅 THUD → 半幅 THUD → 0.4 幅 LOW_TICK
        assertThat(score.semantics(SwiftieHapticCueKind.BRACELET_DROP)).containsExactly(
            HapticSemantic.DRAG_START,
            HapticSemantic.GESTURE_END,
            HapticSemantic.SCROLL_EDGE,
        ).inOrder()
    }

    @Test
    fun swayEnvelopeStartsOneStepLateSoTheLandingCannotCutItOff() {
        val sway = score.envelope(SwiftieHapticCueKind.BRACELET_SWAY)
        val lastDrop = score.of(SwiftieHapticCueKind.BRACELET_DROP).last()
        // 落地与起晃本来压在同一毫秒上。平台上后发的一记会掐掉正在播的波形 ——
        // 那记落地会把整段 1700ms 的余震掐死。后移一格（约 100ms）就够，
        // 视觉上「落地即起晃」差一格不可感知
        assertThat(sway.atMs).isEqualTo(lastDrop.atMs + sway.timingsMs.first())
        assertThat(sway.atMs).isGreaterThan(lastDrop.atMs)
        // 后移之后仍然离定格合影段很远
        assertThat(sway.atMs + sway.durationMs).isLessThan(SwiftieTimeline.FINAL_HOLD_START)
    }

    @Test
    fun swayEnvelopeIsTheVisibleSwayCurveMeasuredNotAnInventedOne() {
        val sway = score.envelope(SwiftieHapticCueKind.BRACELET_SWAY)
        assertThat(sway.durationMs).isEqualTo(BRACELET_SWAY_MS)
        // 每格的振幅就是屏幕上那条摆角曲线在格中点的值，归一到 0f..1f。
        // 采样点从**视觉的**摆动起点算（包络自己晚一格才起），所以形状一模一样、整体后移。
        // 两条曲线一旦分家，手上的余震就会和眼里的摆动错开
        val stepMs = BRACELET_SWAY_MS.toFloat() / sway.timingsMs.size
        sway.amplitudes.forEachIndexed { index, amplitude ->
            val sampleAtMs = BRACELET_DROP_MS + (stepMs * (index + 0.5f)).toLong()
            val expected = abs(braceletSwayDegrees(sampleAtMs)) / BRACELET_SWAY_MAX_DEG
            assertThat(amplitude).isWithin(1e-6f).of(expected)
        }
    }

    @Test
    fun swayEnvelopeHasFourEverWeakerPeaksLikeTheDampedSine() {
        val amplitudes = score.envelope(SwiftieHapticCueKind.BRACELET_SWAY).amplitudes
        val peaks = peakIndices(amplitudes)
        // |sin(4πt)| 在 1700ms 里过四个峰，格数必须够密才画得出来
        assertThat(peaks).hasSize(4)
        // 指数衰减：一峰弱于一峰。这也是 RichTap 那层接不下它的原因（只收单峰）
        peaks.map { amplitudes[it] }
            .zipWithNext { high, low -> assertThat(low).isLessThan(high) }
    }

    @Test
    fun swayStandInsSitOnTheFirstThreePeaks() {
        val ticks = score.of(SwiftieHapticCueKind.BRACELET_SWAY_TICK)
        // 第四个峰已经被 exp 衰减压到几乎无感，不值一记
        assertThat(ticks).hasSize(3)
        val sway = score.envelope(SwiftieHapticCueKind.BRACELET_SWAY)
        val peaks = peakIndices(sway.amplitudes)
        ticks.forEachIndexed { index, tick ->
            assertThat(tick.atMs).isAtLeast(sway.atMs)
            assertThat(tick.atMs).isLessThan(sway.atMs + sway.durationMs)
            // 每记必须落在对应那个峰所在的那一格上下 —— 摆到最边上才是该响的一刻。
            // 容一格：替身按解析式的 t = 1/8、3/8、5/8 排，包络是按格中点采样的
            val bucket = controlPointAt(sway, tick.atMs - sway.atMs)
            assertThat(abs(bucket - peaks[index])).isAtMost(1)
        }
        score.semantics(SwiftieHapticCueKind.BRACELET_SWAY_TICK).forEach {
            assertThat(it).isEqualTo(HapticSemantic.FREQUENT_TICK)
        }
        // 整段手链的触感不许溢进定格合影段
        assertThat(sway.atMs + sway.durationMs).isAtMost(SwiftieTimeline.FINAL_HOLD_START)
    }

    // ------------------------------------------------------------------------
    // 倒滑与绽放
    // ------------------------------------------------------------------------

    @Test
    fun rewindTicksCountTheBoundariesThePlayheadFliesBackOver() {
        val ticks = score.of(SwiftieHapticCueKind.REWIND_TICK)
        val loverCenter = swiftieEraCenterFraction(SwiftieErasData.LOVER_INDEX)
        // 记数不是写死的 5，是从轴上真正跨过的那几条边界数出来的：
        // 落在 Lover 中心右侧、且不是起点 1f 的那些
        val crossed = SWIFTIE_ERA_EDGES.count { it > loverCenter && it < 1f }
        assertThat(crossed).isEqualTo(5)
        assertThat(ticks).hasSize(crossed)
        // 倒着飞，所以时刻严格递增而跨过的边界是从右往左
        assertThat(ticks.map { it.atMs }).isInStrictOrder()
        ticks.forEach {
            assertThat(it.atMs).isGreaterThan(SwiftieTimeline.REWIND_START)
            // 不许拖进绽放段：那一段是另一件事，重叠会把最重的一笔糊掉
            assertThat(it.atMs).isLessThan(SwiftieTimeline.LOVER_BLOOM_START)
        }
        score.semantics(SwiftieHapticCueKind.REWIND_TICK).forEach {
            assertThat(it).isEqualTo(HapticSemantic.SEGMENT_TICK)
        }
    }

    @Test
    fun loverBloomIsASingleSlowHumpThatFillsTheWholeBloomWindow() {
        val bloom = score.envelope(SwiftieHapticCueKind.LOVER_BLOOM)
        assertThat(bloom.atMs).isEqualTo(SwiftieTimeline.LOVER_BLOOM_START)
        assertThat(bloom.durationMs).isEqualTo(SwiftieTimeline.LOVER_BLOOM_MS)
        // 单峰：RichTap 那层只收单峰，绽放是整条序列里唯一两层都播得出来的一段
        assertThat(peakIndices(bloom.amplitudes)).hasSize(1)
        assertThat(bloom.amplitudes.max()).isWithin(1e-6f).of(0.85f)
        // 慢升快落：升段要比落段长，「慢升」的重点在慢
        val peak = peakIndices(bloom.amplitudes).single()
        assertThat(peak + 1).isGreaterThan(bloom.amplitudes.size - peak - 1)
        // 收在 0：一段三秒半的包络需要干净的收尾，硬切会听成「被掐断」
        assertThat(bloom.amplitudes.last()).isEqualTo(0f)
    }

    @Test
    fun loverBloomStandInsMarkTheRiseAndThePeak() {
        val bloom = score.envelope(SwiftieHapticCueKind.LOVER_BLOOM)
        val ticks = score.of(SwiftieHapticCueKind.LOVER_BLOOM_TICK)
            .filterIsInstance<SwiftieDiscreteCue>()
        // 表里的「最低层」是 SLOW_RISE + CLICK，拆成起点一记上冲 + 峰值一记敲击
        assertThat(ticks).hasSize(2)
        assertThat(ticks.map { it.semantic }).containsExactly(
            HapticSemantic.THRESHOLD_ARMED,
            HapticSemantic.CONFIRM,
        ).inOrder()
        assertThat(ticks.first().atMs).isEqualTo(bloom.atMs)
        // 峰值那一记必须踩在包络自己那个峰值控制点的末尾上。
        // 别拿 LOVER_BLOOM_MS × 0.7f 去算：0.7f 的真值是 0.69999998，会早一毫秒
        val peak = peakIndices(bloom.amplitudes).single()
        val peakEndMs = bloom.timingsMs.take(peak + 1).sumOf { it.toLong() }
        assertThat(ticks.last().atMs).isEqualTo(bloom.atMs + peakEndMs)
        // 配乐 1:58–2:02 唱 Lover，峰值要压在那句里
        assertThat(ticks.last().atMs).isGreaterThan(SwiftieTimeline.REWIND_START)
        assertThat(ticks.last().atMs).isLessThan(SwiftieTimeline.LOVER_BLOOM_END)
    }

    // ------------------------------------------------------------------------
    // 包络本身要能被 tier 1 收下
    // ------------------------------------------------------------------------

    @Test
    fun everyEnvelopeIsShapedSoTierOneWillNotRejectIt() {
        assertThat(score.envelopes()).isNotEmpty()
        score.envelopes().forEach { envelope ->
            assertThat(envelope.amplitudes).hasSize(envelope.timingsMs.size)
            // createWaveform 拒收 ≤0 的时长；NaN 与越界振幅会在 backend 里抛
            envelope.timingsMs.forEach { assertThat(it).isGreaterThan(0) }
            envelope.amplitudes.forEach { amplitude ->
                assertThat(amplitude.isNaN()).isFalse()
                assertThat(amplitude).isAtLeast(0f)
                assertThat(amplitude).isAtMost(1f)
            }
            assertThat(envelope.durationMs)
                .isEqualTo(envelope.timingsMs.sumOf { it.toLong() })
        }
    }

    @Test
    fun envelopesSortBeforeTheirOwnStandIns() {
        // 指挥要先知道包络被接下了才能把替身丢掉，所以同一毫秒上包络必须在前
        score.envelopes().forEach { envelope ->
            val standIns = score.cues.filter { it.kind.standInFor == envelope.kind }
            assertThat(standIns).isNotEmpty()
            standIns.filter { it.atMs == envelope.atMs }.forEach { standIn ->
                assertThat(score.cues.indexOf(envelope))
                    .isLessThan(score.cues.indexOf(standIn))
            }
        }
    }

    @Test
    fun noEnvelopeSharesAMillisecondWithAnUnrelatedDiscreteCue() {
        // 同一毫秒上排一段包络和一记无关的离散事件，等于让后发的那一记去掐前一记 ——
        // 手链落地与摆动余震曾经就是这样，1700ms 的余震被一记落地掐死。
        // buildScore 里「无关离散排在包络之前」那条排序规则是第二道防线，
        // 但真正该做的是**一开始就不要撞**，这条钉住的是那个
        score.envelopes().forEach { envelope ->
            val collided = score.cues.filter {
                it.atMs == envelope.atMs &&
                    it !== envelope &&
                    it.kind.standInFor != envelope.kind
            }
            assertThat(collided).isEmpty()
        }
    }

    // ------------------------------------------------------------------------
    // cuesIn 的区间语义
    // ------------------------------------------------------------------------

    @Test
    fun cuesInIsLeftOpenAndRightClosed() {
        val first = score.cues.first()
        // 右闭：端点那一记这一帧就该发出去
        assertThat(score.cuesIn(0L, first.atMs)).contains(first)
        // 左开：下一帧游标推到端点上，同一记不许再发一次
        assertThat(score.cuesIn(first.atMs, first.atMs + 1)).doesNotContain(first)
        // 时钟没走（暂停）或倒着走，什么都不该发
        assertThat(score.cuesIn(first.atMs, first.atMs)).isEmpty()
        assertThat(score.cuesIn(SwiftieTimeline.TOTAL_MS, 0L)).isEmpty()
    }

    @Test
    fun walkingTheWholeTimelineFrameByFrameYieldsEveryCueExactlyOnce() {
        // 16ms 一帧走完 126 秒，一记不漏、一记不重 —— 这条兜住的是区间端点上的差一错
        val collected = mutableListOf<SwiftieHapticCue>()
        var cursor = 0L
        while (cursor < SwiftieTimeline.TOTAL_MS) {
            val next = (cursor + 16L).coerceAtMost(SwiftieTimeline.TOTAL_MS)
            collected += score.cuesIn(cursor, next)
            cursor = next
        }
        assertThat(collected).containsExactlyElementsIn(score.cues).inOrder()
    }
}
