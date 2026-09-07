package com.tracktosearch.data.ai

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.TmdbRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.security.MessageDigest

class AiRepositoryTest {

    /** 核验/补齐链路依赖 TmdbRepository 与 Context，测试里一律 relaxed mock 静默兜底。 */
    private fun buildAiRepository(api: AiApiService, storage: AiStorage): AiRepository = AiRepository(
        api,
        storage,
        Json { ignoreUnknownKeys = true },
        mockk<TmdbRepository>(relaxed = true),
        mockk<Context>(relaxed = true)
    )


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
        val repository = buildAiRepository(api, storage)

        val result = repository.getGreeting("friend-a", "usagi", forceRefresh = true)

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.QUOTA_EXCEEDED)
        assertThat(error.serverCode).isEqualTo("DAILY_QUOTA_EXCEEDED")
    }

    @Test
    fun repository_parsesAiQuotaErrorFromNon2xxErrorBody() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.getGreeting(any()) } returns Response.error(
            429,
            """{"code":"AI_DAILY_QUOTA_EXCEEDED","message":"Daily AI quota exceeded"}"""
                .toResponseBody("application/json".toMediaType())
        )
        val repository = buildAiRepository(api, storage)

        val result = repository.getGreeting("friend-a", "usagi", forceRefresh = true)

        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.QUOTA_EXCEEDED)
        assertThat(error.serverCode).isEqualTo("AI_DAILY_QUOTA_EXCEEDED")
        assertThat(error.message).isEqualTo("Daily AI quota exceeded")
    }

    @Test
    fun forceRefresh_businessErrorDoesNotFallBackToCachedValue() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val cached = AiGreetingDto(greeting = "old greeting")
        coEvery { storage.read("friend-a", AiCacheFeature.GREETING, "usagi") } returns
            Json.encodeToString(AiGreeting.serializer(), cached.toDomain())
        coEvery { api.getGreeting(any()) } returns Response.error(
            429,
            """{"code":"AI_SESSION_QUOTA_EXCEEDED","message":"Session quota exceeded"}"""
                .toResponseBody("application/json".toMediaType())
        )
        val repository = buildAiRepository(api, storage)

        val result = repository.getGreeting("friend-a", "usagi", forceRefresh = true)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(AiApiException::class.java)
    }

    @Test
    fun quizCacheKeyChangesWhenExcludedQuizIdsChange() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.getQuiz(any()) } returns Response.success(
            AiApiResponse(code = "SUCCESS", data = validQuizDto("quiz-1"))
        )
        val suffixes = mutableListOf<String?>()
        coEvery { storage.write(any(), any(), any(), any()) } coAnswers {
            suffixes += arg<String?>(3)
        }
        val repository = buildAiRepository(api, storage)
        val request = AiQuizRequest(
            watched = listOf(AiWatchedTitleDto(mediaId = "tmdb:1", mediaType = "movie", title = "A")),
            sessionId = "sprite-session",
        )

        repository.getQuiz("friend-a", request.copy(excludedQuizIds = listOf("quiz-old")))
        repository.getQuiz("friend-a", request.copy(excludedQuizIds = listOf("quiz-new")))

        assertThat(suffixes).hasSize(2)
        assertThat(suffixes[0]).isNotEqualTo(suffixes[1])
    }

    private fun validQuizDto(quizId: String): AiQuizDto = AiQuizDto(
        quizId = quizId,
        questions = buildList {
            repeat(10) { index ->
                add(
                    AiQuizQuestionDto(
                        id = "single-$index",
                        type = "single",
                        prompt = "Question $index",
                        options = listOf(AiQuizOption("a", "A"), AiQuizOption("b", "B")),
                    )
                )
            }
            repeat(2) { index ->
                add(
                    AiQuizQuestionDto(
                        id = "multiple-$index",
                        type = "multiple",
                        prompt = "Question multiple $index",
                        options = listOf(AiQuizOption("a", "A"), AiQuizOption("b", "B")),
                    )
                )
            }
            add(
                AiQuizQuestionDto(
                    id = "short",
                    type = "short",
                    prompt = "Question short",
                )
            )
        }
    )

    @Test
    fun repository_mergesOuterQuotaIntoGreetingDomain() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val expectedQuota = AiQuotaDto(
            sessionUsed = 3,
            sessionLimit = 14,
            dailyUsed = 11,
            dailyLimit = 80,
            resetAt = 1_234L,
        )
        coEvery { api.getGreeting(any()) } returns Response.success(
            AiApiResponse(
                code = "SUCCESS",
                data = AiGreetingDto(greeting = "你好"),
                quota = expectedQuota,
            )
        )
        val repository = buildAiRepository(api, storage)

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
        val repository = buildAiRepository(api, storage)

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
        // v2 起缓存 key 带 "v2|" 前缀做结构版本隔离（旧结构缓存不命中）
        assertThat(suffix.captured).isEqualTo("v2|${canonical.sha256HexForTest()}")
    }

    @Test
    fun dailyRequestAndCacheKeyIncludeLocaleAndWatchedDigest() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val watched = AiWatchedTitleDto(
            mediaId = "tmdb:1",
            mediaType = "movie",
            title = "电影 A",
            year = 2024,
            mediaIds = AiMediaIdsDto(tmdbId = 1)
        )
        val requests = mutableListOf<AiDailyRequest>()
        coEvery { api.getDailyKnowledge(any()) } coAnswers {
            requests += arg<AiDailyRequest>(0)
            Response.success(AiApiResponse(code = "SUCCESS", data = AiDailyKnowledgeDto(id = "daily-1")))
        }
        val suffixes = mutableListOf<String?>()
        coEvery { storage.write(any(), any(), any(), any()) } coAnswers {
            suffixes += arg<String?>(3)
        }
        val repository = buildAiRepository(api, storage)

        repository.getDailyKnowledge(
            "friend-a",
            forceRefresh = true,
            watched = listOf(watched),
            locale = "ja-JP"
        ).getOrThrow()
        repository.getDailyKnowledge(
            "friend-a",
            forceRefresh = true,
            watched = listOf(watched),
            locale = "en-US"
        ).getOrThrow()

        coVerify(exactly = 2) { api.getDailyKnowledge(any()) }
        assertThat(requests.map { it.locale }).containsExactly("ja-JP", "en-US").inOrder()
        assertThat(requests.first().watched).isEqualTo(listOf(watched))
        assertThat(suffixes).hasSize(2)
        assertThat(suffixes[0]).isNotEqualTo(suffixes[1])
        val parts = suffixes[0]!!.split("|")
        assertThat(parts).hasSize(4)
        assertThat(parts[0]).isEqualTo("unit-v1")
        assertThat(parts[1]).matches("\\d{4}-\\d{2}-\\d{2}")
        assertThat(parts[2]).isEqualTo("ja-JP")
        val canonical = Json { encodeDefaults = true }
            .encodeToString(ListSerializerHolder.serializer, listOf(watched))
        assertThat(parts[3]).isEqualTo(canonical.sha256HexForTest())
    }

    @Test
    fun dailyLocaleCache_fallsBackToLegacyDateCache() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>()
        // 直接使用旧版 domain 缓存 JSON，避免当前序列化器把新字段写进测试夹具。
        val legacyJson = """
            {
              "id":"legacy-daily",
              "title":"旧标题",
              "fact":"旧事实",
              "explanation":"旧解释",
              "sourceName":"旧来源",
              "sourceUrl":"https://legacy.example/source",
              "publishedAt":null,
              "characterLine":null
            }
        """.trimIndent()
        val readSuffixes = mutableListOf<String?>()
        coEvery { storage.read(any(), any(), any()) } coAnswers {
            val suffix = arg<String?>(2)
            readSuffixes += suffix
            // 旧键只有日期（或日期 + digest），没有 locale 分隔段。
            if (suffix?.contains("|") == false) legacyJson else null
        }
        val repository = buildAiRepository(api, storage)

        val daily = repository.getDailyKnowledge("friend-a").getOrThrow()

        assertThat(daily.id).isEqualTo("legacy-daily")
        assertThat(daily.takeaway).isEqualTo("旧事实")
        assertThat(daily.filmEvidence).isEqualTo("旧事实")
        assertThat(readSuffixes).hasSize(2)
        assertThat(readSuffixes[0]).startsWith("unit-v1|")
        assertThat(readSuffixes[1]).matches("\\d{4}-\\d{2}-\\d{2}")
        coVerify(exactly = 0) { api.getDailyKnowledge(any()) }
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
    }

    @Test
    fun dailyRequest_rejectsUnsupportedLocaleBeforeNetworkAndCache() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val repository = buildAiRepository(api, storage)

        val result = repository.getDailyKnowledge("friend-a", locale = "zh-TW")

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.INVALID_REQUEST)
        coVerify(exactly = 0) { api.getDailyKnowledge(any()) }
        coVerify(exactly = 0) { storage.read(any(), any(), any()) }
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
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
        val repository = buildAiRepository(api, storage)

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
        val repository = buildAiRepository(api, storage)

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
        val repository = buildAiRepository(api, storage)

        val audio = repository.playGuestTts(AiTtsRequest("usagi", "到！")).getOrThrow()

        assertThat(audio.audioDataUrl).isEqualTo("data:audio/wav;base64,AA==")
        coVerify(exactly = 1) { api.playTts(any()) }
        coVerify(exactly = 0) { storage.read(any(), any(), any()) }
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
    }

    @Test
    fun ttsCacheIdentity_includesSceneAndIgnoresLegacyStyle() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.playTts(any()) } returns Response.success(
            AiApiResponse(
                code = "SUCCESS",
                data = AiAudioDto(audioDataUrl = "data:audio/mpeg;base64,AA=="),
            )
        )
        val suffixes = mutableListOf<String?>()
        coEvery { storage.write(any(), any(), any(), any()) } coAnswers {
            suffixes += arg<String?>(3)
        }
        val repository = buildAiRepository(api, storage)

        repository.playTts(
            "friend-a",
            AiTtsRequest("usagi", "到——！", style = "style-a", scene = AiTtsScene.GREETING),
        ).getOrThrow()
        repository.playTts(
            "friend-a",
            AiTtsRequest("usagi", "到——！", style = "style-b", scene = AiTtsScene.GREETING),
        ).getOrThrow()
        repository.playTts(
            "friend-a",
            AiTtsRequest("usagi", "到——！", style = "style-a", scene = AiTtsScene.ACTIVATION_ACK),
        ).getOrThrow()

        assertThat(suffixes).hasSize(3)
        assertThat(suffixes[0]).isEqualTo(suffixes[1])
        assertThat(suffixes[0]).isNotEqualTo(suffixes[2])
    }

    @Test
    fun expiredSignedAudioUrl_isRefetchedInsteadOfReadFromCache() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val expired = AiAudio(
            audioDataUrl = null,
            audioUrl = "https://gateway.example/audio/expired",
            audioUrlExpiresAt = System.currentTimeMillis() - 1,
            mimeType = "audio/mpeg",
            durationMs = null,
            cacheKey = "tts-vd-v1/expired.mp3",
            transcript = "到！",
        )
        coEvery { storage.read("friend-a", AiCacheFeature.TTS, any()) } returns
            Json.encodeToString(AiAudio.serializer(), expired)
        coEvery { api.playTts(any()) } returns Response.success(
            AiApiResponse(
                code = "SUCCESS",
                data = AiAudioDto(
                    audioUrl = "https://gateway.example/audio/fresh",
                    audioUrlExpiresAt = System.currentTimeMillis() + 60_000,
                ),
            )
        )
        val repository = buildAiRepository(api, storage)

        val audio = repository.playTts(
            "friend-a",
            AiTtsRequest("usagi", "到！", scene = AiTtsScene.ACTIVATION_ACK),
        ).getOrThrow()

        assertThat(audio.audioUrl).isEqualTo("https://gateway.example/audio/fresh")
        coVerify(exactly = 1) { api.playTts(any()) }
    }

    @Test
    fun greetingWithExpiredSignedAudioUrl_isRefetchedInsteadOfPlayedFromCache() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val expiredGreeting = AiGreeting(
            nickname = "小明",
            greeting = "欢迎回来",
            spokenText = "欢迎回来呀哈！",
            nicknameMeaning = "名字很有精神",
            comment = "今天也找部好电影吧",
            audio = AiAudio(
                audioDataUrl = null,
                audioUrl = "https://gateway.example/audio/expired",
                audioUrlExpiresAt = System.currentTimeMillis() - 1,
                mimeType = "audio/mpeg",
                durationMs = null,
                cacheKey = "tts-vd-v1/expired.mp3",
                transcript = "欢迎回来呀哈！",
            ),
        )
        coEvery { storage.read("friend-a", AiCacheFeature.GREETING, "usagi") } returns
            Json.encodeToString(AiGreeting.serializer(), expiredGreeting)
        coEvery { api.getGreeting(any()) } returns Response.success(
            AiApiResponse(
                code = "SUCCESS",
                data = AiGreetingDto(
                    greeting = "欢迎回来",
                    spokenText = "欢迎回来呀哈！",
                    audio = AiAudioDto(
                        audioUrl = "https://gateway.example/audio/fresh",
                        audioUrlExpiresAt = System.currentTimeMillis() + 60_000,
                    ),
                ),
            )
        )
        val repository = buildAiRepository(api, storage)

        val greeting = repository.getGreeting("friend-a", "usagi").getOrThrow()

        assertThat(greeting.audio?.audioUrl).isEqualTo("https://gateway.example/audio/fresh")
        coVerify(exactly = 1) { api.getGreeting(any()) }
    }

    @Test
    fun quizResult_retainsCorrectAnswerFieldsAndOuterQuota() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        val expectedQuota = AiQuotaDto(2, 14, 8, 80, 2_000L)
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
        val repository = buildAiRepository(api, storage)

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
