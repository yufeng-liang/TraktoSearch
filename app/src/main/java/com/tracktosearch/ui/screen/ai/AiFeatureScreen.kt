package com.tracktosearch.ui.screen.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.data.util.ConnectivityObserver
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppErrorVariant
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiRecommendation
import com.tracktosearch.data.ai.AiTasteAnalysis

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
    // OFFLINE 错误码可能先于 ConnectivityObserver 到达，UI 统一按不可用态处理，避免
    // 加载条、确认框或场景动画在离线说明上方短暂闪现。
    val offlineUi = state.networkStatus == ConnectivityObserver.NetworkStatus.OFFLINE ||
        state.errorCode == AI_OFFLINE_ERROR_CODE
    // 只把真正有可渲染字段的对象视为旧内容；空 DTO 仍应显示加载占位，而不是空矩形。
    val hasFeatureContent = when (feature) {
        AiFeature.GREETING -> state.greeting?.let {
            it.greeting.isNotBlank() ||
                it.spokenText.isNotBlank() ||
                it.nicknameMeaning.isNotBlank() ||
                it.comment.isNotBlank() ||
                it.audio != null
        } == true
        AiFeature.TASTE -> state.taste?.let {
            it.roast.isNotBlank() ||
                it.tasteProfile.isNotBlank() ||
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
                it.fact.isNotBlank() ||
                it.explanation.isNotBlank() ||
                it.characterLine.orEmpty().isNotBlank() ||
                it.sourceName.isNotBlank()
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
            TopAppBar(
                title = {
                    Text(
                        text = featureTitle(feature),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.ai_feature_back)
                        )
                    }
                },
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
                            if (refreshNeedsConfirm) discardQuizConfirmVisible = true else onRefresh()
                        },
                        enabled = !state.isLoading && !offlineUi
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.ai_feature_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    // 简答题输入框在 LazyColumn 里，没有这层 imePadding 会被软键盘完全盖住
                    .imePadding()
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            ) {
                // 离线是功能页自己的占位状态，不使用 Overlay 错误卡片；否则遮罩会把这句文案盖住。
                // 有内容刷新时给加载条预留高度，避免它压住首段标题或试听入口。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (showLoadingBanner) 52.dp else 0.dp)
                ) {
                    if (offlineUi) {
                        FeatureUnavailable()
                    } else if (showLoadingPlaceholder) {
                        FeatureLoadingPlaceholder(onCancel = viewModel::cancelActiveFeatureRequest)
                    } else if (!hasFeatureContent) {
                        // 没有请求、错误或离线状态时保持空白，避免把取消/尚未开始误报为离线。
                        Spacer(Modifier.fillMaxSize())
                    } else {
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
                            AiFeature.DAILY -> DailyFeature(daily = state.dailyKnowledge)
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
                                text = stringResource(R.string.ai_feature_loading),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // LLM 生成耗时不定，允许中途放弃：取消是静默操作，不弹错误不打扰
                            TextButton(
                                onClick = viewModel::cancelActiveFeatureRequest,
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
            AlertDialog(
                onDismissRequest = { discardQuizConfirmVisible = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text(stringResource(R.string.ai_quiz_refresh_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.ai_quiz_refresh_message,
                            answeredQuizCount(state.quiz, state.quizAnswers)
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        discardQuizConfirmVisible = false
                        onRefresh()
                    }) {
                        Text(stringResource(R.string.ai_quiz_refresh_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { discardQuizConfirmVisible = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
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
    // greeting 为空时优先用服务端的 spokenText，两个字段都空则不画空卡片。
    val greetingText = greeting.greeting.ifBlank { greeting.spokenText }.trim()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (greetingText.isNotBlank()) {
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
                        Spacer(Modifier.height(14.dp))
                        TypewriterText(
                            text = greetingText,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
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
                        Icon(Icons.Rounded.VolumeUp, contentDescription = null)
                        Text(
                            text = stringResource(R.string.ai_audio_play),
                            // 保留播放按钮的固定槽位，长本地化文案只截断，不把试听操作挤没。
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { onPlayAudio(audio) }) {
                            Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.ai_audio_play))
                        }
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
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.ai_taste_roast), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(8.dp))
                    TypewriterText(text = taste.roast, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_taste_profile),
                text = taste.tasteProfile
            )
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(highlight, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
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
            .then(if (hasMediaId) Modifier.clickable(onClick = onOpen) else Modifier),
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
                recommendation.year?.let { Text("(${it})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                Icon(Icons.Rounded.OpenInNew, contentDescription = stringResource(R.string.ai_taste_details), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun DailyFeature(daily: AiDailyKnowledge?) {
    if (daily == null) {
        FeatureUnavailable()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.ai_daily_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Text(daily.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_daily_fact),
                text = daily.fact,
                reveal = AiTextReveal.TYPEWRITER
            )
        }
        item {
            MeaningSection(
                title = stringResource(R.string.ai_daily_explanation),
                text = daily.explanation,
                reveal = AiTextReveal.FADE_IN,
                revealDelayMillis = 150L
            )
        }
        if (!daily.characterLine.isNullOrBlank()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(daily.characterLine.orEmpty(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (daily.sourceName.isNotBlank()) {
            item {
                Text(
                    text = stringResource(R.string.ai_daily_source_format, daily.sourceName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
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
