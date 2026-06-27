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
import androidx.compose.ui.unit.dp
import com.tracktosearch.data.remote.panhub.ChannelGroup
import com.tracktosearch.data.remote.panhub.PanHubChannel
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.remote.panhub.PanHubPlugin

@Composable
fun PanHubConfigDialog(
    config: PanHubConfig,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PanHub 搜索配置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 并发数
                Column {
                    Text("并发数", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = (concurrencyText.toIntOrNull() ?: 4).toFloat(),
                        onValueChange = { v ->
                            val intVal = v.roundToInt()
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
                    label = { Text("超时 (ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 插件标题 + 全选按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("插件", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val allPluginsSelected = enabledPlugins.size == PanHubPlugin.entries.size
                    TextButton(onClick = {
                        val newSet = if (allPluginsSelected) emptySet() else PanHubPlugin.entries.map { it.id }.toSet()
                        enabledPlugins = newSet
                        onEnabledPluginsChange(newSet)
                    }) {
                        Text(if (allPluginsSelected) "全不选" else "全选")
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
                                        val updated = enabledPlugins.toMutableSet()
                                        if (enabled) updated.add(plugin.id) else updated.remove(plugin.id)
                                        enabledPlugins = updated
                                        onEnabledPluginsChange(updated)
                                    }
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
                    Text("频道", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    val allChannelsSelected = enabledChannels.size == PanHubChannel.entries.size
                    TextButton(onClick = {
                        val newSet = if (allChannelsSelected) emptySet() else PanHubChannel.entries.map { it.id }.toSet()
                        enabledChannels = newSet
                        onEnabledChannelsChange(newSet)
                    }) {
                        Text(if (allChannelsSelected) "全不选" else "全选")
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
                                    val updated = enabledChannels.toMutableSet()
                                    if (allOn) {
                                        updated.addAll(groupChannels.map { it.id })
                                    } else {
                                        updated.removeAll(groupChannels.map { it.id })
                                    }
                                    enabledChannels = updated
                                    onEnabledChannelsChange(updated)
                                }
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
                                                val updated = enabledChannels.toMutableSet()
                                                if (enabled) updated.add(channel.id) else updated.remove(channel.id)
                                                enabledChannels = updated
                                                onEnabledChannelsChange(updated)
                                            }
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
                Text("完成")
            }
        }
    )
}

private fun Float.roundToInt(): Int = (this + 0.5f).toInt()
