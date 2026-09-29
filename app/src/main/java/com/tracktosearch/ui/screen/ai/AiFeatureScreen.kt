package com.tracktosearch.ui.screen.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.tracktosearch.R
import com.tracktosearch.ui.component.SubPageTopBar
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.util.ConnectivityObserver
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.AppBottomSheet
import com.tracktosearch.ui.component.bottomScrollFade
import com.tracktosearch.ui.theme.floatingSheetColor
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.component.ShimmerState
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiDailyKnowledgeContentFeedback
import com.tracktosearch.data.ai.AiDailyKnowledgeHistoryRecord
import com.tracktosearch.data.ai.AiDailyStage
import com.tracktosearch.data.ai.AiQuizDifficulty
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiNameSignal
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.ai.AiTasteEvidence
import com.tracktosearch.data.ai.AiTasteAnalysis

/** 服务端插图状态：生成中（后台生图，完成后由静默刷新补拉）。 */
private const val ILLUSTRATION_STATUS_GENERATING = "generating"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiFeatureScreen(
    feature: AiFeature,
    state: AiSpriteUiState,
    viewModel: AiSpriteViewModel,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPlayAudio: (AiAudio) -> Unit,
    onMovieClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onShowClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onRecommendationClick: ((AiRecommendation) -> Unit)? = null
) {
    val sceneRevision = when (feature) {
        AiFeature.TASTE -> state.tasteRevision
        AiFeature.QUIZ -> state.quizResultRevision
        else -> 0L
    }
    val sceneEvent = when (feature) {
        AiFeature.TASTE -> state.taste?.let { tasteSceneEvent(it.recommendations.isNotEmpty()) }
        AiFeature.QUIZ -> state.quizResult?.let { quizSceneEvent(it.score, it.totalScore) }
        else -> null
    }
    var featureAnchorBounds by remember(feature) { mutableStateOf<Rect?>(null) }
    var showFeatureScene by remember(feature) { mutableStateOf(false) }
    var handledSceneRevision by remember(feature) { mutableStateOf(sceneRevision) }
    var discardQuizConfirmVisible by remember(feature) { mutableStateOf(false) }
    // 答题进行中点刷新等于放弃这一轮：必须先确认，不能手滑就把 13 题作答清空
    val refreshNeedsConfirm = feature == AiFeature.QUIZ && hasQuizInProgress(state)
    val dailyChangeLimitReached = feature == AiFeature.DAILY &&
        !canChangeDailyKnowledge(state.dailyKnowledgeChangeCount)
    val reducedMotion = rememberAiReducedMotion()
    // OFFLINE 错误码可能先于 ConnectivityObserver 到达，UI 统一按不可用态处理，避免
    // 加载条、确认框或场景动画在离线说明上方短暂闪现。
    val offlineUi = state.networkStatus == ConnectivityObserver.NetworkStatus.OFFLINE ||
        state.errorCode == AI_OFFLINE_ERROR_CODE
    // 只把真正有可渲染字段的对象视为旧内容；空 DTO 仍应显示加载占位，而不是空矩形。
    val hasFeatureContent = when (feature) {
        AiFeature.GREETING -> state.greeting?.let {
            it.nickname.isNotBlank() ||
                it.greeting.isNotBlank() ||
                it.spokenText.isNotBlank() ||
                it.nicknameMeaning.isNotBlank() ||
                it.comment.isNotBlank() ||
                it.nameSignals.isNotEmpty() ||
                it.nicknameSignature.isNotBlank() ||
                it.audio != null
        } == true
        AiFeature.TASTE -> state.taste?.let {
            it.roast.isNotBlank() ||
                it.tasteProfile.isNotBlank() ||
                it.profileSentence.isNotBlank() ||
                it.profileKeywords.isNotEmpty() ||
                it.evidence.isNotEmpty() ||
                it.highlights.isNotEmpty() ||
                it.recommendations.isNotEmpty()
        } == true
        AiFeature.QUIZ -> when {
            state.quizResult != null -> true
            !state.quizStarted -> state.quizPreviewMovies.isNotEmpty()
            else -> state.quiz?.questions?.isNotEmpty() == true
        }
        AiFeature.DAILY -> state.dailyKnowledge?.let {
            it.title.isNotBlank() ||
                it.takeaway.isNotBlank() ||
                it.fact.isNotBlank() ||
                it.explanation.isNotBlank() ||
                it.characterLine.orEmpty().isNotBlank() ||
                it.relatedMediaTitle.orEmpty().isNotBlank() ||
                it.relatedMedia?.title.orEmpty().isNotBlank() ||
                it.filmEvidence.isNotBlank() ||
                it.sourceName.isNotBlank() ||
                it.source?.name.orEmpty().isNotBlank()
        } == true
    }
    // 首次请求还没有内容时显示专用加载态，不把“离线不可用”误当成加载结果。
    val showLoadingPlaceholder = state.isLoading &&
        !offlineUi &&
        state.errorCode == null &&
        !hasFeatureContent
    val showLoadingBanner = state.isLoading &&
        !offlineUi &&
        state.errorCode == null &&
        hasFeatureContent
    // 场景图是成功反馈，任何加载/错误/离线状态都不能让它盖住内容或错误 Overlay。
    val sceneBlocked = state.isLoading || state.errorCode != null || offlineUi
    val canShowRefreshConfirm = refreshNeedsConfirm &&
        !offlineUi &&
        !state.isLoading
    val haptics = rememberAppHaptics()
    // 底部安全区：宿主页面可能先消费过系统栏 insets，让 WindowInsets.navigationBars 归零；
    // 直接读窗口根 View 的真实 insets，保证答题/每日内容底部不落到手势区下（仍保持沉浸背景）。
    val contentBottomInset = rememberRootNavigationBarBottomInset()

    LaunchedEffect(canShowRefreshConfirm) {
        if (!canShowRefreshConfirm) {
            // 答题已重置、开始请求或断网时，收起上一轮留下的确认框状态。
            discardQuizConfirmVisible = false
        }
    }

    LaunchedEffect(feature, sceneRevision, sceneEvent, sceneBlocked) {
        if (sceneEvent == null || sceneBlocked) {
            // 被阻塞时不标记 revision，网络恢复且请求结束后仍可按新结果正常播放一次。
            showFeatureScene = false
            return@LaunchedEffect
        }
        if (shouldShowSceneForRevision(handledSceneRevision, sceneRevision)) {
            handledSceneRevision = sceneRevision
            showFeatureScene = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
            SubPageTopBar(
                title = featureTitle(feature),
                onBack = onBack,
                backContentDescription = stringResource(R.string.ai_feature_back),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                windowInsets = TopAppBarDefaults.windowInsets,
                actions = {
                    // 刷新是最主要的配额消耗入口，把当日+本会话用量摆在按钮旁边
                    state.quota?.let { quota ->
                        Text(
                            text = stringResource(
                                R.string.ai_quota_full,
                                quota.dailyUsed,
                                quota.dailyLimit,
                                quota.sessionUsed,
                                quota.sessionLimit
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = {
                            // 图标按钮给轻一档。点下去可能是弹确认框、可能是直接刷新，
                            // 语义只按「它是个刷新图标按钮」给，不跟着后续弹窗变
                            haptics.lightTap()
                            if (refreshNeedsConfirm) discardQuizConfirmVisible = true else onRefresh()
                        },
                        enabled = !state.isLoading && !offlineUi && !dailyChangeLimitReached
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(
                                if (dailyChangeLimitReached) {
                                    R.string.ai_daily_change_limit_description
                                } else {
                                    R.string.ai_feature_refresh
                                }
                            )
                        )
                    }
                },
            )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    // 简答题输入框在 LazyColumn 里，没有这层 imePadding 会被软键盘完全盖住
                    .imePadding()
                    .padding(bottom = contentBottomInset)
            ) {
                // 离线是功能页自己的占位状态，不使用 Overlay 错误卡片；否则遮罩会把这句文案盖住。
                // 有内容刷新时给加载条预留高度，避免它压住首段标题或试听入口。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (showLoadingBanner) 52.dp else 0.dp)
                ) {
                    if (offlineUi && !hasFeatureContent) {
                        if (feature == AiFeature.DAILY) {
                            DailyOfflinePlaceholder()
                        } else {
                            FeatureUnavailable()
                        }
                    } else if (showLoadingPlaceholder) {
                        if (feature == AiFeature.DAILY) {
                            DailyLoadingPlaceholder(
                                stage = state.dailyStage,
                                progress = dailyStreamProgressFraction(
                                    stage = state.dailyStage,
                                    chars = state.dailyStageChars,
                                    expectedChars = state.dailyStageExpectedChars
                                ),
                                startedAtMillis = state.dailyRequestStartedAtMillis,
                                onCancel = viewModel::cancelActiveFeatureRequest
                            )
                        } else {
                            FeatureLoadingPlaceholder(onCancel = viewModel::cancelActiveFeatureRequest)
                        }
                    } else if (!hasFeatureContent) {
                        // 没有请求、错误或离线状态时保持空白，避免把取消/尚未开始误报为离线。
                        Spacer(Modifier.fillMaxSize())
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (offlineUi) OfflineCachedBanner()
                            Box(modifier = Modifier.weight(1f)) {
                                when (feature) {
                                    AiFeature.GREETING -> GreetingFeature(greeting = state.greeting, onPlayAudio = onPlayAudio)
                                    AiFeature.TASTE -> TasteFeature(
                                        taste = state.taste,
                                        onMovieClick = onMovieClick,
                                        onShowClick = onShowClick,
                                        onRecommendationClick = onRecommendationClick,
                                        onHeaderAnchorBoundsChanged = { featureAnchorBounds = it }
                                    )
                                    AiFeature.QUIZ -> AiQuizScreen(
                                        state = state,
                                        viewModel = viewModel,
                                        onResultAnchorBoundsChanged = { featureAnchorBounds = it }
                                    )
                                    AiFeature.DAILY -> state.dailyKnowledge?.let { daily ->
                                        DailyFeature(
                                            daily = daily,
                                            state = state,
                                            viewModel = viewModel,
                                            reducedMotion = reducedMotion
                                        )
                                    } ?: FeatureUnavailable()
                                }
                            }
                        }
                    }
                }

                if (showLoadingBanner) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                // 今日知识刷新时带上真实阶段：换一条同样要等十几秒，用户要知道现在在跑哪一步
                                text = if (feature == AiFeature.DAILY) {
                                    dailyStageLabel(state.dailyStage) ?: stringResource(R.string.ai_feature_loading)
                                } else {
                                    stringResource(R.string.ai_feature_loading)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // LLM 生成耗时不定，允许中途放弃：取消是静默操作，不弹错误不打扰
                            TextButton(
                                onClick = {
                                    // 「取消」按取消档给轻一记
                                    haptics.lightTap()
                                    viewModel.cancelActiveFeatureRequest()
                                },
                                modifier = Modifier.height(24.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.ai_feature_cancel),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (!offlineUi && state.errorCode != null) {
                    FeatureError(
                        errorCode = state.errorCode,
                        onRetry = onRefresh
                    )
                }
            }
        }

        if (discardQuizConfirmVisible && canShowRefreshConfirm) {
            AppAlertDialog(
                onDismissRequest = { discardQuizConfirmVisible = false },
                title = stringResource(R.string.ai_quiz_refresh_title),
                message = stringResource(
                    R.string.ai_quiz_refresh_message,
                    answeredQuizCount(state.quiz, state.quizAnswers)
                ),
                confirm = DialogAction(
                    label = stringResource(R.string.ai_quiz_refresh_confirm),
                    onClick = {
                        discardQuizConfirmVisible = false
                        onRefresh()
                    }
                ),
                dismiss = DialogAction(
                    label = stringResource(R.string.common_cancel),
                    onClick = { discardQuizConfirmVisible = false }
                )
            )
        }

        // 仅把 visible 设为 false 仍会先播放退场帧；错误 Overlay 出现时直接移除场景层，
        // 防止高 zIndex 的庆祝插画在退场期间短暂压住错误文案。
        if (!sceneBlocked) {
            AiSpriteMotion(
                characterId = state.activatedCharacterId.orEmpty(),
                anchor = sceneEvent?.let { sceneArtFor(it).anchor } ?: AiSpriteAnchor.AiFeatureHeader,
                anchorBounds = featureAnchorBounds,
                visible = showFeatureScene &&
                    featureAnchorBounds != null &&
                    sceneEvent != null &&
                    state.activatedCharacterId?.let { automaticSpriteArt(it) != null } == true,
                onClick = {},
                onFinished = { showFeatureScene = false },
                modifier = Modifier.zIndex(5f),
                sceneRes = sceneEvent?.let { sceneArtFor(it).drawableRes },
                // 功能页内的场景图只是庆祝插画，不要变成一块盖在内容上的可点区域
                interactive = false
            )
        }
    }
}

