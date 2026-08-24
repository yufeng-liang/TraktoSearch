package com.tracktosearch.ui.screen.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiQuiz
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
    fun usagiAuditionTextDoesNotUseActivationAckPhrase() {
        val usagi = com.tracktosearch.data.ai.AiCharacterCatalog.all.first { it.id == "usagi" }

        assertThat(usagi.auditionText).doesNotContain("到")
        assertThat(usagi.auditionText).isNotEmpty()
    }

    @Test
    fun auditionRequest_usesStaticTextAndAuditionScene() {
        val character = AiCharacter(
            id = "usagi",
            name = "乌萨奇",
            activationWord = "乌萨奇",
            auditionText = "呀哈！你的片单有点东西。",
        )

        val request = buildAuditionTtsRequest(character, "sprite-test")

        assertThat(request).isEqualTo(
            AiTtsRequest(
                characterId = "usagi",
                text = "呀哈！你的片单有点东西。",
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
    fun errorCodesMapToDistinctActionableMessages() {
        // 之前所有码都渲染成 ai_error，用户看不出原因只能反复点重试
        assertThat(aiErrorMessageRes("NOT_ENOUGH_MOVIES")).isEqualTo(R.string.ai_error_not_enough_movies)
        assertThat(aiErrorMessageRes("WATCHED_LIST_EMPTY")).isEqualTo(R.string.ai_error_watched_list_empty)
        assertThat(aiErrorMessageRes("QUOTA_EXCEEDED")).isEqualTo(R.string.ai_error_quota_exceeded)
        assertThat(aiErrorMessageRes("ACTIVATION_NOT_MATCHED")).isEqualTo(R.string.ai_error_activation_not_matched)
        assertThat(aiErrorMessageRes("AUDIO_UNAVAILABLE")).isEqualTo(R.string.ai_error_audio_unavailable)
        assertThat(aiErrorMessageRes("UNAUTHORIZED")).isEqualTo(R.string.ai_error_unauthorized)
        assertThat(aiErrorMessageRes("NETWORK")).isEqualTo(R.string.ai_error_network)
        assertThat(aiErrorMessageRes("CHARACTERS_LOAD_FAILED")).isEqualTo(R.string.ai_error_characters_failed)
        // 未识别的码仍回退到通用文案
        assertThat(aiErrorMessageRes("SOMETHING_NEW")).isEqualTo(R.string.ai_error)
        assertThat(aiErrorMessageRes(null)).isEqualTo(R.string.ai_error)
    }

    @Test
    fun errorsThatRetryCannotFixDoNotOfferRetry() {
        assertThat(aiErrorIsRetryable("NOT_ENOUGH_MOVIES")).isFalse()
        assertThat(aiErrorIsRetryable("QUOTA_EXCEEDED")).isFalse()
        assertThat(aiErrorIsRetryable("UNAUTHORIZED")).isFalse()
        assertThat(aiErrorIsRetryable("WATCHED_LIST_EMPTY")).isFalse()
        assertThat(aiErrorIsRetryable("NETWORK")).isTrue()
        assertThat(aiErrorIsRetryable("SERVER")).isTrue()
        assertThat(aiErrorIsRetryable(null)).isTrue()
    }

    @Test
    fun activationBlockedReasonExplainsPreparingAndExhaustedInsteadOfSilentDisable() {
        val ready = AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)
        val preparing = ready.copy(isAvailable = false)
        val authorized = AiSpriteUiState(
            selectedCharacterId = ready.id,
            authState = com.tracktosearch.data.auth.AuthState.AUTHORIZED
        )

        assertThat(activationBlockedReasonRes(ready, authorized)).isNull()
        assertThat(activationBlockedReasonRes(preparing, authorized))
            .isEqualTo(R.string.ai_sprite_activate_disabled_preparing)
        assertThat(
            activationBlockedReasonRes(
                ready,
                authorized.copy(activationAttempt = 5, textActivationAttempt = 5)
            )
        ).isEqualTo(R.string.ai_sprite_activate_disabled_exhausted)
        // 语音用满但文字还有次数时不算堵死
        assertThat(activationBlockedReasonRes(ready, authorized.copy(activationAttempt = 5))).isNull()
    }

    @Test
    fun unansweredCountTreatsBlankTextAndEmptySelectionAsUnanswered() {
        val quiz = AiQuiz(
            quizId = "q",
            title = "标题",
            subtitle = "",
            mediaTitles = emptyList(),
            totalScore = 100,
            questions = listOf(
                AiQuizQuestion(id = "a", type = AiQuizQuestionType.SINGLE, prompt = "1"),
                AiQuizQuestion(id = "b", type = AiQuizQuestionType.SHORT, prompt = "2"),
                AiQuizQuestion(id = "c", type = AiQuizQuestionType.MULTIPLE, prompt = "3")
            )
        )
        val answers = mapOf(
            "a" to AiQuizAnswer("a", selectedOptionIds = listOf("x")),
            "b" to AiQuizAnswer("b", selectedOptionIds = emptyList(), textAnswer = "   ")
        )

        assertThat(unansweredQuizCount(quiz, answers)).isEqualTo(2)
        assertThat(answeredQuizCount(quiz, answers)).isEqualTo(1)
        assertThat(unansweredQuizCount(null, answers)).isEqualTo(0)
    }

    @Test
    fun quizInProgressOnlyWhileStartedAndBeforeResult() {
        val quiz = AiQuiz(
            quizId = "q",
            title = "标题",
            subtitle = "",
            mediaTitles = emptyList(),
            totalScore = 100,
            questions = listOf(
                AiQuizQuestion(id = "a", type = AiQuizQuestionType.SINGLE, prompt = "1")
            )
        )
        val base = AiSpriteUiState(quiz = quiz, quizStarted = true)

        assertThat(hasQuizInProgress(base)).isTrue()
        assertThat(hasQuizInProgress(base.copy(quizStarted = false))).isFalse()
        assertThat(hasQuizInProgress(base.copy(quiz = null))).isFalse()
    }

    @Test
    fun replaceIsBlockedWhenCandidatesRunOutNotJustWhenCountUsedUp() {
        val seven = (1..7).map { id ->
            com.tracktosearch.data.ai.AiWatchedTitleDto(
                mediaId = id.toString(),
                mediaType = "movie",
                title = "片名$id"
            )
        }
        // 已看正好 7 部：候选全部在用，之前按钮仍可点但点了原样返回
        assertThat(canReplaceQuizPreview(seven, seven, replacementCount = 0)).isFalse()

        val nine = seven + (8..9).map { id ->
            com.tracktosearch.data.ai.AiWatchedTitleDto(
                mediaId = id.toString(),
                mediaType = "movie",
                title = "片名$id"
            )
        }
        assertThat(canReplaceQuizPreview(seven, nine, replacementCount = 0)).isTrue()
        assertThat(canReplaceQuizPreview(seven, nine, replacementCount = 2)).isFalse()
        assertThat(remainingQuizReplacements(0)).isEqualTo(2)
        assertThat(remainingQuizReplacements(3)).isEqualTo(0)
    }

    @Test
    fun voiceActivationAllowsFiveAttemptsThenStops() {
        assertThat(canRequestVoiceActivation(0)).isTrue()
        assertThat(canRequestVoiceActivation(4)).isTrue()
        assertThat(canRequestVoiceActivation(5)).isFalse()
        assertThat(canRequestVoiceActivation(6)).isFalse()
    }

    @Test
    fun textActivationIsCappedAtFiveAttemptsAndTrimsTypedName() {
        assertThat(canRequestTextActivation(0)).isTrue()
        assertThat(canRequestTextActivation(4)).isTrue()
        assertThat(canRequestTextActivation(5)).isFalse()

        assertThat(normalizeSpokenName("  乌萨奇 ")).isEqualTo("乌萨奇")
        assertThat(isTextActivationInputValid("   ")).isFalse()
        assertThat(isTextActivationInputValid(" 乌萨奇")).isTrue()
    }

    @Test
    fun textActivationEntryAppearsOnlyAfterVoiceFailedAndBeforeItsOwnLimit() {
        val ready = AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)
        val authorized = AiSpriteUiState(
            characters = listOf(ready),
            selectedCharacterId = ready.id,
            authState = com.tracktosearch.data.auth.AuthState.AUTHORIZED
        )

        // 语音还没失败过：不给文字入口，避免用户直接绕过语音
        assertThat(shouldShowTextActivation(authorized)).isFalse()

        val offered = authorized.copy(
            activationAttempt = 1,
            activationState = AiActivationState.FAILED,
            textActivationOffered = true
        )
        assertThat(shouldShowTextActivation(offered)).isTrue()
        assertThat(canActivateCharacterByText(ready, offered)).isTrue()

        // 重试语音的这几秒入口要留着，不能闪走
        val retryingVoice = offered.copy(activationState = AiActivationState.RECORDING)
        assertThat(shouldShowTextActivation(retryingVoice)).isTrue()
        assertThat(canActivateCharacterByText(ready, retryingVoice)).isFalse()

        // 文字次数用满后收起入口
        assertThat(shouldShowTextActivation(offered.copy(textActivationAttempt = 5))).isFalse()
        // 已激活成功的角色不再展示激活入口
        assertThat(shouldShowTextActivation(offered.copy(activatedCharacterId = ready.id))).isFalse()
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
    fun activationRequiresReadyCharacterAndStopsAfterSuccessOrFiveVoiceAttempts() {
        val ready = AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)
        val unavailable = ready.copy(isAvailable = false)
        val authorized = AiSpriteUiState(authState = com.tracktosearch.data.auth.AuthState.AUTHORIZED)

        assertThat(canActivateCharacter(ready, authorized)).isTrue()
        assertThat(canActivateCharacter(unavailable, authorized)).isFalse()
        // 第 4 次失败后还能再喊一次，第 5 次用完才锁
        assertThat(canActivateCharacter(ready, authorized.copy(activationAttempt = 4))).isTrue()
        assertThat(canActivateCharacter(ready, authorized.copy(activationAttempt = 5))).isFalse()
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
        assertThat(shouldShowActivationSuccessBadge("usagi", null, isAuthorized = true)).isEqualTo(false)
        assertThat(shouldShowActivationSuccessBadge("usagi", "hachiware", isAuthorized = true)).isEqualTo(false)
        assertThat(shouldShowActivationSuccessBadge("usagi", "usagi", isAuthorized = false)).isEqualTo(false)
        assertThat(shouldShowActivationSuccessBadge("usagi", "usagi", isAuthorized = true)).isEqualTo(true)
    }

    @Test
    fun activatedContentRequiresTheSelectedActivatedCharacterAndAuthorization() {
        assertThat(shouldShowActivatedCharacterContent("usagi", "usagi", isAuthorized = true)).isTrue()
        assertThat(shouldShowActivatedCharacterContent("hachiware", "usagi", isAuthorized = true)).isFalse()
        assertThat(shouldShowActivatedCharacterContent("usagi", "usagi", isAuthorized = false)).isFalse()
        assertThat(shouldShowActivatedCharacterContent("usagi", null, isAuthorized = true)).isFalse()
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
