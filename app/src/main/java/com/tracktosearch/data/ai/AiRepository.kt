package com.tracktosearch.data.ai

import android.content.Context
import android.util.Base64
import com.tracktosearch.R
import com.tracktosearch.data.ai.orchestrate.AiOrchestrator
import com.tracktosearch.data.ai.orchestrate.AiQuizScoring
import com.tracktosearch.data.ai.prompt.AiCharacters
import com.tracktosearch.data.ai.prompt.DailyKnowledgeUnit
import com.tracktosearch.data.ai.prompt.DailyRecentUsage
import com.tracktosearch.data.ai.prompt.DetailProfile
import com.tracktosearch.data.ai.prompt.ProfileMediaItem
import com.tracktosearch.data.ai.prompt.TtsScene
import com.tracktosearch.data.ai.prompt.deriveBankQuizId
import com.tracktosearch.data.ai.provider.AiProviderErrorCode
import com.tracktosearch.data.ai.provider.AiProviderException
import com.tracktosearch.data.ai.provider.AiProviderSettingsRepository
import com.tracktosearch.data.ai.provider.AiFeatureSlot
import com.tracktosearch.data.local.db.AiProfileMediaEntity
import com.tracktosearch.data.remote.tmdb.dto.TmdbMultiSearchResult
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.ui.screen.discoverfilter.DiscoverFilterConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

// 服务端兼容字段已随 AiApiService 拆除；解码仍统一走宽松 Json，避免每次调用新建实例。
private val lenientJson = Json { ignoreUnknownKeys = true }

enum class AiErrorCode {
    UNAUTHORIZED,
    QUOTA_EXCEEDED,
    RATE_LIMITED,
    CHARACTER_UNAVAILABLE,
    ACTIVATION_REQUIRED,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    NETWORK,
    SERVER,
    UNKNOWN,
    // BYOK 新增：用户 Provider 配置缺失/不支持对应能力（TTS、配图）。
    PROVIDER_INVALID,
    PROVIDER_NO_TTS,
    PROVIDER_NO_IMAGE
}

class AiApiException(
    val errorCode: AiErrorCode,
    val serverCode: String?,
    val httpCode: Int,
    override val message: String
) : Exception(message)

object AiErrorMapper {
    fun map(serverCode: String?, httpCode: Int): AiErrorCode {
        // 业务错误码优先：403+ACTIVATION_REQUIRED / 429+QUOTA_EXCEEDED 等服务端返回的业务语义
        // 比 HTTP 状态码更精确，先匹配 code 再回退到 http 启发式。
        return when (serverCode.normalized().removePrefix("AI_")) {
            "UNAUTHORIZED", "AUTH_REQUIRED", "TOKEN_EXPIRED", "INVALID_TOKEN" -> AiErrorCode.UNAUTHORIZED
            "QUOTA_EXCEEDED", "DAILY_QUOTA_EXCEEDED", "SESSION_QUOTA_EXCEEDED" -> AiErrorCode.QUOTA_EXCEEDED
            "RATE_LIMITED", "TOO_MANY_REQUESTS" -> AiErrorCode.RATE_LIMITED
            "CHARACTER_UNAVAILABLE", "VOICE_NOT_READY" -> AiErrorCode.CHARACTER_UNAVAILABLE
            "ACTIVATION_REQUIRED", "INVALID_ACTIVATION" -> AiErrorCode.ACTIVATION_REQUIRED
            "INVALID_REQUEST", "VALIDATION_ERROR", "INVALID_AUDIO", "INVALID_MODEL",
            "INVALID_ACTION", "NOT_FOUND" -> AiErrorCode.INVALID_REQUEST
            "SERVER", "UPSTREAM_ERROR" -> AiErrorCode.SERVER
            "EMPTY_RESPONSE", "INVALID_RESPONSE" -> AiErrorCode.INVALID_RESPONSE
            // BYOK：Provider 层错误码直映（编排器抛出的 AiProviderException 带 serverCode=code.name）。
            "PROVIDER_INVALID" -> AiErrorCode.PROVIDER_INVALID
            "PROVIDER_NO_TTS" -> AiErrorCode.PROVIDER_NO_TTS
            "PROVIDER_NO_IMAGE" -> AiErrorCode.PROVIDER_NO_IMAGE
            else -> when {
                httpCode == 401 || httpCode == 403 -> AiErrorCode.UNAUTHORIZED
                httpCode == 429 -> AiErrorCode.RATE_LIMITED
                httpCode >= 500 -> AiErrorCode.SERVER
                // 其余 4xx 都属于请求侧问题，落 INVALID_REQUEST 给出比 UNKNOWN 更有指导性的文案
                httpCode >= 400 -> AiErrorCode.INVALID_REQUEST
                else -> AiErrorCode.UNKNOWN
            }
        }
    }

    fun fromThrowable(throwable: Throwable): AiApiException {
        val code = if (throwable is IOException) AiErrorCode.NETWORK else AiErrorCode.UNKNOWN
        return AiApiException(code, null, 0, throwable.message ?: code.name.lowercase(Locale.ROOT))
    }

    fun exception(serverCode: String?, message: String, httpCode: Int): AiApiException {
        return AiApiException(map(serverCode, httpCode), serverCode, httpCode, message)
    }

    private fun String?.normalized(): String = this.orEmpty().trim().uppercase(Locale.ROOT)
}

/**
 * BYOK AI 仓库：所有 LLM/语音/配图调用走用户自配 Provider，经 [AiOrchestrator] 本地编排完成。
 *
 * 相对旧网关版的变化：
 * - 无 AiApiService：greeting/taste/quiz/daily/detail/rank/tts/image 全部本地编排；
 * - 角色目录、激活判断、判分（quiz submit / quiz feedback）全部本地；
 * - 配额与额度概念消失，配额快照方法删除；
 * - 缓存结构（AiStorage + 域模型 JSON）保持不变，旧缓存依旧可读。
 */
