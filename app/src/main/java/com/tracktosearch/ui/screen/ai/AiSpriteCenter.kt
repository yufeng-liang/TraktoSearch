package com.tracktosearch.ui.screen.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class AuditionPlaybackState {
    IDLE,
    LOADING,
    PLAYING
}

/** 试听 LOADING 的兜底超时：TTS 请求挂住时把按钮还给用户。 */
private const val AUDITION_LOADING_TIMEOUT_MS = 8_000L

@Composable
fun AiSpriteCenter(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onMovieClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onShowClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onRecommendationClick: ((com.tracktosearch.data.ai.AiRecommendation) -> Unit)? = null,
    viewModel: AiSpriteViewModel = rememberSharedAiSpriteViewModel()
) {
    if (!visible) return

    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audioPlayer = remember(context) { AiAudioPlayer(context) }
    val mainScope = rememberCoroutineScope()
    var auditionPlaybackState by remember { mutableStateOf(AuditionPlaybackState.IDLE) }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequested = false
        // 拒权后不能什么都不发生：直接判定语音走不通，把文字兜底入口开出来
        if (granted) viewModel.activate(context) else viewModel.onVoiceActivationUnavailable()
    }

    LaunchedEffect(Unit) {
        // 每次打开精灵中心轮换会话 ID：ViewModel 现在跨页面共享，
        // 不轮换的话会话配额要等 App 重启才重置
        viewModel.onSpriteCenterOpened()
        viewModel.ensureLoaded()
        launch {
            viewModel.audioEvents.collectLatest { audio ->
                audioPlayer.play(
                    audio,
                    onStarted = { mainScope.launch { auditionPlaybackState = AuditionPlaybackState.PLAYING } },
                    onFinished = { mainScope.launch { auditionPlaybackState = AuditionPlaybackState.IDLE } }
                )
            }
        }
        launch {
            viewModel.guestPreviewFallbackEvents.collectLatest { text ->
                audioPlayer.playText(
                    text,
                    onStarted = { mainScope.launch { auditionPlaybackState = AuditionPlaybackState.PLAYING } },
                    onFinished = { mainScope.launch { auditionPlaybackState = AuditionPlaybackState.IDLE } }
                )
            }
        }
    }
    LaunchedEffect(state.selectedCharacterId, state.isAuthorized) {
        audioPlayer.stop()
        auditionPlaybackState = AuditionPlaybackState.LOADING
    }
    // 试听请求挂住时不能永久转圈：超时自动回到可重播状态，否则重播按钮永久禁用
    LaunchedEffect(auditionPlaybackState, state.selectedCharacterId) {
        if (auditionPlaybackState != AuditionPlaybackState.LOADING) return@LaunchedEffect
        kotlinx.coroutines.delay(AUDITION_LOADING_TIMEOUT_MS)
        if (auditionPlaybackState == AuditionPlaybackState.LOADING) {
            auditionPlaybackState = AuditionPlaybackState.IDLE
        }
    }
    DisposableEffect(Unit) {
        onDispose { audioPlayer.stop() }
    }
    BackHandler { if (state.activeFeature != null) viewModel.closeFeature() else onDismiss() }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 6.dp
    ) {
        if (state.activeFeature != null) {
            AiFeatureScreen(
                feature = state.activeFeature!!,
                state = state,
                viewModel = viewModel,
                onBack = viewModel::closeFeature,
                onRefresh = viewModel::refreshFeature,
                onPlayAudio = { audioPlayer.play(it) },
                onMovieClick = onMovieClick,
                onShowClick = onShowClick,
                onRecommendationClick = onRecommendationClick
            )
        } else {
            SpriteCenterHome(
                state = state,
                onDismiss = onDismiss,
                onNavigateToLogin = onNavigateToLogin,
                onSelectCharacter = viewModel::selectCharacter,
                onActivate = {
                    // 主按钮永远是语音：次数没用满就一直能重试，文字兜底是并列的第二入口
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.activate(context)
                    } else if (!permissionRequested) {
                        permissionRequested = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onTextActivate = viewModel::activateByText,
                onOpenFeature = viewModel::openFeature,
                onReloadCharacters = viewModel::reloadCharacters,
                onPlayAudio = { audio -> audioPlayer.play(audio) },
                onReplayAudition = {
                    audioPlayer.stop()
                    auditionPlaybackState = AuditionPlaybackState.LOADING
                    viewModel.replaySelectedCharacter()
                },
                auditionPlaybackState = auditionPlaybackState,
                onClearError = viewModel::clearError
            )
        }
    }

    // 锐评隐私弹窗挂在最外层：功能页与首页两个层级都要能触发
    // 首次使用说明弹窗：同意才允许上传数据并进入功能页
    if (state.showTasteConsent) {
        AlertDialog(
            onDismissRequest = viewModel::onTasteConsentDismissed,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.ai_taste_consent_title)) },
            text = { Text(stringResource(R.string.ai_taste_consent_message)) },
            confirmButton = {
                // AlertDialog 的槽是独立 subcomposition（Dialog 有自己的宿主 View），单独取一份
                val confirmHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    confirmHaptics.tap()
                    viewModel.onTasteConsentAgreed()
                }) {
                    Text(stringResource(R.string.ai_taste_consent_agree))
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    viewModel.onTasteConsentDismissed()
                }) {
                    Text(stringResource(R.string.ai_taste_consent_decline))
                }
            }
        )
    }
    // 功能被设置页开关关闭时的引导弹窗：确认后跳设置页
    if (state.showTasteDisabled) {
        AlertDialog(
            onDismissRequest = viewModel::onTasteDisabledDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            text = {
                Text(
                    stringResource(
                        R.string.ai_taste_disabled_message,
                        stringResource(R.string.ai_feature_taste)
                    )
                )
            },
            confirmButton = {
                // 同上，槽内单独取一份。跳的是本应用自己的设置页签（MainTabNavigator），
                // 不是系统设置，所以不属于「离开本应用」那条静默规则
                val confirmHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    confirmHaptics.tap()
                    // 先收弹窗再跳转：弹窗标志挂在共享 ViewModel 上，离开页面前必须清掉
                    viewModel.onTasteDisabledDismiss()
                    onOpenSettings()
                }) {
                    Text(stringResource(R.string.ai_taste_go_settings))
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = {
                    dismissHaptics.lightTap()
                    viewModel.onTasteDisabledDismiss()
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpriteCenterHome(
    state: AiSpriteUiState,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onSelectCharacter: (String) -> Unit,
    onActivate: () -> Unit,
    onTextActivate: (String) -> Unit,
    onOpenFeature: (AiFeature) -> Unit,
    onReloadCharacters: () -> Unit,
    onPlayAudio: (com.tracktosearch.data.ai.AiAudio) -> Unit,
    onReplayAudition: () -> Unit,
    auditionPlaybackState: AuditionPlaybackState,
    onClearError: () -> Unit
) {
    val character = state.selectedCharacter ?: state.characters.first()
    val scrollState = rememberScrollState()
    val haptics = rememberAppHaptics()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.ai_sprite_center_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = stringResource(R.string.ai_sprite_center_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 关的是盖在搜索页之上的这层面板，不弹导航栈，按「对话框的关闭」给轻一记；
                    // 上面那个 BackHandler 与系统返回手势照旧静默（同 onDismissRequest 的处理）
                    IconButton(onClick = {
                        haptics.lightTap()
                        onDismiss()
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ai_sprite_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
        ) {
            CharacterStage(
                character = character,
                state = state,
                onPlayAudio = onPlayAudio,
                onReplayAudition = onReplayAudition,
                auditionPlaybackState = auditionPlaybackState
            )

            val characterLabel = stringResource(
                R.string.ai_sprite_character_selection,
                character.name,
                character.personalityPrompt
            )
            Text(
                text = characterLabel,
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.characters.forEach { item ->
                    CharacterChoice(
                        character = item,
                        selected = item.id == state.selectedCharacterId,
                        onClick = { onSelectCharacter(item.id) }
                    )
                }
            }

            // 角色目录取不回来时全员「准备中」，这里明确说原因并给重试，而不是让按钮干灰着
            if (state.charactersLoadFailed) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.ai_error_characters_failed),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = {
                            haptics.tap()
                            onReloadCharacters()
                        }) {
                            Text(stringResource(R.string.ai_sprite_characters_retry))
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (!state.isAuthorized) {
                GuestHintCard(onNavigateToLogin = onNavigateToLogin)
            } else if (shouldShowActivatedCharacterContent(
                    selectedCharacterId = character.id,
                    activatedCharacterId = state.activatedCharacterId,
                    isAuthorized = state.isAuthorized
                )
            ) {
                ActivatedStatusPanel(state = state, character = character)
            } else {
                ActivationPanel(
                    state = state,
                    character = character,
                    onActivate = onActivate,
                    onTextActivate = onTextActivate,
                    onClearError = onClearError
                )
            }

            AnimatedVisibility(
                visible = shouldShowActivatedCharacterContent(
                    selectedCharacterId = character.id,
                    activatedCharacterId = state.activatedCharacterId,
                    isAuthorized = state.isAuthorized
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                    GreetingSummaryCard(
                        state = state,
                        onOpenGreeting = { onOpenFeature(AiFeature.GREETING) },
                        onPlayAudio = onPlayAudio
                    )
                    Spacer(Modifier.height(16.dp))
                    FeatureList(state = state, onOpenFeature = onOpenFeature)
                }
            }
        }
    }
}

@Composable
private fun GuestHintCard(onNavigateToLogin: () -> Unit) {
    val haptics = rememberAppHaptics()
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_sprite_guest_hint), style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = {
                        haptics.tap()
                        onNavigateToLogin()
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(stringResource(R.string.ai_sprite_login))
                }
            }
        }
    }
}

