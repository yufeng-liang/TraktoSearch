package com.tracktosearch.ui.screen.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizHistory
import com.tracktosearch.data.ai.AiQuizResult
import com.tracktosearch.data.ai.AiQuota
import com.tracktosearch.data.ai.AiRepository
import com.tracktosearch.data.ai.AiTasteAnalysis
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.test.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiSpriteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val aiRepository = mockk<AiRepository>(relaxed = true)
    private val authManager = mockk<AuthManager>()
    private val traktRepository = mockk<TraktRepository>(relaxed = true)

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
        coEvery { aiRepository.getDailyKnowledge("friend-a", false) } returns Result.success(daily)

        viewModel.openFeature(AiFeature.DAILY)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dailyKnowledge).isEqualTo(daily)
        assertThat(viewModel.uiState.value.quota).isEqualTo(expectedQuota)
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
        coEvery { aiRepository.getDailyKnowledge("friend-a", false) } coAnswers {
            newRequestStarted.complete(Unit)
            releaseNewRequest.await()
            Result.success(dailyKnowledge())
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

    private fun viewModel(
        authState: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.AUTHORIZED)
    ): AiSpriteViewModel {
        every { authManager.authState } returns authState
        every { authManager.nickname } returns MutableStateFlow("朋友")
        every { authManager.friendId } returns MutableStateFlow("friend-a")
        coEvery { aiRepository.listCharacters() } returns Result.success(emptyList())
        return AiSpriteViewModel(aiRepository, authManager, traktRepository)
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
