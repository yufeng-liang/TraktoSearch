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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiQuizQuestionType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun AiSpriteCenter(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit = {},
    onMovieClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onShowClick: (Int, Int, String, String, Double, Boolean, Boolean) -> Unit = { _, _, _, _, _, _, _ -> },
    onRecommendationClick: ((com.tracktosearch.data.ai.AiRecommendation) -> Unit)? = null,
    viewModel: AiSpriteViewModel = hiltViewModel()
) {
    if (!visible) return

    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audioPlayer = remember(context) { AiAudioPlayer(context) }
    var permissionRequested by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRequested = false
        if (granted) viewModel.activate(context)
    }

    LaunchedEffect(Unit) {
        viewModel.ensureLoaded()
        launch {
            viewModel.audioEvents.collectLatest { audio -> audioPlayer.play(audio) }
        }
        launch {
            viewModel.guestPreviewFallbackEvents.collectLatest { text -> audioPlayer.playText(text) }
        }
    }
    LaunchedEffect(state.selectedCharacterId, state.isAuthorized) {
        audioPlayer.stop()
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
                    when (activationRequestMode(state.activationAttempt)) {
                        AiActivationRequestMode.VOICE -> {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                viewModel.activate(context)
                            } else if (!permissionRequested) {
                                permissionRequested = true
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        AiActivationRequestMode.TEXT -> viewModel.activateByText()
                        AiActivationRequestMode.NONE -> viewModel.clearError()
                    }
                },
                onTextActivate = viewModel::activateByText,
                onOpenFeature = viewModel::openFeature,
                onPlayAudio = { audio -> audioPlayer.play(audio) },
                onClearError = viewModel::clearError
            )
        }
    }
}

