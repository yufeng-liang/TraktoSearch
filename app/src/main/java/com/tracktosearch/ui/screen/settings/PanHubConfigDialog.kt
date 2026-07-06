package com.tracktosearch.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.data.remote.panhub.ChannelGroup
import com.tracktosearch.data.remote.panhub.PanHubChannel
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.remote.panhub.PanHubPlugin
import com.tracktosearch.R
import com.tracktosearch.ui.theme.appSwitchColors
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.HapticType

@Composable
fun PanHubConfigDialog(
    config: PanHubConfig,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConcurrencyChange: (Int) -> Unit,
    onTimeoutMsChange: (Int) -> Unit,
    onEnabledPluginsChange: (Set<String>) -> Unit,
    onEnabledChannelsChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var concurrencyText by remember(config) { mutableStateOf(config.concurrency.toString()) }
    var timeoutText by remember(config) { mutableStateOf(config.timeoutMs.toString()) }
    var enabledPlugins by remember(config) { mutableStateOf(config.enabledPlugins) }
    var enabledChannels by remember(config) { mutableStateOf(config.enabledChannels) }

    val view = LocalView.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.panhub_config_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 启用开关（顶部，Panhub 卡片只负责进入此弹窗）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_panhub_enabled),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { view.performHaptic(HapticType.CLICK); onEnabledChange(it) },
                        colors = appSwitchColors()
                    )
                }
                HorizontalDivider()

                // 并发数
                Column {
                    Text(stringResource(R.string.panhub_concurrency), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = (concurrencyText.toIntOrNull() ?: 4).toFloat(),
                        onValueChange = { v ->
                            val intVal = v.roundToInt()
                            val oldVal = concurrencyText.toIntOrNull() ?: 4
                            if (intVal != oldVal) {
                                view.performHaptic(HapticType.TICK)
                            }
                            concurrencyText = intVal.toString()
                            onConcurrencyChange(intVal)
                        },
                        valueRange = 1f..16f,
                        steps = 14
                    )
                    Text(
                        text = concurrencyText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                // 超时
                OutlinedTextField(
                    value = timeoutText,
                    onValueChange = { v ->
                        timeoutText = v
                        v.toIntOrNull()?.let { onTimeoutMsChange(it) }
                    },
                    label = { Text(stringResource(R.string.panhub_timeout)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 插件标题 + 全选按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.panhub_plugins), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val allPluginsSelected = enabledPlugins.size == PanHubPlugin.entries.size
                    TextButton(onClick = {
                        val newSet = if (allPluginsSelected) emptySet() else PanHubPlugin.entries.map { it.id }.toSet()
                        enabledPlugins = newSet
                        onEnabledPluginsChange(newSet)
                    }) {
                        Text(if (allPluginsSelected) stringResource(R.string.panhub_deselect_all) else stringResource(R.string.panhub_select_all))
                    }
                }

                // 插件列表：每行2项
                PanHubPlugin.entries.chunked(2).forEach { rowPlugins ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPlugins.forEach { plugin ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = plugin.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = plugin.id in enabledPlugins,
                                    onCheckedChange = { enabled ->
                                        view.performHaptic(HapticType.CLICK)
                                        val updated = enabledPlugins.toMutableSet()
                                        if (enabled) updated.add(plugin.id) else updated.remove(plugin.id)
                                        enabledPlugins = updated
                                        onEnabledPluginsChange(updated)
                                    },
                                    colors = appSwitchColors()
                                )
                            }
                        }
                        if (rowPlugins.size < 2) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                // 频道标题 + 全选按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.panhub_channels), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val allChannelsSelected = enabledChannels.size == PanHubChannel.entries.size
                    TextButton(onClick = {
                        val newSet = if (allChannelsSelected) emptySet() else PanHubChannel.entries.map { it.id }.toSet()
                        enabledChannels = newSet
                        onEnabledChannelsChange(newSet)
                    }) {
                        Text(if (allChannelsSelected) stringResource(R.string.panhub_deselect_all) else stringResource(R.string.panhub_select_all))
                    }
                }

                // 频道分组
                ChannelGroup.entries.forEach { group ->
                    val groupChannels = PanHubChannel.entries.filter { it.group == group }
                    val enabledCount = groupChannels.count { it.id in enabledChannels }
                    val allEnabledInGroup = enabledCount == groupChannels.size
                    var expanded by remember { mutableStateOf(false) }

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expanded = !expanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "${group.displayName} ($enabledCount/${groupChannels.size})",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            // 每组的全选/全不选开关
                            Switch(
                                checked = allEnabledInGroup,
                                onCheckedChange = { allOn ->
                                    view.performHaptic(HapticType.CLICK)
                                    val updated = enabledChannels.toMutableSet()
                                    if (allOn) {
                                        updated.addAll(groupChannels.map { it.id })
                                    } else {
                                        updated.removeAll(groupChannels.map { it.id })
                                    }
                                    enabledChannels = updated
                                    onEnabledChannelsChange(updated)
                                },
                                colors = appSwitchColors()
                            )
                        }

                        AnimatedVisibility(visible = expanded) {
                            Column(modifier = Modifier.padding(start = 24.dp)) {
                                groupChannels.forEach { channel ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = channel.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Switch(
                                            checked = channel.id in enabledChannels,
                                            onCheckedChange = { enabled ->
                                                view.performHaptic(HapticType.CLICK)
                                                val updated = enabledChannels.toMutableSet()
                                                if (enabled) updated.add(channel.id) else updated.remove(channel.id)
                                                enabledChannels = updated
                                                onEnabledChannelsChange(updated)
                                            },
                                            colors = appSwitchColors()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_done))
            }
        }
    )
}

private fun Float.roundToInt(): Int = (this + 0.5f).toInt()
