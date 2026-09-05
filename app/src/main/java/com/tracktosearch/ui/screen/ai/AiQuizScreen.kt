package com.tracktosearch.ui.screen.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizDifficulty
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizResult
import com.tracktosearch.data.ai.AiWatchedTitleDto
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import kotlinx.coroutines.delay

/** 单选题选中后展示选中态的时长，之后自动进入下一题。 */
private const val QUIZ_AUTO_ADVANCE_DELAY_MS = 600L

/** 闯关流程阶段，AnimatedContent 按 phase 切换预览/答题/出分。 */
private enum class QuizPhase { PREVIEW, QUESTIONS, RESULT, UNAVAILABLE }

@Composable
fun AiQuizScreen(
    state: AiSpriteUiState,
    viewModel: AiSpriteViewModel,
    onResultAnchorBoundsChanged: (Rect) -> Unit = {}
) {
    val quiz = state.quiz
    val phase = when {
        state.quizResult != null -> QuizPhase.RESULT
        !state.quizStarted -> QuizPhase.PREVIEW
        quiz == null || quiz.questions.isEmpty() -> QuizPhase.UNAVAILABLE
        else -> QuizPhase.QUESTIONS
    }
    // 此前预览→答题→出分是 if/else 直切，这里统一 fade+slide 转场（300ms 标准缓动）
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            val enter = fadeIn(tween(durationMillis = 300, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(durationMillis = 300, easing = FastOutSlowInEasing)) { it / 6 }
            val exit = fadeOut(tween(durationMillis = 200, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(tween(durationMillis = 300, easing = FastOutSlowInEasing)) { -it / 6 }
            enter togetherWith exit
        },
        label = "quiz_phase"
    ) { target ->
        when (target) {
            QuizPhase.RESULT -> QuizResultScreen(
                result = state.quizResult!!,
                quiz = state.quiz,
                answers = state.quizAnswers,
                feedbackDifficulty = state.quizFeedbackDifficulty,
                onFeedback = viewModel::submitQuizDifficultyFeedback,
                onReplay = viewModel::replayQuiz,
                onResultAnchorBoundsChanged = onResultAnchorBoundsChanged
            )
            QuizPhase.PREVIEW -> QuizPreviewScreen(
                movies = state.quizPreviewMovies,
                localizedTitles = state.quizPreviewLocalizedTitles,
                replacementCount = state.quizReplacementCount,
                replaceAvailable = state.quizReplaceAvailable,
                isLoading = state.isLoading,
                onReplace = viewModel::replaceQuizMovie,
                onStart = viewModel::startQuiz,
                onRedrawAll = viewModel::redrawAllQuizPreview
            )
            QuizPhase.UNAVAILABLE -> if (state.isLoading) {
                QuizLoadingPlaceholder()
            } else {
                QuizUnavailable()
            }
            QuizPhase.QUESTIONS -> QuizQuestionScreen(
                state = state,
                viewModel = viewModel,
                quiz = quiz!!
            )
        }
    }
}

@Composable
private fun QuizQuestionScreen(
    state: AiSpriteUiState,
    viewModel: AiSpriteViewModel,
    quiz: AiQuiz
) {
    val questionIndex = state.quizIndex.coerceIn(0, quiz.questions.lastIndex)
    val question = quiz.questions[questionIndex]
    val answer = state.quizAnswers[question.id]
    val progress = quizProgress(questionIndex + 1, quiz.questions.size)
    val unanswered = unansweredQuizCount(quiz, state.quizAnswers)
    var unansweredConfirmVisible by remember(quiz.quizId, question.id) { mutableStateOf(false) }
    val haptics = rememberAppHaptics()

    LaunchedEffect(unanswered, state.isLoading) {
        if (unanswered == 0 || state.isLoading) {
            // 题目已补齐、提交或刷新开始后，不能继续显示上一状态的确认框。
            unansweredConfirmVisible = false
        }
    }

    // 自动跳题是加速路径不是替换：仅单选题从无到有选中时触发，
    // 600ms 展示选中态（判分在服务端，本地没有对错反馈）后切下一题；手动「下一题」仍保留。
    // 回头改已答的题不自动跳，最后一题答完停在「提交」前。
    val initiallyAnswered = remember(question.id) {
        answer?.selectedOptionIds?.isNotEmpty() == true
    }
    LaunchedEffect(question.id, answer?.selectedOptionIds) {
        if (initiallyAnswered) return@LaunchedEffect
        val selected = answer?.selectedOptionIds.orEmpty()
        if (question.type != AiQuizQuestionType.SINGLE || selected.isEmpty()) return@LaunchedEffect
        if (questionIndex >= quiz.questions.lastIndex) return@LaunchedEffect
        delay(QUIZ_AUTO_ADVANCE_DELAY_MS)
        // delay 期间用户可能已手动切题：核对当前索引仍是本题再跳，避免连跳两题
        if (viewModel.uiState.value.quizIndex == questionIndex) {
            viewModel.nextQuestion()
        }
    }

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
                    stringResource(
                        R.string.ai_quiz_spoiler,
                        quiz.mediaTitles.joinToString(stringResource(R.string.ai_quiz_media_separator))
                    ),
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
                    onClick = {
                        haptics.tap()
                        viewModel.previousQuestion()
                    },
                    enabled = questionIndex > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ai_quiz_previous))
                }
                if (questionIndex == quiz.questions.lastIndex) {
                    Button(
                        onClick = {
                            haptics.tap()
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
                        onClick = {
                            haptics.tap()
                            viewModel.nextQuestion()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ai_quiz_next))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    if (unansweredConfirmVisible && unanswered > 0 && !state.isLoading) {
        AlertDialog(
            onDismissRequest = { unansweredConfirmVisible = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.ai_quiz_unanswered_title)) },
            text = { Text(stringResource(R.string.ai_quiz_unanswered_message, unanswered)) },
            confirmButton = {
                // AlertDialog 的槽是独立 subcomposition（Dialog 有自己的宿主 View），单独取一份
                val confirmHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    confirmHaptics.tap()
                    unansweredConfirmVisible = false
                    viewModel.submitQuiz()
                }) {
                    Text(stringResource(R.string.ai_quiz_submit_anyway))
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    unansweredConfirmVisible = false
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun QuizPreviewScreen(
    movies: List<AiWatchedTitleDto>,
    localizedTitles: Map<String, String>,
    replacementCount: Int,
    replaceAvailable: Boolean,
    isLoading: Boolean,
    onReplace: (Int) -> Unit,
    onStart: () -> Unit,
    onRedrawAll: () -> Unit
) {
    if (movies.isEmpty()) {
        if (isLoading) {
            QuizLoadingPlaceholder()
        } else {
            QuizUnavailable()
        }
        return
    }
    val haptics = rememberAppHaptics()
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
        itemsIndexed(movies, key = { _, movie -> quizMediaKey(movie.mediaType, movie.mediaId) }) { index, movie ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
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
                        Text(
                            text = localizedQuizPreviewTitle(movie, localizedTitles),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        movie.year?.let {
                            Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            // 7 行里每行一个，成片出现的行内操作按列表项那档给轻一记
                            haptics.lightTap()
                            onReplace(index)
                        },
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 已看不足 7 部时开始按钮禁用，得说明原因而不是留一个死按钮
                if (movies.size < 7) {
                    Text(
                        text = stringResource(R.string.ai_quiz_need_more_movies),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        haptics.tap()
                        onStart()
                    },
                    enabled = movies.size == 7 && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.ai_quiz_start))
                }
                // 「全部重抽」是次级操作：OutlinedButton 不抢主按钮视觉；非破坏性（还没作答），无需二次确认
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onRedrawAll()
                    },
                    // 与「换一部」共用可用性判断：没有未用候选时重抽必然原样返回，禁用免得变死按钮
                    enabled = replaceAvailable && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.ai_quiz_redraw_all))
                }
            }
        }
    }
}

