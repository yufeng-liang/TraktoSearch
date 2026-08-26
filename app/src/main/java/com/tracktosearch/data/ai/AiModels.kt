package com.tracktosearch.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/** 网关统一返回包装。API Key 只存在服务端，客户端只接收这个业务响应。 */
@Serializable
data class AiApiResponse<T>(
    val code: String,
    val message: String = "",
    val requestId: String = "",
    val data: T? = null,
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiErrorDto(
    val code: String,
    val message: String = "",
    val requestId: String = ""
)

@Serializable
data class AiQuotaDto(
    val sessionUsed: Int = 0,
    val sessionLimit: Int = 14,
    val dailyUsed: Int = 0,
    val dailyLimit: Int = 80,
    val resetAt: Long? = null
)

@Serializable
data class AiQuota(
    val sessionUsed: Int,
    val sessionLimit: Int,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val resetAt: Long?
)

@Serializable
data class AiActivateRequest(
    val characterId: String,
    val spokenName: String = "",
    val audioDataUrl: String? = null,
    val locale: String = "zh-CN",
    val sessionId: String = "activation"
)

@Serializable
data class AiActivationDto(
    val activated: Boolean = false,
    val characterId: String = "",
    val characterName: String = "",
    val activationPhrase: String = "到！",
    val voiceStatus: String = "preparing",
    val character: AiCharacterDto? = null,
    val quota: AiQuotaDto? = null,
    val greeting: AiGreetingDto? = null,
    val audio: AiAudioDto? = null
)

@Serializable
data class AiActivation(
    val activated: Boolean,
    val activationPhrase: String = "",
    val character: AiCharacter?,
    val quota: AiQuota?,
    val greeting: AiGreeting?,
    val audio: AiAudio?
)

@Serializable
data class AiGreetingRequest(
    val characterId: String,
    val includeAudio: Boolean = true,
    val forceRefresh: Boolean = false,
    val sessionId: String = "greeting"
)

@Serializable
data class AiGreetingDto(
    val nickname: String = "",
    val greeting: String = "",
    val spokenText: String? = null,
    val nicknameMeaning: String = "",
    val comment: String = "",
    val audio: AiAudioDto? = null,
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiGreeting(
    val nickname: String,
    val greeting: String,
    val spokenText: String = greeting,
    val nicknameMeaning: String,
    val comment: String,
    val audio: AiAudio?,
    val quota: AiQuota? = null
)

@Serializable
data class AiWatchedTitleDto(
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val publicRating: Double? = null,
    val userRating: Double? = null,
    val watchedAt: String? = null,
    val mediaIds: AiMediaIdsDto = AiMediaIdsDto()
)

@Serializable
data class AiTasteRequest(
    val watched: List<AiWatchedTitleDto> = emptyList(),
    val forceRefresh: Boolean = false,
    val sessionId: String = "taste"
)

@Serializable
data class AiMediaIdsDto(
    val traktId: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null
)

@Serializable
data class AiRecommendationDto(
    val id: String = "",
    val mediaType: String = "movie",
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    // TMDB 核验后本地填充的海报路径（"/xxx.jpg"），UI 层按项目约定拼完整 URL
    val posterPath: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null,
    val reason: String = ""
)

@Serializable
data class AiTasteDto(
    val nickname: String = "",
    val roast: String = "",
    val taste: List<String> = emptyList(),
    val tasteProfile: String = "",
    val highlights: List<String> = emptyList(),
    val recommendations: List<AiRecommendationDto> = emptyList(),
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiWatchedTitle(
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    val publicRating: Double?,
    val userRating: Double?,
    val watchedAt: String?
)

@Serializable
data class AiRecommendation(
    val id: String,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val posterPath: String? = null,
    val tmdbId: Int?,
    val traktId: Int?,
    val imdbId: String?,
    val doubanId: String?,
    val reason: String
)

@Serializable
data class AiTasteAnalysis(
    val roast: String,
    val tasteProfile: String,
    val highlights: List<String>,
    val recommendations: List<AiRecommendation>,
    val quota: AiQuota? = null
)

@Serializable
data class AiQuizRequest(
    val watched: List<AiWatchedTitleDto> = emptyList(),
    val excludedQuizIds: List<String> = emptyList(),
    val questionCount: Int = 13,
    val sessionId: String = "quiz"
)

@Serializable
data class AiQuizOption(
    val id: String,
    val text: String
)

@Serializable
data class AiQuizQuestion(
    val id: String,
    val type: AiQuizQuestionType,
    val prompt: String,
    val options: List<AiQuizOption> = emptyList(),
    val mediaTitle: String? = null,
    val quote: String? = null,
    val maxScore: Int = 0
)

@Serializable
enum class AiQuizQuestionType {
    SINGLE,
    MULTIPLE,
    SHORT
}

@Serializable
data class AiQuizDto(
    val quizId: String = "",
    val title: String = "你真的看懂这些影视了吗",
    val subtitle: String = "",
    val mediaTitles: List<String> = emptyList(),
    val movies: List<AiWatchedTitleDto> = emptyList(),
    val questions: List<AiQuizQuestionDto> = emptyList(),
    val totalScore: Int = 100,
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiQuizQuestionDto(
    val id: String,
    val type: String,
    val prompt: String,
    val options: List<AiQuizOption> = emptyList(),
    val mediaTitle: String? = null,
    val quote: String? = null,
    val maxScore: Int = 0,
    val explanation: String? = null,
    val correctOptionIds: List<String> = emptyList()
)

@Serializable
data class AiQuiz(
    val quizId: String,
    val title: String,
    val subtitle: String,
    val mediaTitles: List<String>,
    val questions: List<AiQuizQuestion>,
    val totalScore: Int,
    val quota: AiQuota? = null
) {
    val isThirteenQuestionStructure: Boolean
        get() = questions.size == 13 &&
            questions.count { it.type == AiQuizQuestionType.SINGLE } == 10 &&
            questions.count { it.type == AiQuizQuestionType.MULTIPLE } == 2 &&
            questions.count { it.type == AiQuizQuestionType.SHORT } == 1
}

@Serializable
data class AiQuizAnswerDto(
    val questionId: String,
    val selectedOptionIds: List<String> = emptyList(),
    val textAnswer: String? = null
)

@Serializable
data class AiQuizAnswer(
    val questionId: String,
    val selectedOptionIds: List<String> = emptyList(),
    val textAnswer: String? = null
)

@Serializable
data class AiSubmitQuizRequest(
    val quizId: String,
    val answers: List<AiQuizAnswerDto>,
    val sessionId: String = "quiz"
)

@Serializable
data class AiQuizQuestionResultDto(
    val questionId: String,
    val score: Int = 0,
    val correct: Boolean = false,
    val explanation: String = "",
    val correctOptionIds: List<String> = emptyList(),
    /** Worker 对简答题返回文本，对多选题返回字符串数组。 */
    val correctAnswer: JsonElement? = null
)

@Serializable
data class AiQuizQuestionResult(
    val questionId: String,
    val score: Int,
    val correct: Boolean,
    val explanation: String,
    val correctOptionIds: List<String> = emptyList(),
    val correctAnswer: String? = null
)

@Serializable
data class AiQuizResultDto(
    val quizId: String,
    val score: Int = 0,
    val totalScore: Int = 100,
    val correctCount: Int = 0,
    val totalQuestions: Int = 13,
    val summary: String = "",
    val dimensionScores: Map<String, Int> = emptyMap(),
    val questionResults: List<AiQuizQuestionResultDto> = emptyList(),
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiQuizResult(
    val quizId: String,
    val score: Int,
    val totalScore: Int,
    val correctCount: Int,
    val totalQuestions: Int,
    val summary: String,
    val dimensionScores: Map<String, Int>,
    val questionResults: List<AiQuizQuestionResult>,
    val quota: AiQuota? = null
)

/** 闯关历史（离线可浏览）：最近一次结果 + 历史最高分。按 friendId 分区持久化。 */
@Serializable
data class AiQuizHistory(
    val bestScore: Int = 0,
    val lastResult: AiQuizResult? = null
)

@Serializable
data class AiDailyKnowledgeDto(
    val id: String = "",
    val title: String = "",
    val fact: String = "",
    val explanation: String = "",
    val sourceName: String = "",
    val sourceUrl: String = "",
    val publishedAt: Long? = null,
    val characterLine: String? = null,
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiDailyRequest(
    val sessionId: String = "daily",
    val forceRefresh: Boolean = false
)

@Serializable
data class AiDailyKnowledge(
    val id: String,
    val title: String,
    val fact: String,
    val explanation: String,
    val sourceName: String,
    val sourceUrl: String,
    val publishedAt: Long?,
    val characterLine: String?,
    val quota: AiQuota? = null
)

@Serializable
enum class AiTtsScene {
    AUDITION,
    ACTIVATION_ACK,
    GREETING,
}

@Serializable
data class AiTtsRequest(
    val characterId: String,
    val text: String,
    val style: String? = null,
    val sessionId: String = "default",
    val scene: AiTtsScene? = null
)

@Serializable
data class AiAudioDto(
    val audioDataUrl: String? = null,
    val audioUrl: String? = null,
    val audioUrlExpiresAt: Long? = null,
    val mimeType: String = "audio/mpeg",
    val durationMs: Long? = null,
    val cacheKey: String? = null,
    val transcript: String? = null,
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiAudio(
    val audioDataUrl: String?,
    val audioUrl: String?,
    val audioUrlExpiresAt: Long? = null,
    val mimeType: String,
    val durationMs: Long?,
    val cacheKey: String?,
    val transcript: String?,
    val quota: AiQuota? = null
)

fun AiQuotaDto.toDomain(): AiQuota = AiQuota(sessionUsed, sessionLimit, dailyUsed, dailyLimit, resetAt)

fun AiAudioDto.toDomain(outerQuota: AiQuotaDto? = null): AiAudio = AiAudio(
    audioDataUrl = audioDataUrl,
    audioUrl = audioUrl,
    audioUrlExpiresAt = audioUrlExpiresAt,
    mimeType = mimeType,
    durationMs = durationMs,
    cacheKey = cacheKey,
    transcript = transcript,
    quota = (outerQuota ?: quota)?.toDomain()
)

fun AiGreetingDto.toDomain(outerQuota: AiQuotaDto? = null): AiGreeting {
    val effectiveQuota = outerQuota ?: quota
    return AiGreeting(
        nickname = nickname,
        greeting = greeting,
        spokenText = spokenText?.takeIf { it.isNotBlank() } ?: greeting,
        nicknameMeaning = nicknameMeaning,
        comment = comment,
        audio = audio?.toDomain(effectiveQuota),
        quota = effectiveQuota?.toDomain()
    )
}

fun AiTasteDto.toDomain(outerQuota: AiQuotaDto? = null): AiTasteAnalysis {
    val effectiveQuota = outerQuota ?: quota
    return AiTasteAnalysis(
        roast = roast,
        tasteProfile = tasteProfile.ifBlank { taste.joinToString("、") },
        highlights = highlights.ifEmpty { taste },
        recommendations = recommendations.map { recommendation ->
            AiRecommendation(
                id = recommendation.id.ifBlank {
                    recommendation.tmdbId?.toString() ?: recommendation.title
                },
                mediaType = recommendation.mediaType,
                title = recommendation.title,
                year = recommendation.year,
                posterUrl = recommendation.posterUrl,
                posterPath = recommendation.posterPath,
                tmdbId = recommendation.tmdbId,
                traktId = recommendation.traktId,
                imdbId = recommendation.imdbId,
                doubanId = recommendation.doubanId,
                reason = recommendation.reason
            )
        },
        quota = effectiveQuota?.toDomain()
    )
}

fun AiQuizDto.toDomainOrNull(outerQuota: AiQuotaDto? = null): AiQuiz? {
    val mappedQuestions = mutableListOf<AiQuizQuestion>()
    for (question in questions) {
        val type = when (question.type.trim().lowercase(Locale.ROOT)) {
            "single", "single_choice" -> AiQuizQuestionType.SINGLE
            "multiple", "multiple_choice" -> AiQuizQuestionType.MULTIPLE
            "short", "short_answer" -> AiQuizQuestionType.SHORT
            else -> return null
        }
        mappedQuestions += AiQuizQuestion(
            id = question.id,
            type = type,
            prompt = question.prompt,
            options = question.options,
            mediaTitle = question.mediaTitle,
            quote = question.quote,
            maxScore = question.maxScore
        )
    }
    return AiQuiz(
        quizId = quizId,
        title = title,
        subtitle = subtitle,
        mediaTitles = mediaTitles.ifEmpty { movies.map { it.title } },
        questions = mappedQuestions,
        totalScore = totalScore,
        quota = (outerQuota ?: quota)?.toDomain()
    )
}

fun AiQuizResultDto.toDomain(outerQuota: AiQuotaDto? = null): AiQuizResult = AiQuizResult(
    quizId = quizId,
    score = score,
    totalScore = totalScore,
    correctCount = correctCount,
    totalQuestions = totalQuestions,
    summary = summary,
    dimensionScores = dimensionScores,
    questionResults = questionResults.map {
        AiQuizQuestionResult(
            questionId = it.questionId,
            score = it.score,
            correct = it.correct,
            explanation = it.explanation,
            correctOptionIds = it.correctOptionIds.ifEmpty { it.correctAnswer.toOptionIds() },
            correctAnswer = it.correctAnswer.toAnswerText()
        )
    },
    quota = (outerQuota ?: quota)?.toDomain()
)

fun AiDailyKnowledgeDto.toDomain(outerQuota: AiQuotaDto? = null): AiDailyKnowledge = AiDailyKnowledge(
    id = id,
    title = title,
    fact = fact,
    explanation = explanation,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    publishedAt = publishedAt,
    characterLine = characterLine,
    quota = (outerQuota ?: quota)?.toDomain()
)

fun AiActivationDto.toDomain(outerQuota: AiQuotaDto? = null): AiActivation {
    val effectiveQuota = outerQuota ?: quota
    return AiActivation(
        activated = activated,
        activationPhrase = activationPhrase,
        character = character?.toDomain()
            ?: AiCharacterCatalog.all.firstOrNull { it.id == characterId }?.let { base ->
                base.copy(
                    name = characterName.ifBlank { base.name },
                    isAvailable = voiceStatus == "ready"
                )
            },
        quota = effectiveQuota?.toDomain(),
        greeting = greeting?.toDomain(effectiveQuota),
        audio = audio?.toDomain(effectiveQuota)
    )
}

private fun JsonElement?.toAnswerText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    is JsonArray -> joinToString("、") { it.toAnswerText().orEmpty() }
    else -> toString()
}

private fun JsonElement?.toOptionIds(): List<String> = when (this) {
    is JsonPrimitive -> content.takeIf { it.matches(OPTION_ID_PATTERN) }?.let(::listOf).orEmpty()
    is JsonArray -> mapNotNull { element ->
        (element as? JsonPrimitive)?.content?.takeIf { it.matches(OPTION_ID_PATTERN) }
    }
    else -> emptyList()
}

private val OPTION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,32}")
