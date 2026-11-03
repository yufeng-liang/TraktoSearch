package com.tracktosearch.data.ai

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.ai.orchestrate.AiOrchestrator
import com.tracktosearch.data.ai.orchestrate.AiQuizScoring
import com.tracktosearch.data.ai.provider.AiProviderErrorCode
import com.tracktosearch.data.ai.provider.AiProviderException
import com.tracktosearch.data.ai.provider.AiProviderSettingsRepository
import com.tracktosearch.data.repository.TmdbRepository
import android.util.Base64
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Test
import java.security.MessageDigest

/**
 * BYOK 仓库层测试：AI 调用全部走本地编排器（AiOrchestrator mock），
 * 断言重点=缓存键推导、本地判分、错误映射与兜底策略（不再有网关/配额语义）。
 */
class AiRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 编排器 relaxed mock：未打桩的挂起函数返回默认值，个别用例再精确打桩。 */
    private val orchestrator: AiOrchestrator = mockk(relaxed = true)

    /** 核验/补齐链路依赖 TmdbRepository 与 Context，测试里一律 relaxed mock 静默兜底。 */
    private fun buildAiRepository(storage: AiStorage): AiRepository = AiRepository(
        orchestrator,
        storage,
        json,
        mockk<TmdbRepository>(relaxed = true),
        mockk<AiProfileRepository>(relaxed = true),
        // 缓存键的 Provider 标签：未配置时内部 runCatching 兜底成空串，relaxed mock 正好
        mockk<AiProviderSettingsRepository>(relaxed = true),
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
    fun listCharacters_returnsLocalCatalogAllAvailable() = runTest {
        val characters = buildAiRepository(mockk(relaxed = true)).listCharacters().getOrThrow()

        // BYOK：7 角色打进 App 全量可用，激活门槛已去除
        assertThat(characters).hasSize(AiCharacterCatalog.all.size)
        assertThat(characters.all { it.isAvailable }).isTrue()
    }

    @Test
    fun repository_providerInvalidSurfacesAsAiApiExceptionWithoutFallback() = runTest {
        // 配置缺失必须如实报错引导用户去配置页，绝不能退兜底文案掩盖问题
        coEvery { orchestrator.generateGreeting(any(), any()) } throws AiProviderException(
            AiProviderErrorCode.PROVIDER_INVALID,
            "Provider not configured"
        )
        val storage = mockk<AiStorage>(relaxed = true)
        val repository = buildAiRepository(storage)

        val result = repository.getGreeting("friend-a", "usagi", nickname = "小明", forceRefresh = true)

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.PROVIDER_INVALID)
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
    }

    @Test
    fun repository_serverErrorFallsBackToGreetingWithoutCacheWrite() = runTest {
        // 模型输出被拒/上游异常这类「暂时性失败」退本地兜底文案，但不写缓存
        coEvery { orchestrator.generateGreeting(any(), any()) } throws AiProviderException(
            AiProviderErrorCode.SERVER,
            "upstream exploded"
        )
        val storage = mockk<AiStorage>(relaxed = true)
        val repository = buildAiRepository(storage)

        val greeting = repository.getGreeting("friend-a", "usagi", nickname = "小明", forceRefresh = true)
            .getOrThrow()

        assertThat(greeting.greeting).contains("乌萨奇")
        assertThat(greeting.nickname).isEqualTo("小明")
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
    }

    @Test
    fun greetingCacheHitSkipsOrchestratorWhenAudioUsable() = runTest {
        val cached = AiGreeting(
            nickname = "小明",
            greeting = "缓存问候",
            spokenText = "缓存问候",
            nicknameMeaning = "含义",
            comment = "点评",
            audio = AiAudio(
                audioDataUrl = "data:audio/mpeg;base64,AA==",
                audioUrl = null,
                audioUrlExpiresAt = null,
                mimeType = "audio/mpeg",
                durationMs = null,
                cacheKey = "cache-key",
                transcript = null
            )
        )
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { storage.read("friend-a", AiCacheFeature.GREETING, "usagi") } returns
            Json.encodeToString(AiGreeting.serializer(), cached)

        val greeting = buildAiRepository(storage).getGreeting("friend-a", "usagi", nickname = "小明")
            .getOrThrow()

        assertThat(greeting.greeting).isEqualTo("缓存问候")
        coVerify(exactly = 0) { orchestrator.generateGreeting(any(), any()) }
    }

    @Test
    fun quizCacheKeyChangesWhenExcludedQuizIdsChange() = runTest {
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { storage.read(any(), any(), any()) } returns null
        coEvery { orchestrator.generateQuiz(any(), any(), any(), any()) } returns
            AiOrchestrator.OrchestratedQuiz(validDomainQuiz("quiz-1"), emptyList())
        val suffixes = mutableListOf<String?>()
        coEvery { storage.write(any(), any(), any(), any()) } coAnswers {
            suffixes += arg<String?>(3)
        }
        val repository = buildAiRepository(storage)
        val request = AiQuizRequest(
            watched = listOf(AiWatchedTitleDto(mediaId = "tmdb:1", mediaType = "movie", title = "A")),
        )

        repository.getQuiz("friend-a", nickname = "小明", request = request.copy(excludedQuizIds = listOf("quiz-old")))
        repository.getQuiz("friend-a", nickname = "小明", request = request.copy(excludedQuizIds = listOf("quiz-new")))

        assertThat(suffixes).hasSize(2)
        assertThat(suffixes[0]).isNotEqualTo(suffixes[1])
    }

    @Test
    fun quizDifficultyHint_isPassedToOrchestratorAfterFiveFeedbacks() = runTest {
        val storage = mockk<AiStorage>(relaxed = true)
        // 反馈记录要真的落盘再读回：按 (feature, suffix) 建内存 map 模拟 AiStorage 的读改写
        val store = mutableMapOf<String, String>()
        coEvery { storage.write(any(), any(), any(), any()) } coAnswers {
            store["${arg<AiCacheFeature>(1).name}_${arg<String?>(3)}"] = arg<String>(2)
        }
        coEvery { storage.read(any(), any(), any()) } coAnswers {
            store["${arg<AiCacheFeature>(1).name}_${arg<String?>(2)}"]
        }
        coEvery { orchestrator.generateQuiz(any(), any(), any(), any()) } returns
            AiOrchestrator.OrchestratedQuiz(validDomainQuiz("quiz-1"), emptyList())
        val repository = buildAiRepository(storage)

        // 不足窗口（5 条）时不出提示
        repeat(2) { repository.submitQuizDifficulty("friend-a", "quiz-x", AiQuizDifficulty.EASY).getOrThrow() }
        repeat(3) { repository.submitQuizDifficulty("friend-a", "quiz-y", AiQuizDifficulty.HARD).getOrThrow() }
        val hintSlot = slot<String?>()
        coEvery { orchestrator.generateQuiz(any(), any(), any(), captureNullable(hintSlot)) } returns
            AiOrchestrator.OrchestratedQuiz(validDomainQuiz("quiz-1"), emptyList())

        repository.getQuiz("friend-a", nickname = "小明").getOrThrow()

        // 3 hard + 2 easy → 达到阈值，出「偏难」提示（文案与服务端 readQuizDifficultyHint 一致）
        assertThat(hintSlot.captured).isEqualTo(
            "用户反馈近期题目偏难：本轮以主流影片的主线情节为主，减少冷门细节题，让题目更容易被答对。"
        )
    }

    @Test
    fun quizStream_completedWritesCacheForPregenerateToo() = runTest {
        // BYOK：预生成与正式请求共用缓存（没有服务端 usedSets 约束），完成事件必须落盘
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { storage.read(any(), any(), any()) } returns null
        coEvery { orchestrator.generateQuizStream(any(), any(), any(), any()) } returns
            flowOf(AiQuizStreamEvent.Completed(validDomainQuiz("quiz-pregen")))
        val writes = mutableListOf<String>()
        coEvery { storage.write(any(), any(), any(), any()) } coAnswers { writes += arg<String>(2) }
        val repository = buildAiRepository(storage)
        val request = AiQuizRequest(
            watched = listOf(AiWatchedTitleDto(mediaId = "tmdb:1", mediaType = "movie", title = "A")),
            date = "2026-09-11"
        )

        val events = repository.getQuizStream("friend-a", nickname = "小明", request = request).toList()

        val completed = events.filterIsInstance<AiQuizStreamEvent.Completed>().single()
        assertThat(completed.quiz.quizId).isEqualTo("quiz-pregen")
        assertThat(writes).hasSize(1)
        assertThat(writes.single()).contains("quiz-pregen")
    }

    @Test
    fun quizStream_cacheHitEmitsCompletedWithoutOrchestrator() = runTest {
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { storage.read(any(), any(), any()) } returns
            Json.encodeToString(AiQuiz.serializer(), validDomainQuiz("quiz-cached"))

        val events = buildAiRepository(storage)
            .getQuizStream("friend-a", nickname = "小明")
            .toList()

        assertThat(events).hasSize(1)
        assertThat((events.single() as AiQuizStreamEvent.Completed).quiz.quizId).isEqualTo("quiz-cached")
        coVerify(exactly = 0) { orchestrator.generateQuizStream(any(), any(), any(), any()) }
    }

    @Test
    fun submitQuiz_scoresLocallyFromCachedQuiz() = runTest {
        val quiz = validDomainQuiz("quiz-1")
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { storage.readSuffixes("friend-a", AiCacheFeature.QUIZ) } returns listOf("quiz-suffix")
        coEvery { storage.read("friend-a", AiCacheFeature.QUIZ, "quiz-suffix") } returns
            Json.encodeToString(AiQuiz.serializer(), quiz)
        val repository = buildAiRepository(storage)

        val result = repository.submitQuiz(
            "friend-a",
            "quiz-1",
            buildList {
                // 10 道单选全对（8 分×10）+ 2 道多选全对（10 分×2）+ 简答只判对不加分
                repeat(10) { index -> add(AiQuizAnswer(questionId = "single-$index", selectedOptionIds = listOf("a"))) }
                repeat(2) { index -> add(AiQuizAnswer(questionId = "multiple-$index", selectedOptionIds = listOf("a", "b"))) }
                add(AiQuizAnswer(questionId = "short", textAnswer = "因为从众压力让人附和"))
            }
        ).getOrThrow()

        assertThat(result.score).isEqualTo(100)
        // v2 计分口径：简答题不计入 correctCount
        assertThat(result.correctCount).isEqualTo(12)
        val shortResult = result.questionResults.single { it.questionId == "short" }
        assertThat(shortResult.correct).isTrue()
        assertThat(shortResult.correctAnswer).isEqualTo("从众压力")
    }

    @Test
    fun submitQuiz_missingCachedQuizFailsWithInvalidRequest() = runTest {
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { storage.readSuffixes(any(), any()) } returns emptyList()

        val result = buildAiRepository(storage).submitQuiz("friend-a", "quiz-404", emptyList())

        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.INVALID_REQUEST)
    }

    @Test
    fun guestTts_wrapsProviderBytesIntoDataUrlAndStripsCatchphrase() = runTest {
        // 纯 JVM 单测里 android.util.Base64 未被 JVM 单测桩替换，mock 静态方法按字节原样编码
        mockkStatic(Base64::class)
        every { Base64.encodeToString(any(), any()) } answers { java.util.Base64.getEncoder().encodeToString(arg(0)) }
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { orchestrator.synthesizeTts(any(), any(), any(), any(), any()) } returns byteArrayOf(1, 2, 3)
        coEvery { orchestrator.stripCatchphraseFromTranscript(any(), any()) } returns "到！"
        val repository = buildAiRepository(storage)

        val audio = repository.playGuestTts(AiTtsRequest("usagi", "到！鸭蛋")).getOrThrow()

        assertThat(audio.audioDataUrl).isEqualTo("data:audio/mpeg;base64,AQID")
        assertThat(audio.mimeType).isEqualTo("audio/mpeg")
        assertThat(audio.transcript).isEqualTo("到！")
        coVerify(exactly = 0) { storage.read(any(), any(), any()) }
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
    }

    @Test
    fun ttsCacheIdentity_includesSceneAndIgnoresLegacyStyle() = runTest {
        val storage = mockk<AiStorage>(relaxed = true)
        // relaxed mock 对 ByteArray 返回值不可靠，必须显式打桩
        coEvery { orchestrator.synthesizeTts(any(), any(), any(), any(), any()) } returns byteArrayOf(1)
        val suffixes = mutableListOf<String?>()
        coEvery { storage.write(any(), any(), any(), any()) } coAnswers {
            suffixes += arg<String?>(3)
        }
        val repository = buildAiRepository(storage)

        repository.playTts("friend-a", AiTtsRequest("usagi", "到——！", style = "style-a", scene = AiTtsScene.GREETING)).getOrThrow()
        repository.playTts("friend-a", AiTtsRequest("usagi", "到——！", style = "style-b", scene = AiTtsScene.GREETING)).getOrThrow()
        repository.playTts("friend-a", AiTtsRequest("usagi", "到——！", style = "style-a", scene = AiTtsScene.ACTIVATION_ACK)).getOrThrow()

        assertThat(suffixes).hasSize(3)
        assertThat(suffixes[0]).isEqualTo(suffixes[1])
        assertThat(suffixes[0]).isNotEqualTo(suffixes[2])
    }

    @Test
    fun tasteCacheSuffix_isDerivedFromWatchedData() = runTest {
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
        coEvery { orchestrator.analyzeTaste(any(), any()) } returns AiTasteAnalysis(
            roast = "锐评",
            tasteProfile = "画像",
            highlights = emptyList(),
            recommendations = emptyList(),
            quota = null
        )
        val suffix = slot<String?>()
        val repository = buildAiRepository(storage)

        repository.getTaste(
            "friend-a",
            AiTasteRequest(watched = listOf(watched)),
            nickname = "小明",
            forceRefresh = true,
        ).getOrThrow()

        coVerify(exactly = 1) {
            storage.write("friend-a", AiCacheFeature.TASTE, any(), captureNullable(suffix))
        }
        val canonical = Json { encodeDefaults = true }
            .encodeToString(ListSerializer(AiWatchedTitleDto.serializer()), listOf(watched))
        // v2 起缓存 key 带 "v2|" 前缀做结构版本隔离（旧结构缓存不命中）
        assertThat(suffix.captured).isEqualTo("v2|${canonical.sha256HexForTest()}")
    }

    @Test
    fun dailyLocaleCache_fallsBackToLegacyDateCache() = runTest {
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
        val repository = buildAiRepository(storage)

        val daily = repository.getDailyKnowledge("friend-a").getOrThrow()

        assertThat(daily.id).isEqualTo("legacy-daily")
        assertThat(daily.takeaway).isEqualTo("旧事实")
        assertThat(daily.filmEvidence).isEqualTo("旧事实")
        assertThat(readSuffixes).hasSize(2)
        assertThat(readSuffixes[0]).startsWith("unit-v1|")
        assertThat(readSuffixes[1]).matches("\\d{4}-\\d{2}-\\d{2}")
        coVerify(exactly = 0) { orchestrator.generateDailyStream(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
    }

    @Test
    fun dailyRequest_rejectsUnsupportedLocaleBeforeCacheAndOrchestrator() = runTest {
        val storage = mockk<AiStorage>(relaxed = true)
        val repository = buildAiRepository(storage)

        val result = repository.getDailyKnowledge("friend-a", locale = "zh-TW")

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.INVALID_REQUEST)
        coVerify(exactly = 0) { storage.read(any(), any(), any()) }
        coVerify(exactly = 0) { storage.write(any(), any(), any(), any()) }
        coVerify(exactly = 0) { orchestrator.generateDailyStream(any(), any(), any(), any(), any()) }
    }

    /** 构造合法 13 题结构（10 单选 + 2 多选 + 1 简答）并带判分依据（correctOptionIds/correctAnswer）。 */
    private fun validDomainQuiz(quizId: String): AiQuiz = AiQuiz(
        quizId = quizId,
        title = "你真的看懂这些影视了吗",
        subtitle = "本轮涉及：电影 A",
        mediaTitles = listOf("电影 A"),
        questions = buildList {
            repeat(10) { index ->
                add(
                    AiQuizQuestion(
                        id = "single-$index",
                        type = AiQuizQuestionType.SINGLE,
                        prompt = "Question $index",
                        options = listOf(AiQuizOption("a", "A"), AiQuizOption("b", "B")),
                        correctOptionIds = listOf("a"),
                        correctAnswer = "a"
                    )
                )
            }
            repeat(2) { index ->
                add(
                    AiQuizQuestion(
                        id = "multiple-$index",
                        type = AiQuizQuestionType.MULTIPLE,
                        prompt = "Question multiple $index",
                        options = listOf(AiQuizOption("a", "A"), AiQuizOption("b", "B")),
                        correctOptionIds = listOf("a", "b"),
                        correctAnswer = "a,b"
                    )
                )
            }
            add(
                AiQuizQuestion(
                    id = "short",
                    type = AiQuizQuestionType.SHORT,
                    prompt = "Question short",
                    // 简答题判分依据：correctAnswer 文本 + 关键词，二选一命中即判对
                    correctAnswer = "从众压力",
                    answerKeywords = listOf("从众")
                )
            )
        },
        totalScore = 100
    )

    private fun String.sha256HexForTest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
