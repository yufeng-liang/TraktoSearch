package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieQuizStateTest {

    private fun type(vararg digits: Char): SwiftieQuizState =
        digits.fold(SwiftieQuizState()) { state, digit -> state.append(digit) }

    @Test
    fun append_capsAtTwoDigitsAndIgnoresNonDigits() {
        assertThat(type('1', '3').input).isEqualTo("13")
        // 第三位被丢弃，不是覆盖
        assertThat(type('1', '3', '7').input).isEqualTo("13")
        assertThat(type('1', 'X').input).isEqualTo("1")
    }

    @Test
    fun canSubmit_onlyWhenExactlyTwoDigits() {
        assertThat(SwiftieQuizState().canSubmit).isFalse()
        assertThat(type('1').canSubmit).isFalse()
        assertThat(type('1', '3').canSubmit).isTrue()
        // 答对后提交键不再可用，防止重复触发解锁
        assertThat(type('1', '3').submit().canSubmit).isFalse()
    }

    @Test
    fun submit_correctAnswerSolves() {
        val solved = type('1', '3').submit()
        assertThat(solved.phase).isEqualTo(SwiftieQuizPhase.SOLVED)
        assertThat(solved.solved).isTrue()
        assertThat(solved.wrongCount).isEqualTo(0)
        // 答对定格在 13，上行要用它渲染成 13 + 87 = 100
        assertThat(solved.input).isEqualTo("13")
    }

    @Test
    fun submit_wrongAnswerCountsAndKeepsInputForShake() {
        val wrong = type('1', '2').submit()
        assertThat(wrong.phase).isEqualTo(SwiftieQuizPhase.WRONG)
        assertThat(wrong.wrongCount).isEqualTo(1)
        // 摇晃动画期间还要看得见自己填错的数字，清空交给 clearWrong
        assertThat(wrong.input).isEqualTo("12")
    }

    @Test
    fun wrongState_clearsOnTimeoutOrOnNextKey() {
        val wrong = type('1', '2').submit()
        assertThat(wrong.clearWrong().input).isEmpty()
        assertThat(wrong.clearWrong().phase).isEqualTo(SwiftieQuizPhase.INPUT)
        // 摇晃还没结束就继续按键：当作重新开始，而不是接在 12 后面
        assertThat(wrong.append('9').input).isEqualTo("9")
        assertThat(wrong.backspace().input).isEmpty()
        // clearWrong 对非 WRONG 态是空操作
        assertThat(type('1').clearWrong().input).isEqualTo("1")
    }

    @Test
    fun luckyHint_appearsOnThirdWrongAndStays() {
        var state = SwiftieQuizState()
        repeat(2) { state = state.append('1').append('2').submit().clearWrong() }
        assertThat(state.showLuckyHint).isFalse()
        state = state.append('1').append('2').submit()
        assertThat(state.wrongCount).isEqualTo(3)
        assertThat(state.showLuckyHint).isTrue()
        // 提示出现后不再收回
        assertThat(state.clearWrong().showLuckyHint).isTrue()
    }
}
