package com.tracktosearch.ui.screen.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.ai.provider.AiFeatureSlot
import com.tracktosearch.data.ai.provider.AiModelProbe
import com.tracktosearch.data.ai.provider.AiProviderConfig
import com.tracktosearch.data.ai.provider.AiProviderProtocol
import com.tracktosearch.data.ai.provider.AiProviderSettings
import com.tracktosearch.ui.component.SubPageTopBar

/**
 * BYOK 服务商配置页（v2）。
 *
 * 页面结构：配置概览 → 文本服务商（必需）→ 语音服务商（可选）→ 图片生成服务商（可选）
 * → 按功能分配模型 → 偏好。
 *
 * 模型不再手输：填好地址与 Key 后点「拉取模型」从服务商目录取回，勾选启用，
 * 点模型名设为主模型，每个模型都能单独测一次真实请求验证可用性。
 * Key 保存后不回显（只在首次输入时存在一次）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiProviderSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statusMap by viewModel.status.collectAsStateWithLifecycle()
    val withKey by viewModel.withKey.collectAsStateWithLifecycle()

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
            Spacer(Modifier.height(8.dp))
            ProviderSummaryCard(settings, withKey)
            Spacer(Modifier.height(18.dp))
            ProviderCard(
                providerId = AiProviderConfig.GLOBAL_ID,
                title = stringResource(R.string.ai_provider_text_section),
                description = stringResource(R.string.ai_provider_text_desc),
                badge = stringResource(R.string.ai_provider_badge_required),
                allowProtocolChoice = true,
                probeWarning = null,
                settings = settings,
                viewModel = viewModel,
                hasStoredKey = AiProviderConfig.GLOBAL_ID in withKey,
                status = statusMap[AiProviderConfig.GLOBAL_ID] ?: ProviderUiStatus()
            )
            Spacer(Modifier.height(14.dp))
            ProviderCard(
                providerId = AiProviderConfig.TTS_ID,
                title = stringResource(R.string.ai_provider_tts_section),
                description = stringResource(R.string.ai_provider_tts_desc),
                badge = stringResource(R.string.ai_provider_badge_optional),
                allowProtocolChoice = false,
                probeWarning = null,
                settings = settings,
                viewModel = viewModel,
                hasStoredKey = AiProviderConfig.TTS_ID in withKey,
                status = statusMap[AiProviderConfig.TTS_ID] ?: ProviderUiStatus()
            )
            Spacer(Modifier.height(14.dp))
            ProviderCard(
                providerId = AiProviderConfig.IMAGE_ID,
                title = stringResource(R.string.ai_provider_image_section),
                description = stringResource(R.string.ai_provider_image_desc),
                badge = stringResource(R.string.ai_provider_badge_optional),
                allowProtocolChoice = false,
                probeWarning = stringResource(R.string.ai_provider_image_probe_warning),
                settings = settings,
                viewModel = viewModel,
                hasStoredKey = AiProviderConfig.IMAGE_ID in withKey,
                status = statusMap[AiProviderConfig.IMAGE_ID] ?: ProviderUiStatus()
            )
            Spacer(Modifier.height(22.dp))
            SlotModelSection(settings, viewModel)
            Spacer(Modifier.height(22.dp))
            PreferencesSection(settings, viewModel)
            // 底部留白：手势条与滚动尽头之间留一屏呼吸感
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** 顶部概览：一句话状态 + 三个服务商的配置情况 + 主模型。 */
@Composable
private fun ProviderSummaryCard(settings: AiProviderSettings, withKey: Set<String>) {
    // 「已配置」= 地址/模型齐 + 本机存过 Key，两者缺一实际都跑不通
    val ready = settings.global.isConfigured && AiProviderConfig.GLOBAL_ID in withKey
    val container = if (ready) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainer = if (ready) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(color = container, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = onContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (ready) R.string.ai_provider_summary_ready
                        else R.string.ai_provider_summary_pending
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainer
                )
            }
            Text(
                text = if (ready) {
                    stringResource(R.string.ai_provider_summary_primary, settings.global.model)
                } else {
                    stringResource(R.string.ai_provider_summary_pending_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.86f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderStatusChip(
                    label = stringResource(R.string.ai_provider_status_text),
                    configured = settings.global.isConfigured && AiProviderConfig.GLOBAL_ID in withKey,
                    emphasize = true
                )
                ProviderStatusChip(
                    label = stringResource(R.string.ai_provider_status_tts),
                    configured = settings.tts.isConfigured && AiProviderConfig.TTS_ID in withKey,
                    emphasize = false
                )
                ProviderStatusChip(
                    label = stringResource(R.string.ai_provider_status_image),
                    configured = settings.image.isConfigured && AiProviderConfig.IMAGE_ID in withKey,
                    emphasize = false
                )
            }
        }
    }
}

