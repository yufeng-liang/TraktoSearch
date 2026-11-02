package com.tracktosearch.data.ai.orchestrate

import javax.inject.Inject
import com.tracktosearch.data.ai.AiDailyCheckQuestion
import com.tracktosearch.data.ai.AiDailyIllustration
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiDailyRelatedMedia
import com.tracktosearch.data.ai.AiDailySource
import com.tracktosearch.data.ai.AiDailyStage
import com.tracktosearch.data.ai.AiDailyStageStatus
import com.tracktosearch.data.ai.AiDailyStreamEvent
import com.tracktosearch.data.ai.AiDetailEnvironmentDto
import com.tracktosearch.data.ai.AiDetailMediaDto
import com.tracktosearch.data.ai.AiDetailRecommendation
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.AiNameSignal
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizOption
import com.tracktosearch.data.ai.AiQuizStage
import com.tracktosearch.data.ai.AiQuizStageStatus
import com.tracktosearch.data.ai.AiQuizStreamEvent
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.ai.AiTasteAnalysis
import com.tracktosearch.data.ai.AiTasteEvidence
import com.tracktosearch.data.ai.AiWatchedTitleDto
import com.tracktosearch.data.ai.prompt.AiCharacterPersona
import com.tracktosearch.data.ai.prompt.AiCharacters
import com.tracktosearch.data.ai.prompt.AiMessage
import com.tracktosearch.data.ai.prompt.DAILY_LOCALES
import com.tracktosearch.data.ai.prompt.DailyKnowledgeMovieInput
import com.tracktosearch.data.ai.prompt.DailyKnowledgeUnit
import com.tracktosearch.data.ai.prompt.DailyRecentUsage
import com.tracktosearch.data.ai.prompt.DetailCandidate
import com.tracktosearch.data.ai.prompt.DetailEnvironmentInput
import com.tracktosearch.data.ai.prompt.DetailMedia
import com.tracktosearch.data.ai.prompt.DetailProfile
import com.tracktosearch.data.ai.prompt.DetailRequestInput
import com.tracktosearch.data.ai.prompt.PromptWatchMovie
import com.tracktosearch.data.ai.prompt.QUIZ_QUESTION_SLOT_COUNT
import com.tracktosearch.data.ai.prompt.QUIZ_REVIEW_SHAPE_SPEC
import com.tracktosearch.data.ai.prompt.QUIZ_SLOT_UNIT_SPEC
import com.tracktosearch.data.ai.prompt.QUIZ_UNIT_SLOT_COUNT
import com.tracktosearch.data.ai.prompt.QuestionSlot
import com.tracktosearch.data.ai.prompt.SlotMovie
import com.tracktosearch.data.ai.prompt.TtsScene
import com.tracktosearch.data.ai.prompt.UnitSlot
import com.tracktosearch.data.ai.prompt.dailyCandidateMessages
import com.tracktosearch.data.ai.prompt.dailyRepairHintFor
import com.tracktosearch.data.ai.prompt.dailyReviewMessages
import com.tracktosearch.data.ai.prompt.detailMessages
import com.tracktosearch.data.ai.prompt.greetingMessages
import com.tracktosearch.data.ai.prompt.planQuestionSlots
import com.tracktosearch.data.ai.prompt.planUnitSlots
import com.tracktosearch.data.ai.prompt.questionSlotMessages
import com.tracktosearch.data.ai.prompt.rankMovieTitlesForSlots
import com.tracktosearch.data.ai.prompt.recommendationMessages
import com.tracktosearch.data.ai.prompt.recommendationReason
import com.tracktosearch.data.ai.prompt.tasteMessages
import com.tracktosearch.data.ai.prompt.unitSlotMessages
import com.tracktosearch.data.ai.provider.AiChatRequest
import com.tracktosearch.data.ai.provider.AiFeatureSlot
import com.tracktosearch.data.ai.provider.AiImageRequest
import com.tracktosearch.data.ai.provider.AiProviderClientFactory
import com.tracktosearch.data.ai.provider.AiProviderException
import com.tracktosearch.data.ai.provider.AiProviderSettingsRepository
import com.tracktosearch.data.ai.provider.AiTtsRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// 编排档位常量（对拍任务书：temperature 取任务书定案，与 TS 服务端实际值 0.7/0.2 的
// 差异在交付报告注明）。
// ---------------------------------------------------------------------------

/** 创作类功能（greeting/taste/quiz/daily）的采样温度。 */
private const val TEMPERATURE_CREATIVE = 0.8

/** 精确类功能（detail/rank）的采样温度。 */
private const val TEMPERATURE_PRECISE = 0.3

/** quiz 单元槽位最大输出 token（对拍 QUIZ_SLOT_BUDGET.maxTokensUnit=2600）。 */
private const val QUIZ_MAX_TOKENS_UNIT = 2600

/** quiz 题位槽位最大输出 token（对拍 QUIZ_SLOT_BUDGET.maxTokensQuestion=1800）。 */
private const val QUIZ_MAX_TOKENS_QUESTION = 1800

/** 槽位修复轮数：round 0..2 共 3 次调用（对拍 QUIZ_SLOT_BUDGET.repairRounds=2）。 */
private const val QUIZ_REPAIR_ROUNDS = 2

/** 单元/题位槽位并发（对拍 mapWithGate 并发 2）。 */
private const val QUIZ_SLOT_CONCURRENCY = 2

/** 单套题最大 LLM 调用次数（对拍 QUIZ_SLOT_BUDGET.maxCallsPerSet=80）。 */
private const val QUIZ_MAX_CALLS_PER_SET = 80

/** 单套题最大耗时毫秒（对拍 QUIZ_SLOT_BUDGET.maxElapsedMs=660_000）。 */
private const val QUIZ_MAX_ELAPSED_MS = 660_000L

/** 出题最少已看片数（对拍 handleQuizStream 的 At least 7 watched movies）。 */
private const val QUIZ_MIN_MOVIES = 7

/** 概念去重后至少保留的单元数，不足则整包失败（对拍 keptPairs<3 → 走兜底）。 */
private const val QUIZ_MIN_KEPT_UNITS = 3

/** daily 候选阶段最大输出 token（对拍 2600）。 */
private const val DAILY_MAX_TOKENS_CANDIDATE = 2600

/** daily 二审阶段最大输出 token（对拍 3000）。 */
private const val DAILY_MAX_TOKENS_REVIEW = 3000

/** daily 阶段修复轮数（对拍 DAILY_REPAIR_ROUNDS=2，round 0..2 共 3 次）。 */
private const val DAILY_REPAIR_ROUNDS = 2

/** daily 阶段预期字符数（对拍 DAILY_STAGE_EXPECTED_CHARS=1800）。 */
private const val DAILY_STAGE_EXPECTED_CHARS = 1800

/** detail 分析最大输出 token（对拍 maxCompletionTokens=700）。 */
private const val DETAIL_MAX_TOKENS = 700

/** 推荐排序最大输出 token（对拍 maxCompletionTokens=500）。 */
private const val RANK_MAX_TOKENS = 500

/** 排序候选上限（对拍 MAX_CANDIDATES=60）。 */
private const val RANK_MAX_CANDIDATES = 60

