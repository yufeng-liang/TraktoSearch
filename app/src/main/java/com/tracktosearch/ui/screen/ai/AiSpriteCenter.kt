package com.tracktosearch.ui.screen.ai

import android.Manifest
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.PopupShowEffect
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.floatingDialogColor
import com.tracktosearch.ui.theme.floatingSheetColor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class AuditionPlaybackState {
    IDLE,
    LOADING,
    PLAYING
}

/** 试听 LOADING 的兜底超时：TTS 请求挂住时把按钮还给用户。 */
private const val AUDITION_LOADING_TIMEOUT_MS = 8_000L

/** 按住说话按钮的高度：比普通按钮高一档，手指落上去不至于压着上下边界。 */
private val ACTIVATION_BUTTON_HEIGHT = 56.dp

/** 按住说话按钮的圆角，光环描边要跟着它走才贴合。 */
private val ACTIVATION_BUTTON_CORNER = 16.dp

/** 提示行 / 电平带这一行的最小高度，两种内容共用同一个高度不让面板跳。 */
private val ACTIVATION_HINT_ROW_MIN_HEIGHT = 28.dp

/** 拖出多远算「松手取消」，见 isVoiceCancelArmed。 */
private val VOICE_HOLD_CANCEL_SLOP = 48.dp

/** 禁用态容器色透明度，对齐 Material 3 填充按钮的 disabled 规格。 */
private const val ACTIVATION_DISABLED_CONTAINER_ALPHA = 0.12f

