package com.tracktosearch.ui.screen.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.ai.AiActivateRequest
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiCharacterCatalog
import com.tracktosearch.data.ai.AiErrorCode
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizResult
import com.tracktosearch.data.ai.AiTasteAnalysis
import com.tracktosearch.data.ai.AiWatchedTitleDto
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiRepository
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

enum class AiFeature {
    GREETING,
    TASTE,
    QUIZ,
    DAILY
}

/** 出分页难度反馈提交状态：SUBMITTED 后不再重复发请求。 */
enum class AiQuizFeedbackState {
    NOT_SUBMITTED,
    SUBMITTED
}

enum class AiActivationState {
    IDLE,
    RECORDING,
    VERIFYING,
    SUCCESS,
    FAILED
}

private const val WATCHED_TITLES_TTL_MS = 10 * 60 * 1000L

data class AiSpriteUiState(
    val characters: List<AiCharacter> = AiCharacterCatalog.all,
    val selectedCharacterId: String = "usagi",
    val activatedCharacterId: String? = null,
    val authState: AuthState = AuthState.UNAUTHORIZED,
    val nickname: String? = null,
    val activationState: AiActivationState = AiActivationState.IDLE,
    val activationAttempt: Int = 0,
    // 文字兜底入口：语音失败或麦克风不可用后锁存为 true，换角色/撤销授权时复位
    val textActivationOffered: Boolean = false,
    val textActivationAttempt: Int = 0,
    val activationMessage: String? = null,
    val quota: com.tracktosearch.data.ai.AiQuota? = null,
    val isLoading: Boolean = false,
    val loadingFeature: AiFeature? = null,
    val activeFeature: AiFeature? = null,
    val greeting: AiGreeting? = null,
    val taste: AiTasteAnalysis? = null,
    // 口味分析成功加载次数，用于只触发一次对应场景图
    val tasteRevision: Long = 0L,
    val quiz: AiQuiz? = null,
    val quizPreviewMovies: List<AiWatchedTitleDto> = emptyList(),
    val quizReplacementCount: Int = 0,
    // 「换一部」是否还有没用过的候选：已看正好 7 部时候选会被用光，按钮必须跟着禁用
    val quizReplaceAvailable: Boolean = false,
    val quizStarted: Boolean = false,
    val quizIndex: Int = 0,
    val quizAnswers: Map<String, AiQuizAnswer> = emptyMap(),
    val quizResult: AiQuizResult? = null,
    // 答题结果成功提交次数，用于只触发一次对应场景图
    val quizResultRevision: Long = 0L,
    // 出分页难度反馈：选中档位与提交状态（SUBMITTED 后 UI 收起/禁用，不再重复提交）
    val quizFeedbackDifficulty: com.tracktosearch.data.ai.AiQuizDifficulty? = null,
    val quizFeedbackState: AiQuizFeedbackState = AiQuizFeedbackState.NOT_SUBMITTED,
    val quizHistory: com.tracktosearch.data.ai.AiQuizHistory? = null,
    val dailyKnowledge: AiDailyKnowledge? = null,
    // 角色目录是否成功取回：失败时全部角色停在「准备中」，UI 要给出原因和重试入口
    val charactersLoadFailed: Boolean = false,
    val errorCode: String? = null
) {
    val isAuthorized: Boolean
        get() = authState == AuthState.AUTHORIZED || authState == AuthState.OFFLINE

    val selectedCharacter: AiCharacter?
        get() = characters.firstOrNull { it.id == selectedCharacterId }

    val activatedCharacter: AiCharacter?
        get() = activatedCharacterId?.let { id -> characters.firstOrNull { it.id == id } }
}

