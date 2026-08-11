package com.tracktosearch.ui.screen.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.ai.AiQuizQuestionType
import org.junit.Test

class AiUiLogicTest {

    @Test
    fun activationAllowsTwoRetriesAndStopsAtThirdAttempt() {
        assertThat(canRetryActivation(1)).isTrue()
        assertThat(canRetryActivation(2)).isTrue()
        assertThat(canRetryActivation(3)).isFalse()
    }

    @Test
    fun quizProgressIsClampedToStableQuestionRange() {
        assertThat(quizProgress(current = 0, total = 13)).isEqualTo(0f)
        assertThat(quizProgress(current = 4, total = 13)).isEqualTo(4f / 13f)
        assertThat(quizProgress(current = 18, total = 13)).isEqualTo(1f)
    }

    @Test
    fun localQuizScoreUsesConfiguredQuestionWeights() {
        assertThat(localQuestionScore(AiQuizQuestionType.SINGLE, answered = true)).isEqualTo(7)
        assertThat(localQuestionScore(AiQuizQuestionType.MULTIPLE, answered = true)).isEqualTo(10)
        assertThat(localQuestionScore(AiQuizQuestionType.SHORT, answered = true)).isEqualTo(10)
        assertThat(localQuestionScore(AiQuizQuestionType.SINGLE, answered = false)).isEqualTo(0)
    }
}
