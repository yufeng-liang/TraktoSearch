package com.tracktosearch.ui.screen.ai

import androidx.annotation.StringRes
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiDailyCheckQuestion
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizQuestionResult
import com.tracktosearch.data.ai.AiQuizStage
import com.tracktosearch.data.ai.AiDailyStage
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.ai.AiTtsScene
import com.tracktosearch.data.ai.AiTtsRequest
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.ai.AiWatchedTitleDto
import java.util.Locale
import kotlin.math.log10

private const val MAX_VOICE_ACTIVATION_ATTEMPTS = 5
private const val MAX_TEXT_ACTIVATION_ATTEMPTS = 5
private const val MAX_OVERLAY_SESSION_COUNT = 3
private const val MAX_OVERLAY_DAILY_COUNT = 6
private const val IDLE_TRIGGER_DELAY_MS = 8_000L
/** 每套题涉及几部影视：客户端预览、服务端门槛与出题片单都是 7，改要一起改。 */
const val QUIZ_MOVIE_COUNT = 7
// 出题两阶段在总进度中的权重：units 实测约 140s、review 约 250s
private const val QUIZ_UNITS_PROGRESS_WEIGHT = 0.35f
// 今日知识两阶段实测耗时接近（候选约 6s、复核约 7s），各占一半
private const val DAILY_CANDIDATE_PROGRESS_WEIGHT = 0.5f
private const val MAX_DAILY_KNOWLEDGE_CHANGES = 2

/** 探头静止观察前的空闲等待时长，两个搜索页都用这个常量，别再各写一遍 8_000L。 */
const val AI_SPRITE_IDLE_DELAY_MS = IDLE_TRIGGER_DELAY_MS

/**
 * 探头是否真的可以起来。
 *
 * 两个搜索页原来各抄了一套条件并且已经漂移：Trakt 搜索页漏了「锚点还没量到」这一条，
 * 于是 anchorBounds 为 null 时也会消费一次展示额度，用户什么都没看到，
 * 而 showAiSpriteMotion 又因为 visible 一直是 false 拿不到 onFinished 回调，
 * 在用户下一次交互前这一页不会再出探头。统一收到这里。
 */
fun shouldStartSpriteOverlay(
    trigger: AiSpriteOverlayTrigger,
    hasBlockingState: Boolean,
    hasAnchorBounds: Boolean,
    hasResultAnchor: Boolean,
    motionVisible: Boolean
): Boolean =
    !hasBlockingState &&
        hasAnchorBounds &&
        !motionVisible &&
        (trigger != AiSpriteOverlayTrigger.SEARCH_COMPLETED || hasResultAnchor)

/**
 * 错误码到用户可读文案的映射。
 *
 * 之前所有错误码都渲染成同一句「精灵正在休息」，用户看不出到底是片单不够、
 * 配额用完还是名字没喊对，只能反复点重试。这里把 ViewModel 的本地码和
 * AiErrorCode 的服务端码合到一张表上，各自给出能指导下一步动作的文案。
 */
@StringRes
fun aiErrorMessageRes(errorCode: String?): Int = when (errorCode) {
    null -> R.string.ai_error
    // 本地码
    "AUTH_REQUIRED" -> R.string.ai_error_auth_required
    "ACTIVATION_RETRY_LIMIT" -> R.string.ai_error_activation_retry_limit
    "ACTIVATION_UNAVAILABLE" -> R.string.ai_error_character_unavailable
    "ACTIVATION_NAME_EMPTY" -> R.string.ai_error_name_empty
    "ACTIVATION_NOT_MATCHED" -> R.string.ai_error_activation_not_matched
    // 按住失败分档：麦克风没拾到、声音太轻、按得太短，三种情况的下一步动作完全不同
    "ACTIVATION_TOO_SHORT" -> R.string.ai_error_activation_too_short
    "ACTIVATION_SILENT" -> R.string.ai_error_activation_silent
    "ACTIVATION_TOO_QUIET" -> R.string.ai_error_activation_too_quiet
    "AUDIO_UNAVAILABLE" -> R.string.ai_error_audio_unavailable
    // 刚授予麦克风权限：借错误位提示「再按一次」，避免自动开录把用户吓一跳
    "AUDIO_PERMISSION_GRANTED" -> R.string.ai_error_audio_permission_granted
    "NOT_ENOUGH_MOVIES" -> R.string.ai_error_not_enough_movies
    "WATCHED_LIST_EMPTY" -> R.string.ai_error_watched_list_empty
    // 离线是功能页的内联状态，不应显示成泛化的服务器错误或遮罩弹窗
    "OFFLINE" -> R.string.ai_feature_unavailable
    "CHARACTERS_LOAD_FAILED" -> R.string.ai_error_characters_failed
    // AiErrorCode.name
    "UNAUTHORIZED" -> R.string.ai_error_unauthorized
    "QUOTA_EXCEEDED" -> R.string.ai_error_quota_exceeded
    "RATE_LIMITED" -> R.string.ai_error_rate_limited
    "CHARACTER_UNAVAILABLE" -> R.string.ai_error_character_unavailable
    "ACTIVATION_REQUIRED" -> R.string.ai_error_activation_required
    "INVALID_REQUEST" -> R.string.ai_error_invalid_request
    "INVALID_RESPONSE" -> R.string.ai_error_invalid_response
    "NETWORK" -> R.string.ai_error_network
    "SERVER" -> R.string.ai_error_server
    else -> R.string.ai_error
}

