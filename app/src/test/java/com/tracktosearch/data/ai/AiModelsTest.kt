package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiModelsTest {

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