@HiltViewModel
class AiSpriteViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val authManager: AuthManager,
    private val traktRepository: TraktRepository,
    private val overlayStorage: com.tracktosearch.data.local.AiSpriteOverlayStorage
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AiSpriteUiState(
            authState = authManager.authState.value,
            nickname = authManager.nickname.value
        )
    )
    val uiState: StateFlow<AiSpriteUiState> = _uiState.asStateFlow()

    private val _audioEvents = MutableSharedFlow<AiAudio>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioEvents: SharedFlow<AiAudio> = _audioEvents.asSharedFlow()

    private val _guestPreviewFallbackEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val guestPreviewFallbackEvents: SharedFlow<String> = _guestPreviewFallbackEvents.asSharedFlow()

    private var initialized = false
    private var authObserved = false
    private var activationRestored = false
    private var previewJob: Job? = null
    private var requestJob: Job? = null
    /** 请求代际用于隔离已取消请求的 finally，避免旧请求覆盖新请求的加载态。 */
    private var requestGeneration = 0L
    private var recentQuizIds = emptyList<String>()
    private var quizCandidates = emptyList<AiWatchedTitleDto>()
    /** 已看列表短期缓存（时间戳 to 列表），避免每次进功能页都全量重拉 Trakt 历史。 */
    private var watchedTitlesCache: Pair<Long, List<AiWatchedTitleDto>>? = null
    // 一次精灵中心会话共用一个会话 ID，让服务端会话配额按一次打开的精灵中心计算。
    // 若每次请求都发新 UUID，会话配额形同虚设，只剩每日上限。
    // ViewModel 现在挂在 Activity 作用域跨页面共享，不能再按实例生成——否则整个 App
    // 生命周期只有一个会话，会话配额到重启才重置。改由 onSpriteCenterOpened() 轮换。
    private var spriteSessionId = newSpriteSessionId()

    private fun newSpriteSessionId(): String = "sprite-${UUID.randomUUID()}"

    /**
     * 探头展示策略。
     *
     * 必须挂在共享 ViewModel 上：之前它是页面级 `remember`，导航离开再回来就重建，
     * 会话上限 3 次实际退化成「每次进页面 3 次」，两个搜索页还各算一份，
     * IDLE 的 90 秒冷却也跨页失效。
     */
    private val overlayPolicy = AiSpriteOverlayPolicy(
        readDailyCount = overlayStorage::readDailyCount,
        writeDailyCount = overlayStorage::writeDailyCount
    )

    /** 消费一次探头展示额度；未激活或额度用尽返回 false。 */
    fun tryConsumeOverlay(trigger: AiSpriteOverlayTrigger, dayKey: String): Boolean =
        overlayPolicy.tryConsume(isSpriteActivatedForMotion(), trigger, dayKey)

    /** 自动探头要求角色已激活且有对应素材，没素材的角色不参与探头。 */
    fun isSpriteActivatedForMotion(): Boolean =
        _uiState.value.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true

    /** 每次打开精灵中心换一个会话 ID，保持「一次打开 = 一个会话」的配额语义。 */
    fun onSpriteCenterOpened() {
        spriteSessionId = newSpriteSessionId()
    }

    fun ensureLoaded() {
        restoreActivation()
        if (initialized) {
            scheduleCharacterPreview()
            return
        }
        initialized = true
        viewModelScope.launch {
            loadCharacters()
            scheduleCharacterPreview()
            loadQuizHistory()
        }
    }

    /**
     * 恢复上次激活的角色，并开始跟随授权态。
     *
     * 与 [ensureLoaded] 分开：这条路径不拉角色目录也不播试听，
     * 供详情页这类没有激活入口、但要用激活态的页面直接调用，
     * 不会在详情页突然放出一段语音。
     */
    fun restoreActivation() {
        observeAuthState()
        if (activationRestored) return
        activationRestored = true
        viewModelScope.launch {
            val friendId = authManager.friendId.value.orEmpty()
            if (friendId.isBlank()) return@launch
            val restored = aiRepository.readActivatedCharacterId(friendId) ?: return@launch
            if (_uiState.value.characters.none { it.id == restored }) return@launch
            _uiState.update { state ->
                // 本次会话里已经激活过就不覆盖，避免落盘的旧值顶掉刚激活的角色
                if (state.activatedCharacterId != null) state
                else state.copy(
                    activatedCharacterId = restored,
                    selectedCharacterId = restored,
                    activationState = AiActivationState.SUCCESS
                )
            }
        }
    }

    private fun observeAuthState() {
        if (authObserved) return
        authObserved = true
        viewModelScope.launch {
            launch {
                var previousAuthState = authManager.authState.value
                authManager.authState.collect { authState ->
                    if (previousAuthState != AuthState.UNAUTHORIZED && authState == AuthState.UNAUTHORIZED) {
                        clearPrivateStateAfterUnauthorized()
                    }
                    previousAuthState = authState
                    _uiState.update {
                        it.copy(
                            authState = authState,
                            nickname = authManager.nickname.value
                        )
                    }
                }
            }
            launch {
                authManager.nickname.collect { nickname ->
                    _uiState.update { it.copy(nickname = nickname) }
                }
            }
        }
    }

    /** 撤销授权后丢弃当前账号的 AI 结果，但保留角色目录和游客试听能力。 */
    private fun clearPrivateStateAfterUnauthorized() {
        invalidateCurrentRequest()
        previewJob?.cancel()
        previewJob = null
        recentQuizIds = emptyList()
        quizCandidates = emptyList()
        watchedTitlesCache = null
        // 重新登录后要能再从落盘值恢复，所以放开这道闸
        activationRestored = false
        _uiState.update {
            it.copy(
                activatedCharacterId = null,
                activationState = AiActivationState.IDLE,
                activationAttempt = 0,
                textActivationOffered = false,
                textActivationAttempt = 0,
                activationMessage = null,
                quota = null,
                isLoading = false,
                loadingFeature = null,
                activeFeature = null,
                greeting = null,
                taste = null,
                tasteRevision = 0L,
                quiz = null,
                quizPreviewMovies = emptyList(),
                quizReplacementCount = 0,
                quizReplaceAvailable = false,
                quizStarted = false,
                quizIndex = 0,
                quizAnswers = emptyMap(),
                quizResult = null,
                quizResultRevision = 0L,
                quizFeedbackDifficulty = null,
                quizFeedbackState = AiQuizFeedbackState.NOT_SUBMITTED,
                quizHistory = null,
                dailyKnowledge = null,
                errorCode = null
            )
        }
    }

    private suspend fun loadQuizHistory() {
        val friendId = authManager.friendId.value.orEmpty()
        if (friendId.isBlank()) return
        aiRepository.readQuizHistory(friendId)?.let { history ->
            _uiState.update { it.copy(quizHistory = history) }
        }
    }

    fun selectCharacter(characterId: String) {
        if (!shouldResetActivationAttempt(_uiState.value.selectedCharacterId, characterId)) return
        if (_uiState.value.characters.none { it.id == characterId }) return
        _uiState.update {
            it.copy(
                selectedCharacterId = characterId,
                activationState = if (it.activatedCharacterId == characterId) AiActivationState.SUCCESS else AiActivationState.IDLE,
                activationMessage = null,
                errorCode = null,
                // 换角色重置失败尝试计数，避免某角色语音激活 5 次失败后永久锁死所有角色
                activationAttempt = 0,
                // 文字兜底入口也跟着角色重置：新角色还没试过语音，先别急着给兜底
                textActivationOffered = false,
                textActivationAttempt = 0
            )
        }
        scheduleCharacterPreview()
    }

    fun activate(context: Context) {
        val state = _uiState.value
        if (!state.isAuthorized) {
            setError("AUTH_REQUIRED")
            return
        }
        val character = state.selectedCharacter ?: return
        if (!character.isAvailable) {
            setError("CHARACTER_UNAVAILABLE")
            return
        }
        if (!canRequestVoiceActivation(state.activationAttempt)) {
            // 语音次数用满：不再录音，但把文字兜底开出来，别把用户彻底堵死
            _uiState.update { it.copy(textActivationOffered = true, errorCode = "ACTIVATION_RETRY_LIMIT") }
            return
        }
        if (!canActivateCharacter(character, state)) {
            setError("ACTIVATION_UNAVAILABLE")
            return
        }
        val attempt = state.activationAttempt + 1
        _uiState.update {
            it.copy(
                activationAttempt = attempt,
                activationState = AiActivationState.RECORDING,
                activationMessage = null,
                errorCode = null
            )
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val audioDataUrl = AiAudioRecorder.recordOnce(context)
            if (audioDataUrl.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        activationState = AiActivationState.FAILED,
                        textActivationOffered = true,
                        errorCode = "AUDIO_UNAVAILABLE"
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(activationState = AiActivationState.VERIFYING) }
            aiRepository.activate(
                friendId = authManager.friendId.value.orEmpty(),
                request = AiActivateRequest(
                    characterId = character.id,
                    audioDataUrl = audioDataUrl,
                    spokenName = character.activationWord,
                    sessionId = spriteSessionId
                )
            ).onSuccess { activation ->
                _uiState.update {
                    it.copy(
                        activatedCharacterId = if (activation.activated) character.id else it.activatedCharacterId,
                        activationState = if (activation.activated) AiActivationState.SUCCESS else AiActivationState.FAILED,
                        activationMessage = activation.activationPhrase.takeIf { activation.activated && it.isNotBlank() },
                        quota = activation.quota ?: it.quota,
                        // 语音没识别出来也算"走不通"，此时才把文字入口露出来
                        textActivationOffered = it.textActivationOffered || !activation.activated,
                        errorCode = if (activation.activated) null else "ACTIVATION_NOT_MATCHED"
                    )
                }
                activation.audio?.let { _audioEvents.emit(it) }
                if (activation.activated) {
                    // 落盘激活态：跨进程重启和详情页这类无激活入口的页面都要认这个角色
                    aiRepository.saveActivatedCharacterId(
                        authManager.friendId.value.orEmpty(),
                        character.id
                    )
                    loadGreeting(character.id)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        activationState = AiActivationState.FAILED,
                        textActivationOffered = true,
                        errorCode = errorCode(error)
                    )
                }
            }
        }
    }

    /** 麦克风权限被拒绝或设备没有录音源：语音这条路当场判死，直接把文字入口开出来。 */
    fun onVoiceActivationUnavailable() {
        _uiState.update {
            it.copy(
                activationState = AiActivationState.FAILED,
                textActivationOffered = true,
                errorCode = "AUDIO_UNAVAILABLE"
            )
        }
    }

    /**
     * 文字兜底激活：用用户手输的角色名提交，不再直接拿角色预设名蒙过去。
     *
     * 只在语音失败或麦克风不可用之后可用（textActivationOffered），与语音次数分开计数。
     */
    fun activateByText(spokenName: String) {
        val state = _uiState.value
        if (!state.isAuthorized) {
            setError("AUTH_REQUIRED")
            return
        }
        val character = state.selectedCharacter ?: return
        if (!character.isAvailable) {
            setError("CHARACTER_UNAVAILABLE")
            return
        }
        val typedName = normalizeSpokenName(spokenName)
        if (typedName.isEmpty()) {
            setError("ACTIVATION_NAME_EMPTY")
            return
        }
        if (!canRequestTextActivation(state.textActivationAttempt)) {
            setError("ACTIVATION_RETRY_LIMIT")
            return
        }
        if (!canActivateCharacterByText(character, state)) {
            setError("ACTIVATION_UNAVAILABLE")
            return
        }
        val attempt = state.textActivationAttempt + 1
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    textActivationAttempt = attempt,
                    activationState = AiActivationState.VERIFYING,
                    errorCode = null
                )
            }
            aiRepository.activate(
                authManager.friendId.value.orEmpty(),
                AiActivateRequest(
                    characterId = character.id,
                    spokenName = typedName,
                    sessionId = spriteSessionId
                )
            ).onSuccess { activation ->
                _uiState.update {
                    it.copy(
                        activatedCharacterId = if (activation.activated) character.id else it.activatedCharacterId,
                        activationState = if (activation.activated) AiActivationState.SUCCESS else AiActivationState.FAILED,
                        activationMessage = activation.activationPhrase.takeIf { activation.activated && it.isNotBlank() },
                        quota = activation.quota ?: it.quota,
                        errorCode = if (activation.activated) null else "ACTIVATION_NOT_MATCHED"
                    )
                }
                activation.audio?.let { _audioEvents.emit(it) }
                if (activation.activated) {
                    // 落盘激活态：跨进程重启和详情页这类无激活入口的页面都要认这个角色
                    aiRepository.saveActivatedCharacterId(
                        authManager.friendId.value.orEmpty(),
                        character.id
                    )
                    loadGreeting(character.id)
                }
            }.onFailure { error ->
                _uiState.update { it.copy(activationState = AiActivationState.FAILED, errorCode = errorCode(error)) }
            }
        }
    }

    fun openFeature(feature: AiFeature) {
        _uiState.update { it.copy(activeFeature = feature, errorCode = null) }
        when (feature) {
            AiFeature.GREETING -> loadGreeting(_uiState.value.activatedCharacterId ?: _uiState.value.selectedCharacterId)
            AiFeature.TASTE -> loadTaste()
            // 返回后重进不能把答到一半的一轮题冲掉；只有没有未完成的一轮时才重新抽题
            AiFeature.QUIZ -> if (!hasQuizInProgress(_uiState.value)) prepareQuizPreview()
            AiFeature.DAILY -> loadDaily()
        }
    }

    fun closeFeature() {
        invalidateCurrentRequest()
        _uiState.update {
            it.copy(
                isLoading = false,
                activeFeature = null,
                loadingFeature = null,
                errorCode = null,
                // 激活进行中被关闭：请求已取消，但 activationState 若仍卡在
                // RECORDING/VERIFYING，激活按钮会因 inFlight 判断永久禁用。
                // 关闭功能页时重置为 IDLE，下次进入可重新激活。
                activationState = if (
                    it.activationState == AiActivationState.RECORDING ||
                    it.activationState == AiActivationState.VERIFYING
                ) {
                    AiActivationState.IDLE
                } else {
                    it.activationState
                },
                activationMessage = null
            )
        }
    }

    /** 功能页刷新。答题页的刷新等于放弃这一轮，UI 必须先做二次确认再调用。 */
    fun refreshFeature() {
        when (_uiState.value.activeFeature) {
            AiFeature.GREETING -> loadGreeting(
                _uiState.value.activatedCharacterId ?: _uiState.value.selectedCharacterId,
                forceRefresh = true
            )
            AiFeature.TASTE -> loadTaste(forceRefresh = true)
            AiFeature.QUIZ -> replayQuiz()
            AiFeature.DAILY -> loadDaily(forceRefresh = true)
            null -> Unit
        }
    }

    /**
     * 取消当前进行中的 AI 请求（加载浮条上的「取消」按钮入口）。
     *
     * 请求已由 [requestJob] 管理：这里换请求代际并取消 Job，被取消协程的
     * CancellationException 走静默路径——不写 errorCode、不弹错误，
     * 再显式复位 isLoading/loadingFeature 收起浮条。与答题中「刷新=放弃」
     * 的二次确认互不影响：取消不动 quiz/quizAnswers 状态。
     */
    fun cancelActiveFeatureRequest() {
        beginRequest()
        _uiState.update { it.copy(isLoading = false, loadingFeature = null) }
    }

    fun setQuizAnswer(questionId: String, optionIds: List<String>, textAnswer: String? = null) {
        _uiState.update { state ->
            state.copy(
                quizAnswers = state.quizAnswers + (questionId to AiQuizAnswer(questionId, optionIds, textAnswer))
            )
        }
    }

    fun setQuizTextAnswer(questionId: String, textAnswer: String) {
        val current = _uiState.value.quizAnswers[questionId]
        setQuizAnswer(questionId, current?.selectedOptionIds.orEmpty(), textAnswer)
    }

    fun nextQuestion() {
        _uiState.update { state ->
            val lastIndex = (state.quiz?.questions?.size ?: 1) - 1
            state.copy(quizIndex = (state.quizIndex + 1).coerceAtMost(lastIndex))
        }
    }

    fun previousQuestion() {
        _uiState.update { it.copy(quizIndex = (it.quizIndex - 1).coerceAtLeast(0)) }
    }

    fun submitQuiz() {
        val state = _uiState.value
        val quiz = state.quiz?.takeIf { state.quizStarted } ?: return
        beginRequest()
        requestJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorCode = null) }
            aiRepository.submitQuiz(
                authManager.friendId.value.orEmpty(),
                quiz.quizId,
                state.quizAnswers.values.toList()
            ).onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        quizResult = result,
                        quizResultRevision = it.quizResultRevision + 1L,
                        quota = result.quota ?: it.quota
                    )
                }
                // 保存最近/最高成绩与错题（离线可浏览闯关历史）
                val friendId = authManager.friendId.value.orEmpty()
                if (friendId.isNotBlank()) {
                    aiRepository.saveQuizHistory(friendId, result)
                    aiRepository.readQuizHistory(friendId)?.let { history ->
                        _uiState.update { it.copy(quizHistory = history) }
                    }
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, errorCode = errorCode(error)) }
            }
        }
    }

    fun replayQuiz() {
        _uiState.update {
            it.copy(
                quiz = null,
                quizResult = null,
                quizStarted = false,
                quizIndex = 0,
                quizAnswers = emptyMap(),
                quizReplacementCount = 0,
                quizReplaceAvailable = false,
                quizPreviewMovies = emptyList(),
                quizFeedbackDifficulty = null,
                quizFeedbackState = AiQuizFeedbackState.NOT_SUBMITTED
            )
        }
        quizCandidates = emptyList()
        prepareQuizPreview()
    }

    /**
     * 出分页难度反馈：本地先锁 SUBMITTED 防重复，异步静默提交。
     * 失败在 Repository 层就被吞掉，这里不处理结果、不设 isLoading，不打扰用户。
     */
    fun submitQuizDifficultyFeedback(difficulty: com.tracktosearch.data.ai.AiQuizDifficulty) {
        val state = _uiState.value
        if (state.quizFeedbackState == AiQuizFeedbackState.SUBMITTED) return
        val quizId = state.quizResult?.quizId ?: state.quiz?.quizId ?: return
        _uiState.update {
            it.copy(
                quizFeedbackState = AiQuizFeedbackState.SUBMITTED,
                quizFeedbackDifficulty = difficulty
            )
        }
        viewModelScope.launch {
            aiRepository.submitQuizDifficulty(quizId, difficulty)
        }
    }

    /**
     * 预览页「全部重抽」：基于已缓存的候选池本地重新抽一批（尽量避开当前 7 部），
     * 不重新拉 Trakt 已看列表。非破坏性操作（还没作答），无需二次确认。
     */
    fun redrawAllQuizPreview() {
        val state = _uiState.value
        if (state.quizStarted) return
        if (quizCandidates.isEmpty()) {
            prepareQuizPreview()
            return
        }
        val preview = redrawQuizPreview(state.quizPreviewMovies, quizCandidates)
        if (preview == state.quizPreviewMovies) return
        _uiState.update {
            it.copy(
                quizPreviewMovies = preview,
                quizReplacementCount = 0,
                quizReplaceAvailable = canReplaceQuizPreview(preview, quizCandidates, 0)
            )
        }
    }

    fun startQuiz() {
        val preview = _uiState.value.quizPreviewMovies
        if (preview.size < 7) {
            setError("NOT_ENOUGH_MOVIES")
            return
        }
        loadQuiz(preview, forceRefresh = false)
    }

    fun replaceQuizMovie(index: Int) {
        val state = _uiState.value
        val next = replaceQuizPreview(
            current = state.quizPreviewMovies,
            candidates = quizCandidates,
            index = index,
            replacementCount = state.quizReplacementCount
        )
        if (next != state.quizPreviewMovies) {
            val nextReplacementCount = state.quizReplacementCount + 1
            _uiState.update {
                it.copy(
                    quizPreviewMovies = next,
                    quizReplacementCount = nextReplacementCount,
                    quizReplaceAvailable = canReplaceQuizPreview(next, quizCandidates, nextReplacementCount)
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorCode = null) }
    }

    /** 手动重播当前角色的试听，供试听文案旁的播放按钮使用。 */
    fun replaySelectedCharacter() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            previewSelectedCharacter()
        }
    }

    private suspend fun loadCharacters() {
        aiRepository.listCharacters().fold(
            onSuccess = { remote ->
                val remoteById = remote.associateBy { it.id }
                _uiState.update { state ->
                    state.copy(
                        characters = state.characters.map { local -> remoteById[local.id] ?: local },
                        charactersLoadFailed = false
                    )
                }
            },
            // 本地目录默认 isAvailable=false，取不到远端可用态就全是「准备中」，
            // 激活按钮会整片灰掉。必须显式标记失败，让 UI 给出原因和重试入口。
            onFailure = { _uiState.update { it.copy(charactersLoadFailed = true) } }
        )
    }

    /** 角色目录加载失败后的手动重试。 */
    fun reloadCharacters() {
        viewModelScope.launch {
            _uiState.update { it.copy(charactersLoadFailed = false, errorCode = null) }
            loadCharacters()
            if (_uiState.value.charactersLoadFailed) setError("CHARACTERS_LOAD_FAILED")
        }
    }

    /** 试听请求挂在本预览 Job 内执行：取消 previewJob 会一并取消 TTS，避免快速切换角色时旧请求后完成、播放上一个角色的声音。 */
    private suspend fun previewSelectedCharacter() {
        val character = _uiState.value.selectedCharacter ?: return
        val authorized = isAuthorized()
        val request = buildAuditionTtsRequest(character, spriteSessionId)
        when (auditionPlaybackRoute(authorized, character.isAvailable)) {
            AiAuditionPlaybackRoute.SYSTEM_TTS -> {
                emitGuestPreviewFallback(character)
            }
            AiAuditionPlaybackRoute.GUEST_TTS -> {
                aiRepository.playGuestTts(request).fold(
                    onSuccess = { audio ->
                        if (audio.hasPlayableSource()) _audioEvents.emit(audio)
                        else emitGuestPreviewFallback(character)
                    },
                    onFailure = { emitGuestPreviewFallback(character) }
                )
            }
            AiAuditionPlaybackRoute.AUTHORIZED_TTS -> {
                aiRepository.playTts(authManager.friendId.value.orEmpty(), request).fold(
                    onSuccess = { audio ->
                        if (audio.hasPlayableSource()) _audioEvents.emit(audio)
                        else emitGuestPreviewFallback(character)
                    },
                    onFailure = { emitGuestPreviewFallback(character) }
                )
            }
        }
    }

    private fun scheduleCharacterPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(350)
            previewSelectedCharacter()
        }
    }

    private suspend fun emitGuestPreviewFallback(character: AiCharacter) {
        character.auditionText.takeIf { it.isNotBlank() }?.let { text ->
            _guestPreviewFallbackEvents.emit(text)
        }
    }

    private fun AiAudio.hasPlayableSource(): Boolean =
        !audioDataUrl.isNullOrBlank() || !audioUrl.isNullOrBlank()

    private fun loadGreeting(characterId: String, forceRefresh: Boolean = false) {
        runFeature(AiFeature.GREETING) {
            aiRepository.getGreeting(authManager.friendId.value.orEmpty(), characterId, forceRefresh)
                .onSuccess { greeting ->
                    _uiState.update { it.copy(greeting = greeting, quota = greeting.quota ?: it.quota) }
                    greeting.audio?.let { _audioEvents.emit(it) }
                }
                .getOrElse { throw it }
        }
    }

    private fun loadTaste(forceRefresh: Boolean = false) {
        runFeature(AiFeature.TASTE) {
            val watched = watchedTitles()
            if (watched.isEmpty()) throw IllegalStateException("WATCHED_LIST_EMPTY")
            aiRepository.getTaste(
                authManager.friendId.value.orEmpty(),
                com.tracktosearch.data.ai.AiTasteRequest(watched = watched, forceRefresh = forceRefresh),
                forceRefresh
            ).onSuccess { taste ->
                _uiState.update {
                    it.copy(
                        taste = taste,
                        tasteRevision = it.tasteRevision + 1L,
                        quota = taste.quota ?: it.quota
                    )
                }
            }
                .getOrElse { throw it }
        }
    }

    private fun prepareQuizPreview() {
        runFeature(AiFeature.QUIZ) {
            val watched = watchedTitles()
            if (watched.size < 7) throw IllegalStateException("NOT_ENOUGH_MOVIES")
            quizCandidates = watched
            val preview = selectQuizPreview(watched)
            _uiState.update {
                it.copy(
                    quiz = null,
                    quizResult = null,
                    quizStarted = false,
                    quizIndex = 0,
                    quizAnswers = emptyMap(),
                    quizPreviewMovies = preview,
                    quizReplacementCount = 0,
                    quizReplaceAvailable = canReplaceQuizPreview(preview, watched, 0)
                )
            }
        }
    }

    private fun loadQuiz(watched: List<AiWatchedTitleDto>, forceRefresh: Boolean) {
        runFeature(AiFeature.QUIZ) {
            if (watched.size < 7) throw IllegalStateException("NOT_ENOUGH_MOVIES")
            aiRepository.getQuiz(
                authManager.friendId.value.orEmpty(),
                com.tracktosearch.data.ai.AiQuizRequest(
                    watched = watched,
                    excludedQuizIds = recentQuizIds,
                    questionCount = 13,
                    sessionId = spriteSessionId
                ),
                forceRefresh
            ).onSuccess { quiz ->
                recentQuizIds = (listOf(quiz.quizId) + recentQuizIds).take(3)
                _uiState.update {
                    it.copy(
                        quiz = quiz,
                        quizStarted = true,
                        quizIndex = 0,
                        quizAnswers = emptyMap(),
                        quizResult = null,
                        quota = quiz.quota ?: it.quota
                    )
                }
            }.getOrElse { throw it }
        }
    }

    private fun loadDaily(forceRefresh: Boolean = false) {
        runFeature(AiFeature.DAILY) {
            aiRepository.getDailyKnowledge(authManager.friendId.value.orEmpty(), forceRefresh)
                .onSuccess { daily ->
                    _uiState.update {
                        it.copy(dailyKnowledge = daily, quota = daily.quota ?: it.quota)
                    }
                }
                .getOrElse { throw it }
        }
    }

    private fun runFeature(feature: AiFeature, block: suspend () -> Unit) {
        if (!isAuthorized()) {
            setError("AUTH_REQUIRED")
            return
        }
        val requestId = beginRequest()
        requestJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingFeature = feature, errorCode = null) }
            try {
                block()
            } catch (e: CancellationException) {
                // 新请求（切换功能/提交/激活）取消旧请求时，不能走 onFailure 画假错误或翻转状态
                throw e
            } catch (e: Exception) {
                if (requestGeneration == requestId) {
                    _uiState.update { it.copy(errorCode = errorCode(e)) }
                }
            } finally {
                if (requestGeneration == requestId) {
                    _uiState.update { it.copy(isLoading = false, loadingFeature = null) }
                }
            }
        }
    }

    private fun beginRequest(): Long {
        invalidateCurrentRequest()
        return requestGeneration
    }

    private fun invalidateCurrentRequest() {
        requestGeneration += 1L
        requestJob?.cancel()
        requestJob = null
    }

    private suspend fun watchedTitles(): List<AiWatchedTitleDto> {
        // 「锐评」「闯关」每次进入都全量拉 Trakt 已看历史，导致每次都转圈。
        // 已看列表短时间内基本不变，缓存 10 分钟即可，符合缓存优先原则。
        watchedTitlesCache?.let { cached ->
            if (System.currentTimeMillis() - cached.first < WATCHED_TITLES_TTL_MS) return cached.second
        }
        val loaded = withContext(Dispatchers.IO) {
            val results = listOf(
                async { traktRepository.getAllMovieHistory(extended = "full") },
                async { traktRepository.getAllShowHistory(extended = "full") }
            ).awaitAll()
            val movies = (results[0].getOrNull() as? List<TraktWatchlistMovieItem>).orEmpty().map { it.toAiWatched() }
            val shows = (results[1].getOrNull() as? List<TraktWatchlistShowItem>).orEmpty().map { it.toAiWatched() }
            (movies + shows).sortedByDescending { it.watchedAt.orEmpty() }.take(60)
        }
        if (loaded.isNotEmpty()) watchedTitlesCache = System.currentTimeMillis() to loaded
        return loaded
    }

    private fun isAuthorized(): Boolean =
        _uiState.value.isAuthorized && !authManager.friendId.value.isNullOrBlank()

    private fun setError(code: String) {
        _uiState.update { it.copy(errorCode = code) }
    }

    private fun errorCode(error: Throwable): String {
        val aiError = error as? com.tracktosearch.data.ai.AiApiException
        return aiError?.errorCode?.name ?: error.message?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
    }
}

