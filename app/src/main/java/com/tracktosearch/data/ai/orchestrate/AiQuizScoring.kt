package com.tracktosearch.data.ai.orchestrate

import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionResult
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizResult
import com.tracktosearch.data.ai.AiQuizOption

/**
 * BYOK 本地判分：把服务端 scoreQuiz（handler.ts:3526-3570）原样移植。
 *
 * 判分契约（scoringVersion=2）：
 * - 分值：single=8、multiple=10、short=0；
 * - short 题只给反馈不计分，correctCount 也不累计（isNew 分支）；
 * - short 判对规则：答案（zh-CN 小写）包含任一 answerKeyword（小写）即对，
 *   无关键词时退回「包含参考答案全文」。
 */
object AiQuizScoring {

    const val SCORING_VERSION = 2

    /** 总分页文案：与服务端 submit 响应一致。 */
    const val SUMMARY_GOOD = "你不只是看过，还真的留下了思考。"
    const val SUMMARY_PLAIN = "再回看一次，也许会发现新的入口。"

    /** 单题分值（scoringVersion=2 口径）。 */
    private fun pointsFor(question: AiQuizQuestion): Int = when (question.type) {
        AiQuizQuestionType.SHORT -> 0
        AiQuizQuestionType.MULTIPLE -> 10
        AiQuizQuestionType.SINGLE -> 8
    }

    /** short 题判对：关键词命中或包含参考答案全文（对拍 isAnswerCorrect 的 short 分支）。 */
    private fun isShortAnswerCorrect(
        answer: String?,
        question: AiQuizQuestion,
        correctAnswerText: String?,
    ): Boolean {
        val trimmed = answer?.trim()
        if (trimmed.isNullOrEmpty()) return false
        val normalized = trimmed.lowercase(java.util.Locale.SIMPLIFIED_CHINESE)
        if (question.answerKeywords.isNotEmpty()) {
            return question.answerKeywords.any {
                normalized.contains(it.lowercase(java.util.Locale.SIMPLIFIED_CHINESE))
            }
        }
        val reference = correctAnswerText ?: return false
        return normalized.contains(reference.lowercase(java.util.Locale.SIMPLIFIED_CHINESE))
    }

    /** 单题判对（对拍 isAnswerCorrect）：single 全等、multiple 排序后集合相等。 */
    fun isAnswerCorrect(
        answer: List<String>?,
        textAnswer: String?,
        question: AiQuizQuestion,
        correctAnswerText: String? = null,
    ): Boolean {
        return when (question.type) {
            AiQuizQuestionType.SHORT -> isShortAnswerCorrect(textAnswer, question, correctAnswerText)
            AiQuizQuestionType.SINGLE ->
                answer?.singleOrNull() != null && answer.single() == question.correctOptionIds.firstOrNull()
            AiQuizQuestionType.MULTIPLE ->
                answer != null && answer.isNotEmpty() &&
                    answer.distinct().sorted() == question.correctOptionIds.distinct().sorted()
        }
    }

