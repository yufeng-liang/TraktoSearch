package com.tracktosearch.ui.screen.ai

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.ai.AiActivateRequest
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiCharacterCatalog
import com.tracktosearch.data.ai.AiErrorCode
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiQuota
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
import com.tracktosearch.data.ai.AiDailyKnowledgeContentFeedback
import com.tracktosearch.data.ai.AiDailyKnowledgeHistoryRecord
import com.tracktosearch.data.ai.AiDailyStage
import com.tracktosearch.data.ai.AiDailyStageStatus
import com.tracktosearch.data.ai.AiDailyStreamEvent
import com.tracktosearch.data.ai.AiQuizDifficulty
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
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

private val BEIJING_ZONE = ZoneId.of("GMT+8")
private const val WATCHED_TITLES_TTL_MS = 10 * 60 * 1000L
private const val QUIZ_PREVIEW_SIZE = QUIZ_MOVIE_COUNT
/** 每套固定 13 题：服务端对 questionCount 做严格校验，客户端不能传别的值。 */
private const val QUIZ_QUESTION_COUNT = 13

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
    // 出题等待页的真实进度：服务端流式事件驱动（阶段 + 已生成字符数）
    val quizStage: com.tracktosearch.data.ai.AiQuizStage? = null,
    val quizStageChars: Int = 0,
    val quizStageExpectedChars: Int = 0,
    // 本轮出题请求的发起时刻（毫秒）：等待页据此显示已用时长
    val quizRequestStartedAtMillis: Long = 0L,
    val quizPreviewMovies: List<AiWatchedTitleDto> = emptyList(),
    val quizPreviewLocalizedTitles: Map<String, String> = emptyMap(),
    // 用户此刻正在等生成：等待页据此进入真实进度视图（静默预生成不置这个标志）
    val quizGenerating: Boolean = false,
    // 今日题目的准备状态：预览页据此说明点「开始」是秒开还是现场生成
    val quizPrepareState: AiQuizPrepareState = AiQuizPrepareState.IDLE,
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
    // 今日知识加载页的真实进度：服务端流式事件驱动（阶段 + 已生成字符数）
    val dailyStage: com.tracktosearch.data.ai.AiDailyStage? = null,
    val dailyStageChars: Int = 0,
    val dailyStageExpectedChars: Int = 0,
    // 本轮加载的发起时刻（毫秒）：加载页据此显示已用时长
    val dailyRequestStartedAtMillis: Long = 0L,
    val dailyKnowledgeChangeCount: Int = 0,
    val dailyQuestionSelectedOptionId: String? = null,
    val dailyKnowledgeFeedback: AiDailyKnowledgeContentFeedback? = null,
    val dailyKnowledgeDifficultyFeedback: AiQuizDifficulty? = null,
    // 最近学过的本地历史（含当天离线回看），只读展示用
    val dailyKnowledgeHistory: List<AiDailyKnowledgeHistoryRecord> = emptyList(),
    // 影视依据区的真实剧照/海报 URL：TMDB backdrop 优先，海报兜底
    val dailyMediaImageUrl: String? = null,
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
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val restoredDailyKnowledgeChangeCount = restoreDailyKnowledgeChangeCount()
    private val _uiState = MutableStateFlow(
        AiSpriteUiState(
            authState = authManager.authState.value,
            networkStatus = connectivityObserver.status.value,
            nickname = authManager.nickname.value,
            dailyKnowledgeChangeCount = restoredDailyKnowledgeChangeCount
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
    /**
     * 静默预生成：进入出题页就把当天这一套题备好，用户点「开始」时直接命中服务端题库。
     *
     * 单独一个 Job（不进 requestJob）：切换功能、取消等待都不该中断它 —— 它产出的是当天
     * 固定那一套题，中断了下次还得重来，而服务端并不会因为客户端断开而停止生成。
     * [quizAwaitPrewarm] 置位时表示用户已经点了「开始」正在等这一条流。
     */
    private var quizPrewarmJob: Job? = null
    /** 预生成完成的那一天的日期；换天即作废（跨零点后旧结果属于昨天的题）。 */
    private var quizPreparedDay: String? = null
    /** 作废令牌：取消/重开预生成后，旧 Job 的迟到回调不能再写状态。 */
    private var quizPrewarmToken = 0L
    private var quizAwaitPrewarm = false
    /** 开始答题构造请求时复用预览阶段的富化结果。 */
    private var quizPreviewEnrichmentLanguage: String? = null
    private var quizPreviewEnrichments = emptyMap<String, QuizPreviewEnrichment>()
    /** 授权变化时取消本地私有状态恢复，避免迟到结果复活旧账号数据。 */
    private var activationRestoreJob: Job? = null
    private var quizHistoryJob: Job? = null
    private var privateStateGeneration = 0L
    /** 锐评隐私守卫任务；关闭功能页或切换功能时必须取消。 */
    private var tasteGuardJob: Job? = null
    private var tasteGuardGeneration = 0L
    /** 概念插图静默刷新任务：一个内容最多触发一次，切页即取消。 */
    private var dailyIllustrationJob: Job? = null
    private var dailyIllustrationRefreshedUnitIds = mutableSetOf<String>()
    /** 影视依据区剧照查找任务：内容切换即取消，避免旧结果覆盖新内容。 */
    private var dailyStillJob: Job? = null
    /** 最近学过历史读取任务。 */
    private var dailyHistoryJob: Job? = null
    /** 已看列表短期缓存（时间戳 to 列表），避免每次进功能页都全量重拉 Trakt 历史。 */
    private var watchedTitlesCache: Pair<Long, List<AiWatchedTitleDto>>? = null
    // 一次精灵中心会话共用一个会话 ID，让服务端会话配额按一次打开的精灵中心计算。
    // 若每次请求都发新 UUID，会话配额形同虚设，只剩每日上限。
    // ViewModel 现在挂在 Activity 作用域跨页面共享，不能再按实例生成——否则整个 App
    // 生命周期只有一个会话，会话配额到重启才重置。改由 onSpriteCenterOpened() 轮换。
    private var spriteSessionId = newSpriteSessionId()

    init {
        observeConnectivityState()
        observeQuotaSnapshot()
    }

    /**
     * 额度变化时把当日用量落盘：服务端没有独立额度查询接口，重启后恢复激活态
     * 只能靠这份快照把「今日 x/80」补回来。按 distinct dailyUsed 去重，避免每次刷新都写。
     */
    private fun observeQuotaSnapshot() {
        viewModelScope.launch(ioDispatcher) {
            _uiState
                .map { it.quota?.dailyUsed }
                .distinctUntilChanged()
                .collect { dailyUsed ->
                    if (dailyUsed == null) return@collect
                    val friendId = authManager.friendId.value.orEmpty()
                    if (friendId.isBlank()) return@collect
                    aiRepository.saveQuotaSnapshot(friendId, dailyUsed)
                }
        }
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
                    // 初始就是离线时不能取消刚启动的每日知识本地缓存读取；
                    // 只有从在线变离线才需要打断仍在等待网络的请求。
                    previous != ConnectivityObserver.NetworkStatus.OFFLINE &&
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

    /** 受保护的 AI 请求只接受实时 AUTHORIZED；OFFLINE 仅可继续浏览本地缓存。 */
    private fun hasLiveAiSession(): Boolean =
        authManager.authState.value == AuthState.AUTHORIZED &&
            !authManager.friendId.value.isNullOrBlank()

    /** 离线但仍有可识别账号时，仅允许每日知识走本地缓存读取。 */
    private fun hasOfflineAiSession(): Boolean =
        authManager.authState.value == AuthState.OFFLINE &&
            !authManager.friendId.value.isNullOrBlank()

    /**
     * 网络状态优先：真正离线时必须展示离线文案，而不是误报需要重新授权。
     * [allowOfflineCache] 只给每日知识首次进入使用：允许 Repository 先读缓存，
     * 缓存未命中时仍由统一错误映射回落到离线空状态。
     */
    private fun requireAiAccess(allowOfflineCache: Boolean = false): Boolean {
        if (!isNetworkAvailable()) {
            if (allowOfflineCache && hasOfflineAiSession()) return true
            setError(AI_OFFLINE_ERROR_CODE)
            return false
        }
        if (hasLiveAiSession()) return true
        setError("AUTH_REQUIRED")
        return false
    }

    /**
     * AI 请求在每次挂起返回后都要重新过闸：取消 Job 不保证非协作式实现立即停止，
     * 迟到回调必须同时满足「仍是当前请求、实时在线、授权仍有效」才能写回状态。
     */
    private fun canContinueAiRequest(requestId: Long, allowOfflineCache: Boolean = false): Boolean {
        if (requestGeneration != requestId) return false
        if (!isNetworkAvailable()) {
            if (!allowOfflineCache || !hasOfflineAiSession()) {
                setRequestError(requestId, AI_OFFLINE_ERROR_CODE)
                return false
            }
        } else if (!hasLiveAiSession()) {
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
        // 出错时把等待页收掉：错误由功能页的错误态呈现，不能让用户停在生成中的进度页
        quizAwaitPrewarm = false
        updateIfCurrentRequest(requestId) { state ->
            state.copy(
                errorCode = if (!isNetworkAvailable()) AI_OFFLINE_ERROR_CODE else code,
                quizGenerating = false
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

    /**
     * 自动探头要求角色已激活且有对应素材，没素材的角色不参与探头。
     *
     * release 编译期关掉 AI（[aiFeaturesEnabled]=false）时恒为 false：
     * nextAiSpriteOverlayTrigger 首行就吃 activated 参数、tryConsumeOverlay 也经这里查激活态，
     * 两处搜索页的探头触发与额度消费都从此断掉，是自动探头的唯一收口。
     */
    fun isSpriteActivatedForMotion(): Boolean =
        aiFeaturesEnabled &&
            _uiState.value.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true

    /** 每次打开精灵中心换一个会话 ID，保持「一次打开 = 一个会话」的配额语义。 */
    fun onSpriteCenterOpened() {
        spriteSessionId = newSpriteSessionId()
    }

    fun ensureLoaded() {
        // release 关掉 AI 后没有任何入口能到精灵中心，这里再拉角色目录、恢复激活、
        // 排期试听就是白耗流量与音频焦点，直接不启动。
        if (!aiFeaturesEnabled) return
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
        // release 关掉 AI：详情页只是想让「加入看单」庆祝插画有角色可画，精灵中心与助手
        // 都不在，恢复落盘激活角色（顺带读一遍私有画像）没有消费方，不启动。
        if (!aiFeaturesEnabled) return
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
            // 额度只随各功能响应顺带返回，重启后没有现值；读当日快照把「今日 x/80」补回来，
            // 本轮计数随会话重置故显示 0，跨日快照不命中则维持「额度暂不可用」
            val snapshotDailyUsed = aiRepository.readQuotaSnapshotDailyUsed(friendId)
            var restoredSelected = false
            _uiState.update { state ->
                // 本次会话里已经激活过就不覆盖，避免落盘的旧值顶掉刚激活的角色
                if (state.activatedCharacterId != null) state
                else {
                    restoredSelected = true
                    state.copy(
                        activatedCharacterId = restored,
                        selectedCharacterId = restored,
                        activationState = AiActivationState.SUCCESS,
                        quota = state.quota ?: snapshotDailyUsed?.let { dailyUsed ->
                            AiQuota(
                                sessionUsed = 0,
                                sessionLimit = 14,
                                dailyUsed = dailyUsed,
                                dailyLimit = 80,
                                resetAt = null
                            )
                        }
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
                var previousNickname: String? = null
                authManager.nickname.collect { nickname ->
                    val changed = previousNickname != null && previousNickname != nickname
                    previousNickname = nickname
                    val current = _uiState.value
                    val characterId = current.activatedCharacterId?.takeIf { it.isNotBlank() }
                    val shouldReloadGreeting = changed &&
                        current.activeFeature == AiFeature.GREETING &&
                        characterId != null
                    if (changed && characterId != null) {
                        aiRepository.clearGreetingCache(
                            friendId = authManager.friendId.value.orEmpty(),
                            characterId = characterId
                        )
                    }
                    _uiState.update {
                        it.copy(
                            nickname = nickname,
                            // 旧昵称点评不能继续与新昵称并排展示；在线时立即重新生成。
                            greeting = if (changed) null else it.greeting
                        )
                    }
                    if (shouldReloadGreeting) loadGreeting(characterId)
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
        cancelDailyKnowledgeJobs()
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
        cancelDailyKnowledgeJobs()
        dailyIllustrationRefreshedUnitIds.clear()
        // 正在按住时被撤销授权：麦克风必须当场还回去，不能留着录一段谁也不会用的音频
        discardVoiceHold()
        previewJob?.cancel()
        previewJob = null
        recentQuizIds = emptyList()
        cancelQuizPrewarm()
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
                quizGenerating = false,
                quizPrepareState = AiQuizPrepareState.IDLE,
                quizStarted = false,
                quizIndex = 0,
                quizAnswers = emptyMap(),
                quizResult = null,
                quizResultRevision = 0L,
                quizFeedbackDifficulty = null,
                quizFeedbackState = AiQuizFeedbackState.NOT_SUBMITTED,
                quizHistory = null,
                dailyKnowledge = null,
                dailyKnowledgeChangeCount = 0,
                dailyQuestionSelectedOptionId = null,
                dailyKnowledgeFeedback = null,
                dailyKnowledgeDifficultyFeedback = null,
                dailyKnowledgeHistory = emptyList(),
                dailyMediaImageUrl = null,
                errorCode = null
            )
        }
        persistDailyKnowledgeChangeCount(0)
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
        cancelDailyKnowledgeJobs()
        // 离开出题页就不再承接「点过开始」的那次等待：否则预生成一跑完，用户正在看别的
        // 功能页时会突然被切进答题。预生成本身继续跑，回来点开始仍命中题库。
        if (feature != AiFeature.QUIZ) quizAwaitPrewarm = false
        _uiState.update { it.copy(activeFeature = feature, errorCode = null) }
        // 「锐评我的看单」先过隐私守卫：首次点击弹说明（同意才上传）；离线时直接
        // 留在功能页展示不可用文案，不能再发请求后弹服务器错误遮罩。
        if (!isNetworkAvailable()) {
            if (feature == AiFeature.DAILY) {
                normalizeDailyKnowledgeChangeCountForToday()
                // 每日知识有本地缓存时仍应可读；缓存未命中再落到离线空状态。
                loadDaily(allowOfflineCache = true)
                return
            }
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
        val consentDecided = aiTasteStorage.awaitTasteConsentDecided()
        if (!isTasteGuardCurrent(guardGeneration)) return
        if (!consentDecided) {
            _uiState.update { it.copy(showTasteConsent = true, errorCode = null) }
            return
        }
        val uploadEnabled = aiTasteStorage.awaitTasteUploadEnabled()
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

    /** 离开每日知识页时停掉插图刷新/剧照查找/历史读取，防止迟到结果写进别的页面。 */
    private fun cancelDailyKnowledgeJobs() {
        dailyIllustrationJob?.cancel()
        dailyIllustrationJob = null
        dailyStillJob?.cancel()
        dailyStillJob = null
        dailyHistoryJob?.cancel()
        dailyHistoryJob = null
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
        cancelDailyKnowledgeJobs()
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
            AiFeature.DAILY -> {
                normalizeDailyKnowledgeChangeCountForToday()
                // 页面上还没有内容（首次加载失败）时点重试不该吃掉「换一条」额度：
                // 这次只是把同一份当天内容再取一次（forceRefresh=false 不会生成新内容）。
                if (_uiState.value.dailyKnowledge == null) {
                    loadDaily(forceRefresh = false, allowOfflineCache = true)
                    return
                }
                if (!canChangeDailyKnowledge(_uiState.value.dailyKnowledgeChangeCount)) return
                loadDaily(forceRefresh = true)
            }
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
        // 预生成本身不取消：它产出的是当天固定那一套题，中断了下次还得重来
        quizAwaitPrewarm = false
        _uiState.update {
            it.copy(isLoading = false, loadingFeature = null, quizGenerating = false)
        }
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
        // 不取消进行中的预生成：它备的就是「下一套」（预生成不吃 usedSets，序号没动），
        // 重开一次只会让服务端把同一套题再生成一遍。作废只在换账号时做（见
        // clearPrivateStateAfterUnauthorized）。但要撤掉「点过开始」的那次等待承接：
        // 用户此刻要的是重新看一遍考点，不该等预生成跑完就自动开考。
        quizAwaitPrewarm = false
        _uiState.update {
            it.copy(
                quiz = null,
                quizResult = null,
                quizStarted = false,
                quizIndex = 0,
                quizAnswers = emptyMap(),
                quizPreviewMovies = emptyList(),
                quizPreviewLocalizedTitles = emptyMap(),
                quizFeedbackDifficulty = null,
                quizFeedbackState = AiQuizFeedbackState.NOT_SUBMITTED
            )
        }
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

    /** UI 渲染成功后才写入历史；网络成功不等于用户真的看过这条内容。 */
    fun markDailyKnowledgeShown(daily: AiDailyKnowledge) {
        val friendId = authManager.friendId.value.orEmpty()
        if (friendId.isBlank()) return
        viewModelScope.launch {
            val record = aiRepository.markDailyKnowledgeShown(friendId, daily)
            if (record == null) return@launch
            var pendingAnswerOptionId: String? = null
            _uiState.update { state ->
                if (dailyKnowledgeHistoryKey(state.dailyKnowledge) != dailyKnowledgeHistoryKey(daily)) state
                else {
                    // 用户在历史写库完成前就答了题：首答还在 UI 里，落库交给下面的补写。
                    pendingAnswerOptionId = if (record.questionResult == null) {
                        state.dailyQuestionSelectedOptionId
                    } else {
                        null
                    }
                    state.copy(
                        dailyQuestionSelectedOptionId = state.dailyQuestionSelectedOptionId
                            ?: record.questionResult?.selectedOptionIds?.firstOrNull(),
                        dailyKnowledgeFeedback = state.dailyKnowledgeFeedback ?: record.contentFeedback,
                        dailyKnowledgeDifficultyFeedback = state.dailyKnowledgeDifficultyFeedback
                            ?: record.difficultyFeedback
                    )
                }
            }
            if (pendingAnswerOptionId != null) {
                aiRepository.recordDailyKnowledgeQuestionAnswer(
                    friendId = friendId,
                    unitId = dailyKnowledgeHistoryKey(daily),
                    selectedOptionIds = listOf(pendingAnswerOptionId),
                    locale = daily.locale
                )
            }
            // 新记录落库后刷新“最近学过”列表。
            loadDailyKnowledgeHistory()
        }
    }

    /** 即时小题只在本地判定，不额外请求 AI，也不计入 13 题成绩。 */
    fun selectDailyKnowledgeQuestionOption(optionId: String) {
        val state = _uiState.value
        val question = state.dailyKnowledge?.checkQuestion
        if (!dailyCheckQuestionIsValid(question) || state.dailyQuestionSelectedOptionId != null) return
        if (question?.options?.none { it.id == optionId } != false) return
        _uiState.update { it.copy(dailyQuestionSelectedOptionId = optionId) }
        persistDailyKnowledgeAnswer(state.dailyKnowledge, optionId)
    }

    /** 内容反馈本地一次性记录，同时落到今日知识历史，供后续候选选择使用。 */
    fun recordDailyKnowledgeFeedback(feedback: AiDailyKnowledgeContentFeedback) {
        val daily = _uiState.value.dailyKnowledge ?: return
        if (dailyKnowledgeHistoryKey(daily).isBlank()) return
        _uiState.update { state ->
            if (state.dailyKnowledgeFeedback != null) state
            else state.copy(dailyKnowledgeFeedback = feedback)
        }
        persistDailyKnowledgeFeedback(daily, feedback)
    }

    /** 难度反馈复用闯关难度枚举；同样本地一次性记录并写入历史。 */
    fun recordDailyKnowledgeDifficultyFeedback(difficulty: AiQuizDifficulty) {
        val daily = _uiState.value.dailyKnowledge ?: return
        if (dailyKnowledgeHistoryKey(daily).isBlank()) return
        _uiState.update { state ->
            if (state.dailyKnowledgeDifficultyFeedback != null) state
            else state.copy(dailyKnowledgeDifficultyFeedback = difficulty)
        }
        persistDailyKnowledgeDifficultyFeedback(daily, difficulty)
    }

    private fun persistDailyKnowledgeAnswer(daily: AiDailyKnowledge?, optionId: String) {
        val friendId = authManager.friendId.value.orEmpty()
        val unitId = dailyKnowledgeHistoryKey(daily)
        if (daily == null || friendId.isBlank() || unitId.isBlank()) return
        viewModelScope.launch {
            aiRepository.recordDailyKnowledgeQuestionAnswer(
                friendId = friendId,
                unitId = unitId,
                selectedOptionIds = listOf(optionId),
                locale = daily.locale
            )
        }
    }

    private fun persistDailyKnowledgeFeedback(
        daily: AiDailyKnowledge,
        feedback: AiDailyKnowledgeContentFeedback
    ) {
        val friendId = authManager.friendId.value.orEmpty()
        val unitId = dailyKnowledgeHistoryKey(daily)
        if (friendId.isBlank() || unitId.isBlank()) return
        viewModelScope.launch {
            aiRepository.saveDailyKnowledgeContentFeedback(
                friendId = friendId,
                unitId = unitId,
                feedback = feedback,
                locale = daily.locale
            )
        }
    }

    private fun persistDailyKnowledgeDifficultyFeedback(
        daily: AiDailyKnowledge,
        difficulty: AiQuizDifficulty
    ) {
        val friendId = authManager.friendId.value.orEmpty()
        val unitId = dailyKnowledgeHistoryKey(daily)
        if (friendId.isBlank() || unitId.isBlank()) return
        viewModelScope.launch {
            aiRepository.saveDailyKnowledgeDifficultyFeedback(
                friendId = friendId,
                unitId = unitId,
                difficulty = difficulty,
                locale = daily.locale
            )
        }
    }

    /**
     * 开始闯关。
     *
     * 预生成正在跑时必须接同一条流而不是另发一次请求：同一套题生成两遍要烧两次几十次上游
     * 调用，而服务端没有「同一套题正在生成」的合并。承接方式是等它跑完 —— 生成完的那一刻
     * 服务端题库里就有这一套了，随后的正式请求必然命中，返回是秒回。
     */
    fun startQuiz() {
        val preview = _uiState.value.quizPreviewMovies
        if (preview.size < QUIZ_PREVIEW_SIZE) {
            setError("NOT_ENOUGH_MOVIES")
            return
        }
        val prewarm = quizPrewarmJob
        if (prewarm != null && prewarm.isActive) {
            // 等待页立即可见，计时从用户点下的这一刻算起（预生成先跑的那段不算在他头上）
            _uiState.update {
                it.copy(
                    quizGenerating = true,
                    quizRequestStartedAtMillis = System.currentTimeMillis(),
                    errorCode = null
                )
            }
            quizAwaitPrewarm = true
            viewModelScope.launch {
                runCatching { prewarm.join() }
                // 等待期间用户点了取消或离开了这一页：不自动接着开题
                if (!quizAwaitPrewarm) return@launch
                quizAwaitPrewarm = false
                loadQuiz(_uiState.value.quizPreviewMovies, forceRefresh = false)
            }
            return
        }
        loadQuiz(preview, forceRefresh = false)
    }

    /**
     * 静默预生成当天这一套题。
     *
     * 进页面就把题备好，用户点「开始」时服务端题库已命中，不必等几分钟。预生成不扣用户额度、
     * 不计已玩套数（服务端 prefetch 分支），所以「猜错了用户不会玩」也不可惜。
     * 已有结果、已在跑、离线、未授权都直接返回 —— 它只是增强路径，失败要静默。
     */
    private fun scheduleQuizPrewarm(preview: List<AiWatchedTitleDto>) {
        val friendId = authManager.friendId.value.orEmpty()
        val day = currentDailyKnowledgeChangeDay()
        if (preview.size < QUIZ_PREVIEW_SIZE || friendId.isBlank()) {
            return
        }
        if (quizPreparedDay == day || quizPrewarmJob?.isActive == true) return
        if (!isNetworkAvailable() || !hasLiveAiSession()) return
        quizPreparedDay = null
        quizAwaitPrewarm = false
        updateQuizPrepareState(AiQuizPrepareState.PREPARING)
        val token = ++quizPrewarmToken
        val stillCurrent = { token == quizPrewarmToken }
        quizPrewarmJob = viewModelScope.launch {
            try {
                val enriched = enrichQuizPreviewMovies(preview)
                if (!isNetworkAvailable() || !hasLiveAiSession()) {
                    updateQuizPrepareState(AiQuizPrepareState.FAILED)
                    return@launch
                }
                var completed: AiQuiz? = null
                var dailySetsDone = false
                aiRepository.getQuizStream(
                    friendId,
                    quizStreamRequest(enriched, day, prefetch = true),
                    forceRefresh = false
                ).collect { event ->
                    if (!stillCurrent()) return@collect
                    when (event) {
                        is com.tracktosearch.data.ai.AiQuizStreamEvent.Completed -> completed = event.quiz
                        com.tracktosearch.data.ai.AiQuizStreamEvent.DailySetsDone -> dailySetsDone = true
                        else -> Unit
                    }
                    _uiState.update { state -> state.withQuizStreamProgress(event) }
                }
                if (!stillCurrent()) return@launch
                // 「当天该发的套都发过了」是当天的稳定结论（已玩套数只增不减），记下当天不再重试：
                // 再进页面也不用再问一次服务端。
                quizPreparedDay = if (completed != null || dailySetsDone) day else null
                updateQuizPrepareState(
                    when {
                        completed != null -> AiQuizPrepareState.READY
                        dailySetsDone -> AiQuizPrepareState.EXHAUSTED
                        else -> AiQuizPrepareState.FAILED
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 预生成失败不打扰用户：预览页会说明「点开始会现场生成」，点开始走冷路径
                if (stillCurrent()) updateQuizPrepareState(AiQuizPrepareState.FAILED)
            } finally {
                if (stillCurrent()) {
                    quizPrewarmJob = null
                    _uiState.update { it.copy(quizStage = null, quizStageChars = 0, quizStageExpectedChars = 0) }
                }
            }
        }
    }

    /** 丢弃预生成：Job 取消 + 结果作废。服务端生成不受客户端断开影响，不必补偿。 */
    private fun cancelQuizPrewarm() {
        quizPrewarmToken += 1L
        quizPrewarmJob?.cancel()
        quizPrewarmJob = null
        quizPreparedDay = null
        quizAwaitPrewarm = false
    }

    /** 生成态收敛：清进度并回 IDLE，需要时再单独置目标态。 */
    private fun updateQuizPrepareState(state: AiQuizPrepareState) {
        _uiState.update { it.copy(quizPrepareState = state) }
    }

    /**
     * 出题请求体：预生成与正式出题只差 [prefetch] 一个字段。
     *
     * 其余字段必须逐字一致 —— 服务端按 (用户, 日期, 套序号) 定 quizId、按片单定内容，
     * 客户端本地缓存键又由这套参数派生；只要有一处不同，预生成就白做。
     */
    private fun quizStreamRequest(
        watched: List<AiWatchedTitleDto>,
        day: String,
        prefetch: Boolean
    ) = com.tracktosearch.data.ai.AiQuizRequest(
        watched = watched,
        excludedQuizIds = recentQuizIds,
        questionCount = QUIZ_QUESTION_COUNT,
        sessionId = spriteSessionId,
        date = day,
        prefetch = prefetch
    )

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
            val friendId = authManager.friendId.value.orEmpty()
            // 片单按 (用户, 本地日期) 稳定：同一天怎么重进都是这 7 部，预生成才对得上
            val preview = selectDailyQuizPreview(
                candidates = watched,
                seed = quizDaySeed(friendId, currentDailyKnowledgeChangeDay()),
                count = QUIZ_PREVIEW_SIZE
            )
            val localizedTitles = resolveQuizPreviewLocalizedTitles(preview)
            if (!canContinueAiRequest(requestId)) return@runFeature
            updateIfCurrentRequest(requestId) {
                it.copy(
                    quiz = null,
                    quizResult = null,
                    quizStarted = false,
                    quizGenerating = false,
                    quizIndex = 0,
                    quizAnswers = emptyMap(),
                    quizPreviewMovies = preview,
                    quizPreviewLocalizedTitles = localizedTitles
                )
            }
            // 预览页一出来就把当天这一套题备好：用户看完考点直接点开始，不必现场等几分钟
            scheduleQuizPrewarm(preview)
        }
    }

    /** 出题片单种子：同 (用户, 本地日期) 恒定，与传服务端的日期同源。 */
    private fun quizDaySeed(friendId: String, day: String): String = "$friendId:$day"

    /**
     * 为最多 7 部预览影视补齐安全的 TMDB 依据字段。
     * 始终先 peek；详情富化只限定在本轮预览，并由 startQuiz 复用。
     */
    private suspend fun enrichQuizPreviewMovies(
        movies: List<AiWatchedTitleDto>
    ): List<AiWatchedTitleDto> {
        val preview = movies.take(QUIZ_PREVIEW_SIZE)
        val languageAtStart = languageStorage.language.value
        if (quizPreviewEnrichmentLanguage != languageAtStart) {
            quizPreviewEnrichmentLanguage = languageAtStart
            quizPreviewEnrichments = emptyMap()
        }

        val missing = preview.filter { movie ->
            !quizPreviewEnrichments.containsKey(quizPreviewTitleKey(movie))
        }
        if (missing.isNotEmpty()) {
            val networkAvailableAtStart = isNetworkAvailable()
            val resolved = withContext(ioDispatcher) {
                supervisorScope {
                    missing.map { movie ->
                        async {
                            quizPreviewTitleKey(movie) to enrichQuizMovie(movie, networkAvailableAtStart)
                        }
                    }.awaitAll().toMap()
                }
            }
            if (languageStorage.language.value == languageAtStart) {
                quizPreviewEnrichments = quizPreviewEnrichments + resolved
            } else {
                // 不保留两种语言混杂的富化结果。
                quizPreviewEnrichmentLanguage = null
                quizPreviewEnrichments = emptyMap()
            }
        }
        return preview.map { movie ->
            quizPreviewEnrichments[quizPreviewTitleKey(movie)]?.watched ?: movie
        }
    }

    private suspend fun enrichQuizMovie(
        movie: AiWatchedTitleDto,
        networkAvailableAtStart: Boolean
    ): QuizPreviewEnrichment {
        val tmdbId = movie.mediaIds.tmdbId?.takeIf { it > 0 }
            ?: return QuizPreviewEnrichment(movie, null)
        return try {
            val isShow = movie.mediaType.equals("show", ignoreCase = true) ||
                movie.mediaType.equals("tv", ignoreCase = true)
            if (isShow) {
                val enrichment = tmdbRepository.peekTvEnrichment(tmdbId, movie.title, movie.year)
                    ?: (if (networkAvailableAtStart) tmdbRepository.enrichTv(tmdbId, movie.title, movie.year) else null)
                    ?: return QuizPreviewEnrichment(movie, null)
                val localizedTitle = enrichment.chineseTitle.trim()
                    .takeIf { it.isNotBlank() && !it.equals(movie.title.trim(), ignoreCase = true) }
                val enrichedMovie = movie.copy(
                    // 保持 Trakt 标题不变；新增字段只作为 Worker 的补充事实依据。
                    overview = enrichment.overview.trim().ifBlank { movie.overview },
                    originalTitle = enrichment.originalTitle.trim().ifBlank { movie.originalTitle },
                    runtime = enrichment.episodeRunTime ?: movie.runtime,
                    country = enrichment.country.trim().ifBlank { movie.country },
                    genres = movie.genres.ifEmpty {
                        enrichment.genres.split(" · ").map(String::trim).filter(String::isNotBlank)
                    },
                    mediaIds = movie.mediaIds.copy(
                        imdbId = movie.mediaIds.imdbId ?: enrichment.imdbId
                    )
                )
                QuizPreviewEnrichment(enrichedMovie, localizedTitle)
            } else {
                val enrichment = tmdbRepository.peekMovieEnrichment(tmdbId, movie.title, movie.year)
                    ?: (if (networkAvailableAtStart) tmdbRepository.enrichMovie(tmdbId, movie.title, movie.year) else null)
                    ?: return QuizPreviewEnrichment(movie, null)
                val localizedTitle = enrichment.chineseTitle.trim()
                    .takeIf { it.isNotBlank() && !it.equals(movie.title.trim(), ignoreCase = true) }
                val enrichedMovie = movie.copy(
                    // 保持 Trakt 标题不变；新增字段只作为 Worker 的补充事实依据。
                    overview = enrichment.overview.trim().ifBlank { movie.overview },
                    originalTitle = enrichment.originalTitle.trim().ifBlank { movie.originalTitle },
                    runtime = enrichment.runtime ?: movie.runtime,
                    country = enrichment.country.trim().ifBlank { movie.country },
                    genres = movie.genres.ifEmpty {
                        enrichment.genres.split(" · ").map(String::trim).filter(String::isNotBlank)
                    },
                    mediaIds = movie.mediaIds.copy(
                        imdbId = movie.mediaIds.imdbId ?: enrichment.imdbId
                    )
                )
                QuizPreviewEnrichment(enrichedMovie, localizedTitle)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 详情缺失不能阻断答题，也不能凭空制造依据。
            QuizPreviewEnrichment(movie, null)
        }
    }

    private suspend fun resolveQuizPreviewLocalizedTitles(
        movies: List<AiWatchedTitleDto>
    ): Map<String, String> {
        enrichQuizPreviewMovies(movies)
        return if (languageStorage.language.value == quizPreviewEnrichmentLanguage) {
            movies.take(QUIZ_PREVIEW_SIZE).mapNotNull { movie ->
                quizPreviewEnrichments[quizPreviewTitleKey(movie)]?.localizedTitle?.let {
                    quizPreviewTitleKey(movie) to it
                }
            }.toMap()
        } else {
            emptyMap()
        }
    }

    private data class QuizPreviewEnrichment(
        val watched: AiWatchedTitleDto,
        val localizedTitle: String?
    )

    private fun quizPreviewTitleKey(movie: AiWatchedTitleDto): String =
        quizMediaKey(movie.mediaType, movie.mediaId)

    private fun loadQuiz(watched: List<AiWatchedTitleDto>, forceRefresh: Boolean) {
        runFeature(AiFeature.QUIZ) { requestId ->
            if (watched.size < QUIZ_PREVIEW_SIZE) throw IllegalStateException("NOT_ENOUGH_MOVIES")
            if (!canContinueAiRequest(requestId)) return@runFeature
            val enrichedWatched = enrichQuizPreviewMovies(watched)
            if (!canContinueAiRequest(requestId)) return@runFeature
            val day = currentDailyKnowledgeChangeDay()
            updateIfCurrentRequest(requestId) {
                it.copy(
                    quizRequestStartedAtMillis = System.currentTimeMillis(),
                    // 等待页立刻可见：生成中不再只是把「开始」按钮灰掉、什么都不说
                    quizGenerating = true
                )
            }
            // 流式出题：两阶段实测 280~420s，服务端每 10s 至少写一行事件（真实进度或心跳），
            // 等待页因此能显示「提炼单元 → 逐题生成」的真实阶段与进度，而不是干转圈。
            aiRepository.getQuizStream(
                authManager.friendId.value.orEmpty(),
                quizStreamRequest(enrichedWatched, day, prefetch = false),
                forceRefresh
            ).collect { event ->
                if (event is com.tracktosearch.data.ai.AiQuizStreamEvent.Completed) {
                    if (canContinueAiRequest(requestId)) applyQuiz(event.quiz)
                    return@collect
                }
                updateIfCurrentRequest(requestId) { state -> state.withQuizStreamProgress(event) }
            }
        }
    }

    /**
     * 把一套题送进答题态。现场生成与预生成命中都走这里，保证状态收敛方式只有一种。
     */
    private fun applyQuiz(quiz: AiQuiz) {
        recentQuizIds = (listOf(quiz.quizId) + recentQuizIds).take(3)
        // 这一套已经玩上了：预生成结果与「已就绪」标记都不再对应当前预览页
        quizPreparedDay = null
        _uiState.update {
            it.copy(
                quiz = quiz,
                quizStarted = true,
                quizIndex = 0,
                quizAnswers = emptyMap(),
                quizResult = null,
                quota = quiz.quota ?: it.quota,
                quizStage = null,
                quizStageChars = 0,
                quizStageExpectedChars = 0,
                quizGenerating = false,
                quizPrepareState = AiQuizPrepareState.IDLE
            )
        }
    }

    /** 阶段/进度事件统一写进等待页字段：预生成与现场生成共用一套语义。 */
    private fun AiSpriteUiState.withQuizStreamProgress(
        event: com.tracktosearch.data.ai.AiQuizStreamEvent
    ): AiSpriteUiState = when (event) {
        is com.tracktosearch.data.ai.AiQuizStreamEvent.Stage -> copy(
            quizStage = event.stage,
            quizStageExpectedChars = if (event.status == com.tracktosearch.data.ai.AiQuizStageStatus.START) {
                event.expectedChars
            } else {
                0
            },
            // 阶段重新开始（含修复轮）时进度归零，避免进度条停在上一轮的位置
            quizStageChars = if (event.status == com.tracktosearch.data.ai.AiQuizStageStatus.START) {
                0
            } else {
                quizStageChars
            }
        )
        is com.tracktosearch.data.ai.AiQuizStreamEvent.Progress -> copy(
            quizStage = event.stage,
            quizStageChars = event.chars
        )
        else -> this
    }

    private fun loadDaily(
        forceRefresh: Boolean = false,
        allowOfflineCache: Boolean = false
    ) {
        runFeature(
            feature = AiFeature.DAILY,
            allowOfflineCache = !forceRefresh && allowOfflineCache
        ) { requestId ->
            val friendId = authManager.friendId.value.orEmpty()
            val locale = currentDailyKnowledgeLocale()
            val shownDate = currentDailyKnowledgeChangeDay()
            val history = aiRepository.readDailyKnowledgeHistory(friendId)
            val todayRecords = history.filter {
                it.shownDate == shownDate && it.locale == locale
            }
            val historyChangeCount = (todayRecords.size - 1).coerceIn(0, 2)
            val latestRecord = todayRecords.firstOrNull()

            // 当天已有真正展示过的内容时直接复用历史：内容稳定、离线可读，也少一次网络请求。
            if (!forceRefresh && latestRecord != null) {
                if (!canContinueAiRequest(requestId, allowOfflineCache)) return@runFeature
                val latestKnowledge = latestRecord.knowledge
                updateIfCurrentRequest(requestId) { state ->
                    applyDailyKnowledgeRecord(state, latestRecord, historyChangeCount)
                        .copy(dailyMediaImageUrl = null, dailyStage = null, dailyStageChars = 0)
                }
                persistDailyKnowledgeChangeCount(historyChangeCount)
                onDailyKnowledgeContentSet(latestKnowledge, requestId, history)
                return@runFeature
            }

            // 流式加载：先清掉上一轮的阶段态并记开始时刻，让加载页从一开始就有真实阶段与时长
            val startedAtMillis = System.currentTimeMillis()
            updateIfCurrentRequest(requestId) { state ->
                state.copy(
                    dailyStage = null,
                    dailyStageChars = 0,
                    dailyStageExpectedChars = 0,
                    dailyRequestStartedAtMillis = startedAtMillis
                )
            }
            aiRepository.getDailyStream(
                friendId = friendId,
                watched = watchedTitles(),
                forceRefresh = forceRefresh,
                locale = locale
            ).collect { event ->
                when (event) {
                    is AiDailyStreamEvent.Stage -> updateIfCurrentRequest(requestId) { state ->
                        state.copy(
                            dailyStage = event.stage,
                            dailyStageExpectedChars = event.expectedChars,
                            // 阶段重新开始（含修复轮）时进度归零，避免进度条停在上一轮的位置
                            dailyStageChars = if (event.status == AiDailyStageStatus.START) 0 else state.dailyStageChars
                        )
                    }
                    is AiDailyStreamEvent.Progress -> updateIfCurrentRequest(requestId) { state ->
                        state.copy(dailyStage = event.stage, dailyStageChars = event.chars)
                    }
                    AiDailyStreamEvent.Ping -> Unit
                    is AiDailyStreamEvent.Completed -> {
                        if (!canContinueAiRequest(requestId, !forceRefresh && allowOfflineCache)) {
                            return@collect
                        }
                        val daily = event.daily
                        updateIfCurrentRequest(requestId) { state ->
                            val nextChangeCount = if (forceRefresh) {
                                (maxOf(state.dailyKnowledgeChangeCount, historyChangeCount) + 1).coerceAtMost(2)
                            } else {
                                maxOf(state.dailyKnowledgeChangeCount, historyChangeCount)
                            }
                            if (forceRefresh) persistDailyKnowledgeChangeCount(nextChangeCount)
                            state.copy(
                                dailyKnowledge = daily,
                                dailyKnowledgeChangeCount = nextChangeCount,
                                dailyQuestionSelectedOptionId = null,
                                dailyKnowledgeFeedback = null,
                                dailyKnowledgeDifficultyFeedback = null,
                                dailyMediaImageUrl = null,
                                dailyStage = null,
                                dailyStageChars = 0,
                                dailyStageExpectedChars = 0,
                                quota = daily.quota ?: state.quota
                            )
                        }
                        onDailyKnowledgeContentSet(daily, requestId, history)
                    }
                }
            }
        }
    }

    private fun applyDailyKnowledgeRecord(
        state: AiSpriteUiState,
        record: AiDailyKnowledgeHistoryRecord,
        changeCount: Int
    ): AiSpriteUiState = state.copy(
        dailyKnowledge = record.knowledge,
        dailyKnowledgeChangeCount = changeCount,
        dailyQuestionSelectedOptionId = record.questionResult?.selectedOptionIds?.firstOrNull(),
        dailyKnowledgeFeedback = record.contentFeedback,
        dailyKnowledgeDifficultyFeedback = record.difficultyFeedback,
        quota = record.knowledge.quota ?: state.quota
    )

    /** 内容落地后的插图/剧照/历史联动；requestId 失效后不写任何状态。 */
    private fun onDailyKnowledgeContentSet(
        daily: AiDailyKnowledge,
        requestId: Long,
        history: List<AiDailyKnowledgeHistoryRecord>
    ) {
        scheduleDailyStillLookup(daily, requestId)
        scheduleDailyIllustrationRefresh(daily, requestId)
        updateIfCurrentRequest(requestId) { it.copy(dailyKnowledgeHistory = history) }
    }

    /**
     * 概念插图静默刷新：Worker 后台生图完成后，页面最多补拉一次拿到 ready 状态。
     * 用户已在答题（插图就绪会改变布局）或离开页面时跳过，遵守设计的
     * “最多静默刷新一次”与“答题时不插入图片”。
     */
    private fun scheduleDailyIllustrationRefresh(daily: AiDailyKnowledge, requestId: Long) {
        dailyIllustrationJob?.cancel()
        dailyIllustrationJob = null
        val status = daily.illustration?.status ?: return
        if (status != "generating") return
        val unitKey = dailyKnowledgeHistoryKey(daily)
        if (unitKey.isBlank() || unitKey in dailyIllustrationRefreshedUnitIds) return
        dailyIllustrationRefreshedUnitIds += unitKey
        dailyIllustrationJob = viewModelScope.launch {
            delay(DAILY_ILLUSTRATION_REFRESH_DELAY_MS)
            if (requestGeneration != requestId) return@launch
            if (_uiState.value.activeFeature != AiFeature.DAILY) return@launch
            if (_uiState.value.dailyQuestionSelectedOptionId != null) return@launch
            if (!isNetworkAvailable() || !hasLiveAiSession()) return@launch
            val current = _uiState.value.dailyKnowledge ?: return@launch
            if (dailyKnowledgeHistoryKey(current) != unitKey) return@launch
            aiRepository.refreshDailyKnowledge(
                friendId = authManager.friendId.value.orEmpty(),
                watched = watchedTitles(),
                locale = current.locale
            ).onSuccess { refreshed ->
                if (requestGeneration != requestId) return@onSuccess
                if (refreshed.illustration?.isReady != true) return@onSuccess
                _uiState.update { state ->
                    if (dailyKnowledgeHistoryKey(state.dailyKnowledge) != unitKey) state
                    else state.copy(
                        dailyKnowledge = state.dailyKnowledge?.copy(illustration = refreshed.illustration)
                    )
                }
            }
        }
    }

    /** 影视依据区配图：TMDB backdrop 优先，海报兜底；拿不到保持 null 走占位图标。 */
    private fun scheduleDailyStillLookup(daily: AiDailyKnowledge, requestId: Long) {
        dailyStillJob?.cancel()
        dailyStillJob = null
        val media = daily.relatedMedia ?: return
        val tmdbId = media.tmdbId?.takeIf { it > 0 } ?: return
        val isShow = media.mediaType.equals("show", ignoreCase = true) ||
            media.mediaType.equals("tv", ignoreCase = true)
        dailyStillJob = viewModelScope.launch {
            val url = withContext(ioDispatcher) {
                runCatching {
                    val backdrops = if (isShow) {
                        tmdbRepository.getTvImages(tmdbId)
                    } else {
                        tmdbRepository.getMovieImages(tmdbId)
                    }
                    val backdropPath = backdrops.firstOrNull()?.file_path?.takeIf { it.isNotBlank() }
                    if (backdropPath != null) {
                        com.tracktosearch.data.remote.tmdb.TmdbImageUrls.build(backdropPath, com.tracktosearch.data.remote.tmdb.TmdbImageUrls.W780)
                    } else {
                        val poster = tmdbRepository.posterPath(
                            tmdbId,
                            if (isShow) com.tracktosearch.data.repository.MediaType.SHOW
                            else com.tracktosearch.data.repository.MediaType.MOVIE
                        )
                        poster?.let {
                            com.tracktosearch.data.remote.tmdb.TmdbImageUrls.build(it, com.tracktosearch.data.remote.tmdb.TmdbImageUrls.W342)
                        }
                    }
                }.getOrNull()
            }
            if (requestGeneration == requestId && url != null) {
                _uiState.update { state ->
                    if (dailyKnowledgeHistoryKey(state.dailyKnowledge) != dailyKnowledgeHistoryKey(daily)) state
                    else state.copy(dailyMediaImageUrl = url)
                }
            }
        }
    }

    /** 离线加载最近学过列表；只读，失败静默保持旧值。 */
    private fun loadDailyKnowledgeHistory() {
        val friendId = authManager.friendId.value.orEmpty()
        if (friendId.isBlank()) return
        val generation = privateStateGeneration
        dailyHistoryJob?.cancel()
        dailyHistoryJob = viewModelScope.launch {
            val records = aiRepository.readDailyKnowledgeHistory(friendId)
            if (canReadPrivateState(friendId, generation)) {
                _uiState.update { it.copy(dailyKnowledgeHistory = records) }
            }
        }
    }

    /** 历史回看：把某天的旧知识装回每日视图，答题与反馈状态随记录恢复，不消耗换题额度。 */
    fun openDailyKnowledgeRecord(record: AiDailyKnowledgeHistoryRecord) {
        val requestId = beginRequest()
        requestJob = viewModelScope.launch {
            updateIfCurrentRequest(requestId) { state ->
                applyDailyKnowledgeRecord(state, record, state.dailyKnowledgeChangeCount)
            }
        }
        scheduleDailyStillLookup(record.knowledge, requestId)
        dailyIllustrationJob?.cancel()
        dailyIllustrationJob = null
    }

    private fun dailyKnowledgeHistoryKey(daily: AiDailyKnowledge?): String =
        daily?.unitId?.trim()?.takeIf { it.isNotEmpty() } ?: daily?.id?.trim().orEmpty()

    private fun currentDailyKnowledgeLocale(): String {
        val systemLanguage = runCatching {
            context.resources.configuration.locales[0]?.language.orEmpty()
        }.getOrDefault("")
        return dailyKnowledgeLocale(languageStorage.language.value, systemLanguage)
    }

    private fun currentDailyKnowledgeChangeDay(): String = LocalDate.now(BEIJING_ZONE).toString()

    private fun restoreDailyKnowledgeChangeCount(): Int {
        if (savedStateHandle.get<String>(KEY_DAILY_KNOWLEDGE_CHANGE_DAY) != currentDailyKnowledgeChangeDay()) {
            return 0
        }
        return savedStateHandle.get<Int>(KEY_DAILY_KNOWLEDGE_CHANGE_COUNT)
            ?.coerceIn(0, 2)
            ?: 0
    }

    /** ViewModel 跨零点仍存活时也要按新的一天重置，不能沿用昨天的两次额度。 */
    private fun normalizeDailyKnowledgeChangeCountForToday() {
        val today = currentDailyKnowledgeChangeDay()
        if (savedStateHandle.get<String>(KEY_DAILY_KNOWLEDGE_CHANGE_DAY) == today) return
        savedStateHandle[KEY_DAILY_KNOWLEDGE_CHANGE_DAY] = today
        savedStateHandle[KEY_DAILY_KNOWLEDGE_CHANGE_COUNT] = 0
        _uiState.update { it.copy(dailyKnowledgeChangeCount = 0) }
    }

    private fun persistDailyKnowledgeChangeCount(count: Int) {
        savedStateHandle[KEY_DAILY_KNOWLEDGE_CHANGE_DAY] = currentDailyKnowledgeChangeDay()
        savedStateHandle[KEY_DAILY_KNOWLEDGE_CHANGE_COUNT] = count
    }

    private fun runFeature(
        feature: AiFeature,
        allowOfflineCache: Boolean = false,
        block: suspend (Long) -> Unit
    ) {
        if (!requireAiAccess(allowOfflineCache)) return
        val requestId = beginRequest()
        requestJob = viewModelScope.launch {
            try {
                if (!canContinueAiRequest(requestId, allowOfflineCache)) return@launch
                updateIfCurrentRequest(requestId) {
                    it.copy(isLoading = true, loadingFeature = feature, errorCode = null)
                }
                if (!canContinueAiRequest(requestId, allowOfflineCache)) return@launch
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
            val moviesDeferred = async { traktRepository.getAllMovieHistory(extended = "full") }
            val showsDeferred = async { traktRepository.getAllShowHistory(extended = "full") }
            val movies = moviesDeferred.await().getOrNull().orEmpty().map { it.toAiWatched() }
            val shows = showsDeferred.await().getOrNull().orEmpty().map { it.toAiWatched() }
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

    private companion object {
        const val KEY_DAILY_KNOWLEDGE_CHANGE_DAY = "daily_knowledge_change_day"
        const val KEY_DAILY_KNOWLEDGE_CHANGE_COUNT = "daily_knowledge_change_count"
        /** 插图后台生成一般在请求后几十秒内完成；等 25 秒补拉一次足以覆盖首屏会话。 */
        const val DAILY_ILLUSTRATION_REFRESH_DELAY_MS = 25_000L
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