private fun TraktWatchlistMovieItem.toAiWatched(): AiWatchedTitleDto = AiWatchedTitleDto(
    mediaId = movie.ids.trakt.takeIf { it > 0 }?.toString() ?: movie.ids.tmdb.toString(),
    mediaType = "movie",
    title = movie.title,
    year = movie.year.takeIf { it > 0 },
    genres = movie.genres,
    publicRating = movie.rating,
    watchedAt = watched_at.takeIf { it.isNotBlank() },
    mediaIds = AiMediaIdsDto(
        traktId = movie.ids.trakt.takeIf { it > 0 }?.toString(),
        tmdbId = movie.ids.tmdb.takeIf { it > 0 },
        imdbId = movie.ids.imdb.takeIf { it.isNotBlank() }
    )
)

private fun TraktWatchlistShowItem.toAiWatched(): AiWatchedTitleDto = AiWatchedTitleDto(
    mediaId = show.ids.trakt.takeIf { it > 0 }?.toString() ?: show.ids.tmdb.toString(),
    mediaType = "show",
    title = show.title,
    year = show.year.takeIf { it > 0 },
    genres = show.genres,
    publicRating = show.rating,
    watchedAt = watched_at.takeIf { it.isNotBlank() },
    mediaIds = AiMediaIdsDto(
        traktId = show.ids.trakt.takeIf { it > 0 }?.toString(),
        tmdbId = show.ids.tmdb.takeIf { it > 0 },
        imdbId = show.ids.imdb.takeIf { it.isNotBlank() }
    )
)