/**
 * 重试按钮是否有意义。
 *
 * 片单不够、配额用完、授权失效这几类靠重试永远解决不了，
 * 继续给重试按钮只会让用户白点并多烧一次请求。
 * 按住失败的四个码（TOO_SHORT / SILENT / TOO_QUIET / AUDIO_PERMISSION_GRANTED）
 * 都是「再按一次就能解决」，走 else 分支拿到 true 即可，不必单列。
 */
fun aiErrorIsRetryable(errorCode: String?): Boolean = when (errorCode) {
    "AUTH_REQUIRED",
    "UNAUTHORIZED",
    "QUOTA_EXCEEDED",
    "NOT_ENOUGH_MOVIES",
    "WATCHED_LIST_EMPTY",
    "OFFLINE",
    "CHARACTER_UNAVAILABLE",
    "ACTIVATION_UNAVAILABLE",
    "ACTIVATION_RETRY_LIMIT",
    "ACTIVATION_REQUIRED" -> false
    else -> true
}

private val overlayTriggerCooldowns = mapOf(
    AiSpriteOverlayTrigger.FIRST_ENTRY to 0L,
    AiSpriteOverlayTrigger.SEARCH_COMPLETED to 3_000L,
    AiSpriteOverlayTrigger.IDLE to 90_000L
)

const val AI_DOUBAN_NAV_PREFIX = "ai-douban:"

enum class AiSpriteOverlayTrigger {
    FIRST_ENTRY,
    SEARCH_COMPLETED,
    IDLE
}

enum class AiAuditionPlaybackRoute {
    GUEST_TTS,
    AUTHORIZED_TTS,
    SYSTEM_TTS
}

fun buildAuditionTtsRequest(character: AiCharacter, sessionId: String): AiTtsRequest =
    AiTtsRequest(
        characterId = character.id,
        text = character.auditionText,
        sessionId = sessionId,
        scene = AiTtsScene.AUDITION
    )

fun auditionPlaybackRoute(isAuthorized: Boolean, isAvailable: Boolean): AiAuditionPlaybackRoute = when {
    !isAvailable -> AiAuditionPlaybackRoute.SYSTEM_TTS
    isAuthorized -> AiAuditionPlaybackRoute.AUTHORIZED_TTS
    else -> AiAuditionPlaybackRoute.GUEST_TTS
}

data class AiSpriteOverlayBudget(
    val sessionShown: Int = 0,
    val dailyShown: Int = 0
) {
    fun canShow(): Boolean =
        sessionShown < MAX_OVERLAY_SESSION_COUNT && dailyShown < MAX_OVERLAY_DAILY_COUNT

    fun consume(): AiSpriteOverlayBudget =
        if (canShow()) copy(sessionShown = sessionShown + 1, dailyShown = dailyShown + 1) else this
}

/** 搜索页浮层只消费一次展示额度，触发源不负责自己维护计数。 */
class AiSpriteOverlayPolicy(
    private val readDailyCount: (String) -> Int = { 0 },
    private val writeDailyCount: (String, Int) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private var dayKey: String? = null
    private var budget = AiSpriteOverlayBudget()
    private val lastConsumedAt = mutableMapOf<AiSpriteOverlayTrigger, Long>()

    fun tryConsume(
        activated: Boolean,
        trigger: AiSpriteOverlayTrigger,
        currentDayKey: String
    ): Boolean {
        if (!activated || trigger !in AiSpriteOverlayTrigger.entries) return false
        if (dayKey != currentDayKey) {
            dayKey = currentDayKey
            budget = budget.copy(dailyShown = readDailyCount(currentDayKey).coerceAtLeast(0))
        }
        val now = clock()
        val lastTime = lastConsumedAt[trigger]
        val cooldown = overlayTriggerCooldowns[trigger] ?: 0L
        if (lastTime != null && (now < lastTime || now - lastTime < cooldown)) return false
        if (!budget.canShow()) return false
        budget = budget.consume()
        lastConsumedAt[trigger] = now
        writeDailyCount(currentDayKey, budget.dailyShown)
        return true
    }
}

/**
 * 语音激活共 5 次机会（首次 + 4 次重试）。
 *
 * 主按钮在次数用满前一直是语音，不再"第一次失败就把主按钮换成文字"——
 * 那种设计让用户没法重试语音，且文字按钮直接拿角色预设名提交，用户根本没输入的机会。
 * 成功后也不会进入持续监听。
 */
