package com.tracktosearch.ui.screen.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiTtsScene
import com.tracktosearch.data.ai.AiTtsRequest
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizOption
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizQuestionResult
import com.tracktosearch.data.ai.AiRecommendation
import kotlin.random.Random
import org.junit.Test

class AiUiLogicTest {

    @Test
    fun auditionRequest_usesStaticTextAndAuditionScene() {
        val character = AiCharacter(
            id = "usagi",
            name = "乌萨奇",
            activationWord = "乌萨奇",
            auditionText = "到！",
        )

        val request = buildAuditionTtsRequest(character, "sprite-test")

        assertThat(request).isEqualTo(
            AiTtsRequest(
                characterId = "usagi",
                text = "到！",
                sessionId = "sprite-test",
                scene = AiTtsScene.AUDITION,
            )
        )
    }

    @Test
    fun auditionPlaybackRoute_separatesGuestAuthorizedAndUnavailableCharacters() {
        assertThat(auditionPlaybackRoute(isAuthorized = false, isAvailable = true))
            .isEqualTo(AiAuditionPlaybackRoute.GUEST_TTS)
        assertThat(auditionPlaybackRoute(isAuthorized = true, isAvailable = true))
            .isEqualTo(AiAuditionPlaybackRoute.AUTHORIZED_TTS)
        assertThat(auditionPlaybackRoute(isAuthorized = false, isAvailable = false))
            .isEqualTo(AiAuditionPlaybackRoute.SYSTEM_TTS)
    }

    @Test
    fun activationAllowsTwoRetriesAndStopsAtThirdAttempt() {
        assertThat(canRetryActivation(1)).isTrue()
        assertThat(canRetryActivation(2)).isTrue()
        assertThat(canRetryActivation(3)).isFalse()
    }

    @Test
    fun activationRecordsVoiceOnlyForTheFirstAttemptAndUsesTextFallbackAfterward() {
        assertThat(activationRequestMode(0)).isEqualTo(AiActivationRequestMode.VOICE)
        assertThat(activationRequestMode(1)).isEqualTo(AiActivationRequestMode.TEXT)
        assertThat(activationRequestMode(2)).isEqualTo(AiActivationRequestMode.TEXT)
        assertThat(activationRequestMode(3)).isEqualTo(AiActivationRequestMode.NONE)
    }

