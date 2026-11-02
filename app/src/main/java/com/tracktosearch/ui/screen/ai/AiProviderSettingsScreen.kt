package com.tracktosearch.ui.screen.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.ai.provider.AiFeatureSlot
import com.tracktosearch.data.ai.provider.AiProviderConfig
import com.tracktosearch.data.ai.provider.AiProviderErrorCode
import com.tracktosearch.data.ai.provider.AiProviderException
import com.tracktosearch.data.ai.provider.AiProviderProtocol
import com.tracktosearch.data.ai.provider.AiProviderSettings
import com.tracktosearch.ui.component.SubPageTopBar

/**
 * BYOK Provider 配置页：全局服务商 + 语音/配图服务商 + 按功能覆盖模型（高级）+ 偏好开关。
 * Key 保存后不回显（只在首次输入时存在），全部改动点「保存」落 DataStore/Keystore。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiProviderSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            SubPageTopBar(
                title = stringResource(R.string.ai_provider_settings_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.ai_feature_back),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                windowInsets = TopAppBarDefaults.windowInsets
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            if (!settings.global.isConfigured) {
                // 无 Key 引导：放最顶上，第一次进来第一眼就该看到
                Text(
                    text = stringResource(R.string.ai_provider_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            ProviderSection(
                providerId = AiProviderConfig.GLOBAL_ID,
                heading = stringResource(R.string.ai_provider_global_section),
                description = stringResource(R.string.ai_provider_global_desc),
                protocolLockedToOpenAi = false,
                settings = settings,
                viewModel = viewModel,
                testState = testState
            )
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            ProviderSection(
                providerId = AiProviderConfig.MEDIA_ID,
                heading = stringResource(R.string.ai_provider_media_section),
                description = stringResource(R.string.ai_provider_media_desc),
                protocolLockedToOpenAi = true,
                settings = settings,
                viewModel = viewModel,
                testState = testState
            )
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            ModelOverridesSection(settings, viewModel)
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))
            PreferencesSection(settings, viewModel)
            // 底部留白：手势条与滚动尽头之间留一屏呼吸感
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** 一条 Provider（全局 / 语音配图）的编辑卡：协议、地址、模型、Key、保存与测试。 */
@Composable
private fun ProviderSection(
    providerId: String,
    heading: String,
    description: String,
    protocolLockedToOpenAi: Boolean,
    settings: AiProviderSettings,
    viewModel: AiProviderSettingsViewModel,
    testState: ProviderTestState
) {
    val current = if (providerId == AiProviderConfig.MEDIA_ID) settings.media else settings.global
    var protocol by rememberSaveable(current.protocol.name) {
        mutableStateOf(if (protocolLockedToOpenAi) AiProviderProtocol.OPENAI_COMPATIBLE else current.protocol)
    }
    var baseUrl by rememberSaveable(current.baseUrl) { mutableStateOf(current.baseUrl) }
    var model by rememberSaveable(current.model) { mutableStateOf(current.model) }
    var apiKey by rememberSaveable(providerId) { mutableStateOf("") }
    var savedTick by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!protocolLockedToOpenAi) {
            ProtocolSelector(protocol) { protocol = it }
        } else {
            Text(
                text = stringResource(R.string.ai_provider_protocol_openai_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.ai_provider_base_url)) },
            placeholder = { Text(stringResource(R.string.ai_provider_base_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text(stringResource(R.string.ai_provider_model)) },
            placeholder = { Text(stringResource(R.string.ai_provider_model_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.ai_provider_api_key)) },
            placeholder = {
                Text(
                    if (viewModel.hasKey(providerId)) stringResource(R.string.ai_provider_key_saved)
                    else stringResource(R.string.ai_provider_api_key_hint)
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    viewModel.saveProvider(providerId, protocol, baseUrl, model, apiKey) {
                        savedTick = true
                    }
                },
                enabled = baseUrl.isNotBlank() && model.isNotBlank()
            ) {
                Text(stringResource(R.string.ai_provider_save))
            }
            TextButton(
                onClick = { viewModel.testConnection(providerId, protocol, baseUrl, model, apiKey) },
                enabled = testState.runningFor == null && baseUrl.isNotBlank() && model.isNotBlank()
            ) {
                Text(
                    if (testState.runningFor == providerId) stringResource(R.string.ai_provider_testing)
                    else stringResource(R.string.ai_provider_test)
                )
            }
            if (savedTick && testState.successFor != providerId) {
                Text(
                    text = stringResource(R.string.ai_provider_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        // 测试结果只保留一行：成功绿字，失败红字带对端 message（截断防刷屏）
        when {
            testState.runningFor == providerId -> Unit
            testState.successFor == providerId -> Text(
                text = stringResource(R.string.ai_provider_test_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            testState.failureFor == providerId -> Text(
                text = stringResource(
                    R.string.ai_provider_test_fail,
                    (testState.failureMessage ?: "").take(160)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** 协议三选：OpenAI 兼容 / Gemini / Anthropic（分段按钮风格用 TextButton 排开）。 */
@Composable
private fun ProtocolSelector(current: AiProviderProtocol, onSelect: (AiProviderProtocol) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ProtocolOption(stringResource(R.string.ai_provider_protocol_openai), current == AiProviderProtocol.OPENAI_COMPATIBLE) {
            onSelect(AiProviderProtocol.OPENAI_COMPATIBLE)
        }
        ProtocolOption(stringResource(R.string.ai_provider_protocol_gemini), current == AiProviderProtocol.GEMINI) {
            onSelect(AiProviderProtocol.GEMINI)
        }
        ProtocolOption(stringResource(R.string.ai_provider_protocol_anthropic), current == AiProviderProtocol.ANTHROPIC) {
            onSelect(AiProviderProtocol.ANTHROPIC)
        }
    }
}

@Composable
private fun ProtocolOption(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 高级区：8 个功能位逐个覆盖模型；留空即跟随全局。 */
@Composable
private fun ModelOverridesSection(settings: AiProviderSettings, viewModel: AiProviderSettingsViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.ai_provider_overrides_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.ai_provider_overrides_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.ai_provider_collapse else R.string.ai_provider_expand))
            }
        }
        if (expanded) {
            AiFeatureSlot.entries.forEach { slot ->
                SlotOverrideRow(
                    label = stringResource(slotLabelRes(slot)),
                    initial = settings.modelOverrides[slot.name].orEmpty(),
                    onCommit = { viewModel.saveModelOverride(slot, it) }
                )
            }
        }
    }
}

@Composable
private fun SlotOverrideRow(label: String, initial: String, onCommit: (String) -> Unit) {
    var value by rememberSaveable(label, initial) { mutableStateOf(initial) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(label) },
            placeholder = { Text(stringResource(R.string.ai_provider_follow_global)) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        // 提交策略：失焦（onValueChangeFinished）即写 DataStore；空串=清除覆盖恢复全局
        if (initial.isNotBlank() || value.isNotBlank()) {
            TextButton(onClick = { onCommit("") }) {
                Text(stringResource(R.string.ai_provider_clear_override))
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(value) {
        // 输入稳定后再提交，避免每个字符一次写盘
        kotlinx.coroutines.delay(600)
        if (value.trim() != initial) onCommit(value)
    }
}

/** 偏好开关：音色跟随角色 + 出题预生成。 */
@Composable
private fun PreferencesSection(settings: AiProviderSettings, viewModel: AiProviderSettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.ai_provider_preferences_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        SwitchRow(
            title = stringResource(R.string.ai_provider_voice_follow),
            description = stringResource(R.string.ai_provider_voice_follow_desc),
            checked = settings.voiceFollowCharacter,
            onCheckedChange = { viewModel.setVoiceFollowCharacter(it) }
        )
        SwitchRow(
            title = stringResource(R.string.ai_provider_quiz_pregen),
            description = stringResource(R.string.ai_provider_quiz_pregen_desc),
            checked = settings.quizPregenerateEnabled,
            onCheckedChange = { viewModel.setQuizPregenerateEnabled(it) }
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun slotLabelRes(slot: AiFeatureSlot): Int = when (slot) {
    AiFeatureSlot.GREETING -> R.string.ai_provider_slot_greeting
    AiFeatureSlot.TASTE -> R.string.ai_provider_slot_taste
    AiFeatureSlot.QUIZ -> R.string.ai_provider_slot_quiz
    AiFeatureSlot.DAILY -> R.string.ai_provider_slot_daily
    AiFeatureSlot.DETAIL -> R.string.ai_provider_slot_detail
    AiFeatureSlot.RANK -> R.string.ai_provider_slot_rank
    AiFeatureSlot.TTS -> R.string.ai_provider_slot_tts
    AiFeatureSlot.DAILY_IMAGE -> R.string.ai_provider_slot_daily_image
}