fun canRequestVoiceActivation(attempt: Int): Boolean = attempt < MAX_VOICE_ACTIVATION_ATTEMPTS

/** 文字兜底同样限 5 次，避免输入框被反复提交刷服务端配额。 */
fun canRequestTextActivation(attempt: Int): Boolean = attempt < MAX_TEXT_ACTIVATION_ATTEMPTS

/** 按住时长下限：短于此按误触处理，不识别也不计次数。 */
const val VOICE_HOLD_MIN_DURATION_MS = 500L

/** 按住上限：到点自动收尾并按命中/未命中提交。 */
const val VOICE_HOLD_MAX_DURATION_MS = 10_000L

/** 峰值电平低于此值认为全程没有人声。 */
const val VOICE_SILENCE_LEVEL = 0.04f

/** 峰值电平低于此值认为有声但太轻，识别本来就不可能成。 */
const val VOICE_TOO_QUIET_LEVEL = 0.12f

/** 电平底噪线：安静房间的本底大约就在 -50 dBFS，再往下画竖条画的只是噪声。 */
private const val VOICE_LEVEL_FLOOR_DB = -50f

/** 上升系数：一帧走掉 60% 落差，开口那一瞬间竖条就立起来。 */
private const val VOICE_LEVEL_ATTACK = 0.6f

/** 下降系数：一帧只走 20% 落差，字与字之间的停顿不会让竖条抽回去。 */
private const val VOICE_LEVEL_RELEASE = 0.2f

enum class AiVoiceHoldOutcome { MATCHED, UNAVAILABLE, TOO_SHORT, SILENT, TOO_QUIET, NOT_MATCHED }

/**
 * 按住收尾判定，判定顺序即优先级。
 *
 * 命中优先于一切，包括按不到 500 ms 就松手：用户已经喊对了，没道理因为手快再罚一次。
 * 其次才是设备不可用、时长、电平三档。
 *
 * 分出静音与太轻两档是为了让失败可行动——以前三种失败都只回一句「名字没对上」，
 * 麦克风被别的应用占着的用户会一直以为是自己发音不准，直到 5 次机会全烧完。
 * 喊了别的角色名会落到电平检查这一段，声音够大就判 NOT_MATCHED，
 * 这是有意的：那种情况下用户确实喊错了名字。
 */
fun resolveVoiceHoldOutcome(
    holdDurationMs: Long,
    peakLevel: Float,
    matchedCharacterId: String?,
    selectedCharacterId: String,
    captureUnavailable: Boolean
): AiVoiceHoldOutcome = when {
    matchedCharacterId == selectedCharacterId -> AiVoiceHoldOutcome.MATCHED
    captureUnavailable -> AiVoiceHoldOutcome.UNAVAILABLE
    holdDurationMs < VOICE_HOLD_MIN_DURATION_MS -> AiVoiceHoldOutcome.TOO_SHORT
    peakLevel < VOICE_SILENCE_LEVEL -> AiVoiceHoldOutcome.SILENT
    peakLevel < VOICE_TOO_QUIET_LEVEL -> AiVoiceHoldOutcome.TOO_QUIET
    else -> AiVoiceHoldOutcome.NOT_MATCHED
}

/**
 * 该结果要不要烧掉一次语音激活机会。
 *
 * 计数点从「按下」挪到「实际提交」：误触和拿不到麦克风都没送出任何请求，不该消耗机会。
 * 静音反而要计次，否则对着关掉的麦克风可以无限重试，5 次上限就形同虚设。
 * 这里刻意不写 else，将来加档位时编译器会逼着做一次「烧不烧次数」的决定。
 */
fun voiceHoldOutcomeCountsAsAttempt(outcome: AiVoiceHoldOutcome): Boolean = when (outcome) {
    AiVoiceHoldOutcome.UNAVAILABLE,
    AiVoiceHoldOutcome.TOO_SHORT -> false
    AiVoiceHoldOutcome.MATCHED,
    AiVoiceHoldOutcome.SILENT,
    AiVoiceHoldOutcome.TOO_QUIET,
    AiVoiceHoldOutcome.NOT_MATCHED -> true
}

/** 该结果对应的本地错误码；MATCHED 返回 null，那条路走的是激活成功链路而不是错误位。 */
fun voiceHoldOutcomeErrorCode(outcome: AiVoiceHoldOutcome): String? = when (outcome) {
    AiVoiceHoldOutcome.MATCHED -> null
    AiVoiceHoldOutcome.UNAVAILABLE -> "AUDIO_UNAVAILABLE"
    AiVoiceHoldOutcome.TOO_SHORT -> "ACTIVATION_TOO_SHORT"
    AiVoiceHoldOutcome.SILENT -> "ACTIVATION_SILENT"
    AiVoiceHoldOutcome.TOO_QUIET -> "ACTIVATION_TOO_QUIET"
    AiVoiceHoldOutcome.NOT_MATCHED -> "ACTIVATION_NOT_MATCHED"
}

