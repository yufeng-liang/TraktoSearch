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
import com.tracktosearch.data.ai.AiDetailAnalysis
import com.tracktosearch.data.ai.AiDetailEnvironmentDto
import com.tracktosearch.data.ai.AiDetailInterestLevel
import com.tracktosearch.data.ai.AiDetailMediaDto
import com.tracktosearch.data.ai.AiDetailRecommendation
import com.tracktosearch.data.ai.AiDetailWatchTiming
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.AiNameSignal
import com.tracktosearch.data.ai.provider.AiProviderErrorCode
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizOption
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
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
import com.tracktosearch.data.ai.prompt.bankSeed
import com.tracktosearch.data.ai.prompt.selectDailyMovies
import com.tracktosearch.data.ai.prompt.DAILY_LOCALES
import com.tracktosearch.data.ai.prompt.DailyCheckOption
import com.tracktosearch.data.ai.prompt.DailyCheckQuestion
import com.tracktosearch.data.ai.prompt.DailyKnowledgeMovieInput
import com.tracktosearch.data.ai.prompt.DailyKnowledgeSource
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
import com.tracktosearch.data.ai.prompt.timingBasis
import com.tracktosearch.data.ai.prompt.timingLevel
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
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------------------
// 编排档位常量（对拍服务端：各供应商默认温度为 0.7，detail/rank 显式传 0.2）。
// ---------------------------------------------------------------------------

/** 创作类功能（greeting/taste/quiz/daily）的采样温度（对拍 mimo.ts:59 等供应商默认 0.7）。 */
private const val TEMPERATURE_CREATIVE = 0.7

/** 精确类功能（detail/rank）的采样温度（对拍 detail-handler.ts:123/:173 显式 0.2）。 */
private const val TEMPERATURE_PRECISE = 0.2

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

/**
 * 每天抽片数（对拍 handler.ts:1096 QUIZ_MOVIE_COUNT=7）。
 * 与服务端一致：出题只吃「当天可复算抽出的 7 部」，而非全部已看记录。
 */
private const val DAILY_QUIZ_MOVIE_COUNT = 7

/** 兜底题库的单选/多选条数（对拍 fallbackQuizQuestions：10 单选 + 2 多选 + 1 简答）。 */
private const val FALLBACK_SINGLE_COUNT = 10
private const val FALLBACK_MULTIPLE_COUNT = 2

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

/**
 * detail 兜底文案的文本上限（对拍 detail-handler.ts:18 MAX_TEXT=1200）。
 * 与服务端 normalizeDetailOutput 的 readText 上限同口径。
 */
private const val DETAIL_MAX_TEXT = 1200

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
 * quizId 形状（对拍 deriveBankQuizId：bank-YYYY-MM-DD-hash8），用于反解出题日期。
 * 注意 Kotlin 源码里是双反斜杠：正则收到的才是 \d。
 */
private val QUIZ_ID_DATE_REGEX = Regex("^bank-(\\d{4}-\\d{2}-\\d{2})-")

/** detail 兜底判断「高信号类型」用的类型词（对拍 /drama|comedy|romance|animation|fantasy/i）。 */
private val DETAIL_HIGH_SIGNAL_GENRE_REGEX =
    Regex("drama|comedy|romance|animation|fantasy", RegexOption.IGNORE_CASE)

/**
 * 允许本地兜底的错误码（与 AiRepository.FALLBACK_ELIGIBLE_CODES 同口径）。
 *
 * 语义：模型答坏了（解析失败/上游 5xx/限流/未知）可以用本地兜底顶上；
 * 配置缺失、鉴权失败、网络不可用则照常上抛——把故障伪装成成功会让用户找不到真正的原因。
 */
private val FALLBACK_ELIGIBLE_CODES = setOf(
    AiProviderErrorCode.INVALID_RESPONSE,
    AiProviderErrorCode.SERVER,
    AiProviderErrorCode.RATE_LIMITED,
    AiProviderErrorCode.UNKNOWN,
)

