package com.tracktosearch.data.ai

import android.content.Context
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbMultiSearchResult
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.ui.screen.discoverfilter.DiscoverFilterConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import retrofit2.Response

private val AI_SUCCESS_CODES = setOf("SUCCESS", "OK", "200")

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
            "INVALID_REQUEST", "VALIDATION_ERROR" -> AiErrorCode.INVALID_REQUEST
            "EMPTY_RESPONSE", "INVALID_RESPONSE" -> AiErrorCode.INVALID_RESPONSE
            else -> when {
                httpCode == 401 || httpCode == 403 -> AiErrorCode.UNAUTHORIZED
                httpCode == 429 -> AiErrorCode.RATE_LIMITED
                httpCode >= 500 -> AiErrorCode.SERVER
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
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.QUIZ,
            forceRefresh = refresh,
            // 缓存 key 带会话 ID：每次进入精灵中心会话都拿新题包，避免重做上一局"开卷考"
            suffix = quizCacheSuffix(sessionId, request.excludedQuizIds, watched),
            serializer = AiQuiz.serializer()
        ) {
            val payload = api.getQuiz(
                request.copy(watched = watched, sessionId = sessionId)
            ).requirePayload()
            val quiz = payload.data.toDomainOrNull(payload.quota)
            if (quiz == null || !quiz.isThirteenQuestionStructure) {
                throw AiErrorMapper.exception("INVALID_RESPONSE", "Quiz must contain 13 valid questions", 200)
            }
            quiz
        }
    }

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

    suspend fun getDailyKnowledge(
        friendId: String,
        forceRefresh: Boolean = false
    ): Result<AiDailyKnowledge> {
        val sessionId = sessionIdFor(friendId)
        return cachedRequest(
            friendId = friendId,
            feature = AiCacheFeature.DAILY_KNOWLEDGE,
            forceRefresh = forceRefresh,
            suffix = currentCacheDate(),
            serializer = AiDailyKnowledge.serializer()
        ) {
            val payload = api.getDailyKnowledge(
                AiDailyRequest(
                    sessionId = sessionId,
                    forceRefresh = forceRefresh
                )
            ).requirePayload()
            payload.data.toDomain(payload.quota)
        }
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

    suspend fun clearActivatedCharacterId(friendId: String) {
        runCatching { storage.remove(friendId, AiCacheFeature.ACTIVATION) }
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
        isCacheValid: (T) -> Boolean = { true },
        block: suspend () -> T
    ): Result<T> {
        if (friendId.trim().isEmpty()) {
            return Result.failure(AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400))
        }
        if (!forceRefresh) {
            readCached(friendId, feature, serializer, suffix)
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
                readCached(friendId, feature, serializer, suffix)
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

    private fun quizCacheSuffix(
        sessionId: String,
        excludedQuizIds: List<String>,
        watched: List<AiWatchedTitleDto>
    ): String = listOf(
        sessionId,
        excludedQuizIds.sorted().joinToString(","),
        watchedDigest(watched)
    ).joinToString("|").sha256Hex()

    // 每日冷知识按东八区自然日切换缓存，避免 UTC 换日在本地中午/下午提前翻篇
    private fun currentCacheDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT+8")
    }.format(Date())

    private companion object {
        const val MAX_WATCHED_ITEMS = 60

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

private fun <T> Response<AiApiResponse<T>>.requirePayload(): AiResponsePayload<T> {
    val envelope = body()
    val errorEnvelope = envelope ?: errorBody()?.string()?.let { raw ->
        runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(AiErrorDto.serializer(), raw)
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