/**
 * PCM 块的线性 RMS 换成 0..1 电平：-50 dBFS 及以下为 0，0 dBFS 为 1。
 *
 * 走 dB 而不是直接拿 RMS 当高度，是因为线性值下正常说话只有 0.05 上下，
 * 竖条几乎贴着底走，用户会以为麦克风没拾到音。
 * rms 小于等于 0 必须先挡掉：log10(0) 是负无穷，一路传到 Canvas 就是一排 NaN 竖条。
 */
fun normalizeVoiceLevel(rms: Float): Float {
    if (rms <= 0f) return 0f
    val dbfs = 20f * log10(rms)
    return ((dbfs - VOICE_LEVEL_FLOOR_DB) / -VOICE_LEVEL_FLOOR_DB).coerceIn(0f, 1f)
}

/**
 * 电平平滑：起快落慢。
 *
 * 一帧约 100 ms，直接画原始电平会被字与字之间的停顿抽成锯齿。
 * 上升取 0.6 保证开口即可见，下降只取 0.2 让竖条缓缓落回去。
 * 两端各夹一次 0..1，采集层给出越界值时也不会把竖条画出格子。
 */
fun smoothVoiceLevel(previous: Float, next: Float): Float {
    val from = previous.coerceIn(0f, 1f)
    val to = next.coerceIn(0f, 1f)
    val factor = if (to > from) VOICE_LEVEL_ATTACK else VOICE_LEVEL_RELEASE
    return (from + (to - from) * factor).coerceIn(0f, 1f)
}

/** 用户手输的角色名先去掉首尾空白再比对，避免输入法带的空格造成必然失败。 */
fun normalizeSpokenName(input: String): String = input.trim()

fun isTextActivationInputValid(input: String): Boolean = normalizeSpokenName(input).isNotEmpty()

fun shouldResetActivationAttempt(currentSelectedCharacterId: String, nextCharacterId: String): Boolean =
    currentSelectedCharacterId != nextCharacterId

fun nextAiSpriteOverlayTrigger(
    entryHandled: Boolean,
    wasSearchLoading: Boolean,
    isSearchLoading: Boolean,
    hasResults: Boolean,
    isSearchFocused: Boolean,
    searchQuery: String,
    activated: Boolean,
    nowMs: Long = 0L,
    idleForMs: Long = 0L,
    hasBlockingOverlay: Boolean = false
): AiSpriteOverlayTrigger? {
    if (!activated) return null
    return when {
        !entryHandled -> AiSpriteOverlayTrigger.FIRST_ENTRY
        wasSearchLoading && !isSearchLoading && hasResults -> AiSpriteOverlayTrigger.SEARCH_COMPLETED
        shouldTriggerIdle(
            isSearchFocused = isSearchFocused,
            searchQuery = searchQuery,
            isSearchLoading = isSearchLoading,
            hasBlockingOverlay = hasBlockingOverlay,
            idleForMs = idleForMs,
            nowMs = nowMs
        ) -> AiSpriteOverlayTrigger.IDLE
        else -> null
    }
}

fun shouldTriggerIdle(
    isSearchFocused: Boolean,
    searchQuery: String,
    isSearchLoading: Boolean,
    hasBlockingOverlay: Boolean,
    idleForMs: Long,
    nowMs: Long
): Boolean =
    nowMs >= 0L &&
        idleForMs >= IDLE_TRIGGER_DELAY_MS &&
        !isSearchFocused &&
        searchQuery.isBlank() &&
        !isSearchLoading &&
        !hasBlockingOverlay

fun canActivateCharacter(character: AiCharacter, state: AiSpriteUiState): Boolean =
    character.isAvailable &&
        state.isAiAvailable &&
        state.activatedCharacterId != character.id &&
        state.activationState != AiActivationState.RECORDING &&
        state.activationState != AiActivationState.VERIFYING &&
        state.activationState != AiActivationState.SUCCESS &&
        canRequestVoiceActivation(state.activationAttempt)

/**
 * 文字激活入口是否展示：授权过、文字次数没用满、该角色还没激活，三条都满足就一直在。
 *
 * 原来还要求 state 里锁存过「语音失败或麦克风不可用」，也就是必须先让语音失败一次入口才出现。
 * 图书馆、深夜、开会这些场合的用户根本开不了口，却被逼着先故意失败一次，
 * 白烧一次语音机会才拿到能用的入口。改成常驻，语音与文字各自计次互不影响。
 */
fun shouldShowTextActivation(state: AiSpriteUiState): Boolean =
    state.isAuthorized &&
        canRequestTextActivation(state.textActivationAttempt) &&
        state.selectedCharacterId != state.activatedCharacterId

