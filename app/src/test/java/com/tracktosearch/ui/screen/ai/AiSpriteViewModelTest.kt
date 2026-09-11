package com.tracktosearch.ui.screen.ai

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.ai.AiDailyCheckQuestion
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiDailyKnowledgeContentFeedback
import com.tracktosearch.data.ai.AiDailyKnowledgeHistoryRecord
import com.tracktosearch.data.ai.AiDailyKnowledgeQuestionResult
import com.tracktosearch.data.ai.AiDailyStreamEvent
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizDifficulty
import com.tracktosearch.data.ai.AiQuizHistory
import com.tracktosearch.data.ai.AiQuizResult
import com.tracktosearch.data.ai.AiQuota
import com.tracktosearch.data.ai.AiRepository
import com.tracktosearch.data.ai.AiQuizOption
import com.tracktosearch.data.ai.AiQuizRequest
import com.tracktosearch.data.ai.AiQuizStreamEvent
import com.tracktosearch.data.ai.AiTasteAnalysis
import com.tracktosearch.data.ai.AiVoiceCapture
import com.tracktosearch.data.ai.AiVoiceCaptureEvent
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.remote.trakt.dto.TraktIds
import com.tracktosearch.data.remote.trakt.dto.TraktMovie
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.test.MainDispatcherRule
import com.tracktosearch.data.util.ConnectivityObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class AiSpriteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val aiRepository = mockk<AiRepository>(relaxed = true)
    private val authManager = mockk<AuthManager>()
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val tmdbRepository = mockk<TmdbRepository>(relaxed = true)
    private val connectivityObserver = mockk<ConnectivityObserver>()
    private val languageStorage = mockk<LanguageStorage>()
    private val overlayStorage = mockk<com.tracktosearch.data.local.AiSpriteOverlayStorage>(relaxed = true)
    private val aiTasteStorage = mockk<com.tracktosearch.data.local.AiTasteStorage>()

    /** 今日知识已改成流式：用「直接下发 Completed」的假流等价于旧的一次性返回。 */
    private fun stubDailyStream(daily: AiDailyKnowledge) {
        coEvery { aiRepository.getDailyStream(any(), any(), any(), any()) } returns
            flowOf(AiDailyStreamEvent.Completed(daily))
    }

    @Test
    fun submitQuiz_mergesResultQuotaIntoUiState() = runTest {
        val expectedQuota = quota(sessionUsed = 4, dailyUsed = 21)
        val result = quizResult(expectedQuota)
        val viewModel = viewModel()
        viewModel.seedState {
            it.copy(
                quiz = quiz(),
                quizStarted = true,
                quota = quota(sessionUsed = 1, dailyUsed = 2)
            )
        }
        coEvery { aiRepository.submitQuiz("friend-a", "quiz-1", any()) } returns Result.success(result)
        coEvery { aiRepository.readQuizHistory("friend-a") } returns null

        viewModel.submitQuiz()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.quota).isEqualTo(expectedQuota)
    }

    @Test
    fun loadDaily_mergesDailyQuotaIntoUiState() = runTest {
        val expectedQuota = quota(sessionUsed = 5, dailyUsed = 22)
        val daily = dailyKnowledge(expectedQuota)
        val viewModel = viewModel()
        coEvery { aiRepository.readDailyKnowledgeHistory("friend-a") } returns emptyList()
        stubDailyStream(daily)

        viewModel.openFeature(AiFeature.DAILY)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dailyKnowledge).isEqualTo(daily)
        assertThat(viewModel.uiState.value.quota).isEqualTo(expectedQuota)
    }

    @Test
    fun offlineAiFeatures_showInlineUnavailableStateWithoutStartingNetworkRequests() = runTest {
        val viewModel = viewModel(
            networkStatus = MutableStateFlow(ConnectivityObserver.NetworkStatus.OFFLINE)
        )

        listOf(AiFeature.GREETING, AiFeature.TASTE, AiFeature.QUIZ)
            .forEach(viewModel::openFeature)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activeFeature).isEqualTo(AiFeature.QUIZ)
        assertThat(viewModel.uiState.value.errorCode).isEqualTo(AI_OFFLINE_ERROR_CODE)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        coVerify(exactly = 0) { aiRepository.getGreeting(any(), any(), any()) }
        coVerify(exactly = 0) { aiRepository.getTaste(any(), any(), any()) }
        // 离线时连静默预生成也不能发出去
        coVerify(exactly = 0) { aiRepository.getQuizStream(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.getAllMovieHistory(any(), any()) }
        coVerify(exactly = 0) { traktRepository.getAllShowHistory(any(), any()) }
    }

    @Test
    fun offlineDailyKnowledge_readsCachedUnitWithoutSettingErrorState() = runTest {
        val daily = dailyKnowledge()
        val viewModel = viewModel(
            authState = MutableStateFlow(AuthState.OFFLINE),
            networkStatus = MutableStateFlow(ConnectivityObserver.NetworkStatus.OFFLINE)
        )
        coEvery { aiRepository.readDailyKnowledgeHistory("friend-a") } returns emptyList()
        stubDailyStream(daily)

        viewModel.openFeature(AiFeature.DAILY)
        advanceUntilIdle()
        coVerify(exactly = 1) { aiRepository.readDailyKnowledgeHistory("friend-a") }
        coVerify(exactly = 1) { aiRepository.getDailyStream(any(), any(), any(), any()) }
        assertThat(viewModel.uiState.value.dailyKnowledge).isEqualTo(daily)
        assertThat(viewModel.uiState.value.errorCode).isNull()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun dailyIllustration_generating_triggersSingleSilentRefresh() = runTest {
        val generating = dailyKnowledge().copy(
            unitId = "unit-illu",
            illustration = com.tracktosearch.data.ai.AiDailyIllustration(status = "generating")
        )
        val readyIllustration = com.tracktosearch.data.ai.AiDailyIllustration(
            status = "ready",
            url = "https://img.example/unit-illu.png"
        )
        val refreshed = dailyKnowledge().copy(unitId = "unit-illu", illustration = readyIllustration)
        val viewModel = viewModel()
        coEvery { aiRepository.readDailyKnowledgeHistory("friend-a") } returns emptyList()
        stubDailyStream(generating)
        coEvery { aiRepository.refreshDailyKnowledge(any(), any(), any()) } returns Result.success(refreshed)

        viewModel.openFeature(AiFeature.DAILY)
        advanceUntilIdle()

        coVerify(exactly = 1) { aiRepository.refreshDailyKnowledge("friend-a", any(), any()) }
        assertThat(viewModel.uiState.value.dailyKnowledge?.illustration?.isReady).isTrue()

        // 同一内容只静默刷新一次；再进页面不重复拉取。
        viewModel.openFeature(AiFeature.DAILY)
        advanceUntilIdle()
        coVerify(exactly = 1) { aiRepository.refreshDailyKnowledge(any(), any(), any()) }
    }

    @Test
    fun dailyIllustration_silentRefreshSkippedWhileQuestionAnswered() = runTest {
        val generating = dailyKnowledge().copy(
            unitId = "unit-answered",
            illustration = com.tracktosearch.data.ai.AiDailyIllustration(status = "generating"),
            checkQuestion = com.tracktosearch.data.ai.AiDailyCheckQuestion(
                prompt = "题目",
                options = listOf(
                    com.tracktosearch.data.ai.AiQuizOption("a", "选项一"),
                    com.tracktosearch.data.ai.AiQuizOption("b", "选项二")
                ),
                correctOptionIds = listOf("a"),
                explanation = "解析"
            )
        )
        val viewModel = viewModel()
        coEvery { aiRepository.readDailyKnowledgeHistory("friend-a") } returns emptyList()
        stubDailyStream(generating)

        viewModel.openFeature(AiFeature.DAILY)
        // 只推进到内容落地，不跨过 25 秒静默刷新窗口，先让用户答题。
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        viewModel.selectDailyKnowledgeQuestionOption("a")
        advanceUntilIdle()

        coVerify(exactly = 0) { aiRepository.refreshDailyKnowledge(any(), any(), any()) }
    }

    @Test
    fun openDailyKnowledgeRecord_restoresHistoricalContentWithoutBurningChangeQuota() = runTest {
        val record = com.tracktosearch.data.ai.AiDailyKnowledgeHistoryRecord(
            unitId = "unit-old",
            shownDate = "2026-09-01",
            locale = "zh-CN",
            knowledge = dailyKnowledge().copy(id = "unit-old", unitId = "unit-old", title = "旧知识"),
            questionResult = com.tracktosearch.data.ai.AiDailyKnowledgeQuestionResult(
                selectedOptionIds = listOf("a"),
                correct = true,
                answeredAt = 1L
            )
        )
        val viewModel = viewModel()
        viewModel.seedState {
            it.copy(
                dailyKnowledge = dailyKnowledge(),
                dailyKnowledgeChangeCount = 1,
                activeFeature = AiFeature.DAILY
            )
        }

        viewModel.openDailyKnowledgeRecord(record)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dailyKnowledge?.title).isEqualTo("旧知识")
        assertThat(viewModel.uiState.value.dailyQuestionSelectedOptionId).isEqualTo("a")
        assertThat(viewModel.uiState.value.dailyKnowledgeChangeCount).isEqualTo(1)
    }

    @Test
    fun loadDaily_usesAppLanguageLocale() = runTest {
        val localeSlot = slot<String>()
        val viewModel = viewModel(
            language = MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)
        )
        coEvery {
            aiRepository.getDailyStream(any(), any(), any(), capture(localeSlot))
        } returns flowOf(AiDailyStreamEvent.Completed(dailyKnowledge()))

        viewModel.openFeature(AiFeature.DAILY)
        advanceUntilIdle()

        assertThat(localeSlot.captured).isEqualTo("en-US")
    }

    @Test
    fun refreshDaily_countsUserInitiatedChangeAndResetsInteractionState() = runTest {
        val viewModel = viewModel()
        viewModel.seedState {
            it.copy(
                activeFeature = AiFeature.DAILY,
                dailyKnowledge = structuredDailyKnowledge(),
                dailyKnowledgeChangeCount = 0,
                dailyQuestionSelectedOptionId = "a",
                dailyKnowledgeFeedback = AiDailyKnowledgeContentFeedback.HELPFUL,
                dailyKnowledgeDifficultyFeedback = AiQuizDifficulty.EASY
            )
        }
        coEvery {
            aiRepository.getDailyStream(any(), true, any(), any())
        } returns flowOf(AiDailyStreamEvent.Completed(structuredDailyKnowledge()))

        viewModel.refreshFeature()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.dailyKnowledgeChangeCount).isEqualTo(1)
        assertThat(state.dailyQuestionSelectedOptionId).isNull()
        assertThat(state.dailyKnowledgeFeedback).isNull()
        assertThat(state.dailyKnowledgeDifficultyFeedback).isNull()
    }

    @Test
    fun dailyKnowledgeChangeBoundary_resetsWhenRestoredDayIsDifferent() = runTest {
        val savedStateHandle = SavedStateHandle(
            mapOf(
                "daily_knowledge_change_day" to "2000-01-01",
                "daily_knowledge_change_count" to 2
            )
        )
        val viewModel = viewModel(savedStateHandle = savedStateHandle)

        assertThat(viewModel.uiState.value.dailyKnowledgeChangeCount).isEqualTo(0)
    }

    @Test
    fun refreshDaily_stopsAfterTwoUserInitiatedChanges() = runTest {
        val viewModel = viewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "daily_knowledge_change_day" to LocalDate.now().toString(),
                    "daily_knowledge_change_count" to 2
                )
            )
        )
        viewModel.seedState {
            it.copy(
                activeFeature = AiFeature.DAILY,
                dailyKnowledge = dailyKnowledge(),
                dailyKnowledgeChangeCount = 2
            )
        }

        viewModel.refreshFeature()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dailyKnowledgeChangeCount).isEqualTo(2)
        coVerify(exactly = 0) { aiRepository.getDailyStream(any(), any(), any(), any()) }
    }

    @Test
    fun dailyQuestionAndFeedback_areLocalAndOneShot() = runTest {
        val viewModel = viewModel()
        viewModel.seedState {
            it.copy(activeFeature = AiFeature.DAILY, dailyKnowledge = structuredDailyKnowledge())
        }

        viewModel.selectDailyKnowledgeQuestionOption("b")
        viewModel.selectDailyKnowledgeQuestionOption("a")
        viewModel.recordDailyKnowledgeFeedback(AiDailyKnowledgeContentFeedback.TOO_BROAD)
        viewModel.recordDailyKnowledgeFeedback(AiDailyKnowledgeContentFeedback.HELPFUL)
        viewModel.recordDailyKnowledgeDifficultyFeedback(AiQuizDifficulty.JUST_RIGHT)
        viewModel.recordDailyKnowledgeDifficultyFeedback(AiQuizDifficulty.HARD)

        val state = viewModel.uiState.value
        assertThat(state.dailyQuestionSelectedOptionId).isEqualTo("b")
        assertThat(state.dailyKnowledgeFeedback).isEqualTo(AiDailyKnowledgeContentFeedback.TOO_BROAD)
        assertThat(state.dailyKnowledgeDifficultyFeedback).isEqualTo(AiQuizDifficulty.JUST_RIGHT)
        coVerify(exactly = 0) { aiRepository.submitQuizDifficulty(any(), any()) }
    }

    @Test
    fun authOfflineState_doesNotUseNetworkEvenWhenConnectivityIsOnline() = runTest {
        val viewModel = viewModel(
            authState = MutableStateFlow(AuthState.OFFLINE),
            networkStatus = MutableStateFlow(ConnectivityObserver.NetworkStatus.ONLINE)
        )

        viewModel.openFeature(AiFeature.DAILY)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.errorCode).isEqualTo("AUTH_REQUIRED")
        coVerify(exactly = 0) { aiRepository.getDailyStream(any(), any(), any(), any()) }
    }

    @Test
    fun refreshGreeting_usesActivatedCharacterInsteadOfSelectedCharacter() = runTest {
        val viewModel = viewModel()
        viewModel.seedState {
            it.copy(
                activeFeature = AiFeature.GREETING,
                selectedCharacterId = "usagi",
                activatedCharacterId = "tomo"
            )
        }
        coEvery {
            aiRepository.getGreeting("friend-a", "tomo", forceRefresh = true)
        } returns Result.success(greeting())

        viewModel.refreshFeature()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            aiRepository.getGreeting("friend-a", "tomo", forceRefresh = true)
        }
        coVerify(exactly = 0) {
            aiRepository.getGreeting("friend-a", "usagi", forceRefresh = true)
        }
    }

    @Test
    fun closeFeature_cancelsCurrentRequestAndClearsLoadingState() = runTest {
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val viewModel = viewModel()
        coEvery { aiRepository.getGreeting("friend-a", "usagi", false) } coAnswers {
            requestStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                requestCancelled.complete(Unit)
            }
        }

        viewModel.openFeature(AiFeature.GREETING)
        runCurrent()
        assertThat(requestStarted.isCompleted).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        viewModel.closeFeature()
        runCurrent()

        assertThat(requestCancelled.isCompleted).isTrue()
        assertThat(viewModel.uiState.value.activeFeature).isNull()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.loadingFeature).isNull()
    }

    @Test
    fun staleRequestFinally_doesNotClearNewRequestLoadingState() = runTest {
        val releaseOldRequest = CompletableDeferred<Unit>()
        val newRequestStarted = CompletableDeferred<Unit>()
        val releaseNewRequest = CompletableDeferred<Unit>()
        val viewModel = viewModel()
        coEvery { aiRepository.getGreeting("friend-a", "usagi", false) } coAnswers {
            withContext(NonCancellable) { releaseOldRequest.await() }
            Result.success(greeting())
        }
        // 必须用 returns flow{} 而不是 coAnswers：getDailyStream 不是 suspend 函数，
        // coAnswers 里的 await 会被 mockk 用 runBlocking 执行，直接卡死测试线程。
        coEvery { aiRepository.getDailyStream(any(), any(), any(), any()) } returns flow {
            newRequestStarted.complete(Unit)
            releaseNewRequest.await()
            emit(AiDailyStreamEvent.Completed(dailyKnowledge()))
        }

        viewModel.openFeature(AiFeature.GREETING)
        runCurrent()
        viewModel.openFeature(AiFeature.DAILY)
        runCurrent()
        assertThat(newRequestStarted.isCompleted).isTrue()
        assertThat(viewModel.uiState.value.loadingFeature).isEqualTo(AiFeature.DAILY)

        releaseOldRequest.complete(Unit)
        runCurrent()

        assertThat(viewModel.uiState.value.isLoading).isTrue()
        assertThat(viewModel.uiState.value.loadingFeature).isEqualTo(AiFeature.DAILY)

        releaseNewRequest.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun featureGuardFailure_clearsStaleLoadingState() = runTest {
        val authState = MutableStateFlow(AuthState.AUTHORIZED)
        val requestStarted = CompletableDeferred<Unit>()
        val viewModel = viewModel(authState)
        coEvery { aiRepository.getGreeting("friend-a", "usagi", false) } coAnswers {
            requestStarted.complete(Unit)
            awaitCancellation()
        }

        viewModel.openFeature(AiFeature.GREETING)
        runCurrent()
        assertThat(requestStarted.isCompleted).isTrue()
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        // 新请求已换代，但真正执行 guard 前授权失效；不能留下旧请求的 loading。
        viewModel.openFeature(AiFeature.DAILY)
        authState.value = AuthState.UNAUTHORIZED
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.loadingFeature).isNull()
        assertThat(viewModel.uiState.value.errorCode).isEqualTo("AUTH_REQUIRED")
    }

    @Test
    fun unauthorized_clearsPrivateAiStateAndCancelsActiveFeatureRequest() = runTest {
        val authState = MutableStateFlow(AuthState.AUTHORIZED)
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val viewModel = viewModel(authState)
        coEvery { aiRepository.getGreeting("friend-a", "tomo", false) } coAnswers {
            requestStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                requestCancelled.complete(Unit)
            }
        }
        viewModel.seedState {
            it.copy(
                selectedCharacterId = "usagi",
                activatedCharacterId = "tomo",
                activationState = AiActivationState.SUCCESS,
                activationMessage = "activated",
                quota = quota(sessionUsed = 3, dailyUsed = 8),
                activeFeature = AiFeature.GREETING,
                greeting = greeting(),
                taste = AiTasteAnalysis("吐槽", "画像", emptyList(), emptyList()),
                quiz = quiz(),
                quizResult = quizResult(quota()),
                quizHistory = AiQuizHistory(bestScore = 90, lastResult = quizResult(quota())),
                dailyKnowledge = dailyKnowledge(quota())
            )
        }

        viewModel.ensureLoaded()
        runCurrent()
        viewModel.openFeature(AiFeature.GREETING)
        runCurrent()
        assertThat(requestStarted.isCompleted).isTrue()

        authState.value = AuthState.UNAUTHORIZED
        runCurrent()

        assertThat(requestCancelled.isCompleted).isTrue()
        val state = viewModel.uiState.value
        assertThat(state.activatedCharacterId).isNull()
        assertThat(state.activeFeature).isNull()
        assertThat(state.greeting).isNull()
        assertThat(state.taste).isNull()
        assertThat(state.quiz).isNull()
        assertThat(state.quizResult).isNull()
        assertThat(state.quizHistory).isNull()
        assertThat(state.dailyKnowledge).isNull()
        assertThat(state.quota).isNull()
        assertThat(state.activationState).isEqualTo(AiActivationState.IDLE)
        assertThat(state.activationMessage).isNull()
        assertThat(state.selectedCharacterId).isEqualTo("usagi")
    }

    @Test
    fun unauthorizedAlreadyActiveBeforeObserverStartup_clearsPrivateAiState() = runTest {
        val authState = MutableStateFlow(AuthState.AUTHORIZED)
        val viewModel = viewModel(authState)
        viewModel.seedState {
            it.copy(
                activatedCharacterId = "usagi",
                activeFeature = AiFeature.DAILY,
                greeting = greeting(),
                taste = AiTasteAnalysis("吐槽", "画像", emptyList(), emptyList()),
                quiz = quiz(),
                quizResult = quizResult(quota()),
                dailyKnowledge = dailyKnowledge(quota())
            )
        }

        // 授权在监听启动前失效：首个 StateFlow 值也必须触发私有状态清理。
        authState.value = AuthState.UNAUTHORIZED
        viewModel.restoreActivation()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.activatedCharacterId).isNull()
        assertThat(state.activeFeature).isNull()
        assertThat(state.greeting).isNull()
        assertThat(state.taste).isNull()
        assertThat(state.quiz).isNull()
        assertThat(state.quizResult).isNull()
        assertThat(state.dailyKnowledge).isNull()
    }

    @Test
    fun unauthorized_cancelsCharacterPreviewAndKeepsGuestPreviewAvailable() = runTest {
        val authState = MutableStateFlow(AuthState.AUTHORIZED)
        val previewStarted = CompletableDeferred<Unit>()
        val previewCancelled = CompletableDeferred<Unit>()
        val viewModel = viewModel(authState)
        coEvery { aiRepository.listCharacters() } returns Result.success(
            listOf(viewModelCharacter("usagi"), viewModelCharacter("hachiware"))
        )
        coEvery { aiRepository.playTts("friend-a", any()) } coAnswers {
            previewStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                previewCancelled.complete(Unit)
            }
        }
        coEvery { aiRepository.playGuestTts(any()) } returns Result.success(audio())

        viewModel.ensureLoaded()
        runCurrent()
        testScheduler.advanceTimeBy(350)
        runCurrent()
        assertThat(previewStarted.isCompleted).isTrue()

        authState.value = AuthState.UNAUTHORIZED
        runCurrent()

        assertThat(previewCancelled.isCompleted).isTrue()
        assertThat(viewModel.uiState.value.characters).isNotEmpty()
        assertThat(viewModel.uiState.value.selectedCharacterId).isEqualTo("usagi")

        viewModel.selectCharacter("hachiware")
        testScheduler.advanceTimeBy(350)
        runCurrent()
        coVerify(exactly = 1) { aiRepository.playGuestTts(any()) }
    }

    @Test
    fun selectCharacter_bundledAuditionPlaysImmediatelyWithoutTts() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.assets.open(any()) } returns ByteArrayInputStream(ByteArray(16))
        val viewModel = viewModel(context = context)
        // 用本地目录角色（文案与预存音频一致）模拟服务端文案未变的合并结果
        coEvery { aiRepository.listCharacters() } returns Result.success(
            listOf(com.tracktosearch.data.ai.AiCharacterCatalog.all.first { it.id == "usagi" })
        )
        val audioEvent = async { viewModel.audioEvents.first() }

        viewModel.ensureLoaded()
        runCurrent()
        viewModel.selectCharacter("usagi")
        runCurrent()

        // 预存音频命中：跳过防抖直接发 file:///android_asset 源，且不发起任何 TTS 请求
        assertThat(audioEvent.await().audioUrl).isEqualTo("file:///android_asset/ai_auditions/usagi.mp3")
        coVerify(exactly = 0) { aiRepository.playTts(any(), any()) }
        coVerify(exactly = 0) { aiRepository.playGuestTts(any()) }
    }

    @Test
    fun reopeningSpriteCenter_bundledAuditionReachesACollectorRegisteredAfterEnsureLoaded() = runTest {
        val context = mockk<Context>(relaxed = true)
        every { context.assets.open(any()) } returns ByteArrayInputStream(ByteArray(16))
        val viewModel = viewModel(context = context)
        coEvery { aiRepository.listCharacters() } returns Result.success(
            listOf(com.tracktosearch.data.ai.AiCharacterCatalog.all.first { it.id == "usagi" })
        )

        // 第一次 ensureLoaded 模拟搜索页启动时那次调用：initialized 从此为 true
        viewModel.ensureLoaded()
        advanceUntilIdle()

        // 精灵中心的真实顺序：LaunchedEffect 先调 ensureLoaded，收集器晚一拍才 launch。
        // 预存试听不经过 350ms 防抖，事件必须等收集器就位，否则 replay = 0 的 SharedFlow
        // 会静默丢弃，UI 停在 LOADING 干转到 8 秒兜底超时。
        viewModel.ensureLoaded()
        val audioEvent = async { viewModel.audioEvents.first() }
        advanceUntilIdle()

        assertThat(audioEvent.await().audioUrl).isEqualTo("file:///android_asset/ai_auditions/usagi.mp3")
    }

    @Test
    fun replaySelectedCharacter_requestsAuditionImmediately() = runTest {
        val authState = MutableStateFlow(AuthState.UNAUTHORIZED)
        val viewModel = viewModel(authState)
        coEvery { aiRepository.listCharacters() } returns Result.success(
            listOf(viewModelCharacter("usagi"))
        )
        coEvery { aiRepository.playGuestTts(any()) } returns Result.success(audio())

        viewModel.ensureLoaded()
        runCurrent()
        viewModel.replaySelectedCharacter()
        runCurrent()

        coVerify(atLeast = 1) { aiRepository.playGuestTts(any()) }
    }

    @Test
    fun voiceHoldStart_entersRecordingWithoutBurningAnAttempt() = runTest {
        val voiceCapture = FakeVoiceCapture()
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()

        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.RECORDING)
        // 计数点已从「按下」挪到「实际提交」：误触碰一下按钮不该烧掉 5 次机会里的一次
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(0)
        assertThat(voiceCapture.captureCount).isEqualTo(1)
    }

    @Test
    fun voiceHoldCancel_returnsToIdleWithoutErrorOrAttempt() = runTest {
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Level(0.05f)))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()
        testScheduler.advanceTimeBy(600)
        runCurrent()
        viewModel.onActivatePressCancel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.activationState).isEqualTo(AiActivationState.IDLE)
        assertThat(state.activationAttempt).isEqualTo(0)
        assertThat(state.errorCode).isNull()
        assertThat(state.voiceLevel).isEqualTo(0f)
    }

    @Test
    fun voiceHoldReleasedTooSoon_reportsTooShortWithoutBurningAnAttempt() = runTest {
        // 电平给到正常说话音量：太短这一档必须压过电平档，否则误触会被报成「声音太轻」
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Level(0.05f)))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()
        testScheduler.advanceTimeBy(200)
        runCurrent()
        viewModel.onActivatePressEnd()
        advanceTailGrace()

        val state = viewModel.uiState.value
        assertThat(state.errorCode).isEqualTo("ACTIVATION_TOO_SHORT")
        assertThat(state.activationAttempt).isEqualTo(0)
        // 误触不该在按钮下面留一条红字，回 IDLE 当没发生过
        assertThat(state.activationState).isEqualTo(AiActivationState.IDLE)
    }

    @Test
    fun silentVoiceHold_reportsSilentAndBurnsAnAttempt() = runTest {
        // 原始 RMS 0.003 约 -50 dBFS，归一化后是 0：安静房间的本底，等于全程没人说话
        val voiceCapture = FakeVoiceCapture(List(3) { AiVoiceCaptureEvent.Level(0.003f) })
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        holdAndRelease(viewModel)

        val state = viewModel.uiState.value
        assertThat(state.errorCode).isEqualTo("ACTIVATION_SILENT")
        assertThat(state.activationState).isEqualTo(AiActivationState.FAILED)
        // 静音要计次：否则对着被占用的麦克风可以无限重试，5 次上限形同虚设
        assertThat(state.activationAttempt).isEqualTo(1)
    }

    @Test
    fun quietVoiceHold_reportsTooQuietAndBurnsAnAttempt() = runTest {
        // 原始 RMS 0.005 归一化后约 0.08：过了静音线 0.04 但没到太轻线 0.12。
        // 这条同时是「先归一化再比阈值」的看门测试——直接拿原始 RMS 比，
        // 0.005 < 0.04 会被误判成静音，用户被叫去检查麦克风而不是靠近一点再喊
        val voiceCapture = FakeVoiceCapture(List(3) { AiVoiceCaptureEvent.Level(0.005f) })
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        holdAndRelease(viewModel)

        val state = viewModel.uiState.value
        assertThat(state.errorCode).isEqualTo("ACTIVATION_TOO_QUIET")
        assertThat(state.activationAttempt).isEqualTo(1)
    }

    @Test
    fun audibleVoiceHoldWithoutMatch_reportsNotMatchedAndBurnsAnAttemptOnce() = runTest {
        // 一帧 0.0075（归一化约 0.15，过了太轻线 0.12），之后全是近似静音的尾音帧。
        // 峰值必须取未平滑值：平滑后首帧只有 0.15 × 0.6 = 0.09，
        // 会把喊得够响的用户判成「声音太轻」，指向完全错误的下一步动作
        val voiceCapture = FakeVoiceCapture(
            listOf(AiVoiceCaptureEvent.Level(0.0075f)) + List(3) { AiVoiceCaptureEvent.Level(0.0001f) }
        )
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        holdAndRelease(viewModel)

        assertThat(viewModel.uiState.value.errorCode).isEqualTo("ACTIVATION_NOT_MATCHED")
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(1)
        coVerify(exactly = 0) { aiRepository.activate(any(), any()) }
        // 宽限窗口过后收尾只发生一次，次数不会被补第二刀
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(1)
    }

    @Test
    fun matchedSelectedCharacter_verifiesImmediatelyThenActivatesViaTextPath() = runTest {
        val voiceCapture = FakeVoiceCapture(
            listOf(AiVoiceCaptureEvent.Level(0.05f), AiVoiceCaptureEvent.Matched("usagi"))
        )
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()
        val requests = mutableListOf<com.tracktosearch.data.ai.AiActivateRequest>()
        val releaseServer = CompletableDeferred<Unit>()
        coEvery { aiRepository.activate("friend-a", capture(requests)) } coAnswers {
            releaseServer.await()
            Result.success(activation(audio = audio()))
        }
        coEvery { aiRepository.getGreeting(any(), any(), any()) } returns Result.success(greeting())
        // audioEvents 是 replay = 0 的 SharedFlow，没订阅者时 emit 会被静默丢弃
        val ack = async { viewModel.audioEvents.first() }
        runCurrent()

        viewModel.onActivatePressStart()
        runCurrent()

        // 命中即收尾，不等松手
        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.VERIFYING)
        releaseServer.complete(Unit)
        advanceUntilIdle()

        // 本地命中后走服务端文字激活：不上传音频、无 ASR，spokenName 用角色标准名
        assertThat(requests).hasSize(1)
        assertThat(requests.single().audioDataUrl).isNull()
        assertThat(requests.single().spokenName).isEqualTo("usagi")
        val state = viewModel.uiState.value
        assertThat(state.activationState).isEqualTo(AiActivationState.SUCCESS)
        assertThat(state.activatedCharacterId).isEqualTo("usagi")
        assertThat(state.errorCode).isNull()
        // ACK 语音与落盘照旧
        assertThat(ack.await()).isEqualTo(audio())
        coVerify { aiRepository.saveActivatedCharacterId("friend-a", "usagi") }
    }

    @Test
    fun matchedOtherCharacter_keepsRecordingAndLandsOnNotMatched() = runTest {
        val voiceCapture = FakeVoiceCapture(
            listOf(AiVoiceCaptureEvent.Level(0.05f), AiVoiceCaptureEvent.Matched("hachiware"))
        )
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()
        // 喊的是别的角色名：不提前收尾，这一次按住里用户还能补喊正确的名字
        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.RECORDING)

        testScheduler.advanceTimeBy(600)
        runCurrent()
        viewModel.onActivatePressEnd()
        advanceTailGrace()

        // 与旧服务端行为一致：喊了别的角色名按未匹配处理，只针对所选角色激活
        assertThat(viewModel.uiState.value.errorCode).isEqualTo("ACTIVATION_NOT_MATCHED")
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(1)
        coVerify(exactly = 0) { aiRepository.activate(any(), any()) }
    }

    @Test
    fun captureUnavailable_reportsAudioUnavailableWithoutBurningAnAttempt() = runTest {
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Unavailable))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()

        // 拿不到麦克风/引擎当场判死，不等松手，也没提交任何请求，所以不烧机会
        val state = viewModel.uiState.value
        assertThat(state.errorCode).isEqualTo("AUDIO_UNAVAILABLE")
        assertThat(state.activationState).isEqualTo(AiActivationState.FAILED)
        assertThat(state.activationAttempt).isEqualTo(0)
        coVerify(exactly = 0) { aiRepository.activate(any(), any()) }
    }

    @Test
    fun levelEvents_landInUiStateAsSmoothedLevel() = runTest {
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Level(0.05f)))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()

        // 原始 RMS 0.05 约 -26 dBFS，归一化 0.48，再按 attack 0.6 平滑一帧得 0.29；
        // 断言不是原始值 0.05 正是重点：竖条画的必须是归一化平滑后的电平
        assertThat(viewModel.uiState.value.voiceLevel).isWithin(0.01f).of(0.288f)
    }

    @Test
    fun voicePermissionGranted_onlyHintsAndNeverStartsRecording() = runTest {
        val voiceCapture = FakeVoiceCapture()
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onVoicePermissionGranted()
        advanceUntilIdle()

        // 授权弹窗一消失就自动开录会录到一段空白（手指早已离开按钮）并白烧一次机会
        assertThat(viewModel.uiState.value.errorCode).isEqualTo("AUDIO_PERMISSION_GRANTED")
        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.IDLE)
        assertThat(voiceCapture.captureCount).isEqualTo(0)
    }

    @Test
    fun matchArrivingInsideTailGrace_stillActivates() = runTest {
        // KWS 配的是 numTrailingBlanks = 2：关键词说完还要约 80-150 ms 尾音才确认命中。
        // 「说完就松手」是最自然的操作，全靠松手后的宽限窗口这段音频才拿得到成功
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Level(0.05f)))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()
        val releaseServer = CompletableDeferred<Unit>()
        coEvery { aiRepository.activate("friend-a", any()) } coAnswers {
            releaseServer.await()
            Result.success(activation())
        }
        coEvery { aiRepository.getGreeting(any(), any(), any()) } returns Result.success(greeting())

        viewModel.onActivatePressStart()
        runCurrent()
        testScheduler.advanceTimeBy(600)
        runCurrent()
        viewModel.onActivatePressEnd()
        // 仍在 250 ms 宽限窗口内：麦克风还在送真实音频
        testScheduler.advanceTimeBy(100)
        runCurrent()
        voiceCapture.push(AiVoiceCaptureEvent.Matched("usagi"))
        runCurrent()

        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.VERIFYING)
        releaseServer.complete(Unit)
        advanceUntilIdle()

        // 排在后面的那次宽限收尾必须是空操作，不能把成功覆盖成 ACTIVATION_NOT_MATCHED
        val state = viewModel.uiState.value
        assertThat(state.activationState).isEqualTo(AiActivationState.SUCCESS)
        assertThat(state.activatedCharacterId).isEqualTo("usagi")
        assertThat(state.errorCode).isNull()
        coVerify(exactly = 1) { aiRepository.activate("friend-a", any()) }
    }

    @Test
    fun tailGraceDoesNotCountTowardHoldDuration() = runTest {
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Level(0.05f)))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()
        testScheduler.advanceTimeBy(400)
        runCurrent()
        viewModel.onActivatePressEnd()
        advanceTailGrace()

        // 采集协程活了约 650 ms（400 + 250 宽限），但按住时长只算到松手那一刻的 400 ms：
        // 把宽限窗口算进去，误触就能绕过 500 ms 下限白烧一次机会
        val state = viewModel.uiState.value
        assertThat(state.errorCode).isEqualTo("ACTIVATION_TOO_SHORT")
        assertThat(state.activationState).isEqualTo(AiActivationState.IDLE)
        assertThat(state.activationAttempt).isEqualTo(0)
    }

    @Test
    fun lateCancelAfterMatchedSuccess_keepsActivatedState() = runTest {
        val voiceCapture = FakeVoiceCapture(
            listOf(AiVoiceCaptureEvent.Level(0.05f), AiVoiceCaptureEvent.Matched("usagi"))
        )
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()
        coEvery { aiRepository.activate("friend-a", any()) } returns Result.success(activation())
        coEvery { aiRepository.getGreeting(any(), any(), any()) } returns Result.success(greeting())

        viewModel.onActivatePressStart()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.SUCCESS)

        // 命中是手指还按着时就收尾的：SUCCESS 后 UI 换掉激活面板，手势协程被连带取消，
        // 其 finally 会补发一次取消。那一次必须是空操作，否则刚激活的角色和问候气泡都被抹掉
        viewModel.onActivatePressCancel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.activationState).isEqualTo(AiActivationState.SUCCESS)
        assertThat(state.activatedCharacterId).isEqualTo("usagi")
        assertThat(state.errorCode).isNull()
    }

    @Test
    fun lateReleaseAfterTimeoutFinish_doesNotBurnASecondAttempt() = runTest {
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Level(0.05f)))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()
        // 10 秒上限自行收尾（同样带 250 ms 尾音宽限）
        testScheduler.advanceTimeBy(11_000)
        runCurrent()
        assertThat(viewModel.uiState.value.errorCode).isEqualTo("ACTIVATION_NOT_MATCHED")
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(1)

        // 用户到这时才真的松手：不能再收尾一遍，更不能再烧一次机会
        viewModel.onActivatePressEnd()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(1)
    }

    @Test
    fun doublePressEnd_appliesVerdictExactlyOnce() = runTest {
        val voiceCapture = FakeVoiceCapture(listOf(AiVoiceCaptureEvent.Level(0.05f)))
        val viewModel = viewModel(voiceCapture = voiceCapture)
        viewModel.seedVoiceReadyState()

        viewModel.onActivatePressStart()
        runCurrent()
        testScheduler.advanceTimeBy(600)
        runCurrent()
        viewModel.onActivatePressEnd()
        viewModel.onActivatePressEnd()
        advanceTailGrace()

        assertThat(viewModel.uiState.value.errorCode).isEqualTo("ACTIVATION_NOT_MATCHED")
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(1)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.activationAttempt).isEqualTo(1)
    }

    @Test
    fun lateActivationResult_cannotReplaceOfflineState() = runTest {
        val networkStatus = MutableStateFlow(ConnectivityObserver.NetworkStatus.ONLINE)
        val result = CompletableDeferred<Result<com.tracktosearch.data.ai.AiActivation>>()
        val viewModel = viewModel(networkStatus = networkStatus)
        viewModel.seedVoiceReadyState()
        coEvery { aiRepository.activate("friend-a", any()) } coAnswers {
            withContext(NonCancellable) { result.await() }
        }

        viewModel.activateByText("usagi")
        runCurrent()
        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.VERIFYING)

        networkStatus.value = ConnectivityObserver.NetworkStatus.OFFLINE
        runCurrent()
        assertThat(viewModel.uiState.value.networkStatus)
            .isEqualTo(ConnectivityObserver.NetworkStatus.OFFLINE)
        assertThat(viewModel.uiState.value.errorCode).isEqualTo(AI_OFFLINE_ERROR_CODE)

        result.complete(Result.success(activation()))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activatedCharacterId).isNull()
        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.IDLE)
        assertThat(viewModel.uiState.value.errorCode).isEqualTo(AI_OFFLINE_ERROR_CODE)
    }

    @Test
    fun authorizedAuditionFailure_fallsBackToSystemSpeech() = runTest {
        val viewModel = viewModel()
        coEvery { aiRepository.listCharacters() } returns Result.success(
            listOf(viewModelCharacter("usagi"))
        )
        coEvery { aiRepository.playTts("friend-a", any()) } returns Result.failure(RuntimeException("TTS_UNAVAILABLE"))
        val fallback = async { viewModel.guestPreviewFallbackEvents.first() }

        viewModel.ensureLoaded()
        testScheduler.advanceTimeBy(350)
        runCurrent()

        assertThat(fallback.await()).isEqualTo("试听")
    }

    @Test
    fun ensureLoaded_previewsRestoredCharacterAfterRestore() = runTest {
        val viewModel = viewModel()
        coEvery { aiRepository.listCharacters() } returns Result.success(
            listOf(viewModelCharacter("usagi"))
        )
        coEvery { aiRepository.readActivatedCharacterId("friend-a") } returns "usagi"
        coEvery { aiRepository.playTts("friend-a", any()) } returns Result.success(audio())

        viewModel.ensureLoaded()
        advanceUntilIdle()

        // 落盘激活角色恢复后要自动试听：恢复是异步的，可能晚于 ensureLoaded
        // 末尾那次 scheduleCharacterPreview，不补触发 UI 会停在 LOADING 干转
        coVerify(atLeast = 1) { aiRepository.playTts("friend-a", any()) }
    }

    @Test
    fun restoreActivation_bringsBackPersistedCharacterWithoutCatalogOrPreviewRequests() = runTest {
        val viewModel = viewModel()
        coEvery { aiRepository.readActivatedCharacterId("friend-a") } returns "hachiware"

        viewModel.restoreActivation()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activatedCharacterId).isEqualTo("hachiware")
        assertThat(viewModel.uiState.value.selectedCharacterId).isEqualTo("hachiware")
        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.SUCCESS)
        // 详情页走这条路径，不能顺带拉角色目录或播试听
        coVerify(exactly = 0) { aiRepository.listCharacters() }
        coVerify(exactly = 0) { aiRepository.playTts(any(), any()) }
    }

    @Test
    fun restoreActivation_ignoresPersistedCharacterOutsideCatalog() = runTest {
        val viewModel = viewModel()
        coEvery { aiRepository.readActivatedCharacterId("friend-a") } returns "not-a-character"

        viewModel.restoreActivation()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activatedCharacterId).isNull()
    }

    @Test
    fun restoreActivation_doesNotOverrideCharacterActivatedInThisSession() = runTest {
        val viewModel = viewModel()
        viewModel.seedState { it.copy(activatedCharacterId = "usagi") }
        coEvery { aiRepository.readActivatedCharacterId("friend-a") } returns "hachiware"

        viewModel.restoreActivation()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.activatedCharacterId).isEqualTo("usagi")
    }

    @Test
    fun activationSuccessPersistsCharacterForLaterProcesses() = runTest {
        val viewModel = viewModel()
        viewModel.seedVoiceReadyState()
        coEvery { aiRepository.activate(any(), any()) } returns Result.success(activation())

        viewModel.activateByText("usagi")
        advanceUntilIdle()

        coVerify { aiRepository.saveActivatedCharacterId("friend-a", "usagi") }
    }

    @Test
    fun quizPreview_usesLocalizedTitlesButSubmitsOriginalTitlesToAi() = runTest {
        val watched = (0 until 7).map { index ->
            TraktWatchlistMovieItem(
                watched_at = "2024-01-${"%02d".format(index + 1)}T00:00:00Z",
                movie = TraktMovie(
                    title = "Original $index",
                    year = 2024,
                    ids = TraktIds(trakt = index + 1, tmdb = 100 + index),
                    genres = listOf("drama")
                )
            )
        }
        val networkStatus = MutableStateFlow(ConnectivityObserver.NetworkStatus.ONLINE)
        val localizedRequests = mutableListOf<Int>()
        val quizRequests = mutableListOf<AiQuizRequest>()
        // 出题已改流式：请求从流事件里捕获。注意 getQuizStream 不是 suspend 函数，
        // 桩必须返回 flow{}／flowOf，不能用 coAnswers（会被 runBlocking 焊死测试线程）。
        // 进入出题页会先静默预生成，所以捕获到的是 [预生成, 正式出题] 两条。
        coEvery {
            aiRepository.getQuizStream("friend-a", capture(quizRequests), any())
        } returns flowOf(AiQuizStreamEvent.Completed(quiz()))
        val viewModel = viewModel(
            networkStatus = networkStatus,
            language = MutableStateFlow(LanguageStorage.LANGUAGE_CHINESE)
        )
        coEvery { traktRepository.getAllMovieHistory(extended = "full") } returns Result.success(watched)
        coEvery { traktRepository.getAllShowHistory(extended = "full") } returns Result.success(emptyList())
        every { tmdbRepository.peekMovieEnrichment(any(), any(), any()) } returns null
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } coAnswers {
            localizedRequests += firstArg<Int>()
            TmdbRepository.MovieEnrichment(
                posterUrl = null,
                chineseTitle = "本地化标题-${firstArg<Int>()}",
                originalTitle = "Original TMDB ${firstArg<Int>()}",
                overview = "TMDB 可靠剧情简介 ${firstArg<Int>()}",
                genres = "drama · history",
                year = 2024,
                rating = 0.0,
                runtime = 128,
                country = "美国",
                imdbId = "tt${firstArg<Int>()}"
            )
        }

        viewModel.openFeature(AiFeature.QUIZ)
        advanceUntilIdle()

        val previewState = viewModel.uiState.value
        assertThat(previewState.quizPreviewMovies).hasSize(7)
        assertThat(previewState.quizPreviewLocalizedTitles).hasSize(7)
        assertThat(localizedRequests).hasSize(7)
        assertThat(previewState.quizPreviewLocalizedTitles.values)
            .containsExactlyElementsIn(localizedRequests.map { "本地化标题-$it" })

        val prewarmCallCount = quizRequests.size
        viewModel.startQuiz()
        advanceUntilIdle()

        val submit = quizRequests.last()
        assertThat(submit.prefetch).isFalse()
        assertThat(quizRequests).hasSize(prewarmCallCount + 1)
        assertThat(submit.watched.map { it.title })
            .containsExactlyElementsIn(watched.map { it.movie.title })
        assertThat(submit.watched.map { it.title })
            .containsNoneIn(previewState.quizPreviewLocalizedTitles.values)
        val watched100 = submit.watched.single {
            it.mediaIds.tmdbId == 100
        }
        assertThat(watched100.overview).isEqualTo("TMDB 可靠剧情简介 100")
        assertThat(watched100.originalTitle).isEqualTo("Original TMDB 100")
        assertThat(watched100.runtime).isEqualTo(128)
        assertThat(watched100.country).isEqualTo("美国")
        assertThat(watched100.mediaIds.imdbId).isEqualTo("tt100")
        assertThat(watched100.genres).containsExactly("drama")
    }

    @Test
    fun quizPreview_keepsSuccessfulLocalizedTitlesWhenOneTmdbRequestFails() = runTest {
        val watched = (0 until 7).map { index ->
            TraktWatchlistMovieItem(
                watched_at = "2024-02-${"%02d".format(index + 1)}T00:00:00Z",
                movie = TraktMovie(
                    title = "Original $index",
                    year = 2024,
                    ids = TraktIds(trakt = index + 1, tmdb = 200 + index),
                    genres = listOf("drama")
                )
            )
        }
        val failedTmdbId = 203
        val viewModel = viewModel(
            language = MutableStateFlow(LanguageStorage.LANGUAGE_CHINESE)
        )
        coEvery { traktRepository.getAllMovieHistory(extended = "full") } returns Result.success(watched)
        coEvery { traktRepository.getAllShowHistory(extended = "full") } returns Result.success(emptyList())
        every { tmdbRepository.peekMovieEnrichment(any(), any(), any()) } returns null
        coEvery { tmdbRepository.enrichMovie(any(), any(), any()) } coAnswers {
            val tmdbId = firstArg<Int>()
            if (tmdbId == failedTmdbId) throw RuntimeException("TMDB unavailable")
            TmdbRepository.MovieEnrichment(
                posterUrl = null,
                chineseTitle = "本地化标题-$tmdbId",
                originalTitle = secondArg<String>(),
                overview = "",
                genres = "",
                year = 2024,
                rating = 0.0
            )
        }

        viewModel.openFeature(AiFeature.QUIZ)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.quizPreviewMovies).hasSize(7)
        assertThat(state.quizPreviewLocalizedTitles).containsKey(quizMediaKey("movie", "1"))
        assertThat(state.quizPreviewLocalizedTitles).doesNotContainKey(quizMediaKey("movie", "4"))
        assertThat(state.quizPreviewLocalizedTitles).hasSize(6)
        assertThat(state.quizPreviewMovies.single { it.mediaIds.tmdbId == failedTmdbId }.title)
            .isEqualTo("Original 3")
    }

    @Test
    fun openingQuiz_preparesTodaysSetSilentlyAndStartOnlySubmitsIt() = runTest {
        val requests = mutableListOf<AiQuizRequest>()
        val viewModel = viewModel()
        stubQuizWatchedHistory()
        coEvery { aiRepository.getQuizStream("friend-a", capture(requests), any()) } returns
            flowOf(AiQuizStreamEvent.Completed(quiz()))

        viewModel.openFeature(AiFeature.QUIZ)
        advanceUntilIdle()

        // 进页面就静默预生成当天这一套，并把状态摆给用户看
        assertThat(requests.map { it.prefetch }).containsExactly(true)
        assertThat(viewModel.uiState.value.quizPrepareState).isEqualTo(AiQuizPrepareState.READY)
        val prepared = requests.single()
        assertThat(prepared.date).isEqualTo(currentBeijingDate())

        viewModel.startQuiz()
        advanceUntilIdle()

        // 正式出题仍要发一次请求：服务端靠它把当天这一套记为「已玩」，否则下一套会重复；
        // 但参数与预生成逐字一致，所以这次是命中题库的快速返回。
        assertThat(requests.map { it.prefetch }).containsExactly(true, false)
        val submit = requests.last()
        assertThat(submit.date).isEqualTo(prepared.date)
        assertThat(submit.watched.map { it.mediaIds.tmdbId })
            .containsExactlyElementsIn(prepared.watched.map { it.mediaIds.tmdbId })
        assertThat(viewModel.uiState.value.quizStarted).isTrue()
        assertThat(viewModel.uiState.value.quiz).isNotNull()
        assertThat(viewModel.uiState.value.quizGenerating).isFalse()
    }

    @Test
    fun startQuizWhilePrewarmRuns_waitsForThatStreamInsteadOfStartingASecondOne() = runTest {
        val requests = mutableListOf<AiQuizRequest>()
        val release = CompletableDeferred<Unit>()
        val viewModel = viewModel()
        stubQuizWatchedHistory()
        coEvery { aiRepository.getQuizStream("friend-a", capture(requests), any()) } returns flow {
            release.await()
            emit(AiQuizStreamEvent.Completed(quiz()))
        }

        viewModel.openFeature(AiFeature.QUIZ)
        runCurrent()
        assertThat(requests.map { it.prefetch }).containsExactly(true)

        viewModel.startQuiz()
        runCurrent()

        // 预生成还在跑：等待页当场可见，而且没有第二条流（同一套题生成两遍要多烧几十次上游调用）
        assertThat(viewModel.uiState.value.quizGenerating).isTrue()
        assertThat(requests).hasSize(1)

        release.complete(Unit)
        advanceUntilIdle()

        assertThat(requests.map { it.prefetch }).containsExactly(true, false)
        assertThat(viewModel.uiState.value.quizStarted).isTrue()
    }

    @Test
    fun prewarmFailure_staysSilentAndStartFallsBackToColdGeneration() = runTest {
        val requests = mutableListOf<AiQuizRequest>()
        val viewModel = viewModel()
        stubQuizWatchedHistory()
        coEvery { aiRepository.getQuizStream("friend-a", capture(requests), any()) } returnsMany listOf(
            flow { throw IllegalStateException("upstream down") },
            flowOf(AiQuizStreamEvent.Completed(quiz()))
        )

        viewModel.openFeature(AiFeature.QUIZ)
        advanceUntilIdle()

        // 预生成失败不打扰用户：没有错误态，只在预览页说明「点开始会现场生成」
        assertThat(viewModel.uiState.value.quizPrepareState).isEqualTo(AiQuizPrepareState.FAILED)
        assertThat(viewModel.uiState.value.errorCode).isNull()
        assertThat(viewModel.uiState.value.quizPreviewMovies).hasSize(7)

        viewModel.startQuiz()
        advanceUntilIdle()

        assertThat(requests.map { it.prefetch }).containsExactly(true, false)
        assertThat(viewModel.uiState.value.quizStarted).isTrue()
    }

    @Test
    fun prewarmRefusedForTheDayShowsUsedUpAndDoesNotAskAgain() = runTest {
        val requests = mutableListOf<AiQuizRequest>()
        val viewModel = viewModel()
        stubQuizWatchedHistory()
        // 当天该预生成的套都发过了：服务端拒发预生成（用户玩完了当天的量），这不是故障
        coEvery { aiRepository.getQuizStream("friend-a", capture(requests), any()) } returns
            flowOf(AiQuizStreamEvent.DailySetsDone)

        viewModel.openFeature(AiFeature.QUIZ)
        advanceUntilIdle()

        // 文案要说实话：不是「没备好」，是「今天的题已玩完」
        assertThat(viewModel.uiState.value.quizPrepareState).isEqualTo(AiQuizPrepareState.EXHAUSTED)
        assertThat(viewModel.uiState.value.errorCode).isNull()
        assertThat(requests.map { it.prefetch }).containsExactly(true)

        // 当天的结论稳定（已玩套数只增不减）：再进页面不该重新问一次服务端
        viewModel.closeFeature()
        viewModel.openFeature(AiFeature.QUIZ)
        advanceUntilIdle()

        assertThat(requests.map { it.prefetch }).containsExactly(true)
        assertThat(viewModel.uiState.value.quizPrepareState).isEqualTo(AiQuizPrepareState.EXHAUSTED)
    }

    @Test
    fun usedUpDayStillStartsTheColdGenerationWhenTheUserAsks() = runTest {
        val requests = mutableListOf<AiQuizRequest>()
        val viewModel = viewModel()
        stubQuizWatchedHistory()
        coEvery { aiRepository.getQuizStream("friend-a", capture(requests), any()) } returnsMany listOf(
            flowOf(AiQuizStreamEvent.DailySetsDone),
            flowOf(AiQuizStreamEvent.Completed(quiz()))
        )

        viewModel.openFeature(AiFeature.QUIZ)
        advanceUntilIdle()
        viewModel.startQuiz()
        advanceUntilIdle()

        // 拒发只针对预生成：正式请求照发，服务端按需生成，用户点开始仍然能玩
        assertThat(requests.map { it.prefetch }).containsExactly(true, false)
        assertThat(viewModel.uiState.value.quizStarted).isTrue()
        assertThat(viewModel.uiState.value.quiz).isNotNull()
    }

    /** 出题页需要 ≥7 部已看影视：所有出题用例共用这份桩。 */
    private fun stubQuizWatchedHistory(count: Int = 7) {
        val watched = (0 until count).map { index ->
            TraktWatchlistMovieItem(
                watched_at = "2024-03-${"%02d".format(index + 1)}T00:00:00Z",
                movie = TraktMovie(
                    title = "Quiz Watch $index",
                    year = 2024,
                    ids = TraktIds(trakt = index + 1, tmdb = 300 + index),
                    genres = listOf("drama")
                )
            )
        }
        coEvery { traktRepository.getAllMovieHistory(extended = "full") } returns Result.success(watched)
        coEvery { traktRepository.getAllShowHistory(extended = "full") } returns Result.success(emptyList())
        every { tmdbRepository.peekMovieEnrichment(any(), any(), any()) } returns null
    }

    private fun currentBeijingDate(): String =
        LocalDate.now(java.time.ZoneId.of("GMT+8")).toString()

    @Test
    fun openingSpriteCenterRotatesSessionIdSoSessionQuotaResetsPerOpen() = runTest {
        val viewModel = viewModel()
        viewModel.seedState {
            it.copy(characters = listOf(viewModelCharacter("usagi")), selectedCharacterId = "usagi")
        }
        val requests = mutableListOf<com.tracktosearch.data.ai.AiTtsRequest>()
        coEvery { aiRepository.playTts("friend-a", capture(requests)) } returns Result.success(audio())

        viewModel.replaySelectedCharacter()
        advanceUntilIdle()
        viewModel.onSpriteCenterOpened()
        viewModel.replaySelectedCharacter()
        advanceUntilIdle()

        assertThat(requests).hasSize(2)
        // ViewModel 现在跨页面共享，会话 ID 必须按「打开一次精灵中心」轮换，
        // 否则会话配额到 App 重启才重置
        assertThat(requests[0].sessionId).isNotEqualTo(requests[1].sessionId)
        assertThat(requests.map { it.sessionId.startsWith("sprite-") }).containsExactly(true, true)
    }

    private fun TestScope.viewModel(
        authState: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.AUTHORIZED),
        networkStatus: MutableStateFlow<ConnectivityObserver.NetworkStatus> =
            MutableStateFlow(ConnectivityObserver.NetworkStatus.ONLINE),
        language: MutableStateFlow<String> = MutableStateFlow(LanguageStorage.LANGUAGE_SYSTEM),
        // 榛樿妯℃嫙 App 鏈墦鍖呴瀛橀煶棰戯細assets 璇诲彇鎶涘紓甯革紝璇曞惉璧扮綉缁?TTS 閾捐矾
        context: Context = mockk(relaxed = true) {
            every { assets.open(any()) } throws FileNotFoundException("no bundled audition")
        },
        voiceCapture: AiVoiceCapture = FakeVoiceCapture(),
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): AiSpriteViewModel {
        every { authManager.authState } returns authState
        every { authManager.nickname } returns MutableStateFlow("鏈嬪弸")
        every { authManager.friendId } returns MutableStateFlow("friend-a")
        every { connectivityObserver.status } returns networkStatus
        every { languageStorage.language } returns language
        coEvery { aiRepository.listCharacters() } returns Result.success(emptyList())
        // 閿愯瘎闅愮瀹堝崼榛樿鏀捐锛氬凡鍚屾剰璇存槑寮圭獥涓斾笂浼犲紑鍏冲紑鍚?
        every { aiTasteStorage.tasteConsentDecided } returns flowOf(true)
        every { aiTasteStorage.tasteUploadEnabled } returns flowOf(true)
        return AiSpriteViewModel(
            aiRepository,
            authManager,
            traktRepository,
            tmdbRepository,
            connectivityObserver,
            languageStorage,
            StandardTestDispatcher(testScheduler),
            overlayStorage,
            aiTasteStorage,
            voiceCapture,
            context,
            savedStateHandle
        )
    }

    /**
     * 假采集：先按脚本发事件，之后像真实实现那样一直挂着不结束。
     *
     * 不自行结束是关键——真实采集是冷 Flow，收尾时机由 ViewModel 取消协程决定；
     * 脚本发完就结束的话，「松手才收尾」的时序在测试里会退化成「流一结束就收尾」。
     */
    private class FakeVoiceCapture(scripted: List<AiVoiceCaptureEvent> = emptyList()) : AiVoiceCapture {
        private val events = Channel<AiVoiceCaptureEvent>(Channel.UNLIMITED)

        /** capture() 被收集过几次，用于断言「这条路径不该开录」。 */
        var captureCount = 0
            private set

        init {
            scripted.forEach { events.trySend(it) }
        }

        /** 按住期间补发一帧事件，例如尾音宽限窗口里迟到的命中。 */
        fun push(event: AiVoiceCaptureEvent) {
            events.trySend(event)
        }

        override fun capture(): Flow<AiVoiceCaptureEvent> = flow {
            captureCount += 1
            for (event in events) emit(event)
        }
    }

    /** 按住语音固件：usagi 已上线且被选中，授权态由 viewModel() 给到 AUTHORIZED。 */
    private fun AiSpriteViewModel.seedVoiceReadyState() = seedState {
        it.copy(characters = listOf(viewModelCharacter("usagi")), selectedCharacterId = "usagi")
    }

    /** 按住 600 ms（过 500 ms 下限）后正常松手并走完尾音宽限窗口。 */
    private fun TestScope.holdAndRelease(viewModel: AiSpriteViewModel) {
        viewModel.onActivatePressStart()
        runCurrent()
        testScheduler.advanceTimeBy(600)
        runCurrent()
        viewModel.onActivatePressEnd()
        advanceTailGrace()
    }

    /** 走完松手后的 250 ms 尾音宽限窗口，让收尾落地。 */
    private fun TestScope.advanceTailGrace() {
        testScheduler.advanceTimeBy(400)
        runCurrent()
    }

    private fun viewModelCharacter(id: String) = com.tracktosearch.data.ai.AiCharacter(
        id = id,
        name = id,
        activationWord = id,
        isAvailable = true,
        auditionText = "试听"
    )

    private fun audio() = com.tracktosearch.data.ai.AiAudio(
        audioDataUrl = "data:audio/mpeg;base64,ZmFrZQ==",
        audioUrl = null,
        mimeType = "audio/mpeg",
        durationMs = 100,
        cacheKey = null,
        transcript = "试听"
    )

    private fun activation(
        activated: Boolean = true,
        audio: com.tracktosearch.data.ai.AiAudio? = null
    ) = com.tracktosearch.data.ai.AiActivation(
        activated = activated,
        activationPhrase = "到！",
        character = null,
        quota = null,
        greeting = null,
        audio = audio
    )

    @Suppress("UNCHECKED_CAST")
    private fun AiSpriteViewModel.seedState(
        transform: (AiSpriteUiState) -> AiSpriteUiState
    ) {
        val field = AiSpriteViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        val state = field.get(this) as MutableStateFlow<AiSpriteUiState>
        state.value = transform(state.value)
    }

    private fun quota(sessionUsed: Int = 1, dailyUsed: Int = 1) = AiQuota(
        sessionUsed = sessionUsed,
        sessionLimit = 14,
        dailyUsed = dailyUsed,
        dailyLimit = 80,
        resetAt = null
    )

    private fun quiz() = AiQuiz(
        quizId = "quiz-1",
        title = "测试问关",
        subtitle = "",
        mediaTitles = emptyList(),
        questions = emptyList(),
        totalScore = 100
    )

    private fun quizResult(quota: AiQuota) = AiQuizResult(
        quizId = "quiz-1",
        score = 90,
        totalScore = 100,
        correctCount = 12,
        totalQuestions = 13,
        summary = "不错",
        dimensionScores = emptyMap(),
        questionResults = emptyList(),
        quota = quota
    )

    private fun structuredDailyKnowledge(quota: AiQuota? = null) = AiDailyKnowledge(
        id = "daily-structured",
        unitId = "unit-1",
        title = "为什么第一个异议很重要？",
        fact = "第一个表达异议的人会降低其他人的心理成本。",
        explanation = "群体共识会放大沉默压力。",
        sourceName = "来源",
        sourceUrl = "https://example.com",
        publishedAt = null,
        characterLine = null,
        quota = quota,
        relationType = "direct_watch",
        evidenceMode = "viewing_interpretation",
        subjectGroup = "people_and_mind",
        subject = "社会心理学",
        concept = "从众压力",
        realWorldExample = "会议里第一个提出不同意见的人。",
        boundary = "这是入门解读，不是临床诊断。",
        difficulty = "medium",
        spoilerLevel = "light",
        checkQuestion = AiDailyCheckQuestion(
            prompt = "这道题考察什么？",
            options = listOf(
                AiQuizOption("a", "从众压力"),
                AiQuizOption("b", "投票规则")
            ),
            correctOptionIds = listOf("a"),
            explanation = "影片证据指向群体压力，不是程序规则。"
        )
    )

    private fun dailyKnowledge(quota: AiQuota? = null) = AiDailyKnowledge(
        id = "daily-1",
        title = "每日知识",
        fact = "事实",
        explanation = "解释",
        sourceName = "来源",
        sourceUrl = "https://example.com",
        publishedAt = null,
        characterLine = null,
        quota = quota
    )

    private fun greeting() = AiGreeting(
        nickname = "朋友",
        greeting = "你好",
        nicknameMeaning = "",
        comment = "",
        audio = null
    )
}
