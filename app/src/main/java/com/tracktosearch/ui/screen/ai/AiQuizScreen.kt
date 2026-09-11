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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.tracktosearch.ui.theme.floatingDialogColor
import java.util.Locale
import kotlinx.coroutines.delay

/** 单选题选中后展示选中态的时长，之后自动进入下一题。 */
// 权衡：单选没有本地判分反馈，600ms 实测看不清选中就跳；提到 1000ms 并配合
// ChoiceRow 的选中高亮，用户来得及确认自己选了哪项，又不必每题手动「下一题」拖慢 13 题节奏。
private const val QUIZ_AUTO_ADVANCE_DELAY_MS = 1000L

/** 闯关流程阶段，AnimatedContent 按 phase 切换预览/生成中/答题/出分。 */
private enum class QuizPhase { PREVIEW, PREPARING, QUESTIONS, RESULT, UNAVAILABLE }

@Composable
fun AiQuizScreen(
    state: AiSpriteUiState,
    viewModel: AiSpriteViewModel,
    onResultAnchorBoundsChanged: (Rect) -> Unit = {}
) {
    val quiz = state.quiz
    val phase = when {
        state.quizResult != null -> QuizPhase.RESULT
        // 用户点了开始、题目还没到：等待页必须现在就在，否则「开始」只是把按钮灰掉
        state.quizGenerating -> QuizPhase.PREPARING
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
                prepareState = state.quizPrepareState,
                isLoading = state.isLoading,
                onStart = viewModel::startQuiz
            )
            // 生成中的等待页与「有开始没题目」兜底共用同一份进度视图
            QuizPhase.PREPARING,
            QuizPhase.UNAVAILABLE -> if (target == QuizPhase.UNAVAILABLE && !state.isLoading) {
                QuizUnavailable()
            } else {
                QuizLoadingPlaceholder(
                    stage = state.quizStage,
                    progress = quizStreamProgressFraction(
                        stage = state.quizStage,
                        chars = state.quizStageChars,
                        expectedChars = state.quizStageExpectedChars
                    ),
                    startedAtMillis = state.quizRequestStartedAtMillis,
                    onCancel = viewModel::cancelActiveFeatureRequest
                )
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
    // 简答题是开放思考项，不计入必答进度；选择题完成度单独显示，避免跳过简答时制造焦虑。
    val unansweredRequired = unansweredRequiredQuizCount(quiz, state.quizAnswers)
    val requiredQuestionCount = quiz.questions.count { it.type != AiQuizQuestionType.SHORT }
    var unansweredConfirmVisible by remember(quiz.quizId, question.id) { mutableStateOf(false) }
    val haptics = rememberAppHaptics()

    LaunchedEffect(unansweredRequired, state.isLoading) {
        if (unansweredRequired == 0 || state.isLoading) {
            // 题目已补齐、提交或刷新开始后，不能继续显示上一状态的确认框。
            unansweredConfirmVisible = false
        }
    }

    // 自动跳题是加速路径不是替换：仅单选题从无到有选中时触发，
    // 展示选中态（判分在服务端，本地没有对错反馈）后切下一题；手动「下一题」仍保留。
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            // 顶栏已是功能标题，页内不再重复 quiz.title（Worker 与顶栏同串，纯重复）。
            // 进度 + 已答数合并一行放最上面，先给位置感再看内容。
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.ai_quiz_progress, questionIndex + 1, quiz.questions.size), style = MaterialTheme.typography.labelMedium)
                    Text(
                        stringResource(
                            R.string.ai_quiz_answered_count,
                            requiredQuestionCount - unansweredRequired,
                            requiredQuestionCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 片名全空时不渲染剧透行，避免出现「本轮会涉及：，包含剧透」的空壳文案
                val roundTitles = localizedQuizRoundTitles(
                    mediaTitles = quiz.mediaTitles,
                    movies = state.quizPreviewMovies,
                    localizedTitles = state.quizPreviewLocalizedTitles
                ).ifEmpty { quiz.mediaTitles.filter { it.isNotBlank() } }
                if (roundTitles.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.ai_quiz_spoiler,
                            roundTitles.joinToString(stringResource(R.string.ai_quiz_media_separator))
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        item {
            QuizQuestionCard(
                question = question,
                stageRes = quizStageLabelRes(questionIndex, quiz.questions.size),
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
                        // 回退是次级导航，比「下一题」轻一档
                        haptics.lightTap()
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
                            if (unansweredRequired > 0) unansweredConfirmVisible = true else viewModel.submitQuiz()
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

    if (unansweredConfirmVisible && unansweredRequired > 0 && !state.isLoading) {
        AlertDialog(
            onDismissRequest = { unansweredConfirmVisible = false },
            containerColor = floatingDialogColor(),
            title = { Text(stringResource(R.string.ai_quiz_unanswered_title)) },
            text = { Text(stringResource(R.string.ai_quiz_unanswered_message, unansweredRequired)) },
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

/**
 * 「今日考点」预览：只读列出今天出题用的影视。
 *
 * 片单由客户端按 (用户, 当天) 稳定选出并交给服务端，所以这里不再提供换片/重抽 ——
 * 用户看到的就是今天这一套题实际会用到的片。顶部一行说明题目准备到哪一步：
 * 已就绪（点开始秒开）/ 准备中（后台生成）/ 未就绪（点开始会现场生成，需等待）。
 */
@Composable
private fun QuizPreviewScreen(
    movies: List<AiWatchedTitleDto>,
    localizedTitles: Map<String, String>,
    prepareState: AiQuizPrepareState,
    isLoading: Boolean,
    onStart: () -> Unit
) {
    if (movies.isEmpty()) {
        if (isLoading) {
            // 预览阶段还没有流式进度（连片单都没定），退化为不确定进度条
            QuizLoadingPlaceholder(stage = null, progress = 0f, startedAtMillis = 0L)
        } else {
            QuizUnavailable()
        }
        return
    }
    val haptics = rememberAppHaptics()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                stringResource(R.string.ai_quiz_preview_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.ai_quiz_preview_count, movies.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            quizPrepareLabelRes(prepareState)?.let { labelRes ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = when (prepareState) {
                        AiQuizPrepareState.READY -> MaterialTheme.colorScheme.primary
                        AiQuizPrepareState.FAILED -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        itemsIndexed(movies, key = { _, movie -> quizMediaKey(movie.mediaType, movie.mediaId) }) { index, movie ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(34.dp),
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
                }
            }
        }
        item {
            // 已看不足 7 部时开始按钮禁用，说明原因而不是留一个死按钮
            if (movies.size < QUIZ_MOVIE_COUNT) {
                Text(
                    text = stringResource(R.string.ai_quiz_need_more_movies),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    haptics.tap()
                    onStart()
                },
                enabled = movies.size == QUIZ_MOVIE_COUNT && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.ai_quiz_start), maxLines = 1)
            }
        }
    }
}

/** 准备状态文案；IDLE 不占位（离线或还没开始准备时不必多一行字）。 */
@androidx.annotation.StringRes
private fun quizPrepareLabelRes(state: AiQuizPrepareState): Int? = when (state) {
    AiQuizPrepareState.READY -> R.string.ai_quiz_prepare_ready
    AiQuizPrepareState.PREPARING -> R.string.ai_quiz_prepare_running
    AiQuizPrepareState.FAILED -> R.string.ai_quiz_prepare_failed
    AiQuizPrepareState.IDLE -> null
}

private fun quizDifficultyLabelRes(difficulty: String): Int = when (difficulty.trim().lowercase(Locale.ROOT)) {
    "easy", "warmup", "basic" -> R.string.ai_quiz_difficulty_easy
    "hard", "challenge", "advanced" -> R.string.ai_quiz_difficulty_hard
    else -> R.string.ai_quiz_difficulty_medium
}

private fun quizStageLabelRes(index: Int, count: Int): Int {
    // 固定 13 题时为 5 / 5 / 3；异常题量也保持三个阶段至少各有可读边界。
    val warmupEnd = (count * 5 / 13).coerceAtLeast(1)
    val deepEnd = (count * 10 / 13).coerceAtLeast(warmupEnd + 1)
    return when {
        index < warmupEnd -> R.string.ai_quiz_stage_warmup
        index < deepEnd -> R.string.ai_quiz_stage_deep
        else -> R.string.ai_quiz_stage_challenge
    }
}

@Composable
private fun QuizQuestionMeta(question: AiQuizQuestion, stageRes: Int) {
    // 答题期元信息只给「阶段 + 难度 + 依据片名」三样：考点/学习视角/takeaway/证据
    // 都在答题前剧透答案方向，还把题目挤到首屏外；它们属于解析，复盘页再看。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                stringResource(stageRes),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                stringResource(quizDifficultyLabelRes(question.difficulty)),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        (question.sourceTitle ?: question.mediaTitle)?.takeIf { it.isNotBlank() }?.let { sourceTitle ->
            Text(
                stringResource(R.string.ai_quiz_source_format, sourceTitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun QuizQuestionCard(
    question: AiQuizQuestion,
    stageRes: Int,
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
            QuizQuestionMeta(question = question, stageRes = stageRes)
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
    // 选中行给整块浅色底：单选自动跳题前有明确高亮，多选也能看清已勾选项
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .hapticClickable(semantic = rowSemantic, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
    val orderedResults = quizOrderedQuestionResults(quiz, result)
    val hasOptionalReflection = quiz?.questions?.any { it.type == AiQuizQuestionType.SHORT } == true
    val objectiveResults = if (quiz == null) {
        orderedResults
    } else {
        orderedResults.filterNot { questionResult ->
            quiz.questions.firstOrNull { it.id == questionResult.questionId }?.type == AiQuizQuestionType.SHORT
        }
    }
    val objectiveCorrectCount = objectiveResults.count { it.correct }
    val objectiveTotal = if (quiz == null) result.totalQuestions else objectiveResults.size
    val objectiveAccuracy = if (objectiveTotal > 0) objectiveCorrectCount * 100 / objectiveTotal else 0

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
                    if (hasOptionalReflection) {
                        Text(
                            stringResource(R.string.ai_quiz_total_score_with_optional_reflection, result.score),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            stringResource(R.string.ai_quiz_optional_reflection_result),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(stringResource(R.string.ai_quiz_score, result.score), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                    }
                    Text(
                        stringResource(R.string.ai_quiz_correct_count, objectiveCorrectCount, objectiveTotal) +
                            " · " +
                            stringResource(R.string.ai_quiz_accuracy, objectiveAccuracy),
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
                    // 13 题一览条形：简答题使用中性段，避免把可选表达误读为客观对错。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        orderedResults.forEach { questionResult ->
                            val isOptional = quiz?.questions?.firstOrNull { it.id == questionResult.questionId }?.type == AiQuizQuestionType.SHORT
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        when {
                                            isOptional -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
                                            questionResult.correct -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f)
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
            val isOptionalReflection = question?.type == AiQuizQuestionType.SHORT
            val answer = answers[item.questionId]
            val selectedLabels = if (question != null) quizAnswerLabels(question, answer) else emptyList()
            val userAnswerText = when {
                selectedLabels.isNotEmpty() -> joinQuizOptionTexts(selectedLabels, answerSeparator)
                !answer?.textAnswer.isNullOrBlank() -> answer.textAnswer.trim()
                else -> null
            }
            val correctAnswerText = quizCorrectAnswerText(question, item, answerSeparator)
                ?: userAnswerText?.takeIf { item.correct }
            val answerRationale = question?.answerRationale?.trim().orEmpty()
                .ifBlank { item.answerRationale.trim() }
            val distractorRationale = question?.distractorRationale?.trim().orEmpty()
                .ifBlank { item.distractorRationale.trim() }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (item.correct) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    // 错题卡底色是 errorContainer，文字必须用 onErrorContainer；
                    // 之前用 error / primary 压在 errorContainer 上既对比度低又撞色
                    val labelColor = if (item.correct) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                    quiz?.let { activeQuiz ->
                        question?.let { currentQuestion ->
                            QuizQuestionMeta(
                                question = currentQuestion,
                                stageRes = quizStageLabelRes(
                                    activeQuiz.questions.indexOfFirst { candidate -> candidate.id == currentQuestion.id }.coerceAtLeast(0),
                                    activeQuiz.questions.size
                                )
                            )
                        }
                    }
                    if (isOptionalReflection) {
                        Text(
                            stringResource(R.string.ai_quiz_optional_reflection_review),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = labelColor
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
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
                    }
                    if (!isOptionalReflection) {
                        Text(
                            stringResource(R.string.ai_quiz_answer_review),
                            style = MaterialTheme.typography.labelMedium,
                            color = labelColor
                        )
                    }
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
                    if (answerRationale.isNotBlank()) {
                        Text(
                            stringResource(R.string.ai_quiz_answer_rationale_format, answerRationale),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (item.explanation.isNotBlank()) {
                        Text(
                            stringResource(R.string.ai_quiz_explanation_format, item.explanation),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    // 从答题页挪来的学习结论：答题前展示会剧透方向，复盘时它是「带走的知识点」
                    question?.learningTakeaway?.trim()?.takeIf { it.isNotBlank() }?.let { takeaway ->
                        Text(
                            stringResource(R.string.ai_quiz_learning_takeaway_format, takeaway),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (distractorRationale.isNotBlank()) {
                        Text(
                            stringResource(R.string.ai_quiz_distractor_rationale_format, distractorRationale),
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

/**
 * 出题等待页。
 *
 * 两阶段实测 3~6 分钟，干转圈会让人以为卡死：这里显示真实阶段、按已生成字符数推进的
 * 进度条、以及已等待时长。进度值来自服务端流式事件（见 AiRepository.getQuizStream），
 * 没拿到阶段信息时退化为不确定进度条。
 */
@Composable
private fun QuizLoadingPlaceholder(
    stage: com.tracktosearch.data.ai.AiQuizStage?,
    progress: Float,
    startedAtMillis: Long,
    onCancel: () -> Unit = {}
) {
    // 每秒刷新已用时长：只驱动这一个文本，避免把整页拖进高频重组
    val elapsedSeconds by produceState(0L, startedAtMillis) {
        while (true) {
            value = if (startedAtMillis > 0L) {
                ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L)
            } else {
                0L
            }
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val stageLabel = when (stage) {
        com.tracktosearch.data.ai.AiQuizStage.UNITS -> stringResource(R.string.ai_quiz_stage_units)
        com.tracktosearch.data.ai.AiQuizStage.REVIEW -> stringResource(R.string.ai_quiz_stage_review)
        null -> stringResource(R.string.ai_quiz_wait_title)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            if (stage == null) {
                CircularProgressIndicator()
            } else {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(56.dp)
                )
            }
            Text(
                text = stageLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            // 两阶段时间线：让「还要等多久」有结构，而不是一句抽象的「生成中」
            QuizStageTimeline(current = stage)
            Text(
                text = stringResource(R.string.ai_quiz_wait_hint),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (startedAtMillis > 0L) {
                Text(
                    text = stringResource(
                        R.string.ai_quiz_elapsed,
                        formatQuizElapsed(elapsedSeconds)
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 长等待必须给出路：等不下去时回到预览页，不把用户锁在转圈里。
            // 预生成不会被这次取消打断，「今日考点」页随后会继续显示准备进度。
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.ai_quiz_wait_cancel))
            }
        }
    }
}

/**
 * 两阶段时间线（提炼单元 → 逐题生成）。
 *
 * 只做「已完成 / 进行中 / 待开始」三态，不显示百分比数字：进度本来是按输出字符数估的，
 * 给个假精确的数值反而会让人盯着一个不动的数字。修复轮会重跑同一阶段，此时它回到进行中。
 */
@Composable
private fun QuizStageTimeline(current: com.tracktosearch.data.ai.AiQuizStage?) {
    val stages = listOf(
        com.tracktosearch.data.ai.AiQuizStage.UNITS to R.string.ai_quiz_stage_units,
        com.tracktosearch.data.ai.AiQuizStage.REVIEW to R.string.ai_quiz_stage_review
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        stages.forEach { (stage, labelRes) ->
            val done = current == com.tracktosearch.data.ai.AiQuizStage.REVIEW &&
                stage == com.tracktosearch.data.ai.AiQuizStage.UNITS
            val active = current == stage
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                }
                            )
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        active -> MaterialTheme.colorScheme.onSurface
                        done -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    }
                )
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

/** 简答题可跳过，提交确认只统计必答的单选/多选题。 */
private fun unansweredRequiredQuizCount(
    quiz: AiQuiz,
    answers: Map<String, AiQuizAnswer>
): Int = quiz.questions.count { question ->
    if (question.type == AiQuizQuestionType.SHORT) return@count false
    val answer = answers[question.id]
    answer == null || (answer.selectedOptionIds.isEmpty() && answer.textAnswer.isNullOrBlank())
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
