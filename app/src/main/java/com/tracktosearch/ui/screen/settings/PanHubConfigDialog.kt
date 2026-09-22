package com.tracktosearch.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.data.remote.panhub.ChannelGroup
import com.tracktosearch.data.remote.panhub.PanHubChannel
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.remote.panhub.PanHubPlugin
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppAlertDialog
import com.tracktosearch.ui.component.DialogAction
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.appSwitchColors

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

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.panhub_config_title),
        content = {
            // text 槽是独立 subcomposition（自带宿主 View），触感实例得在槽内取；
            // 限高与滚动由组件内容槽负责（原手写 verticalScroll 删除）
            val haptics = rememberAppHaptics()
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                        onCheckedChange = { haptics.toggle(it); onEnabledChange(it) },
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
                                haptics.frequentTick()
                            }
                            // 拖动过程只更新本地显示值；松手（onValueChangeFinished）
                            // 才回调提交，避免每个拖拽 tick 都写 DataStore 并触发收集方重组
                            concurrencyText = intVal.toString()
                        },
                        onValueChangeFinished = {
                            // 松手落定那一记：拖动中的 frequentTick 只到「又过一格」，这里才是「停在这个值」
                            haptics.gestureEnd()
                            concurrencyText.toIntOrNull()?.let(onConcurrencyChange)
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
                        // 全选 / 全不选是整组勾选位一起加减（enabledPlugins 是 Set），
                        // 「加上」与「去掉」的方向感有意义，按新状态发 toggle 而不是按钮那档 tap
                        haptics.toggle(!allPluginsSelected)
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
                                        haptics.toggle(enabled)
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
                        // 同插件那侧：整组勾选位一起加减，按新状态发 toggle
                        haptics.toggle(!allChannelsSelected)
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
                                // 展开 / 收起：语义按点完之后的新状态定
                                .hapticClickable(
                                    semantic = if (expanded) HapticSemantic.TOGGLE_OFF
                                               else HapticSemantic.TOGGLE_ON
                                ) { expanded = !expanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
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
                                    haptics.toggle(allOn)
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
                                                haptics.toggle(enabled)
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
        confirm = DialogAction(
            label = stringResource(R.string.common_done),
            onClick = { onDismiss() }
        )
    )
}

private fun Float.roundToInt(): Int = (this + 0.5f).toInt()
