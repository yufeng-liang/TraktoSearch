package com.tracktosearch.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
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
        api.listCharacters().requireData().characters.map { it.toDomain() }
    }

    suspend fun recognizeActivation(
        friendId: String,
        request: AiAsrRequest
    ): Result<AiActivationRecognition> = runForFriend(friendId) {
        api.recognizeActivation(request).requireData().toDomain()
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
        api.activate(request.copy(spokenName = normalized)).requireData().toDomain()
    }

    suspend fun getGreeting(
        friendId: String,
        characterId: String,
        forceRefresh: Boolean = false
    ): Result<AiGreeting> = cachedRequest(
        friendId = friendId,
        feature = AiCacheFeature.GREETING,
        forceRefresh = forceRefresh,
        // 问候按角色区分缓存：缺 characterId 时第二个角色的问候会命中第一个角色的缓存
        suffix = characterId,
        serializer = AiGreeting.serializer()
    ) {
        api.getGreeting(
            AiGreetingRequest(
                characterId = characterId,
                includeAudio = true,
                forceRefresh = forceRefresh,
                sessionId = "greeting"
            )
        ).requireData().toDomain()
    }

    suspend fun getTaste(
        friendId: String,
        request: AiTasteRequest,
        forceRefresh: Boolean = false
    ): Result<AiTasteAnalysis> = cachedRequest(
        friendId = friendId,
        feature = AiCacheFeature.TASTE,
        forceRefresh = forceRefresh,
        serializer = AiTasteAnalysis.serializer()
    ) {
        api.getTaste(request.copy(watched = request.watched.take(MAX_WATCHED_ITEMS)))
            .requireData()
            .toDomain()
    }

    suspend fun getQuiz(
        friendId: String,
        request: AiQuizRequest = AiQuizRequest(),
        forceRefresh: Boolean = false
    ): Result<AiQuiz> = cachedRequest(
        friendId = friendId,
        feature = AiCacheFeature.QUIZ,
        forceRefresh = forceRefresh,
        serializer = AiQuiz.serializer()
    ) {
        val quiz = api.getQuiz(request.copy(watched = request.watched.take(MAX_WATCHED_ITEMS)))
            .requireData()
            .toDomainOrNull()
        if (quiz == null || !quiz.isThirteenQuestionStructure) {
            throw AiErrorMapper.exception("INVALID_RESPONSE", "Quiz must contain 13 valid questions", 200)
        }
        quiz
    }

    suspend fun submitQuiz(
        friendId: String,
        request: AiSubmitQuizRequest
    ): Result<AiQuizResult> = runForFriend(friendId) {
        api.submitQuiz(request).requireData().toDomain()
    }

    suspend fun submitQuiz(
        friendId: String,
        quizId: String,
        answers: List<AiQuizAnswer>
    ): Result<AiQuizResult> = submitQuiz(
        friendId,
        AiSubmitQuizRequest(
            quizId = quizId,
            answers = answers.map { AiQuizAnswerDto(it.questionId, it.selectedOptionIds, it.textAnswer) }
        )
    )

    suspend fun getDailyKnowledge(
        friendId: String,
        forceRefresh: Boolean = false
    ): Result<AiDailyKnowledge> = cachedRequest(
        friendId = friendId,
        feature = AiCacheFeature.DAILY_KNOWLEDGE,
        forceRefresh = forceRefresh,
        serializer = AiDailyKnowledge.serializer()
    ) {
        api.getDailyKnowledge().requireData().toDomain()
    }

    suspend fun playTts(
        friendId: String,
        request: AiTtsRequest
    ): Result<AiAudio> = cachedRequest(
        friendId = friendId,
        feature = AiCacheFeature.TTS,
        forceRefresh = false,
        suffix = request.cacheSuffix(),
        serializer = AiAudio.serializer()
    ) {
        api.playTts(request).requireData().toDomain()
    }

    suspend fun playTts(
        friendId: String,
        characterId: String,
        text: String,
        style: String? = null
    ): Result<AiAudio> = playTts(friendId, AiTtsRequest(characterId, text, style))

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
        block: suspend () -> T
    ): Result<T> {
        if (friendId.trim().isEmpty()) {
            return Result.failure(AiErrorMapper.exception("INVALID_REQUEST", "friendId must not be blank", 400))
        }
        if (!forceRefresh) {
            readCached(friendId, feature, serializer, suffix)?.let { return Result.success(it) }
        }
        return try {
            val value = block()
            storage.write(friendId, feature, json.encodeToString(serializer, value), suffix)
            Result.success(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readCached(friendId, feature, serializer, suffix)?.let { Result.success(it) }
                ?: Result.failure(if (e is AiApiException) e else AiErrorMapper.fromThrowable(e))
        }
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
        return listOf(characterId, style.orEmpty(), text).joinToString("|").sha256Hex()
    }

    private fun String.sha256Hex(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_WATCHED_ITEMS = 60
    }
}

private fun <T> Response<AiApiResponse<T>>.requireData(): T {
    val envelope = body()
    val code = envelope?.code
    if (!isSuccessful || code == null || code.uppercase(Locale.ROOT) !in AI_SUCCESS_CODES) {
        throw AiErrorMapper.exception(
            serverCode = code ?: "HTTP_${code()}",
            message = envelope?.message?.takeIf { it.isNotBlank() } ?: "HTTP ${code()}",
            httpCode = code()
        )
    }
    return envelope.data ?: throw AiErrorMapper.exception("EMPTY_RESPONSE", "AI response data is empty", code())
}
