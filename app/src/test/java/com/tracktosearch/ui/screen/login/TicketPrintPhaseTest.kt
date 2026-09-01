package com.tracktosearch.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TicketPrintPhaseTest {

    @Test
    fun phaseAt_startsWithNothingOutOfTheSlot() {
        val phase = phaseAt(0f)

        assertThat(phase.revealFraction).isWithin(TOLERANCE).of(0f)
        assertThat(phase.rowsVisible).isEqualTo(0)
        assertThat(phase.barcodeVisible).isFalse()
        assertThat(phase.feedStep).isEqualTo(0)
    }

    @Test
    fun phaseAt_endMatchesTheFinalPhaseConstant() {
        // 关掉系统动画时直接拿常量当终态，两者必须一致，否则跳过动画的用户看到的是另一张票
        assertThat(phaseAt(1f)).isEqualTo(TICKET_PRINT_FINAL_PHASE)
    }

    @Test
    fun phaseAt_clampsProgressOutsideZeroToOne() {
        assertThat(phaseAt(-1f)).isEqualTo(phaseAt(0f))
        assertThat(phaseAt(2f)).isEqualTo(phaseAt(1f))
    }

    @Test
    fun phaseB_advancesInStepsInsteadOfInterpolating() {
        // 走纸这一段必须是阶梯：热敏打印机是步进出纸，改成线性插值就变成匀速滑出，
        // 机械感全没了。回归时最容易被顺手「优化」掉的正是这里，所以同一步内取两点比相等
        val earlyInStep = at(460)
        val lateInStep = at(590)
        assertThat(lateInStep.revealFraction).isWithin(TOLERANCE).of(earlyInStep.revealFraction)
        assertThat(lateInStep.feedStep).isEqualTo(earlyInStep.feedStep)

        val nextStep = at(630)
        assertThat(nextStep.revealFraction).isNotWithin(TOLERANCE).of(lateInStep.revealFraction)
        assertThat(nextStep.feedStep).isGreaterThan(lateInStep.feedStep)
    }

    @Test
    fun phaseB_endsWithTheWholeTicketOut() {
        assertThat(at(1090).revealFraction).isWithin(TOLERANCE).of(1f)
        assertThat(at(1099).revealFraction).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun phaseC_overshootStartsPositiveAndDecaysWithProgress() {
        val early = at(1106).overshootDp
        val middle = at(1148).overshootDp
        val late = at(1204).overshootDp

        assertThat(early).isGreaterThan(0f)
        assertThat(middle).isGreaterThan(0f)
        assertThat(late).isGreaterThan(0f)
        assertThat(middle).isLessThan(early)
        assertThat(late).isLessThan(middle)
    }

    @Test
    fun phaseC_overshootIsBackToZeroOnceThePaperSettles() {
        assertThat(at(1260).overshootDp).isWithin(TOLERANCE).of(0f)
        assertThat(phaseAt(1f).overshootDp).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun phaseD_fadesRowsInOneByOneAndStopsAtThree() {
        // 采样点取每档的中段（+25ms），不落在换行的那一刻：换行判定是
        // (elapsedMs / 50).toInt()，边界上两个 float 相减差个 1e-5 就会截到上一档，
        // 测的是「第几档亮几行」而不是「边界归哪一档」
        assertThat(at(1250).rowsVisible).isEqualTo(1)
        assertThat(at(1275).rowsVisible).isEqualTo(1)
        assertThat(at(1325).rowsVisible).isEqualTo(2)
        assertThat(at(1375).rowsVisible).isEqualTo(3)
        // 票上只有三行，最后一段不能再往上加
        assertThat(at(1386).rowsVisible).isEqualTo(3)
        assertThat(phaseAt(1f).rowsVisible).isEqualTo(3)
    }

    @Test
    fun barcode_appearsOnlyAfterTheRowsAreIn() {
        assertThat(at(1325).barcodeVisible).isFalse()
        assertThat(at(1375).barcodeVisible).isTrue()
    }

    @Test
    fun revealFraction_neverGoesBackwards() {
        // 阶段边界算错（分母写错、上下界写反）最典型的症状就是交界处票面回缩一下，
        // 逐帧扫一遍才抓得住
        var previous = 0f
        for (frame in 0..FRAMES) {
            val progress = frame / FRAMES.toFloat()
            val reveal = phaseAt(progress).revealFraction
            assertWithMessage("progress=$progress 处露出比例回跳").that(reveal).isAtLeast(previous)
            previous = reveal
        }
        assertThat(previous).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun overshootDp_neverGoesNegative() {
        for (frame in 0..FRAMES) {
            val progress = frame / FRAMES.toFloat()
            assertWithMessage("progress=$progress 处下移量为负")
                .that(phaseAt(progress).overshootDp).isAtLeast(0f)
        }
    }

    @Test
    fun feedStep_stopsAtExactlySixDistinctTicks() {
        // 触觉是「feedStep 每递增一级抖一次」，所以走完整段出现几个不同的正值就抖几下。
        // 这条盯的是次数：早先挂在 revealFraction 上时，探头段的连续插值会先糊出
        // 七八次连击（且帧率越高越多），六声「咔」听不出来。
        val steps = (0..FRAMES).map { phaseAt(it / FRAMES.toFloat()).feedStep }

        assertThat(steps.filter { it > 0 }.distinct()).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
        assertThat(steps.first()).isEqualTo(0)
        assertThat(steps.last()).isEqualTo(6)
    }

    @Test
    fun feedStep_neverGoesBackwards() {
        var previous = 0
        for (frame in 0..FRAMES) {
            val progress = frame / FRAMES.toFloat()
            val step = phaseAt(progress).feedStep
            assertWithMessage("progress=$progress 处走纸步序回跳").that(step).isAtLeast(previous)
            previous = step
        }
    }

    @Test
    fun feedStep_staysZeroWhileTheHeadIsStillComingOut() {
        // 探头段不抖：纸还没开始步进，先抖起来就没有「探头 → 走纸」的层次
        assertThat(at(0).feedStep).isEqualTo(0)
        assertThat(at(60).feedStep).isEqualTo(0)
        assertThat(at(119).feedStep).isEqualTo(0)
    }

    private companion object {
        const val TOLERANCE = 0.0001f

        /** 逐帧扫描的采样数，对应 0.01f 步长 */
        const val FRAMES = 100

        /**
         * 毫秒转进度。用毫秒写断言而不是写死 0.8929f 这类小数：
         * 阶段边界在生产代码里也是「毫秒 / 总时长」，改 [TICKET_PRINT_DURATION_MS]
         * 时两边一起动，测试不会一片红还看不出红在哪。
         */
        fun at(ms: Int): PrintPhase = phaseAt(ms / TICKET_PRINT_DURATION_MS.toFloat())
    }
}
