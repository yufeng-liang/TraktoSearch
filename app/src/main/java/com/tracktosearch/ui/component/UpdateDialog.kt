package com.tracktosearch.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.data.util.ApkDownloader
import com.tracktosearch.data.util.ApkInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * 轻量级更新日志渲染：
 * - 标题字号缩小为 titleMedium，保持加粗
 * - 列表符号（•）与文字垂直居中对齐
 * - 支持简单的 **粗体** 内联标记
 * - 支持 --- 水平分隔线
 */
@Composable
fun ChangelogContent(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        text.lineSequence()
            .map { it.trimEnd() }
            .forEach { line ->
                if (line.isBlank()) return@forEach
                val trimmed = line.trimStart()
                when {
                    // 水平分隔线
                    trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                        Spacer(modifier = Modifier.height(1.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                    // Markdown 标题 # / ## / ...
                    trimmed.startsWith("#") -> {
                        val level = trimmed.takeWhile { it == '#' }.length
                        val clean = trimmed.trimStart('#').trimStart()
                        val style = when (level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        Text(
                            text = parseInlineMarkdown(clean),
                            style = style,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // 列表项
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                        val itemText = trimmed.substring(2)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = parseInlineMarkdown(itemText),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // 普通段落
                    else -> {
                        Text(
                            text = parseInlineMarkdown(trimmed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
    }
}

/** 解析 **粗体** 内联标记 */
fun parseInlineMarkdown(input: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < input.length) {
            val boldStart = input.indexOf("**", i)
            if (boldStart == -1) {
                append(input.substring(i))
                break
            }
            append(input.substring(i, boldStart))
            val boldEnd = input.indexOf("**", boldStart + 2)
            if (boldEnd == -1) {
                append(input.substring(boldStart))
                break
            }
            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                append(input.substring(boldStart + 2, boldEnd))
            }
            i = boldEnd + 2
        }
    }
}

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // 组件销毁时取消下载
    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    val canDismiss = downloadState !is DownloadState.Downloading

    // 下载完成自动唤起安装
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            ApkInstaller.installApk(context, (downloadState as DownloadState.Completed).file)
        }
    }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text("发现新版本 v${updateInfo.latestVersion}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ChangelogContent(
                    text = updateInfo.changelog.ifBlank { "v${updateInfo.latestVersion} 版本更新" }
                )

                val state = downloadState
                if (state !is DownloadState.Idle) {
                    Spacer(Modifier.height(12.dp))
                    when (state) {
                        is DownloadState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is DownloadState.Completed -> {
                            Text(
                                text = stringResource(R.string.common_save_success),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is DownloadState.Error -> {
                            Text(
                                text = stringResource(R.string.update_download_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                }
            }
        },
        confirmButton = {
            when (val state = downloadState) {
                is DownloadState.Idle -> {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Button(onClick = {
                            downloadState = DownloadState.Downloading(0f)
                            downloadJob = scope.launch {
                                try {
                                    val file = ApkDownloader.downloadApk(
                                        context = context,
                                        url = updateInfo.downloadUrl,
                                        fileName = "TraktToSearch-v${updateInfo.latestVersion}.apk",
                                        onProgress = { progress ->
                                            downloadState = DownloadState.Downloading(progress)
                                        }
                                    )
                                    downloadState = DownloadState.Completed(file)
                                } catch (e: Exception) {
                                    downloadState = DownloadState.Error(e.message ?: "下载失败")
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("内置下载")
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                            context.startActivity(intent)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("浏览器下载")
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                }
                is DownloadState.Downloading -> {
                    OutlinedButton(onClick = {
                        downloadJob?.cancel()
                        downloadState = DownloadState.Idle
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_close))
                    }
                }
                is DownloadState.Completed -> {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Button(onClick = {
                            ApkInstaller.installApk(context, state.file)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_download))
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_later))
                        }
                    }
                }
                is DownloadState.Error -> {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Button(onClick = {
                            downloadState = DownloadState.Idle
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.error_retry))
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                            context.startActivity(intent)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("浏览器下载")
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    )
}