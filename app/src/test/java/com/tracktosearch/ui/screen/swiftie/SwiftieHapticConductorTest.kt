package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.ui.haptic.HapticSemantic
import org.junit.Test

/**
 * 指挥的三条规则：seek 丢弃、闸门丢弃、包络替身抑制。
 *
 * 全是**丢弃**语义 —— 三条都在防同一类事故：欠下的几十记被一次性补发成一串乱码。
 * 用户拖轴线跳到第 9 张卡、息屏两分钟再点亮、按住暂停十秒，
 * 都不能让期间跨过的事件在恢复那一帧堆着响出来。
 */
class SwiftieHapticConductorTest {

    /**
     * 把三个注入的口子录下来。
     *
     * @param envelopeAccepted `playEnvelope` 的返回值。false 模拟「本机播不了包络」
     */
    private class Recorder(private val envelopeAccepted: Boolean = true) {
        val performed = mutableListOf<HapticSemantic>()

        /** 每段被接下的包络有几个控制点。 */
        val envelopes = mutableListOf<Int>()
        var quietDowns = 0

        fun conductor(score: SwiftieHapticScore = SwiftieHapticScore()) = SwiftieHapticConductor(
            score = score,
            perform = { semantic ->
                performed += semantic
                true
            },
            playEnvelope = { timingsMs, amplitudes ->
                assertThat(amplitudes).hasLength(timingsMs.size)
                envelopes += timingsMs.size
                envelopeAccepted
            },
            quietDown = { quietDowns++ },
        )
    }

    /** 逐帧走完整条序列。 */
    private fun SwiftieHapticConductor.runWholeSequence(
        muted: Boolean = false,
        frameMs: Long = 16L,
    ): List<SwiftieHapticCue> {
        val dispatched = mutableListOf<SwiftieHapticCue>()
        var cursor = 0L
        while (cursor < SwiftieTimeline.TOTAL_MS) {
            cursor = (cursor + frameMs).coerceAtMost(SwiftieTimeline.TOTAL_MS)
            dispatched += onFrame(elapsedMs = cursor, seekEpoch = 0, muted = muted)
        }
        return dispatched
    }

    // ------------------------------------------------------------------------
    // 规则一：seek 丢弃，不补发
    // ------------------------------------------------------------------------

    @Test
    fun seekDiscardsEverythingItFlewOverInsteadOfReplayingIt() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        // 正常走到第一张卡落地之后
        val before = conductor.onFrame(elapsedMs = 3_600L, seekEpoch = 0, muted = false)
        assertThat(before).isNotEmpty()
        val dispatchedBeforeSeek = recorder.performed.size

        // 用户把轴线拖到第 7 张卡。拨过那一帧一记都不许发
        val onSeek = conductor.onFrame(elapsedMs = 50_000L, seekEpoch = 1, muted = false)
        assertThat(onSeek).isEmpty()
        // 跨过去的那 11 记（5 记落地 + 6 记曲目列收尾）就此作废，不排队等着
        assertThat(recorder.performed).hasSize(dispatchedBeforeSeek)

