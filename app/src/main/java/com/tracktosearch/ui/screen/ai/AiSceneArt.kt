package com.tracktosearch.ui.screen.ai

import androidx.annotation.DrawableRes
import com.tracktosearch.R

/** 语义场景图事件。场景图是整图素材，不参与角色五层拆分。 */
enum class AiSceneEvent {
    SEARCH_FIRST_ENTRY,
    SEARCH_IDLE,
    SEARCH_COMPLETED,
    DETAIL_WATCHLIST_ADDED,
    QUIZ_PERFECT,
    QUIZ_RESULT,
    AI_RECOMMENDATIONS,
    AI_TASTE
}

fun sceneEventForSearch(trigger: AiSpriteOverlayTrigger): AiSceneEvent = when (trigger) {
    AiSpriteOverlayTrigger.FIRST_ENTRY -> AiSceneEvent.SEARCH_FIRST_ENTRY
    AiSpriteOverlayTrigger.IDLE -> AiSceneEvent.SEARCH_IDLE
    AiSpriteOverlayTrigger.SEARCH_COMPLETED -> AiSceneEvent.SEARCH_COMPLETED
}

fun searchAnchorFor(
    trigger: AiSpriteOverlayTrigger,
    hasResultAnchor: Boolean = true
): AiSpriteAnchor = when (trigger) {
    AiSpriteOverlayTrigger.FIRST_ENTRY,
    AiSpriteOverlayTrigger.IDLE -> AiSpriteAnchor.SearchBox
    AiSpriteOverlayTrigger.SEARCH_COMPLETED ->
        if (hasResultAnchor) AiSpriteAnchor.ResultCard else AiSpriteAnchor.SearchBox
}

fun shouldShowSceneForRevision(previousRevision: Long, currentRevision: Long): Boolean =
    currentRevision > 0L && currentRevision > previousRevision

fun shouldShowWatchlistAddedScene(previousRevision: Long, currentRevision: Long): Boolean =
    shouldShowSceneForRevision(previousRevision, currentRevision)

fun quizSceneEvent(score: Int, totalScore: Int): AiSceneEvent =
    if (totalScore > 0 && score >= totalScore) AiSceneEvent.QUIZ_PERFECT
    else AiSceneEvent.QUIZ_RESULT

fun tasteSceneEvent(hasRecommendations: Boolean): AiSceneEvent =
    if (hasRecommendations) AiSceneEvent.AI_RECOMMENDATIONS else AiSceneEvent.AI_TASTE

/** 场景图资源与页面锚点的固定目录。 */
data class AiSceneArtSpec(
    val event: AiSceneEvent,
    @DrawableRes val drawableRes: Int,
    val anchor: AiSpriteAnchor
)

fun sceneArtFor(event: AiSceneEvent): AiSceneArtSpec = when (event) {
    AiSceneEvent.SEARCH_FIRST_ENTRY -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_search_first_entry,
        AiSpriteAnchor.SearchBox
    )
    AiSceneEvent.SEARCH_IDLE -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_search_idle,
        AiSpriteAnchor.SearchBox
    )
    AiSceneEvent.SEARCH_COMPLETED -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_search_completed,
        AiSpriteAnchor.ResultCard
    )
    AiSceneEvent.DETAIL_WATCHLIST_ADDED -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_detail_watchlist,
        AiSpriteAnchor.DetailHeader
    )
    AiSceneEvent.QUIZ_PERFECT -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_quiz_perfect,
        AiSpriteAnchor.QuizResult
    )
    AiSceneEvent.QUIZ_RESULT -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_quiz_result,
        AiSpriteAnchor.QuizResult
    )
    AiSceneEvent.AI_RECOMMENDATIONS -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_ai_recommendations,
        AiSpriteAnchor.AiFeatureHeader
    )
    AiSceneEvent.AI_TASTE -> AiSceneArtSpec(
        event,
        R.drawable.ai_scene_ai_taste,
        AiSpriteAnchor.AiFeatureHeader
    )
}
