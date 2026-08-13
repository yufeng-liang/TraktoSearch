package com.tracktosearch.data.ai

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
        return when (serverCode.normalized()) {
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
    private val json: Json
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
            suffix = watchedDigest,
            serializer = AiTasteAnalysis.serializer()
        ) {
            val payload = api.getTaste(
                request.copy(
                    watched = watched,
                    forceRefresh = refresh,
                    sessionId = sessionId
                )
            ).requirePayload()
            payload.data.toDomain(payload.quota)
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
            suffix = sessionId,
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
            suffix = currentUtcDate(),
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
            readCached(friendId, feature, serializer, suffix)
                ?.takeIf(isCacheValid)
                ?.let { Result.success(it) }
                ?: Result.failure(if (e is AiApiException) e else AiErrorMapper.fromThrowable(e))
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

    private fun currentUtcDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private companion object {
        const val MAX_WATCHED_ITEMS = 60
        val DEFAULT_SESSION_IDS = setOf("activation", "greeting", "taste", "quiz", "daily", "default")
        val jsonForCacheKeys = Json { encodeDefaults = true }
    }
}

private data class AiResponsePayload<T>(
    val data: T,
    val quota: AiQuotaDto?
)

private fun <T> Response<AiApiResponse<T>>.requirePayload(): AiResponsePayload<T> {
    val envelope = body()
    val code = envelope?.code
    if (!isSuccessful || code == null || code.uppercase(Locale.ROOT) !in AI_SUCCESS_CODES) {
        throw AiErrorMapper.exception(
            serverCode = code ?: "HTTP_${code()}",
            message = envelope?.message?.takeIf { it.isNotBlank() } ?: "HTTP ${code()}",
            httpCode = code()
        )
    }
    return AiResponsePayload(
        data = envelope.data ?: throw AiErrorMapper.exception("EMPTY_RESPONSE", "AI response data is empty", code()),
        quota = envelope.quota
    )
}