/**
 * 预览列表只替换展示标题；传给 Worker 的仍是 [AiWatchedTitleDto.title] 原始值。
 * 类型参与 key，避免电影和剧集使用相同 ID 时互相覆盖本地化标题。
 */
private fun localizedQuizPreviewTitle(
    movie: AiWatchedTitleDto,
    localizedTitles: Map<String, String>
): String = localizedTitles[quizMediaKey(movie.mediaType, movie.mediaId)]
    ?.takeIf { it.isNotBlank() }
    // 兼容旧状态中仍以原始 mediaType 保存的标题映射。
    ?: localizedTitles["${movie.mediaType}:${movie.mediaId}"]
        ?.takeIf { it.isNotBlank() }
    ?: movie.title


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
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(question.prompt, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            question.quote?.takeIf { it.isNotBlank() }?.let { quote ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
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
    // 多选题的 state 是 selectedOptionIds 的加减，「加上 / 去掉」的方向感有意义 → toggle；
    // 单选题只是把选中位挪一格，没有「取消」这回事 → segmentTick
    val rowSemantic = when {
        !multiple -> HapticSemantic.SEGMENT_TICK
        selected -> HapticSemantic.TOGGLE_OFF
        else -> HapticSemantic.TOGGLE_ON
    }
    // 勾选框 / 单选钮自己消费点击，命中它们时父行的 clickable 不会跟着触发，
    // 所以两边各发一记不会双震；反过来只给父行会漏掉「正好点在钮上」这条路
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(semantic = rowSemantic, onClick = onClick)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (multiple) {
            Checkbox(
                checked = selected,
                onCheckedChange = { checked ->
                    haptics.toggle(checked)
                    onClick()
                }
            )
        } else {
            RadioButton(
                selected = selected,
                onClick = {
                    haptics.segmentTick()
                    onClick()
                }
            )
        }
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun QuizResultScreen(
    result: AiQuizResult,
    quiz: AiQuiz?,
    answers: Map<String, AiQuizAnswer>,
    feedbackDifficulty: AiQuizDifficulty?,
    onFeedback: (AiQuizDifficulty) -> Unit,
    onReplay: () -> Unit,
    onResultAnchorBoundsChanged: (Rect) -> Unit = {}
) {
    // 已选难度用 rememberSaveable 存 name：页面重组/进程重建不丢「已反馈」标记；
    // quizId 变化（新一轮）时旧值失效自动重置
    var feedbackName by rememberSaveable(result.quizId) { mutableStateOf<String?>(feedbackDifficulty?.name) }
    val feedbackSelected = AiQuizDifficulty.fromName(feedbackName)
    val haptics = rememberAppHaptics()
    val answerSeparator = stringResource(R.string.ai_quiz_media_separator)

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
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.ai_quiz_result_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.ai_quiz_score, result.score), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                    Text(
                        stringResource(R.string.ai_quiz_correct_count, result.correctCount, result.totalQuestions) +
                            " · " +
                            stringResource(R.string.ai_quiz_accuracy, quizAccuracyPercent(result)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    // 按得分段给一句短评语，可爱不啰嗦
                    Text(
                        stringResource(quizScoreCommentRes(result.score)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    // 13 题一览条形：每题等宽一小段，答对主题色、答错弱化灰，一眼看出分布
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        quizOrderedQuestionResults(quiz, result).forEach { questionResult ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        if (questionResult.correct) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)
                                        }
                                    )
                            )
                        }
                    }
                }
            }
        }
        item {
            QuizDifficultyFeedbackSection(
                selected = feedbackSelected,
                onSelect = { difficulty ->
                    if (feedbackSelected == null) {
                        feedbackName = difficulty.name
                        onFeedback(difficulty)
                    }
                }
            )
        }
        if (result.summary.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
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
                selectedLabels.isNotEmpty() -> selectedLabels.joinToString(answerSeparator)
                !answer?.textAnswer.isNullOrBlank() -> answer.textAnswer.trim()
                else -> null
            }
            val correctAnswerText = quizCorrectAnswerText(question, item, answerSeparator)
                ?: userAnswerText?.takeIf { item.correct }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 答对/答错图标：答对主题色；答错沿用错题卡的前景色（在 errorContainer 上保证可读）
                        Icon(
                            imageVector = if (item.correct) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = labelColor
                        )
                        Text(
                            text = stringResource(R.string.ai_quiz_question_score, item.score),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = labelColor
                        )
                    }
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
            Button(
                onClick = {
                    haptics.tap()
                    onReplay()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ai_quiz_replay))
            }
        }
    }
}

