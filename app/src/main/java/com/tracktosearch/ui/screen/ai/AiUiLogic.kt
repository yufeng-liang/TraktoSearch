package com.tracktosearch.ui.screen.ai

import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizQuestionResult
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.ai.AiTtsScene
import com.tracktosearch.data.ai.AiTtsRequest
import com.tracktosearch.data.ai.AiWatchedTitleDto
import kotlin.random.Random

private const val MAX_ACTIVATION_ATTEMPTS = 3
private const val MAX_OVERLAY_SESSION_COUNT = 3
private const val MAX_OVERLAY_DAILY_COUNT = 6
private const val IDLE_TRIGGER_DELAY_MS = 8_000L

private val overlayTriggerCooldowns = mapOf(
    AiSpriteOverlayTrigger.FIRST_ENTRY to 0L,
    AiSpriteOverlayTrigger.SEARCH_COMPLETED to 30_000L,
    AiSpriteOverlayTrigger.IDLE to 45_000L
)

const val AI_DOUBAN_NAV_PREFIX = "ai-douban:"

enum class AiSpriteOverlayTrigger {
    FIRST_ENTRY,
    SEARCH_COMPLETED,
    IDLE
}

enum class AiActivationRequestMode {
    VOICE,
    TEXT,
    NONE
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

/** 激活只允许首次尝试加两次重试，成功后不会进入持续监听。 */
fun canRetryActivation(attempt: Int): Boolean = attempt in 1 until MAX_ACTIVATION_ATTEMPTS

fun activationRequestMode(attempt: Int): AiActivationRequestMode = when {
    attempt == 0 -> AiActivationRequestMode.VOICE
    canRetryActivation(attempt) -> AiActivationRequestMode.TEXT
    else -> AiActivationRequestMode.NONE
}

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
        (state.activationAttempt == 0 || canRetryActivation(state.activationAttempt))

private fun AiWatchedTitleDto.key(): String = "$mediaType:$mediaId"

fun selectQuizPreview(
    candidates: List<AiWatchedTitleDto>,
    random: Random = Random.Default
): List<AiWatchedTitleDto> = candidates
    .distinctBy { it.key() }
    .shuffled(random)
    .take(7)

fun replaceQuizPreview(
    current: List<AiWatchedTitleDto>,
    candidates: List<AiWatchedTitleDto>,
    index: Int,
    replacementCount: Int,
    random: Random = Random.Default
): List<AiWatchedTitleDto> {
    if (replacementCount >= 2 || index !in current.indices) return current
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
