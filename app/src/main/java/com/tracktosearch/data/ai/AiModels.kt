package com.tracktosearch.data.ai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
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
@OptIn(ExperimentalSerializationApi::class)
data class AiActivateRequest(
    val characterId: String,
    val spokenName: String = "",
    // 留 null 不序列化：网关把"报文里出现音频字段"当成带了音频，
    // 显式 null 会被判成非法音频（400 INVALID_AUDIO），文字激活与语音激活都会被挡下
    @EncodeDefault(EncodeDefault.Mode.NEVER)
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
data class AiNameSignalDto(
    val text: String = "",
    val interpretation: String = ""
)

@Serializable
data class AiNameSignal(
    val text: String = "",
    val interpretation: String = ""
)

@Serializable
data class AiGreetingDto(
    val nickname: String = "",
    val greeting: String = "",
    val spokenText: String? = null,
    val nicknameMeaning: String = "",
    val comment: String = "",
    val audio: AiAudioDto? = null,
    val quota: AiQuotaDto? = null,
    val nameSignals: List<AiNameSignalDto> = emptyList(),
    val nicknameSignature: String = ""
)

@Serializable
data class AiGreeting(
    val nickname: String,
    val greeting: String,
    val spokenText: String = greeting,
    val nicknameMeaning: String,
    val comment: String,
    val audio: AiAudio?,
    val quota: AiQuota? = null,
    val nameSignals: List<AiNameSignal> = emptyList(),
    val nicknameSignature: String = ""
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class AiWatchedTitleDto(
    val mediaId: String,
    val mediaType: String,
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val publicRating: Double? = null,
    val userRating: Double? = null,
    val watchedAt: String? = null,
    val mediaIds: AiMediaIdsDto = AiMediaIdsDto(),
    // 仅在答题请求中按需补齐；默认值保证旧客户端/旧 Worker 仍可互通。
    // NEVER 让 taste/daily 仍保持原有请求体，不发送空的新增字段。
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val overview: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val originalTitle: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val runtime: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val country: String = ""
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
data class AiTasteEvidenceDto(
    val title: String = "",
    val signal: String = "",
    val inference: String = "",
    val confidence: String = ""
)

@Serializable
data class AiTasteEvidence(
    val title: String = "",
    val signal: String = "",
    val inference: String = "",
    val confidence: String = ""
)

@Serializable
data class AiTasteDto(
    val nickname: String = "",
    val roast: String = "",
    val taste: List<String> = emptyList(),
    val tasteProfile: String = "",
    val highlights: List<String> = emptyList(),
    val recommendations: List<AiRecommendationDto> = emptyList(),
    val quota: AiQuotaDto? = null,
    val profileKeywords: List<String> = emptyList(),
    val profileSentence: String = "",
    val evidence: List<AiTasteEvidenceDto> = emptyList()
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
    val watchedAt: String?,
    val overview: String = "",
    val originalTitle: String = "",
    val runtime: Int? = null,
    val country: String = ""
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
    val quota: AiQuota? = null,
    val profileKeywords: List<String> = emptyList(),
    val profileSentence: String = "",
    val evidence: List<AiTasteEvidence> = emptyList()
)

@Serializable
data class AiQuizRequest(
    val watched: List<AiWatchedTitleDto> = emptyList(),
    val excludedQuizIds: List<String> = emptyList(),
    val questionCount: Int = 13,
    val sessionId: String = "quiz",
    /**
     * 客户端本地日期（YYYY-MM-DD，东八区）。
     *
     * 服务端当天题库按 `(用户, 日期, 套序号)` 定片与定 id，不传日期时它只能取 UTC 日期，
     * 东八区用户会在早上 8 点整「换日」；服务端允许与 UTC 今天相差 ±1 天，传本地日期是安全区间。
     */
    val date: String? = null,
    /**
     * 静默预生成：只为把当天题目提前备好，不消耗用户额度、不计入「已玩套数」。
     * 预生成与正式出题必须落在同一 (用户, 日期, 套序号) 上，正式请求才会命中题库秒开。
     */
    val prefetch: Boolean = false
)

/** 出题阶段：UNITS=提炼学习单元，REVIEW=把单元转换成 13 题。 */
enum class AiQuizStage { UNITS, REVIEW }

enum class AiQuizStageStatus { START, DONE }

/**
 * 出题流式事件（服务端 /api/ai/quiz/stream 的 NDJSON 行）。
 * 两阶段实测 280~420s，进度事件让等待页显示真实阶段与生成量；Ping 仅用于保活连接。
 */
sealed interface AiQuizStreamEvent {
    /** 阶段开始/结束；expectedChars 为该阶段预期输出字符数，用于把 chars 换算成百分比。 */
    data class Stage(
        val stage: AiQuizStage,
        val status: AiQuizStageStatus,
        val expectedChars: Int
    ) : AiQuizStreamEvent

    /** 生成中：chars 为当前阶段已生成的字符数。 */
    data class Progress(val stage: AiQuizStage, val chars: Int) : AiQuizStreamEvent

    /** 心跳：服务端每 10s 至少发一次，UI 无需展示。 */
    data object Ping : AiQuizStreamEvent

    /** 完成：携带最终题包（服务端生成失败时给的是确定性兜底题）。 */
    data class Completed(val quiz: AiQuiz) : AiQuizStreamEvent

    /**
     * 服务端拒发预生成：当天该预生成的套已经发过（用户玩完了当天的量）。
     *
     * 这不是故障 —— 正式的出题请求仍会按需生成，只是要现场跑一遍，所以与「预生成失败」
     * 分开表达：界面该说「今天的题已玩完，再开一局会现场生成」，而不是「没备好」。
     */
    data object DailySetsDone : AiQuizStreamEvent
}

/** 预生成拒发码：与 auth-worker 的 PREFETCH_DAILY_SETS_DONE 逐字对应。 */
const val QUIZ_PREWARM_DAILY_SETS_DONE = "PREFETCH_DAILY_SETS_DONE"

/** 每日知识阶段：CANDIDATE=挑选候选知识，REVIEW=复核改写。 */
enum class AiDailyStage { CANDIDATE, REVIEW }

enum class AiDailyStageStatus { START, DONE }

/**
 * 每日知识流式事件（服务端 /api/ai/daily/stream 的 NDJSON 行）。
 * 两段式生成实测数十秒，进度事件让加载页显示真实阶段与生成量；Ping 仅用于保活连接。
 */
sealed interface AiDailyStreamEvent {
    /** 阶段开始/结束；expectedChars 为该阶段预期输出字符数，用于把 chars 换算成百分比。 */
    data class Stage(
        val stage: AiDailyStage,
        val status: AiDailyStageStatus,
        val expectedChars: Int
    ) : AiDailyStreamEvent

    /** 生成中：chars 为当前阶段已生成的字符数。 */
    data class Progress(val stage: AiDailyStage, val chars: Int) : AiDailyStreamEvent

    /** 心跳：服务端每 10s 至少发一次，UI 无需展示。 */
    data object Ping : AiDailyStreamEvent

    /** 完成：携带最终知识（服务端生成失败时给的是确定性兜底内容）。 */
    data class Completed(val daily: AiDailyKnowledge) : AiDailyStreamEvent
}

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
    val maxScore: Int = 0,
    val difficulty: String = "medium",
    val knowledgePoint: String = "",
    val sourceTitle: String? = null,
    val answerRationale: String = "",
    val distractorRationale: String = "",
    val explanation: String? = null,
    val correctOptionIds: List<String> = emptyList(),
    // 跨学科学习字段：服务端缺失时保持空值，客户端仍可展示旧题。
    val subject: String = "",
    val concept: String = "",
    val learningTakeaway: String = "",
    // Worker 返回一段可读的证据说明，而不是数组；保持 Android/Worker JSON 契约一致。
    val evidenceUsed: String = ""
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
    val correctOptionIds: List<String> = emptyList(),
    val difficulty: String = "medium",
    val knowledgePoint: String = "",
    val sourceTitle: String? = null,
    val answerRationale: String = "",
    val distractorRationale: String = "",
    // Worker 新题字段，均有默认值以兼容历史缓存和旧响应。
    val subject: String = "",
    val concept: String = "",
    val learningTakeaway: String = "",
    val evidenceUsed: String = ""
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
    val correctAnswer: JsonElement? = null,
    val answerRationale: String = "",
    val distractorRationale: String = "",
    val subject: String = "",
    val concept: String = "",
    val learningTakeaway: String = "",
    val evidenceUsed: String = ""
)

@Serializable
data class AiQuizQuestionResult(
    val questionId: String,
    val score: Int,
    val correct: Boolean,
    val explanation: String,
    val correctOptionIds: List<String> = emptyList(),
    val correctAnswer: String? = null,
    val answerRationale: String = "",
    val distractorRationale: String = "",
    val subject: String = "",
    val concept: String = "",
    val learningTakeaway: String = "",
    val evidenceUsed: String = ""
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

/** 出分页难度反馈档位，wireValue 对接服务端契约 easy/just_right/hard。 */
@Serializable
enum class AiQuizDifficulty(val wireValue: String) {
    EASY("easy"),
    JUST_RIGHT("just_right"),
    HARD("hard");

    companion object {
        /** rememberSaveable 存的是 name，恢复时安全解析，脏值返回 null。 */
        fun fromName(name: String?): AiQuizDifficulty? =
            name?.let { runCatching { valueOf(it) }.getOrNull() }
    }
}

/** POST /api/ai/quiz/feedback 请求体：action 固定 quiz.feedback。 */
@Serializable
data class AiQuizFeedbackRequest(
    val action: String = "quiz.feedback",
    val quizId: String,
    val difficulty: String
)

@Serializable
data class AiQuizFeedbackDto(
    val success: Boolean = false
)

/** 闯关历史（离线可浏览）：最近一次结果 + 历史最高分。按 friendId 分区持久化。 */
@Serializable
data class AiQuizHistory(
    val bestScore: Int = 0,
    val lastResult: AiQuizResult? = null
)

/** 今日影视知识的多语言契约。locale 标签区分大小写，避免 Worker 端做隐式猜测。 */
// subject 是 Worker 内部受控学科键，客户端展示前需按 locale 本地化。
object AiDailyKnowledgeContract {
    const val DEFAULT_LOCALE = "zh-CN"
    const val CACHE_SCHEMA_VERSION = "unit-v1"

    /** 本地历史是新存储键；版本不匹配时不读取也不覆盖，避免未来结构被旧版本写坏。 */
    const val HISTORY_SCHEMA_VERSION = 1
    const val HISTORY_RETENTION_DAYS = 30
    const val HISTORY_MAX_ENTRIES = 50

    /** P0 只开放四种内容语言，其他值由 Repository 在请求和缓存前拦截。 */
    val SUPPORTED_LOCALES = setOf("zh-CN", "en-US", "ja-JP", "ko-KR")
}

/** 知识内容反馈：对应设计中的“有帮助 / 太泛 / 关系弱 / 太难 / 片透多”。 */
@Serializable
enum class AiDailyKnowledgeContentFeedback {
    HELPFUL,
    TOO_BROAD,
    WEAK_RELATION,
    TOO_HARD,
    TOO_MUCH_SPOILER
}

@Serializable
data class AiDailyRelatedMediaDto(
    val title: String = "",
    val mediaType: String = "",
    val traktId: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null
)

@Serializable
data class AiDailySourceDto(
    val name: String = "",
    val url: String = "",
    val evidence: String = ""
)

/** 概念插图状态：Worker 后台生成，URL 是 10 分钟短期签名地址。 */
@Serializable
data class AiDailyIllustrationDto(
    val status: String = "unavailable",
    val role: String = "concept_illustration",
    val url: String? = null,
    val styleVersion: String = "",
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val urlExpiresAt: Long? = null
)

@Serializable
data class AiDailyIllustration(
    val status: String,
    val role: String = "concept_illustration",
    val url: String? = null,
    val styleVersion: String = "",
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val urlExpiresAt: Long? = null
) {
    val isReady: Boolean
        get() = status == "ready" && !url.isNullOrBlank()
}

@Serializable
data class AiDailyCheckQuestionDto(
    val prompt: String = "",
    val options: List<AiQuizOption> = emptyList(),
    val correctOptionIds: List<String> = emptyList(),
    val explanation: String = ""
)

@Serializable
data class AiDailyKnowledgeDto(
    val id: String = "",
    val title: String = "",
    val fact: String = "",
    val explanation: String = "",
    val sourceName: String = "",
    val sourceUrl: String? = null,
    val publishedAt: Long? = null,
    val characterLine: String? = null,
    val quota: AiQuotaDto? = null,
    val relatedMediaTitle: String? = null,
    val containsSpoiler: Boolean = false,
    // 共享学习单元结构化字段。全部可选，旧 Worker / 旧缓存缺失时仍按旧字段展示。
    val unitId: String? = null,
    val version: Int? = null,
    val locale: String? = null,
    val relationType: String? = null,
    val evidenceMode: String? = null,
    val subjectGroup: String? = null,
    val subject: String? = null,
    val concept: String? = null,
    val takeaway: String? = null,
    val relatedMedia: AiDailyRelatedMediaDto? = null,
    val filmEvidence: String? = null,
    val realWorldExample: String? = null,
    val boundary: String? = null,
    val difficulty: String? = null,
    val spoilerLevel: String? = null,
    val source: AiDailySourceDto? = null,
    val checkQuestion: AiDailyCheckQuestionDto? = null,
    val illustration: AiDailyIllustrationDto? = null
)

@Serializable
data class AiDailyRequest(
    val sessionId: String = "daily",
    val forceRefresh: Boolean = false,
    val watched: List<AiWatchedTitleDto> = emptyList(),
    val locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
)

@Serializable
data class AiDailyRelatedMedia(
    val title: String = "",
    val mediaType: String = "",
    val traktId: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null
)

@Serializable
data class AiDailySource(
    val name: String = "",
    val url: String = "",
    val evidence: String = ""
)

@Serializable
data class AiDailyCheckQuestion(
    val prompt: String = "",
    val options: List<AiQuizOption> = emptyList(),
    val correctOptionIds: List<String> = emptyList(),
    val explanation: String = ""
)

@Serializable
data class AiDailyKnowledge(
    val id: String,
    val unitId: String? = null,
    val title: String,
    val fact: String,
    val explanation: String,
    val sourceName: String,
    val sourceUrl: String,
    val publishedAt: Long?,
    val characterLine: String?,
    val quota: AiQuota? = null,
    val relatedMediaTitle: String? = null,
    val containsSpoiler: Boolean = false,
    // 以下结构化字段默认空值，保证旧 domain 缓存可继续反序列化。
    val version: Int? = null,
    val locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE,
    val relationType: String? = null,
    val evidenceMode: String? = null,
    val subjectGroup: String? = null,
    val subject: String? = null,
    val concept: String? = null,
    @SerialName("takeaway")
    private val takeawayValue: String = "",
    val relatedMedia: AiDailyRelatedMedia? = null,
    @SerialName("filmEvidence")
    private val filmEvidenceValue: String = "",
    val realWorldExample: String? = null,
    val boundary: String? = null,
    val difficulty: String? = null,
    val spoilerLevel: String? = null,
    val source: AiDailySource? = null,
    val checkQuestion: AiDailyCheckQuestion? = null,
    // 插图状态每次响应动态装配，不参与本地历史去重；URL 过期后静默刷新链路会重取。
    val illustration: AiDailyIllustration? = null
) {
    // 旧 domain 缓存没有结构化字段时，用旧 fact 兜底，保证升级后旧内容仍可读。
    val takeaway: String
        get() = takeawayValue.ifBlank { fact }

    val filmEvidence: String
        get() = filmEvidenceValue.ifBlank { fact }
}

@Serializable
data class AiDailyKnowledgeQuestionResult(
    val selectedOptionIds: List<String> = emptyList(),
    val correct: Boolean = false,
    val answeredAt: Long = 0L
)

@Serializable
data class AiDailyKnowledgeHistoryRecord(
    val unitId: String,
    val shownDate: String,
    val locale: String,
    val relatedMedia: AiDailyRelatedMedia? = null,
    val subject: String? = null,
    val concept: String? = null,
    // 完整内容随历史落盘，历史页和当天离线回看都不需要再请求网络。
    val knowledge: AiDailyKnowledge,
    val questionCompleted: Boolean = false,
    val questionResult: AiDailyKnowledgeQuestionResult? = null,
    val contentFeedback: AiDailyKnowledgeContentFeedback? = null,
    val difficultyFeedback: AiQuizDifficulty? = null,
    // 毫秒时间戳可能相同；单调序列保证同一天多次展示时能稳定判断“最近展示”。
    val sequence: Long = 0L,
    val shownAt: Long = 0L,
    val updatedAt: Long = shownAt
)

@Serializable
data class AiDailyKnowledgeHistory(
    val schemaVersion: Int = AiDailyKnowledgeContract.HISTORY_SCHEMA_VERSION,
    val records: List<AiDailyKnowledgeHistoryRecord> = emptyList()
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
        quota = effectiveQuota?.toDomain(),
        nameSignals = nameSignals.map { AiNameSignal(it.text, it.interpretation) },
        nicknameSignature = nicknameSignature
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
        quota = effectiveQuota?.toDomain(),
        profileKeywords = profileKeywords.ifEmpty { taste },
        profileSentence = profileSentence.ifBlank { tasteProfile.ifBlank { taste.joinToString("、") } },
        evidence = evidence.map {
            AiTasteEvidence(
                title = it.title,
                signal = it.signal,
                inference = it.inference,
                confidence = it.confidence
            )
        }
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
            mediaTitle = question.mediaTitle?.takeIf { it.isNotBlank() }
                ?: question.sourceTitle?.takeIf { it.isNotBlank() },
            quote = question.quote,
            maxScore = question.maxScore,
            difficulty = question.difficulty.ifBlank { "medium" },
            knowledgePoint = question.knowledgePoint,
            sourceTitle = question.sourceTitle?.takeIf { it.isNotBlank() } ?: question.mediaTitle,
            answerRationale = question.answerRationale.ifBlank { question.explanation.orEmpty() },
            distractorRationale = question.distractorRationale,
            explanation = question.explanation,
            correctOptionIds = question.correctOptionIds,
            subject = question.subject,
            concept = question.concept,
            learningTakeaway = question.learningTakeaway,
            evidenceUsed = question.evidenceUsed
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
            correctAnswer = it.correctAnswer.toAnswerText(),
            answerRationale = it.answerRationale,
            distractorRationale = it.distractorRationale,
            subject = it.subject,
            concept = it.concept,
            learningTakeaway = it.learningTakeaway,
            evidenceUsed = it.evidenceUsed
        )
    },
    quota = (outerQuota ?: quota)?.toDomain()
)

fun AiDailyKnowledgeDto.toDomain(outerQuota: AiQuotaDto? = null): AiDailyKnowledge {
    val relatedMediaDomain = relatedMedia?.toDomain()
        ?: relatedMediaTitle?.takeIf { it.isNotBlank() }?.let { AiDailyRelatedMedia(title = it) }
    val sourceDomain = source?.toDomain()
        ?: AiDailySource(name = sourceName, url = sourceUrl.orEmpty()).takeIf {
            it.name.isNotBlank() || it.url.isNotBlank()
        }
    return AiDailyKnowledge(
        id = id,
        unitId = unitId,
        title = title,
        fact = fact,
        explanation = explanation,
        // 旧 UI 字段优先保留原值；新结构化响应缺旧字段时用结构化字段补齐。
        sourceName = sourceName.ifBlank { source?.name.orEmpty() },
        sourceUrl = sourceUrl ?: source?.url.orEmpty(),
        publishedAt = publishedAt,
        characterLine = characterLine,
        quota = (outerQuota ?: quota)?.toDomain(),
        relatedMediaTitle = relatedMediaTitle
            ?: relatedMediaDomain?.title?.takeIf { it.isNotBlank() },
        containsSpoiler = containsSpoiler,
        version = version,
        locale = locale?.takeIf { it.isNotBlank() } ?: AiDailyKnowledgeContract.DEFAULT_LOCALE,
        relationType = relationType,
        evidenceMode = evidenceMode,
        subjectGroup = subjectGroup,
        subject = subject,
        concept = concept,
        takeawayValue = takeaway.orEmpty(),
        relatedMedia = relatedMediaDomain,
        filmEvidenceValue = filmEvidence.orEmpty(),
        realWorldExample = realWorldExample,
        boundary = boundary,
        difficulty = difficulty,
        spoilerLevel = spoilerLevel,
        source = sourceDomain,
        checkQuestion = checkQuestion?.toDomain(),
        illustration = illustration?.let {
            AiDailyIllustration(
                status = it.status,
                role = it.role,
                url = it.url,
                styleVersion = it.styleVersion,
                mimeType = it.mimeType,
                width = it.width,
                height = it.height,
                urlExpiresAt = it.urlExpiresAt
            )
        }
    )
}

private fun AiDailyRelatedMediaDto.toDomain(): AiDailyRelatedMedia = AiDailyRelatedMedia(
    title = title,
    mediaType = mediaType,
    traktId = traktId,
    tmdbId = tmdbId,
    imdbId = imdbId,
    doubanId = doubanId
)

private fun AiDailySourceDto.toDomain(): AiDailySource = AiDailySource(
    name = name,
    url = url,
    evidence = evidence
)

private fun AiDailyCheckQuestionDto.toDomain(): AiDailyCheckQuestion = AiDailyCheckQuestion(
    prompt = prompt,
    options = options,
    correctOptionIds = correctOptionIds,
    explanation = explanation
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
