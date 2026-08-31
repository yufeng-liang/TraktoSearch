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
        val earlyInStep = phaseAt(0.33f)
        val lateInStep = phaseAt(0.42f)
        assertThat(lateInStep.revealFraction).isWithin(TOLERANCE).of(earlyInStep.revealFraction)

        val nextStep = phaseAt(0.45f)
        assertThat(nextStep.revealFraction).isNotWithin(TOLERANCE).of(lateInStep.revealFraction)
    }

    @Test
    fun phaseB_endsWithTheWholeTicketOut() {
        assertThat(phaseAt(0.78f).revealFraction).isWithin(TOLERANCE).of(1f)
        assertThat(phaseAt(0.7857f).revealFraction).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun phaseC_overshootStartsPositiveAndDecaysWithProgress() {
        val early = phaseAt(0.79f).overshootDp
        val middle = phaseAt(0.82f).overshootDp
        val late = phaseAt(0.86f).overshootDp

        assertThat(early).isGreaterThan(0f)
        assertThat(middle).isGreaterThan(0f)
        assertThat(late).isGreaterThan(0f)
        assertThat(middle).isLessThan(early)
        assertThat(late).isLessThan(middle)
    }

    @Test
    fun phaseC_overshootIsBackToZeroOnceThePaperSettles() {
        assertThat(phaseAt(0.9f).overshootDp).isWithin(TOLERANCE).of(0f)
        assertThat(phaseAt(1f).overshootDp).isWithin(TOLERANCE).of(0f)
    }

    @Test
    fun phaseD_fadesRowsInOneByOneAndStopsAtThree() {
        assertThat(phaseAt(0.8929f).rowsVisible).isEqualTo(1)
        assertThat(phaseAt(0.9286f).rowsVisible).isEqualTo(2)
        assertThat(phaseAt(0.9643f).rowsVisible).isEqualTo(3)
        // 票上只有三行，最后一段不能再往上加
        assertThat(phaseAt(0.99f).rowsVisible).isEqualTo(3)
        assertThat(phaseAt(1f).rowsVisible).isEqualTo(3)
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

    private companion object {
        const val TOLERANCE = 0.0001f

        /** 逐帧扫描的采样数，对应 0.01f 步长 */
        const val FRAMES = 100
    }
}