/** 禁用态内容色透明度，对齐 Material 3 填充按钮的 disabled 规格。 */
private const val ACTIVATION_DISABLED_CONTENT_ALPHA = 0.38f

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
        // 授权返回时用户的手指早就抬了，这里自动开录只会录到一段空白，
        // 所以只置一个「已授权，再按一次」的提示码。
        // 拒权仍然判定语音走不通，让文字入口把原因说清楚
        if (granted) viewModel.onVoicePermissionGranted() else viewModel.onVoiceActivationUnavailable()
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
                // 主按钮永远是语音，改成按住说话：次数没用满就一直能重试，文字兜底是并列的第二入口。
                // 权限检查留在这一层——pointerInput 里拿不到 launcher，也没法等系统弹窗
                hasRecordPermission = {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                },
                onRequestRecordPermission = {
                    if (!permissionRequested) {
                        permissionRequested = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onHoldStart = viewModel::onActivatePressStart,
                onHoldEnd = viewModel::onActivatePressEnd,
                onHoldCancel = viewModel::onActivatePressCancel,
                onCancelArmedChanged = viewModel::onVoiceCancelArmedChanged,
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
    // 用户按的是「口味分析」，等来的却是一道前置弹窗 —— 按下与这一层出现之间隔着
    // 一次 DataStore 挂起读，而且弹出来的不是他要的那一页。这一记是在标记这次改道
    PopupShowEffect(state.showTasteConsent)
    if (state.showTasteConsent) {
        AlertDialog(
            onDismissRequest = viewModel::onTasteConsentDismissed,
            containerColor = floatingDialogColor(),
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
    PopupShowEffect(state.showTasteDisabled)
    if (state.showTasteDisabled) {
        AlertDialog(
            onDismissRequest = viewModel::onTasteDisabledDismiss,
            containerColor = floatingDialogColor(),
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
    hasRecordPermission: () -> Boolean,
    onRequestRecordPermission: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onCancelArmedChanged: (Boolean) -> Unit,
    onTextActivate: (String) -> Unit,
    onOpenFeature: (AiFeature) -> Unit,
    onReloadCharacters: () -> Unit,
    onPlayAudio: (com.tracktosearch.data.ai.AiAudio) -> Unit,
    onReplayAudition: () -> Unit,
    auditionPlaybackState: AuditionPlaybackState,
    onClearError: () -> Unit
) {
    val character = state.selectedCharacter ?: state.characters.first()
    val isActivated = shouldShowActivatedCharacterContent(character.id, state.activatedCharacterId, state.isAuthorized)
    var showCharacterPicker by remember { mutableStateOf(false) }
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
                            text = if (isActivated) stringResource(R.string.ai_sprite_workbench_title)
                            else stringResource(R.string.ai_sprite_center_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = if (isActivated) stringResource(R.string.ai_sprite_workbench_subtitle)
                            else stringResource(R.string.ai_sprite_center_subtitle),
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
            if (isActivated) {
                ActivatedCompactHeader(
                    state = state,
                    character = character,
                    onChangeCharacter = { showCharacterPicker = true },
                    onReplayAudition = onReplayAudition,
                    auditionPlaybackState = auditionPlaybackState
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.ai_feature_section_title),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = stringResource(R.string.ai_feature_section_subtitle),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FeatureBento(onOpenFeature = onOpenFeature)
            } else {
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
                    hasRecordPermission = hasRecordPermission,
                    onRequestRecordPermission = onRequestRecordPermission,
                    onHoldStart = onHoldStart,
                    onHoldEnd = onHoldEnd,
                    onHoldCancel = onHoldCancel,
                    onCancelArmedChanged = onCancelArmedChanged,
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
    if (showCharacterPicker) {
        CharacterPickerSheet(
            state = state,
            onDismiss = { showCharacterPicker = false },
            onReloadCharacters = onReloadCharacters,
            onSelectCharacter = { id ->
                showCharacterPicker = false
                onSelectCharacter(id)
            }
        )
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
                // 呼吸缩放经 graphicsLayer 块延迟读：动画每帧只重绘，不让这张卡逐帧重组
                modifier = Modifier
                    .size(168.dp)
                    .graphicsLayer {
                        scaleX = bob
                        scaleY = bob
                    }
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
            val auditionText = if (showActivatedContent && state.activationState == AiActivationState.SUCCESS) {
                state.greeting?.greeting ?: state.activationMessage.orEmpty()
            } else {
                character.auditionText
            }
            if (auditionText.isNotBlank()) {
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
                            text = auditionText,
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
            modifier = Modifier.padding(top = 9.dp, bottom = 4.dp)
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
    hasRecordPermission: () -> Boolean,
    onRequestRecordPermission: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onCancelArmedChanged: (Boolean) -> Unit,
    onTextActivate: (String) -> Unit,
    onClearError: () -> Unit
) {
    var textDialogVisible by remember { mutableStateOf(false) }
    val recording = state.activationState == AiActivationState.RECORDING
    val verifying = state.activationState == AiActivationState.VERIFYING
    val cancelArmed = recording && state.voiceCancelArmed
    val voiceAvailable = canRequestVoiceActivation(state.activationAttempt)
    val showTextActivation = shouldShowTextActivation(state)
    val blockedReasonRes = activationBlockedReasonRes(character, state)
    // 手势闸门和外观要分开看：canActivateCharacter 在 RECORDING/VERIFYING 期间返回 false，
    // 那是为了挡住第二次按下，不是要把用户正按着的按钮画成灰的
    val canStartHold = canActivateCharacter(character, state)
    val looksEnabled = canStartHold || recording || verifying

    val haptics = rememberAppHaptics()
    val previousActivationState = remember { mutableStateOf(state.activationState) }
    LaunchedEffect(state.activationState) {
        // 在协程里读写上一次状态，不在组合期读，免得多触发一次重组
        val previous = previousActivationState.value
        previousActivationState.value = state.activationState
        // RECORDING → VERIFYING 只在流式命中时发生（失败路径都是回 IDLE/FAILED），
        // 拿它当「喊中了」的震动信号就不用再开一条 replay = 0 的事件通道
        if (previous == AiActivationState.RECORDING && state.activationState == AiActivationState.VERIFYING) {
            // 「听到了」是这次按住落到位的那一记，不是「激活成功」——
            // 成功/失败那两记归输出侧在 SUCCESS / FAILED 上发，这里发了就重了
            haptics.gestureEnd()
        }
    }

    // pointerInput 的 key 必须固定成 Unit：按下之后 canActivateCharacter 立刻变 false，
    // 若把它写进 key，节点会在按住途中重建、手势协程被取消，刚开的录音当场夭折。
    // 所以闸门与回调都用 rememberUpdatedState 取最新值，手势节点本身一直不换。
    val currentCanStartHold by rememberUpdatedState(canStartHold)
    val currentHasPermission by rememberUpdatedState(hasRecordPermission)
    val currentRequestPermission by rememberUpdatedState(onRequestRecordPermission)
    val currentHoldStart by rememberUpdatedState(onHoldStart)
    val currentHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentHoldCancel by rememberUpdatedState(onHoldCancel)
    val currentCancelArmedChanged by rememberUpdatedState(onCancelArmedChanged)

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        val activationText = when (state.activationState) {
            AiActivationState.RECORDING -> stringResource(R.string.ai_sprite_activating, character.activationWord)
            AiActivationState.VERIFYING -> stringResource(R.string.ai_sprite_activating, character.activationWord)
            else -> stringResource(R.string.ai_sprite_activate, character.activationWord)
        }
        Text(activationText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // 提示行与读屏用的手势说明是同一句，取一次复用
        val holdGestureHint = stringResource(R.string.ai_sprite_hold_hint, character.activationWord)
        // 提示行与电平带共用这一行：录音时提示文字只是变透明，仍然参与测量，
        // 行高不会因为电平带出现/消失而变，整块面板也就不会在按下那一瞬跳一下
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .heightIn(min = ACTIVATION_HINT_ROW_MIN_HEIGHT),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                // 激活入口整体不可用时说明原因，而不是留一个没有解释的灰按钮
                text = blockedReasonRes?.let { stringResource(it) } ?: holdGestureHint,
                style = MaterialTheme.typography.bodySmall,
                color = if (blockedReasonRes != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.alpha(if (recording) 0f else 1f)
            )
            if (recording) {
                VoiceLevelMeter(level = state.voiceLevel, modifier = Modifier.matchParentSize())
            }
        }
        Spacer(Modifier.height(10.dp))
        val buttonLabel = when {
            // 先判进行中再判次数：命中的那一次会在 VERIFYING 期间把次数记满，
            // 反过来写会在验证途中把文案切成「次数用完」
            verifying -> stringResource(R.string.ai_sprite_heard)
            cancelArmed -> stringResource(R.string.ai_sprite_release_to_cancel)
            recording -> stringResource(R.string.ai_sprite_release_to_activate)
            !voiceAvailable -> stringResource(R.string.ai_sprite_voice_limit_reached)
            else -> stringResource(R.string.ai_sprite_hold_to_talk, character.activationWord)
        }
        val containerColor = when {
            !looksEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = ACTIVATION_DISABLED_CONTAINER_ALPHA)
            // 拖出按钮后直接翻成错误色：光凭文案不够，手指还压在屏幕上时先看到的是颜色
            cancelArmed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
        val contentColor = when {
            !looksEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = ACTIVATION_DISABLED_CONTENT_ALPHA)
            cancelArmed -> MaterialTheme.colorScheme.onError
            else -> MaterialTheme.colorScheme.onPrimary
        }
        // 不能用 Button：它自带的 clickable 会和按住手势抢同一串指针事件，
        // 而按住说话需要的是「按下 / 拖动 / 抬起」三段，不是一个 onClick
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(ACTIVATION_BUTTON_HEIGHT)
                .voiceLevelHalo(
                    level = if (recording) state.voiceLevel else 0f,
                    color = containerColor,
                    cornerRadius = ACTIVATION_BUTTON_CORNER
                )
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    // 读屏读不出「按住」这个动作，把操作说明放 contentDescription、
                    // 当前阶段放 stateDescription；真正的无障碍替代路径是常驻的文字入口
                    contentDescription = holdGestureHint
                    stateDescription = buttonLabel
                    if (!looksEnabled) disabled()
                }
                .pointerInput(Unit) {
                    detectHoldToTalk(
                        canStartHold = { currentCanStartHold },
                        hasRecordPermission = { currentHasPermission() },
                        onRequestRecordPermission = { currentRequestPermission() },
                        // 按住起手那记「抓住了」：这颗按钮走的是裸 pointerInput，
                        // 不经 hapticCombinedClickable，长按那一记得自己发
                        onHoldStart = {
                            haptics.dragStart()
                            currentHoldStart()
                        },
                        // 松手收尾两条路都不发：抬手时页面已经在换状态（VERIFYING 的
                        // gestureEnd、或退回 IDLE），再补一记就是背靠背两下
                        onHoldEnd = { currentHoldEnd() },
                        onHoldCancel = { currentHoldCancel() },
                        onCancelArmedChanged = { armed ->
                            // 只在拖进「松手就取消」那一档发：越界回来不发，
                            // 否则在边界上来回蹭会连成一串
                            if (armed) haptics.thresholdArmed()
                            currentCancelArmedChanged(armed)
                        }
                    )
                },
            shape = RoundedCornerShape(ACTIVATION_BUTTON_CORNER),
            color = containerColor,
            contentColor = contentColor
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (verifying) {
                    // 只有等服务端 ACK 这一段才转圈；录音阶段的「有没有拾到声」交给上方电平带
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = contentColor
                    )
                } else {
                    Icon(
                        imageVector = if (cancelArmed) Icons.Rounded.Close else Icons.Rounded.Mic,
                        contentDescription = null
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = buttonLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
        // 常驻的第二入口：不再要求语音先失败一次。
        // 按住说话对开不了口的场合（图书馆、深夜、会议）本来就走不通，
        // 而且读屏也没法做出「按住」这个手势，这里就是那条替代路径。
        // 点开是输入框而不是直接提交预设名
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

/**
 * 按住说话手势。
 *
 * 没用 detectTapGestures：它只能给出「按住期间是否被取消」一个布尔结果，
 * 表达不了「拖出按钮取消」，而这里需要在按住过程中持续上报手指位置。
 *
 * 每一次 onHoldStart 之后必须恰好走到 onHoldEnd 或 onHoldCancel 一次：
 * 少一次，麦克风与 KWS 解码锁不会释放，下回按住直接拿不到设备；
 * 多一次，同一段按住会被收尾两遍。因此收尾统一放在 finally，用 handled 标记保证只发一次。
 * 系统取消（ACTION_CANCEL、节点销毁、面板被切走）表现为协程被取消，同样会走到 finally，
 * 这正是「系统取消按取消处理」那一条。
 */
private suspend fun PointerInputScope.detectHoldToTalk(
    canStartHold: () -> Boolean,
    hasRecordPermission: () -> Boolean,
    onRequestRecordPermission: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onCancelArmedChanged: (Boolean) -> Unit
) {
    val cancelSlopPx = VOICE_HOLD_CANCEL_SLOP.toPx()
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 按钮不可用（角色没上线 / 次数用满 / 已经有一次按住在跑）时把这次按下放过去。
            // 不消费也就不挡外层 verticalScroll，用户还能从按钮上滑动页面
            if (!canStartHold()) {
                waitForUpOrCancellation()
                continue
            }
            // 权限只能由持有 launcher 的 Composable 去申请，pointerInput 里拿不到。
            // 约定是「只弹窗不开录」：系统弹窗返回时手指早就抬了，这时开录只会录到一段空白
            if (!hasRecordPermission()) {
                onRequestRecordPermission()
                waitForUpOrCancellation()
                continue
            }
            down.consume()
            onHoldStart()
            var armed = false
            var handled = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    // 指针凭空消失（没有抬起事件）也算异常终止，交给 finally 走取消
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // 全程消费：外层是 verticalScroll，不把事件抢下来的话上滑会变成滚页面，
                    // 按钮跟着走位，「拖出取消」就无从判断了
                    change.consume()
                    if (!change.pressed) {
                        handled = true
                        if (armed) onHoldCancel() else onHoldEnd()
                        break
                    }
                    val nextArmed = isVoiceCancelArmed(change.position, size, cancelSlopPx)
                    // 只在跨界那一下上报：move 每帧都来，每帧都发会把 ViewModel 刷爆
                    if (nextArmed != armed) {
                        armed = nextArmed
                        onCancelArmedChanged(armed)
                    }
                }
            } finally {
                if (!handled) onHoldCancel()
                // armed 复位，否则下一次按住会以「松手取消」的红按钮起步
                if (armed) onCancelArmedChanged(false)
            }
        }
    }
}

/**
 * 手指是否已经拖到「松手就取消」的位置。
 *
 * 判的是超出按钮边界 48dp，而不是刚离开边界：按钮只有 56dp 高，
 * 手指接触面本来就压着上下边缘，按边界原样判会在用户按稳的过程中反复跳红。
 * 48dp 也正好是「上滑取消」这个肌肉记忆里的那段距离。
 */
private fun isVoiceCancelArmed(position: Offset, size: IntSize, slopPx: Float): Boolean =
    position.x < -slopPx ||
        position.y < -slopPx ||
        position.x > size.width + slopPx ||
        position.y > size.height + slopPx

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
        containerColor = floatingDialogColor(),
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


@Composable
private fun ActivatedCompactHeader(
    state: AiSpriteUiState,
    character: AiCharacter,
    onChangeCharacter: () -> Unit,
    onReplayAudition: () -> Unit,
    auditionPlaybackState: AuditionPlaybackState
) {
    val audioLabel = when (auditionPlaybackState) {
        AuditionPlaybackState.LOADING -> stringResource(R.string.ai_audio_loading)
        AuditionPlaybackState.PLAYING -> stringResource(R.string.ai_audio_playing)
        AuditionPlaybackState.IDLE -> stringResource(R.string.ai_audio_play)
    }
    Surface(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = characterTint(character).copy(alpha = 0.18f),
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(Modifier.size(64.dp), RoundedCornerShape(20.dp), color = characterTint(character).copy(alpha = 0.42f)) {
                Box(contentAlignment = Alignment.Center) { AiCharacterGlyph(character, Modifier.size(54.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.ai_sprite_activated_status, character.name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    state.quota?.let { q -> stringResource(R.string.ai_sprite_quota, q.dailyUsed, q.dailyLimit, q.sessionUsed, q.sessionLimit) }
                        ?: stringResource(R.string.ai_sprite_quota_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onReplayAudition,
                enabled = auditionPlaybackState != AuditionPlaybackState.LOADING,
                modifier = Modifier.semantics { contentDescription = audioLabel }
            ) {
                when (auditionPlaybackState) {
                    AuditionPlaybackState.LOADING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    AuditionPlaybackState.PLAYING -> Icon(Icons.Rounded.GraphicEq, stringResource(R.string.ai_audio_playing), tint = MaterialTheme.colorScheme.primary)
                    AuditionPlaybackState.IDLE -> Icon(Icons.Rounded.VolumeUp, stringResource(R.string.ai_audio_play), tint = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = onChangeCharacter, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.ai_sprite_change_character))
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CharacterPickerSheet(
    state: AiSpriteUiState,
    onDismiss: () -> Unit,
    onReloadCharacters: () -> Unit,
    onSelectCharacter: (String) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = floatingSheetColor(),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(
                start = 20.dp,
                end = 20.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.ai_sprite_character_picker_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(stringResource(R.string.ai_sprite_character_picker_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            state.characters.forEach { item ->
                CharacterSheetRow(item, item.id == state.selectedCharacterId) { onSelectCharacter(item.id) }
            }
            if (state.charactersLoadFailed) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ai_error_characters_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onReloadCharacters) { Text(stringResource(R.string.ai_sprite_characters_retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterSheetRow(character: AiCharacter, selected: Boolean, onClick: () -> Unit) {
    val availability = if (character.isAvailable) stringResource(R.string.ai_sprite_audition_available) else stringResource(R.string.ai_sprite_preparing)
    Surface(
        onClick = onClick,
        modifier = Modifier.semantics { this.selected = selected; role = Role.RadioButton },
        shape = RoundedCornerShape(20.dp),
        color = if (selected) characterTint(character).copy(alpha = 0.24f) else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(58.dp), RoundedCornerShape(18.dp), color = characterTint(character).copy(alpha = 0.32f)) {
                Box(contentAlignment = Alignment.Center) { AiCharacterGlyph(character, Modifier.size(50.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (selected) Text(stringResource(R.string.ai_sprite_current_character), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(availability, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.ChevronRight, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


@Composable
private fun FeatureBento(onOpenFeature: (AiFeature) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BentoFeatureCard(AiFeature.TASTE, Modifier.fillMaxWidth(), true) { onOpenFeature(AiFeature.TASTE) }
        BentoFeatureCard(AiFeature.QUIZ, Modifier.fillMaxWidth(), true) { onOpenFeature(AiFeature.QUIZ) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoFeatureCard(AiFeature.GREETING, Modifier.weight(1f), false) { onOpenFeature(AiFeature.GREETING) }
            BentoFeatureCard(AiFeature.DAILY, Modifier.weight(1f), false) { onOpenFeature(AiFeature.DAILY) }
        }
    }
}

@Composable
private fun BentoFeatureCard(
    feature: AiFeature,
    modifier: Modifier,
    primary: Boolean,
    onClick: () -> Unit
) {
    val icon = when (feature) {
        AiFeature.GREETING -> Icons.Rounded.WavingHand
        AiFeature.TASTE -> Icons.Rounded.RateReview
        AiFeature.QUIZ -> Icons.Rounded.Quiz
        AiFeature.DAILY -> Icons.Rounded.Lightbulb
    }
    // 图标底色统一跟随主题色，不再按功能分配不同色相。
    val tint = MaterialTheme.colorScheme.primary
    val title = when (feature) {
        AiFeature.GREETING -> stringResource(R.string.ai_feature_greeting)
        AiFeature.TASTE -> stringResource(R.string.ai_feature_taste)
        AiFeature.QUIZ -> stringResource(R.string.ai_feature_quiz)
        AiFeature.DAILY -> stringResource(R.string.ai_feature_daily)
    }
    val description = when (feature) {
        AiFeature.GREETING -> stringResource(R.string.ai_feature_greeting_description)
        AiFeature.TASTE -> stringResource(R.string.ai_feature_taste_description)
        AiFeature.QUIZ -> stringResource(R.string.ai_feature_quiz_description)
        AiFeature.DAILY -> stringResource(R.string.ai_feature_daily_description)
    }
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (primary) 174.dp else 190.dp),
        shape = RoundedCornerShape(if (primary) 26.dp else 22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (primary) 2.dp else 1.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(if (primary) 52.dp else 46.dp), RoundedCornerShape(18.dp), color = tint.copy(alpha = 0.22f)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(if (primary) 28.dp else 24.dp), tint) }
            }
            Text(title, style = if (primary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(
                description,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (primary) 3 else 4
            )
        }
    }
}