/**
 * 文字激活按钮可点条件：角色已上线、授权过、文字次数没用满、该角色还没激活、没有请求在飞。
 *
 * 这里同样不看「语音失败过没有」。入口改成常驻后若只放开展示条件，
 * 按钮会常驻着灰给用户看，`AiSpriteViewModel.activateByText` 还会用同一个判定
 * 回一句「角色还没准备好」，比原来的锁存更糟。
 */
fun canActivateCharacterByText(character: AiCharacter, state: AiSpriteUiState): Boolean =
    character.isAvailable &&
        state.isAiAvailable &&
        canRequestTextActivation(state.textActivationAttempt) &&
        state.activatedCharacterId != character.id &&
        state.activationState != AiActivationState.RECORDING &&
        state.activationState != AiActivationState.VERIFYING &&
        state.activationState != AiActivationState.SUCCESS

/**
 * 激活入口整体不可用时的原因文案；可用时返回 null。
 *
 * 之前主按钮只是灰掉，用户看不出是角色没上线还是次数用完，只能干瞪眼。
 */
@StringRes
fun activationBlockedReasonRes(character: AiCharacter, state: AiSpriteUiState): Int? = when {
    !character.isAvailable -> R.string.ai_sprite_activate_disabled_preparing
    state.activatedCharacterId == character.id -> null
    !canRequestVoiceActivation(state.activationAttempt) &&
        !canRequestTextActivation(state.textActivationAttempt) ->
        R.string.ai_sprite_activate_disabled_exhausted
    else -> null
}

/** 今日影视知识的请求 locale：应用语言设置优先，system 再回落到系统语言。 */
fun dailyKnowledgeLocale(
    languageSetting: String,
    systemLanguage: String
): String = when (languageSetting) {
    LanguageStorage.LANGUAGE_CHINESE -> "zh-CN"
    LanguageStorage.LANGUAGE_ENGLISH -> "en-US"
    LanguageStorage.LANGUAGE_JAPANESE -> "ja-JP"
    LanguageStorage.LANGUAGE_KOREAN -> "ko-KR"
    LanguageStorage.LANGUAGE_SYSTEM -> when (systemLanguage.take(2).lowercase()) {
        "zh" -> "zh-CN"
        "en" -> "en-US"
        "ja" -> "ja-JP"
        "ko" -> "ko-KR"
        else -> "zh-CN"
    }
    else -> "zh-CN"
}

/** 关联等级只认 Worker 受控枚举，未知值不渲染成看起来可信的标签。 */
@StringRes
fun dailyKnowledgeRelationLabelRes(relationType: String?): Int? = when (relationType) {
    "direct_watch" -> R.string.ai_daily_relation_direct_watch
    "theme_extension" -> R.string.ai_daily_relation_theme_extension
    "general_knowledge" -> R.string.ai_daily_relation_general_knowledge
    else -> null
}

/** 证据模式标签帮助用户区分影片事实、观影解读和外部事实。 */
@StringRes
fun dailyKnowledgeEvidenceModeLabelRes(evidenceMode: String?): Int? = when (evidenceMode) {
    "film_fact" -> R.string.ai_daily_evidence_film_fact
    "viewing_interpretation" -> R.string.ai_daily_evidence_viewing_interpretation
    "external_fact" -> R.string.ai_daily_evidence_external_fact
    "theme_extension" -> R.string.ai_daily_evidence_theme_extension
    else -> null
}

/** UI 只展示八个大类，具体 subject 仍是 Worker 内部受控键，不直接透出。 */
@StringRes
fun dailyKnowledgeSubjectGroupLabelRes(subjectGroup: String?): Int? = when (subjectGroup) {
    "film_expression" -> R.string.ai_daily_subject_film_expression
    "people_and_mind" -> R.string.ai_daily_subject_people_and_mind
    "society_and_institution" -> R.string.ai_daily_subject_society_and_institution
    "history_and_culture" -> R.string.ai_daily_subject_history_and_culture
    "philosophy_and_ethics" -> R.string.ai_daily_subject_philosophy_and_ethics
    "science_and_nature" -> R.string.ai_daily_subject_science_and_nature
    "technology_and_future" -> R.string.ai_daily_subject_technology_and_future
    "life_and_career" -> R.string.ai_daily_subject_life_and_career
    else -> null
}

/** 每日知识允许用户主动换 2 条；首次进入的自动加载不消耗这个额度。 */
fun canChangeDailyKnowledge(changeCount: Int): Boolean =
    changeCount < MAX_DAILY_KNOWLEDGE_CHANGES

fun remainingDailyKnowledgeChanges(changeCount: Int): Int =
    (MAX_DAILY_KNOWLEDGE_CHANGES - changeCount).coerceIn(0, MAX_DAILY_KNOWLEDGE_CHANGES)

/**
 * 即时小题渲染前的本地守卫：Worker 已做硬校验，这里再挡一次旧缓存或异常数据，
 * 避免把没有唯一正确答案的题画成可点但永远无法判定的 UI。
 */
