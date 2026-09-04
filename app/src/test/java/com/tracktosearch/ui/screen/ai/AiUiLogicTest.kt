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
        // 按住失败分档：麦克风没拾到、声音太轻、按得太短要给出各自的下一步动作
        assertThat(aiErrorMessageRes("ACTIVATION_TOO_SHORT")).isEqualTo(R.string.ai_error_activation_too_short)
        assertThat(aiErrorMessageRes("ACTIVATION_SILENT")).isEqualTo(R.string.ai_error_activation_silent)
        assertThat(aiErrorMessageRes("ACTIVATION_TOO_QUIET")).isEqualTo(R.string.ai_error_activation_too_quiet)
        assertThat(aiErrorMessageRes("AUDIO_PERMISSION_GRANTED"))
            .isEqualTo(R.string.ai_error_audio_permission_granted)
        assertThat(aiErrorMessageRes("UNAUTHORIZED")).isEqualTo(R.string.ai_error_unauthorized)
        assertThat(aiErrorMessageRes("NETWORK")).isEqualTo(R.string.ai_error_network)
        assertThat(aiErrorMessageRes("OFFLINE")).isEqualTo(R.string.ai_feature_unavailable)
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
        assertThat(aiErrorIsRetryable("OFFLINE")).isFalse()
        assertThat(aiErrorIsRetryable("NETWORK")).isTrue()
        assertThat(aiErrorIsRetryable("SERVER")).isTrue()
        assertThat(aiErrorIsRetryable(null)).isTrue()
        // 按住失败分档与「刚拿到麦克风权限」都是再按一次就能解决的，必须留着重试
        assertThat(aiErrorIsRetryable("ACTIVATION_TOO_SHORT")).isTrue()
        assertThat(aiErrorIsRetryable("ACTIVATION_SILENT")).isTrue()
        assertThat(aiErrorIsRetryable("ACTIVATION_TOO_QUIET")).isTrue()
        assertThat(aiErrorIsRetryable("AUDIO_PERMISSION_GRANTED")).isTrue()
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
    fun quizMediaKeyTreatsShowAndTvAsTheSameMediaType() {
        assertThat(quizMediaKey("show", "42")).isEqualTo("show:42")
        assertThat(quizMediaKey("tv", "42")).isEqualTo("show:42")

        val candidates = listOf(
            com.tracktosearch.data.ai.AiWatchedTitleDto("42", "show", "剧集"),
            com.tracktosearch.data.ai.AiWatchedTitleDto("42", "tv", "旧类型别名"),
            com.tracktosearch.data.ai.AiWatchedTitleDto("43", "movie", "电影")
        )
        assertThat(selectQuizPreview(candidates, kotlin.random.Random(7))).hasSize(2)
        assertThat(selectQuizPreview(candidates, kotlin.random.Random(7)).map { it.mediaId })
            .containsExactly("42", "43")
    }

    @Test
    fun voiceActivationAllowsFiveAttemptsThenStops() {
        assertThat(canRequestVoiceActivation(0)).isTrue()
        assertThat(canRequestVoiceActivation(4)).isTrue()
        assertThat(canRequestVoiceActivation(5)).isFalse()
        assertThat(canRequestVoiceActivation(6)).isFalse()
    }

    @Test
    fun voiceHoldMatchWinsOverEveryFailureIncludingTooShortHold() {
        // 喊中了就是成功：按得再短、峰值再低也不能反过来罚用户重来一次
        assertThat(
            resolveVoiceHoldOutcome(
                holdDurationMs = 120L,
                peakLevel = 0f,
                matchedCharacterId = "usagi",
                selectedCharacterId = "usagi",
                captureUnavailable = true
            )
        ).isEqualTo(AiVoiceHoldOutcome.MATCHED)
    }

    @Test
    fun voiceHoldFallsThroughToLevelChecksWhenAnotherCharacterNameWasHeard() {
        // 喊得清楚但喊的是别的角色：不是静音也不是太轻，只能告诉用户名字没对上
        assertThat(
            resolveVoiceHoldOutcome(
                holdDurationMs = 1_500L,
                peakLevel = 0.6f,
                matchedCharacterId = "hachiware",
                selectedCharacterId = "usagi",
                captureUnavailable = false
            )
        ).isEqualTo(AiVoiceHoldOutcome.NOT_MATCHED)
        // 同一句喊得很轻时先按电平分档，让用户靠近一点而不是怀疑自己记错了名字
        assertThat(
            resolveVoiceHoldOutcome(
                holdDurationMs = 1_500L,
                peakLevel = 0.1f,
                matchedCharacterId = "hachiware",
                selectedCharacterId = "usagi",
                captureUnavailable = false
            )
        ).isEqualTo(AiVoiceHoldOutcome.TOO_QUIET)
    }

    @Test
    fun voiceHoldOutcomeSplitsUnavailableTooShortSilentAndTooQuietAtTheirBoundaries() {
        fun outcome(holdMs: Long, peak: Float, unavailable: Boolean = false) = resolveVoiceHoldOutcome(
            holdDurationMs = holdMs,
            peakLevel = peak,
            matchedCharacterId = null,
            selectedCharacterId = "usagi",
            captureUnavailable = unavailable
        )

        // 麦克风都没拿到时时长和电平都是假数据，不能拿它们编失败原因
        assertThat(outcome(100L, 0f, unavailable = true)).isEqualTo(AiVoiceHoldOutcome.UNAVAILABLE)
        assertThat(outcome(VOICE_HOLD_MIN_DURATION_MS - 1L, 0.6f)).isEqualTo(AiVoiceHoldOutcome.TOO_SHORT)
        // 正好压到下限算一次有效按住，往下继续按电平分档
        assertThat(outcome(VOICE_HOLD_MIN_DURATION_MS, 0f)).isEqualTo(AiVoiceHoldOutcome.SILENT)
        assertThat(outcome(3_000L, VOICE_SILENCE_LEVEL - 0.001f)).isEqualTo(AiVoiceHoldOutcome.SILENT)
        assertThat(outcome(3_000L, VOICE_SILENCE_LEVEL)).isEqualTo(AiVoiceHoldOutcome.TOO_QUIET)
        assertThat(outcome(3_000L, VOICE_TOO_QUIET_LEVEL - 0.001f)).isEqualTo(AiVoiceHoldOutcome.TOO_QUIET)
        assertThat(outcome(3_000L, VOICE_TOO_QUIET_LEVEL)).isEqualTo(AiVoiceHoldOutcome.NOT_MATCHED)
        // 到 10 秒上限自动收尾走的是同一张表
        assertThat(outcome(VOICE_HOLD_MAX_DURATION_MS, 0.6f)).isEqualTo(AiVoiceHoldOutcome.NOT_MATCHED)
    }

    @Test
    fun onlyRealAttemptsBurnVoiceActivationChances() {
        // 5 次机会是用户的资产：误触和麦克风拿不到都没提交过，不许烧
        assertThat(voiceHoldOutcomeCountsAsAttempt(AiVoiceHoldOutcome.TOO_SHORT)).isFalse()
        assertThat(voiceHoldOutcomeCountsAsAttempt(AiVoiceHoldOutcome.UNAVAILABLE)).isFalse()
        // 真录到了就算一次，静音也算——不然对着关掉的麦克风能无限重试
        assertThat(voiceHoldOutcomeCountsAsAttempt(AiVoiceHoldOutcome.MATCHED)).isTrue()
        assertThat(voiceHoldOutcomeCountsAsAttempt(AiVoiceHoldOutcome.SILENT)).isTrue()
        assertThat(voiceHoldOutcomeCountsAsAttempt(AiVoiceHoldOutcome.TOO_QUIET)).isTrue()
        assertThat(voiceHoldOutcomeCountsAsAttempt(AiVoiceHoldOutcome.NOT_MATCHED)).isTrue()
    }

    @Test
    fun voiceHoldErrorCodesLandOnFourDistinctFailureMessages() {
        assertThat(voiceHoldOutcomeErrorCode(AiVoiceHoldOutcome.MATCHED)).isNull()
        assertThat(voiceHoldOutcomeErrorCode(AiVoiceHoldOutcome.UNAVAILABLE)).isEqualTo("AUDIO_UNAVAILABLE")
        assertThat(voiceHoldOutcomeErrorCode(AiVoiceHoldOutcome.TOO_SHORT)).isEqualTo("ACTIVATION_TOO_SHORT")
        assertThat(voiceHoldOutcomeErrorCode(AiVoiceHoldOutcome.SILENT)).isEqualTo("ACTIVATION_SILENT")
        assertThat(voiceHoldOutcomeErrorCode(AiVoiceHoldOutcome.TOO_QUIET)).isEqualTo("ACTIVATION_TOO_QUIET")
        assertThat(voiceHoldOutcomeErrorCode(AiVoiceHoldOutcome.NOT_MATCHED)).isEqualTo("ACTIVATION_NOT_MATCHED")

        // 分档的全部意义在于四种失败各说各的下一步；漏配文案会静默退回同一句「精灵正在休息」
        val messages = AiVoiceHoldOutcome.entries
            .mapNotNull { voiceHoldOutcomeErrorCode(it) }
            .map { aiErrorMessageRes(it) }
        assertThat(messages).hasSize(5)
        assertThat(messages).containsNoDuplicates()
        assertThat(messages).doesNotContain(R.string.ai_error)
    }

    @Test
    fun normalizeVoiceLevelGuardsSilenceAndClampsBothEnds() {
        // rms 为 0 时 log10(0) 是负无穷，不挡住电平带会直接画 NaN
        assertThat(normalizeVoiceLevel(0f)).isEqualTo(0f)
        assertThat(normalizeVoiceLevel(-0.5f)).isEqualTo(0f)
        // -50 dBFS 当底噪：安静房间的本底不该让竖条自己跳起来
        assertThat(normalizeVoiceLevel(0.0031623f)).isWithin(0.001f).of(0f)
        assertThat(normalizeVoiceLevel(0.0005f)).isEqualTo(0f)
        // 0 dBFS 满幅，越界输入夹在 1
        assertThat(normalizeVoiceLevel(1f)).isEqualTo(1f)
        assertThat(normalizeVoiceLevel(4f)).isEqualTo(1f)
        // -25 dBFS 正好落在中间，正常说话音量才看得出明显变化
        assertThat(normalizeVoiceLevel(0.056234f)).isWithin(0.01f).of(0.5f)
    }

    @Test
    fun smoothVoiceLevelRisesFastAndFallsSlowly() {
        // 起要快：开口那一帧竖条必须立刻立起来，否则用户以为麦克风没拾到音
        val attacked = smoothVoiceLevel(previous = 0f, next = 1f)
        assertThat(attacked).isAtLeast(0.5f)
        assertThat(attacked).isLessThan(1f)

        // 落要慢：同样幅度的跌落步子必须比起的小，不然字与字之间竖条会抽搐
        val firstRelease = smoothVoiceLevel(previous = 1f, next = 0f)
        assertThat(1f - firstRelease).isLessThan(attacked)
        assertThat(firstRelease).isGreaterThan(0.5f)

        // 松口后要连着好几帧才衰到底，而不是一帧归零
        val secondRelease = smoothVoiceLevel(firstRelease, 0f)
        val thirdRelease = smoothVoiceLevel(secondRelease, 0f)
        assertThat(secondRelease).isLessThan(firstRelease)
        assertThat(thirdRelease).isLessThan(secondRelease)
        assertThat(thirdRelease).isGreaterThan(0.2f)
        var settled = thirdRelease
        repeat(30) { settled = smoothVoiceLevel(settled, 0f) }
        assertThat(settled).isLessThan(0.05f)

        // 越界输入不能把电平顶出 0..1，Canvas 拿到越界值会把竖条画出格子
        assertThat(smoothVoiceLevel(previous = 0.5f, next = 9f)).isAtMost(1f)
        assertThat(smoothVoiceLevel(previous = -3f, next = -3f)).isAtLeast(0f)
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
    fun textActivationEntryStaysAvailableWithoutFailingVoiceFirst() {
        val ready = AiCharacter("usagi", "乌萨奇", "乌萨奇", isAvailable = true)
        val authorized = AiSpriteUiState(
            characters = listOf(ready),
            selectedCharacterId = ready.id,
            authState = com.tracktosearch.data.auth.AuthState.AUTHORIZED
        )

        // 入口常驻：安静环境（图书馆、深夜）里开不了口的用户不必先故意让语音失败一次
        assertThat(shouldShowTextActivation(authorized)).isTrue()
        // 按钮也必须真能点。只放开展示条件的话，按钮会常驻着灰给用户看，
        // activateByText 还会用同一个判定回一句「角色还没准备好」
        assertThat(canActivateCharacterByText(ready, authorized)).isTrue()
        // 未授权时仍然不给任何激活入口
        assertThat(
            shouldShowTextActivation(
                authorized.copy(authState = com.tracktosearch.data.auth.AuthState.UNAUTHORIZED)
            )
        ).isFalse()

        val afterVoiceFailure = authorized.copy(
            activationAttempt = 1,
            activationState = AiActivationState.FAILED
        )
        assertThat(shouldShowTextActivation(afterVoiceFailure)).isTrue()
        assertThat(canActivateCharacterByText(ready, afterVoiceFailure)).isTrue()

        // 重试语音的这几秒入口要留着，不能闪走；按钮则要跟着录音态禁用
        val retryingVoice = afterVoiceFailure.copy(activationState = AiActivationState.RECORDING)
        assertThat(shouldShowTextActivation(retryingVoice)).isTrue()
        assertThat(canActivateCharacterByText(ready, retryingVoice)).isFalse()

        // 文字次数用满后收起入口
        assertThat(shouldShowTextActivation(afterVoiceFailure.copy(textActivationAttempt = 5))).isFalse()
        // 已激活成功的角色不再展示激活入口
        assertThat(shouldShowTextActivation(afterVoiceFailure.copy(activatedCharacterId = ready.id))).isFalse()
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
    fun activatedContentRequiresTheSelectedActivatedCharacterAndAuthorization() {
        assertThat(shouldShowActivatedCharacterContent("usagi", "usagi", isAuthorized = true)).isTrue()
        assertThat(shouldShowActivatedCharacterContent("hachiware", "usagi", isAuthorized = true)).isFalse()
        assertThat(shouldShowActivatedCharacterContent("usagi", "usagi", isAuthorized = false)).isFalse()
        assertThat(shouldShowActivatedCharacterContent("usagi", null, isAuthorized = true)).isFalse()
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

}