/** 激活成功后取代激活面板：不再留一个灰掉的「喊名字来激活」按钮当摆设。 */
@Composable
private fun ActivatedStatusPanel(state: AiSpriteUiState, character: AiCharacter) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ai_sprite_activated_status, character.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.ai_sprite_activated_hint),
                    style = MaterialTheme.typography.bodySmall
                )
                state.quota?.let { quota ->
                    Text(
                        stringResource(
                            R.string.ai_sprite_quota,
                            quota.dailyUsed,
                            quota.dailyLimit,
                            quota.sessionUsed,
                            quota.sessionLimit
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 首页只展示问候正文一句，完整解读/点评留给「昵称欢迎」功能页。
 * 之前首页把三段全铺出来，功能页再原样重复一遍，多一层导航零信息增量。
 */
@Composable
private fun GreetingSummaryCard(
    state: AiSpriteUiState,
    onOpenGreeting: () -> Unit,
    onPlayAudio: (com.tracktosearch.data.ai.AiAudio) -> Unit
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.ai_sprite_greeting_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        state.greeting?.audio?.let { audio ->
            IconButton(onClick = {
                haptics.lightTap()
                onPlayAudio(audio)
            }) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.ai_audio_play))
            }
        }
    }
    val greeting = state.greeting
    if (greeting != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                // 卡片整块进「昵称欢迎」功能页，按列表项进详情给轻一档
                .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onOpenGreeting),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(greeting.greeting, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ai_sprite_greeting_expand),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else if (!state.isLoading && !state.activationMessage.isNullOrBlank()) {
        Text(
            text = state.activationMessage,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CharacterStage(
    character: AiCharacter,
    state: AiSpriteUiState,
    onPlayAudio: (com.tracktosearch.data.ai.AiAudio) -> Unit,
    onReplayAudition: () -> Unit,
    auditionPlaybackState: AuditionPlaybackState
) {
    val showActivatedContent = shouldShowActivatedCharacterContent(
        selectedCharacterId = character.id,
        activatedCharacterId = state.activatedCharacterId,
        isAuthorized = state.isAuthorized
    )
    val transition = rememberInfiniteTransition(label = "sprite_bob")
    val haptics = rememberAppHaptics()
    val bob by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "sprite_scale"
    )
    Surface(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(270.dp),
        shape = RoundedCornerShape(30.dp),
        color = characterTint(character).copy(alpha = 0.20f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            AiCharacterGlyph(
                character = character,
                modifier = Modifier
                    .size(168.dp)
                    .scale(bob)
            )
            if (shouldShowActivationSuccessBadge(character.id, state.activatedCharacterId, state.isAuthorized)) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(character.name, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.ai_sprite_activation_success),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!showActivatedContent) {
                        val auditionContentDescription = when (auditionPlaybackState) {
                            AuditionPlaybackState.LOADING -> stringResource(R.string.ai_audio_loading)
                            AuditionPlaybackState.PLAYING -> stringResource(R.string.ai_audio_playing)
                            AuditionPlaybackState.IDLE -> stringResource(R.string.ai_audio_play)
                        }
                        IconButton(
                            onClick = {
                                // 试听要等 TTS 回来（还有 LOADING 态），这一记是「点到了」的即时回执
                                haptics.lightTap()
                                onReplayAudition()
                            },
                            enabled = auditionPlaybackState != AuditionPlaybackState.LOADING,
                            modifier = Modifier
                                .size(32.dp)
                                .semantics {
                                    contentDescription = auditionContentDescription
                                }
                        ) {
                            when (auditionPlaybackState) {
                                AuditionPlaybackState.LOADING -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                AuditionPlaybackState.PLAYING -> Icon(
                                    Icons.Rounded.GraphicEq,
                                    contentDescription = stringResource(R.string.ai_audio_playing),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                AuditionPlaybackState.IDLE -> Icon(
                                    Icons.Rounded.VolumeUp,
                                    contentDescription = stringResource(R.string.ai_audio_play),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = if (showActivatedContent && state.activationState == AiActivationState.SUCCESS) {
                            state.greeting?.greeting ?: state.activationMessage.orEmpty()
                        } else character.auditionText,
                        // 必须给 weight：Row 里非 weight 子项按顺序吃满剩余宽度，
                        // 长文案会把后面那个播放按钮挤成 0 宽看不见
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                    state.greeting?.audio?.takeIf { showActivatedContent }?.let { audio ->
                        IconButton(
                            onClick = {
                                haptics.lightTap()
                                onPlayAudio(audio)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.ai_audio_play), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterChoice(character: AiCharacter, selected: Boolean, onClick: () -> Unit) {
    val preparingLabel = stringResource(R.string.ai_sprite_preparing)
    // 选中态之前只靠底色和抬升表达，读屏读不出选了谁；补 selected 语义与单选 role
    val choiceDescription = if (character.isAvailable) character.name else "${character.name}, $preparingLabel"
    // selectedCharacterId 是单值赋值、一组里只能选一个（role 也是 RadioButton）→ segmentTick
    val haptics = rememberAppHaptics()
    Surface(
        onClick = {
            haptics.segmentTick()
            onClick()
        },
        modifier = Modifier
            .size(width = 88.dp, height = 112.dp)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
                contentDescription = choiceDescription
            },
        shape = RoundedCornerShape(18.dp),
        color = if (selected) characterTint(character).copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (selected) 4.dp else 0.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(vertical = 9.dp)
        ) {
            AiCharacterGlyph(character, Modifier.size(58.dp))
            Text(character.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            if (!character.isAvailable) {
                Text(preparingLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActivationPanel(
    state: AiSpriteUiState,
    character: AiCharacter,
    onActivate: () -> Unit,
    onTextActivate: (String) -> Unit,
    onClearError: () -> Unit
) {
    var textDialogVisible by remember { mutableStateOf(false) }
    val inFlight = state.activationState == AiActivationState.RECORDING ||
        state.activationState == AiActivationState.VERIFYING
    val voiceAvailable = canRequestVoiceActivation(state.activationAttempt)
    val showTextActivation = shouldShowTextActivation(state)
    val blockedReasonRes = activationBlockedReasonRes(character, state)
    val haptics = rememberAppHaptics()

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        val activationText = when (state.activationState) {
            AiActivationState.RECORDING -> stringResource(R.string.ai_sprite_activating, character.activationWord)
            AiActivationState.VERIFYING -> stringResource(R.string.ai_sprite_activating, character.activationWord)
            else -> stringResource(R.string.ai_sprite_activate, character.activationWord)
        }
        Text(activationText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            // 激活入口整体不可用时说明原因，而不是留一个没有解释的灰按钮
            text = blockedReasonRes?.let { stringResource(it) } ?: stringResource(R.string.ai_sprite_listening),
            style = MaterialTheme.typography.bodySmall,
            color = if (blockedReasonRes != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                // 本页主操作。可能顺带弹系统权限框，但那盖在本应用之上、没离开任务栈，照主操作给 tap
                haptics.tap()
                onActivate()
            },
            enabled = canActivateCharacter(character, state),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            if (inFlight) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(imageVector = Icons.Rounded.Mic, contentDescription = null)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                if (voiceAvailable) {
                    stringResource(R.string.ai_sprite_activate, character.activationWord)
                } else {
                    stringResource(R.string.ai_sprite_voice_limit_reached)
                }
            )
        }
        // 语音失败或麦克风不可用之后才出现的第二入口，点开是输入框而不是直接提交预设名
        AnimatedVisibility(visible = showTextActivation) {
            Column {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.ai_sprite_text_fallback_available),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onClearError()
                        textDialogVisible = true
                    },
                    enabled = canActivateCharacterByText(character, state),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Keyboard, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.ai_sprite_text_fallback))
                }
            }
        }
        if (state.errorCode != null) {
            Text(
                text = stringResource(aiErrorMessageRes(state.errorCode)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        state.quota?.let { quota ->
            Text(
                stringResource(R.string.ai_sprite_quota, quota.dailyUsed, quota.dailyLimit, quota.sessionUsed, quota.sessionLimit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }

    if (textDialogVisible) {
        TextActivationDialog(
            character = character,
            onDismiss = { textDialogVisible = false },
            onConfirm = { typedName ->
                textDialogVisible = false
                onTextActivate(typedName)
            }
        )
    }
}

/** 文字兜底输入框：用户自己敲角色名，敲对了才算激活。 */
@Composable
private fun TextActivationDialog(
    character: AiCharacter,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember(character.id) { mutableStateOf("") }
    val valid = isTextActivationInputValid(input)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.ai_sprite_text_fallback_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ai_sprite_text_fallback_dialog_hint, character.activationWord),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ai_sprite_text_fallback_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            // AlertDialog 的槽是独立 subcomposition（Dialog 有自己的宿主 View），单独取一份
            val confirmHaptics = rememberAppHaptics()
            TextButton(onClick = {
                confirmHaptics.tap()
                onConfirm(input)
            }, enabled = valid) {
                Text(stringResource(R.string.ai_sprite_text_fallback_confirm))
            }
        },
        dismissButton = {
            val dismissHaptics = rememberAppHaptics()
            TextButton(onClick = {
                dismissHaptics.lightTap()
                onDismiss()
            }) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun FeatureList(state: AiSpriteUiState, onOpenFeature: (AiFeature) -> Unit) {
    // 图标按各功能语义选：打招呼挥手 / 点评看单 / 答题闯关 / 冷知识灯泡
    val features = listOf(
        AiFeature.GREETING to Icons.Rounded.WavingHand,
        AiFeature.TASTE to Icons.Rounded.RateReview,
        AiFeature.QUIZ to Icons.Rounded.Quiz,
        AiFeature.DAILY to Icons.Rounded.Lightbulb
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        features.forEach { (feature, icon) ->
            val title = when (feature) {
                AiFeature.GREETING -> stringResource(R.string.ai_feature_greeting)
                AiFeature.TASTE -> stringResource(R.string.ai_feature_taste)
                AiFeature.QUIZ -> stringResource(R.string.ai_feature_quiz)
                AiFeature.DAILY -> stringResource(R.string.ai_feature_daily)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 四个功能行，进各自的功能页，按列表项给轻一档
                    .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onOpenFeature(feature) },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ai_feature_start), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