fun dailyCheckQuestionIsValid(question: AiDailyCheckQuestion?): Boolean {
    if (question == null) return false
    val options = question.options
    if (options.size !in 2..4) return false
    val optionIds = options.map { it.id.trim() }.toSet()
    if (optionIds.size != options.size || optionIds.any { it.isBlank() }) return false
    val correctIds = question.correctOptionIds.map { it.trim() }.toSet()
    return correctIds.size == 1 && correctIds.first() in optionIds &&
        question.prompt.isNotBlank() && question.explanation.isNotBlank() &&
        options.all { it.text.isNotBlank() }
}

/** 未作答题目数，用于提交前提醒，避免用户一路点「下一题」到底后白拿 0 分。 */
fun unansweredQuizCount(quiz: AiQuiz?, answers: Map<String, AiQuizAnswer>): Int {
    val questions = quiz?.questions ?: return 0
    return questions.count { question ->
        val answer = answers[question.id]
        answer == null ||
            (answer.selectedOptionIds.isEmpty() && answer.textAnswer.isNullOrBlank())
    }
}

fun answeredQuizCount(quiz: AiQuiz?, answers: Map<String, AiQuizAnswer>): Int {
    val total = quiz?.questions?.size ?: 0
    return total - unansweredQuizCount(quiz, answers)
}

/** 答题进行中：有题目、已开始、还没出结果。刷新/重进都要先保住这份进度。 */
fun hasQuizInProgress(state: AiSpriteUiState): Boolean =
    state.quizStarted && state.quiz != null && state.quizResult == null

/**
 * 出题等待页的总进度（0f..1f）。
 *
 * 两阶段按实测耗时分配权重：units 约 140s、review 约 250s（本地闭环实测），
 * 阶段内进度用「已生成字符数 / 该阶段预期字符数」估算，服务端会把预期值随 stage 事件下发。
 * 生成量会浮动，超过预期时按 100% 封顶；没拿到阶段信息时回落到不确定态（0f）。
 */
