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
import com.tracktosearch.data.ai.AiVoiceCapture
import com.tracktosearch.data.ai.AiVoiceCaptureEvent
import com.tracktosearch.data.ai.AiWatchedTitleDto
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiRepository
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.util.ConnectivityObserver
import com.tracktosearch.di.DispatcherModule
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named

internal const val AI_OFFLINE_ERROR_CODE = "OFFLINE"

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

/**
 * 松手后继续采集的尾音宽限窗口。
 *
 * KWS 配的是 numTrailingBlanks = 2：关键词说完之后还要约 80–150 ms 的后续音频才会确认命中。
 * 手指一抬就取消采集，说完名字立刻松手的用户根本给不出这段尾音，明明喊对了却收到
 * ACTIVATION_NOT_MATCHED，还白烧一次机会——而「说完就松手」恰恰是最自然的操作。
 * 250 ms 足够覆盖那 80–150 ms；此时用户已经停口，进来的正是 KWS 要的近似静音帧。
 */
private const val VOICE_HOLD_TAIL_GRACE_MS = 250L

/**
 * 按住计时步长。
 *
 * 按住时长必须用 delay 累加而不是只读墙钟：本 ViewModel 由 Hilt 构造，
 * 没法额外注入一个可替换的时钟（构造参数的默认值 Dagger 不认），
 * 而 delay 在单测里跟虚拟时间走，500 ms 下限与宽限窗口不算时长这两条才断言得出来。
 * 50 ms 一步，对 500 ms 的下限判定精度足够。
 */
private const val VOICE_HOLD_CLOCK_TICK_MS = 50L

