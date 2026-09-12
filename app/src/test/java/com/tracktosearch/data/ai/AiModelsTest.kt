package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AiModelsTest {

    @Test
    fun quota_defaults_matchCurrentAiLimits() {
        val quota = AiQuotaDto()

        assertThat(quota.sessionLimit).isEqualTo(14)
        assertThat(quota.dailyLimit).isEqualTo(80)
    }

    @Test
    fun greeting_keepsDisplayTextSeparateFromSpokenText() {
        val greeting = AiGreetingDto(
            greeting = "你好呀",
            spokenText = "你好呀，今天也一起看电影吧！",
        ).toDomain()

        assertThat(greeting.greeting).isEqualTo("你好呀")
        assertThat(greeting.spokenText).isEqualTo("你好呀，今天也一起看电影吧！")
    }

    @Test
    fun greeting_fallsBackToDisplayTextWhenSpokenTextIsMissing() {
        assertThat(AiGreetingDto(greeting = "到！").toDomain().spokenText).isEqualTo("到！")
    }

    @Test
    fun ttsRequest_serializesSceneWithoutChangingLegacyFields() {
        val request = AiTtsRequest(
            characterId = "usagi",
            text = "到——！",
            style = "旧客户端风格",
            sessionId = "sprite-session",
            scene = AiTtsScene.ACTIVATION_ACK,
        )
        val body = Json { encodeDefaults = true }
            .encodeToJsonElement(AiTtsRequest.serializer(), request)
            .jsonObject

        assertThat(body["style"]?.jsonPrimitive?.content).isEqualTo("旧客户端风格")
        assertThat(body["sessionId"]?.jsonPrimitive?.content).isEqualTo("sprite-session")
        assertThat(body["scene"]?.jsonPrimitive?.content).isEqualTo("ACTIVATION_ACK")
    }

    @Test
    fun activateRequest_leavesOutTheAudioFieldWhenNothingIsUploaded() {
        // 网关把"报文里出现 audioDataUrl"当成带了音频，显式 null 会被判成非法音频（400 INVALID_AUDIO），
        // 语音激活（本地识别后换文字激活）与文字兜底两条路都要靠这个字段不出现才能通过。
        val body = Json { encodeDefaults = true }
            .encodeToJsonElement(
                AiActivateRequest.serializer(),
                AiActivateRequest(characterId = "usagi", spokenName = "乌萨奇", sessionId = "sprite-session")
            )
            .jsonObject

        assertThat(body.keys).doesNotContain("audioDataUrl")
        assertThat(body["spokenName"]?.jsonPrimitive?.content).isEqualTo("乌萨奇")
        assertThat(body["sessionId"]?.jsonPrimitive?.content).isEqualTo("sprite-session")
    }

    @Test
    fun activateRequest_keepsTheAudioFieldWhenAudioIsUploaded() {
        val body = Json { encodeDefaults = true }
            .encodeToJsonElement(
                AiActivateRequest.serializer(),
                AiActivateRequest(characterId = "usagi", audioDataUrl = "data:audio/wav;base64,AA==")
            )
            .jsonObject

        assertThat(body["audioDataUrl"]?.jsonPrimitive?.content).isEqualTo("data:audio/wav;base64,AA==")
    }

    @Test
    fun ttsScenes_matchWorkerContract() {
        assertThat(AiTtsScene.entries.map { it.name }).containsExactly(
            "AUDITION", "ACTIVATION_ACK", "GREETING"
        ).inOrder()
    }

    @Test
    fun characterDto_usesPreviewTextWhenLegacyAuditionTextIsMissing() {
        val character = AiCharacterDto(
            id = "usagi",
            name = "乌萨奇",
            activationWord = "乌萨奇",
            isAvailable = true,
            previewText = "服务端试听文案"
        ).toDomain()

        assertThat(character.auditionText).isEqualTo("服务端试听文案")
    }

    @Test
    fun characterDto_fallsBackToCatalogAuditionTextWhenBothRemoteFieldsAreMissing() {
        val character = AiCharacterDto(
            id = "usagi",
            name = "乌萨奇",
            activationWord = "乌萨奇",
            isAvailable = true
        ).toDomain()

        assertThat(character.auditionText)
            .isEqualTo(AiCharacterCatalog.all.single { it.id == "usagi" }.auditionText)
    }

    @Test
    fun characterDto_keepsAuditionTextEmptyWhenUnknownCharacterHasNoRemoteText() {
        val character = AiCharacterDto(
            id = "unknown",
            name = "未知角色",
            activationWord = "未知角色",
            isAvailable = true
        ).toDomain()

        assertThat(character.auditionText).isEmpty()
    }

    @Test
    fun characterCatalog_containsAllSevenCharactersInStableOrder() {
        val characters = AiCharacterCatalog.all

        assertThat(characters).hasSize(7)
        assertThat(characters.map { it.name }).containsExactly(
            "吉伊", "小八", "乌萨奇", "飞鼠", "狮萨", "栗子馒头", "獭师"
        ).inOrder()
        assertThat(characters.single { it.id == "usagi" }.activationWord).isEqualTo("乌萨奇")
    }

    @Test
    fun quiz_acceptsTenSingleTwoMultipleAndOneShortQuestion() {
        val questions = buildList {
            repeat(10) { add(question("single-$it", AiQuizQuestionType.SINGLE)) }
            repeat(2) { add(question("multiple-$it", AiQuizQuestionType.MULTIPLE)) }
            add(question("short-1", AiQuizQuestionType.SHORT))
        }

        val quiz = AiQuiz(
            quizId = "quiz-1",
            title = "你真的看懂这些影视了吗",
            subtitle = "本轮涉及：电影 A",
            mediaTitles = listOf("电影 A"),
            questions = questions,
            totalScore = 100
        )

        assertThat(quiz.isThirteenQuestionStructure).isTrue()
    }

    @Test
    fun quiz_rejectsWrongQuestionComposition() {
        val quiz = AiQuiz(
            quizId = "quiz-2",
            title = "quiz",
            subtitle = "subtitle",
            mediaTitles = emptyList(),
            questions = List(13) { question("q-$it", AiQuizQuestionType.SINGLE) },
            totalScore = 100
        )

        assertThat(quiz.isThirteenQuestionStructure).isFalse()
    }

    @Test
    fun structuredFields_mapToDomainAndLegacyFieldsStillFallback() {
        val greeting = AiGreetingDto(
            nickname = "片单旅人",
            nameSignals = listOf(AiNameSignalDto(text = "旅人", interpretation = "带有漫游感")),
            nicknameSignature = "把故事带在身上的人"
        ).toDomain()
        val taste = AiTasteDto(
            taste = listOf("克制叙事"),
            profileSentence = "偏爱留白与余韵",
            evidence = listOf(
                AiTasteEvidenceDto(
                    title = "电影 A",
                    signal = "偏好关系张力",
                    inference = "更在意人物之间未说出口的情绪",
                    confidence = "high"
                )
            )
        ).toDomain()

        assertThat(greeting.nameSignals.single().text).isEqualTo("旅人")
        assertThat(greeting.nicknameSignature).isEqualTo("把故事带在身上的人")
        assertThat(taste.profileKeywords).containsExactly("克制叙事")
        assertThat(taste.profileSentence).isEqualTo("偏爱留白与余韵")
        assertThat(taste.evidence.single().title).isEqualTo("电影 A")
        assertThat(taste.evidence.single().confidence).isEqualTo("high")
    }

    @Test
    fun dailyMetadata_defaultsSafelyAndMapsStructuredFields() {
        val daily = AiDailyKnowledgeDto(
            id = "daily-1",
            relatedMediaTitle = "电影 A",
            containsSpoiler = true
        ).toDomain()

        assertThat(daily.relatedMediaTitle).isEqualTo("电影 A")
        assertThat(daily.containsSpoiler).isTrue()
        assertThat(AiDailyKnowledgeDto(id = "legacy").toDomain().relatedMediaTitle).isNull()
    }

    @Test
    fun dailyKnowledgeDto_decodesStructuredUnitAndKeepsLegacyFields() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<AiDailyKnowledgeDto>(
            """
            {
              "id":"unit-1",
              "title":"为什么第一个提出异议的人很重要？",
              "fact":"旧事实",
              "explanation":"旧解释",
              "sourceName":"旧来源",
              "sourceUrl":"https://legacy.example/source",
              "publishedAt":1725500000000,
              "characterLine":"旧台词",
              "relatedMediaTitle":"旧片名",
              "containsSpoiler":true,
              "unitId":"unit-1",
              "version":1,
              "locale":"zh-CN",
              "relationType":"direct_watch",
              "evidenceMode":"viewing_interpretation",
              "subjectGroup":"people_and_mind",
              "subject":"心理学",
              "concept":"从众压力",
              "takeaway":"第一个表达异议的人会降低其他人说出不同意见的心理成本。",
              "relatedMedia":{
                "title":"十二怒汉",
                "mediaType":"movie",
                "traktId":"123",
                "imdbId":"tt0050083",
                "tmdbId":550,
                "doubanId":null
              },
              "filmEvidence":"陪审团第一次投票后，少数意见逐渐获得公开讨论的空间。",
              "realWorldExample":"会议室里第一个提出疑问的人会让后续讨论更开放。",
              "boundary":"这是基于影片场景的入门解读，不是对角色的临床诊断。",
              "difficulty":"easy",
              "spoilerLevel":"light",
              "source":{"name":"新来源","url":"https://source.example","evidence":"来源支持群体压力结论"},
              "checkQuestion":{
                "prompt":"谁降低了其他人表达异议的心理成本？",
                "options":[{"id":"a","text":"第一个提出异议的人"},{"id":"b","text":"保持沉默的人"}],
                "correctOptionIds":["a"],
                "explanation":"第一个公开异议会打破表面共识。"
              },
              "illustration":{"status":"generating"}
            }
            """.trimIndent()
        )
        val domain = dto.toDomain()

        // 旧字段必须原样保留，供旧 UI 与旧缓存语义继续工作。
        assertThat(domain.fact).isEqualTo("旧事实")
        assertThat(domain.explanation).isEqualTo("旧解释")
        assertThat(domain.sourceName).isEqualTo("旧来源")
        assertThat(domain.sourceUrl).isEqualTo("https://legacy.example/source")
        assertThat(domain.relatedMediaTitle).isEqualTo("旧片名")
        assertThat(domain.containsSpoiler).isTrue()
        // 新结构化字段完整进入 domain。
        assertThat(domain.unitId).isEqualTo("unit-1")
        assertThat(domain.version).isEqualTo(1)
        assertThat(domain.locale).isEqualTo("zh-CN")
        assertThat(domain.relationType).isEqualTo("direct_watch")
        assertThat(domain.evidenceMode).isEqualTo("viewing_interpretation")
        assertThat(domain.subjectGroup).isEqualTo("people_and_mind")
        assertThat(domain.subject).isEqualTo("心理学")
        assertThat(domain.concept).isEqualTo("从众压力")
        assertThat(domain.takeaway).isEqualTo("第一个表达异议的人会降低其他人说出不同意见的心理成本。")
        assertThat(domain.relatedMedia?.title).isEqualTo("十二怒汉")
        assertThat(domain.relatedMedia?.traktId).isEqualTo("123")
        assertThat(domain.filmEvidence).isEqualTo("陪审团第一次投票后，少数意见逐渐获得公开讨论的空间。")
        assertThat(domain.realWorldExample).isEqualTo("会议室里第一个提出疑问的人会让后续讨论更开放。")
        assertThat(domain.boundary).isEqualTo("这是基于影片场景的入门解读，不是对角色的临床诊断。")
        assertThat(domain.difficulty).isEqualTo("easy")
        assertThat(domain.spoilerLevel).isEqualTo("light")
        assertThat(domain.source?.name).isEqualTo("新来源")
        assertThat(domain.source?.evidence).isEqualTo("来源支持群体压力结论")
        assertThat(domain.checkQuestion?.correctOptionIds).containsExactly("a")
        assertThat(domain.illustration?.status).isEqualTo("generating")
        assertThat(domain.illustration?.isReady).isFalse()
    }

    @Test
    fun dailyKnowledge_isFallbackMapsFromPayloadAndDefaultsFalse() {
        val json = Json { ignoreUnknownKeys = true }
        val generated = json.decodeFromString<AiDailyKnowledgeDto>(
            """{"id":"unit-1","unitId":"unit-1","isFallback":false}"""
        )
        val fallback = json.decodeFromString<AiDailyKnowledgeDto>(
            """{"id":"seed-2026-09-12","unitId":"seed-2026-09-12","isFallback":true}"""
        )
        val legacyDto = json.decodeFromString<AiDailyKnowledgeDto>("""{"id":"legacy-dto"}""")
        val legacyCached = json.decodeFromString<AiDailyKnowledge>(
            """
            {
              "id":"legacy-cache",
              "title":"旧标题",
              "fact":"旧事实",
              "explanation":"旧解释",
              "sourceName":"旧来源",
              "sourceUrl":"https://legacy.example/source",
              "publishedAt":null,
              "characterLine":null
            }
            """.trimIndent()
        )

        assertThat(generated.toDomain().isFallback).isFalse()
        assertThat(fallback.toDomain().isFallback).isTrue()
        // 旧 Worker 不带该字段、旧 domain 缓存没有该字段，都必须默认按正常内容展示。
        assertThat(legacyDto.isFallback).isFalse()
        assertThat(legacyDto.toDomain().isFallback).isFalse()
        assertThat(legacyCached.isFallback).isFalse()
    }

    @Test
    fun dailyIllustration_readyStateRequiresUrl() {
        val ready = AiDailyIllustration(status = "ready", url = "https://img.example/a.png", urlExpiresAt = 1L)
        val readyNoUrl = AiDailyIllustration(status = "ready", url = null)
        val generating = AiDailyIllustration(status = "generating", url = "https://img.example/a.png")

        assertThat(ready.isReady).isTrue()
        assertThat(readyNoUrl.isReady).isFalse()
        assertThat(generating.isReady).isFalse()
    }

    @Test
    fun dailyKnowledgeDto_acceptsNullSourceUrlAndFallsBackToStructuredSource() {
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString<AiDailyKnowledgeDto>(
            """
            {
              "id":"2026-09-06",
              "unitId":"unit-null-source",
              "version":1,
              "title":"旧字段允许来源链接为空",
              "fact":"旧事实",
              "explanation":"旧解释",
              "sourceName":"",
              "sourceUrl":null,
              "source":{"name":"结构化来源","url":"https://source.example","evidence":"来源支持该概念"},
              "publishedAt":null,
              "characterLine":null
            }
            """.trimIndent()
        )
        val domain = dto.toDomain()

        assertThat(domain.sourceUrl).isEqualTo("https://source.example")
        assertThat(domain.sourceName).isEqualTo("结构化来源")
        assertThat(domain.source?.evidence).isEqualTo("来源支持该概念")
    }

    @Test
    fun dailyKnowledgeLegacyPayload_decodesIntoDtoAndCachedDomainWithFallback() {
        val legacyJson = """
            {
              "id":"legacy-cache",
              "title":"旧标题",
              "fact":"旧事实",
              "explanation":"旧解释",
              "sourceName":"旧来源",
              "sourceUrl":"https://legacy.example/source",
              "publishedAt":1725500000000,
              "characterLine":null
            }
        """.trimIndent()
        val json = Json { ignoreUnknownKeys = true }
        val dto = json.decodeFromString<AiDailyKnowledgeDto>(legacyJson)
        val cached = json.decodeFromString<AiDailyKnowledge>(legacyJson)
        val domain = dto.toDomain()

        assertThat(dto.version).isNull()
        assertThat(dto.takeaway).isNull()
        assertThat(domain.takeaway).isEqualTo("旧事实")
        assertThat(domain.filmEvidence).isEqualTo("旧事实")
        assertThat(cached.takeaway).isEqualTo("旧事实")
        assertThat(cached.filmEvidence).isEqualTo("旧事实")
        assertThat(cached.locale).isEqualTo(AiDailyKnowledgeContract.DEFAULT_LOCALE)
        assertThat(cached.version).isNull()
        assertThat(cached.relatedMedia).isNull()
        assertThat(cached.checkQuestion).isNull()
    }

    @Test
    fun dailyRequest_defaultsToChineseLocaleAndSerializesIt() {
        assertThat(AiDailyKnowledgeContract.SUPPORTED_LOCALES)
            .containsExactly("zh-CN", "en-US", "ja-JP", "ko-KR")
            .inOrder()
        val request = AiDailyRequest(sessionId = "daily-session")
        val body = Json { encodeDefaults = true }
            .encodeToJsonElement(AiDailyRequest.serializer(), request)
            .jsonObject

        assertThat(request.locale).isEqualTo("zh-CN")
        assertThat(body["locale"]?.jsonPrimitive?.content).isEqualTo("zh-CN")
    }

    @Test
    fun watchedTitle_keepsOptionalTmdbEvidenceAndLegacyPayloadDefaults() {
        val legacy = Json.decodeFromString(
            AiWatchedTitleDto.serializer(),
            """{"mediaId":"1","mediaType":"movie","title":"旧电影"}"""
        )
        assertThat(legacy.overview).isEmpty()
        assertThat(legacy.originalTitle).isEmpty()
        assertThat(legacy.runtime).isNull()
        assertThat(legacy.country).isEmpty()
        val legacyBody = Json { encodeDefaults = true }
            .encodeToJsonElement(AiWatchedTitleDto.serializer(), legacy)
            .jsonObject
        assertThat(legacyBody.keys).containsNoneOf("overview", "originalTitle", "runtime", "country")

        val enriched = AiWatchedTitleDto(
            mediaId = "tmdb:1",
            mediaType = "movie",
            title = "旧电影",
            overview = "一段可靠的剧情简介",
            originalTitle = "Original Movie",
            runtime = 128,
            country = "美国"
        )
        val body = Json { encodeDefaults = true }
            .encodeToJsonElement(AiWatchedTitleDto.serializer(), enriched)
            .jsonObject
        assertThat(body["overview"]?.jsonPrimitive?.content).isEqualTo("一段可靠的剧情简介")
        assertThat(body["originalTitle"]?.jsonPrimitive?.content).isEqualTo("Original Movie")
        assertThat(body["runtime"]?.jsonPrimitive?.content).isEqualTo("128")
        assertThat(body["country"]?.jsonPrimitive?.content).isEqualTo("美国")
    }

    @Test
    fun quizQuestionMetadata_mapsCrossDisciplinaryLearningFields() {
        val quiz = AiQuizDto(
            quizId = "quiz-learning",
            questions = listOf(
                AiQuizQuestionDto(
                    id = "q1",
                    type = "single",
                    prompt = "题目",
                    subject = "社会学",
                    concept = "社会规范",
                    learningTakeaway = "群体规范会影响个人选择",
                    evidenceUsed = "《电影 A》的群体冲突；TMDB 剧情简介"
                )
            )
        ).toDomainOrNull()!!

        val question = quiz.questions.single()
        assertThat(question.subject).isEqualTo("社会学")
        assertThat(question.concept).isEqualTo("社会规范")
        assertThat(question.learningTakeaway).isEqualTo("群体规范会影响个人选择")
        assertThat(question.evidenceUsed).isEqualTo("《电影 A》的群体冲突；TMDB 剧情简介")
    }

    @Test
    fun quizQuestionMetadata_mapsStructuredFieldsAndRetainsLegacyFields() {
        val quiz = AiQuizDto(
            quizId = "quiz-structured",
            questions = listOf(
                AiQuizQuestionDto(
                    id = "q1",
                    type = "single",
                    prompt = "题目",
                    mediaTitle = "旧字段片名",
                    sourceTitle = "明确依据片名",
                    difficulty = "hard",
                    knowledgePoint = "人物动机",
                    answerRationale = "正确选项对应关键转折",
                    distractorRationale = "其他选项混淆了时间线"
                )
            )
        ).toDomainOrNull()!!

        val question = quiz.questions.single()
        assertThat(question.mediaTitle).isEqualTo("旧字段片名")
        assertThat(question.sourceTitle).isEqualTo("明确依据片名")
        assertThat(question.difficulty).isEqualTo("hard")
        assertThat(question.knowledgePoint).isEqualTo("人物动机")
        assertThat(question.answerRationale).isEqualTo("正确选项对应关键转折")
        assertThat(question.distractorRationale).isEqualTo("其他选项混淆了时间线")
    }

    @Test
    fun quizQuestionMetadata_usesLegacyMediaAndExplanationAsFallbacks() {
        val quiz = AiQuizDto(
            quizId = "quiz-compat",
            questions = listOf(
                AiQuizQuestionDto(
                    id = "q1",
                    type = "single",
                    prompt = "题目",
                    mediaTitle = "旧字段片名",
                    explanation = "旧字段解析"
                )
            )
        ).toDomainOrNull()!!

        val question = quiz.questions.single()
        assertThat(question.difficulty).isEqualTo("medium")
        assertThat(question.sourceTitle).isEqualTo("旧字段片名")
        assertThat(question.answerRationale).isEqualTo("旧字段解析")
    }

    private fun question(id: String, type: AiQuizQuestionType) = AiQuizQuestion(
        id = id,
        type = type,
        prompt = "题目 $id",
        options = if (type == AiQuizQuestionType.SHORT) emptyList() else listOf(
            AiQuizOption("a", "选项 A"),
            AiQuizOption("b", "选项 B")
        )
    )
}