@Composable
private fun GreetingFeature(greeting: AiGreeting?, onPlayAudio: (AiAudio) -> Unit) {
    if (greeting == null) {
        FeatureUnavailable()
        return
    }
    // 增强字段存在时先展示昵称原文和可追溯线索；旧响应仍沿用寓意/评断两段文案。
    val greetingText = greeting.greeting.ifBlank { greeting.spokenText }.trim()
    val haptics = rememberAppHaptics()
    val nickname = greeting.nickname.trim()
    val hasStructuredReading = greeting.nameSignals.any {
        it.text.isNotBlank() || it.interpretation.isNotBlank()
    } || greeting.nicknameSignature.isNotBlank()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (greetingText.isNotBlank() || nickname.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
                            Text(
                                text = stringResource(R.string.ai_feature_nickname),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (nickname.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = nickname,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (greetingText.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            TypewriterText(
                                text = greetingText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
        if (hasStructuredReading) {
            item {
                Text(
                    text = stringResource(R.string.ai_feature_language_inference_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (greeting.nameSignals.isNotEmpty()) {
            item { NameSignalsSection(greeting.nameSignals) }
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_feature_meaning),
                text = greeting.nicknameMeaning,
                reveal = AiTextReveal.FADE_IN,
                revealDelayMillis = 150L
            )
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_feature_comment),
                text = greeting.comment,
                reveal = AiTextReveal.FADE_IN,
                revealDelayMillis = 400L
            )
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_feature_nickname_signature),
                text = greeting.nicknameSignature
            )
        }
        item {
            greeting.audio?.let { audio ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null)
                        Text(
                            text = stringResource(R.string.ai_audio_play),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            // TTS 要等一会儿才出声，这一记轻触感是「点到了」的即时回执
                            haptics.lightTap()
                            onPlayAudio(audio)
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = stringResource(R.string.ai_audio_play))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameSignalsSection(signals: List<AiNameSignal>) {
    val visibleSignals = signals.filter { it.text.isNotBlank() || it.interpretation.isNotBlank() }
    if (visibleSignals.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.ai_feature_name_signals),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            visibleSignals.forEach { signal ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (signal.text.isNotBlank()) {
                        Text(
                            signal.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (signal.interpretation.isNotBlank()) {
                        Text(
                            signal.interpretation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeaningSection(
    title: String,
    text: String,
    reveal: AiTextReveal = AiTextReveal.NONE,
    revealDelayMillis: Long = 0L
) {
    if (text.isBlank()) {
        // 空字段不渲染只有底色的空卡片，避免被误认为文字加载失败或只剩矩形占位。
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            when (reveal) {
                AiTextReveal.TYPEWRITER -> TypewriterText(text = text, style = MaterialTheme.typography.bodyLarge)
                AiTextReveal.FADE_IN -> FadeInText(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    delayMillis = revealDelayMillis
                )
                AiTextReveal.NONE -> Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun TasteFeature(
    taste: AiTasteAnalysis?,
    onMovieClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onShowClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit,
    onRecommendationClick: ((AiRecommendation) -> Unit)?,
    onHeaderAnchorBoundsChanged: (Rect) -> Unit = {}
) {
    if (taste == null) {
        FeatureUnavailable()
        return
    }
    var roastExpanded by remember(taste.roast) { mutableStateOf(false) }
    val profileSentence = taste.profileSentence.ifBlank { taste.tasteProfile }
    val keywords = taste.profileKeywords.ifEmpty { taste.highlights.take(5) }
    val visibleEvidence = taste.evidence.filter {
        it.title.isNotBlank() || it.signal.isNotBlank() || it.inference.isNotBlank() || it.confidence.isNotBlank()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionTitle(
                title = stringResource(R.string.ai_taste_title),
                icon = Icons.Rounded.AutoAwesome,
                onBoundsChanged = onHeaderAnchorBoundsChanged
            )
        }
        if (profileSentence.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.ai_taste_profile),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            profileSentence,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        if (keywords.isNotEmpty()) {
            item { TasteKeywordsSection(keywords) }
        }
        if (visibleEvidence.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.ai_taste_evidence),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            itemsIndexed(visibleEvidence, key = { index, evidence -> "${evidence.title}_$index" }) { _, evidence ->
                TasteEvidenceCard(evidence)
            }
        }
        if (taste.highlights.isNotEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.ai_taste_highlights), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        taste.highlights.forEach { highlight ->
                            if (highlight.isNotBlank()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(highlight, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (taste.roast.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { roastExpanded = !roastExpanded },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.ai_taste_roast),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Icon(
                                imageVector = if (roastExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        if (roastExpanded) {
                            Text(
                                taste.roast,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.ai_taste_recommendations),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (taste.recommendations.isEmpty()) {
            item { Text(stringResource(R.string.ai_taste_no_recommendations), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            // id 可能为空（回退到 title），同名条目会重复 → 用 index 兜底保证 key 唯一
            itemsIndexed(taste.recommendations, key = { index, recommendation -> "${recommendation.id}_$index" }) { _, recommendation ->
                RecommendationCard(
                    recommendation = recommendation,
                    onOpen = {
                        if (onRecommendationClick != null) {
                            onRecommendationClick(recommendation)
                        } else {
                            val hasNativeId = (recommendation.traktId ?: 0) > 0 || (recommendation.tmdbId ?: 0) > 0
                            val imdbOrNavigationKey = if (hasNativeId) {
                                recommendation.imdbId.orEmpty()
                            } else {
                                recommendationNavigationKey(recommendation).orEmpty()
                            }
                            val traktId = recommendation.traktId ?: 0
                            val tmdbId = recommendation.tmdbId ?: 0
                            if (recommendation.mediaType.lowercase() == "show") {
                                onShowClick(traktId, tmdbId, recommendation.title, imdbOrNavigationKey, 0.0, false, false)
                            } else {
                                onMovieClick(traktId, tmdbId, recommendation.title, imdbOrNavigationKey, 0.0, false, false)
                            }
                        }
                    }
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun TasteKeywordsSection(keywords: List<String>) {
    val visibleKeywords = keywords.map(String::trim).filter(String::isNotBlank).distinct()
    if (visibleKeywords.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.ai_taste_profile_keywords),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleKeywords, key = { it }) { keyword ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            keyword,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TasteEvidenceCard(evidence: AiTasteEvidence) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (evidence.title.isNotBlank()) {
                Text(
                    evidence.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (evidence.signal.isNotBlank()) {
                Text(
                    stringResource(R.string.ai_taste_evidence_signal_format, evidence.signal),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (evidence.inference.isNotBlank()) {
                Text(
                    stringResource(R.string.ai_taste_evidence_inference_format, evidence.inference),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (evidence.confidence.isNotBlank()) {
                Text(
                    stringResource(R.string.ai_taste_evidence_confidence_format, evidence.confidence),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecommendationCard(recommendation: AiRecommendation, onOpen: () -> Unit) {
    val hasMediaId = recommendationHasDetailRoute(recommendation)
    val mediaIcon = if (recommendation.mediaType.lowercase() == "show") Icons.Rounded.LiveTv else Icons.Rounded.Movie
    // 核验/补齐回填的是 TMDB poster_path，按项目约定拼完整 URL；服务端直发的 posterUrl 优先
    val posterUrl = recommendation.posterUrl?.takeIf { it.isNotBlank() }
        ?: recommendation.posterPath?.takeIf { it.isNotBlank() }?.let { TmdbImageUrls.build(it) }
    // 与 FadeInText 同款简单渐入：淡入 + 轻微上移，返回本页不重播
    var shown by remember { mutableStateOf(false) }
    val revealProgress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "recommendation_card_reveal"
    )
    LaunchedEffect(recommendation.id) {
        shown = true
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = revealProgress
                translationY = (1f - revealProgress) * 6.dp.toPx()
            }
            .clip(RoundedCornerShape(13.dp))
            // 列表项进详情，给轻一档。没有 mediaId 的卡片本来就不挂 clickable，也就没有触感
            .then(
                if (hasMediaId) {
                    Modifier.hapticClickable(
                        semantic = HapticSemantic.LIGHT_TAP,
                        onClick = onOpen
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 62.dp, height = 90.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (posterUrl == null) {
                    Icon(mediaIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = recommendation.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recommendation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                recommendation.year?.let { year -> Text(stringResource(R.string.ai_taste_recommendation_year_format, year), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(recommendation.reason, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                // 不可点的卡片要说明原因，否则用户以为卡片坏了一直戳
                if (!hasMediaId) {
                    Text(
                        text = stringResource(R.string.ai_taste_no_detail),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (hasMediaId) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = stringResource(R.string.ai_taste_details), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyFeature(
    daily: AiDailyKnowledge,
    state: AiSpriteUiState,
    viewModel: AiSpriteViewModel,
    reducedMotion: Boolean
) {
    val historyKey = daily.unitId?.trim()?.takeIf { it.isNotEmpty() } ?: daily.id
    // 进入首帧后才写历史，避免把网络成功误当成用户已经阅读。
    LaunchedEffect(historyKey, daily.locale) {
        viewModel.markDailyKnowledgeShown(daily)
    }
    val relatedTitle = daily.relatedMedia?.title?.trim().orEmpty()
        .ifBlank { daily.relatedMediaTitle?.trim().orEmpty() }
    val relationRes = dailyKnowledgeRelationLabelRes(daily.relationType)
    val evidenceModeRes = dailyKnowledgeEvidenceModeLabelRes(daily.evidenceMode)
    val subjectGroupRes = dailyKnowledgeSubjectGroupLabelRes(daily.subjectGroup)
    val sourceName = daily.source?.name?.trim().orEmpty()
        .ifBlank { daily.sourceName.trim() }
    val sourceUrl = daily.source?.url?.trim().orEmpty()
        .ifBlank { daily.sourceUrl.trim() }
    val sourceEvidence = daily.source?.evidence?.trim().orEmpty()
    val hasSourceData = dailySourceBlockHasContent(
        sourceName = sourceName,
        sourceUrl = sourceUrl,
        sourceEvidence = sourceEvidence,
        publishedAt = daily.publishedAt
    )
    val hasSpoilerMarker = daily.containsSpoiler ||
        daily.spoilerLevel == "light" ||
        daily.spoilerLevel == "heavy"

    val question = daily.checkQuestion
    val questionValid = dailyCheckQuestionIsValid(question)
    val selectedOptionId = state.dailyQuestionSelectedOptionId
    val selectedOption = question?.options?.firstOrNull { it.id == selectedOptionId }
    val correctOptionId = question?.correctOptionIds?.firstOrNull()
    val correctOption = question?.options?.firstOrNull { it.id == correctOptionId }
    val answered = selectedOption != null && correctOption != null
    val answerIsCorrect = answered && selectedOption.id == correctOption.id

    val realWorldExample = daily.realWorldExample?.trim().orEmpty()
    val boundary = daily.boundary?.trim().orEmpty()
    val hasConceptDetails = realWorldExample.isNotBlank() || boundary.isNotBlank()
    val conceptKey = daily.unitId?.takeIf { it.isNotBlank() } ?: daily.id
    var conceptExpanded by rememberSaveable(conceptKey) { mutableStateOf(false) }
    var historySheetVisible by rememberSaveable { mutableStateOf(false) }
    val illustration = daily.illustration
    val illustrationUrl = illustration?.takeIf { it.isReady }?.url
    var illustrationLoadFailed by remember(illustrationUrl) { mutableStateOf(false) }
    val mediaImageUrl = state.dailyMediaImageUrl
    val context = LocalContext.current
    // 沿用项目外链约定：ACTION_VIEW + NEW_TASK 交给系统浏览器；只放行 http(s) 防止服务端下发异常 scheme
    val openSourceLink: (String) -> Unit = { url ->
        if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hasEvidenceSection = relatedTitle.isNotBlank() ||
        daily.filmEvidence.isNotBlank() ||
        relationRes != null ||
        evidenceModeRes != null ||
        subjectGroupRes != null
    val hasConceptSection = daily.explanation.isNotBlank() || hasConceptDetails
    val questionItemIndex = 1 +
        (if (daily.isFallback) 1 else 0) +
        (if (hasSpoilerMarker) 1 else 0) +
        (if (hasEvidenceSection) 1 else 0) +
        (if (hasConceptSection) 1 else 0)
    val scrollToQuestion: () -> Unit = {
        scope.launch {
            if (reducedMotion) {
                listState.scrollToItem(questionItemIndex)
            } else {
                listState.animateScrollToItem(questionItemIndex)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.ai_daily_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { historySheetVisible = true },
                    enabled = state.dailyKnowledgeHistory.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = stringResource(R.string.ai_daily_history_open_description),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = stringResource(R.string.ai_daily_recent_learned))
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = daily.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.ai_daily_takeaway),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = daily.takeaway,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        if (daily.isFallback) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.ai_daily_fallback_notice),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        if (hasSpoilerMarker) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        text = stringResource(R.string.ai_daily_spoiler_warning),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (hasEvidenceSection) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp
                ) {
                    Column {
                        if (mediaImageUrl != null) {
                            // 真实剧照/海报由媒体数据层按 mediaId 选择；AI 不返回图片地址。
                            AsyncImage(
                                model = mediaImageUrl,
                                contentDescription = relatedTitle.ifBlank {
                                    stringResource(R.string.ai_daily_media_image_description)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (mediaImageUrl == null) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 64.dp, height = 88.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Movie,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    relationRes?.let { DailyLabelPill(stringResource(it)) }
                                    evidenceModeRes?.let { DailyLabelPill(stringResource(it)) }
                                    subjectGroupRes?.let { DailyLabelPill(stringResource(it)) }
                                }
                                if (relatedTitle.isNotBlank()) {
                                    Text(
                                        text = relatedTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (daily.filmEvidence.isNotBlank()) {
                                    Text(
                                        text = daily.filmEvidence,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hasConceptSection) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ai_daily_concept_section_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (illustrationUrl != null && !illustrationLoadFailed) {
                            // AI 概念插图只辅助理解，不承担事实证明责任；角色与来源在 caption 里声明。
                            // 失败（如 10 分钟签名 URL 已过期）时降级为不渲染空槽，避免大片空白。
                            // 插图就绪时淡入：静默刷新补拉成功后是「无图 → 有图」的跳变，硬切会闪一下。
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(durationMillis = 320))
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AsyncImage(
                                        model = illustrationUrl,
                                        contentDescription = stringResource(
                                            R.string.ai_daily_illustration_description,
                                            daily.concept.orEmpty()
                                        ),
                                        onError = { illustrationLoadFailed = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(14.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Text(
                                        text = stringResource(R.string.ai_daily_illustration_caption),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else if (!illustrationLoadFailed && illustration?.status == ILLUSTRATION_STATUS_GENERATING) {
                            // 后台生图期间给出「占位 + 说明」而不是留白：用户知道图会来，也知道去哪儿等。
                            // 文案只在生成中显示；unavailable 与失败一律不渲染空槽（旧行为）。
                            val shimmer = rememberShimmer()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f)
                                    .shimmer(shimmer, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.ai_daily_illustration_generating),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (daily.explanation.isNotBlank()) {
                            Text(
                                text = daily.explanation,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        if (hasConceptDetails) {
                            TextButton(onClick = { conceptExpanded = !conceptExpanded }) {
                                Icon(
                                    imageVector = if (conceptExpanded) {
                                        Icons.Rounded.ExpandLess
                                    } else {
                                        Icons.Rounded.ExpandMore
                                    },
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(
                                        if (conceptExpanded) {
                                            R.string.ai_daily_concept_collapse
                                        } else {
                                            R.string.ai_daily_concept_expand
                                        }
                                    )
                                )
                            }
                            // 减少动态效果时直接显示终态；未开启时只用 150ms 轻微淡入。
                            AnimatedVisibility(
                                visible = conceptExpanded,
                                enter = if (reducedMotion) {
                                    EnterTransition.None
                                } else {
                                    fadeIn(androidx.compose.animation.core.tween(durationMillis = 150))
                                },
                                exit = if (reducedMotion) {
                                    ExitTransition.None
                                } else {
                                    fadeOut(androidx.compose.animation.core.tween(durationMillis = 120))
                                }
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (realWorldExample.isNotBlank()) {
                                        Text(
                                            text = stringResource(
                                                R.string.ai_daily_real_world_example_format,
                                                realWorldExample
                                            ),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    if (boundary.isNotBlank()) {
                                        Text(
                                            text = stringResource(R.string.ai_daily_boundary, boundary),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hasSourceData) {
            item {
                // 来源区块独立成卡：有 name/url/证据/日期才渲染；数据缺失时不留空壳。
                DailySourceBlock(
                    sourceName = sourceName,
                    sourceUrl = sourceUrl,
                    sourceEvidence = sourceEvidence,
                    publishedAt = daily.publishedAt,
                    onOpenUrl = openSourceLink
                )
            }
        }

        if (questionValid && question != null && correctOption != null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ai_daily_question_section_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = question.prompt,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.ai_daily_question_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        question.options.forEach { option ->
                            val isSelected = option.id == selectedOptionId
                            val isCorrectOption = answered && option.id == correctOption.id
                            val optionStateDescription = when {
                                !answered -> null
                                isCorrectOption -> stringResource(R.string.ai_daily_option_correct)
                                isSelected -> stringResource(R.string.ai_daily_option_wrong)
                                else -> null
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !answered) {
                                        viewModel.selectDailyKnowledgeQuestionOption(option.id)
                                    }
                                    .padding(vertical = 4.dp)
                                    .semantics {
                                        optionStateDescription?.let { stateDescription = it }
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                    enabled = !answered
                                )
                                Text(
                                    text = option.text,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (answered && isCorrectOption) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = stringResource(R.string.ai_daily_option_correct),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (answered && isSelected && !isCorrectOption) {
                                    Icon(
                                        imageVector = Icons.Rounded.Cancel,
                                        contentDescription = stringResource(R.string.ai_daily_option_wrong),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        if (answered) {
                            Text(
                                text = stringResource(
                                    if (answerIsCorrect) {
                                        R.string.ai_daily_question_result_correct
                                    } else {
                                        R.string.ai_daily_question_result_wrong
                                    }
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (answerIsCorrect) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            Text(
                                text = stringResource(
                                    R.string.ai_daily_question_correct_answer_format,
                                    correctOption.text
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.ai_daily_question_explanation),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = question.explanation,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        if (!daily.characterLine.isNullOrBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = daily.characterLine.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        item {
            DailyFeedbackSection(
                selectedContentFeedback = state.dailyKnowledgeFeedback,
                selectedDifficultyFeedback = state.dailyKnowledgeDifficultyFeedback,
                showDifficultyFeedback = answered,
                onContentFeedback = viewModel::recordDailyKnowledgeFeedback,
                onDifficultyFeedback = viewModel::recordDailyKnowledgeDifficultyFeedback
            )
        }

        item {
            val canChange = canChangeDailyKnowledge(state.dailyKnowledgeChangeCount)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (questionValid) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (answered) {
                                Button(
                                    onClick = scrollToQuestion,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ai_daily_view_explanation),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                OutlinedButton(
                                    onClick = { viewModel.openFeature(AiFeature.QUIZ) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ai_daily_enter_quiz),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Button(
                                    onClick = scrollToQuestion,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ai_daily_start_question),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                OutlinedButton(
                                    onClick = viewModel::refreshFeature,
                                    enabled = canChange,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = stringResource(R.string.ai_daily_change),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = viewModel::refreshFeature,
                            enabled = canChange,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.ai_daily_change),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.ai_daily_change_remaining_format,
                            remainingDailyKnowledgeChanges(state.dailyKnowledgeChangeCount)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

    }

    if (historySheetVisible) {
        AppBottomSheet(
            onDismissRequest = { historySheetVisible = false },
            skipPartiallyExpanded = false
        ) {
            DailyKnowledgeHistorySheet(
                records = state.dailyKnowledgeHistory,
                onOpenRecord = { record ->
                    historySheetVisible = false
                    // 收起 sheet 后把每日列表拉回顶部：切换成旧内容时若停在原滚动位置，
                    // 会看起来像「没打开任何东西」，回到顶部让新内容从标题起可见。
                    scope.launch { listState.scrollToItem(0) }
                    viewModel.openDailyKnowledgeRecord(record)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DailyKnowledgeHistorySheet(
    records: List<AiDailyKnowledgeHistoryRecord>,
    onOpenRecord: (AiDailyKnowledgeHistoryRecord) -> Unit
) {
    val sheetHaptics = rememberAppHaptics()
    val scrollState = rememberScrollState()
    val fadeColor = floatingSheetColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomScrollFade(scrollState, fadeColor)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.ai_daily_recent_learned),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (records.isEmpty()) {
            Text(
                text = stringResource(R.string.ai_daily_history_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            records.forEach { record ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // 轻触回执：sheet 收起 + 内容切换前的即时反馈
                                sheetHaptics.lightTap()
                                onOpenRecord(record)
                            }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = record.knowledge.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOfNotNull(
                                record.shownDate,
                                record.concept?.takeIf { it.isNotBlank() },
                                record.relatedMedia?.title?.takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyLabelPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DailyFeedbackSection(
    selectedContentFeedback: AiDailyKnowledgeContentFeedback?,
    selectedDifficultyFeedback: AiQuizDifficulty?,
    showDifficultyFeedback: Boolean,
    onContentFeedback: (AiDailyKnowledgeContentFeedback) -> Unit,
    onDifficultyFeedback: (AiQuizDifficulty) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_daily_content_feedback_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DailyFeedbackOption(
                    label = stringResource(R.string.ai_daily_feedback_helpful),
                    selected = selectedContentFeedback == AiDailyKnowledgeContentFeedback.HELPFUL,
                    enabled = selectedContentFeedback == null,
                    onClick = { onContentFeedback(AiDailyKnowledgeContentFeedback.HELPFUL) }
                )
                DailyFeedbackOption(
                    label = stringResource(R.string.ai_daily_feedback_too_broad),
                    selected = selectedContentFeedback == AiDailyKnowledgeContentFeedback.TOO_BROAD,
                    enabled = selectedContentFeedback == null,
                    onClick = { onContentFeedback(AiDailyKnowledgeContentFeedback.TOO_BROAD) }
                )
                DailyFeedbackOption(
                    label = stringResource(R.string.ai_daily_feedback_weak_relation),
                    selected = selectedContentFeedback == AiDailyKnowledgeContentFeedback.WEAK_RELATION,
                    enabled = selectedContentFeedback == null,
                    onClick = { onContentFeedback(AiDailyKnowledgeContentFeedback.WEAK_RELATION) }
                )
                DailyFeedbackOption(
                    label = stringResource(R.string.ai_daily_feedback_too_hard),
                    selected = selectedContentFeedback == AiDailyKnowledgeContentFeedback.TOO_HARD,
                    enabled = selectedContentFeedback == null,
                    onClick = { onContentFeedback(AiDailyKnowledgeContentFeedback.TOO_HARD) }
                )
                DailyFeedbackOption(
                    label = stringResource(R.string.ai_daily_feedback_too_many_spoilers),
                    selected = selectedContentFeedback == AiDailyKnowledgeContentFeedback.TOO_MUCH_SPOILER,
                    enabled = selectedContentFeedback == null,
                    onClick = { onContentFeedback(AiDailyKnowledgeContentFeedback.TOO_MUCH_SPOILER) }
                )
            }
            if (selectedContentFeedback != null) {
                Text(
                    text = stringResource(R.string.ai_daily_feedback_done),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showDifficultyFeedback) {
                Text(
                    text = stringResource(R.string.ai_daily_difficulty_feedback_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (showDifficultyFeedback) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DailyFeedbackOption(
                        label = stringResource(R.string.ai_quiz_feedback_easy),
                        selected = selectedDifficultyFeedback == AiQuizDifficulty.EASY,
                        enabled = selectedDifficultyFeedback == null,
                        onClick = { onDifficultyFeedback(AiQuizDifficulty.EASY) },
                        modifier = Modifier.weight(1f)
                    )
                    DailyFeedbackOption(
                        label = stringResource(R.string.ai_quiz_feedback_just_right),
                        selected = selectedDifficultyFeedback == AiQuizDifficulty.JUST_RIGHT,
                        enabled = selectedDifficultyFeedback == null,
                        onClick = { onDifficultyFeedback(AiQuizDifficulty.JUST_RIGHT) },
                        modifier = Modifier.weight(1f)
                    )
                    DailyFeedbackOption(
                        label = stringResource(R.string.ai_quiz_feedback_hard),
                        selected = selectedDifficultyFeedback == AiQuizDifficulty.HARD,
                        enabled = selectedDifficultyFeedback == null,
                        onClick = { onDifficultyFeedback(AiQuizDifficulty.HARD) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (showDifficultyFeedback && selectedDifficultyFeedback != null) {
                Text(
                    text = stringResource(R.string.ai_daily_feedback_done),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DailyFeedbackOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = {}, modifier = modifier) {
            Text(text = label, textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(text = label, textAlign = TextAlign.Center)
        }
    }
}

/** 今日知识阶段文案；没拿到阶段信息时返回 null，由调用方回落到通用加载文案。 */
@Composable
private fun dailyStageLabel(stage: AiDailyStage?): String? = when (stage) {
    AiDailyStage.CANDIDATE -> stringResource(R.string.ai_daily_stage_candidate)
    AiDailyStage.REVIEW -> stringResource(R.string.ai_daily_stage_review)
    null -> null
}

/**
 * 今日知识加载页。
 *
 * 两段生成实测十几秒，干骨架屏看不出是在跑还是卡死：这里给出真实阶段、按已生成字符数推进的
 * 进度环、已等待时长与取消入口（与出题等待页同一套语言），下面保留骨架卡片说明「将要出现什么」。
 * 进度值来自服务端流式事件（见 AiRepository.getDailyStream），没拿到阶段信息时退化为不确定进度。
 */
@Composable
private fun DailyLoadingPlaceholder(
    stage: AiDailyStage?,
    progress: Float,
    startedAtMillis: Long,
    onCancel: () -> Unit
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
    val shimmer = rememberShimmer()
    val stageLabel = dailyStageLabel(stage) ?: stringResource(R.string.ai_daily_loading)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (stage == null) {
                    CircularProgressIndicator()
                } else {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(52.dp)
                    )
                }
                Text(
                    text = stageLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.ai_daily_wait_hint),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (startedAtMillis > 0L) {
                    Text(
                        text = stringResource(
                            R.string.ai_daily_elapsed,
                            formatQuizElapsed(elapsedSeconds)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DailySkeletonLine(widthFraction = 0.72f, shimmer = shimmer)
                    DailySkeletonLine(widthFraction = 0.94f, shimmer = shimmer)
                    DailySkeletonLine(widthFraction = 0.66f, shimmer = shimmer)
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 88.dp)
                            .shimmer(shimmer, RoundedCornerShape(12.dp))
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DailySkeletonLine(widthFraction = 0.42f, shimmer = shimmer)
                        DailySkeletonLine(widthFraction = 0.88f, shimmer = shimmer)
                        DailySkeletonLine(widthFraction = 0.62f, shimmer = shimmer)
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    DailySkeletonLine(widthFraction = 0.36f, shimmer = shimmer)
                    DailySkeletonLine(widthFraction = 0.92f, shimmer = shimmer)
                    DailySkeletonLine(widthFraction = 0.78f, shimmer = shimmer)
                }
            }
        }
        // 长等待必须给出路：等不下去时回到入口，不把用户锁在骨架屏里
        item {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.ai_feature_cancel))
            }
        }
    }
}

@Composable
private fun DailySkeletonLine(widthFraction: Float, shimmer: ShimmerState) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .shimmer(shimmer, RoundedCornerShape(7.dp))
    )
}

@Composable
private fun DailyOfflinePlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.ai_daily_offline_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.ai_daily_offline_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 非公开的无障碍开关键名。读不到就按未开启处理。 */
private const val KEY_A11Y_ANIMATION_DISABLED = "accessibility_display_animation_disabled"

/**
 * 今日影视知识只在解释展开和滚动定位使用轻动效；系统要求减少动效时全部取终态。
 */
@Composable
private fun rememberAiReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { readAiReducedMotion(context) }
}

private fun readAiReducedMotion(context: Context): Boolean {
    val animatorScale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }.getOrDefault(1f)
    val a11yDisabled = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            KEY_A11Y_ANIMATION_DISABLED,
            0
        )
    }.getOrDefault(0)
    return animatorScale == 0f || a11yDisabled == 1
}

@Composable
private fun DailySourceBlock(
    sourceName: String,
    sourceUrl: String,
    sourceEvidence: String,
    publishedAt: Long?,
    onOpenUrl: (String) -> Unit
) {
    val name = sourceName.trim()
    val url = sourceUrl.trim()
    val evidence = sourceEvidence.trim()
    val hasSourceContent = name.isNotEmpty() || url.isNotEmpty() || evidence.isNotEmpty()
    if (!hasSourceContent && publishedAt == null) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (hasSourceContent) {
                // 只有发布日期而没有来源内容时不再显示「来源」标题，避免空壳语义
                Text(
                    text = stringResource(R.string.ai_daily_source_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (url.isNotEmpty()) {
                // 有链接时整行可点：优先展示来源名，缺名时直接展示 URL 本身
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenUrl(url) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = name.ifEmpty { url },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.ai_daily_source_open_url),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (name.isNotEmpty()) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
            }
            if (evidence.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.ai_daily_source_evidence_format, evidence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            publishedAt?.let {
                Text(
                    text = stringResource(R.string.ai_daily_published_at_format, formatDailyPublishedAt(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun rememberRootNavigationBarBottomInset(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    // 先取 Compose 消费链外的窗口根 insets（宿主消费过系统栏时 Compose 值会被清零）；
    // 拿不到根值时回退到 Compose 的 navigationBars，两个值取大者。
    val rootBottomPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.rootWindowInsets
            ?.getInsets(android.view.WindowInsets.Type.navigationBars())
            ?.bottom ?: 0
    } else {
        @Suppress("DEPRECATION")
        view.rootWindowInsets?.stableInsetBottom ?: 0
    }
    val composeBottom = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    return with(density) {
        maxOf(rootBottomPx.toDp(), composeBottom)
    }
}

private fun formatDailyPublishedAt(timestamp: Long): String {
    // 后端历史数据有毫秒时间戳；同时兼容少量旧数据使用秒时间戳的情况。
    val millis = if (timestamp in 1L..10_000_000_000L) timestamp * 1000 else timestamp
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(millis))
}

@Composable
private fun SectionTitle(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onBoundsChanged: (Rect) -> Unit = {}
) {
    Row(
        modifier = Modifier.onGloballyPositioned { onBoundsChanged(it.boundsInRoot()) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun FeatureLoadingPlaceholder(onCancel: () -> Unit) {
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
            // 首次加载也保留取消入口，避免没有旧内容时用户只能等待请求结束。
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.ai_feature_cancel))
            }
        }
    }
}

@Composable
private fun OfflineCachedBanner() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = stringResource(R.string.ai_feature_offline_cached_status),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun FeatureUnavailable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.ai_feature_unavailable),
            modifier = Modifier.padding(24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 错误态。
 *
 * 在线错误由共享的 [AppErrorState]（Overlay 形态）承接，输出不变：
 * 按错误码给出具体原因（片单不够 / 配额用完 / 授权失效…）而非一律「精灵正在休息」；
 * 带遮罩挡住底层列表，不让底下照样能滚能点；重试解决不了的错误不给重试按钮，免得白点还烧请求。
 * 离线状态在调用方提前渲染 [FeatureUnavailable]，不进入这里，确保底层文案可见。
 */
@Composable
private fun FeatureError(errorCode: String, onRetry: () -> Unit) {
    AppErrorState(
        message = stringResource(aiErrorMessageRes(errorCode)),
        onRetry = onRetry,
        variant = AppErrorVariant.Overlay,
        retryable = aiErrorIsRetryable(errorCode),
        retryLabel = stringResource(R.string.ai_feature_retry)
    )
}

@Composable
private fun featureTitle(feature: AiFeature): String = when (feature) {
    AiFeature.GREETING -> stringResource(R.string.ai_feature_greeting)
    AiFeature.TASTE -> stringResource(R.string.ai_feature_taste)
    AiFeature.QUIZ -> stringResource(R.string.ai_feature_quiz)
    AiFeature.DAILY -> stringResource(R.string.ai_feature_daily)
}