        // 拨完之后从新位置接着走：50160 是第 7 张（Lover，锚点）落地
        val after = conductor.onFrame(elapsedMs = 50_200L, seekEpoch = 1, muted = false)
        assertThat(after.map { it.kind })
            .containsExactly(SwiftieHapticCueKind.CARD_LAND_ANCHOR)
        assertThat(recorder.performed.last()).isEqualTo(HapticSemantic.CONFIRM)
    }

    @Test
    fun seekingForwardOverAnEnvelopeDoesNotStartIt() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        conductor.onFrame(elapsedMs = 200L, seekEpoch = 0, muted = false)
        // 用户在扩散段还没走完就把轴线拖到签名段末尾。跨过去的两段包络一段都不许起 ——
        // 补发一段 7300ms 的签名包络，手上会在完全不相干的画面上震七秒
        assertThat(conductor.onFrame(elapsedMs = 106_000L, seekEpoch = 1, muted = false)).isEmpty()
        assertThat(recorder.envelopes).isEmpty()
        assertThat(recorder.performed).isEmpty()
    }

    @Test
    fun seekingBackwardsResumesFromTheNewSpotWithoutReplaying() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        conductor.onFrame(elapsedMs = 60_000L, seekEpoch = 0, muted = false)
        recorder.performed.clear()
        recorder.envelopes.clear()
        // 往回拨到扩散段之前
        assertThat(conductor.onFrame(elapsedMs = 200L, seekEpoch = 1, muted = false)).isEmpty()
        assertThat(conductor.onFrame(elapsedMs = 300L, seekEpoch = 1, muted = false)).isEmpty()
        // 300 → 500 跨过 400，那一记该发 —— 拨完之后是正常播放，不是静音
        assertThat(conductor.onFrame(elapsedMs = 500L, seekEpoch = 1, muted = false)).isNotEmpty()
        assertThat(recorder.envelopes).hasSize(1)
    }

    // ------------------------------------------------------------------------
    // 规则二：闸门静音也是丢弃，不是攒着
    // ------------------------------------------------------------------------

    @Test
    fun mutedFramesDiscardInsteadOfQueueingUp() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        // 按住暂停 / 息屏 / 焦点丢失：期间照样推游标，只是不派发
        assertThat(conductor.onFrame(elapsedMs = 3_600L, seekEpoch = 0, muted = true)).isEmpty()
        assertThat(recorder.performed).isEmpty()
        assertThat(recorder.envelopes).isEmpty()

        // 闸门重开之后从当时的时刻接着走，欠下的十几记不许补发成一串乱码
        assertThat(conductor.onFrame(elapsedMs = 3_700L, seekEpoch = 0, muted = false)).isEmpty()
        // 4930 是第一张卡曲目列铺完，那一记是闸门重开后第一条到期的
        val resumed = conductor.onFrame(elapsedMs = 5_000L, seekEpoch = 0, muted = false)
        assertThat(resumed.map { it.kind })
            .containsExactly(SwiftieHapticCueKind.TRACKLIST_DONE)
    }

    @Test
    fun aStalledOrRewoundClockEmitsNothing() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        assertThat(conductor.onFrame(elapsedMs = 1_200L, seekEpoch = 0, muted = false))
            .isNotEmpty()
        // 同一毫秒再来一帧（时钟被暂停顶住）：同一记不许发两次
        assertThat(conductor.onFrame(elapsedMs = 1_200L, seekEpoch = 0, muted = false)).isEmpty()
        // 倒着走（不该发生，但别让它把一整段重放一遍）
        assertThat(conductor.onFrame(elapsedMs = 900L, seekEpoch = 0, muted = false)).isEmpty()
        // 游标跟着退回去了，所以 1100 那一记会再到期一次 —— 这是「时钟倒流」本身的账，
        // 不是补发。真正的拨轴走 seekEpoch，上面那两条测的是那条路
        assertThat(conductor.onFrame(elapsedMs = 1_200L, seekEpoch = 0, muted = false))
            .isNotEmpty()
    }

    // ------------------------------------------------------------------------
    // 规则三：包络被接下时它的替身要丢掉
    // ------------------------------------------------------------------------

    @Test
    fun standInIsDroppedOnceItsEnvelopeIsTaken() {
        val recorder = Recorder(envelopeAccepted = true)
        val conductor = recorder.conductor()
        // 400ms 上同时排着扩散包络与它的替身
        val dispatched = conductor.onFrame(elapsedMs = 500L, seekEpoch = 0, muted = false)
        assertThat(dispatched.map { it.kind })
            .containsExactly(SwiftieHapticCueKind.DIFFUSION_RISE)
        assertThat(recorder.envelopes).hasSize(1)
        // 替身一记都没发出去 —— 否则同一段落会同时听到包络与替身
        assertThat(recorder.performed).isEmpty()
    }

    @Test
    fun standInFiresWhenTheEnvelopeCannotBePlayedOnThisDevice() {
        val recorder = Recorder(envelopeAccepted = false)
        val conductor = recorder.conductor()
        val dispatched = conductor.onFrame(elapsedMs = 500L, seekEpoch = 0, muted = false)
        // 包络与替身都算派发：包络试过了（没人接），替身补上
        assertThat(dispatched.map { it.kind }).containsExactly(
            SwiftieHapticCueKind.DIFFUSION_RISE,
            SwiftieHapticCueKind.DIFFUSION_RISE_TICK,
        ).inOrder()
        assertThat(recorder.performed).containsExactly(HapticSemantic.THRESHOLD_ARMED)
    }

    @Test
    fun suppressionIsPerEnvelopeNotGlobal() {
        val recorder = Recorder(envelopeAccepted = true)
        val conductor = recorder.conductor()
        // 走到手链段。三记落地与余震包络落在同一帧里（包络只比末记落地晚一格），
        // 但落地不是余震的替身，包络被接下也不许把它们一起吞掉
        conductor.onFrame(elapsedMs = SwiftieTimeline.BRACELET_START, seekEpoch = 0, muted = false)
        recorder.performed.clear()
        recorder.envelopes.clear()
        val landing = conductor.onFrame(
            elapsedMs = SwiftieTimeline.BRACELET_START + SwiftieTimeline.BRACELET_MS,
            seekEpoch = 0,
            muted = false,
        )
        assertThat(landing.map { it.kind }).contains(SwiftieHapticCueKind.BRACELET_DROP)
        assertThat(landing.map { it.kind }).contains(SwiftieHapticCueKind.BRACELET_SWAY)
        // 余震的三记替身才是被吞掉的那些
        assertThat(landing.map { it.kind })
            .doesNotContain(SwiftieHapticCueKind.BRACELET_SWAY_TICK)
        assertThat(recorder.performed).containsExactly(
            HapticSemantic.DRAG_START,
            HapticSemantic.GESTURE_END,
            HapticSemantic.SCROLL_EDGE,
        ).inOrder()
    }

    // ------------------------------------------------------------------------
    // stopOngoing：没播过包络就连引擎都不解析
    // ------------------------------------------------------------------------

    @Test
    fun stopOngoingDoesNothingBeforeAnyEnvelopeHasBeenPlayed() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        // 页面刚挂载。Provider.get() 第一次要跑完整套设备能力探测，
        // 那笔阻塞不该由「什么都还没响过」的时刻付
        conductor.stopOngoing()
        assertThat(recorder.quietDowns).isEqualTo(0)

        // 闸门关着跨过了包络也一样：一记都没派发出去
        conductor.onFrame(elapsedMs = 1_000L, seekEpoch = 0, muted = true)
        conductor.stopOngoing()
        assertThat(recorder.quietDowns).isEqualTo(0)
    }

    @Test
    fun stopOngoingForwardsOnceAnEnvelopeHasBeenPlayed() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        conductor.onFrame(elapsedMs = 500L, seekEpoch = 0, muted = false)
        conductor.stopOngoing()
        assertThat(recorder.quietDowns).isEqualTo(1)
        // 可以白调：停一个已经停了的引擎不是错
        conductor.stopOngoing()
        assertThat(recorder.quietDowns).isEqualTo(2)
    }

    @Test
    fun anEnvelopeThatNoLayerTookStillArmsStopOngoing() {
        // playEnvelope 返回 false 只是说「本机没人接」，波形可能已经在某一层上跑了一半，
        // 该停还得停。everPlayedEnvelope 记的是「派发过」，不是「被接下过」
        val recorder = Recorder(envelopeAccepted = false)
        val conductor = recorder.conductor()
        conductor.onFrame(elapsedMs = 500L, seekEpoch = 0, muted = false)
        conductor.stopOngoing()
        assertThat(recorder.quietDowns).isEqualTo(1)
    }

    // ------------------------------------------------------------------------
    // 整条跑一遍
    // ------------------------------------------------------------------------

    @Test
    fun runningTheWholeSequenceDispatchesEveryCueExactlyOnce() {
        val recorder = Recorder(envelopeAccepted = false)
        val score = SwiftieHapticScore()
        val dispatched = recorder.conductor(score).runWholeSequence()
        // 没人接包络，所以谱子上每一条都该露面：53 记离散 + 5 段包络。
        // 离散那个数跟着 SwiftieSignaturePath 的笔数走（现在 12 笔，'i' 上那一点单独算
        // 一笔），重新子集化字形导致笔数变化时这三条用例的数字都要跟着改
        assertThat(dispatched).containsExactlyElementsIn(score.cues).inOrder()
        assertThat(recorder.performed).hasSize(53)
        assertThat(recorder.envelopes).hasSize(5)
    }

    @Test
    fun workingEnvelopesSilenceAllEighteenStandIns() {
        val recorder = Recorder(envelopeAccepted = true)
        val score = SwiftieHapticScore()
        val dispatched = recorder.conductor(score).runWholeSequence()
        val standIns = score.cues.count { it.kind.standInFor != null }
        // 1 扩散 + 12 笔画 + 1 闪光 + 3 余震 + 2 绽放
        assertThat(standIns).isEqualTo(19)
        assertThat(dispatched).hasSize(score.cues.size - standIns)
        assertThat(recorder.performed).hasSize(53 - standIns)
        assertThat(recorder.envelopes).hasSize(5)
    }

    @Test
    fun aFullyMutedRunNeverTouchesTheEngine() {
        val recorder = Recorder()
        val conductor = recorder.conductor()
        // 用户按住暂停从头到尾（或者息屏了整段）：一记都不许响，也不许解析引擎
        assertThat(conductor.runWholeSequence(muted = true)).isEmpty()
        assertThat(recorder.performed).isEmpty()
        assertThat(recorder.envelopes).isEmpty()
        conductor.stopOngoing()
        assertThat(recorder.quietDowns).isEqualTo(0)
    }

    @Test
    fun reducedMotionRunSkipsTheDenseRowsAtDispatchTimeToo() {
        val recorder = Recorder(envelopeAccepted = false)
        val score = SwiftieHapticScore(reducedMotion = true)
        val dispatched = recorder.conductor(score).runWholeSequence()
        // 余震整段消失，「标尺出现了」那一记留着 —— 它是结构性路标，不是密集事件
        assertThat(dispatched.map { it.kind }).doesNotContain(SwiftieHapticCueKind.BRACELET_SWAY)
        assertThat(dispatched.map { it.kind })
            .doesNotContain(SwiftieHapticCueKind.BRACELET_SWAY_TICK)
        assertThat(dispatched.map { it.kind }).contains(SwiftieHapticCueKind.AXIS_TICK)
        assertThat(recorder.performed).hasSize(50)
        assertThat(recorder.envelopes).hasSize(4)
    }
}
