package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import retrofit2.Response
import java.security.MessageDigest

class AiRepositoryTest {

    @Test
    fun activationNameNormalizer_acceptsTraditionalAndPinyinLikeInput() {
        val character = AiCharacterCatalog.all.single { it.id == "usagi" }

        assertThat(AiActivationNormalizer.normalize(" 烏薩奇！ ")).isEqualTo("乌萨奇")
        assertThat(AiActivationNormalizer.matches(character, "wusaqi")).isTrue()
    }

    @Test
    fun storageKey_containsSchemaAndSeparatesFriends() {
        val first = AiStorageKey.forFriend("friend-a", AiCacheFeature.GREETING)
        val second = AiStorageKey.forFriend("friend-b", AiCacheFeature.GREETING)

        assertThat(first).startsWith("ai_v2_greeting_")
        assertThat(first).contains("friend-a")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun storageKey_lengthPrefixesFriendIdToAvoidSuffixCollision() {
        // friendId "x_y" 不能与 friendId "x" + 后缀 "_y" 的 key 碰撞
        val withUnderscore = AiStorageKey.forFriend("x_y", AiCacheFeature.GREETING)
        val withSuffix = AiStorageKey.forFriend("x", AiCacheFeature.GREETING, "_y")

        assertThat(withUnderscore).isNotEqualTo(withSuffix)
    }

    @Test
    fun activationDomain_preservesServerActivationPhrase() {
        val activation = AiActivationDto(
            activated = true,
            activationPhrase = "Activated!"
        ).toDomain()

        assertThat(activation.activationPhrase).isEqualTo("Activated!")
    }

    @Test
    fun repository_mapsDailyQuotaErrorCode() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.getGreeting(any()) } returns Response.success(
            AiApiResponse<AiGreetingDto>(
                code = "DAILY_QUOTA_EXCEEDED",
                message = "daily quota reached",
                requestId = "req-1",
                data = null
            )
        )
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        val result = repository.getGreeting("friend-a", "usagi", forceRefresh = true)

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.QUOTA_EXCEEDED)
        assertThat(error.serverCode).isEqualTo("DAILY_QUOTA_EXCEEDED")
    }

    @Test
    fun repository_mergesOuterQuotaIntoGreetingDomain() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val expectedQuota = AiQuotaDto(
            sessionUsed = 3,
            sessionLimit = 7,
            dailyUsed = 11,
            dailyLimit = 40,
            resetAt = 1_234L,
        )
        coEvery { api.getGreeting(any()) } returns Response.success(
            AiApiResponse(
                code = "SUCCESS",
                data = AiGreetingDto(greeting = "你好"),
                quota = expectedQuota,
            )
        )
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        val greeting = repository.getGreeting("friend-a", "usagi", forceRefresh = true).getOrThrow()

        assertThat(greeting.quota).isEqualTo(expectedQuota.toDomain())
    }

    @Test
    fun tasteCacheSuffix_isDerivedFromWatchedData() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val watched = AiWatchedTitleDto(
            mediaId = "tmdb:1",
            mediaType = "movie",
            title = "电影 A",
            year = 2024,
            genres = listOf("Drama"),
            publicRating = 8.2,
            userRating = 4.0,
            watchedAt = "2026-08-01T00:00:00Z",
            mediaIds = AiMediaIdsDto(tmdbId = 1),
        )
        coEvery { api.getTaste(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = AiTasteDto())
        )
        val suffix = slot<String?>()
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        repository.getTaste(
            "friend-a",
            AiTasteRequest(watched = listOf(watched)),
            forceRefresh = true,
        ).getOrThrow()

        coVerify(exactly = 1) {
            storage.write("friend-a", AiCacheFeature.TASTE, any(), captureNullable(suffix))
        }
        val canonical = Json { encodeDefaults = true }
            .encodeToString(ListSerializerHolder.serializer, listOf(watched))
        assertThat(suffix.captured).isEqualTo(canonical.sha256HexForTest())
    }

    @Test
    fun dailyCacheSuffix_containsCurrentUtcDate() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.getDailyKnowledge(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = AiDailyKnowledgeDto(id = "daily-1"))
        )
        val suffix = slot<String?>()
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        repository.getDailyKnowledge("friend-a", forceRefresh = true).getOrThrow()

        coVerify(exactly = 1) {
            storage.write("friend-a", AiCacheFeature.DAILY_KNOWLEDGE, any(), captureNullable(suffix))
        }
        assertThat(suffix.captured).matches("\\d{4}-\\d{2}-\\d{2}")
    }

    @Test
    fun featureRequests_reuseSessionIdEstablishedByActivation() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val activationRequest = AiActivateRequest(
            characterId = "usagi",
            spokenName = "乌萨奇",
            sessionId = "sprite-test-session",
        )
        coEvery { api.activate(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = AiActivationDto(activated = true))
        )
        coEvery { api.getGreeting(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = AiGreetingDto(greeting = "到"))
        )
        val request = slot<AiGreetingRequest>()
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        repository.activate("friend-a", activationRequest).getOrThrow()
        repository.getGreeting("friend-a", "usagi", forceRefresh = true).getOrThrow()

        coVerify(exactly = 1) { api.getGreeting(capture(request)) }
        assertThat(request.captured.sessionId).isEqualTo("sprite-test-session")
    }

    @Test
    fun quizSubmission_requestCarriesSharedSessionId() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.activate(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = AiActivationDto(activated = true))
        )
        coEvery { api.submitQuiz(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = AiQuizResultDto(quizId = "quiz-1"))
        )
        val request = slot<AiSubmitQuizRequest>()
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        repository.activate(
            "friend-a",
            AiActivateRequest("usagi", spokenName = "乌萨奇", sessionId = "sprite-test-session"),
        ).getOrThrow()
        repository.submitQuiz("friend-a", "quiz-1", emptyList()).getOrThrow()

        coVerify(exactly = 1) { api.submitQuiz(capture(request)) }
        val body = Json.encodeToJsonElement(AiSubmitQuizRequest.serializer(), request.captured).jsonObject
        assertThat(body["sessionId"]?.jsonPrimitive?.content).isEqualTo("sprite-test-session")
    }

    @Test
    fun guestTts_doesNotReadOrWriteFriendScopedCache() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.playTts(any()) } returns Response.success(
            AiApiResponse(
                code = "SUCCESS",
                data = AiAudioDto(audioDataUrl = "data:audio/wav;base64,AA==")
            )
        )
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        val audio = repository.playGuestTts(AiTtsRequest("usagi", "到！")).getOrThrow()

        assertThat(audio.audioDataUrl).isEqualTo("data:audio/wav;base64,AA==")
        coVerify(exactly = 1) { api.playTts(any()) }
        coVerify(exactly = 0) { storage.read(any(), any(), any()) }
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
    }

    @Test
    fun quizResult_retainsCorrectAnswerFieldsAndOuterQuota() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val expectedQuota = AiQuotaDto(2, 7, 8, 40, 2_000L)
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<AiQuizResultDto>(
            """
            {
              "quizId":"quiz-1",
              "score":80,
              "correctCount":1,
              "totalQuestions":1,
              "questionResults":[{
                "questionId":"q-1",
                "score":10,
                "correct":true,
                "explanation":"因为这个选择更能解释角色的动机",
                "correctOptionIds":["b"],
                "correctAnswer":"选择 B"
              }]
            }
            """.trimIndent()
        )
        coEvery { api.submitQuiz(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = dto, quota = expectedQuota)
        )
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        val result = repository.submitQuiz("friend-a", "quiz-1", emptyList()).getOrThrow()

        val quotaField = result.javaClass.declaredFields.firstOrNull { it.name == "quota" }
        assertThat(quotaField).isNotNull()
        quotaField!!.isAccessible = true
        assertThat(quotaField.get(result)).isEqualTo(expectedQuota.toDomain())
        val questionResult = result.questionResults.single()
        val idsField = questionResult.javaClass.declaredFields.firstOrNull { it.name == "correctOptionIds" }
        val answerField = questionResult.javaClass.declaredFields.firstOrNull { it.name == "correctAnswer" }
        assertThat(idsField).isNotNull()
        assertThat(answerField).isNotNull()
        idsField!!.isAccessible = true
        answerField!!.isAccessible = true
        assertThat(idsField.get(questionResult)).isEqualTo(listOf("b"))
        assertThat(answerField.get(questionResult)).isEqualTo("选择 B")
    }

    private object ListSerializerHolder {
        val serializer = kotlinx.serialization.builtins.ListSerializer(AiWatchedTitleDto.serializer())
    }

    private fun String.sha256HexForTest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
