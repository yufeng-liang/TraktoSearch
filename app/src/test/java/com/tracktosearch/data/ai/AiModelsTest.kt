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
