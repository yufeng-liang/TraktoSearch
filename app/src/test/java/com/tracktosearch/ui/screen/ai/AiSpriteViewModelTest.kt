package com.tracktosearch.ui.screen.ai

import android.content.Context
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
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

@OptIn(ExperimentalCoroutinesApi::class)
class AiSpriteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val aiRepository = mockk<AiRepository>(relaxed = true)
    private val authManager = mockk<AuthManager>()
    private val traktRepository = mockk<TraktRepository>(relaxed = true)
    private val overlayStorage = mockk<com.tracktosearch.data.local.AiSpriteOverlayStorage>(relaxed = true)
    private val aiTasteStorage = mockk<com.tracktosearch.data.local.AiTasteStorage>()

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
    fun voiceActivation_localMatch_activatesViaTextPathWithoutAudioUpload() = runTest {
        val recognizer = mockk<com.tracktosearch.data.ai.AiKwsRecognizer> {
            coEvery { recognize(any()) } returns com.tracktosearch.data.ai.AiVoiceMatch.Matched("usagi")
        }
        val viewModel = viewModel(voiceRecognizer = recognizer)
        viewModel.seedState {
            it.copy(characters = listOf(viewModelCharacter("usagi")), selectedCharacterId = "usagi")
        }
        val requests = mutableListOf<com.tracktosearch.data.ai.AiActivateRequest>()
        coEvery { aiRepository.activate("friend-a", capture(requests)) } returns Result.success(
            com.tracktosearch.data.ai.AiActivation(
                activated = true,
                activationPhrase = "到！",
                character = null,
                quota = null,
                greeting = null,
                audio = null
            )
        )
        mockkObject(AiAudioRecorder)
        try {
            coEvery { AiAudioRecorder.recordPcmOnce(any(), any()) } returns FloatArray(16_000)
            viewModel.activate(mockk())
            advanceUntilIdle()
        } finally {
            unmockkObject(AiAudioRecorder)
        }

        // 本地命中后走服务端文字激活：不上传音频、无 ASR，spokenName 用角色标准名
        assertThat(requests).hasSize(1)
        assertThat(requests.single().audioDataUrl).isNull()
        assertThat(requests.single().spokenName).isEqualTo("usagi")
        coVerify { aiRepository.saveActivatedCharacterId("friend-a", "usagi") }
    }

    @Test
    fun voiceActivation_noMatch_showsNotMatchedAndOffersTextWithoutServerCall() = runTest {
        val viewModel = viewModel()
        viewModel.seedState {
            it.copy(characters = listOf(viewModelCharacter("usagi")), selectedCharacterId = "usagi")
        }
        mockkObject(AiAudioRecorder)
        try {
            coEvery { AiAudioRecorder.recordPcmOnce(any(), any()) } returns FloatArray(16_000)
            viewModel.activate(mockk())
            advanceUntilIdle()
        } finally {
            unmockkObject(AiAudioRecorder)
        }

        assertThat(viewModel.uiState.value.activationState).isEqualTo(AiActivationState.FAILED)
        assertThat(viewModel.uiState.value.errorCode).isEqualTo("ACTIVATION_NOT_MATCHED")
        assertThat(viewModel.uiState.value.textActivationOffered).isTrue()
        coVerify(exactly = 0) { aiRepository.activate(any(), any()) }
    }

    @Test
    fun voiceActivation_matchedOtherCharacter_treatedAsNotMatched() = runTest {
        val recognizer = mockk<com.tracktosearch.data.ai.AiKwsRecognizer> {
            coEvery { recognize(any()) } returns com.tracktosearch.data.ai.AiVoiceMatch.Matched("hachiware")
        }
        val viewModel = viewModel(voiceRecognizer = recognizer)
        viewModel.seedState {
            it.copy(characters = listOf(viewModelCharacter("usagi")), selectedCharacterId = "usagi")
        }
        mockkObject(AiAudioRecorder)
        try {
            coEvery { AiAudioRecorder.recordPcmOnce(any(), any()) } returns FloatArray(16_000)
            viewModel.activate(mockk())
            advanceUntilIdle()
        } finally {
            unmockkObject(AiAudioRecorder)
        }

        // 与旧服务端行为一致：喊了别的角色名按未匹配处理，只针对所选角色激活
        assertThat(viewModel.uiState.value.errorCode).isEqualTo("ACTIVATION_NOT_MATCHED")
        coVerify(exactly = 0) { aiRepository.activate(any(), any()) }
    }

    @Test
    fun voiceActivation_recognizerUnavailable_fallsBackToTextOffer() = runTest {
        val recognizer = mockk<com.tracktosearch.data.ai.AiKwsRecognizer> {
            coEvery { recognize(any()) } returns com.tracktosearch.data.ai.AiVoiceMatch.Unavailable
        }
        val viewModel = viewModel(voiceRecognizer = recognizer)
        viewModel.seedState {
            it.copy(characters = listOf(viewModelCharacter("usagi")), selectedCharacterId = "usagi")
        }
        mockkObject(AiAudioRecorder)
        try {
            coEvery { AiAudioRecorder.recordPcmOnce(any(), any()) } returns FloatArray(16_000)
            viewModel.activate(mockk())
            advanceUntilIdle()
        } finally {
            unmockkObject(AiAudioRecorder)
        }

        assertThat(viewModel.uiState.value.errorCode).isEqualTo("AUDIO_UNAVAILABLE")
        assertThat(viewModel.uiState.value.textActivationOffered).isTrue()
        coVerify(exactly = 0) { aiRepository.activate(any(), any()) }
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
        viewModel.seedState {
            it.copy(
                characters = listOf(viewModelCharacter("usagi")),
                selectedCharacterId = "usagi",
                textActivationOffered = true
            )
        }
        coEvery { aiRepository.activate(any(), any()) } returns Result.success(
            com.tracktosearch.data.ai.AiActivation(
                activated = true,
                activationPhrase = "到！",
                character = null,
                quota = null,
                greeting = null,
                audio = null
            )
        )

        viewModel.activateByText("usagi")
        advanceUntilIdle()

        coVerify { aiRepository.saveActivatedCharacterId("friend-a", "usagi") }
    }

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

    private fun viewModel(
        authState: MutableStateFlow<AuthState> = MutableStateFlow(AuthState.AUTHORIZED),
        // 默认模拟 App 未打包预存音频：assets 读取抛异常，试听走网络 TTS 链路
        context: Context = mockk(relaxed = true) {
            every { assets.open(any()) } throws FileNotFoundException("no bundled audition")
        },
        voiceRecognizer: com.tracktosearch.data.ai.AiKwsRecognizer = mockk(relaxed = true) {
            coEvery { recognize(any()) } returns com.tracktosearch.data.ai.AiVoiceMatch.NoMatch
        }
    ): AiSpriteViewModel {
        every { authManager.authState } returns authState
        every { authManager.nickname } returns MutableStateFlow("朋友")
        every { authManager.friendId } returns MutableStateFlow("friend-a")
        coEvery { aiRepository.listCharacters() } returns Result.success(emptyList())
        // 锐评隐私守卫默认放行：已同意说明弹窗且上传开关开启
        every { aiTasteStorage.tasteConsentDecided } returns flowOf(true)
        every { aiTasteStorage.tasteUploadEnabled } returns flowOf(true)
        return AiSpriteViewModel(aiRepository, authManager, traktRepository, overlayStorage, aiTasteStorage, voiceRecognizer, context)
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
