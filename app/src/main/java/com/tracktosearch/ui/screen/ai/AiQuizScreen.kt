package com.tracktosearch.ui.screen.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizResult
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiWatchedTitleDto

@Composable
fun AiQuizScreen(
    state: AiSpriteUiState,
    viewModel: AiSpriteViewModel,
    onResultAnchorBoundsChanged: (Rect) -> Unit = {}
) {
    val result = state.quizResult
    if (result != null) {
        QuizResultScreen(
            result = result,
            quiz = state.quiz,
            answers = state.quizAnswers,
            onReplay = viewModel::replayQuiz,
            onResultAnchorBoundsChanged = onResultAnchorBoundsChanged
        )
        return
    }

    if (!state.quizStarted) {
        QuizPreviewScreen(
            movies = state.quizPreviewMovies,
            replacementCount = state.quizReplacementCount,
            replaceAvailable = state.quizReplaceAvailable,
            isLoading = state.isLoading,
            onReplace = viewModel::replaceQuizMovie,
            onStart = viewModel::startQuiz
        )
        return
    }

    val quiz = state.quiz
    if (quiz == null || quiz.questions.isEmpty()) {
        QuizUnavailable()
        return
    }

    val questionIndex = state.quizIndex.coerceIn(0, quiz.questions.lastIndex)
    val question = quiz.questions[questionIndex]
    val answer = state.quizAnswers[question.id]
    val progress = quizProgress(questionIndex + 1, quiz.questions.size)
    val unanswered = unansweredQuizCount(quiz, state.quizAnswers)
    var unansweredConfirmVisible by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(quiz.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(5.dp))
            if (quiz.subtitle.isNotBlank()) {
                Text(quiz.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (quiz.mediaTitles.isNotEmpty()) {
                Text(
                    stringResource(R.string.ai_quiz_spoiler, quiz.mediaTitles.joinToString("、")),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.ai_quiz_progress, questionIndex + 1, quiz.questions.size), style = MaterialTheme.typography.labelMedium)
                }
                // 已答数常驻，用户能看出自己漏了几题，不用翻回去数
                Text(
                    stringResource(
                        R.string.ai_quiz_answered_count,
                        quiz.questions.size - unanswered,
                        quiz.questions.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            QuizQuestionCard(
                question = question,
                selectedOptionIds = answer?.selectedOptionIds.orEmpty(),
                textAnswer = answer?.textAnswer.orEmpty(),
                onSingleChoice = { selected -> viewModel.setQuizAnswer(question.id, listOf(selected)) },
                onMultipleChoice = { selected -> viewModel.setQuizAnswer(question.id, selected) },
                onTextAnswer = { text -> viewModel.setQuizTextAnswer(question.id, text) }
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = viewModel::previousQuestion,
                    enabled = questionIndex > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ai_quiz_previous))
                }
                if (questionIndex == quiz.questions.lastIndex) {
                    Button(
                        onClick = {
                            // 有漏题先提醒：未作答直接算 0 分，提交后没有回头路
                            if (unanswered > 0) unansweredConfirmVisible = true else viewModel.submitQuiz()
                        },
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ai_quiz_finish))
                    }
                } else {
                    Button(
                        onClick = viewModel::nextQuestion,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ai_quiz_next))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (unansweredConfirmVisible) {
        AlertDialog(
            onDismissRequest = { unansweredConfirmVisible = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.ai_quiz_unanswered_title)) },
            text = { Text(stringResource(R.string.ai_quiz_unanswered_message, unanswered)) },
            confirmButton = {
                TextButton(onClick = {
                    unansweredConfirmVisible = false
                    viewModel.submitQuiz()
                }) {
                    Text(stringResource(R.string.ai_quiz_submit_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { unansweredConfirmVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun QuizPreviewScreen(
    movies: List<AiWatchedTitleDto>,
    replacementCount: Int,
    replaceAvailable: Boolean,
    isLoading: Boolean,
    onReplace: (Int) -> Unit,
    onStart: () -> Unit
) {
    if (movies.isEmpty()) {
        QuizUnavailable()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                stringResource(R.string.ai_quiz_preview_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ai_quiz_preview_count, movies.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        itemsIndexed(movies, key = { _, movie -> "${movie.mediaType}:${movie.mediaId}" }) { index, movie ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${index + 1}", fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(movie.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        movie.year?.let {
                            Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(
                        onClick = { onReplace(index) },
                        // 候选被用光（已看正好 7 部）时必须禁用，否则是个点了没反应的死按钮
                        enabled = replaceAvailable && !isLoading
                    ) {
                        Text(stringResource(R.string.ai_quiz_replace))
                    }
                }
            }
        }
        item {
            Text(
                if (!replaceAvailable && remainingQuizReplacements(replacementCount) > 0) {
                    stringResource(R.string.ai_quiz_replace_exhausted)
                } else {
                    stringResource(R.string.ai_quiz_replace_remaining, remainingQuizReplacements(replacementCount))
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Button(
                onClick = onStart,
                enabled = movies.size == 7 && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ai_quiz_start))
            }
        }
    }
}

@Composable
private fun QuizQuestionCard(
    question: AiQuizQuestion,
    selectedOptionIds: List<String>,
    textAnswer: String,
    onSingleChoice: (String) -> Unit,
    onMultipleChoice: (List<String>) -> Unit,
    onTextAnswer: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(question.prompt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            question.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = quote,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            question.mediaTitle?.takeIf { it.isNotBlank() }?.let { title ->
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            when (question.type) {
                AiQuizQuestionType.SINGLE -> {
                    Text(stringResource(R.string.ai_quiz_single_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    question.options.forEach { option ->
                        ChoiceRow(
                            text = option.text,
                            selected = option.id in selectedOptionIds,
                            multiple = false,
                            onClick = { onSingleChoice(option.id) }
                        )
                    }
                }
                AiQuizQuestionType.MULTIPLE -> {
                    Text(stringResource(R.string.ai_quiz_multiple_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    question.options.forEach { option ->
                        ChoiceRow(
                            text = option.text,
                            selected = option.id in selectedOptionIds,
                            multiple = true,
                            onClick = {
                                val next = if (option.id in selectedOptionIds) {
                                    selectedOptionIds - option.id
                                } else {
                                    selectedOptionIds + option.id
                                }
                                onMultipleChoice(next)
                            }
                        )
                    }
                }
                AiQuizQuestionType.SHORT -> {
                    Text(stringResource(R.string.ai_quiz_short_answer_optional), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = textAnswer,
                        onValueChange = onTextAnswer,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        placeholder = { Text(stringResource(R.string.ai_quiz_short_answer_placeholder)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    text: String,
    selected: Boolean,
    multiple: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (multiple) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        } else {
            RadioButton(selected = selected, onClick = onClick)
        }
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QuizResultScreen(
    result: AiQuizResult,
    quiz: AiQuiz?,
    answers: Map<String, AiQuizAnswer>,
    onReplay: () -> Unit,
    onResultAnchorBoundsChanged: (Rect) -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { onResultAnchorBoundsChanged(it.boundsInRoot()) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.ai_quiz_result_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ai_quiz_score, result.score), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                    Text(
                        stringResource(R.string.ai_quiz_correct_count, result.correctCount, result.totalQuestions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        if (result.summary.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(result.summary, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        items(result.questionResults, key = { it.questionId }) { item ->
            val question = quiz?.questions?.firstOrNull { it.id == item.questionId }
            val answer = answers[item.questionId]
            val selectedLabels = if (question != null) quizAnswerLabels(question, answer) else emptyList()
            val userAnswerText = when {
                selectedLabels.isNotEmpty() -> selectedLabels.joinToString("、")
                !answer?.textAnswer.isNullOrBlank() -> answer.textAnswer.trim()
                else -> null
            }
            val correctAnswerText = quizCorrectAnswerText(question, item)
                ?: userAnswerText?.takeIf { item.correct }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = if (item.correct) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    // 错题卡底色是 errorContainer，文字必须用 onErrorContainer；
                    // 之前用 error / primary 压在 errorContainer 上既对比度低又撞色
                    val labelColor = if (item.correct) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                    Text(
                        text = stringResource(R.string.ai_quiz_question_score, item.score),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = labelColor
                    )
                    Text(
                        stringResource(R.string.ai_quiz_answer_review),
                        style = MaterialTheme.typography.labelMedium,
                        color = labelColor
                    )
                    correctAnswerText?.let { text ->
                        Text(
                            stringResource(R.string.ai_quiz_correct_answer, text),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (userAnswerText != null && userAnswerText != correctAnswerText) {
                        Text(
                            stringResource(R.string.ai_quiz_your_answer, userAnswerText),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (userAnswerText == null) {
                        Text(stringResource(R.string.ai_quiz_unanswered), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (item.explanation.isNotBlank()) {
                        Text(
                            stringResource(R.string.ai_quiz_explanation_format, item.explanation),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
        item {
            Button(onClick = onReplay, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_quiz_replay))
            }
        }
    }
}

@Composable
private fun QuizUnavailable() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.ai_feature_unavailable),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