/** TTS 文本上限（对拍 'TTS text is too long' 500）。 */
private const val TTS_MAX_TEXT_LENGTH = 500

/** 本地 TTS 默认音色：服务端由 voiceStatus 就绪后决定音色，本地无该状态，取 OpenAI 兼容默认音色。 */
private const val TTS_DEFAULT_VOICE = "alloy"

/**
 * 角色 → 标准音色固定映射（设计定案：voiceDesignPrompt 无法直喂 OpenAI 兼容 /audio/speech，
 * 每个角色映射一个标准音色；「音色跟随角色」关闭或角色缺映射时回 DEFAULT）。
 */
private val TTS_VOICE_BY_CHARACTER = mapOf(
    "chiikawa" to "nova",
    "hachiware" to "shimmer",
    "usagi" to "echo",
    "flying-squirrel" to "fable",
    "shisa" to "ash",
    "kurimanju" to "onyx",
    "rakko" to "alloy",
)

/** 已看条目上限（对拍 MAX_WATCHED_ITEMS=60）。 */
private const val MAX_WATCHED_ITEMS = 60

/** 题包固定标题（对拍 publicQuiz title）。 */
private const val QUIZ_TITLE = "你真的看懂这些影视了吗"

/** mediaKey 形状（对拍 ^(movie|show):[A-Za-z0-9._:-]{1,96}$）。 */
private val MEDIA_KEY_REGEX = Regex("^(movie|show):[A-Za-z0-9._:-]{1,96}$")

/** imdb id 形状（对拍 tt\d{1,12}i?）。 */
private val IMDB_ID_REGEX = Regex("^tt\\d{1,12}i?$")

/** 无信息昵称（对拍 /^[\d\W_]+$/u，Java 默认 \W 即非 ASCII 字母数字下划线）。 */
private val NOISE_NICKNAME_REGEX = Regex("^[\\d\\W_]+$")

/**
 * BYOK 本地编排引擎：把 auth-worker 的 LLM 编排（greeting/taste/quiz/daily/detail/rank/tts）
 * 原样移植到本地，LLM 调用经 [AiProviderClient] 直连用户自配 Provider。
 *
 * 错误契约：所有失败一律抛 [AiProviderException]
 * —— 校验耗尽/JSON 解析失败 → INVALID_RESPONSE；入参不合法 → INVALID_REQUEST；
 * Provider 未配置 → PROVIDER_INVALID（由 [AiProviderSettingsRepository.resolve] 抛出）。
 *
 * 与服务端兜底策略的差异（任务约束「校验耗尽必须抛错」）：
 * greeting/taste/quiz/daily 的最终兜底文案（fallbackGreeting/fallbackTaste/fallbackQuizQuestions/
 * fallbackDailyKnowledgeUnit）不移植，统一抛 INVALID_RESPONSE，兜底交由仓库层决定；
 * quiz 槽位内部的降级题（fallbackQuestionForSlot）保留——它是槽位管线维持
 * 「13 题 = 10 单选 + 2 多选 + 1 简答」不变量的内部逻辑。
 */