data class AiSpriteUiState(
    val characters: List<AiCharacter> = AiCharacterCatalog.all,
    val selectedCharacterId: String = "usagi",
    val activatedCharacterId: String? = null,
    val authState: AuthState = AuthState.UNAUTHORIZED,
    val networkStatus: ConnectivityObserver.NetworkStatus = ConnectivityObserver.NetworkStatus.ONLINE,
    val nickname: String? = null,
    val activationState: AiActivationState = AiActivationState.IDLE,
    val activationAttempt: Int = 0,
    // 平滑后的电平 0..1：只喂按住时的电平带与呼吸光环，任何判定都不看它（判定用未平滑的峰值）
    val voiceLevel: Float = 0f,
    // 手指是否已拖出按钮范围：只换按钮文案（松手激活 / 松手取消），不影响采集
    val voiceCancelArmed: Boolean = false,
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
    val quizPreviewLocalizedTitles: Map<String, String> = emptyMap(),
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
    val errorCode: String? = null,
    // 「锐评我的看单」首次使用说明弹窗：true 时弹出（同意后才上传数据）
    val showTasteConsent: Boolean = false,
    // 锐评功能被设置页开关关闭时的引导弹窗：true 时弹出「去设置」
    val showTasteDisabled: Boolean = false
) {
    val isAuthorized: Boolean
        get() = authState == AuthState.AUTHORIZED || authState == AuthState.OFFLINE

    /** 只有实时授权态才能访问 AI 网关；OFFLINE 仅代表本地缓存仍可浏览。 */
    val hasLiveAiAuthorization: Boolean
        get() = authState == AuthState.AUTHORIZED

    val isAiAvailable: Boolean
        get() = hasLiveAiAuthorization && networkStatus == ConnectivityObserver.NetworkStatus.ONLINE

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
    private val tmdbRepository: TmdbRepository,
    private val connectivityObserver: ConnectivityObserver,
    private val languageStorage: LanguageStorage,
    @Named(DispatcherModule.IO_DISPATCHER) private val ioDispatcher: CoroutineDispatcher,
    private val overlayStorage: com.tracktosearch.data.local.AiSpriteOverlayStorage,
    private val aiTasteStorage: com.tracktosearch.data.local.AiTasteStorage,
    private val voiceCapture: AiVoiceCapture,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AiSpriteUiState(
            authState = authManager.authState.value,
            networkStatus = connectivityObserver.status.value,
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
    /** 按住采集 Job：取消它才会释放麦克风与 KWS 解码锁，每条收尾路径都必须取消。 */
    private var voiceCaptureJob: Job? = null
    /** 松手后的尾音宽限计时 Job，见 VOICE_HOLD_TAIL_GRACE_MS。 */
    private var voiceTailJob: Job? = null
    /**
     * 本次按住是否已经收尾。
     *
     * 一次按住只允许收尾一次，且只有活着的按住才允许改状态。命中会自行收尾进 VERIFYING
     * 直到 SUCCESS，此时 UI 会换掉激活面板并连带取消手势协程，手势层的 finally 会补发一次
     * onActivatePressCancel()——那次必须是空操作，否则刚激活成功的角色和问候气泡会被抹回 IDLE。
     * 10 秒上限自行收尾后迟到的真实松手同理。初值 true 表示「当前没有按住」，
     * 因此没按过就来的松手/取消一律无视；每次 onActivatePressStart() 重新置 false。
     */
    private var voiceHoldFinished = true
    /** 按住起点（单调纳秒）：真机上以它为准。 */
    private var voiceHoldStartedAtNanos = 0L
    /** delay 累加出的按住时长：单测里以它为准，见 VOICE_HOLD_CLOCK_TICK_MS。 */
    private var voiceTickedHoldMs = 0L
    /** 松手瞬间的时长快照，非空即已进入尾音宽限窗口；宽限窗口不算进按住时长。 */
    private var voiceReleasedHoldMs: Long? = null
    /** 本次按住的归一化电平峰值，取未平滑值：平滑的下降斜坡会把峰值拖低，喊够响也会被判太轻。 */
    private var voicePeakLevel = 0f
    /** 本次按住最后一次命中的角色 ID；喊成别的角色也要带到收尾判定里，不能当没命中。 */
    private var voiceMatchedCharacterId: String? = null
    private var voiceCaptureUnavailable = false
    /** 请求代际用于隔离已取消请求的 finally，避免旧请求覆盖新请求的加载态。 */
    private var requestGeneration = 0L
    private var recentQuizIds = emptyList<String>()
    private var quizCandidates = emptyList<AiWatchedTitleDto>()
    /** 当前问答预览的 TMDB 本地化标题补全任务；替换预览时取消旧任务，避免旧结果覆盖新列表。 */
    private var quizLocalizationJob: Job? = null
    /** 授权变化时取消本地私有状态恢复，避免迟到结果复活旧账号数据。 */
    private var activationRestoreJob: Job? = null
    private var quizHistoryJob: Job? = null
    private var privateStateGeneration = 0L
    /** 锐评隐私守卫任务；关闭功能页或切换功能时必须取消。 */
    private var tasteGuardJob: Job? = null
    private var tasteGuardGeneration = 0L
    /** 已看列表短期缓存（时间戳 to 列表），避免每次进功能页都全量重拉 Trakt 历史。 */
    private var watchedTitlesCache: Pair<Long, List<AiWatchedTitleDto>>? = null
    // 一次精灵中心会话共用一个会话 ID，让服务端会话配额按一次打开的精灵中心计算。
    // 若每次请求都发新 UUID，会话配额形同虚设，只剩每日上限。
    // ViewModel 现在挂在 Activity 作用域跨页面共享，不能再按实例生成——否则整个 App
    // 生命周期只有一个会话，会话配额到重启才重置。改由 onSpriteCenterOpened() 轮换。
    private var spriteSessionId = newSpriteSessionId()

    init {
        observeConnectivityState()
    }

    /**
     * AI 请求必须同时满足授权和实时在线；AuthState.OFFLINE 只代表本地缓存可用，
     * 不能把它当成可以访问网关的状态。网络切换时主动取消正在进行的请求，避免离线
     * 等待超时后再弹一个与实际原因不符的服务器错误。
     */
    private fun observeConnectivityState() {
        viewModelScope.launch {
            var previous = _uiState.value.networkStatus
            connectivityObserver.status.collect { status ->
                _uiState.update { it.copy(networkStatus = status) }
                when {
                    status == ConnectivityObserver.NetworkStatus.OFFLINE -> {
                        enterOfflineState()
                    }
                    previous == ConnectivityObserver.NetworkStatus.OFFLINE &&
                        status == ConnectivityObserver.NetworkStatus.ONLINE -> {
                        _uiState.update { state ->
                            if (state.errorCode == AI_OFFLINE_ERROR_CODE) {
                                state.copy(errorCode = null)
                            } else {
                                state
                            }
                        }
                        // 目录请求也不能在离线时发出；恢复网络后补一次即可。
                        if (initialized) {
                            launch { loadCharacters() }
                        }
                    }
                }
                previous = status
            }
        }
    }

    private fun enterOfflineState() {
        invalidateCurrentRequest()
        discardVoiceHold()
        previewJob?.cancel()
        previewJob = null
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                loadingFeature = null,
                activationState = if (
                    state.activationState == AiActivationState.RECORDING ||
                    state.activationState == AiActivationState.VERIFYING
                ) {
                    AiActivationState.IDLE
                } else {
                    state.activationState
                },
                voiceLevel = 0f,
                voiceCancelArmed = false,
                // 隐私守卫弹窗也是模态层，断网后必须一起收起，否则会遮住功能页的离线文案。
                showTasteConsent = false,
                showTasteDisabled = false,
                errorCode = AI_OFFLINE_ERROR_CODE
            )
        }
    }

    private fun isNetworkAvailable(): Boolean =
        // 请求回调不能依赖 collect 尚未同步完成的 uiState 快照，直接读取实时网络源。
        connectivityObserver.status.value == ConnectivityObserver.NetworkStatus.ONLINE

    private fun requireNetworkForAi(): Boolean {
        if (isNetworkAvailable()) return true
        setError(AI_OFFLINE_ERROR_CODE)
        return false
    }

    /** 受保护的 AI 请求只接受实时 AUTHORIZED；OFFLINE 仅可继续浏览本地缓存。 */
    private fun hasLiveAiSession(): Boolean =
        authManager.authState.value == AuthState.AUTHORIZED &&
            !authManager.friendId.value.isNullOrBlank()

    /** 网络状态优先：真正离线时必须展示离线文案，而不是误报需要重新授权。 */
    private fun requireAiAccess(): Boolean {
        if (!requireNetworkForAi()) return false
        if (hasLiveAiSession()) return true
        setError("AUTH_REQUIRED")
        return false
    }

    /**
     * AI 请求在每次挂起返回后都要重新过闸：取消 Job 不保证非协作式实现立即停止，
     * 迟到回调必须同时满足「仍是当前请求、实时在线、授权仍有效」才能写回状态。
     */
    private fun canContinueAiRequest(requestId: Long): Boolean {
        if (requestGeneration != requestId) return false
        if (!isNetworkAvailable()) {
            setRequestError(requestId, AI_OFFLINE_ERROR_CODE)
            return false
        }
        if (!hasLiveAiSession()) {
            setRequestError(requestId, "AUTH_REQUIRED")
            return false
        }
        return true
    }

    /** 只允许当前请求写 UI，旧请求的迟到回调直接丢弃。 */
    private fun updateIfCurrentRequest(
        requestId: Long,
        transform: (AiSpriteUiState) -> AiSpriteUiState
    ) {
        _uiState.update { state ->
            if (requestGeneration == requestId) transform(state) else state
        }
    }

    /** 错误回调统一先判实时网络，避免离线时把 SERVER/NETWORK 送进 Overlay。 */
    private fun requestErrorCode(error: Throwable): String =
        if (!isNetworkAvailable()) AI_OFFLINE_ERROR_CODE else errorCode(error)

    private fun setRequestError(requestId: Long, code: String) {
        updateIfCurrentRequest(requestId) { state ->
            state.copy(
                errorCode = if (!isNetworkAvailable()) AI_OFFLINE_ERROR_CODE else code
            )
        }
    }

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
        // 恢复落盘激活角色后要播试听：恢复是异步的，竞态上可能晚于
        // ensureLoaded 末尾那次 scheduleCharacterPreview，谁后完成谁负责触发
        restoreActivation(previewOnRestore = true)
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
     * 不会在详情页突然放出一段语音。[previewOnRestore] 供精灵中心进入时传 true：
     * 恢复出的角色选中后 UI 会进入试听 LOADING 态，不补触发就会干转 8 秒超时。
     */
    fun restoreActivation(previewOnRestore: Boolean = false) {
        observeAuthState()
        if (activationRestored) return
        activationRestored = true
        activationRestoreJob?.cancel()
        val generation = privateStateGeneration
        activationRestoreJob = viewModelScope.launch {
            val friendId = authManager.friendId.value.orEmpty()
            if (friendId.isBlank()) return@launch
            val restored = aiRepository.readActivatedCharacterId(friendId) ?: return@launch
            if (!canReadPrivateState(friendId, generation)) return@launch
            if (_uiState.value.characters.none { it.id == restored }) return@launch
            var restoredSelected = false
            _uiState.update { state ->
                // 本次会话里已经激活过就不覆盖，避免落盘的旧值顶掉刚激活的角色
                if (state.activatedCharacterId != null) state
                else {
                    restoredSelected = true
                    state.copy(
                        activatedCharacterId = restored,
                        selectedCharacterId = restored,
                        activationState = AiActivationState.SUCCESS
                    )
                }
            }
            if (previewOnRestore && restoredSelected && canReadPrivateState(friendId, generation)) {
                scheduleCharacterPreview()
            }
        }
    }

    private fun observeAuthState() {
        if (authObserved) return
        authObserved = true
        viewModelScope.launch {
            launch {
                var previousAuthState: AuthState? = null
                authManager.authState.collect { authState ->
                    if (authState == AuthState.UNAUTHORIZED && previousAuthState != AuthState.UNAUTHORIZED) {
                        clearPrivateStateAfterUnauthorized()
                    } else if (
                        previousAuthState == AuthState.AUTHORIZED &&
                        authState != AuthState.AUTHORIZED
                    ) {
                        cancelAiWorkForAuthLoss()
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

    /**
     * 授权从实时有效态变为离线/失效时，停止所有需要 Bearer 的工作。
     * OFFLINE 仍可浏览本地缓存，但不能继续把旧请求的结果写进当前页面。
     */
    private fun cancelAiWorkForAuthLoss() {
        invalidateCurrentRequest()
        cancelPrivateStateLoads()
        cancelTasteGuard()
        discardVoiceHold()
        previewJob?.cancel()
        previewJob = null
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                loadingFeature = null,
                activationState = if (
                    state.activationState == AiActivationState.RECORDING ||
                    state.activationState == AiActivationState.VERIFYING
                ) {
                    AiActivationState.IDLE
                } else {
                    state.activationState
                },
                voiceLevel = 0f,
                voiceCancelArmed = false,
                errorCode = when {
                    !isNetworkAvailable() -> AI_OFFLINE_ERROR_CODE
                    state.activeFeature != null -> "AUTH_REQUIRED"
                    else -> null
                }
            )
        }
    }

    /** 撤销授权后丢弃当前账号的 AI 结果，但保留角色目录和游客试听能力。 */
    private fun clearPrivateStateAfterUnauthorized() {
        invalidateCurrentRequest()
        cancelPrivateStateLoads()
        cancelTasteGuard()
        // 正在按住时被撤销授权：麦克风必须当场还回去，不能留着录一段谁也不会用的音频
        discardVoiceHold()
        previewJob?.cancel()
        previewJob = null
        recentQuizIds = emptyList()
        quizCandidates = emptyList()
        quizLocalizationJob?.cancel()
        quizLocalizationJob = null
        watchedTitlesCache = null
        // 重新登录后要能再从落盘值恢复，所以放开这道闸
        activationRestored = false
        _uiState.update {
            it.copy(
                activatedCharacterId = null,
                activationState = AiActivationState.IDLE,
                activationAttempt = 0,
                voiceLevel = 0f,
                voiceCancelArmed = false,
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
                quizPreviewLocalizedTitles = emptyMap(),
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

    private fun loadQuizHistory() {
        quizHistoryJob?.cancel()
        val generation = privateStateGeneration
        quizHistoryJob = viewModelScope.launch {
            val friendId = authManager.friendId.value.orEmpty()
            if (friendId.isBlank()) return@launch
            val history = aiRepository.readQuizHistory(friendId) ?: return@launch
            if (canReadPrivateState(friendId, generation)) {
                _uiState.update { it.copy(quizHistory = history) }
            }
        }
    }

    private fun canReadPrivateState(friendId: String, generation: Long): Boolean =
        generation == privateStateGeneration &&
            authManager.friendId.value == friendId &&
            (authManager.authState.value == AuthState.AUTHORIZED ||
                authManager.authState.value == AuthState.OFFLINE)

    private fun cancelPrivateStateLoads() {
        privateStateGeneration += 1L
        activationRestoreJob?.cancel()
        activationRestoreJob = null
        quizHistoryJob?.cancel()
        quizHistoryJob = null
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
                // 文字次数同样按角色重置：两条路各 5 次，互不影响
                textActivationAttempt = 0
            )
        }
        scheduleCharacterPreview()
    }

    /**
     * 按下主按钮：进 RECORDING 并开始采集，此时不计次数。
     *
     * 计数点从「按下」挪到「实际提交」（见 [finishVoiceHold]）：误触碰一下按钮不该烧掉
     * 用户 5 次机会里的一次。开录前的四道闸与旧的一次性激活完全一致，一条都不放。
     */
    fun onActivatePressStart() {
        val state = _uiState.value
        if (!requireAiAccess()) return
        val character = state.selectedCharacter ?: return
        if (!character.isAvailable) {
            setError("CHARACTER_UNAVAILABLE")
            return
        }
        if (!canRequestVoiceActivation(state.activationAttempt)) {
            // 语音次数用满：不再开录。文字入口已改成常驻，这里只需说明为什么按不动
            setError("ACTIVATION_RETRY_LIMIT")
            return
        }
        if (!canActivateCharacter(character, state)) {
            setError("ACTIVATION_UNAVAILABLE")
            return
        }
        // 上一段按住的残留（尾音宽限窗口里又按下来）先整段丢掉，再开新的一段
        discardVoiceHold()
        voiceHoldFinished = false
        voiceHoldStartedAtNanos = System.nanoTime()
        voiceTickedHoldMs = 0L
        voiceReleasedHoldMs = null
        voicePeakLevel = 0f
        voiceMatchedCharacterId = null
        voiceCaptureUnavailable = false
        _uiState.update {
            it.copy(
                activationState = AiActivationState.RECORDING,
                activationMessage = null,
                voiceLevel = 0f,
                voiceCancelArmed = false,
                errorCode = null
            )
        }
        voiceCaptureJob = viewModelScope.launch {
            // 计时与采集同生共死：取消采集 job 会一并停掉计时，不会留个协程空转到 10 秒
            launch { runVoiceHoldClock() }
            try {
                voiceCapture.capture().collect { event -> onVoiceCaptureEvent(event) }
            } catch (e: CancellationException) {
                // 收尾/取消都靠取消协程，取消异常必须原样往上走，不能被当成录音失败
                throw e
            } catch (_: Exception) {
                // 采集实现自己已经把设备异常收敛成 Unavailable，这里只兜漏出来的那些：
                // viewModelScope 里未捕获的异常会直接崩掉进程，一次按住不该带走整个 App
                onVoiceCaptureEvent(AiVoiceCaptureEvent.Unavailable)
            }
        }
    }

    /**
     * 正常松手：进尾音宽限窗口，窗口结束后按 [resolveVoiceHoldOutcome] 收尾。
     *
     * 没有活着的按住时是空操作（判定见 [scheduleVoiceHoldFinish]）：命中和 10 秒上限
     * 都会自行收尾，之后迟到的这次松手不能再改一遍状态、更不能再烧一次次数。
     */
    fun onActivatePressEnd() {
        scheduleVoiceHoldFinish()
    }

    /**
     * 上滑取消：立刻停采集回 IDLE，不计次数不报错。
     *
     * 取消掉的按住整段作废，没有尾音要补，所以不给宽限窗口。
     * 没有活着的按住时同样是空操作：命中自行收尾进 SUCCESS 后 UI 会换掉激活面板，
     * 手势协程被连带取消，其 finally 会补发一次取消；那时把状态推回 IDLE
     * 会把刚激活成功的角色和问候气泡一起抹掉。按下时被闸门挡住（次数用满等）也是同理，
     * 手势层照样补一次取消，不能顺手把刚设的错误码清掉，那样用户就看不到按不动的原因了。
     */
    fun onActivatePressCancel() {
        if (voiceHoldFinished) return
        discardVoiceHold()
        _uiState.update {
            it.copy(
                activationState = AiActivationState.IDLE,
                voiceLevel = 0f,
                voiceCancelArmed = false,
                errorCode = null
            )
        }
    }

    /** 手指是否已拖出按钮范围：只驱动按钮文案，不动采集也不动次数。 */
    fun onVoiceCancelArmedChanged(armed: Boolean) {
        if (_uiState.value.voiceCancelArmed == armed) return
        _uiState.update { it.copy(voiceCancelArmed = armed) }
    }

    /**
     * 麦克风权限刚授予：只置提示码，绝不自动开录。
     *
     * 权限弹窗一消失就自己开录的话，用户刚点完「允许」手指早已离开按钮，
     * 录进去的是一段空白，还要为此烧掉一次机会。让用户自己再按一次。
     */
    fun onVoicePermissionGranted() {
        setError("AUDIO_PERMISSION_GRANTED")
    }

    /** 丢弃当前按住：停采集、停宽限计时，不写任何状态，供取消/撤销授权/开新一段复用。 */
    private fun discardVoiceHold() {
        voiceHoldFinished = true
        // 泄漏的采集 job 同时占着麦克风和 KWS 解码锁：麦克风不还，下一次按住拿不到设备；
        // 解码锁不还，之后每一次 openSession() 都会永久挂起
        voiceCaptureJob?.cancel()
        voiceCaptureJob = null
        // 从宽限计时协程内部调回来时，这里取消的是它自己，没有问题：
        // 本函数不挂起，剩下的语句照常执行到底
        voiceTailJob?.cancel()
        voiceTailJob = null
        voiceReleasedHoldMs = null
    }

    /** 当前按住已持续多久：真机以单调墙钟为准，虚拟时间里以 delay 累加值为准，两者都只会低估。 */
    private fun currentVoiceHoldMs(): Long =
        maxOf(voiceTickedHoldMs, (System.nanoTime() - voiceHoldStartedAtNanos) / 1_000_000L)

    /**
     * 按住计时兼 10 秒上限。
     *
     * 计时为什么用 delay 累加见 VOICE_HOLD_CLOCK_TICK_MS。到上限走与正常松手完全相同的
     * 收尾路径（含尾音宽限窗口）：按到 10 秒的用户通常还在说，同样得有那段尾音才确认得了命中。
     */
    private suspend fun runVoiceHoldClock() {
        while (voiceTickedHoldMs < VOICE_HOLD_MAX_DURATION_MS) {
            delay(VOICE_HOLD_CLOCK_TICK_MS)
            voiceTickedHoldMs += VOICE_HOLD_CLOCK_TICK_MS
        }
        scheduleVoiceHoldFinish()
    }

    /** 采集事件落地：电平只喂显示与峰值，命中和设备不可用直接决定收尾时机。 */
    private fun onVoiceCaptureEvent(event: AiVoiceCaptureEvent) {
        when (event) {
            is AiVoiceCaptureEvent.Level -> {
                // 采集层报的是原始线性 RMS，而 VOICE_SILENCE_LEVEL / VOICE_TOO_QUIET_LEVEL
                // 是归一化 0..1 上的阈值：直接拿 RMS 去比，正常说话（RMS 0.04 约 -28 dBFS）
                // 会被判成「没听到声音」，用户明明喊了却被要求去检查麦克风
                val normalized = normalizeVoiceLevel(event.level)
                if (normalized > voicePeakLevel) voicePeakLevel = normalized
                _uiState.update { it.copy(voiceLevel = smoothVoiceLevel(it.voiceLevel, normalized)) }
            }
            is AiVoiceCaptureEvent.Matched -> {
                // 同一段按住里用户可能把名字喊两遍：流每次命中后 reset，第二遍还会再报一次。
                // 第一遍即终局，重复那次会被 finishVoiceHold 的已收尾标记挡掉
                voiceMatchedCharacterId = event.characterId
                // 喊中所选角色立刻收尾不等松手；喊的是别人则继续录，
                // 由收尾判定按电平决定落 NOT_MATCHED 还是 TOO_QUIET
                if (event.characterId == _uiState.value.selectedCharacterId) finishVoiceHold()
            }
            AiVoiceCaptureEvent.Unavailable -> {
                voiceCaptureUnavailable = true
                finishVoiceHold()
            }
        }
    }

    /**
     * 排程收尾：松手后不立刻停采集，再多收一段尾音（原因见 VOICE_HOLD_TAIL_GRACE_MS）。
     *
     * 时长快照在这里取：宽限窗口是我们自己多录的，不能算进按住时长，
     * 否则 400 ms 的误触会被凑成 650 ms，绕过 500 ms 下限白烧一次机会。
     * 已经收尾或已经在宽限窗口里都不再排程，一段按住只收尾一次。
     */
    private fun scheduleVoiceHoldFinish() {
        if (voiceHoldFinished || voiceReleasedHoldMs != null) return
        voiceReleasedHoldMs = currentVoiceHoldMs()
        voiceTailJob = viewModelScope.launch {
            delay(VOICE_HOLD_TAIL_GRACE_MS)
            finishVoiceHold()
        }
    }

    /**
     * 一次按住的终局：算出 outcome，命中走服务端激活，其余落错误码。
     *
     * 命中、设备不可用、松手宽限到点、10 秒上限四条路都汇到这里，谁先到谁作数，
     * 晚到的被 voiceHoldFinished 挡成空操作——宽限窗口里迟到的命中因此仍然算成功，
     * 而排在它后面的那次宽限收尾不会把成功状态覆盖回失败。
     */
    private fun finishVoiceHold() {
        if (voiceHoldFinished) return
        if (!isNetworkAvailable()) {
            discardVoiceHold()
            _uiState.update {
                it.copy(
                    activationState = AiActivationState.IDLE,
                    voiceLevel = 0f,
                    voiceCancelArmed = false,
                    errorCode = AI_OFFLINE_ERROR_CODE
                )
            }
            return
        }
        val state = _uiState.value
        val outcome = resolveVoiceHoldOutcome(
            holdDurationMs = voiceReleasedHoldMs ?: currentVoiceHoldMs(),
            peakLevel = voicePeakLevel,
            matchedCharacterId = voiceMatchedCharacterId,
            selectedCharacterId = state.selectedCharacterId,
            captureUnavailable = voiceCaptureUnavailable
        )
        discardVoiceHold()
        val matched = outcome == AiVoiceHoldOutcome.MATCHED
        _uiState.update {
            it.copy(
                // 只有真送出去的按住才烧机会：误触和拿不到麦克风都没提交任何请求
                activationAttempt = if (voiceHoldOutcomeCountsAsAttempt(outcome)) {
                    it.activationAttempt + 1
                } else {
                    it.activationAttempt
                },
                activationState = when {
                    matched -> AiActivationState.VERIFYING
                    // 太短就是误触，别在按钮下面留一条红字，回 IDLE 当没发生过
                    outcome == AiVoiceHoldOutcome.TOO_SHORT -> AiActivationState.IDLE
                    else -> AiActivationState.FAILED
                },
                voiceLevel = 0f,
                voiceCancelArmed = false,
                errorCode = voiceHoldOutcomeErrorCode(outcome)
            )
        }
        if (!matched) return
        val character = state.selectedCharacter ?: return
        val requestId = beginRequest()
        requestJob = viewModelScope.launch { submitVoiceActivation(character, requestId) }
    }

    /**
     * 本地命中后走服务端文字激活：换取角色 ACK 语音与配额记录，
     * spokenName 是角色标准名，服务端 matchesActivationName 必然命中。
     *
     * 激活请求也纳入请求代际：断网/切页后即使旧协程迟到返回，也不能把 OFFLINE
     * 状态覆盖成 SERVER，更不能把已关闭页面的结果写回当前精灵中心。
     */
    private suspend fun submitVoiceActivation(character: AiCharacter, requestId: Long) {
        if (!canContinueAiRequest(requestId)) return
        val result = aiRepository.activate(
            friendId = authManager.friendId.value.orEmpty(),
            request = AiActivateRequest(
                characterId = character.id,
                spokenName = character.activationWord,
                sessionId = spriteSessionId
            )
        )
        if (!canContinueAiRequest(requestId)) return
        result.onSuccess { activation ->
            if (!canContinueAiRequest(requestId)) return@onSuccess
            updateIfCurrentRequest(requestId) {
                it.copy(
                    activatedCharacterId = if (activation.activated) character.id else it.activatedCharacterId,
                    activationState = if (activation.activated) AiActivationState.SUCCESS else AiActivationState.FAILED,
                    activationMessage = activation.activationPhrase.takeIf { activation.activated && it.isNotBlank() },
                    quota = activation.quota ?: it.quota,
                    errorCode = if (activation.activated) null else "ACTIVATION_NOT_MATCHED"
                )
            }
            activation.audio?.let { audio ->
                if (canContinueAiRequest(requestId)) _audioEvents.emit(audio)
            }
            if (activation.activated && requestGeneration == requestId) {
                // 落盘激活态：跨进程重启和详情页这类无激活入口的页面都要认这个角色
                aiRepository.saveActivatedCharacterId(
                    authManager.friendId.value.orEmpty(),
                    character.id
                )
                if (requestGeneration == requestId) loadGreeting(character.id)
            }
        }.onFailure { error ->
            if (requestGeneration != requestId) return@onFailure
            updateIfCurrentRequest(requestId) {
                it.copy(
                    activationState = AiActivationState.FAILED,
                    errorCode = requestErrorCode(error)
                )
            }
        }
    }

    /** 麦克风权限被拒绝或设备没有录音源：语音这条路当场判死，文字入口本来就常驻。 */
    fun onVoiceActivationUnavailable() {
        _uiState.update {
            it.copy(
                activationState = AiActivationState.FAILED,
                errorCode = if (!isNetworkAvailable()) {
                    AI_OFFLINE_ERROR_CODE
                } else {
                    "AUDIO_UNAVAILABLE"
                }
            )
        }
    }

    /**
     * 文字兜底激活：用用户手输的角色名提交，不再直接拿角色预设名蒙过去。
     *
     * 入口常驻，不再要求先让语音失败一次：图书馆、深夜、开会这些场合的用户根本开不了口，
     * 却被逼着先故意失败一次、白烧一次语音机会才能拿到能用的入口。
     * 与语音次数分开计数，各 5 次；请求代际防止迟到结果覆盖离线态。
     */
    fun activateByText(spokenName: String) {
        val state = _uiState.value
        if (!requireAiAccess()) return
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
        val requestId = beginRequest()
        requestJob = viewModelScope.launch {
            if (!canContinueAiRequest(requestId)) return@launch
            updateIfCurrentRequest(requestId) {
                it.copy(
                    textActivationAttempt = attempt,
                    activationState = AiActivationState.VERIFYING,
                    errorCode = null
                )
            }
            if (requestGeneration != requestId) return@launch
            val result = aiRepository.activate(
                authManager.friendId.value.orEmpty(),
                AiActivateRequest(
                    characterId = character.id,
                    spokenName = typedName,
                    sessionId = spriteSessionId
                )
            )
            if (!canContinueAiRequest(requestId)) return@launch
            result.onSuccess { activation ->
                if (!canContinueAiRequest(requestId)) return@onSuccess
                updateIfCurrentRequest(requestId) {
                    it.copy(
                        activatedCharacterId = if (activation.activated) character.id else it.activatedCharacterId,
                        activationState = if (activation.activated) AiActivationState.SUCCESS else AiActivationState.FAILED,
                        activationMessage = activation.activationPhrase.takeIf { activation.activated && it.isNotBlank() },
                        quota = activation.quota ?: it.quota,
                        errorCode = if (activation.activated) null else "ACTIVATION_NOT_MATCHED"
                    )
                }
                activation.audio?.let { audio ->
                    if (canContinueAiRequest(requestId)) _audioEvents.emit(audio)
                }
                if (activation.activated && requestGeneration == requestId) {
                    // 落盘激活态：跨进程重启和详情页这类无激活入口的页面都要认这个角色
                    aiRepository.saveActivatedCharacterId(
                        authManager.friendId.value.orEmpty(),
                        character.id
                    )
                    if (requestGeneration == requestId) loadGreeting(character.id)
                }
            }.onFailure { error ->
                if (requestGeneration != requestId) return@onFailure
                updateIfCurrentRequest(requestId) {
                    it.copy(
                        activationState = AiActivationState.FAILED,
                        errorCode = requestErrorCode(error)
                    )
                }
            }
        }
    }

    fun openFeature(feature: AiFeature) {
        cancelTasteGuard()
        _uiState.update { it.copy(activeFeature = feature, errorCode = null) }
        // 「锐评我的看单」先过隐私守卫：首次点击弹说明（同意才上传）；离线时直接
        // 留在功能页展示不可用文案，不能再发请求后弹服务器错误遮罩。
        if (!isNetworkAvailable()) {
            setError(AI_OFFLINE_ERROR_CODE)
            return
        }
        if (feature == AiFeature.TASTE) {
            launchTasteGuard(forceRefresh = false)
            return
        }
        when (feature) {
            AiFeature.GREETING -> loadGreeting(_uiState.value.activatedCharacterId ?: _uiState.value.selectedCharacterId)
            // 已在上方 TASTE 守卫分支处理
            AiFeature.TASTE -> Unit
            // 返回后重进不能把答到一半的一轮题冲掉；只有没有未完成的一轮时才重新抽题
            AiFeature.QUIZ -> if (!hasQuizInProgress(_uiState.value)) prepareQuizPreview()
            AiFeature.DAILY -> loadDaily()
        }
    }

    /**
     * 锐评功能隐私守卫（须在协程内调用）：
     * 1. 用户从未对首次说明弹窗做出决定 → 弹说明弹窗，不进入功能页；
     * 2. 已决定但上传开关已关闭 → 弹「去设置」引导，不进入功能页；
     * 3. 否则进入功能页并按原逻辑加载数据（缓存/截断逻辑不动）。
     */
    private suspend fun guardThenLoadTaste(forceRefresh: Boolean, guardGeneration: Long) {
        // DataStore 读取可能挂起；每次恢复后都重新确认页面、授权和网络仍然有效。
        if (!requireAiAccess() || !isTasteGuardCurrent(guardGeneration)) return
        val consentDecided = aiTasteStorage.tasteConsentDecided.first()
        if (!isTasteGuardCurrent(guardGeneration)) return
        if (!consentDecided) {
            _uiState.update { it.copy(showTasteConsent = true, errorCode = null) }
            return
        }
        val uploadEnabled = aiTasteStorage.tasteUploadEnabled.first()
        if (!isTasteGuardCurrent(guardGeneration)) return
        if (!uploadEnabled) {
            _uiState.update { it.copy(showTasteDisabled = true, errorCode = null) }
            return
        }
        if (!isTasteGuardCurrent(guardGeneration)) return
        _uiState.update { it.copy(activeFeature = AiFeature.TASTE, errorCode = null) }
        loadTaste(forceRefresh)
    }

    private fun launchTasteGuard(forceRefresh: Boolean) {
        cancelTasteGuard()
        val guardGeneration = tasteGuardGeneration
        tasteGuardJob = viewModelScope.launch {
            guardThenLoadTaste(forceRefresh, guardGeneration)
        }
    }

    private fun isTasteGuardCurrent(guardGeneration: Long): Boolean =
        guardGeneration == tasteGuardGeneration &&
            _uiState.value.activeFeature == AiFeature.TASTE &&
            isNetworkAvailable() &&
            hasLiveAiSession()

    private fun cancelTasteGuard() {
        tasteGuardGeneration += 1L
        tasteGuardJob?.cancel()
        tasteGuardJob = null
    }

    /** 首次说明弹窗点击「同意并继续」：记录已决定，进入功能页并立即加载。 */
    fun onTasteConsentAgreed() {
        cancelTasteGuard()
        val guardGeneration = tasteGuardGeneration
        tasteGuardJob = viewModelScope.launch {
            if (!isTasteGuardCurrent(guardGeneration)) return@launch
            aiTasteStorage.setConsentDecided(true)
            if (!isTasteGuardCurrent(guardGeneration)) return@launch
            _uiState.update { it.copy(showTasteConsent = false) }
            guardThenLoadTaste(forceRefresh = false, guardGeneration = guardGeneration)
        }
    }

    /** 首次说明弹窗点击「暂不使用」或外部关闭：仅收起弹窗，不记录决定（下次点击会再次询问）。 */
    fun onTasteConsentDismissed() {
        _uiState.update { it.copy(showTasteConsent = false) }
    }

    /** 「去设置」引导弹窗关闭（取消或跳转后）：仅收起弹窗。 */
    fun onTasteDisabledDismiss() {
        _uiState.update { it.copy(showTasteDisabled = false) }
    }

    fun closeFeature() {
        cancelTasteGuard()
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
            // 锐评刷新同样过隐私守卫：功能页打开期间开关可能已在设置里被关闭
            AiFeature.TASTE -> launchTasteGuard(forceRefresh = true)
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
        if (!requireAiAccess()) return
        val requestId = beginRequest()
        requestJob = viewModelScope.launch {
            // 网络状态在入口检查后、协程真正调度前可能已经变化；避免旧协程把离线态改回加载态。
            if (!canContinueAiRequest(requestId)) return@launch
            updateIfCurrentRequest(requestId) { it.copy(isLoading = true, errorCode = null) }
            if (requestGeneration != requestId) return@launch
            aiRepository.submitQuiz(
                authManager.friendId.value.orEmpty(),
                quiz.quizId,
                state.quizAnswers.values.toList()
            ).onSuccess { result ->
                if (!canContinueAiRequest(requestId)) {
                    updateIfCurrentRequest(requestId) { it.copy(isLoading = false) }
                    return@onSuccess
                }
                updateIfCurrentRequest(requestId) {
                    it.copy(
                        isLoading = false,
                        quizResult = result,
                        quizResultRevision = it.quizResultRevision + 1L,
                        quota = result.quota ?: it.quota
                    )
                }
                // 保存最近/最高成绩与错题（离线可浏览闯关历史）
                val friendId = authManager.friendId.value.orEmpty()
                if (friendId.isNotBlank() && requestGeneration == requestId) {
                    aiRepository.saveQuizHistory(friendId, result)
                    if (requestGeneration == requestId) {
                        aiRepository.readQuizHistory(friendId)?.let { history ->
                            if (requestGeneration == requestId) {
                                updateIfCurrentRequest(requestId) { it.copy(quizHistory = history) }
                            }
                        }
                    }
                }
            }.onFailure { error ->
                if (requestGeneration != requestId) return@onFailure
                updateIfCurrentRequest(requestId) {
                    it.copy(
                        isLoading = false,
                        // 网络先于取消回调落地时，离线优先于 Overlay 服务器错误。
                        errorCode = requestErrorCode(error)
                    )
                }
            }
        }
    }

    fun replayQuiz() {
        quizLocalizationJob?.cancel()
        quizLocalizationJob = null
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
                quizPreviewLocalizedTitles = emptyMap(),
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
        if (!isNetworkAvailable()) return
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
                quizPreviewLocalizedTitles = it.quizPreviewLocalizedTitles.filterKeys { key ->
                    preview.any { movie -> quizPreviewTitleKey(movie) == key }
                },
                quizReplacementCount = 0,
                quizReplaceAvailable = canReplaceQuizPreview(preview, quizCandidates, 0)
            )
        }
        refreshQuizPreviewLocalizedTitles(preview)
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
                    quizPreviewLocalizedTitles = it.quizPreviewLocalizedTitles.filterKeys { key ->
                        next.any { movie -> quizPreviewTitleKey(movie) == key }
                    },
                    quizReplacementCount = nextReplacementCount,
                    quizReplaceAvailable = canReplaceQuizPreview(next, quizCandidates, nextReplacementCount)
                )
            }
            refreshQuizPreviewLocalizedTitles(next)
        }
    }

    fun clearError() {
        _uiState.update {
            if (!isNetworkAvailable()) {
                it.copy(errorCode = AI_OFFLINE_ERROR_CODE)
            } else {
                it.copy(errorCode = null)
            }
        }
    }

    /** 手动重播当前角色的试听，供试听文案旁的播放按钮使用。 */
    fun replaySelectedCharacter() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            previewSelectedCharacter()
        }
    }

    /**
     * 预存试听音频：assets 里按角色 ID 打包了与服务端文案一致的 MP3，命中即零网络播放。
     * 服务端改过试听文案时（远端合并覆盖后的 auditionText 与本地目录不一致）返回 null，
     * 让调用方走 TTS 重新合成，避免播错文案的旧音频。
     */
    private fun bundledAuditionAudio(character: AiCharacter): AiAudio? {
        val catalogText = AiCharacterCatalog.all.firstOrNull { it.id == character.id }?.auditionText
        if (character.auditionText != catalogText) return null
        val assetPath = "ai_auditions/${character.id}.mp3"
        return try {
            context.assets.open(assetPath).use { /* 能打开即视为已打包 */ }
            AiAudio(
                audioDataUrl = null,
                audioUrl = "file:///android_asset/$assetPath",
                mimeType = "audio/mpeg",
                durationMs = null,
                cacheKey = null,
                transcript = character.auditionText
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadCharacters() {
        if (!isNetworkAvailable()) {
            setError(AI_OFFLINE_ERROR_CODE)
            return
        }
        aiRepository.listCharacters().fold(
            onSuccess = { remote ->
                val remoteById = remote.associateBy { it.id }
                _uiState.update { state ->
                    state.copy(
                        // 远端主要负责可用态；文本字段为空时保留本地目录，避免试听区域只剩空矩形。
                        characters = state.characters.map { local ->
                            remoteById[local.id]?.let { remoteCharacter ->
                                local.copy(
                                    name = remoteCharacter.name.takeIf { it.isNotBlank() } ?: local.name,
                                    activationWord = remoteCharacter.activationWord
                                        .takeIf { it.isNotBlank() } ?: local.activationWord,
                                    aliases = remoteCharacter.aliases.takeIf { it.isNotEmpty() } ?: local.aliases,
                                    isAvailable = remoteCharacter.isAvailable,
                                    personalityPrompt = remoteCharacter.personalityPrompt
                                        .takeIf { it.isNotBlank() } ?: local.personalityPrompt,
                                    auditionText = remoteCharacter.auditionText
                                        .takeIf { it.isNotBlank() } ?: local.auditionText
                                )
                            } ?: local
                        },
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
        // 离线时只走系统 TTS，不向网关发请求；在线且未激活的访客仍可使用免费 MiMo TTS。
        if (!isNetworkAvailable()) {
            emitGuestPreviewFallback(character)
            return
        }
        // 预存音频命中则立即播放，不消耗 TTS 配额；未命中按角色状态走网络链路
        bundledAuditionAudio(character)?.let { local ->
            emitAuditionAudio(local)
            return
        }
        val authorized = hasLiveAiSession()
        val request = buildAuditionTtsRequest(character, spriteSessionId)
        when (auditionPlaybackRoute(authorized, character.isAvailable)) {
            AiAuditionPlaybackRoute.SYSTEM_TTS -> {
                emitGuestPreviewFallback(character)
            }
            AiAuditionPlaybackRoute.GUEST_TTS -> {
                aiRepository.playGuestTts(request).fold(
                    onSuccess = { audio ->
                        if (audio.hasPlayableSource()) emitAuditionAudio(audio)
                        else emitGuestPreviewFallback(character)
                    },
                    onFailure = { emitGuestPreviewFallback(character) }
                )
            }
            AiAuditionPlaybackRoute.AUTHORIZED_TTS -> {
                aiRepository.playTts(authManager.friendId.value.orEmpty(), request).fold(
                    onSuccess = { audio ->
                        if (audio.hasPlayableSource()) emitAuditionAudio(audio)
                        else emitGuestPreviewFallback(character)
                    },
                    onFailure = { emitGuestPreviewFallback(character) }
                )
            }
        }
    }

    private fun scheduleCharacterPreview() {
        previewJob?.cancel()
        // 预存音频命中时跳过防抖立即播放；未命中才按原防抖走网络 TTS
        val character = _uiState.value.selectedCharacter
        if (character != null) {
            bundledAuditionAudio(character)?.let { local ->
                previewJob = viewModelScope.launch { emitAuditionAudio(local) }
                return
            }
        }
        previewJob = viewModelScope.launch {
            delay(350)
            previewSelectedCharacter()
        }
    }

    /**
     * 试听事件必须等精灵中心的收集器就位再发。
     *
     * [_audioEvents] 是 replay = 0 的 SharedFlow，没有订阅者时 emit 会被静默丢弃。
     * 精灵中心的 LaunchedEffect 先调 ensureLoaded()、之后才 launch 收集器；而搜索页启动时
     * 已经调过一次 ensureLoaded，进精灵中心走的是 initialized 分支，预存试听又不经过 350ms
     * 防抖，emit 正好落在收集器注册之前。事件丢掉后 UI 停在 LOADING，只能干转到 8 秒兜底超时。
     *
     * 页面没打开时这里会一直挂着，下一次 scheduleCharacterPreview() 取消 previewJob 即释放，
     * 也保证不会在页面打开瞬间补播上一个角色的旧试听。
     */
    private suspend fun emitAuditionAudio(audio: AiAudio) {
        _audioEvents.subscriptionCount.first { it > 0 }
        _audioEvents.emit(audio)
    }

    /** 系统语音兜底同样是试听链路的一环，收集器未就位时丢事件一样会让 UI 干转。 */
    private suspend fun emitGuestPreviewFallback(character: AiCharacter) {
        character.auditionText.takeIf { it.isNotBlank() }?.let { text ->
            _guestPreviewFallbackEvents.subscriptionCount.first { it > 0 }
            _guestPreviewFallbackEvents.emit(text)
        }
    }

    private fun AiAudio.hasPlayableSource(): Boolean =
        !audioDataUrl.isNullOrBlank() || !audioUrl.isNullOrBlank()

    private fun loadGreeting(characterId: String, forceRefresh: Boolean = false) {
        runFeature(AiFeature.GREETING) { requestId ->
            aiRepository.getGreeting(authManager.friendId.value.orEmpty(), characterId, forceRefresh)
                .onSuccess { greeting ->
                    if (!canContinueAiRequest(requestId)) return@onSuccess
                    updateIfCurrentRequest(requestId) {
                        it.copy(greeting = greeting, quota = greeting.quota ?: it.quota)
                    }
                    greeting.audio?.let { audio ->
                        if (canContinueAiRequest(requestId)) _audioEvents.emit(audio)
                    }
                }
                .getOrElse { throw it }
        }
    }

    private fun loadTaste(forceRefresh: Boolean = false) {
        runFeature(AiFeature.TASTE) { requestId ->
            val watched = watchedTitles()
            if (watched.isEmpty()) throw IllegalStateException("WATCHED_LIST_EMPTY")
            if (!canContinueAiRequest(requestId)) return@runFeature
            aiRepository.getTaste(
                authManager.friendId.value.orEmpty(),
                com.tracktosearch.data.ai.AiTasteRequest(watched = watched, forceRefresh = forceRefresh),
                forceRefresh
            ).onSuccess { taste ->
                if (!canContinueAiRequest(requestId)) return@onSuccess
                updateIfCurrentRequest(requestId) {
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
        runFeature(AiFeature.QUIZ) { requestId ->
            val watched = watchedTitles()
            if (watched.size < 7) throw IllegalStateException("NOT_ENOUGH_MOVIES")
            if (!canContinueAiRequest(requestId)) return@runFeature
            val preview = selectQuizPreview(watched)
            val localizedTitles = resolveQuizPreviewLocalizedTitles(preview)
            if (!canContinueAiRequest(requestId)) return@runFeature
            quizCandidates = watched
            updateIfCurrentRequest(requestId) {
                it.copy(
                    quiz = null,
                    quizResult = null,
                    quizStarted = false,
                    quizIndex = 0,
                    quizAnswers = emptyMap(),
                    quizPreviewMovies = preview,
                    quizPreviewLocalizedTitles = localizedTitles,
                    quizReplacementCount = 0,
                    quizReplaceAvailable = canReplaceQuizPreview(preview, watched, 0)
                )
            }
        }
    }

    /**
     * 为当前最多 7 部预览影视补齐 TMDB 当前语言标题。
     * 先 peek 内存缓存，只有未命中才请求详情；单项失败不影响其他标题，也不改变发给 AI 的原始标题。
     */
    private suspend fun resolveQuizPreviewLocalizedTitles(
        movies: List<AiWatchedTitleDto>
    ): Map<String, String> {
        if (!isNetworkAvailable()) return emptyMap()
        val languageAtStart = languageStorage.language.value
        // 使用可注入调度器，测试可继续受 runTest 虚拟时钟控制，避免异步任务污染后续用例。
        val resolved = withContext(ioDispatcher) {
            supervisorScope {
                movies.map { movie ->
                    async {
                        val tmdbId = movie.mediaIds.tmdbId?.takeIf { it > 0 }
                            ?: return@async null
                        try {
                            val isShow = movie.mediaType.equals("show", ignoreCase = true) ||
                                movie.mediaType.equals("tv", ignoreCase = true)
                            val localizedTitle = if (isShow) {
                                (tmdbRepository.peekTvEnrichment(tmdbId, movie.title, movie.year)
                                    ?: tmdbRepository.enrichTv(tmdbId, movie.title, movie.year)).chineseTitle
                            } else {
                                (tmdbRepository.peekMovieEnrichment(tmdbId, movie.title, movie.year)
                                    ?: tmdbRepository.enrichMovie(tmdbId, movie.title, movie.year)).chineseTitle
                            }
                            val localized = localizedTitle.trim()
                                .takeIf { it.isNotBlank() && !it.equals(movie.title.trim(), ignoreCase = true) }
                            localized?.let { quizPreviewTitleKey(movie) to it }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull().toMap()
            }
        }
        // 语言在并发补全期间切换时，丢弃混合语言结果；下一次进入页面会按新语言重新补全。
        return if (languageStorage.language.value == languageAtStart) resolved else emptyMap()
    }

    private fun refreshQuizPreviewLocalizedTitles(movies: List<AiWatchedTitleDto>) {
        quizLocalizationJob?.cancel()
        if (movies.isEmpty()) {
            _uiState.update { it.copy(quizPreviewLocalizedTitles = emptyMap()) }
            quizLocalizationJob = null
            return
        }
        quizLocalizationJob = viewModelScope.launch {
            val localizedTitles = resolveQuizPreviewLocalizedTitles(movies)
            if (_uiState.value.quizPreviewMovies == movies) {
                _uiState.update { it.copy(quizPreviewLocalizedTitles = localizedTitles) }
            }
        }
    }

    private fun quizPreviewTitleKey(movie: AiWatchedTitleDto): String =
        quizMediaKey(movie.mediaType, movie.mediaId)

    private fun loadQuiz(watched: List<AiWatchedTitleDto>, forceRefresh: Boolean) {
        runFeature(AiFeature.QUIZ) { requestId ->
            if (watched.size < 7) throw IllegalStateException("NOT_ENOUGH_MOVIES")
            if (!canContinueAiRequest(requestId)) return@runFeature
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
                if (!canContinueAiRequest(requestId)) return@onSuccess
                recentQuizIds = (listOf(quiz.quizId) + recentQuizIds).take(3)
                updateIfCurrentRequest(requestId) {
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
        runFeature(AiFeature.DAILY) { requestId ->
            aiRepository.getDailyKnowledge(authManager.friendId.value.orEmpty(), forceRefresh)
                .onSuccess { daily ->
                    if (!canContinueAiRequest(requestId)) return@onSuccess
                    updateIfCurrentRequest(requestId) {
                        it.copy(dailyKnowledge = daily, quota = daily.quota ?: it.quota)
                    }
                }
                .getOrElse { throw it }
        }
    }

    private fun runFeature(feature: AiFeature, block: suspend (Long) -> Unit) {
        if (!requireAiAccess()) return
        val requestId = beginRequest()
        requestJob = viewModelScope.launch {
            try {
                if (!canContinueAiRequest(requestId)) return@launch
                updateIfCurrentRequest(requestId) {
                    it.copy(isLoading = true, loadingFeature = feature, errorCode = null)
                }
                if (requestGeneration != requestId) return@launch
                block(requestId)
            } catch (e: CancellationException) {
                // 新请求（切换功能/提交/激活）取消旧请求时，不能走 onFailure 画假错误或翻转状态
                throw e
            } catch (e: Exception) {
                if (requestGeneration == requestId) {
                    setRequestError(requestId, requestErrorCode(e))
                }
            } finally {
                updateIfCurrentRequest(requestId) {
                    it.copy(isLoading = false, loadingFeature = null)
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
        val loaded = withContext(ioDispatcher) {
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

    private fun setError(code: String) {
        _uiState.update { state ->
            // 所有入口错误都遵守离线优先，避免晚到的本地/授权回调重新显示 Overlay。
            state.copy(errorCode = if (!isNetworkAvailable()) AI_OFFLINE_ERROR_CODE else code)
        }
    }

    private fun errorCode(error: Throwable): String {
        // AiApiException 直接取码；其余（IO/解析等）经 fromThrowable 归一，
        // 不把原始 message 直通 UI——映射表查不到只会落到无信息量的兜底文案
        val aiError = error as? com.tracktosearch.data.ai.AiApiException
        return aiError?.errorCode?.name
            ?: com.tracktosearch.data.ai.AiErrorMapper.fromThrowable(error).errorCode.name
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
