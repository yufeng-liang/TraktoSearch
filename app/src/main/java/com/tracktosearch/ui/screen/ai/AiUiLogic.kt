package com.tracktosearch.ui.screen.ai

import com.tracktosearch.data.ai.AiQuizQuestionType

private const val MAX_ACTIVATION_ATTEMPTS = 3

/** 激活只允许首次尝试加两次重试，成功后不会进入持续监听。 */
fun canRetryActivation(attempt: Int): Boolean = attempt in 1 until MAX_ACTIVATION_ATTEMPTS

fun quizProgress(current: Int, total: Int): Float {
    if (total <= 0) return 0f
    return (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

fun localQuestionScore(type: AiQuizQuestionType, answered: Boolean): Int {
    if (!answered) return 0
    return when (type) {
        AiQuizQuestionType.SINGLE -> 7
        AiQuizQuestionType.MULTIPLE, AiQuizQuestionType.SHORT -> 10
    }
}