@Composable
private fun SpriteCenterHome(
    state: AiSpriteUiState,
    onDismiss: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onSelectCharacter: (String) -> Unit,
    onActivate: () -> Unit,
    onTextActivate: () -> Unit,
    onOpenFeature: (AiFeature) -> Unit,
    onPlayAudio: (com.tracktosearch.data.ai.AiAudio) -> Unit,
    onClearError: () -> Unit
) {
    val character = state.selectedCharacter ?: state.characters.first()
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.ai_sprite_center_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = stringResource(R.string.ai_sprite_center_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ai_sprite_close))
            }
        }

        CharacterStage(
            character = character,
            state = state,
            onPlayAudio = onPlayAudio
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

        Spacer(Modifier.height(14.dp))
        if (state.isAuthorized) {
            ActivationPanel(
                state = state,
                character = character,
                onActivate = onActivate,
                onTextActivate = onTextActivate,
                onClearError = onClearError
            )
        } else {
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
                            onClick = onNavigateToLogin,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                        ) {
                            Text(stringResource(R.string.ai_sprite_login))
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = state.activatedCharacterId != null) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
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
                        IconButton(onClick = { onPlayAudio(audio) }) {
                            Icon(Icons.Rounded.VolumeUp, contentDescription = stringResource(R.string.ai_audio_play))
                        }
                    }
                }
                state.greeting?.let { greeting ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(greeting.greeting, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(greeting.nicknameMeaning, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(greeting.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (state.greeting == null && !state.isLoading) {
                    Text(
                        text = state.activationMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(16.dp))
                FeatureList(state = state, onOpenFeature = onOpenFeature)
            }
        }
    }
}

@Composable
private fun CharacterStage(
    character: AiCharacter,
    state: AiSpriteUiState,
    onPlayAudio: (com.tracktosearch.data.ai.AiAudio) -> Unit
) {
    val transition = rememberInfiniteTransition(label = "sprite_bob")
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
                    Text(stringResource(R.string.ai_sprite_activation_success), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
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
                    Text(
                        text = if (state.activationState == AiActivationState.SUCCESS) {
                            state.greeting?.greeting ?: state.activationMessage.orEmpty()
                        } else character.auditionText,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                    state.greeting?.audio?.let { audio ->
                        IconButton(onClick = { onPlayAudio(audio) }, modifier = Modifier.size(32.dp)) {
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
    Surface(
        modifier = Modifier
            .size(width = 88.dp, height = 112.dp)
            .clickable(onClick = onClick),
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
                Text(stringResource(R.string.ai_sprite_preparing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ActivationPanel(
    state: AiSpriteUiState,
    character: AiCharacter,
    onActivate: () -> Unit,
    onTextActivate: () -> Unit,
    onClearError: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        val requestMode = activationRequestMode(state.activationAttempt)
        val activationText = when (state.activationState) {
            AiActivationState.RECORDING -> stringResource(R.string.ai_sprite_activating, character.activationWord)
            AiActivationState.VERIFYING -> stringResource(R.string.ai_sprite_activating, character.activationWord)
            AiActivationState.SUCCESS -> state.activationMessage ?: stringResource(R.string.ai_sprite_activation_success)
            else -> stringResource(R.string.ai_sprite_activate, character.activationWord)
        }
        Text(activationText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            text = if (requestMode == AiActivationRequestMode.VOICE) {
                stringResource(R.string.ai_sprite_listening)
            } else {
                stringResource(R.string.ai_sprite_text_fallback_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (requestMode == AiActivationRequestMode.TEXT) {
                    onTextActivate()
                } else {
                    onActivate()
                }
            },
            enabled = canActivateCharacter(character, state),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            if (state.activationState == AiActivationState.RECORDING || state.activationState == AiActivationState.VERIFYING) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(
                    imageVector = if (requestMode == AiActivationRequestMode.VOICE) {
                        Icons.Rounded.Mic
                    } else {
                        Icons.Rounded.AutoAwesome
                    },
                    contentDescription = null
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                when (requestMode) {
                    AiActivationRequestMode.VOICE -> stringResource(R.string.ai_sprite_activate, character.activationWord)
                    AiActivationRequestMode.TEXT -> stringResource(R.string.ai_sprite_text_fallback)
                    AiActivationRequestMode.NONE -> stringResource(R.string.ai_sprite_try_again)
                }
            )
        }
        if (state.errorCode != null) {
            Text(
                text = stringResource(R.string.ai_error),
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
}

@Composable
private fun FeatureList(state: AiSpriteUiState, onOpenFeature: (AiFeature) -> Unit) {
    val features = listOf(
        AiFeature.GREETING to Icons.Rounded.AutoAwesome,
        AiFeature.TASTE to Icons.Rounded.Refresh,
        AiFeature.QUIZ to Icons.Rounded.AutoAwesome,
        AiFeature.DAILY to Icons.Rounded.VolumeUp
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
                    .clickable { onOpenFeature(feature) },
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
fun AiCharacterGlyph(character: AiCharacter, modifier: Modifier = Modifier) {
    val tint = characterTint(character)
    Canvas(modifier = modifier.clip(CircleShape).background(tint.copy(alpha = 0.22f))) {
        drawCharacterFace(this, character, tint)
    }
}

private fun drawCharacterFace(scope: DrawScope, character: AiCharacter, tint: Color) {
    with(scope) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f + 8f
        val radius = size.minDimension * 0.28f
        val earHeight = radius * 1.25f
        val earWidth = radius * 0.72f
        val leftEar = Path().apply {
            moveTo(centerX - radius * 0.56f, centerY - radius * 0.55f)
            quadraticBezierTo(centerX - radius * 1.05f, centerY - earHeight, centerX - radius * 0.35f, centerY - radius * 0.86f)
            close()
        }
        val rightEar = Path().apply {
            moveTo(centerX + radius * 0.56f, centerY - radius * 0.55f)
            quadraticBezierTo(centerX + radius * 1.05f, centerY - earHeight, centerX + radius * 0.35f, centerY - radius * 0.86f)
            close()
        }
        if (character.id == "usagi" || character.id == "hachiware" || character.id == "chiikawa") {
            drawPath(leftEar, tint, style = Fill)
            drawPath(rightEar, tint, style = Fill)
        }
        drawCircle(tint, radius, androidx.compose.ui.geometry.Offset(centerX, centerY))
        val eyeY = centerY - radius * 0.05f
        drawCircle(Color(0xFF302B3B), radius * 0.10f, androidx.compose.ui.geometry.Offset(centerX - radius * 0.42f, eyeY))
        drawCircle(Color(0xFF302B3B), radius * 0.10f, androidx.compose.ui.geometry.Offset(centerX + radius * 0.42f, eyeY))
        drawLine(Color(0xFF302B3B), androidx.compose.ui.geometry.Offset(centerX - radius * 0.18f, centerY + radius * 0.34f), androidx.compose.ui.geometry.Offset(centerX + radius * 0.18f, centerY + radius * 0.34f), strokeWidth = radius * 0.09f)
        if (character.id == "usagi") {
            drawCircle(Color(0xFF302B3B), radius * 0.13f, androidx.compose.ui.geometry.Offset(centerX, centerY - radius * 0.52f))
        }
    }
}

private fun characterTint(character: AiCharacter): Color = when (character.id) {
    "chiikawa" -> Color(0xFFFFC8D8)
    "hachiware" -> Color(0xFF9DD8F2)
    "usagi" -> Color(0xFFFFD66B)
    "momonga", "flying-squirrel" -> Color(0xFFD3B3F3)
    "shisa" -> Color(0xFFFFAA80)
    "kurimanju" -> Color(0xFFB68C69)
    else -> Color(0xFFAED9C2)
}
