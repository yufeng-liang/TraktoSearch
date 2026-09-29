package com.tracktosearch.data.ai

import android.content.Context
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbMultiSearchResult
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.ui.screen.discoverfilter.DiscoverFilterConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import retrofit2.Response

private val AI_SUCCESS_CODES = setOf("SUCCESS", "OK", "200")

// 服务端响应里可能带新字段：这里的解码统一走宽松 Json，避免每次调用都新建实例（JSON_FORMAT_REDUNDANT）
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
    UNKNOWN
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

@Singleton
class AiRepository @Inject constructor(
    private val api: AiApiService,
    private val storage: AiStorage,
    private val json: Json,
    private val tmdbRepository: TmdbRepository,
    // 补齐推荐的 reason 模板文案需要按当前语言生成后随缓存一起落盘
    @ApplicationContext private val context: Context
) {

    suspend fun listCharacters(): Result<List<AiCharacter>> = runApi {
        api.listCharacters().requirePayload().data.characters.map { it.toDomain() }
    }

    suspend fun getProfileSettings(friendId: String): Result<AiProfileSettingsDto> = runForFriend(friendId) {
        api.getProfileSettings().requirePayload().data
    }

    suspend fun updateProfileSettings(
        friendId: String,
        request: AiProfileSettingsRequest
    ): Result<AiProfileSettingsDto> = runForFriend(friendId) {
        api.updateProfileSettings(request).requirePayload().data
    }

    suspend fun syncProfile(
        friendId: String,
        batch: AiProfileSyncBatch
    ): Result<AiProfileSyncResultDto> = runForFriend(friendId) {
        api.syncProfile(batch).requirePayload().data
    }

    suspend fun activate(
        friendId: String,
        characterId: String,
        spokenName: String
    ): Result<AiActivation> = activate(
        friendId,
        AiActivateRequest(characterId = characterId, spokenName = spokenName)
    )

    suspend fun activate(
        friendId: String,
        request: AiActivateRequest
    ): Result<AiActivation> = runForFriend(friendId) {
        val normalized = AiActivationNormalizer.normalize(request.spokenName)
        if (request.audioDataUrl.isNullOrBlank() && normalized.isBlank()) {
            throw AiErrorMapper.exception("INVALID_REQUEST", "Activation name must not be blank", 400)
        }
        val requestWithSession = request.copy(
            spokenName = normalized,
            sessionId = sessionIdFor(friendId, request.sessionId)
        )
        val payload = api.activate(requestWithSession).requirePayload()
        payload.data.toDomain(payload.quota)
    }

    suspend fun getGreeting(
        friendId: String,
        characterId: String,
        forceRefresh: Boolean = false
    ): Result<AiGreeting> {
        val sessionId = sessionIdFor(friendId)
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.GREETING,
            forceRefresh = forceRefresh,
            // 问候按角色区分缓存：缺 characterId 时第二个角色的问候会命中第一个角色的缓存
            suffix = characterId,
            serializer = AiGreeting.serializer(),
            isCacheValid = ::isUsableGreetingCache,
        ) {
            val payload = api.getGreeting(
                AiGreetingRequest(
                    characterId = characterId,
                    includeAudio = true,
                    forceRefresh = forceRefresh,
                    sessionId = sessionId
                )
            ).requirePayload()
            payload.data.toDomain(payload.quota)
        }
    }

    suspend fun getTaste(
        friendId: String,
        request: AiTasteRequest,
        forceRefresh: Boolean = false
    ): Result<AiTasteAnalysis> {
        val watched = request.watched.take(MAX_WATCHED_ITEMS)
        val refresh = forceRefresh || request.forceRefresh
        val sessionId = sessionIdFor(friendId, request.sessionId)
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
            val payload = api.getTaste(
                request.copy(
                    watched = watched,
                    forceRefresh = refresh,
                    sessionId = sessionId
                )
            ).requirePayload()
            verifyAndFillTasteRecommendations(payload.data.toDomain(payload.quota), watched)
        }
    }

    // ========== 口味推荐 TMDB 核验与补齐 ==========

    /**
     * 对服务端口味推荐做 TMDB 核验：
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
        request: AiQuizRequest = AiQuizRequest(),
        forceRefresh: Boolean = false
    ): Result<AiQuiz> {
        val watched = request.watched.take(MAX_WATCHED_ITEMS)
        val refresh = forceRefresh
        val sessionId = sessionIdFor(friendId, request.sessionId)
        val date = request.date ?: currentCacheDate()
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.QUIZ,
            forceRefresh = refresh,
            // 缓存 key 带会话 ID：每次进入精灵中心会话都拿新题包，避免重做上一局"开卷考"
            suffix = quizCacheSuffix(sessionId, date, request.excludedQuizIds, watched),
            serializer = AiQuiz.serializer()
        ) {
            val payload = api.getQuiz(
                request.copy(watched = watched, sessionId = sessionId, date = date)
            ).requirePayload()
            val quiz = payload.data.toDomainOrNull(payload.quota)
            if (quiz == null || !quiz.isThirteenQuestionStructure) {
                throw AiErrorMapper.exception("INVALID_RESPONSE", "Quiz must contain 13 valid questions", 200)
            }
            quiz
        }
    }

    /**
     * 流式出题（POST /api/ai/quiz/stream，NDJSON 逐行事件）。
     *
     * 两阶段实测 280~420s，单次请求会被 OkHttp 90s 读超时掐断；流式下服务端每 10s 至少写
     * 一行（真实进度或心跳），因此连接可以一直保持，等待页也能显示真实阶段。
     * 命中本地缓存时不打网络，直接以 [AiQuizStreamEvent.Completed] 收尾。
     *
     * request.prefetch=true 是静默预生成：服务端不扣额度、不计已玩套数，只为把当天这一套
     * 提前备好。预生成与正式出题共用同一份请求参数（日期/套序号/片单都一样），所以正式请求
     * 必然命中服务端题库、秒回；预生成本身不写本地镜像（见下方 write 处的原因）。
     */
    fun getQuizStream(
        friendId: String,
        request: AiQuizRequest = AiQuizRequest(),
        forceRefresh: Boolean = false
    ): Flow<AiQuizStreamEvent> = flow {
        if (friendId.trim().isEmpty()) {
            throw AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400)
        }
        val watched = request.watched.take(MAX_WATCHED_ITEMS)
        val sessionId = sessionIdFor(friendId, request.sessionId)
        val date = request.date ?: currentCacheDate()
        val suffix = quizCacheSuffix(sessionId, date, request.excludedQuizIds, watched)
        if (!forceRefresh) {
            readCachedWithFallback(friendId, AiCacheFeature.QUIZ, AiQuiz.serializer(), suffix, null)
                ?.takeIf { it.isThirteenQuestionStructure }
                ?.let {
                    emit(AiQuizStreamEvent.Completed(it))
                    return@flow
                }
        }
        val response = api.getQuizStream(
            request.copy(watched = watched, sessionId = sessionId, date = date)
        )
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw AiErrorMapper.exception(
                serverCode = runCatching {
                    lenientJson
                        .decodeFromString(AiErrorDto.serializer(), response.errorBody()?.string().orEmpty())
                        .code
                }.getOrNull() ?: "HTTP_${response.code()}",
                message = "Quiz stream failed with HTTP ${response.code()}",
                httpCode = response.code()
            )
        }
        var completed: AiQuiz? = null
        var dailySetsDone = false
        body.use { responseBody ->
            val source = responseBody.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = parseQuizStreamEvent(line) ?: continue
                when (event) {
                    is AiQuizStreamEvent.Completed -> completed = event.quiz
                    AiQuizStreamEvent.DailySetsDone -> dailySetsDone = true
                    else -> Unit
                }
                emit(event)
            }
        }
        // 拒发预生成是完整答复（当天该发的套都发过了），不是「流被截断」：此时没有题包是正常的。
        // 漏了这一步，拒发事件会先被收下、再被下面这个兜底异常推翻，界面又回到「没备好」。
        if (completed == null && !dailySetsDone) {
            throw AiErrorMapper.exception("EMPTY_RESPONSE", "Quiz stream ended without a result", 200)
        }
        val quiz = completed
        // 预生成不写本地镜像：镜像一旦存在，正式出题就会被本地缓存直接满足、不再发请求，
        // 而服务端要靠那次请求把「当天这一套」记为已玩（quizbank:used 的 usedSets）——
        // 漏记会让下一套题的序号原地不动，又抽回同一套题。预生成只负责把服务端题库暖热。
        if (quiz != null && !request.prefetch) {
            storage.write(friendId, AiCacheFeature.QUIZ, json.encodeToString(AiQuiz.serializer(), quiz), suffix)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun submitQuiz(
        friendId: String,
        request: AiSubmitQuizRequest
    ): Result<AiQuizResult> = runForFriend(friendId) {
        val sessionId = sessionIdFor(friendId, request.sessionId)
        val payload = api.submitQuiz(request.copy(sessionId = sessionId)).requirePayload()
        payload.data.toDomain(payload.quota)
    }

    suspend fun submitQuiz(
        friendId: String,
        quizId: String,
        answers: List<AiQuizAnswer>
    ): Result<AiQuizResult> = submitQuiz(
        friendId,
        AiSubmitQuizRequest(
            quizId = quizId,
            answers = answers.map { AiQuizAnswerDto(it.questionId, it.selectedOptionIds, it.textAnswer) },
            sessionId = sessionIdFor(friendId)
        )
    )

    /**
     * 出分页难度反馈（POST /api/ai/quiz/feedback）。
     *
     * 一次性调用，不走 cachedRequest；失败完全静默——反馈是锦上添花，
     * 网络/解析/服务端任何异常都按成功处理，绝不打扰用户。
     * 鉴权由 AiApiService 的 AuthInterceptor 统一注入，与其他 /api/ai 系列接口一致。
     */
    suspend fun submitQuizDifficulty(
        quizId: String,
        difficulty: AiQuizDifficulty
    ): Result<Unit> = try {
        api.submitQuizFeedback(
            AiQuizFeedbackRequest(quizId = quizId, difficulty = difficulty.wireValue)
        ).requirePayload()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 吞掉一切失败：反馈发不出去也不影响用户离开出分页
        Result.success(Unit)
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
        val sessionId = sessionIdFor(friendId)
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
            val payload = api.getDailyKnowledge(
                AiDailyRequest(
                    sessionId = sessionId,
                    forceRefresh = forceRefresh,
                    watched = watched.take(MAX_WATCHED_ITEMS),
                    locale = normalizedLocale
                )
            ).requirePayload()
            payload.data.toDomain(payload.quota)
        }
    }

    /**
     * 概念插图静默刷新：绕过本地缓存读，直接向 Worker 拿同一天的缓存响应。
     * 服务端每次响应都按 D1 状态重新装配插图并新签短期 URL，所以这里必须走网络，
     * 但 forceRefresh=false 保证不会生成新内容、不消耗换题额度外的配额。
     */
    suspend fun refreshDailyKnowledge(
        friendId: String,
        watched: List<AiWatchedTitleDto> = emptyList(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): Result<AiDailyKnowledge> {
        val normalizedLocale = locale.trim()
        if (normalizedLocale !in AiDailyKnowledgeContract.SUPPORTED_LOCALES) {
            return Result.failure(
                AiErrorMapper.exception("INVALID_REQUEST", "Unsupported daily knowledge locale: $normalizedLocale", 400)
            )
        }
        if (friendId.trim().isEmpty()) {
            return Result.failure(AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400))
        }
        return try {
            val payload = api.getDailyKnowledge(
                AiDailyRequest(
                    sessionId = sessionIdFor(friendId),
                    forceRefresh = false,
                    watched = watched.take(MAX_WATCHED_ITEMS),
                    locale = normalizedLocale
                )
            ).requirePayload()
            val knowledge = payload.data.toDomain(payload.quota)
            runCatching {
                storage.write(
                    friendId,
                    AiCacheFeature.DAILY_KNOWLEDGE,
                    json.encodeToString(AiDailyKnowledge.serializer(), knowledge),
                    dailyCacheSuffix(normalizedLocale, watched.take(MAX_WATCHED_ITEMS))
                )
            }
            Result.success(knowledge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(if (e is AiApiException) e else AiErrorMapper.fromThrowable(e))
        }
    }

    /**
     * 流式今日知识：服务端按 candidate → review 两段推送 NDJSON 事件（阶段/进度/心跳/结果）。
     *
     * 与出题流式同一套协议与读取方式：@Streaming + 逐行读，边读边 emit，
     * 让加载页显示真实阶段与生成量而不是干转圈；命中本地当天缓存时直接 emit Completed（秒开，不打网络）。
     * 服务端生成失败会给确定性兜底内容（同样是 result 事件），所以客户端不会出现空白卡。
     */
    fun getDailyStream(
        friendId: String,
        forceRefresh: Boolean = false,
        watched: List<AiWatchedTitleDto> = emptyList(),
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE
    ): Flow<AiDailyStreamEvent> = flow {
        if (friendId.trim().isEmpty()) {
            throw AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400)
        }
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
        // zh-CN 是旧缓存的真实语言：新键未命中时读旧日期键，避免升级当天强制重新请求。
        val fallbackSuffix = if (normalizedLocale == AiDailyKnowledgeContract.DEFAULT_LOCALE) {
            legacyDailyCacheSuffix(trimmedWatched)
        } else {
            null
        }
        if (!forceRefresh) {
            readCachedWithFallback(
                friendId,
                AiCacheFeature.DAILY_KNOWLEDGE,
                AiDailyKnowledge.serializer(),
                suffix,
                fallbackSuffix
            )?.let {
                emit(AiDailyStreamEvent.Completed(it))
                return@flow
            }
        }
        val response = api.getDailyKnowledgeStream(
            AiDailyRequest(
                sessionId = sessionIdFor(friendId),
                forceRefresh = forceRefresh,
                watched = trimmedWatched,
                locale = normalizedLocale
            )
        )
        val body = response.body()
        if (!response.isSuccessful || body == null) {
            throw AiErrorMapper.exception(
                serverCode = runCatching {
                    lenientJson
                        .decodeFromString(AiErrorDto.serializer(), response.errorBody()?.string().orEmpty())
                        .code
                }.getOrNull() ?: "HTTP_${response.code()}",
                message = "Daily stream failed with HTTP ${response.code()}",
                httpCode = response.code()
            )
        }
        var completed: AiDailyKnowledge? = null
        body.use { responseBody ->
            val source = responseBody.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = parseDailyStreamEvent(line) ?: continue
                if (event is AiDailyStreamEvent.Completed) completed = event.daily
                emit(event)
            }
        }
        val daily = completed
            ?: throw AiErrorMapper.exception("EMPTY_RESPONSE", "Daily stream ended without a result", 200)
        runCatching {
            storage.write(
                friendId,
                AiCacheFeature.DAILY_KNOWLEDGE,
                json.encodeToString(AiDailyKnowledge.serializer(), daily),
                suffix
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 记录一条真正展示过的今日知识。
     *
     * 只有 UI 确认内容已经渲染后才调用；这里不把“网络请求成功”偷换成“用户看过”。
     * 同一 locale、同一天、同一学习单元重复展示时首条记录生效，不覆盖正文和小题，保证当天回看稳定。
     */
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
     * 激活态只放在内存里的话，杀进程或被系统回收后就没了，用户得重新喊一次名字、
     * 再花一次配额和一次 TTS；详情页那种没有激活入口的页面则永远拿不到激活态。
     */
    suspend fun readActivatedCharacterId(friendId: String): String? = runCatching {
        storage.read(friendId, AiCacheFeature.ACTIVATION)?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    suspend fun saveActivatedCharacterId(friendId: String, characterId: String) {
        val normalized = characterId.trim()
        if (normalized.isEmpty()) return
        runCatching { storage.write(friendId, AiCacheFeature.ACTIVATION, normalized) }
    }

    /**
     * 读取当日额度用量快照（dailyUsed），按 UTC 日隔离。
     * 服务端没有独立额度查询接口，重启后恢复激活态时靠它把「今日 x/80」显示出来。
     */
    suspend fun readQuotaSnapshotDailyUsed(friendId: String): Int? = runCatching {
        val raw = storage.read(friendId, AiCacheFeature.QUOTA_SNAPSHOT, quotaSnapshotDay())
            ?: return@runCatching null
        lenientJson.decodeFromString<AiQuotaSnapshotDto>(raw).dailyUsed
    }.getOrNull()

    /** 把最近一次响应里的额度用量落到当日快照，供重启后恢复显示。 */
    suspend fun saveQuotaSnapshot(friendId: String, dailyUsed: Int) {
        runCatching {
            storage.write(
                friendId,
                AiCacheFeature.QUOTA_SNAPSHOT,
                Json.encodeToString(AiQuotaSnapshotDto(dailyUsed)),
                quotaSnapshotDay()
            )
        }
    }

    /** 快照按 UTC 日分键：与网关 usage_day（同样取 UTC 日历日）的复位边界一致，跨日自然不命中。 */
    private fun quotaSnapshotDay(): String = LocalDate.now(ZoneOffset.UTC).toString()

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
        val sessionId = sessionIdFor(friendId, request.sessionId)
        return cachedRequest(
        friendId = friendId,
        feature = AiCacheFeature.TTS,
        forceRefresh = false,
        suffix = request.cacheSuffix(),
        serializer = AiAudio.serializer(),
        isCacheValid = ::isUsableTtsCache,
    ) {
            val payload = api.playTts(request.copy(sessionId = sessionId)).requirePayload()
            payload.data.toDomain(payload.quota)
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
        val payload = api.playTts(request).requirePayload()
        payload.data.toDomain(payload.quota)
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
            serializer = AiDetailAnalysisDto.serializer()
        ) {
            val payload = api.analyzeDetail(
                request.copy(
                    forceRefresh = forceRefresh,
                    sessionId = sessionIdFor(friendId, request.sessionId)
                )
            ).requirePayload()
            payload.data.copy(quota = payload.quota)
        }.map { it.toDomain() }
    }

    /** 相关推荐排序：候选只来自客户端已加载的推荐列表，服务端不引入新片，因此不做缓存。 */
    suspend fun rankDetailRecommendations(
        friendId: String,
        request: AiRecommendationsRankRequest
    ): Result<List<AiDetailRecommendation>> = runForFriend(friendId) {
        val payload = api.rankRecommendations(
            request.copy(sessionId = sessionIdFor(friendId, request.sessionId))
        ).requirePayload()
        payload.data.toDomain(payload.quota)
    }

    private suspend fun <T> runForFriend(friendId: String, block: suspend () -> T): Result<T> {
        if (friendId.trim().isEmpty()) {
            return Result.failure(AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400))
        }
        return runApi(block)
    }

    private suspend fun <T> runApi(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiApiException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(AiErrorMapper.fromThrowable(e))
        } catch (e: kotlinx.serialization.SerializationException) {
            // 响应结构对不上 DTO：归为响应异常而非裸抛，给用户"内容异常"而非兜底文案
            Result.failure(AiErrorMapper.exception("INVALID_RESPONSE", e.message ?: "INVALID_RESPONSE", 200))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
            storage.write(friendId, feature, json.encodeToString(serializer, value), suffix)
            Result.success(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!forceRefresh) {
                readCachedWithFallback(friendId, feature, serializer, suffix, fallbackSuffix)
                    ?.takeIf(isCacheValid)
                    ?.let { return Result.success(it) }
            }
            Result.failure(if (e is AiApiException) e else AiErrorMapper.fromThrowable(e))
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

    private fun AiTtsRequest.cacheSuffix(): String {
        // 32 位 hashCode 会碰撞且跨 JVM 不稳定，可能把不同文本/角色的音频串给错误请求；
        // 改用 SHA-256 hex 作缓存键。
        return listOf(characterId, scene?.name.orEmpty(), text).joinToString("|").sha256Hex()
    }

    private fun String.sha256Hex(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private val spriteSessions = ConcurrentHashMap<String, String>()

    /** 本地历史是读改写存储，串行化避免并发追加时互相覆盖。 */
    private val dailyKnowledgeHistoryMutex = Mutex()

    private fun sessionIdFor(friendId: String, requestedSessionId: String? = null): String {
        val requested = requestedSessionId?.trim()
        if (!requested.isNullOrBlank() && requested !in DEFAULT_SESSION_IDS) {
            spriteSessions[friendId] = requested
            return requested
        }
        return spriteSessions[friendId] ?: "sprite-${UUID.randomUUID()}".also { generated ->
            spriteSessions[friendId] = generated
        }
    }

    private fun watchedDigest(watched: List<AiWatchedTitleDto>): String =
        jsonForCacheKeys.encodeToString(ListSerializer(AiWatchedTitleDto.serializer()), watched).sha256Hex()

    /**
     * 出题本地缓存键：会话 + 日期 + 已排除套 + 片单摘要。
     *
     * 带日期是为了让「当天那一套」逐日隔离：同一天内预生成与正式出题共用一键（命中即秒开），
     * 跨天必然失效 —— 否则进程长期驻留时会拿昨天的题当今天的。
     */
    private fun quizCacheSuffix(
        sessionId: String,
        date: String,
        excludedQuizIds: List<String>,
        watched: List<AiWatchedTitleDto>
    ): String = listOf(
        sessionId,
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

        val DEFAULT_SESSION_IDS = setOf("activation", "greeting", "taste", "quiz", "daily", "default")
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

private data class AiResponsePayload<T>(
    val data: T,
    val quota: AiQuotaDto?
)

/**
 * 解析 /api/ai/quiz/stream 的一行 NDJSON。
 * 结构不符的行返回 null（跳过），服务端 error 事件折算成统一的 AI 异常。
 */
private fun parseQuizStreamEvent(line: String): AiQuizStreamEvent? {
    val root = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
    val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (type) {
        "stage" -> {
            val stage = quizStageOf(root["stage"]?.jsonPrimitive?.contentOrNull) ?: return null
            val status = when (root["status"]?.jsonPrimitive?.contentOrNull) {
                "start" -> AiQuizStageStatus.START
                "done" -> AiQuizStageStatus.DONE
                else -> return null
            }
            AiQuizStreamEvent.Stage(
                stage = stage,
                status = status,
                expectedChars = root["expectedChars"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }
        "progress" -> {
            val stage = quizStageOf(root["stage"]?.jsonPrimitive?.contentOrNull) ?: return null
            AiQuizStreamEvent.Progress(stage, root["chars"]?.jsonPrimitive?.intOrNull ?: 0)
        }
        "ping" -> AiQuizStreamEvent.Ping
        "result" -> {
            val quizElement = root["quiz"] ?: return null
            val dto = runCatching {
                lenientJson.decodeFromJsonElement(AiQuizDto.serializer(), quizElement)
            }.getOrNull() ?: return null
            val quiz = dto.toDomainOrNull(root["quota"]?.let { quota ->
                runCatching {
                    lenientJson.decodeFromJsonElement(AiQuotaDto.serializer(), quota)
                }.getOrNull()
            }) ?: throw AiErrorMapper.exception("INVALID_RESPONSE", "Quiz stream result is invalid", 200)
            AiQuizStreamEvent.Completed(quiz)
        }
        "error" -> {
            // 「当天该预生成的套都发过了」是常态而非故障：正式请求仍会按需生成，
            // 交给 ViewModel 换一句实话（今天的题已玩完），其余错误码照旧抛出去。
            val code = root["code"]?.jsonPrimitive?.contentOrNull
            if (code == QUIZ_PREWARM_DAILY_SETS_DONE) {
                AiQuizStreamEvent.DailySetsDone
            } else {
                throw AiErrorMapper.exception(
                    serverCode = code,
                    message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Quiz stream failed",
                    httpCode = 200
                )
            }
        }
        else -> null
    }
}

private fun quizStageOf(raw: String?): AiQuizStage? = when (raw) {
    "units" -> AiQuizStage.UNITS
    "review" -> AiQuizStage.REVIEW
    else -> null
}

/**
 * 解析 /api/ai/daily/stream 的一行 NDJSON。
 * 结构不符的行返回 null（跳过），服务端 error 事件折算成统一的 AI 异常。
 */
private fun parseDailyStreamEvent(line: String): AiDailyStreamEvent? {
    val root = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
    val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
    return when (type) {
        "stage" -> {
            val stage = dailyStageOf(root["stage"]?.jsonPrimitive?.contentOrNull) ?: return null
            val status = when (root["status"]?.jsonPrimitive?.contentOrNull) {
                "start" -> AiDailyStageStatus.START
                "done" -> AiDailyStageStatus.DONE
                else -> return null
            }
            AiDailyStreamEvent.Stage(
                stage = stage,
                status = status,
                expectedChars = root["expectedChars"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }
        "progress" -> {
            val stage = dailyStageOf(root["stage"]?.jsonPrimitive?.contentOrNull) ?: return null
            AiDailyStreamEvent.Progress(stage, root["chars"]?.jsonPrimitive?.intOrNull ?: 0)
        }
        "ping" -> AiDailyStreamEvent.Ping
        "result" -> {
            val dailyElement = root["daily"] ?: return null
            val dto = runCatching {
                lenientJson
                    .decodeFromJsonElement(AiDailyKnowledgeDto.serializer(), dailyElement)
            }.getOrNull() ?: return null
            val quota = root["quota"]?.let {
                runCatching {
                    lenientJson
                        .decodeFromJsonElement(AiQuotaDto.serializer(), it)
                }.getOrNull()
            }
            AiDailyStreamEvent.Completed(dto.toDomain(quota))
        }
        "error" -> throw AiErrorMapper.exception(
            serverCode = root["code"]?.jsonPrimitive?.contentOrNull,
            message = root["message"]?.jsonPrimitive?.contentOrNull ?: "Daily stream failed",
            httpCode = 200
        )
        else -> null
    }
}

private fun dailyStageOf(raw: String?): AiDailyStage? = when (raw) {
    "candidate" -> AiDailyStage.CANDIDATE
    "review" -> AiDailyStage.REVIEW
    else -> null
}

private fun <T> Response<AiApiResponse<T>>.requirePayload(): AiResponsePayload<T> {
    val envelope = body()
    val errorEnvelope = envelope ?: errorBody()?.string()?.let { raw ->
        runCatching {
            lenientJson.decodeFromString(AiErrorDto.serializer(), raw)
        }.getOrNull()
    }
    val code = envelope?.code ?: (errorEnvelope as? AiErrorDto)?.code
    if (!isSuccessful || code == null || code.uppercase(Locale.ROOT) !in AI_SUCCESS_CODES) {
        throw AiErrorMapper.exception(
            serverCode = code ?: "HTTP_${code()}",
            message = (envelope?.message ?: (errorEnvelope as? AiErrorDto)?.message)
                ?.takeIf { it.isNotBlank() } ?: "HTTP ${code()}",
            httpCode = code()
        )
    }
    val successEnvelope = envelope
        ?: throw AiErrorMapper.exception("EMPTY_RESPONSE", "AI response data is empty", code())
    return AiResponsePayload(
        data = successEnvelope.data
            ?: throw AiErrorMapper.exception("EMPTY_RESPONSE", "AI response data is empty", code()),
        quota = successEnvelope.quota
    )
}