@Composable
private fun ProviderStatusChip(label: String, configured: Boolean, emphasize: Boolean) {
    // 概览卡本身是浅色容器：chip 必须自带底色与描边，否则会和卡片糊成一片，
    // 三个状态看起来像卡片上的普通文字
    val bg = when {
        configured && emphasize -> MaterialTheme.colorScheme.primary
        configured -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when {
        configured && emphasize -> MaterialTheme.colorScheme.onPrimary
        configured -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = when {
        configured && emphasize -> MaterialTheme.colorScheme.primary
        configured -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(
            // 已配置加勾，未配置只留名称——避免为两种状态各建一个格式化字符串
            text = if (configured) "$label ✓" else label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * 一个服务商的配置卡：协议 → 地址 → Key → 拉取模型 → 模型列表（勾选/设主模型/逐个测试）→ 保存。
 */
@Composable
private fun ProviderCard(
    providerId: String,
    title: String,
    description: String,
    badge: String,
    allowProtocolChoice: Boolean,
    probeWarning: String?,
    settings: AiProviderSettings,
    viewModel: AiProviderSettingsViewModel,
    hasStoredKey: Boolean,
    status: ProviderUiStatus
) {
    val provider = settings.provider(providerId)
    // 语音/图片服务商只支持 OpenAI 兼容协议，这里直接收敛，避免界面上留下无效协议
    val initialProtocol = if (allowProtocolChoice) provider.protocol else AiProviderProtocol.OPENAI_COMPATIBLE
    var protocol by rememberSaveable(providerId, initialProtocol.name) { mutableStateOf(initialProtocol) }
    var baseUrl by rememberSaveable(providerId, provider.baseUrl) { mutableStateOf(provider.baseUrl) }
    var apiKey by rememberSaveable(providerId) { mutableStateOf("") }
    var showAllModels by rememberSaveable(providerId) { mutableStateOf(false) }
    val hasKey = hasStoredKey
    val models = provider.availableModels
    // 默认只展示已勾选/主模型 + 前几个，模型多的中转站不至于一屏全是列表
    val visibleModels = remember(models, provider.enabledModels, provider.model, showAllModels) {
        if (showAllModels) {
            models
        } else {
            (models.filter { it in provider.enabledModels || it == provider.model } + models.take(6)).distinct()
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 标题行：名称 + 必需/可选徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (allowProtocolChoice) {
                ProtocolSegmented(protocol) { protocol = it }
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
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.ai_provider_api_key)) },
                placeholder = {
                    Text(
                        if (hasKey) stringResource(R.string.ai_provider_key_saved)
                        else stringResource(R.string.ai_provider_api_key_hint)
                    )
                },
                trailingIcon = if (hasKey) {
                    { Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            // 主操作行：拉取模型（顺带保存地址/Key）与保存并排，省一屏纵向空间
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { viewModel.fetchModels(providerId, protocol, baseUrl, apiKey) },
                    enabled = !status.fetchingModels && baseUrl.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (status.fetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ai_provider_fetching))
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ai_provider_fetch_models))
                    }
                }
                // 保存：地址/协议/主模型落盘，Key 单独进 Keystore
                Button(
                    onClick = {
                        viewModel.saveProvider(
                            providerId = providerId,
                            protocol = protocol,
                            baseUrl = baseUrl,
                            model = provider.model,
                            apiKey = apiKey
                        )
                    },
                    enabled = baseUrl.isNotBlank() && provider.model.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.ai_provider_save))
                }
            }
            if (status.fetchingModels) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            status.fetchError?.let { error ->
                Text(
                    text = stringResource(R.string.ai_provider_fetch_failed, error.take(180)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            status.saveError?.let { error ->
                Text(
                    text = error.take(120),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (status.saved) {
                Text(
                    text = stringResource(R.string.ai_provider_saved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            probeWarning?.let { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 模型区：目录为空时给引导，拉到了就逐行勾选/设主模型/测试
            if (models.isEmpty()) {
                Text(
                    text = stringResource(R.string.ai_provider_models_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.ai_provider_models_count, models.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    // 测试全部：逐个串行探测，结果直接显示在每行模型右侧
                    TextButton(
                        onClick = {
                            viewModel.probeModels(
                                providerId = providerId,
                                protocol = protocol,
                                baseUrl = baseUrl,
                                models = models,
                                apiKey = apiKey
                            )
                        },
                        enabled = status.probingModels.isEmpty() && baseUrl.isNotBlank()
                    ) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.ai_provider_probe_all))
                    }
                }
                Text(
                    text = stringResource(R.string.ai_provider_model_primary_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                visibleModels.forEach { model ->
                    ModelRow(
                        model = model,
                        enabled = model in provider.enabledModels,
                        primary = model == provider.model,
                        probe = status.probes[model],
                        probing = model in status.probingModels,
                        onToggle = { checked -> viewModel.toggleModel(providerId, model, checked) },
                        onSetPrimary = { viewModel.setPrimaryModel(providerId, model) },
                        onProbe = {
                            viewModel.probeModels(
                                providerId = providerId,
                                protocol = protocol,
                                baseUrl = baseUrl,
                                models = listOf(model),
                                apiKey = apiKey
                            )
                        }
                    )
                }
                // 模型多时先露前几个，其余按需展开
                if (models.size > visibleModels.size) {
                    TextButton(
                        onClick = { showAllModels = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ai_provider_show_all_models, models.size))
                    }
                } else if (showAllModels) {
                    TextButton(
                        onClick = { showAllModels = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ai_provider_show_less_models))
                    }
                }
            }
        }
    }
}

/** 单行模型：勾选启用、点名字设主模型、右侧单独测试。 */
@Composable
private fun ModelRow(
    model: String,
    enabled: Boolean,
    primary: Boolean,
    probe: AiModelProbe?,
    probing: Boolean,
    onToggle: (Boolean) -> Unit,
    onSetPrimary: () -> Unit,
    onProbe: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSetPrimary)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = enabled, onCheckedChange = onToggle)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when {
                probing -> Text(
                    text = stringResource(R.string.ai_provider_testing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                probe?.ok == true -> Text(
                    text = stringResource(R.string.ai_provider_probe_ok, probe.latencyMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                probe != null -> Text(
                    text = stringResource(
                        R.string.ai_provider_probe_fail,
                        (probe.message ?: "").take(80)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (primary) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    text = stringResource(R.string.ai_provider_model_primary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        TextButton(onClick = onProbe, enabled = !probing) {
            Text(stringResource(R.string.ai_provider_probe))
        }
    }
}

/** 协议三选：OpenAI 兼容 / Gemini / Anthropic。 */
@Composable
private fun ProtocolSegmented(current: AiProviderProtocol, onSelect: (AiProviderProtocol) -> Unit) {
    val options = listOf(
        AiProviderProtocol.OPENAI_COMPATIBLE to stringResource(R.string.ai_provider_protocol_openai),
        AiProviderProtocol.GEMINI to stringResource(R.string.ai_provider_protocol_gemini),
        AiProviderProtocol.ANTHROPIC to stringResource(R.string.ai_provider_protocol_anthropic)
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (protocol, label) ->
            SegmentedButton(
                selected = current == protocol,
                onClick = { onSelect(protocol) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary,
                    activeContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                // 三选一在中日韩文案下每条只有三分之一宽：去掉选中勾图标，
                // 选中态靠实心底色表达，文字才不会被挤到贴边或省略号
                icon = {},
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

/** 按功能分配模型：默认都用主模型，需要时给某个功能单独选。 */
@Composable
private fun SlotModelSection(settings: AiProviderSettings, viewModel: AiProviderSettingsViewModel) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val pool = settings.global.modelPool
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
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pool.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ai_provider_overrides_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    AiFeatureSlot.CHAT_SLOTS.forEach { slot ->
                        SlotModelRow(
                            label = stringResource(slotLabelRes(slot)),
                            current = settings.modelOverrides[slot.name].orEmpty(),
                            primaryModel = settings.global.model,
                            pool = pool,
                            onSelect = { viewModel.saveModelOverride(slot, it) }
                        )
                    }
                }
            }
        }
    }
}

/** 一个功能位的模型选择：跟随主模型，或从模型池里挑一个。 */
@Composable
private fun SlotModelRow(
    label: String,
    current: String,
    primaryModel: String,
    pool: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val followPrimary = current.isBlank()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(88.dp)
        )
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = if (followPrimary) {
                    if (primaryModel.isBlank()) {
                        stringResource(R.string.ai_provider_follow_global)
                    } else {
                        stringResource(R.string.ai_provider_follow_primary, primaryModel)
                    }
                } else {
                    current
                },
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
            )
            // 覆盖在输入框上的点击层：readOnly 输入框本身不响应点击展开
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { expanded = true }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ai_provider_follow_global)) },
                    onClick = {
                        onSelect("")
                        expanded = false
                    }
                )
                pool.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onSelect(model)
                            expanded = false
                        }
                    )
                }
            }
        }
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