@Singleton
class AiRepository @Inject constructor(
    private val orchestrator: AiOrchestrator,
    private val storage: AiStorage,
    private val json: Json,
    private val tmdbRepository: TmdbRepository,
    private val aiProfileRepository: AiProfileRepository,
    private val providerSettings: AiProviderSettingsRepository,
    // 补齐推荐的 reason 模板文案需要按当前语言生成后随缓存一起落盘
    @ApplicationContext private val context: Context
) {

    suspend fun listCharacters(): Result<List<AiCharacter>> = runApi {
        // BYOK：角色目录打进 App（7 角色全量可用，激活门槛已去除），不再请求服务端。
        AiCharacterCatalog.all.map { it.copy(isAvailable = true) }
    }

    suspend fun getGreeting(
        friendId: String,
        characterId: String,
        nickname: String,
        forceRefresh: Boolean = false
    ): Result<AiGreeting> {
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.GREETING,
            forceRefresh = forceRefresh,
            // 问候按角色 + 当前服务商/模型区分缓存：缺 characterId 时第二个角色的问候会命中第一个角色的缓存；
            // 换了 Provider（尤其换了模型）后人格口吻完全不同，不能继续回放旧缓存。
            // providerCacheTag 为空（还没配好服务商）时不追加，保持与旧版同形的一个键段。
            suffix = listOfNotNull(
                characterId,
                providerCacheTag(AiFeatureSlot.GREETING).takeIf { it.isNotEmpty() },
            ).joinToString("|"),
            serializer = AiGreeting.serializer(),
            isCacheValid = ::isUsableGreetingCache
        ) {
            orchestrator.generateGreeting(characterId, nickname)
        }.recoverWithFallback { fallbackGreeting(characterId, nickname) }
    }

    suspend fun getTaste(
        friendId: String,
        request: AiTasteRequest,
        nickname: String,
        forceRefresh: Boolean = false
    ): Result<AiTasteAnalysis> {
        val watched = request.watched.take(MAX_WATCHED_ITEMS)
        val refresh = forceRefresh || request.forceRefresh
        val watchedDigest = watchedDigest(watched)
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.TASTE,
            forceRefresh = refresh,
            // v2| 前缀做版本隔离：v2 起缓存里存的是「TMDB 核验 + 补齐」后的成品
            //（tmdbId/posterPath 已填充），旧结构缓存 key 不带该前缀，天然不命中
            suffix = "$TASTE_CACHE_VERSION|$watchedDigest",
            serializer = AiTasteAnalysis.serializer()
        ) {
            verifyAndFillTasteRecommendations(
                orchestrator.analyzeTaste(nickname, watched),
                watched
            )
        }.recoverWithFallback { fallbackTaste(nickname, watched) }
    }

    /**
     * BYOK 兜底：仅当失败属于「模型输出被校验拒绝/上游异常」时退回本地文案；
     * 配置缺失（PROVIDER_INVALID）、无授权、网络问题照常上抛引导用户处理。
     * 兜底不写缓存，下次 forceRefresh 有机会重新生成而不是永远定格兜底内容。
     */
    private fun <T> Result<T>.recoverWithFallback(fallback: () -> T): Result<T> = fold(
        onSuccess = { this },
        onFailure = { error ->
            if (error is AiApiException && error.errorCode in FALLBACK_ELIGIBLE_CODES) {
                Result.success(fallback())
            } else {
                this
            }
        }
    )

    // ========== 口味推荐 TMDB 核验与补齐 ==========

    /**
     * 对本地编排的口味推荐做 TMDB 核验：
     * - 逐条用 searchMulti 反查真实条目（标题全等 + 年份 ±1 + 类型一致），命中才保留，
     *   并回填 tmdbId/posterPath；未命中丢弃，避免渲染虚构条目；
     * - 有效条数不足 [TASTE_MIN_RECOMMENDATIONS] 时按已看列表最高频类型用 Discover 补齐；
     * - 核验/补齐后的成品直接作为 cachedRequest 的返回值写缓存，缓存里存的就是成品。
     */
    private suspend fun verifyAndFillTasteRecommendations(
        analysis: AiTasteAnalysis,
        watched: List<AiWatchedTitleDto>
    ): AiTasteAnalysis {
        val verified = mutableListOf<AiRecommendation>()
        for (recommendation in analysis.recommendations) {
            if (verified.size >= TASTE_MAX_RECOMMENDATIONS) break
            val match = findTmdbMatchFor(recommendation) ?: continue
            verified += recommendation.copy(
                id = recommendation.id.ifBlank { "tmdb-${match.id}" },
                tmdbId = match.id,
                posterPath = match.poster_path
            )
        }
        val recommendations = if (verified.size < TASTE_MIN_RECOMMENDATIONS) {
            verified + buildFallbackRecommendations(watched, verified)
        } else {
            verified
        }
        return analysis.copy(recommendations = recommendations.take(TASTE_MAX_RECOMMENDATIONS))
    }

    /**
     * 在 searchMulti 结果中找与推荐条目精确匹配的真实条目：
     * 标题全等（忽略大小写；本地化与原始标题都比对，中文语言下用原名推荐的条目也能命中）、
     * 年份 ±1 容差（推荐缺年份时跳过年份校验）、类型一致（movie/show 对应 TMDB movie/tv）。
     * searchMulti 自带 1 小时 TtlCache，重复核验成本低，逐条串行即可。
     */
    private suspend fun findTmdbMatchFor(recommendation: AiRecommendation): TmdbMultiSearchResult? {
        val title = recommendation.title.trim()
        if (title.isEmpty()) return null
        val expectedType = if (recommendation.mediaType.equals("show", ignoreCase = true)) "tv" else "movie"
        val expectedTitle = title.lowercase(Locale.ROOT)
        val response = runCatching { tmdbRepository.searchMulti(title) }.getOrNull() ?: return null
        return response.results.firstOrNull { result ->
            if (result.media_type != expectedType) return@firstOrNull false
            val candidateTitles = listOfNotNull(
                result.title, result.name, result.original_title, result.original_name
            ).map { it.trim().lowercase(Locale.ROOT) }
            if (expectedTitle !in candidateTitles) return@firstOrNull false
            val expectedYear = recommendation.year ?: return@firstOrNull true
            val candidateYear = result.release_date.orEmpty().take(4).toIntOrNull()
                ?: result.first_air_date.orEmpty().take(4).toIntOrNull()
            candidateYear != null && abs(candidateYear - expectedYear) <= 1
        }
    }

    /**
     * 已看列表最高频类型的热门影视补齐：
     * - 统计出现最多的 1-2 个 genre（Trakt slug → TMDB genre ID），按热度 Discover 拉真实热门；
     * - 排除已看（tmdbId + 标题）与已核验条目，只补到 [TASTE_MIN_RECOMMENDATIONS] 条；
     * - 补齐条目 tmdbId/posterPath 天然有，reason 用本地化模板文案；
     * - Discover 失败（网络错等）静默跳过，宁缺毋滥不抛错。
     */
    private suspend fun buildFallbackRecommendations(
        watched: List<AiWatchedTitleDto>,
        verified: List<AiRecommendation>
    ): List<AiRecommendation> {
        val needed = TASTE_MIN_RECOMMENDATIONS - verified.size
        if (needed <= 0 || watched.isEmpty()) return emptyList()
        val topGenres = watched.asSequence()
            .flatMap { it.genres.asSequence() }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .filter { it in TRAKT_SLUG_TO_TMDB_MOVIE_GENRE || it in TRAKT_SLUG_TO_TMDB_TV_GENRE }
            .take(TASTE_FALLBACK_GENRES)
        if (topGenres.isEmpty()) return emptyList()

        val excludedTmdbIds = buildSet {
            watched.forEach { watchedItem -> watchedItem.mediaIds.tmdbId?.let(::add) }
            verified.forEach { recommendation -> recommendation.tmdbId?.let(::add) }
        }
        val excludedTitles = buildSet {
            watched.forEach { watchedItem -> add(watchedItem.title.trim().lowercase(Locale.ROOT)) }
            verified.forEach { recommendation -> add(recommendation.title.trim().lowercase(Locale.ROOT)) }
        }

        val fallback = mutableListOf<AiRecommendation>()
        for (slug in topGenres) {
            val movieGenreId = TRAKT_SLUG_TO_TMDB_MOVIE_GENRE[slug]
            if (movieGenreId != null) {
                fallback += discoverFallbackCandidates(
                    TmdbRepository.DiscoverType.MOVIE, movieGenreId, excludedTmdbIds, excludedTitles
                )
            }
            if (fallback.size < needed) {
                val tvGenreId = TRAKT_SLUG_TO_TMDB_TV_GENRE[slug]
                if (tvGenreId != null) {
                    fallback += discoverFallbackCandidates(
                        TmdbRepository.DiscoverType.SHOW, tvGenreId, excludedTmdbIds, excludedTitles
                    )
                }
            }
            if (fallback.size >= needed) break
        }
        return fallback.take(needed)
    }

    /** Discover 单页热门候选：过滤已看/已核验条目后转补齐推荐，失败静默返回空列表。 */
    private suspend fun discoverFallbackCandidates(
        type: TmdbRepository.DiscoverType,
        genreId: Int,
        excludedTmdbIds: Set<Int>,
        excludedTitles: Set<String>
    ): List<AiRecommendation> {
        val page = runCatching {
            tmdbRepository.discover(
                TmdbRepository.DiscoverFilter(
                    type = type,
                    genreIds = listOf(genreId),
                    originCountries = emptyList(),
                    keywordIds = emptyList(),
                    voteAverageMin = 0f,
                    voteAverageMax = 10f,
                    releaseDateStart = null,
                    releaseDateEnd = null,
                    sortBy = TmdbRepository.DiscoverSort.POPULARITY_DESC,
                    hideWatched = false
                ),
                page = 1
            )
        }.getOrNull() ?: return emptyList()
        val isMovie = type == TmdbRepository.DiscoverType.MOVIE
        // 类型名复用 Discover 筛选页的 4 语言本地化资源，模板按当前语言生成 reason
        val genreLabel = DiscoverFilterConstants.genreName(context, genreId, isMovie)
        return page.items.mapNotNull { item ->
            val title = (if (isMovie) item.title else item.name ?: item.title).trim()
            if (title.isEmpty() || item.id <= 0 || item.id in excludedTmdbIds) return@mapNotNull null
            if (title.lowercase(Locale.ROOT) in excludedTitles) return@mapNotNull null
            AiRecommendation(
                id = "tmdb-${item.id}",
                mediaType = if (isMovie) "movie" else "show",
                title = title,
                year = (if (isMovie) item.release_date else item.first_air_date ?: item.release_date)
                    .take(4)
                    .toIntOrNull(),
                posterUrl = null,
                posterPath = item.poster_path,
                tmdbId = item.id,
                traktId = null,
                imdbId = null,
                doubanId = null,
                reason = context.getString(R.string.ai_taste_fallback_reason, genreLabel)
            )
        }
    }

    suspend fun getQuiz(
        friendId: String,
        nickname: String,
        request: AiQuizRequest = AiQuizRequest(),
        forceRefresh: Boolean = false
    ): Result<AiQuiz> {
        val watched = request.watched.take(MAX_WATCHED_ITEMS)
        val date = request.date ?: currentCacheDate()
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.QUIZ,
            forceRefresh = forceRefresh,
            // 缓存 key 带日期 + 排除集 + 片单摘要：同一天内预生成与正式出题共用一键（命中即秒开）。
            suffix = quizCacheSuffix(date, request.excludedQuizIds, watched),
            serializer = AiQuiz.serializer()
        ) {
            generateQuizOnce(friendId, nickname, date, request.excludedQuizIds, watched)
        }
    }

    /**
     * 第 [excludedQuizIds] 套的 identity：参与 quizId 哈希。
     *
     * 只用「排除个数」不够：上游把最近 id 截到 3 个，第 5 套起个数恒为 3，同一天多套会撞同一个 id。
     * 这里把排除集合全量与片单摘要一起纳入，保证「不同套 → 不同 quizId」，
     * 判分反查缓存时不会命中另一套的题包。
     */
    private fun quizSetIdentity(
        excludedQuizIds: List<String>,
        watched: List<AiWatchedTitleDto>
    ): String = listOf(
        excludedQuizIds.sorted().joinToString(","),
        watchedDigest(watched)
    ).joinToString("|")

    /** 一次性生成 13 题题包（编排器内部已有槽位兜底，失败直接抛）。 */
    private suspend fun generateQuizOnce(
        friendId: String,
        nickname: String,
        date: String,
        excludedQuizIds: List<String>,
        watched: List<AiWatchedTitleDto>
    ): AiQuiz {
        val quizId = deriveBankQuizId(friendId, date, quizSetIdentity(excludedQuizIds, watched))
        val orchestrated = orchestrator.generateQuiz(
            nickname = nickname,
            watched = watched,
            quizId = quizId,
            difficultyHint = quizDifficultyHint(friendId)
        )
        val quiz = orchestrated.quiz
        if (!quiz.isThirteenQuestionStructure) {
            throw AiErrorMapper.exception(
                "INVALID_RESPONSE",
                "Quiz must contain 13 valid questions",
                200
            )
        }
        return quiz
    }

    /**
     * BYOK 出题流式编排：阶段事件（单元槽/题槽进度）由编排器直接发，
     * 完成事件校验 13 题结构后写入本地缓存（预生成与正式请求共用缓存，预生成结果同样落盘）。
     */
    fun getQuizStream(
        friendId: String,
        nickname: String,
        request: AiQuizRequest = AiQuizRequest(),
        forceRefresh: Boolean = false
    ): Flow<AiQuizStreamEvent> = flow {
        if (friendId.trim().isEmpty()) {
            throw AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400)
        }
        val watched = request.watched.take(MAX_WATCHED_ITEMS)
        val date = request.date ?: currentCacheDate()
        val suffix = quizCacheSuffix(date, request.excludedQuizIds, watched)
        if (!forceRefresh) {
            readCached(friendId, AiCacheFeature.QUIZ, AiQuiz.serializer(), suffix)
                ?.takeIf { it.isThirteenQuestionStructure }
                ?.let {
                    emit(AiQuizStreamEvent.Completed(it))
                    return@flow
                }
        }
        val quizId = deriveBankQuizId(friendId, date, quizSetIdentity(request.excludedQuizIds, watched))
        orchestrator.generateQuizStream(
            nickname = nickname,
            watched = watched,
            quizId = quizId,
            difficultyHint = quizDifficultyHint(friendId)
        ).collect { event ->
            if (event is AiQuizStreamEvent.Completed) {
                val quiz = event.quiz
                if (!quiz.isThirteenQuestionStructure) {
                    throw AiErrorMapper.exception(
                        "INVALID_RESPONSE",
                        "Quiz must contain 13 valid questions",
                        200
                    )
                }
                runCatching {
                    storage.write(
                        friendId,
                        AiCacheFeature.QUIZ,
                        json.encodeToString(AiQuiz.serializer(), quiz),
                        suffix
                    )
                }
            }
            emit(event)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * BYOK 本地判分：分值/判对规则照搬服务端 scoreQuiz（scoringVersion=2）。
     * correctAnswerTextById 来自题包自带的 correctAnswer（编排链已写进题目本体）。
     */
    suspend fun submitQuiz(
        friendId: String,
        quizId: String,
        answers: List<AiQuizAnswer>
    ): Result<AiQuizResult> = runForFriend(friendId) {
        val quiz = readCachedQuizById(friendId, quizId)
            ?: throw AiErrorMapper.exception(
                "INVALID_REQUEST",
                "Quiz not found locally: $quizId",
                400
            )
        scoreQuizLocally(quiz, answers)
    }

    suspend fun submitQuiz(
        friendId: String,
        request: AiSubmitQuizRequest
    ): Result<AiQuizResult> = submitQuiz(
        friendId,
        request.quizId,
        request.answers.map {
            AiQuizAnswer(questionId = it.questionId, selectedOptionIds = it.selectedOptionIds, textAnswer = it.textAnswer)
        }
    )

    /** 难度反馈只记本地（喂下次出题的难度提示），不再上传服务端。 */
    suspend fun submitQuizDifficulty(
        friendId: String,
        quizId: String,
        difficulty: AiQuizDifficulty
    ): Result<Unit> = runForFriend(friendId) {
        recordQuizDifficultyFeedback(friendId, quizId, difficulty)
        Unit
    }

    suspend fun getDailyKnowledge(
        friendId: String,
        forceRefresh: Boolean = false,
        watched: List<AiWatchedTitleDto> = emptyList(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): Result<AiDailyKnowledge> {
        val normalizedLocale = locale.trim()
        if (normalizedLocale !in AiDailyKnowledgeContract.SUPPORTED_LOCALES) {
            return Result.failure(
                AiErrorMapper.exception(
                    "INVALID_REQUEST",
                    "Unsupported daily knowledge locale: $normalizedLocale",
                    400
                )
            )
        }
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.DAILY_KNOWLEDGE,
            forceRefresh = forceRefresh,
            suffix = dailyCacheSuffix(normalizedLocale, watched),
            // zh-CN 是旧缓存的真实语言。新键未命中时读旧日期键，避免升级当天强制重新请求。
            fallbackSuffix = if (normalizedLocale == AiDailyKnowledgeContract.DEFAULT_LOCALE) {
                legacyDailyCacheSuffix(watched)
            } else {
                null
            },
            serializer = AiDailyKnowledge.serializer()
        ) {
            generateDailyKnowledge(friendId, watched.take(MAX_WATCHED_ITEMS), normalizedLocale)
        }
    }

    /** 一次性生成今日知识（候选 + 审校两段都在编排器内部），插图落 cacheDir。 */
    private suspend fun generateDailyKnowledge(
        friendId: String,
        watched: List<AiWatchedTitleDto>,
        locale: String
    ): AiDailyKnowledge {
        val day = currentDailyKnowledgeDate().toString()
        val recentUsage = readDailyKnowledgeHistory(friendId)
            .take(DAILY_RECENT_USAGE_WINDOW)
            .map { record ->
                DailyRecentUsage(
                    day = record.shownDate,
                    concept = record.concept.orEmpty(),
                    subject = record.subject.orEmpty(),
                    mediaTitle = record.relatedMedia?.title
                )
            }
        var completed: AiDailyKnowledge? = null
        orchestrator.generateDailyStream(
            day = day,
            locale = locale,
            watched = watched,
            recentUsage = recentUsage,
            illustrationSaver = dailyIllustrationSaver(day)
        ).collect { event ->
            if (event is AiDailyStreamEvent.Completed) {
                completed = event.daily
            }
        }
        return completed
            ?: throw AiErrorMapper.exception("EMPTY_RESPONSE", "Daily stream ended without a result", 200)
    }

    /**
     * 概念插图静默刷新：BYOK 插图是本地文件（file://），不存在签名过期问题，
     * 缓存命中即返回；未命中（比如被清理）重新走一次编排，forceRefresh=false 不重复生成新概念。
     */
    suspend fun refreshDailyKnowledge(
        friendId: String,
        watched: List<AiWatchedTitleDto> = emptyList(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): Result<AiDailyKnowledge> {
        val normalizedLocale = locale.trim()
        if (normalizedLocale !in AiDailyKnowledgeContract.SUPPORTED_LOCALES) {
            return Result.failure(
                AiErrorMapper.exception(
                    "INVALID_REQUEST",
                    "Unsupported daily knowledge locale: $normalizedLocale",
                    400
                )
            )
        }
        readCachedWithFallback(
            friendId,
            AiCacheFeature.DAILY_KNOWLEDGE,
            AiDailyKnowledge.serializer(),
            dailyCacheSuffix(normalizedLocale, watched),
            if (normalizedLocale == AiDailyKnowledgeContract.DEFAULT_LOCALE) legacyDailyCacheSuffix(watched) else null
        )?.let { return Result.success(it) }
        return getDailyKnowledge(friendId, forceRefresh = false, watched = watched, locale = normalizedLocale)
    }

    fun getDailyStream(
        friendId: String,
        forceRefresh: Boolean = false,
        watched: List<AiWatchedTitleDto> = emptyList(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): Flow<AiDailyStreamEvent> = flow {
        val normalizedLocale = locale.trim()
        if (normalizedLocale !in AiDailyKnowledgeContract.SUPPORTED_LOCALES) {
            throw AiErrorMapper.exception(
                "INVALID_REQUEST",
                "Unsupported daily knowledge locale: $normalizedLocale",
                400
            )
        }
        val trimmedWatched = watched.take(MAX_WATCHED_ITEMS)
        val suffix = dailyCacheSuffix(normalizedLocale, trimmedWatched)
        if (!forceRefresh) {
            readCachedWithFallback(
                friendId,
                AiCacheFeature.DAILY_KNOWLEDGE,
                AiDailyKnowledge.serializer(),
                suffix,
                if (normalizedLocale == AiDailyKnowledgeContract.DEFAULT_LOCALE) {
                    legacyDailyCacheSuffix(trimmedWatched)
                } else {
                    null
                }
            )?.let {
                emit(AiDailyStreamEvent.Completed(it))
                return@flow
            }
        }
        val day = currentDailyKnowledgeDate().toString()
        val recentUsage = readDailyKnowledgeHistory(friendId)
            .take(DAILY_RECENT_USAGE_WINDOW)
            .map { record ->
                DailyRecentUsage(
                    day = record.shownDate,
                    concept = record.concept.orEmpty(),
                    subject = record.subject.orEmpty(),
                    mediaTitle = record.relatedMedia?.title
                )
            }
        orchestrator.generateDailyStream(
            day = day,
            locale = normalizedLocale,
            watched = trimmedWatched,
            recentUsage = recentUsage,
            illustrationSaver = dailyIllustrationSaver(day)
        ).collect { event ->
            if (event is AiDailyStreamEvent.Completed) {
                runCatching {
                    storage.write(
                        friendId,
                        AiCacheFeature.DAILY_KNOWLEDGE,
                        json.encodeToString(AiDailyKnowledge.serializer(), event.daily),
                        suffix
                    )
                }
            }
            emit(event)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun markDailyKnowledgeShown(
        friendId: String,
        knowledge: AiDailyKnowledge,
        shownDate: LocalDate = currentDailyKnowledgeDate()
    ): AiDailyKnowledgeHistoryRecord? = dailyKnowledgeHistoryMutex.withLock {
        val normalizedFriendId = friendId.trim()
        if (normalizedFriendId.isEmpty()) return@withLock null
        val unitId = knowledge.stableHistoryUnitId() ?: return@withLock null
        val locale = knowledge.locale.trim().ifEmpty { AiDailyKnowledgeContract.DEFAULT_LOCALE }
        if (locale !in AiDailyKnowledgeContract.SUPPORTED_LOCALES) return null

        val history = readDailyKnowledgeHistoryContainer(normalizedFriendId) ?: return null
        val shownDateText = shownDate.toString()
        history.records
            .firstOrNull { it.matchesDailyHistory(shownDateText, locale, unitId) }
            ?.let { return it }

        val now = System.currentTimeMillis()
        val nextSequence = (history.records.maxOfOrNull { it.sequence } ?: 0L) + 1L
        val record = AiDailyKnowledgeHistoryRecord(
            unitId = unitId,
            shownDate = shownDateText,
            locale = locale,
            relatedMedia = knowledge.historyRelatedMedia(),
            subject = knowledge.subject?.trim()?.takeIf { it.isNotEmpty() },
            concept = knowledge.concept?.trim()?.takeIf { it.isNotEmpty() },
            knowledge = knowledge,
            sequence = nextSequence,
            shownAt = now,
            updatedAt = now
        )
        val records = pruneDailyKnowledgeHistory(
            records = history.records + record,
            referenceDate = dailyHistoryReferenceDate(history.records, shownDate)
        )
        return@withLock writeDailyKnowledgeHistory(normalizedFriendId, records, record)
    }

    /** 离线读取本地知识历史，按展示日期和展示序列倒序。 */
    suspend fun readDailyKnowledgeHistory(friendId: String): List<AiDailyKnowledgeHistoryRecord> {
        val normalizedFriendId = friendId.trim()
        if (normalizedFriendId.isEmpty()) return emptyList()
        val history = readDailyKnowledgeHistoryContainer(normalizedFriendId) ?: return emptyList()
        return sortDailyKnowledgeHistoryRecords(history.records)
    }

    /** 离线读取某天、某种语言正在展示的知识；同天多次换题时返回最近展示的一条。 */
    suspend fun readDailyKnowledgeForDate(
        friendId: String,
        shownDate: LocalDate,
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): AiDailyKnowledge? {
        val normalizedLocale = locale.trim()
        if (normalizedLocale !in AiDailyKnowledgeContract.SUPPORTED_LOCALES) return null
        return readDailyKnowledgeHistory(friendId)
            .firstOrNull { it.shownDate == shownDate.toString() && it.locale == normalizedLocale }
            ?.knowledge
    }

    /**
     * 记录即时小题的首答结果。
     *
     * 正确性用记录里缓存的小题答案判断，调用方不能把错误结果写成正确。
     * 已有答案时直接返回旧记录，不覆盖首答。
     */
    suspend fun recordDailyKnowledgeQuestionAnswer(
        friendId: String,
        unitId: String,
        selectedOptionIds: List<String>,
        shownDate: LocalDate = currentDailyKnowledgeDate(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): AiDailyKnowledgeHistoryRecord? {
        val normalizedUnitId = unitId.trim()
        if (normalizedUnitId.isEmpty()) return null
        return updateDailyKnowledgeHistoryRecord(
            friendId = friendId,
            unitId = normalizedUnitId,
            shownDate = shownDate,
            locale = locale
        ) { record ->
            if (record.questionCompleted && record.questionResult != null) {
                return@updateDailyKnowledgeHistoryRecord record
            }
            val question = record.knowledge.checkQuestion
                ?: return@updateDailyKnowledgeHistoryRecord null
            val selected = selectedOptionIds.map { it.trim() }.filter { it.isNotEmpty() }
            AiDailyKnowledgeHistoryRecord(
                unitId = record.unitId,
                shownDate = record.shownDate,
                locale = record.locale,
                relatedMedia = record.relatedMedia,
                subject = record.subject,
                concept = record.concept,
                knowledge = record.knowledge,
                questionCompleted = true,
                questionResult = AiDailyKnowledgeQuestionResult(
                    selectedOptionIds = selected,
                    correct = selected.toSet() == question.correctOptionIds.toSet(),
                    answeredAt = System.currentTimeMillis()
                ),
                contentFeedback = record.contentFeedback,
                difficultyFeedback = record.difficultyFeedback,
                sequence = record.sequence,
                shownAt = record.shownAt,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /** 保存知识内容反馈（有帮助 / 太泛 / 关系弱 / 太难 / 片透多）。 */
    suspend fun saveDailyKnowledgeContentFeedback(
        friendId: String,
        unitId: String,
        feedback: AiDailyKnowledgeContentFeedback,
        shownDate: LocalDate = currentDailyKnowledgeDate(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): AiDailyKnowledgeHistoryRecord? = updateDailyKnowledgeHistoryRecord(
        friendId = friendId,
        unitId = unitId,
        shownDate = shownDate,
        locale = locale
    ) { record ->
        record.copy(
            contentFeedback = feedback,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 保存即时小题难度反馈（太简单 / 刚刚好 / 有点难）。 */
    suspend fun saveDailyKnowledgeDifficultyFeedback(
        friendId: String,
        unitId: String,
        difficulty: AiQuizDifficulty,
        shownDate: LocalDate = currentDailyKnowledgeDate(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): AiDailyKnowledgeHistoryRecord? = updateDailyKnowledgeHistoryRecord(
        friendId = friendId,
        unitId = unitId,
        shownDate = shownDate,
        locale = locale
    ) { record ->
        record.copy(
            difficultyFeedback = difficulty,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 读取闯关历史（离线可浏览最近/最高成绩）。 */
    suspend fun readQuizHistory(friendId: String): AiQuizHistory? = runCatching {
        storage.read(friendId, AiCacheFeature.QUIZ_RESULT)?.let { raw ->
            json.decodeFromJsonElement(AiQuizHistory.serializer(), json.parseToJsonElement(raw))
        }
    }.getOrNull()

    /**
     * 读取上次激活的角色 ID。
     *
     * 激活态只放在内存里的话，杀进程或被系统回收后就没了，用户得重新喊一次名字；
     * 详情页那种没有激活入口的页面则永远拿不到激活态。
     */
    suspend fun readActivatedCharacterId(friendId: String): String? = runCatching {
        storage.read(friendId, AiCacheFeature.ACTIVATION)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    suspend fun saveActivatedCharacterId(friendId: String, characterId: String) {
        val normalized = characterId.trim()
        if (normalized.isEmpty()) return
        runCatching { storage.write(friendId, AiCacheFeature.ACTIVATION, normalized) }
    }

    /** 昵称变化后清除当前精灵的旧点评，避免昵称原文与解析结果错配。 */
    suspend fun clearGreetingCache(friendId: String, characterId: String) {
        val normalizedFriendId = friendId.trim()
        val normalizedCharacterId = characterId.trim()
        if (normalizedFriendId.isEmpty() || normalizedCharacterId.isEmpty()) return
        runCatching {
            storage.remove(normalizedFriendId, AiCacheFeature.GREETING, normalizedCharacterId)
        }
    }

    /** 保存闯关历史：合并更新最高分，保留最近一次结果。 */
    suspend fun saveQuizHistory(friendId: String, result: AiQuizResult) {
        val previous = readQuizHistory(friendId)
        val history = AiQuizHistory(
            bestScore = maxOf(previous?.bestScore ?: 0, result.score),
            lastResult = result
        )
        runCatching {
            storage.write(friendId, AiCacheFeature.QUIZ_RESULT, json.encodeToString(AiQuizHistory.serializer(), history))
        }
    }

    suspend fun playTts(
        friendId: String,
        request: AiTtsRequest
    ): Result<AiAudio> {
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.TTS,
            forceRefresh = false,
            suffix = request.cacheSuffix(providerCacheTag(AiFeatureSlot.TTS)),
            serializer = AiAudio.serializer(),
            isCacheValid = ::isUsableTtsCache
        ) {
            synthesizeTtsAudio(request)
        }
    }

    suspend fun playTts(
        friendId: String,
        characterId: String,
        text: String,
        style: String? = null
    ): Result<AiAudio> = playTts(friendId, AiTtsRequest(characterId, text, style))

    /** 访客角色试听：不读写 friendId、授权状态或本地 AI 缓存。 */
    suspend fun playGuestTts(request: AiTtsRequest): Result<AiAudio> = runApi {
        synthesizeTtsAudio(request)
    }

    /** BYOK 语音合成：Provider 返回 MP3 字节，包成 data URL 进现有缓存结构。 */
    private suspend fun synthesizeTtsAudio(request: AiTtsRequest): AiAudio {
        val bytes = orchestrator.synthesizeTts(
            characterId = request.characterId,
            text = request.text,
            scene = request.scene.toPromptScene()
        )
        return AiAudio(
            audioDataUrl = "data:audio/mpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
            audioUrl = null,
            audioUrlExpiresAt = null,
            mimeType = "audio/mpeg",
            durationMs = null,
            cacheKey = request.cacheSuffix(providerCacheTag(AiFeatureSlot.TTS)),
            // transcript 与服务端同语义：剥掉口头禅，只保留正文（音频里才有口头禅）
            transcript = orchestrator.stripCatchphraseFromTranscript(request.characterId, request.text)
        )
    }

    /** 域场景枚举 → 编排层场景枚举（缺省按 GREETING，与服务端 readTtsScene 回退一致）。 */
    private fun AiTtsScene?.toPromptScene(): TtsScene = when (this) {
        AiTtsScene.AUDITION -> TtsScene.AUDITION
        AiTtsScene.ACTIVATION_ACK -> TtsScene.ACTIVATION_ACK
        AiTtsScene.GREETING, null -> TtsScene.GREETING
    }

    /**
     * 详情页无剧透分析。
     *
     * 缓存后缀把 mediaKey、场景与环境键（日期/星期/时段/季节/天气）一起摘要：
     * 同一部片在「已想看」和「已看过未评」下的分析结论不同，换时段问「值不值得看」也该重新算，
     * 只按 mediaKey 缓存会把上一个场景的结论套到下一个场景。
     */
    suspend fun analyzeDetail(
        friendId: String,
        request: AiDetailAnalyzeRequest,
        forceRefresh: Boolean = request.forceRefresh
    ): Result<AiDetailAnalysis> {
        val cacheSuffix = listOf(
            request.media.mediaKey,
            request.scene,
            request.environment?.let {
                listOf(it.localDate, it.weekday, it.timeOfDay, it.season, it.weatherTag.orEmpty())
                    .joinToString("|")
            }.orEmpty()
        ).joinToString("|").sha256Hex()
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.DETAIL_ANALYSIS,
            forceRefresh = forceRefresh,
            suffix = cacheSuffix,
            serializer = AiDetailAnalysis.serializer()
        ) {
            val profileBundle = buildDetailProfile(friendId)
            orchestrator.analyzeDetail(
                media = request.media,
                scene = request.scene,
                environment = request.environment,
                profile = profileBundle.profile,
                profileVersion = profileBundle.version,
                forceRefresh = forceRefresh
            )
        }
    }

    /** 相关推荐排序：候选只来自客户端已加载的推荐列表，编排器不引入新片，因此不做缓存。 */
    suspend fun rankDetailRecommendations(
        friendId: String,
        request: AiRecommendationsRankRequest
    ): Result<List<AiDetailRecommendation>> = runForFriend(friendId) {
        val profileBundle = buildDetailProfile(friendId)
        orchestrator.rankDetailRecommendations(
            currentMedia = request.currentMedia,
            // 候选 DTO 只有基础字段：overview/演职员/评分位补空，排序只用画像与类型信息。
            candidates = request.candidates.map { recommendation ->
                AiDetailMediaDto(
                    mediaKey = recommendation.mediaKey,
                    mediaType = recommendation.mediaType,
                    title = recommendation.title,
                    year = recommendation.year,
                    genres = recommendation.genres,
                    overview = null,
                    directors = emptyList(),
                    cast = emptyList(),
                    publicRating = null,
                    mediaIds = recommendation.mediaIds,
                    userRating = null,
                    userComment = null
                )
            },
            profile = profileBundle.profile,
            profileVersion = profileBundle.version
        )
    }

    // ========== BYOK 本地画像读取与判分辅助 ==========

    private data class DetailProfileBundle(
        val profile: DetailProfile,
        val version: Long
    )

    /** 从本地画像镜像构建 detail/rank 用的用户画像（标题非空、最多 80 条）。 */
    private suspend fun buildDetailProfile(friendId: String): DetailProfileBundle {
        val media = aiProfileRepository.readProfileMedia(friendId)
        val items = media.asSequence()
            .filter { it.title.isNotBlank() }
            .map { entity ->
                ProfileMediaItem(
                    title = entity.title.trim(),
                    mediaType = if (entity.mediaType.equals("show", ignoreCase = true)) "show" else "movie",
                    genres = parseStoredGenres(entity.genresJson),
                    year = entity.year,
                    // 画像条目的完整信号：排序器要靠「已看/想看/评分/评论」决定推荐取舍，
                    // 只传标题会让同名片与重拍片无法区分（服务端 ProfileMediaView 同样 11 字段）。
                    mediaKey = entity.mediaKey,
                    mediaIds = buildMap {
                        entity.tmdbId?.let { put("tmdb", it.toString()) }
                        entity.traktId?.let { put("trakt", it.toString()) }
                        entity.imdbId?.takeIf { it.isNotBlank() }?.let { put("imdb", it) }
                        entity.doubanId?.takeIf { it.isNotBlank() }?.let { put("douban", it) }
                    },
                    watchlist = entity.isWatchlist,
                    watched = entity.isWatched,
                    rating = entity.userRating,
                    commentPresent = !entity.userComment.isNullOrBlank()
                )
            }
            .take(DETAIL_PROFILE_MAX_ITEMS)
            .toList()
        return DetailProfileBundle(
            profile = DetailProfile(media = items),
            version = media.maxOfOrNull { it.updatedAt } ?: 0L
        )
    }

    private fun parseStoredGenres(raw: String): List<String> = runCatching {
        lenientJson.decodeFromString(ListSerializer(String.serializer()), raw)
    }.getOrNull().orEmpty()

    /** 从本地缓存找题包（按 quizId），找不到说明缓存被清，判分无从谈起。 */
    private suspend fun readCachedQuizById(friendId: String, quizId: String): AiQuiz? {
        for (suffix in storage.readSuffixes(friendId, AiCacheFeature.QUIZ)) {
            val quiz = readCached(friendId, AiCacheFeature.QUIZ, AiQuiz.serializer(), suffix) ?: continue
            if (quiz.quizId == quizId) return quiz
        }
        return null
    }

    /** 本地判分（scoringVersion=2），short 题标准答案文本来自题包 correctAnswer。 */
    private fun scoreQuizLocally(quiz: AiQuiz, answers: List<AiQuizAnswer>): AiQuizResult {
        val correctAnswerTextById = quiz.questions
            .mapNotNull { question -> question.correctAnswer?.let { question.id to it } }
            .toMap()
        return AiQuizScoring.scoreQuiz(
            quiz = quiz,
            answers = answers,
            scoringVersion = AiQuizScoring.SCORING_VERSION,
            correctAnswerTextById = correctAnswerTextById
        )
    }

    // ========== quiz 难度反馈（本地） ==========

    /** quiz 难度反馈本地存储（喂下次出题的难度提示），只保留最近 [QUIZ_DIFFICULTY_FEEDBACK_CAP] 条。 */
    @kotlinx.serialization.Serializable
    private data class QuizDifficultyFeedbackEntry(
        val quizId: String,
        val difficulty: String,
        val at: Long
    )

    private suspend fun recordQuizDifficultyFeedback(
        friendId: String,
        quizId: String,
        difficulty: AiQuizDifficulty
    ) {
        runCatching {
            val previous = readQuizDifficultyFeedback(friendId)
            val entry = QuizDifficultyFeedbackEntry(
                quizId = quizId,
                difficulty = difficulty.wireValue,
                at = System.currentTimeMillis()
            )
            val updated = (previous + entry).takeLast(QUIZ_DIFFICULTY_FEEDBACK_CAP)
            storage.write(
                friendId,
                AiCacheFeature.QUIZ_RESULT,
                json.encodeToString(
                    ListSerializer(QuizDifficultyFeedbackEntry.serializer()),
                    updated
                ),
                QUIZ_DIFFICULTY_FEEDBACK_SUFFIX
            )
        }
    }

    private suspend fun readQuizDifficultyFeedback(friendId: String): List<QuizDifficultyFeedbackEntry> =
        runCatching {
            storage.read(friendId, AiCacheFeature.QUIZ_RESULT, QUIZ_DIFFICULTY_FEEDBACK_SUFFIX)
                ?.let { raw ->
                    json.decodeFromJsonElement(
                        ListSerializer(QuizDifficultyFeedbackEntry.serializer()),
                        json.parseToJsonElement(raw)
                    )
                }
        }.getOrNull().orEmpty()

    /**
     * 出题难度提示（对拍服务端 readQuizDifficultyHint）：最近 5 条难度反馈里
     * hard≥3 提示放简单、easy≥3 提示加难，都没有则不传提示。
     */
    private suspend fun quizDifficultyHint(friendId: String): String? {
        val recent = readQuizDifficultyFeedback(friendId).takeLast(QUIZ_DIFFICULTY_FEEDBACK_WINDOW)
        if (recent.size < QUIZ_DIFFICULTY_FEEDBACK_WINDOW) return null
        val hardCount = recent.count { it.difficulty == AiQuizDifficulty.HARD.wireValue }
        val easyCount = recent.count { it.difficulty == AiQuizDifficulty.EASY.wireValue }
        return when {
            hardCount >= QUIZ_DIFFICULTY_HINT_THRESHOLD -> QUIZ_DIFFICULTY_HARD_HINT
            easyCount >= QUIZ_DIFFICULTY_HINT_THRESHOLD -> QUIZ_DIFFICULTY_EASY_HINT
            else -> null
        }
    }

    // ========== greeting/taste 本地兜底（对拍服务端 fallback 文案） ==========

    /**
     * 允许本地兜底的错误码：模型输出校验失败/上游异常/限流时退兜底文案；
     * 配置缺失、鉴权失败、断网必须如实报错，让用户去配置页修。
     */
    private val FALLBACK_ELIGIBLE_CODES = setOf(
        AiErrorCode.INVALID_RESPONSE,
        AiErrorCode.SERVER,
        AiErrorCode.RATE_LIMITED,
        AiErrorCode.UNKNOWN
    )

    private fun fallbackNameSignals(nickname: String): List<AiNameSignal> {
        val normalized = nickname.trim()
        if (normalized.isEmpty() || normalized.all { it.isDigit() || !it.isLetterOrDigit() } || normalized.length < 2) {
            return emptyList()
        }
        return listOf(
            AiNameSignal(
                text = normalized,
                interpretation = "直接引用昵称原文；仅凭这组文字，无法可靠推断更多个人信息。"
            )
        )
    }

    private fun fallbackNicknameSignature(nickname: String): String {
        val normalized = nickname.trim()
        return if (normalized.isEmpty() || normalized.all { it.isDigit() || !it.isLetterOrDigit() } || normalized.length < 2) {
            "这个昵称提供的文字线索有限，真正的观影选择比名字更能说明你的口味。"
        } else {
            "这个昵称像一个有记忆点的标记；更具体的个性，留给你的片单来说明。"
        }
    }

    private fun fallbackGreeting(characterId: String, nickname: String): AiGreeting {
        val persona = AiCharacters.findCharacter(characterId)
        val characterName = persona?.name ?: "你的观影精灵"
        val catchphrase = persona?.greetingCatchphrase.orEmpty()
        val greeting = listOfNotNull(
            "${nickname}，我是${characterName}。",
            catchphrase.takeIf { it.isNotBlank() }
        ).joinToString("")
        return AiGreeting(
            nickname = nickname,
            greeting = greeting,
            spokenText = greeting,
            nicknameMeaning = "“${nickname}”的文字联想有限，我先不替它编造隐藏寓意。",
            comment = "真正能让人记住你的，还是你挑片时留下的细节。",
            audio = null,
            quota = null,
            nameSignals = fallbackNameSignals(nickname),
            nicknameSignature = fallbackNicknameSignature(nickname)
        )
    }

    private fun fallbackTasteEvidence(watched: List<AiWatchedTitleDto>): List<AiTasteEvidence> =
        watched.take(TASTE_FALLBACK_EVIDENCE_COUNT).map { item ->
            AiTasteEvidence(
                title = item.title,
                signal = "出现在你的已看记录中。",
                inference = "它只是样本的一部分，单凭这一部不能代表稳定偏好。",
                confidence = "low"
            )
        }

    private fun fallbackTaste(nickname: String, watched: List<AiWatchedTitleDto>): AiTasteAnalysis = AiTasteAnalysis(
        roast = "${nickname}的片单像一条有方向感的散步路线：看似随意，其实总在寻找一点余韵。",
        tasteProfile = "${nickname}的片单暂时显示出对故事余韵和人物选择的兴趣，但样本仍需要更多作品来确认。",
        highlights = emptyList(),
        recommendations = emptyList(),
        quota = null,
        profileKeywords = listOf("重视故事余韵", "关注人物选择", "喜欢留白"),
        profileSentence = "${nickname}的片单暂时显示出对故事余韵和人物选择的兴趣，但样本仍需要更多作品来确认。",
        evidence = fallbackTasteEvidence(watched)
    )

    // ========== 底层：错误包装与缓存 ==========

    private suspend fun <T> runForFriend(friendId: String, block: suspend () -> T): Result<T> {
        if (friendId.trim().isEmpty()) {
            return Result.failure(AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400))
        }
        return runApi(block)
    }

    private suspend fun <T> runApi(block: suspend () -> T): Result<T> {
        return try {
            // 统一在 IO 线程执行：内部既有静态网络调用（已各自包 IO），也有
            // EncryptedSharedPreferences 的整份解密（缓存读写）。放主线程会在
            // 配置页/首屏进入时卡帧，且 Keystore 解密在 ANR 阈值内不稳定。
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiApiException) {
            Result.failure(e)
        } catch (e: AiProviderException) {
            // 编排器/适配器抛出的 Provider 异常统一映射成 AiApiException，
            // 否则会落到下面的兜底分支被吞成 UNKNOWN。
            Result.failure(e.toAiApiException())
        } catch (e: IOException) {
            Result.failure(AiErrorMapper.fromThrowable(e))
        } catch (e: kotlinx.serialization.SerializationException) {
            // 响应结构对不上 DTO：归为响应异常而非裸抛，给用户"内容异常"而非兜底文案
            Result.failure(AiErrorMapper.exception("INVALID_RESPONSE", e.message ?: "INVALID_RESPONSE", 200))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Provider 错误码 → 客户端错误码（PROVIDER_AUTH 映射 UNAUTHORIZED，UI 走「检查登录」提示之外的专属文案）。 */
    private fun AiProviderException.toAiApiException(): AiApiException = AiApiException(
        errorCode = when (code) {
            AiProviderErrorCode.PROVIDER_INVALID -> AiErrorCode.PROVIDER_INVALID
            AiProviderErrorCode.PROVIDER_NO_TTS -> AiErrorCode.PROVIDER_NO_TTS
            AiProviderErrorCode.PROVIDER_NO_IMAGE -> AiErrorCode.PROVIDER_NO_IMAGE
            AiProviderErrorCode.PROVIDER_AUTH -> AiErrorCode.UNAUTHORIZED
            AiProviderErrorCode.RATE_LIMITED -> AiErrorCode.RATE_LIMITED
            AiProviderErrorCode.INVALID_REQUEST -> AiErrorCode.INVALID_REQUEST
            AiProviderErrorCode.INVALID_RESPONSE -> AiErrorCode.INVALID_RESPONSE
            AiProviderErrorCode.NETWORK -> AiErrorCode.NETWORK
            AiProviderErrorCode.SERVER -> AiErrorCode.SERVER
            AiProviderErrorCode.UNKNOWN -> AiErrorCode.UNKNOWN
        },
        serverCode = code.name,
        httpCode = httpStatus ?: 0,
        message = message ?: code.name
    )

    private suspend fun <T> cachedRequest(
        friendId: String,
        feature: AiCacheFeature,
        forceRefresh: Boolean,
        serializer: KSerializer<T>,
        suffix: String? = null,
        fallbackSuffix: String? = null,
        isCacheValid: (T) -> Boolean = { true },
        block: suspend () -> T
    ): Result<T> {
        if (friendId.trim().isEmpty()) {
            return Result.failure(AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400))
        }
        if (!forceRefresh) {
            readCachedWithFallback(friendId, feature, serializer, suffix, fallbackSuffix)
                ?.takeIf(isCacheValid)
                ?.let { return Result.success(it) }
        }
        return try {
            val value = block()
            // 缓存写入失败不能连累这次请求：模型已经生成完、额度也已经花掉，
            // 因为落盘异常（keystore 重置、磁盘满）把成功结果变成错误返回是最糟的结果。
            runCatching { storage.write(friendId, feature, json.encodeToString(serializer, value), suffix) }
            Result.success(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!forceRefresh) {
                readCachedWithFallback(friendId, feature, serializer, suffix, fallbackSuffix)
                    ?.takeIf(isCacheValid)
                    ?.let { return Result.success(it) }
            }
            val apiError = when (e) {
                is AiApiException -> e
                is AiProviderException -> e.toAiApiException()
                else -> AiErrorMapper.fromThrowable(e)
            }
            Result.failure(apiError)
        }
    }

    private fun isUsableTtsCache(audio: AiAudio): Boolean {
        if (audio.audioDataUrl.isNullOrBlank() && audio.audioUrl.isNullOrBlank()) return false
        if (audio.audioUrl.isNullOrBlank()) return true
        val expiresAt = audio.audioUrlExpiresAt ?: return false
        return expiresAt > System.currentTimeMillis()
    }

    private fun isUsableGreetingCache(greeting: AiGreeting): Boolean =
        greeting.audio?.let(::isUsableTtsCache) ?: true

    private suspend fun <T> readCachedWithFallback(
        friendId: String,
        feature: AiCacheFeature,
        serializer: KSerializer<T>,
        suffix: String?,
        fallbackSuffix: String?
    ): T? {
        readCached(friendId, feature, serializer, suffix)?.let { return it }
        if (fallbackSuffix == null || fallbackSuffix == suffix) return null
        return readCached(friendId, feature, serializer, fallbackSuffix)
    }

    private suspend fun <T> readCached(
        friendId: String,
        feature: AiCacheFeature,
        serializer: KSerializer<T>,
        suffix: String?
    ): T? {
        val raw = runCatching { storage.read(friendId, feature, suffix) }.getOrNull() ?: return null
        return runCatching { json.decodeFromJsonElement(serializer, json.parseToJsonElement(raw)) }.getOrNull()
    }

    private fun AiTtsRequest.cacheSuffix(providerTag: String = ""): String {
        // 32 位 hashCode 会碰撞且跨 JVM 不稳定，可能把不同文本/角色的音频串给错误请求；
        // 改用 SHA-256 hex 作缓存键。providerTag 把当前语音服务商与模型并入键：
        // 换服务商/换音色后旧音频不再命中，否则「改了配置还是老声音」。
        // 未配置时 tag 为空且不参与拼接，键形状与旧版一致，老缓存继续可用。
        return buildList {
            add(characterId)
            add(scene?.name.orEmpty())
            add(text)
            if (providerTag.isNotEmpty()) add(providerTag)
        }.joinToString("|").sha256Hex()
    }

    /**
     * 当前 Provider 的缓存标签（baseUrl + 模型）。
     *
     * 只用于区分「换了服务商/模型」之后的缓存失效，不含 Key 明文；
     * 取不到配置（未填写）时返回空串，让缓存退回按内容区分的老行为。
     */
    private suspend fun providerCacheTag(slot: AiFeatureSlot): String = runCatching {
        val config = providerSettings.resolve(slot)
        val base = config.baseUrl.trimEnd('/')
        // resolve 在未配置时会抛，但测试替身/半配置状态可能给出空字段：
        // 这时拼出来的 "@" 毫无区分度，只会让同一份缓存永远失配，直接当无标签处理。
        if (base.isBlank() || config.model.isBlank()) "" else "$base@${config.model}"
    }.getOrDefault("")

    private fun String.sha256Hex(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 本地历史是读改写存储，串行化避免并发追加时互相覆盖。 */
    private val dailyKnowledgeHistoryMutex = Mutex()

    private fun watchedDigest(watched: List<AiWatchedTitleDto>): String =
        jsonForCacheKeys.encodeToString(ListSerializer(AiWatchedTitleDto.serializer()), watched).sha256Hex()

    /**
     * 出题本地缓存键：日期 + 已排除套 + 片单摘要。
     *
     * 带日期是为了让「当天那一套」逐日隔离：同一天内预生成与正式出题共用一键（命中即秒开），
     * 跨天必然失效 —— 否则进程长期驻留时会拿昨天的题当今天的。
     * BYOK 不再有服务端会话概念，会话位固定为 "byok"。
     */
    private fun quizCacheSuffix(
        date: String,
        excludedQuizIds: List<String>,
        watched: List<AiWatchedTitleDto>
    ): String = listOf(
        QUIZ_CACHE_SESSION_ID,
        date,
        excludedQuizIds.sorted().joinToString(","),
        watchedDigest(watched)
    ).joinToString("|").sha256Hex()

    /** 今日知识新缓存键：日期 + locale + watchedDigest，语言之间互不覆盖。 */
    private fun dailyCacheSuffix(locale: String, watched: List<AiWatchedTitleDto>): String = listOf(
        AiDailyKnowledgeContract.CACHE_SCHEMA_VERSION,
        currentCacheDate(),
        locale,
        if (watched.isEmpty()) "" else watchedDigest(watched)
    ).joinToString("|")

    /** 旧版今日知识缓存键，仅用于 zh-CN 升级后的读取兼容。 */
    private fun legacyDailyCacheSuffix(watched: List<AiWatchedTitleDto>): String =
        currentCacheDate() + if (watched.isEmpty()) "" else ":" + watchedDigest(watched)

    // 每日知识按东八区自然日切换缓存，避免 UTC 换日在本地中午/下午提前翻篇
    private fun currentDailyKnowledgeDate(): LocalDate = LocalDate.now(BEIJING_ZONE)

    private fun currentCacheDate(): String = currentDailyKnowledgeDate().toString()

    /** 今日知识插图落盘（编排器把 Provider 返回的字节交给这里，返回 file:// 可展示路径）。 */
    private fun dailyIllustrationSaver(
        day: String
    ): suspend (DailyKnowledgeUnit, ByteArray) -> String? = { _, bytes ->
        runCatching {
            val directory = File(context.cacheDir, DAILY_ILLUSTRATION_DIR)
            if (!directory.exists()) directory.mkdirs()
            val file = File(directory, "$day.png")
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    private suspend fun readDailyKnowledgeHistoryContainer(
        friendId: String
    ): AiDailyKnowledgeHistory? {
        val raw = try {
            storage.read(friendId, AiCacheFeature.DAILY_KNOWLEDGE_HISTORY)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } ?: return AiDailyKnowledgeHistory()

        val history = try {
            json.decodeFromJsonElement(
                AiDailyKnowledgeHistory.serializer(),
                json.parseToJsonElement(raw)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        // 版本不认识的旧数据一律不返回，也不允许后续写入覆盖。
        return history.takeIf {
            it.schemaVersion == AiDailyKnowledgeContract.HISTORY_SCHEMA_VERSION
        }
    }

    private suspend fun updateDailyKnowledgeHistoryRecord(
        friendId: String,
        unitId: String,
        shownDate: LocalDate,
        locale: String,
        transform: (AiDailyKnowledgeHistoryRecord) -> AiDailyKnowledgeHistoryRecord?
    ): AiDailyKnowledgeHistoryRecord? = dailyKnowledgeHistoryMutex.withLock {
        val normalizedFriendId = friendId.trim()
        val normalizedUnitId = unitId.trim()
        val normalizedLocale = locale.trim()
        if (
            normalizedFriendId.isEmpty() ||
            normalizedUnitId.isEmpty() ||
            normalizedLocale !in AiDailyKnowledgeContract.SUPPORTED_LOCALES
        ) {
            return null
        }

        val history = readDailyKnowledgeHistoryContainer(normalizedFriendId) ?: return null
        val index = history.records.indexOfFirst {
            it.matchesDailyHistory(shownDate.toString(), normalizedLocale, normalizedUnitId)
        }
        if (index < 0) return null

        val updated = transform(history.records[index]) ?: return@withLock null
        val records = history.records.toMutableList()
        records[index] = updated
        val pruned = pruneDailyKnowledgeHistory(
            records = records,
            referenceDate = dailyHistoryReferenceDate(records, shownDate)
        )
        return@withLock writeDailyKnowledgeHistory(normalizedFriendId, pruned, updated)
    }

    private suspend fun writeDailyKnowledgeHistory(
        friendId: String,
        records: List<AiDailyKnowledgeHistoryRecord>,
        expectedRecord: AiDailyKnowledgeHistoryRecord
    ): AiDailyKnowledgeHistoryRecord? {
        val history = AiDailyKnowledgeHistory(
            schemaVersion = AiDailyKnowledgeContract.HISTORY_SCHEMA_VERSION,
            records = records
        )
        return try {
            storage.write(
                friendId,
                AiCacheFeature.DAILY_KNOWLEDGE_HISTORY,
                json.encodeToString(AiDailyKnowledgeHistory.serializer(), history)
            )
            expectedRecord
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun dailyHistoryReferenceDate(
        records: List<AiDailyKnowledgeHistoryRecord>,
        shownDate: LocalDate
    ): LocalDate {
        val dates = records.mapNotNull { it.shownDate.toLocalDateOrNull() } + shownDate
        return dates.maxOrNull() ?: shownDate
    }

    private fun pruneDailyKnowledgeHistory(
        records: List<AiDailyKnowledgeHistoryRecord>,
        referenceDate: LocalDate
    ): List<AiDailyKnowledgeHistoryRecord> {
        val cutoff = referenceDate.minusDays(AiDailyKnowledgeContract.HISTORY_RETENTION_DAYS - 1L)
        return records
            .mapNotNull { record -> record.shownDate.toLocalDateOrNull()?.let { record to it } }
            .filter { (_, date) -> !date.isBefore(cutoff) }
            .sortedWith(
                compareByDescending<Pair<AiDailyKnowledgeHistoryRecord, LocalDate>> { it.second.toEpochDay() }
                    .thenByDescending { it.first.sequence }
                    .thenBy { it.first.unitId }
            )
            .take(AiDailyKnowledgeContract.HISTORY_MAX_ENTRIES)
            .map { it.first }
    }

    private fun sortDailyKnowledgeHistoryRecords(
        records: List<AiDailyKnowledgeHistoryRecord>
    ): List<AiDailyKnowledgeHistoryRecord> = records.sortedWith(
        compareByDescending<AiDailyKnowledgeHistoryRecord> {
            it.shownDate.toLocalDateOrNull()?.toEpochDay() ?: Long.MIN_VALUE
        }
            .thenByDescending { it.sequence }
            .thenBy { it.unitId }
    )

    private fun AiDailyKnowledgeHistoryRecord.matchesDailyHistory(
        shownDate: String,
        locale: String,
        unitId: String
    ): Boolean = this.shownDate == shownDate && this.locale == locale && this.unitId == unitId

    private fun AiDailyKnowledge.stableHistoryUnitId(): String? =
        unitId?.trim()?.takeIf { it.isNotEmpty() }
            ?: id.trim().takeIf { it.isNotEmpty() }

    private fun AiDailyKnowledge.historyRelatedMedia(): AiDailyRelatedMedia? =
        relatedMedia ?: relatedMediaTitle?.trim()?.takeIf { it.isNotEmpty() }?.let {
            AiDailyRelatedMedia(title = it)
        }

    private fun String.toLocalDateOrNull(): LocalDate? =
        runCatching { LocalDate.parse(this) }.getOrNull()

    private companion object {
        const val MAX_WATCHED_ITEMS = 60
        val BEIJING_ZONE = ZoneId.of("GMT+8")

        /** 口味推荐缓存结构版本：v2 = TMDB 核验 + 补齐后的成品（旧缓存 key 不含该前缀，不命中） */
        const val TASTE_CACHE_VERSION = "v2"

        /** 口味推荐可展示下限（不足则补齐）与上限（超出截断） */
        const val TASTE_MIN_RECOMMENDATIONS = 3
        const val TASTE_MAX_RECOMMENDATIONS = 6

        /** 补齐时参考的已看最高频类型个数（1-2 个，逐个尝试直到补满） */
        const val TASTE_FALLBACK_GENRES = 2

        /** 兜底证据条数与画像镜像构建上限 */
        const val TASTE_FALLBACK_EVIDENCE_COUNT = 5
        const val DETAIL_PROFILE_MAX_ITEMS = 80

        /** BYOK 出题不再有服务端会话，缓存键会话位固定。 */
        const val QUIZ_CACHE_SESSION_ID = "byok"

        /** quiz 难度反馈存储（QUIZ_RESULT 特性 + 独立后缀，不与成绩历史冲突） */
        const val QUIZ_DIFFICULTY_FEEDBACK_SUFFIX = "difficulty_feedback"
        const val QUIZ_DIFFICULTY_FEEDBACK_CAP = 10
        const val QUIZ_DIFFICULTY_FEEDBACK_WINDOW = 5
        const val QUIZ_DIFFICULTY_HINT_THRESHOLD = 3

        /** 与服务端 readQuizDifficultyHint 相同的两段提示文案 */
        const val QUIZ_DIFFICULTY_HARD_HINT =
            "用户反馈近期题目偏难：本轮以主流影片的主线情节为主，减少冷门细节题，让题目更容易被答对。"
        const val QUIZ_DIFFICULTY_EASY_HINT =
            "用户反馈近期题目偏简单：本轮适当增加需要细看才能记住的情节细节题，保持挑战性。"

        /** 今日知识「最近已出，必须避开」窗口与插图落盘目录 */
        const val DAILY_RECENT_USAGE_WINDOW = 5
        const val DAILY_ILLUSTRATION_DIR = "ai_illustration"

        val jsonForCacheKeys = Json { encodeDefaults = true }

        /**
         * Trakt genre slug（已看列表 genres，如 "drama"）→ TMDB 电影 genre ID。
         * ID 与 DiscoverFilterConstants.MOVIE_GENRES 对齐，保证补齐 reason 的类型名能本地化。
         * Trakt 独有 slug（superhero/holiday 等）TMDB 无对应类型，映射不到即跳过。
         */
        val TRAKT_SLUG_TO_TMDB_MOVIE_GENRE = mapOf(
            "action" to 28,
            "adventure" to 12,
            "animation" to 16,
            "comedy" to 35,
            "crime" to 80,
            "documentary" to 99,
            "drama" to 18,
            "family" to 10751,
            "fantasy" to 14,
            "history" to 36,
            "horror" to 27,
            "music" to 10402,
            "musical" to 10402,
            "mystery" to 9648,
            "romance" to 10749,
            "science-fiction" to 878,
            "sci-fi" to 878,
            "thriller" to 53,
            "war" to 10752,
            "western" to 37
        )

        /** Trakt genre slug → TMDB 剧集 genre ID（TMDB TV 无 horror/romance 等独立类型，映射不到就不拉剧集端） */
        val TRAKT_SLUG_TO_TMDB_TV_GENRE = mapOf(
            "action" to 10759,
            "adventure" to 10759,
            "animation" to 16,
            "comedy" to 35,
            "crime" to 80,
            "documentary" to 99,
            "drama" to 18,
            "family" to 10751,
            "fantasy" to 10765,
            "mystery" to 9648,
            "science-fiction" to 10765,
            "sci-fi" to 10765,
            "war" to 10768,
            "western" to 37
        )
    }
}