/** 出分页难度反馈区：三个选项一次性的轻量反馈，跳过=直接离开，无催促。 */
@Composable
private fun QuizDifficultyFeedbackSection(
    selected: AiQuizDifficulty?,
    onSelect: (AiQuizDifficulty) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.ai_quiz_feedback_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeedbackOption(
                    label = stringResource(R.string.ai_quiz_feedback_easy),
                    difficulty = AiQuizDifficulty.EASY,
                    selected = selected,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
                FeedbackOption(
                    label = stringResource(R.string.ai_quiz_feedback_just_right),
                    difficulty = AiQuizDifficulty.JUST_RIGHT,
                    selected = selected,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
                FeedbackOption(
                    label = stringResource(R.string.ai_quiz_feedback_hard),
                    difficulty = AiQuizDifficulty.HARD,
                    selected = selected,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f)
                )
            }
            if (selected != null) {
                // 已反馈：整组收起语义的确认文案；本地立即生效，异步静默提交
                Text(
                    stringResource(R.string.ai_quiz_feedback_done),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FeedbackOption(
    label: String,
    difficulty: AiQuizDifficulty,
    selected: AiQuizDifficulty?,
    onSelect: (AiQuizDifficulty) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = selected == difficulty
    val answered = selected != null
    if (isSelected) {
        // 选中态用主题 primary 高亮（项目选中态规范）；onClick 留空防重复提交
        Button(
            onClick = {},
            modifier = modifier
        ) {
            Text(label, maxLines = 1, textAlign = TextAlign.Center)
        }
    } else {
        // 三档难度只能选一个，是单选而不是多选 → segmentTick
        val haptics = rememberAppHaptics()
        OutlinedButton(
            onClick = {
                haptics.segmentTick()
                onSelect(difficulty)
            },
            // 已反馈后其余选项禁用，防重复提交
            enabled = !answered,
            modifier = modifier
        ) {
            Text(label, maxLines = 1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun QuizLoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.ai_feature_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

/** 正确率百分比（取整），总数为 0 时按 0 处理。 */
private fun quizAccuracyPercent(result: AiQuizResult): Int =
    if (result.totalQuestions > 0) {
        result.correctCount * 100 / result.totalQuestions
    } else {
        0
    }

/** 按得分段选择评语资源：≥90 满分宣言 / ≥70 相当不错 / ≥40 有潜力 / <40 慢慢来。 */
@androidx.annotation.StringRes
private fun quizScoreCommentRes(score: Int): Int = when {
    score >= 90 -> R.string.ai_quiz_comment_perfect
    score >= 70 -> R.string.ai_quiz_comment_great
    score >= 40 -> R.string.ai_quiz_comment_potential
    else -> R.string.ai_quiz_comment_gentle
}

/** 条形图与题目顺序对齐：优先按 quiz.questions 顺序排 questionResults，无 quiz 时按返回顺序。 */
private fun quizOrderedQuestionResults(
    quiz: AiQuiz?,
    result: AiQuizResult
): List<com.tracktosearch.data.ai.AiQuizQuestionResult> {
    val questions = quiz?.questions ?: return result.questionResults
    val byQuestionId = result.questionResults.associateBy { it.questionId }
    return questions.mapNotNull { byQuestionId[it.id] }
}
