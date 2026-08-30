package com.tracktosearch.ui.screen.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import org.junit.Test

class AiSceneArtTest {

    @Test
    fun watchlistSceneStartsOnlyForANewerRevision() {
        assertThat(shouldShowWatchlistAddedScene(previousRevision = 0L, currentRevision = 1L)).isTrue()
        assertThat(shouldShowWatchlistAddedScene(previousRevision = 1L, currentRevision = 1L)).isFalse()
        assertThat(shouldShowWatchlistAddedScene(previousRevision = 3L, currentRevision = 2L)).isFalse()
    }

    @Test
    fun featureSceneStartsOnlyAfterACompletedLoadRevision() {
        assertThat(shouldShowSceneForRevision(previousRevision = 0L, currentRevision = 1L)).isTrue()
        assertThat(shouldShowSceneForRevision(previousRevision = 1L, currentRevision = 1L)).isFalse()
        assertThat(shouldShowSceneForRevision(previousRevision = 2L, currentRevision = 0L)).isFalse()
    }

    @Test
    fun quizSceneRewardsPerfectScoresAndEncouragesAllOtherResults() {
        assertThat(quizSceneEvent(score = 100, totalScore = 100))
            .isEqualTo(AiSceneEvent.QUIZ_PERFECT)
        assertThat(quizSceneEvent(score = 70, totalScore = 100))
            .isEqualTo(AiSceneEvent.QUIZ_RESULT)
        assertThat(quizSceneEvent(score = 0, totalScore = 100))
            .isEqualTo(AiSceneEvent.QUIZ_RESULT)
    }

}