fun quizStreamProgressFraction(
    stage: AiQuizStage?,
    chars: Int,
    expectedChars: Int
): Float {
    if (stage == null) return 0f
    val stageWeight = if (stage == AiQuizStage.UNITS) QUIZ_UNITS_PROGRESS_WEIGHT else 1f - QUIZ_UNITS_PROGRESS_WEIGHT
    val stageFraction = if (expectedChars > 0) {
        (chars.toFloat() / expectedChars.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val completedWeight = if (stage == AiQuizStage.REVIEW) QUIZ_UNITS_PROGRESS_WEIGHT else 0f
    return (completedWeight + stageWeight * stageFraction).coerceIn(0f, 1f)
}

/**
 * 今日知识加载页的总进度（0f..1f）。
 *
 * 两阶段实测耗时接近（候选约 6s、复核约 7s，各约 1800 字符），所以各占一半权重；
 * 阶段内进度用「已生成字符数 / 该阶段预期字符数」估算，预期值随服务端 stage 事件下发。
 * 生成量会浮动，超过预期按 100% 封顶；没拿到阶段信息时回落到不确定态（0f）。
 */
fun dailyStreamProgressFraction(
    stage: AiDailyStage?,
    chars: Int,
    expectedChars: Int
): Float {
    if (stage == null) return 0f
    val stageWeight = if (stage == AiDailyStage.CANDIDATE) {
        DAILY_CANDIDATE_PROGRESS_WEIGHT
    } else {
        1f - DAILY_CANDIDATE_PROGRESS_WEIGHT
    }
    val stageFraction = if (expectedChars > 0) {
        (chars.toFloat() / expectedChars.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val completedWeight = if (stage == AiDailyStage.REVIEW) DAILY_CANDIDATE_PROGRESS_WEIGHT else 0f
    return (completedWeight + stageWeight * stageFraction).coerceIn(0f, 1f)
}

/** 已用时长文案：不足一分钟显示秒，超过显示「分:秒」。 */
fun formatQuizElapsed(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return if (safe < 60) {
        "${safe}s"
    } else {
        val minutes = safe / 60
        val seconds = safe % 60
        minutes.toString() + ":" + seconds.toString().padStart(2, '0')
    }
}

/**
 * 问答候选的媒体类型规范化。
 *
 * Trakt 当前返回 show，但旧缓存/兼容调用可能使用 tv；两者属于同一类媒体，
 * 必须共用 key，否则预览列表会出现重复项，或者本地化标题互相覆盖。
 */
internal fun normalizeQuizMediaType(mediaType: String): String =
    if (mediaType.equals("show", ignoreCase = true) || mediaType.equals("tv", ignoreCase = true)) {
        "show"
    } else {
        mediaType.trim().lowercase()
    }

/** 统一供候选去重、Compose item key 和本地化标题映射使用。 */
internal fun quizMediaKey(mediaType: String, mediaId: String): String =
    "${normalizeQuizMediaType(mediaType)}:$mediaId"

internal fun AiWatchedTitleDto.key(): String = quizMediaKey(mediaType, mediaId)

/** 今日题目的准备状态：预览页据此告诉用户点「开始」是秒开还是要现场等生成。 */
enum class AiQuizPrepareState {
    /** 还没开始准备：离线、未授权、已看不满 7 部，或还没进过出题页 */
    IDLE,

    /** 静默预生成进行中 */
    PREPARING,

    /** 已就绪：点「开始」命中当天题库，秒开 */
    READY,

    /** 预生成失败：点「开始」会现场生成（冷路径，需要等几分钟） */
    FAILED,

    /**
     * 当天该预生成的套都发过了：用户已经玩完当天的量，再开一局是服务端按需生成。
     *
     * 与 [FAILED] 分开：这不是「没准备好」，而是当天已经没有可预生成的套，
     * 文案要说实话（今天的题已玩完），并且当天不必再重试预生成。
     */
    EXHAUSTED
}

/**
 * 当天出题片单：按「种子 + 片名」稳定排序取前 [count] 部（默认 7 部）。
 *
 * 与服务端 `quiz-bank.selectDailyMovies` 同一套思路：排序键只取决于种子与片名，与影视在
 * 列表里的先后无关 —— 同一天同一用户无论重进页面、重启 App 还是换设备，选出的都是同一批片。
 *
 * 这是「静默预生成」的前提：预生成与正式出题必须落在同一批片上，命中服务端题库时内容才会
 * 与预览页展示的今日考点一致；片单飘了，用户就会看到预览里没预告过的影视。
 * 所以这里刻意不用随机洗牌，也刻意不随「已看列表顺序变化」而变。
 *
 * 先去重（同 id 归一 + 同片名只留一部，避免一批里出现同一部片），再按原始顺序输出选中项。
 */
fun selectDailyQuizPreview(
    candidates: List<AiWatchedTitleDto>,
    seed: String,
    count: Int = 7
): List<AiWatchedTitleDto> {
    val distinct = candidates
        .distinctBy { it.key() }
        .distinctBy { it.title.trim().lowercase(Locale.ROOT) }
    if (count <= 0 || distinct.size <= count) return distinct
    val picked = distinct
        .withIndex()
        .sortedWith(compareBy({ quizMovieRankKey(seed, it.value.title) }, { it.index }))
        .take(count)
        .map { it.index }
        .toSet()
    return distinct.filterIndexed { index, _ -> index in picked }
}

/**
 * 片单排序键：FNV-1a + murmur3 收尾混淆，值域 [0, 2^32)，按无符号比较。
 *
 * 种子与片名共享很长前缀（同一用户同一天），不混淆的话相邻 seed 的键低位相关性偏强，
 * 抽出来的片会老是贴着同几部。转成 Long 的无符号值比较，与 JS 侧同一算法的排序一致。
 */
internal fun quizMovieRankKey(seed: String, title: String): Long =
    (mix32(fnv1a32(seed + "\u0000" + title)).toLong() and 0xFFFF_FFFFL)

/** FNV-1a 32 位哈希。Kotlin 的 Int 乘法与 JS Math.imul 一样是 32 位回绕。 */
private fun fnv1a32(input: String): Int {
    var hash = 0x811c9dc5.toInt()
    for (element in input) {
        hash = hash xor element.code
        hash *= 0x01000193
    }
    return hash
}

/** 32 位收尾混淆（murmur3 finalizer 结构）。 */
private fun mix32(value: Int): Int {
    var hash = value
    hash = hash xor (hash ushr 16)
    hash *= 0x7feb352d
    hash = hash xor (hash ushr 15)
    hash *= 0x846ca68b.toInt()
    hash = hash xor (hash ushr 16)
    return hash
}

fun recommendationHasDetailRoute(recommendation: AiRecommendation): Boolean =
    (recommendation.traktId ?: 0) > 0 ||
        (recommendation.tmdbId ?: 0) > 0 ||
        !recommendation.doubanId.isNullOrBlank() ||
        !recommendation.imdbId.isNullOrBlank()

/** 兼容现有电影/剧集回调，把纯豆瓣推荐显式标记给导航层。 */
fun recommendationNavigationKey(recommendation: AiRecommendation): String? =
    recommendation.doubanId?.takeIf { it.isNotBlank() }?.let { "$AI_DOUBAN_NAV_PREFIX$it" }
        ?: recommendation.imdbId?.takeIf { it.isNotBlank() }

fun quizAnswerLabels(
    question: AiQuizQuestion,
    answer: AiQuizAnswer?
): List<String> = answer?.selectedOptionIds.orEmpty().mapNotNull { selectedId ->
    question.options.firstOrNull { it.id == selectedId }?.text
}

fun quizCorrectAnswerText(
    question: AiQuizQuestion?,
    result: AiQuizQuestionResult,
    separator: String = "、"
): String? {
    val optionLabels = result.correctOptionIds.mapNotNull { correctId ->
        question?.options?.firstOrNull { it.id == correctId }?.text
    }
    return joinQuizOptionTexts(optionLabels, separator).takeIf { it.isNotBlank() }
        ?: result.correctAnswer?.trim()?.takeIf { it.isNotBlank() }?.let(::cleanLegacyJoinedAnswerText)
}

fun quizProgress(current: Int, total: Int): Float {
    if (total <= 0) return 0f
    return (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

fun localQuestionScore(type: AiQuizQuestionType, answered: Boolean): Int {
    if (!answered) return 0
    return when (type) {
        AiQuizQuestionType.SINGLE -> 7
        AiQuizQuestionType.MULTIPLE, AiQuizQuestionType.SHORT -> 10
    }
}


/**
 * 复盘/答案拼接：逐项去掉选项文本末尾的句读标点再连接。
 * 服务端多选题选项可能以「。」等结尾，直接拼会得到「A。、B。」。
 */
fun stripTrailingOptionPunctuation(text: String): String =
    text.trim().trimEnd('。', '．', '.', '！', '!', '？', '?', '；', ';', '，', ',', '、')

/**
 * 兼容老缓存：服务端数组答案可能已拼成「A。、B。」一整段（选项以句号结尾再插分隔符）。
 * 先清掉紧贴分隔符前面的句读标点，再去掉整段尾部标点。
 */
fun cleanLegacyJoinedAnswerText(text: String): String =
    stripTrailingOptionPunctuation(
        text.replace(Regex("[。．.!！?？]+(?=[、,，;；])"), "")
    )

fun joinQuizOptionTexts(labels: List<String>, separator: String): String =
    labels.joinToString(separator) { stripTrailingOptionPunctuation(it) }

/**
 * 预览列表只替换展示标题；传给 Worker 的仍是 [AiWatchedTitleDto.title] 原始值。
 * 类型参与 key，避免电影和剧集使用相同 ID 时互相覆盖本地化标题。
 */
fun localizedQuizPreviewTitle(
    movie: AiWatchedTitleDto,
    localizedTitles: Map<String, String>
): String = localizedTitles[quizMediaKey(movie.mediaType, movie.mediaId)]
    ?.takeIf { it.isNotBlank() }
    // 兼容旧状态中仍以原始 mediaType 保存的标题映射。
    ?: localizedTitles["${movie.mediaType}:${movie.mediaId}"]
        ?.takeIf { it.isNotBlank() }
    ?: movie.title

/**
 * 答题页顶部「本轮涉及」片名清单：优先复用选题页的本地化标题，
 * 让顶部语言与选题页一致；匹配不到本地化数据时保留服务端原标题。
 * 顺序跟随 mediaTitles，并做去重（模型偶尔会重复列出同一部片）。
 */
fun localizedQuizRoundTitles(
    mediaTitles: List<String>,
    movies: List<AiWatchedTitleDto>,
    localizedTitles: Map<String, String>
): List<String> {
    if (mediaTitles.isEmpty()) return emptyList()
    val byTitle = movies.associateBy { it.title.trim().lowercase(Locale.ROOT) }
    val byOriginalTitle = movies.associateBy { it.originalTitle.trim().lowercase(Locale.ROOT) }
    val seen = mutableSetOf<String>()
    return mediaTitles.mapNotNull { rawTitle ->
        val key = rawTitle.trim().lowercase(Locale.ROOT)
        val movie = byTitle[key] ?: byOriginalTitle[key]
        val display = movie?.let { localizedQuizPreviewTitle(it, localizedTitles) }
            ?.takeIf { it.isNotBlank() }
            ?: rawTitle.trim()
        if (display.isBlank()) {
            null
        } else if (seen.add(display.lowercase(Locale.ROOT))) {
            display
        } else {
            null
        }
    }
}

/**
 * 顶部标题去重：模型 subtitle 往往是「本轮涉及：…」的开场白，只有它把
 * mediaTitles 里的全部片名都复述了一遍，才判定为重复行，交给本地化片名清单统一展示；
 * 只提个别片名或另有补充信息的副标题保留，避免误删有效说明。
 */
fun quizRoundSubtitleDuplicatesMediaTitles(subtitle: String, mediaTitles: List<String>): Boolean {
    val titles = mediaTitles.map { it.trim() }.filter { it.isNotBlank() }
    if (subtitle.isBlank() || titles.isEmpty()) return false
    val text = subtitle.lowercase(Locale.ROOT)
    return titles.all { text.contains(it.lowercase(Locale.ROOT)) }
}

/**
 * 来源区块渲染条件：name/url/证据/发布日期四项全空时不留空壳，
 * 有任意一项就渲染（url 为空时退化为纯文字来源，不展示可点击链接）。
 */
fun dailySourceBlockHasContent(
    sourceName: String,
    sourceUrl: String,
    sourceEvidence: String,
    publishedAt: Long?
): Boolean = sourceName.isNotBlank() || sourceUrl.isNotBlank() ||
    sourceEvidence.isNotBlank() || publishedAt != null