    @Test
    fun selectingTheCurrentCharacterDoesNotStartANewActivationSession() {
        assertThat(shouldResetActivationAttempt("usagi", "usagi")).isFalse()
        assertThat(shouldResetActivationAttempt("usagi", "hachiware")).isTrue()
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

    @Test
    fun overlayPolicyAcceptsEntrySearchCompletionAndIdleButCapsSessionAndDay() {
        val policy = AiSpriteOverlayPolicy()

        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.FIRST_ENTRY, "2026-08-11")).isTrue()
        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.SEARCH_COMPLETED, "2026-08-11")).isTrue()
        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.IDLE, "2026-08-11")).isTrue()
        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.IDLE, "2026-08-11")).isFalse()
        assertThat(policy.tryConsume(false, AiSpriteOverlayTrigger.IDLE, "2026-08-11")).isFalse()

        val dayBudget = AiSpriteOverlayBudget(sessionShown = 0, dailyShown = 5)
        assertThat(dayBudget.canShow()).isTrue()
        assertThat(dayBudget.consume().dailyShown).isEqualTo(6)
        assertThat(dayBudget.consume().consume()).isEqualTo(dayBudget.consume())
    }

    @Test
    fun overlayWaitsForActivationBeforeConsumingFirstEntryTrigger() {
        assertThat(
            nextAiSpriteOverlayTrigger(
                entryHandled = false,
                wasSearchLoading = false,
                isSearchLoading = false,
                hasResults = false,
                isSearchFocused = false,
                searchQuery = "",
                activated = false
            )
        ).isNull()
        assertThat(
            nextAiSpriteOverlayTrigger(
                entryHandled = false,
                wasSearchLoading = false,
                isSearchLoading = false,
                hasResults = false,
                isSearchFocused = false,
                searchQuery = "",
                activated = true
            )
        ).isEqualTo(AiSpriteOverlayTrigger.FIRST_ENTRY)
    }

    @Test
    fun firstEntryWinsOverSearchCompletionAndIdle() {
        assertThat(
            nextAiSpriteOverlayTrigger(
                entryHandled = false,
                wasSearchLoading = true,
                isSearchLoading = false,
                hasResults = true,
                isSearchFocused = false,
                searchQuery = "",
                activated = true,
                nowMs = 90_000L
            )
        ).isEqualTo(AiSpriteOverlayTrigger.FIRST_ENTRY)
    }

    @Test
    fun idleRequiresEightSecondsAndDoesNotTriggerAfterFocusOrInput() {
        assertThat(
            shouldTriggerIdle(
                isSearchFocused = false,
                searchQuery = "",
                isSearchLoading = false,
                hasBlockingOverlay = false,
                idleForMs = 7_999L,
                nowMs = 10_000L
            )
        ).isFalse()
        assertThat(
            shouldTriggerIdle(
                isSearchFocused = false,
                searchQuery = "",
                isSearchLoading = false,
                hasBlockingOverlay = false,
                idleForMs = 8_000L,
                nowMs = 10_000L
            )
        ).isTrue()
        assertThat(
            shouldTriggerIdle(
                isSearchFocused = true,
                searchQuery = "",
                isSearchLoading = false,
                hasBlockingOverlay = false,
                idleForMs = 8_000L,
                nowMs = 10_000L
            )
        ).isFalse()
        assertThat(
            shouldTriggerIdle(
                isSearchFocused = false,
                searchQuery = "片名",
                isSearchLoading = false,
                hasBlockingOverlay = false,
                idleForMs = 8_000L,
                nowMs = 10_000L
            )
        ).isFalse()
    }

    @Test
    fun policyAppliesThreeMotionSessionBudget() {
        val policy = AiSpriteOverlayPolicy(clock = { 100_000L })

        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.FIRST_ENTRY, "2026-08-13")).isTrue()
        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.SEARCH_COMPLETED, "2026-08-13")).isTrue()
        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.IDLE, "2026-08-13")).isTrue()
        assertThat(policy.tryConsume(true, AiSpriteOverlayTrigger.IDLE, "2026-08-13")).isFalse()
    }

    @Test
    fun overlayPolicyRestoresDailyCountWithoutPersistingSessionCount() {
        var storedDailyCount = 5
        val firstPolicy = AiSpriteOverlayPolicy(
            readDailyCount = { storedDailyCount },
            writeDailyCount = { _, count -> storedDailyCount = count }
        )

        assertThat(firstPolicy.tryConsume(true, AiSpriteOverlayTrigger.FIRST_ENTRY, "2026-08-11")).isTrue()
        assertThat(storedDailyCount).isEqualTo(6)
        assertThat(firstPolicy.tryConsume(true, AiSpriteOverlayTrigger.IDLE, "2026-08-11")).isFalse()

        val nextPolicy = AiSpriteOverlayPolicy(
            readDailyCount = { storedDailyCount },
            writeDailyCount = { _, count -> storedDailyCount = count }
        )
        assertThat(nextPolicy.tryConsume(true, AiSpriteOverlayTrigger.FIRST_ENTRY, "2026-08-11")).isFalse()

        storedDailyCount = 1
        assertThat(nextPolicy.tryConsume(true, AiSpriteOverlayTrigger.SEARCH_COMPLETED, "2026-08-12")).isTrue()
    }

    @Test
    fun activationRequiresReadyCharacterAndStopsAfterSuccessOrTwoRetries() {
        val ready = AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)
        val unavailable = ready.copy(isAvailable = false)
        val authorized = AiSpriteUiState(authState = com.tracktosearch.data.auth.AuthState.AUTHORIZED)

        assertThat(canActivateCharacter(ready, authorized)).isTrue()
        assertThat(canActivateCharacter(unavailable, authorized)).isFalse()
        assertThat(canActivateCharacter(ready, authorized.copy(activationAttempt = 3))).isFalse()
        assertThat(
            canActivateCharacter(
                ready,
                authorized.copy(
                    selectedCharacterId = ready.id,
                    activatedCharacterId = ready.id,
                    activationState = AiActivationState.SUCCESS
                )
            )
        ).isFalse()
    }

    @Test
    fun completedSearchWithResultsUsesTheFirstResultAnchor() {
        assertThat(searchAnchorFor(AiSpriteOverlayTrigger.SEARCH_COMPLETED, hasResultAnchor = true))
            .isEqualTo(AiSpriteAnchor.ResultCard)
        assertThat(searchAnchorFor(AiSpriteOverlayTrigger.SEARCH_COMPLETED, hasResultAnchor = false))
            .isEqualTo(AiSpriteAnchor.SearchBox)
    }

    @Test
    fun activationSuccessBadgeIsShownOnlyForTheActivatedCharacter() {
        assertThat(shouldShowActivationSuccessBadge("usagi", null)).isEqualTo(false)
        assertThat(shouldShowActivationSuccessBadge("usagi", "hachiware")).isEqualTo(false)
        assertThat(shouldShowActivationSuccessBadge("usagi", "usagi")).isEqualTo(true)
    }

    @Test
    fun quizPreviewContainsSevenDistinctMoviesAndAllowsOnlyTwoReplacements() {
        val candidates = (1..10).map { id ->
            com.tracktosearch.data.ai.AiWatchedTitleDto(
                mediaId = id.toString(),
                mediaType = "movie",
                title = "片名$id"
            )
        }
        val preview = selectQuizPreview(candidates, Random(1))
        assertThat(preview).hasSize(7)
        assertThat(preview.map { it.mediaId }.toSet()).hasSize(7)

        val replaced = replaceQuizPreview(preview, candidates, index = 0, replacementCount = 0, random = Random(2))
        assertThat(replaced).hasSize(7)
        assertThat(replaced[0].mediaId).isNotEqualTo(preview[0].mediaId)
        val second = replaceQuizPreview(replaced, candidates, index = 1, replacementCount = 1, random = Random(3))
        assertThat(second[1].mediaId).isNotEqualTo(replaced[1].mediaId)
        assertThat(replaceQuizPreview(second, candidates, 2, replacementCount = 2, random = Random(4))).isEqualTo(second)
    }

    @Test
    fun recommendationDetailsIncludeDoubanAndImdbOnlyItems() {
        val doubanOnly = AiRecommendation("db-1", "movie", "豆瓣片", null, null, null, null, null, "db-1", "")
        val imdbOnly = AiRecommendation("tt-1", "show", "IMDb剧", null, null, null, null, "tt-1", null, "")
        val noId = AiRecommendation("title", "movie", "无 ID", null, null, null, null, null, null, "")

        assertThat(recommendationHasDetailRoute(doubanOnly)).isTrue()
        assertThat(recommendationHasDetailRoute(imdbOnly)).isTrue()
        assertThat(recommendationHasDetailRoute(noId)).isFalse()
        assertThat(recommendationNavigationKey(doubanOnly)).isEqualTo("ai-douban:db-1")
        assertThat(recommendationNavigationKey(imdbOnly)).isEqualTo("tt-1")
    }

    @Test
    fun quizAnswerLabelsExposeSelectedOptionTextForResultReview() {
        val question = AiQuizQuestion(
            id = "q1",
            type = AiQuizQuestionType.SINGLE,
            prompt = "问题",
            options = listOf(AiQuizOption("a", "答案 A"), AiQuizOption("b", "答案 B"))
        )
        val answer = AiQuizAnswer("q1", selectedOptionIds = listOf("b"))
        assertThat(quizAnswerLabels(question, answer)).containsExactly("答案 B")
    }

    @Test
    fun quizCorrectAnswerTextUsesServerCorrectOptionIdsInsteadOfSubmittedAnswer() {
        val question = AiQuizQuestion(
            id = "q1",
            type = AiQuizQuestionType.SINGLE,
            prompt = "问题",
            options = listOf(AiQuizOption("a", "答案 A"), AiQuizOption("b", "答案 B"))
        )
        val result = AiQuizQuestionResult(
            questionId = "q1",
            score = 0,
            correct = false,
            explanation = "解析",
            correctOptionIds = listOf("a")
        )

        assertThat(quizCorrectAnswerText(question, result)).isEqualTo("答案 A")
    }

    @Test
    fun quizCorrectAnswerTextUsesServerTextForShortAnswer() {
        val question = AiQuizQuestion(
            id = "q1",
            type = AiQuizQuestionType.SHORT,
            prompt = "问题"
        )
        val result = AiQuizQuestionResult(
            questionId = "q1",
            score = 10,
            correct = true,
            explanation = "解析",
            correctAnswer = "因为选择会留下痕迹"
        )

        assertThat(quizCorrectAnswerText(question, result)).isEqualTo("因为选择会留下痕迹")
    }
}