    /**
     * 判分入口：answers 为客户端提交的作答列表（questionId → 选项 id / 文本答案）。
     *
     * @param correctAnswerTextById short 题参考答案的补充来源（本地生成的题已带
     *   answerKeywords；旧缓存题两者皆缺时按未作答处理）。这是相对服务端的一处
     *   自由裁量：服务端 correctAnswer 全文在客户端域模型里不存在。
     */
    fun scoreQuiz(
        quiz: AiQuiz,
        answers: List<AiQuizAnswer>,
        scoringVersion: Int = SCORING_VERSION,
        correctAnswerTextById: Map<String, String> = emptyMap(),
    ): AiQuizResult {
        require(scoringVersion == SCORING_VERSION) { "仅支持 scoringVersion=2" }
        val answerById = answers.associateBy { it.questionId }
        var score = 0
        var correctCount = 0
        // 维度分（按题型聚合）：earned/possible → 百分比；short 在 v2 下 possible=0 记 0。
        val earnedByType = mutableMapOf<AiQuizQuestionType, Int>()
        val possibleByType = mutableMapOf<AiQuizQuestionType, Int>()
        val results = quiz.questions.map { question ->
            val answer = answerById[question.id]
            val selected = answer?.selectedOptionIds?.takeIf { it.isNotEmpty() }
            val textAnswer = answer?.textAnswer
            val points = pointsFor(question)
            val correct = isAnswerCorrect(selected, textAnswer, question, correctAnswerTextById[question.id])
            if (correct && points > 0) {
                score += points
                // short 题在 v2 下 points=0，天然不进这里；correctCount 只按可计分题累计。
                correctCount += 1
            }
            earnedByType.merge(question.type, if (correct) points else 0, Int::plus)
            possibleByType.merge(question.type, points, Int::plus)
            val explanation = question.explanation.orEmpty()
            AiQuizQuestionResult(
                questionId = question.id,
                score = if (correct) points else 0,
                correct = correct,
                explanation = explanation,
                correctOptionIds = question.correctOptionIds,
                correctAnswer = when (question.type) {
                    AiQuizQuestionType.MULTIPLE -> question.correctOptionIds.joinToString(",")
                    AiQuizQuestionType.SINGLE -> question.correctOptionIds.firstOrNull()
                    AiQuizQuestionType.SHORT -> correctAnswerTextById[question.id]
                },
                answerRationale = question.answerRationale.ifBlank { explanation },
                distractorRationale = question.distractorRationale,
                subject = question.subject.ifBlank { "影视分析" },
                concept = question.concept.ifBlank {
                    question.knowledgePoint.ifBlank { "证据与推理" }
                },
                learningTakeaway = question.learningTakeaway.ifBlank {
                    "先回到作品中可观察的证据，再形成自己的判断。"
                },
                evidenceUsed = question.evidenceUsed.ifBlank {
                    "已看记录中的《" + (question.sourceTitle ?: question.mediaTitle).orEmpty() + "》。"
                },
            )
        }
        val dimensionScores = AiQuizQuestionType.entries.associate { type ->
            val possible = possibleByType[type] ?: 0
            val earned = earnedByType[type] ?: 0
            type.name.lowercase() to if (possible > 0) {
                Math.round(earned.toDouble() / possible * 100).toInt()
            } else {
                0
            }
        }
        return AiQuizResult(
            quizId = quiz.quizId,
            score = minOf(100, score),
            totalScore = quiz.totalScore,
            correctCount = correctCount,
            totalQuestions = quiz.questions.size,
            summary = if (score >= 80) SUMMARY_GOOD else SUMMARY_PLAIN,
            dimensionScores = dimensionScores,
            questionResults = results,
        )
    }

    /** 便捷重载：内部编排产出的题带有 correctAnswer，判分零信息损失。 */
    fun scoreOrchestrated(
        quiz: AiQuiz,
        answers: List<AiQuizAnswer>,
        orchestrated: List<AiOrchestrateValidate.OrchestratedQuestion>,
    ): AiQuizResult {
        val textById = orchestrated.mapNotNull { item ->
            item.correctAnswer?.let { item.question.id to it }
        }.toMap()
        return scoreQuiz(quiz, answers, SCORING_VERSION, textById)
    }

    /** 选项 id 校验：防止提交里出现题目没有的选项（对拍 readClientAnswerOptionIds）。 */
    fun sanitizeAnswers(quiz: AiQuiz, answers: List<AiQuizAnswer>): List<AiQuizAnswer> {
        val optionsById = quiz.questions.associate { it.id to it.options.map(AiQuizOption::id).toSet() }
        return answers.filter { answer ->
            optionsById.containsKey(answer.questionId) && (answer.textAnswer != null || answer.selectedOptionIds.isNotEmpty())
        }.map { answer ->
            val allowed = optionsById[answer.questionId].orEmpty()
            answer.copy(selectedOptionIds = answer.selectedOptionIds.filter { it in allowed }.distinct().take(4))
        }.filter { it.textAnswer != null || it.selectedOptionIds.isNotEmpty() }
    }
}