/**
 * BYOK 本地编排引擎：把 auth-worker 的 LLM 编排（greeting/taste/quiz/daily/detail/rank/tts）
 * 原样移植到本地，LLM 调用经 [AiProviderClient] 直连用户自配 Provider。
 *
 * 错误契约：所有失败一律抛 [AiProviderException]
 * —— 校验耗尽/JSON 解析失败 → INVALID_RESPONSE；入参不合法 → INVALID_REQUEST；
 * Provider 未配置 → PROVIDER_INVALID（由 [AiProviderSettingsRepository.resolve] 抛出）。
 *
 * 与服务端兜底策略的差异：
 * greeting/taste 的最终兜底文案（fallbackGreeting/fallbackTaste）不移植，失败即抛
 * INVALID_RESPONSE，由仓库层统一决定是否降级；
 * quiz 与 daily 的兜底已移植——单元槽保留不足 3 个、整包质量复核失败时交本地 13 题
 * （[fallbackQuiz]），daily 两阶段失败时交本地种子单元（[fallbackDailyKnowledgeUnit]），
 * 触发条件统一由 [isFallbackEligible] 收口（配置/鉴权/网络错误仍上抛）；
 * detail 失败时交保守的本地判断（[fallbackDetail]）。
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

        /**
         * 打上游之前先记账（对拍 handler.ts:1350-1355：先判预算、再 `calls += 1`，随后才请求）。
         *
         * 两道闸门：
         * - 首轮（isRetry=false）也执行 80 次硬闸——服务端同样无条件检查 maxCallsPerSet；
         * - 修复轮（isRetry=true）额外执行「次数或耗时超限」闸，超限抛错由本格 catch 降级。
         *
         * 先记账再请求，超时/解析失败/429 同样计入预算，避免失败重试把上限吃穿。
         */
        fun beginCall(isRetry: Boolean = false) {
            if (isRetry && exceeded()) {
                throw AiOrchestrateError.invalidResponse("出题槽位调用超出预算")
            }
            if (calls.get() >= QUIZ_MAX_CALLS_PER_SET) {
                throw AiOrchestrateError.invalidResponse("出题槽位调用超出预算")
            }
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
                // 模型靠 rating/userRating 区分「大众评价」与「本人打分」，
                // 报告说的是「你给 8 分」还是「豆瓣 8 分」完全不同。
                rating = item.publicRating,
                userRating = item.userRating,
                watchedAt = item.watchedAt,
                mediaIds = sanitizeMediaIds(item.mediaIds),
                // 本地没有服务端那种「已核验」环节，留空即回落到 mediaIds 语义
                verifiedMediaIds = emptyMap(),
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
     * 抽 7 部片 → 4 单元槽（并发 2、修复轮 2，单格失败只剔除本格）→ 概念去重
     * （<3 走整包兜底题）→ 13 题位槽（并发 2、修复轮 2、失败降级兜底题）→
     * 跨槽位考点撞车修复 → 整包质量复核（失败同样走整包兜底题）。
     *
     * 兜底的意义：13 题结构是判分与 UI 的硬前提，服务端宁可交本地题也不返回错误
     * （handler.ts:922-928）；这里保持同一取舍，但配置/鉴权/网络错误仍上抛。
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
        // 每天抽片（对拍 handler.ts:898）：用稳定种子从已看记录里抽出 7 部，
        // 同一 quizId 必然复算同一组，避免「每次重试换一批片」导致题面不可复现。
        val selectedMovies = selectDailyMovies(
            movies,
            dailyMovieSeed(quizId),
            DAILY_QUIZ_MOVIE_COUNT,
        ) { it.title }
        val orchestrateMovies = selectedMovies.map { AiOrchestrateValidate.watchedToOrchestrateMovie(it) }
        val dailyMovies = selectedMovies.map { toDailyMovieInput(it) }
        val slotMovies = selectedMovies.map { item ->
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
        // 单格失败只剔除本格，绝不让整批作废（对拍 async-pool.ts:40-70 mapWithGate）：
        // 所以用 supervisorScope 而不是 coroutineScope，并逐格 runCatching。
        val fatalUnitError = AtomicReference<AiProviderException?>(null)
        val unitOutcomes = supervisorScope {
            val gate = Semaphore(QUIZ_SLOT_CONCURRENCY)
            unitSlots.map { slot ->
                async {
                    gate.withPermit {
                        val outcome = runCatching {
                            runUnitSlot(nickname, slotMovies, dailyMovies, slot, difficultySuffix, budget)
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            // 配置/鉴权/网络类错误与「模型答坏了」不同：整包兜底会把故障
                            // 掩盖成成功，所以记下来，等确定要走兜底时再上抛。
                            if (error is AiProviderException && !isFallbackEligible(error)) {
                                fatalUnitError.compareAndSet(null, error)
                            }
                            null
                        }
                        onEvent(AiQuizStreamEvent.Progress(AiQuizStage.UNITS, unitsFinished.incrementAndGet()))
                        outcome
                    }
                }
            }.awaitAll().filterNotNull()
        }
        onEvent(AiQuizStreamEvent.Stage(AiQuizStage.UNITS, AiQuizStageStatus.DONE, 0))

        // 概念去重：语义键重复只保留第一格；保留不足 3 个则改走整包兜底题。
        val keptUnits = LinkedHashMap<Int, UnitOutcome>()
        val seenConcepts = HashSet<String>()
        for (outcome in unitOutcomes) {
            val key = AiOrchestrateJson.normalizeSemanticKey(outcome.unit.concept)
            if (key.isEmpty() || !seenConcepts.add(key)) continue
            keptUnits[outcome.slot.index] = outcome
        }
        if (keptUnits.size < QUIZ_MIN_KEPT_UNITS) {
            // 服务端此处 return null，由 handler 换成整包兜底题（handler.ts:922-928）。
            // 致命错误（配置/鉴权/网络）仍上抛；其余一律交本地 13 题，保证客户端拿得到题包。
            fatalUnitError.get()?.let { throw it }
            return fallbackQuiz(quizId, orchestrateMovies)
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

        // 首轮出题：内部含 2 轮修复，3 轮耗尽才降级兜底题（对拍「全部轮耗尽 → degradedQuestion(slot,0)」）。
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
                                // 预算超限由 runQuestionSlot 内部的 beginCall 抛出，本格 catch 后
                                // 降级为兜底题——13 题不变量优先于「必须走模型」。
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
                }.awaitAll()
            }
        }

        val finalQuestions = questionSlots.mapNotNull { assembled[it.index] }
        if (finalQuestions.size != QUIZ_QUESTION_SLOT_COUNT) {
            throw AiOrchestrateError.invalidResponse("题位未能凑齐 13 题")
        }
        // 整包质量复核（对拍 validateReviewedQuiz：逐题门槛 + 学科覆盖 3-8）。
        // 复核失败时服务端 return null → 走整包兜底；这里同样交本地 13 题，而不是把错误抛给用户。
        try {
            AiOrchestrateValidate.validateReviewedQuiz(finalQuestions, orchestrateMovies, unitById)
        } catch (error: AiProviderException) {
            if (!isFallbackEligible(error)) throw error
            onEvent(AiQuizStreamEvent.Stage(AiQuizStage.REVIEW, AiQuizStageStatus.DONE, 0))
            return fallbackQuiz(quizId, orchestrateMovies)
        }
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
            try {
                val hint = if (round > 0) {
                    dailyRepairHintFor(lastError?.message.orEmpty().take(200))
                } else {
                    null
                }
                // 先记账再请求：预算含失败调用，首轮同样受 80 次硬闸约束（对拍 callSlot）。
                budget.beginCall(isRetry = round > 0)
                val payload = chatJson(
                    AiFeatureSlot.QUIZ,
                    unitSlotMessages(nickname, slotMovies, slot, QUIZ_SLOT_UNIT_SPEC + difficultySuffix, hint),
                    TEMPERATURE_CREATIVE,
                    QUIZ_MAX_TOKENS_UNIT,
                )
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

    /**
     * 题位槽位执行：出题 → 回接单元 → 槽位断言 → 质量门槛（对拍 runQuestionSlot）。
     *
     * 修复轮（对拍 handler.ts:1520-1553）：round 0..repairRounds 共 3 次调用，每次失败都把
     * 上一轮 error.message 翻成中文修复提示再试，3 轮耗尽才由调用方降级为兜底题。
     */
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
        var lastError: AiProviderException? = null
        for (round in 0..QUIZ_REPAIR_ROUNDS) {
            try {
                // round 0 用外层提示（考点撞车等），修复轮再叠加上一轮的失败原因。
                val roundHint = if (round == 0) hint else appendRepairHint(hint, lastError)
                // 先记账再请求（对拍 callSlot）：带提示的调用即视为重试，受预算闸约束。
                budget.beginCall(isRetry = roundHint != null)
                val payload = chatJson(
                    AiFeatureSlot.QUIZ,
                    questionSlotMessages(
                        nickname, slotMovies, unit.toSlotUnit(), slot,
                        QUIZ_REVIEW_SHAPE_SPEC + difficultySuffix, roundHint,
                    ),
                    TEMPERATURE_CREATIVE,
                    QUIZ_MAX_TOKENS_QUESTION,
                )
                var orchestrated = AiOrchestrateValidate.normalizeQuestion(
                    unwrapSlotPayload(payload, "question"),
                    slot.index - 1,
                    orchestrateMovies,
                )
                orchestrated = AiOrchestrateValidate.groundQuestionToUnit(orchestrated, unit, orchestrateMovies)
                AiOrchestrateValidate.assertQuestionMatchesSlot(orchestrated, slot, unit)
                AiOrchestrateValidate.assertQuestionQuality(orchestrated, orchestrateMovies, unitById)
                return orchestrated
            } catch (error: AiProviderException) {
                lastError = error
            }
        }
        throw AiOrchestrateError.invalidResponse(lastError?.message ?: "题位槽位校验耗尽")
    }

    /**
     * 拼接本轮修复提示：外层提示（如考点撞车）+ 上一轮失败原因的中文修复提示。
     *
     * 对拍 handler.ts:1524 `hint = dailyRepairHintFor(error.message.slice(0,200))`；
     * 外层本来没有提示时只用修复提示，避免给模型一段空白的「提示：」。
     */
    private fun appendRepairHint(baseHint: String?, error: AiProviderException?): String {
        val repair = dailyRepairHintFor(error?.message.orEmpty().take(200))
        return baseHint?.let { "$it\n$repair" } ?: repair
    }

    /** 槽位载荷解包（对拍 unwrapSlotPayload：{key:…} / {keys:[…]}[0] / 原样）。 */
    private fun unwrapSlotPayload(payload: JsonElement, key: String): JsonElement {
        val record = payload as? JsonObject ?: return payload
        record[key]?.takeIf { it !is JsonNull }?.let { return it }
        (record[key + "s"] as? JsonArray)?.firstOrNull()?.let { return it }
        return payload
    }

    /**
     * 出题抽片种子（对拍 handler.ts:898 `bankSeed(payload.sub, date, setIndex)`）。
     *
     * 本地拿不到 friendId/date 两个入参，只有 quizId 这一稳定身份：它的形状是
     * bank-YYYY-MM-DD-hash8（见 AiQuizBank.deriveBankQuizId），日期段可直接反解；
     * 套序号取 quizId 的稳定哈希低 8 位（String.hashCode 由规范定义，跨进程/跨版本一致）。
     * 用 quizId 顶替 friendId 分量，保证「同一个 quizId → 同一组片 → 同一套题」，
     * 重试、断点续算、客户端重新拉取都不会换片。
     */
    private fun dailyMovieSeed(quizId: String): String {
        val date = QUIZ_ID_DATE_REGEX.find(quizId)?.groupValues?.getOrNull(1).orEmpty()
        val setIndex = quizId.hashCode() and 0xFF
        return bankSeed(quizId, date, setIndex)
    }

    /** 错误是否允许走本地兜底（口径与 AiRepository.FALLBACK_ELIGIBLE_CODES 一致）。 */
    private fun isFallbackEligible(error: AiProviderException): Boolean =
        error.code in FALLBACK_ELIGIBLE_CODES

    // ------------------------------------------------------------------
    // 兜底题库：整包 13 题（对拍 handler.ts:2902-3110 fallbackQuizQuestions）
    // ------------------------------------------------------------------

    /**
     * 整包兜底题库：完全不调模型，用本地模板凑齐「10 单选 + 2 多选 + 1 简答」。
     *
     * 触发点（对拍 handler.ts:931-937 的 `generated ?? fallback`）：单元槽保留不足 3 个、
     * 或整包质量复核失败时，宁可交本地题也不让客户端拿到错误——13 题结构是评分与 UI 的硬前提。
     * 题面里的年份/类型/出品地区等事实直接印在题干，考的是用这些事实做判断。
     */
    private fun fallbackQuiz(
        quizId: String,
        movies: List<AiOrchestrateValidate.OrchestrateMovie>,
    ): OrchestratedQuiz {
        val pool = movies.ifEmpty {
            throw AiOrchestrateError.invalidRequest("At least 7 watched movies are required")
        }
        val questions = ArrayList<AiOrchestrateValidate.OrchestratedQuestion>(QUIZ_QUESTION_SLOT_COUNT)
        val singleBuilders = listOf(
            ::fallbackYearChoice,
            ::fallbackGenreChoice,
            ::fallbackSynopsisChoice,
            ::fallbackRatingChoice,
            ::fallbackTitleChoice,
            ::fallbackCountryChoice,
            ::fallbackSynopsisCheckChoice,
            ::fallbackRatingJudgementChoice,
            ::fallbackReviewChoice,
            ::fallbackDisagreementChoice,
        )
        for (index in 0 until FALLBACK_SINGLE_COUNT) {
            val movie = pool[index % pool.size]
            val title = AiOrchestrateValidate.fallbackDisplayTitle(movie)
            val choice = singleBuilders[index].invoke(movie, title)
            questions += fallbackChoiceQuestion(index, movie, choice, AiQuizQuestionType.SINGLE)
        }
        val multipleBuilders = listOf(::fallbackCredibilityChoice, ::fallbackComparisonChoice)
        for (offset in multipleBuilders.indices) {
            val index = FALLBACK_SINGLE_COUNT + offset
            val movie = pool[index % pool.size]
            val title = AiOrchestrateValidate.fallbackDisplayTitle(movie)
            val choice = multipleBuilders[offset].invoke(movie, title)
            questions += fallbackChoiceQuestion(index, movie, choice, AiQuizQuestionType.MULTIPLE)
        }
        val shortIndex = FALLBACK_SINGLE_COUNT + FALLBACK_MULTIPLE_COUNT
        val shortMovie = pool[shortIndex % pool.size]
        questions += fallbackShortQuestion(
            shortIndex,
            shortMovie,
            AiOrchestrateValidate.fallbackDisplayTitle(shortMovie),
        )
        // 题包影片按实际被引用的片名收窄（与服务端 fallback 的 movies 字段一致）。
        val usedTitles = questions
            .mapNotNull { it.question.sourceTitle ?: it.question.mediaTitle }
            .toHashSet()
        val quizMovies = pool.filter { it.title in usedTitles }.ifEmpty { pool }
        val quiz = AiQuiz(
            quizId = quizId,
            title = QUIZ_TITLE,
            subtitle = "本轮涉及：" + quizMovies.joinToString("、") { it.title },
            mediaTitles = quizMovies.map { it.title },
            questions = questions.map { it.question },
            totalScore = 100,
        )
        return OrchestratedQuiz(quiz = quiz, questions = questions)
    }

    /** 兜底选择题组装（对拍 fallbackSingleQuestion / fallbackMultipleQuestion 的字段映射）。 */
    private fun fallbackChoiceQuestion(
        index: Int,
        movie: AiOrchestrateValidate.OrchestrateMovie,
        choice: FallbackChoice,
        type: AiQuizQuestionType,
    ): AiOrchestrateValidate.OrchestratedQuestion {
        val education = fallbackEducation(movie, choice.knowledgePoint)
        val isMultiple = type == AiQuizQuestionType.MULTIPLE
        val answerRationale = if (isMultiple) {
            "本题考察“${choice.knowledgePoint}”：这些选项分别提供可回到影片内容验证、可比较的分析入口。"
        } else {
            "本题考察“${choice.knowledgePoint}”：最佳答案回到《${movie.title}》里可核验的呈现内容，" +
                "而不是把主观想象当成影片事实。"
        }
        val distractorRationale = if (isMultiple) {
            "未被选中的选项不能帮助完成“${choice.knowledgePoint}”要求的影片内容验证，" +
                "或把外围信息误当成了作品分析。"
        } else {
            "其他选项要么与“${choice.knowledgePoint}”的证据核对无关，要么把作品简化成单一因素或外围信息。"
        }
        val explanation = if (isMultiple) {
            education.evidenceUsed + "这可以用“${education.concept}”来理解；学习结论是：" + education.learningTakeaway
        } else {
            education.evidenceUsed + "这对应“${education.concept}”，因此可以得出：" + education.learningTakeaway
        }
        val difficulty = when {
            isMultiple -> "hard"
            index < 4 -> "easy"
            index < 9 -> "medium"
            else -> "hard"
        }
        val correctAnswer = if (isMultiple) null else choice.correctIds.first()
        val question = AiQuizQuestion(
            id = "q" + (index + 1),
            type = type,
            prompt = choice.prompt,
            options = choice.options,
            mediaTitle = movie.title,
            quote = null,
            maxScore = when (type) {
                AiQuizQuestionType.SHORT -> 0
                AiQuizQuestionType.MULTIPLE -> 10
                AiQuizQuestionType.SINGLE -> 8
            },
            difficulty = difficulty,
            knowledgePoint = choice.knowledgePoint,
            sourceTitle = movie.title,
            answerRationale = answerRationale,
            distractorRationale = distractorRationale,
            explanation = explanation,
            correctOptionIds = choice.correctIds,
            subject = education.subject,
            concept = education.concept,
            learningTakeaway = education.learningTakeaway,
            evidenceUsed = education.evidenceUsed,
            answerKeywords = emptyList(),
            correctAnswer = correctAnswer,
            unitId = null,
        )
        return AiOrchestrateValidate.OrchestratedQuestion(
            question = question,
            correctAnswer = correctAnswer,
            unitId = null,
        )
    }

    /** 兜底简答题组装（对拍 fallbackQuizQuestions 末尾的 q13：不设唯一答案、不计分）。 */
    private fun fallbackShortQuestion(
        index: Int,
        movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): AiOrchestrateValidate.OrchestratedQuestion {
        val education = fallbackEducation(movie, "开放思考")
        val correctAnswer = "人物如何在处境中作出选择，并承担选择的后果。"
        val question = AiQuizQuestion(
            id = "q" + (index + 1),
            type = AiQuizQuestionType.SHORT,
            prompt = "用一句话回答：看完《$title》后，你认为它最值得带回现实生活的一个问题是什么？",
            options = emptyList(),
            mediaTitle = movie.title,
            quote = null,
            maxScore = 0,
            difficulty = "hard",
            knowledgePoint = "开放思考",
            sourceTitle = movie.title,
            answerRationale = "这是一道开放题，重点是把影片中的具体人物、选择或关系连接到自己的理解。",
            distractorRationale = "",
            explanation = education.evidenceUsed + "开放题鼓励把“${education.concept}”转化为自己的问题；" +
                "学习结论是：" + education.learningTakeaway + "本题不设唯一标准答案，不计入客观得分。",
            correctOptionIds = emptyList(),
            subject = education.subject,
            concept = education.concept,
            learningTakeaway = education.learningTakeaway,
            evidenceUsed = education.evidenceUsed,
            answerKeywords = listOf("选择", "处境", "后果", "现实", "关系"),
            correctAnswer = correctAnswer,
            unitId = null,
        )
        return AiOrchestrateValidate.OrchestratedQuestion(
            question = question,
            correctAnswer = correctAnswer,
            unitId = null,
        )
    }

    /** 知识点 → 学科/概念/学习结论（对拍 fallbackEducation：未知考点回落到开放思考）。 */
    private fun fallbackEducation(
        movie: AiOrchestrateValidate.OrchestrateMovie,
        knowledgePoint: String,
    ): FallbackEducation {
        val education = FALLBACK_KNOWLEDGE_EDUCATION[knowledgePoint]
            ?: FALLBACK_KNOWLEDGE_EDUCATION.getValue("开放思考")
        val evidence = movie.evidence.firstOrNull() ?: "已看记录中的片名《${movie.title}》"
        return FallbackEducation(
            subject = education.subject,
            concept = education.concept,
            learningTakeaway = education.takeaway,
            evidenceUsed = "《${movie.title}》的已看记录提供了“$evidence”这一具体线索。",
        )
    }

    /** 兜底选项：id + 文本，文本统一去掉句末标点（对拍 stripTrailingSentencePunctuation）。 */
    /**
     * 兜底选择题的中间形状（单选/多选共用）。
     *
     * 只承载「题干 + 选项 + 正确项 + 考点」四件事：难度、解析、学习结论等
     * 由 [fallbackChoiceQuestion] 按题位统一生成，避免每个 builder 重复写一套文案。
     */
    private data class FallbackChoice(
        val prompt: String,
        val options: List<AiQuizOption>,
        val correctIds: List<String>,
        val knowledgePoint: String,
    )

    private fun fallbackOptions(vararg options: Pair<String, String>): List<AiQuizOption> =
        options.map { (id, text) ->
            AiQuizOption(id = id, text = AiOrchestrateJson.stripTrailingSentencePunctuation(text))
        }

    /** 单选 1：上映年份与时代背景（对拍 singleBuilders[0]）。 */
    private fun fallbackYearChoice(
        movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice {
        val year = movie.year ?: 2000
        return FallbackChoice(
            prompt = "《$title》是 $year 年的作品。下列关于\"隔了多年再看它的眼光\"的说法，哪项最站得住？",
            options = fallbackOptions(
                "a" to "它当年的讨论背景已经失效，现在看必然一无是处。",
                "b" to "作品在上映那年就定型了，今天看和当年看感受必须完全一样。",
                "c" to "把它放回 $year 年的语境理解创作选择，再对照今天的自己，两层读法都成立。",
                "d" to "只要足够多的人夸它，不需要自己再看也能下结论。",
            ),
            correctIds = listOf("c"),
            knowledgePoint = "上映年份与时代背景",
        )
    }

    /** 单选 2：类型定位（对拍 singleBuilders[1]；非中文类型标签回退「剧情」）。 */
    private fun fallbackGenreChoice(
        movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice {
        val rawGenre = movie.genres.firstOrNull() ?: "剧情"
        val genre = if (hasCjk(rawGenre)) rawGenre else "剧情"
        return FallbackChoice(
            prompt = "《$title》在你已看记录里的类型标签是「$genre」。对这个标签哪项理解最准确？",
            options = fallbackOptions(
                "a" to "它一定全程都在唱歌跳舞。",
                "b" to "它必须以真实事件为题材。",
                "c" to "类型标签只是营销用语，没有任何信息量。",
                "d" to "它大概率以「$genre」的常规方式组织叙事，但也可能引入别的类型元素。",
            ),
            correctIds = listOf("d"),
            knowledgePoint = "类型定位",
        )
    }

    /** 单选 3：简介与叙事重心（对拍 singleBuilders[2]）。 */
    private fun fallbackSynopsisChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "朋友说《$title》\"简介看起来像另一部片\"，你可以怎么核对？",
        options = fallbackOptions(
            "a" to "看谁的评价人数多就听谁的。",
            "b" to "片名像就说明是同一部，不用核对。",
            "c" to "凭第一印象直接下判断。",
            "d" to "用已看记录里的简介片段逐段对读，看他描述的情节是否真的出现。",
        ),
        correctIds = listOf("d"),
        knowledgePoint = "简介与叙事重心",
    )

    /** 单选 4：评分与个人判断（对拍 singleBuilders[3]）。 */
    private fun fallbackRatingChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "有人断言\"《$title》的评分这么高，你肯定也喜欢\"。这个推断的问题在哪里？",
        options = fallbackOptions(
            "a" to "高分说明它质量稳定，推断没有问题。",
            "b" to "公映评分是群体平均值，和你的具体喜好没有必然联系，你的判断要回到自己的观影感受。",
            "c" to "评分高低完全由水军操纵，毫无参考价值。",
            "d" to "只要评分高，个人感受就必须与之一致，否则说明你不会看电影。",
        ),
        correctIds = listOf("b"),
        knowledgePoint = "评分与个人判断",
    )

    /** 单选 5：片名与原名（对拍 singleBuilders[4]）。 */
    private fun fallbackTitleChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "关于《$title》的片名，下列哪种态度最合理？",
        options = fallbackOptions(
            "a" to "片名只是一个代号，任何解读都无意义。",
            "b" to "片名一定隐藏了作者的核心隐喻，必须逐字破译。",
            "c" to "片名是理解作品的入口之一：先看它字面指向什么，再回到影片内容验证是否呼应。",
            "d" to "片名好坏直接决定影片好坏。",
        ),
        correctIds = listOf("c"),
        knowledgePoint = "片名与原名",
    )

    /** 单选 6：国家与创作语境（对拍 singleBuilders[5]；地区取证据行里的「国家/地区：」）。 */
    private fun fallbackCountryChoice(
        movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice {
        val country = movie.evidence
            .firstOrNull { it.startsWith("国家/地区：") }
            ?.removePrefix("国家/地区：")
            ?.trim()
            .orEmpty()
        val region = if (hasCjk(country)) country else "它的出品国家"
        return FallbackChoice(
            prompt = "《$title》的出品地区是「$region」。出品地区这个信息对理解影片的实际作用是什么？",
            options = fallbackOptions(
                "a" to "没有作用，好电影超越一切地域。",
                "b" to "它提示了影片的创作语境：题材选择、表达方式常与所处环境相关，是理解的入口之一。",
                "c" to "它决定了影片品质的上限。",
                "d" to "只是发行信息，与影片内容毫无关联。",
            ),
            correctIds = listOf("b"),
            knowledgePoint = "国家与创作语境",
        )
    }

    /** 单选 7：简介核对（对拍 singleBuilders[6]）。 */
    private fun fallbackSynopsisCheckChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "你想确认某篇短文写的是不是《$title》这部作品本身，下列哪条依据最可靠？",
        options = fallbackOptions(
            "a" to "短文里出现了和已看记录简介一致的情节描述。",
            "b" to "短文标题里带了\"影评\"两个字。",
            "c" to "短文配图里有明星面孔。",
            "d" to "短文的点赞数很高。",
        ),
        correctIds = listOf("a"),
        knowledgePoint = "简介与叙事重心",
    )

    /** 单选 8：评分与个人判断（对拍 singleBuilders[7]）。 */
    private fun fallbackRatingJudgementChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "关于《$title》的公映评分和你的个人判断，下列哪项说法最合理？",
        options = fallbackOptions(
            "a" to "评分就是权威结论，个人感受必须向它看齐。",
            "b" to "评分反映群体平均口味，你的判断要回到自己最有把握的具体感受，两者可以不一致。",
            "c" to "评分毫无意义，讨论影片完全不需要参考。",
            "d" to "评分高的片子不可能有缺点。",
        ),
        correctIds = listOf("b"),
        knowledgePoint = "评分与个人判断",
    )

    /** 单选 9：开放思考（对拍 singleBuilders[8]）。 */
    private fun fallbackReviewChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "复盘《$title》时，下列哪种做法最能把\"看过\"变成\"看懂\"？",
        options = fallbackOptions(
            "a" to "记住片名和主要演员就算看懂了。",
            "b" to "回到具体场面：人物面对什么限制、做了什么选择、承担了什么后果。",
            "c" to "多刷几遍自然就懂了，不需要方法。",
            "d" to "把别人的高分短评背下来。",
        ),
        correctIds = listOf("b"),
        knowledgePoint = "开放思考",
    )

    /** 单选 10：开放思考（对拍 singleBuilders[9]）。 */
    private fun fallbackDisagreementChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "讨论《$title》时，朋友给出一个和你相反的解读。下列处理方式哪项最合理？",
        options = fallbackOptions(
            "a" to "谁的评分高听谁的。",
            "b" to "立刻放弃自己的看法。",
            "c" to "双方都回到影片具体内容：各自的解读能被哪些场面支持，再比较说服力。",
            "d" to "解读没有对错，不需要讨论依据。",
        ),
        correctIds = listOf("c"),
        knowledgePoint = "开放思考",
    )

    /** 多选 1：短评可信度（对拍 multipleBuilders[0]）。 */
    private fun fallbackCredibilityChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "看完《$title》想判断一篇短评是否可信，下列哪些做法成立？（多选）",
        options = fallbackOptions(
            "a" to "核对短评引用的情节是否真的出现在影片简介或正片中。",
            "b" to "看短评是否区分了「影片呈现的事实」与「评论者自己的解读」。",
            "c" to "点赞越多说明短评越可信。",
            "d" to "对比多条短评，看它们引用的场面能否互相印证。",
        ),
        correctIds = listOf("a", "b", "d"),
        knowledgePoint = "简介与叙事重心",
    )

    /** 多选 2：作品比较（对拍 multipleBuilders[1]）。 */
    private fun fallbackComparisonChoice(
        @Suppress("UNUSED_PARAMETER") movie: AiOrchestrateValidate.OrchestrateMovie,
        title: String,
    ): FallbackChoice = FallbackChoice(
        prompt = "对比《$title》和你今年看的另一部片，下列哪些做法能让比较有意义？（多选）",
        options = fallbackOptions(
            "a" to "并列观察两部片各自如何组织叙事与节奏。",
            "b" to "比较它们对相近主题的不同处理。",
            "c" to "对照各自的人物选择：面对类似处境时决定了什么、付出了什么。",
            "d" to "只比较评分和票房，数字高的一方赢。",
        ),
        correctIds = listOf("a", "b", "c"),
        knowledgePoint = "作品比较",
    )

    /** 是否含中日韩统一表意文字（对拍 /[\u4e00-\u9fff]/ 的片名/类型/地区判定）。 */
    private fun hasCjk(text: String): Boolean = text.any { it in '\u4e00'..'\u9fff' }

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
        val candidate = try {
            runDailyStage(
                day, locale, movies, recentUsage, DAILY_MAX_TOKENS_CANDIDATE,
            ) { hint -> dailyCandidateMessages(day, locale, movies, hint, recentUsage) }
        } catch (error: AiProviderException) {
            // 服务端兜底（daily-knowledge.ts:2257-2259）：候选失败直接交本地种子单元，不再跑二审。
            // 只有「模型答坏了」才兜底，配置/鉴权/网络错误照常上抛。
            if (!isFallbackEligible(error)) throw error
            send(AiDailyStreamEvent.Stage(AiDailyStage.CANDIDATE, AiDailyStageStatus.DONE, DAILY_STAGE_EXPECTED_CHARS))
            val fallbackUnit = fallbackDailyKnowledgeUnit(day, locale)
            val fallbackIllustration = generateIllustration(fallbackUnit, illustrationSaver)
            send(AiDailyStreamEvent.Completed(toDomainDailyKnowledge(day, fallbackUnit, fallbackIllustration)))
            return@channelFlow
        }
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

    /**
     * daily 本地种子兜底（对拍 daily-knowledge.ts:2221-2237 fallbackDailyKnowledgeUnit）。
     *
     * 种子按 locale 选；本地为控制单文件体积只内置 zh-CN 3 条、其余语言各 1 条，
     * 轮换长度即本地条数。索引 = 日期去连字符后的数字取模，保证同一天命中同一条
     * （非数字 day 取第 0 条）。种子回写成 JSON 再过一遍 normalizeDailyKnowledgeUnit，
     * 与服务端一致——手写内容也不许绕过本地质量门槛。
     */
    private fun fallbackDailyKnowledgeUnit(day: String, locale: String): DailyKnowledgeUnit {
        val seeds = DAILY_FALLBACK_SEEDS[locale] ?: DAILY_FALLBACK_SEEDS.getValue("zh-CN")
        val index = day.replace("-", "").toIntOrNull()?.rem(seeds.size) ?: 0
        val seed = seeds[if (index < 0) 0 else index]
        val payload = Json.encodeToJsonElement(DailyKnowledgeUnit.serializer(), seed)
        return AiOrchestrateValidate.normalizeDailyKnowledgeUnit(payload, day, locale, emptyList())
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
            // 兜底种子的 unitId 以 seed- 开头（对拍 handler.ts:2323-2325 isFallbackUnitId）。
            isFallback = unit.unitId.startsWith("seed-"),
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

    /**
     * 详情分析：单次调用 + 本地校验；
     * 模型答坏了（解析失败/5xx/限流）时退回保守的本地判断，不把错误抛给用户。
     */
    suspend fun analyzeDetail(
        media: AiDetailMediaDto,
        scene: String,
        environment: AiDetailEnvironmentDto?,
        profile: DetailProfile,
        profileVersion: Long,
        forceRefresh: Boolean = false,
    ): AiDetailAnalysis {
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
        return try {
            val payload = chatJson(
                AiFeatureSlot.DETAIL,
                detailMessages(input, profile),
                TEMPERATURE_PRECISE,
                DETAIL_MAX_TOKENS,
            )
            AiOrchestrateValidate.normalizeDetailOutput(payload, scene, input.environment, profileVersion)
        } catch (error: AiProviderException) {
            // 对拍 detail-handler.ts:112-130：调用与 normalize 一起被 try 包住，
            // 任何失败都 return fallbackDetail；这里只放行「模型答坏了」这一类错误。
            if (!isFallbackEligible(error)) throw error
            fallbackDetail(input, profileVersion)
        }
    }

    /**
     * 详情本地兜底（对拍 detail-handler.ts:189-221 fallbackDetail）。
     *
     * 纯规则、不调模型：用「是否有画像 + 类型是否高信号」给出保守的兴趣等级与置信度，
     * 不透传任何模型原文。reasons/摘要/评分解读/观看建议全部来自固定文案或公开字段。
     */
    private fun fallbackDetail(input: DetailRequestInput, profileVersion: Long): AiDetailAnalysis {
        val media = input.media
        val hasProfile = profileVersion > 0
        val highSignal = media.genres.any { DETAIL_HIGH_SIGNAL_GENRE_REGEX.containsMatchIn(it) }
        val interestLevel = when {
            hasProfile && highSignal -> AiDetailInterestLevel.HIGH
            highSignal -> AiDetailInterestLevel.MEDIUM
            else -> AiDetailInterestLevel.LOW
        }
        val reasons = if (hasProfile) {
            listOf("结合你已授权的观影记录进行初步匹配", "当前影视的类型和公开信息已通过结构化字段核对")
        } else {
            listOf("当前没有足够的个人口味数据", "先根据类型、简介和公开评分给出保守判断")
        }
        val spoilerFreeSummary = media.overview?.let { trimText(it, DETAIL_MAX_TEXT) }
            ?: "这是一部可以从类型、公开简介和整体气质开始了解的影视作品。"
        val publicRatingInterpretation = media.publicRating?.let { rating ->
            "公共评分约为 " + String.format(java.util.Locale.US, "%.1f", rating) +
                "，说明它在公开观众中有一定认可度，但不等同于你的个人评分。"
        } ?: "暂无足够的公共评分信息，建议结合简介和你的当下观影状态判断。"
        val watchAdvice = if (input.scene == "WATCHED_NEEDS_REVIEW") {
            "如果你还记得观影感受，可以补充评分或短评，帮助之后的推荐更准确。"
        } else {
            "可以先看简介和预告；如果当下想看轻松或有情绪余韵的作品，它值得加入候选。"
        }
        // 只有「待看语境」才给观看时机；其余场景服务端同样置空（UNKNOWN + 空依据）。
        val watchTiming = if (input.scene == "WATCHLIST_CONTEXT") {
            AiDetailWatchTiming.entries.firstOrNull { it.name == timingLevel(input.environment) }
                ?: AiDetailWatchTiming.UNKNOWN
        } else {
            AiDetailWatchTiming.UNKNOWN
        }
        val watchTimingBasis = if (input.scene == "WATCHLIST_CONTEXT") {
            timingBasis(input.environment)
        } else {
            emptyList()
        }
        return AiDetailAnalysis(
            scene = input.scene,
            interestLevel = interestLevel,
            confidence = if (hasProfile) AiDetailInterestLevel.MEDIUM else AiDetailInterestLevel.LOW,
            spoilerFreeSummary = spoilerFreeSummary,
            publicRatingInterpretation = publicRatingInterpretation,
            watchAdvice = watchAdvice,
            reasons = reasons,
            watchTiming = watchTiming,
            watchTimingBasis = watchTimingBasis,
            profileVersion = profileVersion,
        )
    }

    /** 截断到 max 字符，超出补省略号（对拍 detail-handler.ts:512 trimText）。 */
    private fun trimText(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

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
        // 排除「画像里已经有过动作」的片子：对拍 detail-handler.ts:143-150 --
        // 只要 watched/watchlist/rating 非空/commentPresent 任一成立就收它的可靠 id。
        // 用可靠 id 而不是标题：标题会因译名、副标题、年份后缀对不上而漏排。
        val profileKeys = HashSet<String>()
        val profileTitles = HashSet<String>()
        for (item in profile.media) {
            val key = reliableMediaKeyOf(item.mediaIds)
            val hasAction = item.watched == true ||
                item.watchlist == true ||
                item.rating != null ||
                item.commentPresent == true
            if (hasAction) {
                if (key != null) profileKeys.add(key)
                // 本地画像里可能缺 id（加媒体 id 透传之前写下的老条目），标题兜底仍然有用
                profileTitles.add(item.title.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE))
            }
        }
        val prepared = ArrayList<DetailCandidate>()
        val seenKeys = HashSet<String>()
        for (item in candidates) {
            val candidate = normalizeRankCandidate(item) ?: continue
            if (candidate.mediaKey == currentMedia.mediaKey) continue
            val candidateKey = reliableMediaKeyOf(sanitizeMediaIds(item.mediaIds))
            if (candidateKey != null && candidateKey in profileKeys) continue
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
        // imdb id 大小写不敏感（对拍 detail-handler.ts:416 的 toLowerCase），
        // 画像侧来自本地实体、候选侧来自 TMDB，两边大小写可能不一致，这里统一。
        mediaIds.containsKey("imdb") -> "imdb:" + mediaIds.getValue("imdb").lowercase()
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

// ---------------------------------------------------------------------------
// 兜底常量表（对拍 auth-worker 的 fallbackQuizQuestions / fallbackDailyKnowledgeUnit）
// 放在类外：纯静态数据，不参与编排状态；类内只保留取用逻辑。
// ---------------------------------------------------------------------------

/** 兜底题库的学科三件套（对拍 FALLBACK_KNOWLEDGE_EDUCATION 的 map 值）。 */
private data class FallbackEducationSeed(
    val subject: String,
    val concept: String,
    val takeaway: String,
)

/**
 * 兜底题的教育信息 + 证据说明（对拍 fallbackEducation 的返回）。
 * 与 [FallbackEducationSeed] 分开：学习结论在这里叫 learningTakeaway，对应题目字段。
 */
private data class FallbackEducation(
    val subject: String,
    val concept: String,
    val learningTakeaway: String,
    val evidenceUsed: String,
)

/**
 * 13 个知识点 → 学科/概念/学习结论（对拍 handler.ts:2821-2830 FALLBACK_KNOWLEDGE_EDUCATION）。
 * 未知考点统一回落到「开放思考」。
 */
private val FALLBACK_KNOWLEDGE_EDUCATION: Map<String, FallbackEducationSeed> = mapOf(
    "上映年份与时代背景" to FallbackEducationSeed(
        subject = "历史",
        concept = "上映年份与时代背景",
        takeaway = "年份不是要背的考点；把它和影片题材放在一起看，能读出作品回应的时代议题。",
    ),
    "类型定位" to FallbackEducationSeed(
        subject = "电影学",
        concept = "类型定位",
        takeaway = "类型标签是观察一部片的起点，真正的判断要回到它如何使用类型惯例。",
    ),
    "简介与叙事重心" to FallbackEducationSeed(
        subject = "叙事学",
        concept = "简介与叙事重心",
        takeaway = "简介概括的是叙事重心，抓住它就知道影片把笔墨花在了哪里。",
    ),
    "片名与原名" to FallbackEducationSeed(
        subject = "语言学与符号学",
        concept = "片名与原名",
        takeaway = "对照片名和原名，能看出译名选择强调或弱化了什么信息。",
    ),
    "片长与叙事节奏" to FallbackEducationSeed(
        subject = "剪辑与声音",
        concept = "片长与叙事节奏",
        takeaway = "片长是叙事节奏的粗略指标，长片短片各自承担不同的信息密度。",
    ),
    "国家与创作语境" to FallbackEducationSeed(
        subject = "文化研究",
        concept = "国家与创作语境",
        takeaway = "出品国家提示创作语境，同样的题材在不同语境下讲法不同。",
    ),
    "评分与个人判断" to FallbackEducationSeed(
        subject = "电影学",
        concept = "评分与个人判断",
        takeaway = "公映评分是群体参照，个人判断要回到自己最有把握的具体感受。",
    ),
    "开放思考" to FallbackEducationSeed(
        subject = "电影学",
        concept = "开放思考",
        takeaway = "看完一部作品，把它关于人物选择与后果的问题带回自己的现实判断里继续想。",
    ),
)

/**
 * daily 兜底种子（对拍 daily-knowledge.ts:1038-1886 DAILY_KNOWLEDGE_SEEDS 与
 * :1897-2230 LOCALIZED_FALLBACK_SEEDS）。
 *
 * 与原版的差异（为控制单文件体积）：zh-CN 内置 3 条、en-US/ja-JP/ko-KR 各 1 条，
 * 轮换长度就是本地条数；服务端 zh 有 24 条、其余语言各 3 条。种子文本逐字照抄原版，
 * 因此同样满足本地质量门槛（概念出现在随堂小题解析里、来源可核验）。
 */
private val DAILY_FALLBACK_SEEDS: Map<String, List<DailyKnowledgeUnit>> = mapOf(
    "zh-CN" to listOf(
        DailyKnowledgeUnit(
            unitId = "seed-continuity-editing",
            version = 1,
            locale = "zh-CN",
            relationType = "general_knowledge",
            evidenceMode = "film_fact",
            subjectGroup = "film_expression",
            subject = "电影学",
            concept = "连续性剪辑",
            title = "剪辑为什么让你忽略时间的跳跃",
            takeaway = "连续性剪辑用一致的视线、动作和空间关系，让观众把前后镜头理解成同一场景。",
            relatedMedia = null,
            filmEvidence = "正反打镜头、动作衔接和视线方向共同维持空间与时间连贯，观众因此常忽略剪辑点。",
            explanation = "剪辑不是简单删除时间，而是建立一套可预期的空间关系；当人物视线和动作方向保持一致，观众会自动补全镜头外的连续性。",
            realWorldExample = "看对话场景时，我们很少意识到镜头已经切换，因为两个角色的目光仍指向彼此。",
            boundary = "这是影片制作资料的入门说明，不是某部具体影片的拍摄结论。",
            difficulty = "easy",
            spoilerLevel = "none",
            source = DailyKnowledgeSource(
                name = "Encyclopaedia Britannica",
                url = "https://www.britannica.com/art/motion-picture-technology",
                evidence = "该资料介绍剪辑、镜头关系和连续性在电影制作中的作用。",
            ),
            checkQuestion = DailyCheckQuestion(
                prompt = "连续性剪辑主要帮助观众保持什么？",
                options = listOf(
                    DailyCheckOption(id = "a", text = "同一场景的空间和时间关系。"),
                    DailyCheckOption(id = "b", text = "影片的票房表现。"),
                    DailyCheckOption(id = "c", text = "演员的真实年龄。"),
                ),
                correctOptionIds = listOf("a"),
                explanation = "连续性剪辑通过视线、动作和空间关系维持场景连贯。",
            ),
            characterLine = "镜头切换的秘密被剪藏起来了！",
        ),
        DailyKnowledgeUnit(
            unitId = "seed-selective-attention",
            version = 1,
            locale = "zh-CN",
            relationType = "general_knowledge",
            evidenceMode = "external_fact",
            subjectGroup = "people_and_mind",
            subject = "认知科学",
            concept = "选择性注意",
            title = "你看到的不一定是全部画面",
            takeaway = "注意会优先处理被线索强调的信息，因此同一画面中的细节可能被不同观众遗漏。",
            relatedMedia = null,
            filmEvidence = "电影常用特写、运动、声音提示和色彩对比把观众注意导向关键信息，也常利用背景细节制造后来才被察觉的线索。",
            explanation = "感知资源有限，注意帮助筛选输入；镜头语言正是在替观众安排优先级，让复杂画面不至于变成均匀噪声。",
            realWorldExample = "在喧闹场合听清一个人说话时，其他声音并未消失，只是暂时被注意系统压低。",
            boundary = "这是认知过程的一般说明，不能据此判断具体观众的感知能力或证词可靠性。",
            difficulty = "easy",
            spoilerLevel = "none",
            source = DailyKnowledgeSource(
                name = "American Psychological Association",
                url = "https://www.apa.org/topics/perception-attention",
                evidence = "该资料说明注意会选择性分配认知资源并过滤干扰。",
            ),
            checkQuestion = DailyCheckQuestion(
                prompt = "选择性注意说明观众看同一画面时可能出现什么情况？",
                options = listOf(
                    DailyCheckOption(id = "a", text = "不同人可能关注不同信息并遗漏其他细节。"),
                    DailyCheckOption(id = "b", text = "所有人必然同时看清所有细节。"),
                    DailyCheckOption(id = "c", text = "注意完全不受声音和运动影响。"),
                ),
                correctOptionIds = listOf("a"),
                explanation = "选择性注意会分配认知资源并过滤干扰，因此同一画面中的不同细节可能被遗漏。",
            ),
            characterLine = "注意是一盏小灯，不会照亮全场。",
        ),
        DailyKnowledgeUnit(
            unitId = "seed-opportunity-cost",
            version = 1,
            locale = "zh-CN",
            relationType = "general_knowledge",
            evidenceMode = "external_fact",
            subjectGroup = "society_and_institution",
            subject = "经济学",
            concept = "机会成本",
            title = "选择的一部分，是放弃什么",
            takeaway = "机会成本指选择一个方案时所放弃的次优替代方案的价值。",
            relatedMedia = null,
            filmEvidence = "犯罪片或职场片常把追逐一个目标写成必须放弃时间、关系或另一次机会，这正是资源有限下的取舍。",
            explanation = "时间、资金和注意力不能同时投向所有方案；理解机会成本有助于把“得到什么”和“放弃什么”一起比较。",
            realWorldExample = "把周末用于进修，就不能同时用同一段时间旅行；被放弃的旅行体验就是进修选择的机会成本之一。",
            boundary = "这是经济学入门概念的事实说明，具体价值常依赖个人偏好和可获信息。",
            difficulty = "easy",
            spoilerLevel = "none",
            source = DailyKnowledgeSource(
                name = "Federal Reserve Bank of St. Louis",
                url = "https://www.stlouisfed.org/open-vault/2020/january/real-life-examples-opportunity-cost",
                evidence = "该资料说明机会成本是选择时放弃的次优替代方案价值。",
            ),
            checkQuestion = DailyCheckQuestion(
                prompt = "机会成本指的是什么？",
                options = listOf(
                    DailyCheckOption(id = "a", text = "选择某方案时放弃的次优方案价值。"),
                    DailyCheckOption(id = "b", text = "账单上写明的全部货币支出。"),
                    DailyCheckOption(id = "c", text = "所有可能方案的收益总和。"),
                ),
                correctOptionIds = listOf("a"),
                explanation = "机会成本强调选择时被放弃的次优替代方案价值，不只是显性支出。",
            ),
            characterLine = "选了这条路，也付了那条路。",
        ),
    ),
    "en-US" to listOf(
        DailyKnowledgeUnit(
            unitId = "seed-continuity-editing-en",
            version = 1,
            locale = "en-US",
            relationType = "general_knowledge",
            evidenceMode = "film_fact",
            subjectGroup = "film_expression",
            subject = "电影学",
            concept = "continuity editing",
            title = "Why editing can hide a jump in time",
            takeaway = "Continuity editing uses matching gazes, actions, and spatial relations so we read shots as one scene.",
            relatedMedia = null,
            filmEvidence = "Shot-reverse-shot patterns, matched movement, and consistent eyelines keep space and time connected across cuts.",
            explanation = "Editing does not merely remove time. It creates an expected spatial map; when gazes and actions point the same way, viewers mentally join the shots.",
            realWorldExample = "During a filmed conversation, we seldom notice each cut because the characters still appear to look at each other.",
            boundary = "This is an introductory fact from filmmaking reference material, not a claim about one specific production.",
            difficulty = "easy",
            spoilerLevel = "none",
            source = DailyKnowledgeSource(
                name = "Encyclopaedia Britannica",
                url = "https://www.britannica.com/art/motion-picture-technology",
                evidence = "The reference discusses editing, shot relations, and continuity in motion-picture technology.",
            ),
            checkQuestion = DailyCheckQuestion(
                prompt = "What does continuity editing mainly preserve?",
                options = listOf(
                    DailyCheckOption(id = "a", text = "The spatial and temporal relations of a scene."),
                    DailyCheckOption(id = "b", text = "A film box-office ranking."),
                    DailyCheckOption(id = "c", text = "An actor's actual age."),
                ),
                correctOptionIds = listOf("a"),
                explanation = "Continuity editing uses gazes, action, and spatial relations to preserve scene coherence.",
            ),
            characterLine = "The cut hid itself in plain sight!",
        ),
    ),
    "ja-JP" to listOf(
        DailyKnowledgeUnit(
            unitId = "seed-continuity-editing-ja",
            version = 1,
            locale = "ja-JP",
            relationType = "general_knowledge",
            evidenceMode = "film_fact",
            subjectGroup = "film_expression",
            subject = "电影学",
            concept = "継続的編集",
            title = "編集で時間の飛びが見えにくくなる理由",
            takeaway = "継続的編集は視線、動作、空間関係を揃え、複数のショットをひとつの場面として読ませます。",
            relatedMedia = null,
            filmEvidence = "往復ショット、動作のつなぎ、一致する視線が空間と時間の連続感を保ち、切り替わりを目立たなくします。",
            explanation = "編集は時間を削るだけではありません。視線と動作の方向が揃うと、観客はショットの外にある連続性を自然に補います。",
            realWorldExample = "会話場面では切替えが多くても、登場人物が互いを見ていれば場面は連続して感じられます。",
            boundary = "これは映画技術資料による入門説明であり、特定作品の制作結論ではありません。",
            difficulty = "easy",
            spoilerLevel = "none",
            source = DailyKnowledgeSource(
                name = "Encyclopaedia Britannica",
                url = "https://www.britannica.com/art/motion-picture-technology",
                evidence = "この資料は編集とショット関係、連続性の役割を説明しています。",
            ),
            checkQuestion = DailyCheckQuestion(
                prompt = "継続的編集が主に保つものは何ですか。",
                options = listOf(
                    DailyCheckOption(id = "a", text = "場面の空間と時間の関係。"),
                    DailyCheckOption(id = "b", text = "興行収入の順位。"),
                    DailyCheckOption(id = "c", text = "俳優の実際の年齢。"),
                ),
                correctOptionIds = listOf("a"),
                explanation = "継続的編集は視線、動作、空間関係で場面の連続性を保ちます。",
            ),
            characterLine = "切り替わりはすぐそばに隠れています。",
        ),
    ),
    "ko-KR" to listOf(
        DailyKnowledgeUnit(
            unitId = "seed-continuity-editing-ko",
            version = 1,
            locale = "ko-KR",
            relationType = "general_knowledge",
            evidenceMode = "film_fact",
            subjectGroup = "film_expression",
            subject = "电影学",
            concept = "연속 편집",
            title = "편집이 시간의 도약을 감추는 이유",
            takeaway = "연속 편집은 시선, 동작, 공간 관계를 맞춰 여러 샷을 하나의 장면으로 읽게 만듭니다.",
            relatedMedia = null,
            filmEvidence = "주고받는 샷, 이어지는 동작, 일관된 시선 방향이 공간과 시간의 연속감을 유지합니다.",
            explanation = "편집은 단순히 시간을 자르는 작업이 아닙니다. 시선과 동작의 방향이 맞으면 관객은 샷 밖의 연속성을 스스로 이어 붙입니다.",
            realWorldExample = "대화 장면에서 샷이 자주 바뀌어도 인물들이 서로를 보면 장면은 계속 이어진 것처럼 느껴집니다.",
            boundary = "이것은 영화 기술 자료에 기반한 입문 설명이며 특정 작품의 제작 결론이 아닙니다.",
            difficulty = "easy",
            spoilerLevel = "none",
            source = DailyKnowledgeSource(
                name = "Encyclopaedia Britannica",
                url = "https://www.britannica.com/art/motion-picture-technology",
                evidence = "이 자료는 편집, 샷 관계, 연속성의 역할을 설명합니다.",
            ),
            checkQuestion = DailyCheckQuestion(
                prompt = "연속 편집이 주로 유지하는 것은 무엇인가요?",
                options = listOf(
                    DailyCheckOption(id = "a", text = "장면의 공간과 시간 관계."),
                    DailyCheckOption(id = "b", text = "흥행 순위."),
                    DailyCheckOption(id = "c", text = "배우의 실제 나이."),
                ),
                correctOptionIds = listOf("a"),
                explanation = "연속 편집은 시선, 동작, 공간 관계로 장면의 연속성을 유지합니다.",
            ),
            characterLine = "컷은 바로 옆에 숨어 있었어요.",
        ),
    ),
)
