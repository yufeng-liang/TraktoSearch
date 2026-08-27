package com.tracktosearch.ui.screen.ai

import androidx.annotation.StringRes
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizQuestionResult
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.ai.AiTtsScene
import com.tracktosearch.data.ai.AiTtsRequest
import com.tracktosearch.data.ai.AiWatchedTitleDto
import kotlin.random.Random

private const val MAX_VOICE_ACTIVATION_ATTEMPTS = 5
private const val MAX_TEXT_ACTIVATION_ATTEMPTS = 5
private const val MAX_OVERLAY_SESSION_COUNT = 3
private const val MAX_OVERLAY_DAILY_COUNT = 6
private const val IDLE_TRIGGER_DELAY_MS = 8_000L
private const val MAX_QUIZ_REPLACEMENTS = 2

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
    "AUDIO_UNAVAILABLE" -> R.string.ai_error_audio_unavailable
    "NOT_ENOUGH_MOVIES" -> R.string.ai_error_not_enough_movies
    "WATCHED_LIST_EMPTY" -> R.string.ai_error_watched_list_empty
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
 */
fun aiErrorIsRetryable(errorCode: String?): Boolean = when (errorCode) {
    "AUTH_REQUIRED",
    "UNAUTHORIZED",
    "QUOTA_EXCEEDED",
    "NOT_ENOUGH_MOVIES",
    "WATCHED_LIST_EMPTY",
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
        state.isAuthorized &&
        state.activatedCharacterId != character.id &&
        state.activationState != AiActivationState.RECORDING &&
        state.activationState != AiActivationState.VERIFYING &&
        state.activationState != AiActivationState.SUCCESS &&
        canRequestVoiceActivation(state.activationAttempt)

/**
 * 文字激活入口是否展示：语音失败过或麦克风不可用后才出现，且文字次数没用满。
 *
 * 用 state 里锁存的 textActivationOffered 而不是实时看 activationState，
 * 否则用户重试语音的那几秒入口会闪走。
 */
fun shouldShowTextActivation(state: AiSpriteUiState): Boolean =
    state.isAuthorized &&
        state.textActivationOffered &&
        canRequestTextActivation(state.textActivationAttempt) &&
        state.selectedCharacterId != state.activatedCharacterId

/** 文字激活按钮可点条件：入口已开放、没有请求在飞、该角色还没激活。 */
fun canActivateCharacterByText(character: AiCharacter, state: AiSpriteUiState): Boolean =
    character.isAvailable &&
        state.isAuthorized &&
        state.textActivationOffered &&
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
 * 「换一部」是否还能点：次数没用满 **且** 还有没用过的候选。
 *
 * 已看正好 7 部时候选恰好被用光，之前按钮仍可点但点了原样返回，成了死按钮。
 */
fun canReplaceQuizPreview(
    current: List<AiWatchedTitleDto>,
    candidates: List<AiWatchedTitleDto>,
    replacementCount: Int
): Boolean {
    if (replacementCount >= MAX_QUIZ_REPLACEMENTS) return false
    val usedKeys = current.map { it.key() }.toSet()
    return candidates.any { it.key() !in usedKeys }
}

fun remainingQuizReplacements(replacementCount: Int): Int =
    (MAX_QUIZ_REPLACEMENTS - replacementCount).coerceAtLeast(0)

private fun AiWatchedTitleDto.key(): String = "$mediaType:$mediaId"

fun selectQuizPreview(
    candidates: List<AiWatchedTitleDto>,
    random: Random = Random.Default
): List<AiWatchedTitleDto> = candidates
    .distinctBy { it.key() }
    .shuffled(random)
    .take(7)

/**
 * 预览页「全部重抽」的新一批：尽量整批避开当前已展示的 7 部；
 * 候选不够凑一整批全新的（已看恰好只有 7 部等）时退回全量随机，
 * 此时可换候选也已用光，按钮会被禁用，走到这里只会原样返回。
 */
fun redrawQuizPreview(
    current: List<AiWatchedTitleDto>,
    candidates: List<AiWatchedTitleDto>,
    random: Random = Random.Default
): List<AiWatchedTitleDto> {
    val currentKeys = current.map { it.key() }.toSet()
    val fresh = candidates.distinctBy { it.key() }.filterNot { it.key() in currentKeys }
    return if (fresh.size >= current.size) {
        fresh.shuffled(random).take(current.size)
    } else {
        selectQuizPreview(candidates, random)
    }
}

fun replaceQuizPreview(
    current: List<AiWatchedTitleDto>,
    candidates: List<AiWatchedTitleDto>,
    index: Int,
    replacementCount: Int,
    random: Random = Random.Default
): List<AiWatchedTitleDto> {
    if (replacementCount >= MAX_QUIZ_REPLACEMENTS || index !in current.indices) return current
    val usedKeys = current.map { it.key() }.toSet()
    val replacement = candidates
        .distinctBy { it.key() }
        .filterNot { it.key() in usedKeys }
        .shuffled(random)
        .firstOrNull()
        ?: return current
    return current.toMutableList().also { it[index] = replacement }
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
    result: AiQuizQuestionResult
): String? {
    val optionLabels = result.correctOptionIds.mapNotNull { correctId ->
        question?.options?.firstOrNull { it.id == correctId }?.text
    }
    return optionLabels.joinToString("、").takeIf { it.isNotBlank() }
        ?: result.correctAnswer?.trim()?.takeIf { it.isNotBlank() }
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
