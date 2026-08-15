package com.tracktosearch.ui.screen.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import org.junit.Test

class AiSceneArtTest {

    @Test
    fun searchTriggersUseDeterministicSemanticScenes() {
        assertThat(sceneEventForSearch(AiSpriteOverlayTrigger.FIRST_ENTRY))
            .isEqualTo(AiSceneEvent.SEARCH_FIRST_ENTRY)
        assertThat(sceneEventForSearch(AiSpriteOverlayTrigger.IDLE))
            .isEqualTo(AiSceneEvent.SEARCH_IDLE)
        assertThat(sceneEventForSearch(AiSpriteOverlayTrigger.SEARCH_COMPLETED))
            .isEqualTo(AiSceneEvent.SEARCH_COMPLETED)
    }

    @Test
    fun searchSceneAnchorsKeepTheSearchBoxScenesAboveTheSearchBox() {
        assertThat(searchAnchorFor(AiSpriteOverlayTrigger.FIRST_ENTRY))
            .isEqualTo(AiSpriteAnchor.SearchBox)
        assertThat(searchAnchorFor(AiSpriteOverlayTrigger.IDLE))
            .isEqualTo(AiSpriteAnchor.SearchBox)
        assertThat(searchAnchorFor(AiSpriteOverlayTrigger.SEARCH_COMPLETED))
            .isEqualTo(AiSpriteAnchor.ResultCard)
    }

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

    @Test
    fun tasteScenePrioritizesRecommendationInviteWhenCardsExist() {
        assertThat(tasteSceneEvent(hasRecommendations = true))
            .isEqualTo(AiSceneEvent.AI_RECOMMENDATIONS)
        assertThat(tasteSceneEvent(hasRecommendations = false))
            .isEqualTo(AiSceneEvent.AI_TASTE)
    }

    @Test
    fun everySemanticSceneHasAStableResourceAndAnchor() {
        assertThat(sceneArtFor(AiSceneEvent.SEARCH_FIRST_ENTRY)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.SEARCH_FIRST_ENTRY, R.drawable.ai_scene_search_first_entry, AiSpriteAnchor.SearchBox)
        )
        assertThat(sceneArtFor(AiSceneEvent.SEARCH_IDLE)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.SEARCH_IDLE, R.drawable.ai_scene_search_idle, AiSpriteAnchor.SearchBox)
        )
        assertThat(sceneArtFor(AiSceneEvent.SEARCH_COMPLETED)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.SEARCH_COMPLETED, R.drawable.ai_scene_search_completed, AiSpriteAnchor.ResultCard)
        )
        assertThat(sceneArtFor(AiSceneEvent.DETAIL_WATCHLIST_ADDED)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.DETAIL_WATCHLIST_ADDED, R.drawable.ai_scene_detail_watchlist, AiSpriteAnchor.DetailHeader)
        )
        assertThat(sceneArtFor(AiSceneEvent.QUIZ_PERFECT)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.QUIZ_PERFECT, R.drawable.ai_scene_quiz_perfect, AiSpriteAnchor.QuizResult)
        )
        assertThat(sceneArtFor(AiSceneEvent.QUIZ_RESULT)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.QUIZ_RESULT, R.drawable.ai_scene_quiz_result, AiSpriteAnchor.QuizResult)
        )
        assertThat(sceneArtFor(AiSceneEvent.AI_RECOMMENDATIONS)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.AI_RECOMMENDATIONS, R.drawable.ai_scene_ai_recommendations, AiSpriteAnchor.AiFeatureHeader)
        )
        assertThat(sceneArtFor(AiSceneEvent.AI_TASTE)).isEqualTo(
            AiSceneArtSpec(AiSceneEvent.AI_TASTE, R.drawable.ai_scene_ai_taste, AiSpriteAnchor.AiFeatureHeader)
        )
    }
}