// BYOK：构造函数注入，由 Hilt 装配（DI 在 AiProviderModule 提供 settingsRepository）。
class AiOrchestrator @Inject constructor(
    private val settingsRepository: AiProviderSettingsRepository,
) {

    // ------------------------------------------------------------------
    // 编排结果载体
    // ------------------------------------------------------------------

    /** quiz 编排产出：题包之外保留内部题（含判分用 correctAnswer），判分零信息损失。 */
    data class OrchestratedQuiz(
        val quiz: AiQuiz,
        val questions: List<AiOrchestrateValidate.OrchestratedQuestion>,
    )

    /** 单元槽位产出：槽位与通过校验的单元成对，供题位回查。 */
    private data class UnitOutcome(
        val slot: UnitSlot,
        val unit: AiOrchestrateValidate.OrchestratedUnit,
    )

    /** 单套题调用预算（对拍 QUIZ_SLOT_BUDGET：调用次数 + 总耗时双闸）。 */
    private class QuizCallBudget {
        private val startedAtNanos = System.nanoTime()
        private val calls = AtomicInteger()

        fun countCall() {
            calls.incrementAndGet()
        }

        fun exceeded(): Boolean =
            calls.get() >= QUIZ_MAX_CALLS_PER_SET ||
                (System.nanoTime() - startedAtNanos) / 1_000_000L > QUIZ_MAX_ELAPSED_MS
    }

    // ------------------------------------------------------------------
    // ① greeting：昵称问候（对拍 handleGreeting + normalizeGreeting）
    // ------------------------------------------------------------------

    /**
     * 生成角色问候。调用 greetingMessages 提示词 → 解析 JSON → normalizeGreeting 校验。
     * 失败抛 INVALID_RESPONSE（服务端的 parseTextResultOrFallback 兜底由仓库层决定）。
     */
    suspend fun generateGreeting(characterId: String, nickname: String): AiGreeting {
        val character = AiCharacters.findCharacter(characterId)
            ?: throw AiOrchestrateError.invalidRequest("Unknown character: $characterId")
        val payload = chatJson(
            AiFeatureSlot.GREETING,
            greetingMessages(character, nickname),
            TEMPERATURE_CREATIVE,
            null,
        )
        val record = payload as? JsonObject
            ?: throw AiOrchestrateError.invalidResponse("AI greeting must be an object")
        val greeting = AiOrchestrateJson.requiredText(record, listOf("greeting", "text"), "greeting")
        val meaning = AiOrchestrateJson.requiredText(record, listOf("nicknameMeaning", "meaning"), "nickname meaning")
        val comment = AiOrchestrateJson.requiredText(record, listOf("comment", "点评"), "comment")
        val nameSignals = normalizeNameSignals(record["nameSignals"] ?: record["signals"], nickname)
        val signature = AiOrchestrateJson.optionalText(record, listOf("nicknameSignature", "signature"))
            ?: fallbackNicknameSignature(nickname)
        return AiGreeting(
            nickname = nickname,
            greeting = greeting,
            // spokenText 注入口头禅（对拍 handleGreeting 的 injectCatchphrase）。
            spokenText = injectCatchphrase(character, greeting),
            nicknameMeaning = meaning,
            comment = comment,
            audio = null,
            nameSignals = nameSignals,
            nicknameSignature = signature,
        )
    }

    /** 昵称信号校验（对拍 normalizeNameSignals：字符串项原样收、对象项取别名组、最多 4 条）。 */
    private fun normalizeNameSignals(value: JsonElement?, nickname: String): List<AiNameSignal> {
        if (value == null || value is JsonNull) return fallbackNameSignals(nickname)
        val array = value as? JsonArray
            ?: throw AiOrchestrateError.invalidResponse("AI nickname signals are invalid")
        if (array.isEmpty()) return fallbackNameSignals(nickname)
        val signals = array.take(4).map { item ->
            when (item) {
                is JsonPrimitive -> AiNameSignal(text = item.content, interpretation = "")
                is JsonObject -> AiNameSignal(
                    text = AiOrchestrateJson.requiredText(item, listOf("text", "token", "word"), "name signal text").take(120),
                    interpretation = AiOrchestrateJson.requiredText(
                        item, listOf("interpretation", "meaning", "reason"), "name signal interpretation",
                    ).take(400),
                )
                else -> throw AiOrchestrateError.invalidResponse("AI nickname signals are invalid")
            }
        }
        return signals.ifEmpty { fallbackNameSignals(nickname) }
    }

    /** 兜底昵称信号（对拍 fallbackNameSignals：无信息昵称返回空表）。 */
    private fun fallbackNameSignals(nickname: String): List<AiNameSignal> {
        val normalized = nickname.trim()
        if (normalized.isEmpty() || NOISE_NICKNAME_REGEX.matches(normalized) ||
            normalized.codePointCount(0, normalized.length) < 2
        ) {
            return emptyList()
        }
        return listOf(
            AiNameSignal(
                text = normalized,
                interpretation = "直接引用昵称原文；仅凭这组文字，无法可靠推断更多个人信息。",
            ),
        )
    }

    /** 兜底昵称签名（对拍 fallbackNicknameSignature）。 */
    private fun fallbackNicknameSignature(nickname: String): String =
        if (fallbackNameSignals(nickname).isEmpty()) {
            "这个昵称提供的文字线索有限，真正的观影选择比名字更能说明你的口味。"
        } else {
            "这个昵称像一个有记忆点的标记；更具体的个性，留给你的片单来说明。"
        }

    // ------------------------------------------------------------------
    // ② taste：口味画像（对拍 handleTaste + normalizeTaste）
    // ------------------------------------------------------------------

    /** 生成口味画像。推荐条目不带海报/ID（AiRecommendation 域字段置空），由仓库层核验回填。 */
    suspend fun analyzeTaste(nickname: String, watched: List<AiWatchedTitleDto>): AiTasteAnalysis {
        val movies = watched.take(MAX_WATCHED_ITEMS)
        val promptMovies = movies.map { item ->
            PromptWatchMovie(
                title = item.title,
                mediaType = item.mediaType,
                year = item.year,
                genres = item.genres,
                evidence = AiOrchestrateJson.buildEvidenceLines(item),
            )
        }
        val payload = chatJson(
            AiFeatureSlot.TASTE,
            tasteMessages(nickname, promptMovies),
            TEMPERATURE_CREATIVE,
            null,
        )
        val record = payload as? JsonObject
            ?: throw AiOrchestrateError.invalidResponse("AI taste must be an object")
        return normalizeTaste(record, movies.map { it.title })
    }

    /** taste 输出校验（对拍 normalizeTaste：推荐 ≤8、标题去重、画像句/关键词缺省规则）。 */
    private fun normalizeTaste(record: JsonObject, watchedTitles: List<String>): AiTasteAnalysis {
        val recommendationsArray = record["recommendations"] as? JsonArray
            ?: throw AiOrchestrateError.invalidResponse("AI recommendation format is invalid")
        if (recommendationsArray.size > 8) {
            throw AiOrchestrateError.invalidResponse("AI recommendation format is invalid")
        }
        val watchedKeys = watchedTitles
            .map { it.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE) }
            .toSet()
        val taste = AiOrchestrateJson.requiredTextArray(record, listOf("taste", "traits"), "taste")
        val recommendations = ArrayList<AiRecommendation>()
        val seenRecTitles = HashSet<String>()
        recommendationsArray.forEachIndexed { index, item ->
            val itemRecord = item as? JsonObject
                ?: throw AiOrchestrateError.invalidResponse("AI recommendation format is invalid")
            val recommendation = normalizeRecommendation(itemRecord, index, watchedKeys)
            if (!seenRecTitles.add(recommendation.title)) {
                throw AiOrchestrateError.invalidResponse("Recommendations contain duplicate titles")
            }
            recommendations.add(recommendation)
        }
        val profileKeywords = AiOrchestrateJson.readOptionalAiStringArray(record["profileKeywords"])
            .ifEmpty { taste.take(6) }
        val tasteProfile = AiOrchestrateJson.optionalText(record, listOf("profileSentence", "tasteProfile", "profile"))
            ?: taste.joinToString("、")
        val roast = AiOrchestrateJson.requiredText(record, listOf("roast", "review"), "taste roast")
        val highlights = AiOrchestrateJson.requiredTextArray(record, listOf("highlights", "taste"), "taste highlights")
        return AiTasteAnalysis(
            roast = roast,
            tasteProfile = tasteProfile,
            highlights = highlights,
            recommendations = recommendations,
            profileKeywords = profileKeywords,
            profileSentence = tasteProfile,
            evidence = normalizeTasteEvidence(record["evidence"], watchedTitles),
        )
    }

    /** 单条推荐校验（对拍 normalizeRecommendation：撞已看片名/年份区间/媒体类型）。 */
    private fun normalizeRecommendation(
        record: JsonObject,
        index: Int,
        watchedKeys: Set<String>,
    ): AiRecommendation {
        val title = AiOrchestrateJson.requiredText(record, listOf("title", "name"), "recommendation title")
        if (title.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE) in watchedKeys) {
            throw AiOrchestrateError.invalidResponse("Recommendation duplicates a watched title")
        }
        val year = AiOrchestrateJson.optionalInteger(record["year"])
        if (year != null && (year < 1900 || year > 2035)) {
            throw AiOrchestrateError.invalidResponse("Recommendation year is invalid")
        }
        val mediaType = AiOrchestrateJson.optionalText(record, listOf("mediaType")) ?: "movie"
        if (mediaType != "movie" && mediaType != "show") {
            throw AiOrchestrateError.invalidResponse("Recommendation media type is invalid")
        }
        val reason = AiOrchestrateJson.requiredText(record, listOf("reason", "why"), "recommendation reason")
        return AiRecommendation(
            id = "r" + (index + 1),
            mediaType = mediaType,
            title = title,
            year = year,
            posterUrl = null,
            posterPath = null,
            tmdbId = null,
            traktId = null,
            imdbId = null,
            doubanId = null,
            reason = reason,
        )
    }

    /** 口味证据校验（对拍 normalizeTasteEvidence：必须引用已看片名、去重、置信度归一）。 */
    private fun normalizeTasteEvidence(
        value: JsonElement?,
        watchedTitles: List<String>,
    ): List<AiTasteEvidence> {
        if (value == null || value is JsonNull) return fallbackTasteEvidence(watchedTitles)
        val array = value as? JsonArray
            ?: throw AiOrchestrateError.invalidResponse("AI taste evidence is invalid")
        if (array.isEmpty()) return fallbackTasteEvidence(watchedTitles)
        val watchedKeys = watchedTitles
            .map { it.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE) }
            .toSet()
        val seenTitles = HashSet<String>()
        val evidence = array.take(5).map { item ->
            val record = item as? JsonObject
                ?: throw AiOrchestrateError.invalidResponse("AI taste evidence is invalid")
            val title = AiOrchestrateJson.requiredText(record, listOf("title", "mediaTitle"), "taste evidence title")
            if (title.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE) !in watchedKeys) {
                throw AiOrchestrateError.invalidResponse("Taste evidence must reference watched titles")
            }
            if (!seenTitles.add(title)) {
                throw AiOrchestrateError.invalidResponse("Taste evidence contains duplicate titles")
            }
            val confidenceRaw = AiOrchestrateJson.optionalText(record, listOf("confidence"))?.lowercase()
            AiTasteEvidence(
                title = title,
                signal = AiOrchestrateJson.requiredText(record, listOf("signal", "observed"), "taste evidence signal").take(400),
                inference = AiOrchestrateJson.requiredText(record, listOf("inference", "conclusion"), "taste evidence inference").take(400),
                confidence = if (confidenceRaw in CONFIDENCE_LEVELS) confidenceRaw!! else "low",
            )
        }
        return evidence.ifEmpty { fallbackTasteEvidence(watchedTitles) }
    }

    /** 兜底口味证据（对拍 fallbackTasteEvidence：前 5 部已看片、低置信占位）。 */
    private fun fallbackTasteEvidence(watchedTitles: List<String>): List<AiTasteEvidence> =
        watchedTitles.take(5).map { title ->
            AiTasteEvidence(
                title = title,
                signal = "出现在你的已看记录中。",
                inference = "它只是样本的一部分，单凭这一部不能代表稳定偏好。",
                confidence = "low",
            )
        }

    // ------------------------------------------------------------------
    // ③ quiz：槽位化出题（对拍 generateQuizBySlots + handleQuizStream）
    // ------------------------------------------------------------------

    /**
     * 一次性出题（无进度事件）：内部跑与 [generateQuizStream] 同一条槽位管线。
     * 返回值保留内部题（含 correctAnswer），交 [AiQuizScoring.scoreOrchestrated] 判分。
     */
    suspend fun generateQuiz(
        nickname: String,
        watched: List<AiWatchedTitleDto>,
        quizId: String,
        difficultyHint: String? = null,
    ): OrchestratedQuiz = runQuizPipeline(nickname, watched, quizId, difficultyHint) { }

    /**
     * 流式出题：阶段 start/done + 槽位完成进度 + 最终 Completed 事件。
     * 事件序列与 getQuizStream 消费方约定一致（Stage → Progress… → Completed）。
     */
    fun generateQuizStream(
        nickname: String,
        watched: List<AiWatchedTitleDto>,
        quizId: String,
        difficultyHint: String? = null,
    ): Flow<AiQuizStreamEvent> = channelFlow {
        val result = runQuizPipeline(nickname, watched, quizId, difficultyHint) { event -> send(event) }
        send(AiQuizStreamEvent.Completed(result.quiz))
    }

    /**
     * 槽位化出题主流程（对拍 generateQuizBySlots，handler.ts:1325-1641）：
     * 4 单元槽（并发 2、修复轮 2）→ 概念去重（<3 整包失败）→ 13 题位槽（并发 2、修复轮 2、
     * 失败降级兜底题）→ 跨槽位考点撞车修复 → 整包质量复核。
     */
    private suspend fun runQuizPipeline(
        nickname: String,
        watched: List<AiWatchedTitleDto>,
        quizId: String,
        difficultyHint: String?,
        onEvent: suspend (AiQuizStreamEvent) -> Unit,
    ): OrchestratedQuiz {
        val movies = watched.take(MAX_WATCHED_ITEMS)
        if (movies.size < QUIZ_MIN_MOVIES) {
            throw AiOrchestrateError.invalidRequest("At least 7 watched movies are required")
        }
        val orchestrateMovies = movies.map { AiOrchestrateValidate.watchedToOrchestrateMovie(it) }
        val dailyMovies = movies.map { toDailyMovieInput(it) }
        val slotMovies = movies.map { item ->
            SlotMovie(
                title = item.title,
                mediaType = item.mediaType,
                year = item.year,
                genres = item.genres,
                evidence = AiOrchestrateJson.buildEvidenceLines(item),
            )
        }
        // 难度提示与形状规范直接拼接（对拍 QUIZ_SLOT_UNIT_SPEC + difficultyHint）。
        val difficultySuffix = difficultyHint.orEmpty()
        val budget = QuizCallBudget()

        // ---------- 阶段一：单元槽位 ----------
        onEvent(AiQuizStreamEvent.Stage(AiQuizStage.UNITS, AiQuizStageStatus.START, QUIZ_UNIT_SLOT_COUNT))
        val unitSlots = planUnitSlots(
            quizId,
            rankMovieTitlesForSlots(orchestrateMovies.map { it.title to it.evidence }),
        )
        val unitsFinished = AtomicInteger()
        val unitOutcomes = coroutineScope {
            val gate = Semaphore(QUIZ_SLOT_CONCURRENCY)
            unitSlots.map { slot ->
                async {
                    gate.withPermit {
                        val outcome = runUnitSlot(
                            nickname, slotMovies, dailyMovies, slot, difficultySuffix, budget,
                        )
                        onEvent(AiQuizStreamEvent.Progress(AiQuizStage.UNITS, unitsFinished.incrementAndGet()))
                        outcome
                    }
                }
            }.awaitAll()
        }
        onEvent(AiQuizStreamEvent.Stage(AiQuizStage.UNITS, AiQuizStageStatus.DONE, 0))

        // 概念去重：语义键重复只保留第一格，保留不足 3 个则整包失败。
        val keptUnits = LinkedHashMap<Int, UnitOutcome>()
        val seenConcepts = HashSet<String>()
        for (outcome in unitOutcomes) {
            val key = AiOrchestrateJson.normalizeSemanticKey(outcome.unit.concept)
            if (key.isEmpty() || !seenConcepts.add(key)) continue
            keptUnits[outcome.slot.index] = outcome
        }
        if (keptUnits.size < QUIZ_MIN_KEPT_UNITS) {
            throw AiOrchestrateError.invalidResponse("AI quiz unit concepts are not diverse enough")
        }
        val keptOutcomes = keptUnits.values.toList()
        val unitByIndex = keptUnits.toMap()
        val unitById = keptOutcomes.associate { it.unit.unitId to it.unit }
        val questionSlots = planQuestionSlots(keptOutcomes.map { it.slot.index to it.unit.concept })

        // ---------- 阶段二：题位槽位 ----------
        onEvent(AiQuizStreamEvent.Stage(AiQuizStage.REVIEW, AiQuizStageStatus.START, QUIZ_QUESTION_SLOT_COUNT))
        val assembled = ConcurrentHashMap<Int, AiOrchestrateValidate.OrchestratedQuestion>()
        val degraded = ConcurrentHashMap.newKeySet<Int>()
        val fallbackVariants = ConcurrentHashMap<Int, Int>()
        val movieIndexByTitle = orchestrateMovies.withIndex()
            .associate { (index, movie) -> movie.title to index }
        val questionsFinished = AtomicInteger()

        fun degradedQuestion(slot: QuestionSlot, variant: Int): AiOrchestrateValidate.OrchestratedQuestion {
            val outcome = unitByIndex[slot.unitIndex]
                ?: throw AiOrchestrateError.invalidResponse("题位找不到归属单元")
            val unit = outcome.unit
            val movieIndex = unit.relatedMediaTitle?.let { movieIndexByTitle[it] } ?: 0
            val movie = orchestrateMovies.getOrNull(movieIndex) ?: orchestrateMovies.first()
            return AiOrchestrateValidate.fallbackQuestionForSlot(slot, unit, movie, movieIndex, variant)
        }

        // 首轮出题：失败直接降级兜底题（对拍「全部轮耗尽 → degradedQuestion(slot,0)」）。
        coroutineScope {
            val gate = Semaphore(QUIZ_SLOT_CONCURRENCY)
            questionSlots.map { slot ->
                async {
                    gate.withPermit {
                        val orchestrated = try {
                            runQuestionSlot(
                                nickname, slotMovies, orchestrateMovies,
                                unitByIndex, unitById, slot, difficultySuffix, null, budget,
                            )
                        } catch (error: AiProviderException) {
                            degraded.add(slot.index)
                            fallbackVariants[slot.index] = 0
                            degradedQuestion(slot, 0)
                        }
                        assembled[slot.index] = orchestrated
                        onEvent(AiQuizStreamEvent.Progress(AiQuizStage.REVIEW, questionsFinished.incrementAndGet()))
                    }
                }
            }.awaitAll()
        }

        // 跨槽位考点撞车修复（对拍 knowledgePoint 撞车修复 round 0..repairRounds）。
        for (round in 0..QUIZ_REPAIR_ROUNDS) {
            val seenPoints = LinkedHashMap<String, Int>() // 考点语义键 -> 首次出现的题位号
            val collisions = ArrayList<Pair<QuestionSlot, String>>()
            for (slot in questionSlots) {
                val orchestrated = assembled[slot.index] ?: continue
                val key = AiOrchestrateJson.normalizeSemanticKey(orchestrated.question.knowledgePoint)
                if (key.isEmpty()) continue
                val firstIndex = seenPoints[key]
                if (firstIndex == null) {
                    seenPoints[key] = slot.index
                } else {
                    val firstPoint = assembled[firstIndex]?.question?.knowledgePoint.orEmpty()
                    val hint = "knowledgePoint 与第 $firstIndex 题重复（「$firstPoint」）。" +
                        "本套已用考点：" + seenPoints.keys.joinToString("、") +
                        "。请换一个完全不同于这些的考点，并保证考点原样出现在题干里"
                    collisions.add(slot to hint)
                }
            }
            if (collisions.isEmpty()) break
            coroutineScope {
                val gate = Semaphore(QUIZ_SLOT_CONCURRENCY)
                collisions.map { (slot, hint) ->
                    async {
                        gate.withPermit {
                            if (slot.index in degraded) {
                                // 已降级的格子只换一个兜底变体，不再消耗 LLM 调用。
                                val variant = fallbackVariants.merge(slot.index, 1, Int::plus) ?: 1
                                assembled[slot.index] = degradedQuestion(slot, variant)
                            } else {
                                // 预算闸只拦修复轮（对拍 budgetExhausted 只拦 isRetry）：
                                // 服务端此时抛 AI_UPSTREAM_ERROR 后被本格 catch 降级兜底，13 题不变量优先。
                                if (budget.exceeded()) {
                                    degraded.add(slot.index)
                                    val variant = fallbackVariants.merge(slot.index, 1, Int::plus) ?: 1
                                    assembled[slot.index] = degradedQuestion(slot, variant)
                                } else {
                                    assembled[slot.index] = try {
                                        runQuestionSlot(
                                            nickname, slotMovies, orchestrateMovies,
                                            unitByIndex, unitById, slot, difficultySuffix, hint, budget,
                                        )
                                    } catch (error: AiProviderException) {
                                        degraded.add(slot.index)
                                        val variant = fallbackVariants.merge(slot.index, 1, Int::plus) ?: 1
                                        degradedQuestion(slot, variant)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val finalQuestions = questionSlots.mapNotNull { assembled[it.index] }
        if (finalQuestions.size != QUIZ_QUESTION_SLOT_COUNT) {
            throw AiOrchestrateError.invalidResponse("题位未能凑齐 13 题")
        }
        // 整包质量复核（对拍 validateReviewedQuiz：逐题门槛 + 学科覆盖 3-8）。
        AiOrchestrateValidate.validateReviewedQuiz(finalQuestions, orchestrateMovies, unitById)
        onEvent(AiQuizStreamEvent.Stage(AiQuizStage.REVIEW, AiQuizStageStatus.DONE, 0))

        // 题包影片按实际被引用的片名收窄（对拍 usedTitles/quizMovies）。
        val usedTitles = finalQuestions
            .mapNotNull { it.question.sourceTitle ?: it.question.mediaTitle }
            .toHashSet()
        val quizMovies = orchestrateMovies.filter { it.title in usedTitles }.ifEmpty { orchestrateMovies }
        val quiz = AiQuiz(
            quizId = quizId,
            title = QUIZ_TITLE,
            subtitle = "本轮涉及：" + quizMovies.joinToString("、") { it.title },
            mediaTitles = quizMovies.map { it.title },
            questions = finalQuestions.map { it.question },
            totalScore = 100,
        )
        return OrchestratedQuiz(quiz = quiz, questions = finalQuestions)
    }

    /** 单元槽位执行：修复轮内重试，错误转中文修复提示（对拍单元槽位主循环）。 */
    private suspend fun runUnitSlot(
        nickname: String,
        slotMovies: List<SlotMovie>,
        dailyMovies: List<DailyKnowledgeMovieInput>,
        slot: UnitSlot,
        difficultySuffix: String,
        budget: QuizCallBudget,
    ): UnitOutcome {
        var lastError: AiProviderException? = null
        for (round in 0..QUIZ_REPAIR_ROUNDS) {
            // 预算闸只拦修复轮（对拍 budgetExhausted 只拦 isRetry）。
            if (round > 0 && budget.exceeded()) {
                throw AiOrchestrateError.invalidResponse("出题槽位调用超出预算")
            }
            try {
                val hint = if (round > 0) {
                    dailyRepairHintFor(lastError?.message.orEmpty().take(200))
                } else {
                    null
                }
                val payload = chatJson(
                    AiFeatureSlot.QUIZ,
                    unitSlotMessages(nickname, slotMovies, slot, QUIZ_SLOT_UNIT_SPEC + difficultySuffix, hint),
                    TEMPERATURE_CREATIVE,
                    QUIZ_MAX_TOKENS_UNIT,
                )
                budget.countCall()
                val unit = AiOrchestrateValidate.normalizeQuizSlotUnit(
                    unwrapSlotPayload(payload, "unit"),
                    "zh-CN",
                    dailyMovies,
                )
                AiOrchestrateValidate.assertUnitMatchesSlot(unit, slot)
                return UnitOutcome(slot = slot, unit = unit)
            } catch (error: AiProviderException) {
                lastError = error
            }
        }
        throw AiOrchestrateError.invalidResponse(lastError?.message ?: "单元槽位校验耗尽")
    }

    /** 题位槽位执行：出题 → 回接单元 → 槽位断言 → 质量门槛（对拍 runQuestionSlot）。 */
    private suspend fun runQuestionSlot(
        nickname: String,
        slotMovies: List<SlotMovie>,
        orchestrateMovies: List<AiOrchestrateValidate.OrchestrateMovie>,
        unitByIndex: Map<Int, UnitOutcome>,
        unitById: Map<String, AiOrchestrateValidate.OrchestratedUnit>,
        slot: QuestionSlot,
        difficultySuffix: String,
        hint: String?,
        budget: QuizCallBudget,
    ): AiOrchestrateValidate.OrchestratedQuestion {
        val outcome = unitByIndex[slot.unitIndex]
            ?: throw AiOrchestrateError.invalidResponse("题位找不到归属单元")
        val unit = outcome.unit
        val payload = chatJson(
            AiFeatureSlot.QUIZ,
            questionSlotMessages(
                nickname, slotMovies, unit.toSlotUnit(), slot,
                QUIZ_REVIEW_SHAPE_SPEC + difficultySuffix, hint,
            ),
            TEMPERATURE_CREATIVE,
            QUIZ_MAX_TOKENS_QUESTION,
        )
        budget.countCall()
        var orchestrated = AiOrchestrateValidate.normalizeQuestion(
            unwrapSlotPayload(payload, "question"),
            slot.index - 1,
            orchestrateMovies,
        )
        orchestrated = AiOrchestrateValidate.groundQuestionToUnit(orchestrated, unit, orchestrateMovies)
        AiOrchestrateValidate.assertQuestionMatchesSlot(orchestrated, slot, unit)
        AiOrchestrateValidate.assertQuestionQuality(orchestrated, orchestrateMovies, unitById)
        return orchestrated
    }

    /** 槽位载荷解包（对拍 unwrapSlotPayload：{key:…} / {keys:[…]}[0] / 原样）。 */
    private fun unwrapSlotPayload(payload: JsonElement, key: String): JsonElement {
        val record = payload as? JsonObject ?: return payload
        record[key]?.takeIf { it !is JsonNull }?.let { return it }
        (record[key + "s"] as? JsonArray)?.firstOrNull()?.let { return it }
        return payload
    }

    // ------------------------------------------------------------------
    // ④ daily：每日知识（对拍 generateStage + dailyResponseFromUnit）
    // ------------------------------------------------------------------

    /**
     * 流式每日知识：candidate → review 两阶段，各带修复轮（累积拒因 → 编号提示）。
     * 阶段事件与 getDailyStream 消费方约定一致；成功以 Completed 交付域模型。
     */
    fun generateDailyStream(
        day: String,
        locale: String,
        watched: List<AiWatchedTitleDto>,
        recentUsage: List<DailyRecentUsage> = emptyList(),
        illustrationSaver: (suspend (unit: DailyKnowledgeUnit, bytes: ByteArray) -> String?)? = null,
    ): Flow<AiDailyStreamEvent> = channelFlow {
        if (locale !in DAILY_LOCALES) {
            throw AiOrchestrateError.invalidRequest("Unsupported daily locale: $locale")
        }
        val movies = watched.take(MAX_WATCHED_ITEMS).map { toDailyMovieInput(it) }
        // 候选阶段：失败拒因累积去重后编号下发修复提示。
        send(AiDailyStreamEvent.Stage(AiDailyStage.CANDIDATE, AiDailyStageStatus.START, DAILY_STAGE_EXPECTED_CHARS))
        val candidate = runDailyStage(
            day, locale, movies, recentUsage, DAILY_MAX_TOKENS_CANDIDATE,
        ) { hint -> dailyCandidateMessages(day, locale, movies, hint, recentUsage) }
        send(AiDailyStreamEvent.Stage(AiDailyStage.CANDIDATE, AiDailyStageStatus.DONE, DAILY_STAGE_EXPECTED_CHARS))
        // 二审阶段：携带候选单元复写，输出同结构单元。
        send(AiDailyStreamEvent.Stage(AiDailyStage.REVIEW, AiDailyStageStatus.START, DAILY_STAGE_EXPECTED_CHARS))
        val reviewed = runDailyStage(
            day, locale, movies, recentUsage, DAILY_MAX_TOKENS_REVIEW,
        ) { hint -> dailyReviewMessages(candidate, locale, movies, hint, recentUsage) }
        send(AiDailyStreamEvent.Stage(AiDailyStage.REVIEW, AiDailyStageStatus.DONE, DAILY_STAGE_EXPECTED_CHARS))
        // 概念配图（可选增强）：媒体 Provider 未配置或任何失败都不影响正文。
        val illustration = generateIllustration(reviewed, illustrationSaver)
        send(AiDailyStreamEvent.Completed(toDomainDailyKnowledge(day, reviewed, illustration)))
    }

    /** 用二审单元的 illustrationBrief 生成概念配图并交给调用方落盘，返回可展示 URL。 */
    private suspend fun generateIllustration(
        unit: DailyKnowledgeUnit,
        saver: (suspend (DailyKnowledgeUnit, ByteArray) -> String?)?,
    ): AiDailyIllustration? {
        if (saver == null) return null
        return try {
            val config = settingsRepository.resolve(AiFeatureSlot.DAILY_IMAGE)
            val brief = unit.illustrationBrief?.takeIf { it.isNotBlank() }
                ?: "minimal editorial still life evoking " + unit.concept + ", soft daylight, muted palette"
            val bytes = AiProviderClientFactory.create(config.protocol)
                .generateImage(AiImageRequest(prompt = brief), config)
            if (bytes.isEmpty()) return null
            val url = saver(unit, bytes) ?: return null
            AiDailyIllustration(status = "ready", url = url)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
    }


    /** daily 单阶段执行：修复轮 + 拒因累积（对拍 generateStage 的 rejectionReasons 循环）。 */
    private suspend fun runDailyStage(
        day: String,
        locale: String,
        movies: List<DailyKnowledgeMovieInput>,
        recentUsage: List<DailyRecentUsage>,
        maxOutputTokens: Int,
        buildMessages: (String?) -> List<AiMessage>,
    ): DailyKnowledgeUnit {
        val rejectionReasons = LinkedHashSet<String>()
        var lastError: AiProviderException? = null
        for (round in 0..DAILY_REPAIR_ROUNDS) {
            val hint = if (rejectionReasons.isEmpty()) {
                null
            } else {
                rejectionReasons.mapIndexed { index, reason -> "${index + 1}. $reason" }
                    .joinToString("；")
            }
            try {
                val payload = chatJson(
                    AiFeatureSlot.DAILY,
                    buildMessages(hint),
                    TEMPERATURE_CREATIVE,
                    maxOutputTokens,
                )
                return AiOrchestrateValidate.normalizeDailyKnowledgeUnit(payload, day, locale, movies)
            } catch (error: AiProviderException) {
                lastError = error
                rejectionReasons.add(dailyRepairHintFor(error.message.orEmpty().take(200)))
            }
        }
        throw AiOrchestrateError.invalidResponse(
            lastError?.message ?: "AI daily knowledge unit is invalid",
        )
    }

    /** 已看条目 → daily 电影输入（对拍 readMovies 的证据行构造在 buildEvidenceLines 内完成）。 */
    private fun toDailyMovieInput(item: AiWatchedTitleDto): DailyKnowledgeMovieInput =
        DailyKnowledgeMovieInput(
            title = item.title,
            mediaType = item.mediaType,
            traktId = item.mediaIds.traktId,
            tmdbId = item.mediaIds.tmdbId,
            imdbId = item.mediaIds.imdbId,
            doubanId = item.mediaIds.doubanId,
            evidence = AiOrchestrateJson.buildEvidenceLines(item),
            year = item.year,
            genres = item.genres,
        )

    /** 单元 → 域模型（对拍 dailyResponseFromUnit：id=day、fact=takeaway、剧透标记等）。 */
    private fun toDomainDailyKnowledge(
        day: String,
        unit: DailyKnowledgeUnit,
        illustration: AiDailyIllustration? = null,
    ): AiDailyKnowledge =
        AiDailyKnowledge(
            id = day,
            unitId = unit.unitId,
            title = unit.title,
            fact = unit.takeaway,
            explanation = unit.explanation,
            sourceName = unit.source.name,
            sourceUrl = unit.source.url,
            publishedAt = System.currentTimeMillis(),
            characterLine = unit.characterLine,
            relatedMediaTitle = unit.relatedMedia?.title,
            containsSpoiler = unit.spoilerLevel != "none",
            isFallback = false,
            version = unit.version,
            locale = unit.locale,
            relationType = unit.relationType,
            evidenceMode = unit.evidenceMode,
            subjectGroup = unit.subjectGroup,
            subject = unit.subject,
            concept = unit.concept,
            takeawayValue = unit.takeaway,
            relatedMedia = unit.relatedMedia?.let { media ->
                AiDailyRelatedMedia(
                    title = media.title,
                    mediaType = media.mediaType,
                    traktId = media.traktId,
                    tmdbId = media.tmdbId,
                    imdbId = media.imdbId,
                    doubanId = media.doubanId,
                )
            },
            filmEvidenceValue = unit.filmEvidence,
            realWorldExample = unit.realWorldExample,
            boundary = unit.boundary,
            difficulty = unit.difficulty,
            spoilerLevel = unit.spoilerLevel,
            source = AiDailySource(
                name = unit.source.name,
                url = unit.source.url,
                evidence = unit.source.evidence,
            ),
            checkQuestion = AiDailyCheckQuestion(
                prompt = unit.checkQuestion.prompt,
                options = unit.checkQuestion.options.map { option ->
                    AiQuizOption(id = option.id, text = option.text)
                },
                correctOptionIds = unit.checkQuestion.correctOptionIds,
                explanation = unit.checkQuestion.explanation,
            ),
            illustration = illustration,
        )

    // ------------------------------------------------------------------
    // ⑤ detail：无剧透分析（对拍 analyzeDetail + normalizeDetailOutput）
    // ------------------------------------------------------------------

    /** 详情分析：单次调用，normalize 失败抛 INVALID_RESPONSE（不做 fallbackDetail）。 */
    suspend fun analyzeDetail(
        media: AiDetailMediaDto,
        scene: String,
        environment: AiDetailEnvironmentDto?,
        profile: DetailProfile,
        profileVersion: Long,
        forceRefresh: Boolean = false,
    ): com.tracktosearch.data.ai.AiDetailAnalysis {
        val input = DetailRequestInput(
            media = toDetailMedia(media),
            scene = scene,
            environment = environment?.let { value ->
                DetailEnvironmentInput(
                    localDate = value.localDate,
                    weekday = value.weekday,
                    timeOfDay = value.timeOfDay,
                    season = value.season,
                    weatherTag = value.weatherTag,
                )
            },
            forceRefresh = forceRefresh,
        )
        val payload = chatJson(
            AiFeatureSlot.DETAIL,
            detailMessages(input, profile),
            TEMPERATURE_PRECISE,
            DETAIL_MAX_TOKENS,
        )
        return AiOrchestrateValidate.normalizeDetailOutput(payload, scene, input.environment, profileVersion)
    }

    /** 详情条目 → prompt 载体（mediaIds 收敛成可靠 id 映射，对拍 readMediaIds）。 */
    private fun toDetailMedia(media: AiDetailMediaDto): DetailMedia = DetailMedia(
        mediaKey = media.mediaKey,
        mediaType = media.mediaType,
        title = media.title,
        year = media.year,
        genres = media.genres,
        overview = media.overview?.takeIf { it.isNotBlank() },
        directors = media.directors,
        cast = media.cast,
        publicRating = media.publicRating,
        mediaIds = sanitizeMediaIds(media.mediaIds),
    )

    // ------------------------------------------------------------------
    // ⑥ rank：推荐排序（对拍 rankRecommendations）
    // ------------------------------------------------------------------

    /**
     * 详情页推荐排序：候选清洗（非法丢弃、去已看/画像重复、去重）→ LLM 排序 → 缺失垫底。
     * 候选为空时直接返回空表（对拍「无候选不调 LLM」）。
     */
    suspend fun rankDetailRecommendations(
        currentMedia: AiDetailMediaDto,
        candidates: List<AiDetailMediaDto>,
        profile: DetailProfile,
        profileVersion: Long,
    ): List<AiDetailRecommendation> {
        // 当前条目必须可靠（对拍 normalizeCurrentMedia）。
        if (!MEDIA_KEY_REGEX.matches(currentMedia.mediaKey)) {
            throw AiOrchestrateError.invalidRequest("mediaKey is invalid")
        }
        val currentIds = sanitizeMediaIds(currentMedia.mediaIds)
        if (reliableMediaKeyOf(currentIds) == null) {
            throw AiOrchestrateError.invalidRequest("currentMedia needs a reliable media id")
        }
        if (candidates.size > RANK_MAX_CANDIDATES) {
            throw AiOrchestrateError.invalidRequest("Candidates are invalid")
        }
        // 画像条目本地只有标题（ProfileMediaItem 无媒体 id），按归一化标题排除画像已有片。
        val profileTitles = profile.media
            .map { it.title.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE) }
            .toSet()
        val prepared = ArrayList<DetailCandidate>()
        val seenKeys = HashSet<String>()
        for (item in candidates) {
            val candidate = normalizeRankCandidate(item) ?: continue
            if (candidate.mediaKey == currentMedia.mediaKey) continue
            if (candidate.title.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE) in profileTitles) continue
            if (!seenKeys.add(candidate.mediaKey)) continue
            prepared.add(candidate)
        }
        if (prepared.isEmpty()) return emptyList()
        val payload = chatJson(
            AiFeatureSlot.RANK,
            recommendationMessages(prepared, profile),
            TEMPERATURE_PRECISE,
            RANK_MAX_TOKENS,
        )
        val ordered = AiOrchestrateValidate.normalizeRankOrder(payload, prepared)
        return ordered.map { candidate ->
            AiDetailRecommendation(
                mediaKey = candidate.mediaKey,
                mediaType = candidate.mediaType,
                title = candidate.title,
                year = candidate.year,
                genres = candidate.genres,
                mediaIds = toMediaIdsDto(candidate.mediaIds),
                posterUrl = null,
                reason = recommendationReason(candidate, profile),
            )
        }
    }

    /** 排序候选清洗：非法返回 null 由调用方跳过（对拍 normalizeCandidate 失败跳过该项）。 */
    private fun normalizeRankCandidate(item: AiDetailMediaDto): DetailCandidate? {
        if (!MEDIA_KEY_REGEX.matches(item.mediaKey)) return null
        if (item.mediaType != "movie" && item.mediaType != "show") return null
        val title = item.title.trim()
        if (title.isEmpty() || title.length > 200) return null
        item.year?.let { year -> if (year < 1800 || year > 3000) return null }
        if (item.genres.size > 8 || item.genres.any { it.isBlank() || it.length > 48 }) return null
        val mediaIds = sanitizeMediaIds(item.mediaIds)
        if (reliableMediaKeyOf(mediaIds) == null) return null
        return DetailCandidate(
            mediaKey = item.mediaKey,
            mediaType = item.mediaType,
            title = title,
            year = item.year,
            genres = item.genres.take(8),
            mediaIds = mediaIds,
        )
    }

    /** 媒体 id 清洗（对拍 readMediaIds：tmdb>0 / trakt / imdb 格式 / douban，非法丢弃）。 */
    private fun sanitizeMediaIds(ids: AiMediaIdsDto): Map<String, String> = buildMap {
        ids.tmdbId?.takeIf { it > 0 }?.let { put("tmdb", it.toString()) }
        ids.traktId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("trakt", it) }
        ids.imdbId?.trim()?.lowercase()?.takeIf { IMDB_ID_REGEX.matches(it) }?.let { put("imdb", it) }
        ids.doubanId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("douban", it) }
    }

    /** 可靠媒体键（对拍 reliableMediaKey：tmdb > trakt > imdb > douban 优先）。 */
    private fun reliableMediaKeyOf(mediaIds: Map<String, String>): String? = when {
        mediaIds.containsKey("tmdb") -> "tmdb:" + mediaIds["tmdb"]
        mediaIds.containsKey("trakt") -> "trakt:" + mediaIds["trakt"]
        mediaIds.containsKey("imdb") -> "imdb:" + mediaIds["imdb"]
        mediaIds.containsKey("douban") -> "douban:" + mediaIds["douban"]
        else -> null
    }

    /** 排序结果回填 AiMediaIdsDto（与 sanitizeMediaIds 的键互逆）。 */
    private fun toMediaIdsDto(mediaIds: Map<String, String>): AiMediaIdsDto = AiMediaIdsDto(
        traktId = mediaIds["trakt"],
        tmdbId = mediaIds["tmdb"]?.toIntOrNull(),
        imdbId = mediaIds["imdb"],
        doubanId = mediaIds["douban"],
    )

    // ------------------------------------------------------------------
    // ⑦ TTS / 配图
    // ------------------------------------------------------------------

    /**
     * 角色语音合成：长度闸 500 → 口头禅按场景注入 → 走媒体 Provider（对拍 handleTts）。
     * 失败直接上抛（服务端 synthesizeOptionalAudio 的静默吞错由调用方决定）。
     */
    suspend fun synthesizeTts(
        characterId: String,
        text: String,
        scene: TtsScene,
        voiceFollowCharacter: Boolean? = null,
        customVoice: String? = null,
    ): ByteArray {
        if (text.length > TTS_MAX_TEXT_LENGTH) {
            throw AiOrchestrateError.invalidRequest("TTS text is too long")
        }
        val character = AiCharacters.findCharacter(characterId)
            ?: throw AiOrchestrateError.invalidRequest("Unknown character: $characterId")
        val spokenText = injectCatchphraseForScene(character, text, scene)
        val config = settingsRepository.resolve(AiFeatureSlot.TTS)
        val follow = voiceFollowCharacter ?: settingsRepository.currentSettings().voiceFollowCharacter
        val voice = customVoice?.takeIf { it.isNotBlank() }
            ?: (if (follow) TTS_VOICE_BY_CHARACTER[characterId] else null)
            ?: TTS_DEFAULT_VOICE
        return AiProviderClientFactory.create(config.protocol)
            .synthesizeSpeech(AiTtsRequest(text = spokenText, voice = voice), config)
    }

    /** 口头禅注入（对拍 injectCatchphrase：start 前置、否则后置，空白折叠）。 */
    private fun injectCatchphrase(character: AiCharacterPersona, text: String): String {
        val catchphrase = character.greetingCatchphrase.trim().replace(Regex("\\s+"), " ")
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (catchphrase.isEmpty() || normalized.isEmpty()) return normalized
        return if (character.catchphrasePosition == "start") {
            "$catchphrase $normalized"
        } else {
            "$normalized $catchphrase"
        }
    }

    /**
     * 从转写文本剥掉口头禅（对拍 stripCatchphraseFromTranscript）：音频里有口头禅，
     * 但界面字幕/转写不展示；仓库层组装 AiAudio.transcript 时调用。
     */
    fun stripCatchphraseFromTranscript(characterId: String, text: String): String {
        val catchphrase = AiCharacters.findCharacter(characterId)?.greetingCatchphrase?.trim().orEmpty()
        if (catchphrase.isEmpty()) return text
        val pattern = Regex(" ?" + Regex.escape(catchphrase) + "[。，！？、,.!?；;：:]* ?")
        return pattern.replace(text, "").trim()
    }

    /** 场景化口头禅注入（对拍 injectCatchphraseForScene：非 GREETING 且未全场景开启时原文）。 */
    private fun injectCatchphraseForScene(
        character: AiCharacterPersona,
        text: String,
        scene: TtsScene,
    ): String {
        if (scene != TtsScene.GREETING && !character.spokenInAllScenes) return text
        return injectCatchphrase(character, text)
    }

    /** 每日知识配图（对拍配图链路的图像调用段；brief 由调用方给英文提示词）。 */
    suspend fun generateDailyImage(prompt: String): ByteArray {
        if (prompt.isBlank()) {
            throw AiOrchestrateError.invalidRequest("Image prompt is required")
        }
        val config = settingsRepository.resolve(AiFeatureSlot.DAILY_IMAGE)
        return AiProviderClientFactory.create(config.protocol).generateImage(AiImageRequest(prompt = prompt), config)
    }

    // ------------------------------------------------------------------
    // LLM 调用底座
    // ------------------------------------------------------------------

    /**
     * JSON 模式补全：messages 首条 system 拆为 system 字段，其余合并为 user；
     * jsonMode 全开（三协议适配器内部映射各自的 JSON 约束）。
     */
    private suspend fun chatJson(
        slot: AiFeatureSlot,
        messages: List<AiMessage>,
        temperature: Double,
        maxOutputTokens: Int?,
    ): JsonElement = AiOrchestrateJson.parseModelJson(
        chatText(slot, messages, temperature, maxOutputTokens),
    )

    /** 文本补全：resolve 配置（未配置抛 PROVIDER_INVALID）→ complete → 取回文本。 */
    private suspend fun chatText(
        slot: AiFeatureSlot,
        messages: List<AiMessage>,
        temperature: Double,
        maxOutputTokens: Int?,
    ): String {
        val config = settingsRepository.resolve(slot)
        val (system, user) = splitMessages(messages)
        val response = AiProviderClientFactory.create(config.protocol).complete(
            AiChatRequest(
                system = system,
                user = user,
                temperature = temperature,
                maxOutputTokens = maxOutputTokens,
                jsonMode = true,
            ),
            config,
        )
        return response.text
    }

    /** 消息拆分：首条 system 进 system 字段，其余按序拼接为 user。 */
    private fun splitMessages(messages: List<AiMessage>): Pair<String, String> {
        val system = messages
            .firstOrNull { it.role.equals("system", ignoreCase = true) }
            ?.content.orEmpty()
        val user = messages
            .filterNot { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.content }
        return system to user
    }

    private companion object {
        /** 合法置信度（对拍 confidence ∈ high/medium/low，否则回 low）。 */
        val CONFIDENCE_LEVELS = setOf("high", "medium", "low")
    }
}
