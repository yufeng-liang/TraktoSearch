package com.tracktosearch.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.data.util.ApkDownloader
import com.tracktosearch.data.util.ApkInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private const val RELEASE_PAGE_URL = "https://gitee.com/yufeng-liang/TrackToSearch-release/releases"

private sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * DownloadState 的 Saver,用于 rememberSaveable 保留旋屏后的下载状态。
 *
 * - Idle: 保存为 "idle"
 * - Downloading: 保存为 "downloading:{progress}"（旋屏后 job 丢失,会触发重置为 Idle）
 * - Completed: 保存为 "completed:{file.absolutePath}"（保留文件路径,可重新唤起安装）
 * - Error: 保存为 "error:{message}"（保留错误信息,用户可重试）
 */
private val DownloadStateSaver = Saver<DownloadState, String>(
    save = { state ->
        when (state) {
            is DownloadState.Idle -> "idle"
            is DownloadState.Downloading -> "downloading:${state.progress}"
            is DownloadState.Completed -> "completed:${state.file.absolutePath}"
            is DownloadState.Error -> "error:${state.message}"
        }
    },
    restore = { saved ->
        when {
            saved == "idle" || saved.isBlank() -> DownloadState.Idle
            saved.startsWith("downloading:") -> {
                val progress = saved.removePrefix("downloading:").toFloatOrNull() ?: 0f
                DownloadState.Downloading(progress)
            }
            saved.startsWith("completed:") -> {
                val path = saved.removePrefix("completed:")
                DownloadState.Completed(File(path))
            }
            saved.startsWith("error:") -> {
                val message = saved.removePrefix("error:")
                DownloadState.Error(message)
            }
            else -> DownloadState.Idle
        }
    }
)

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
                    // 列表项 — 用 Row + 固定宽度占位符实现换行缩进对齐
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                        val itemText = trimmed.removePrefix("• ").let { if (it == trimmed) it.removePrefix("- ").let { if (it == trimmed) it.removePrefix("* ") else it } else it }
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "•  ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = parseInlineMarkdown(itemText),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
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

/**
 * 将更新日志按版本分割为 sections，每个 section 包含标题、日期和内容行。
 * 标题形如 "v2.23.0 更新内容（2026-07-06）",日期会被提取到独立字段 [date]。
 */
private data class ChangelogSection(val title: String, val date: String, val lines: List<String>)

private val trailingDateRegex = Regex("（[^）]*\\d{4}-\\d{2}-\\d{2}[^）]*）$")

private fun parseChangelogSections(text: String): List<ChangelogSection> {
    val sections = mutableListOf<ChangelogSection>()
    var currentTitle = ""
    var currentDate = ""
    val currentLines = mutableListOf<String>()

    text.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            if (currentTitle.isNotBlank() || currentLines.isNotEmpty()) {
                sections.add(ChangelogSection(currentTitle, currentDate, currentLines.toList()))
            }
            currentTitle = ""
            currentDate = ""
            currentLines.clear()
            return@forEach
        }
        // 只认 ## 作为 section 标题，# 开头的行跳过（与自动生成的 header 重复）
        if (trimmed.startsWith("## ")) {
            if (currentTitle.isNotBlank() || currentLines.isNotEmpty()) {
                sections.add(ChangelogSection(currentTitle, currentDate, currentLines.toList()))
                currentLines.clear()
            }
            val rawTitle = trimmed.removePrefix("##").trimStart()
            // 提取尾部日期括号,如 "v2.23.0 更新内容（2026-07-06）"
            val match = trailingDateRegex.find(rawTitle)
            if (match != null) {
                // 提取括号内的 yyyy-MM-dd
                val dateInParen = match.value
                val dateMatch = Regex("\\d{4}-\\d{2}-\\d{2}").find(dateInParen)
                currentDate = dateMatch?.value ?: ""
                currentTitle = rawTitle.replace(trailingDateRegex, "").trimEnd()
            } else {
                currentDate = ""
                currentTitle = rawTitle
            }
        } else if (line.isNotBlank()) {
            currentLines.add(line)
        }
    }
    if (currentTitle.isNotBlank() || currentLines.isNotEmpty()) {
        sections.add(ChangelogSection(currentTitle, currentDate, currentLines.toList()))
    }
    return sections
}

/**
 * 带吸顶标题的更新日志渲染：
 * - 每版 section 标题作为 stickyHeader item 渲染，随内容滚动到顶部时变 sticky（不会遮住首行）
 * - 未到顶时标题仍在下面正常显示，便于用户区分各版本
 * - 滑到下一版时，新标题顶替旧标题
 *
 * 使用 LazyColumn 的 stickyHeader API 而非 overlay 浮层：
 * overlay 模式会遮住首行文字；stickyHeader 是标准吸顶，标题占位而非覆盖。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickyHeaderChangelogContent(text: String) {
    val sections = remember(text) { parseChangelogSections(text) }
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth()
    ) {
        sections.forEachIndexed { sectionIdx, section ->
            // 每版 section 标题作为 stickyHeader：随内容滚动到顶部时变 sticky，下一个 header 顶走时正常滚出
            stickyHeader(key = "header_${sectionIdx}") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = parseInlineMarkdown(section.title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            if (section.date.isNotBlank()) {
                                Text(
                                    text = section.date,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            // 内容行
            items(items = section.lines, key = { line -> "content_${sectionIdx}_${line.hashCode()}" }) { line ->
                RenderLine(line)
            }
        }
    }
}

@Composable
private fun RenderLine(line: String) {
    val trimmed = line.trimStart()
    when {
        trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
            val itemText = trimmed.removePrefix("• ").let {
                if (it == trimmed) it.removePrefix("- ").let { s ->
                    if (s == trimmed) s.removePrefix("* ") else s
                } else it
            }
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "•  ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = parseInlineMarkdown(itemText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
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
        else -> {
            Text(
                text = parseInlineMarkdown(trimmed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val downloadFailedMsg = stringResource(R.string.update_download_failed)
    val signatureInvalidMsg = stringResource(R.string.update_signature_invalid)
    val scope = rememberCoroutineScope()
    // 下载状态用 rememberSaveable 保留旋屏后的状态（Completed/Error 不丢失）；
    // Downloading 状态旋屏后 job 丢失，下方 LaunchedEffect 会重置为 Idle
    var downloadState by rememberSaveable(stateSaver = DownloadStateSaver) { mutableStateOf(DownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // 旋屏后 downloadJob 为 null，若 downloadState 仍是 Downloading，重置为 Idle（下载已实际停止）
    LaunchedEffect(downloadState, downloadJob) {
        if (downloadState is DownloadState.Downloading && downloadJob == null) {
            downloadState = DownloadState.Idle
        }
    }

    // 组件销毁时取消下载
    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    val canDismiss = downloadState !is DownloadState.Downloading

    // 下载完成自动唤起安装
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            try {
                ApkInstaller.installApk(context, (downloadState as DownloadState.Completed).file)
            } catch (e: SecurityException) {
                downloadState = DownloadState.Error(signatureInvalidMsg)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = null,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 可滚动的更新日志区域
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                ) {
                    StickyHeaderChangelogContent(
                        text = updateInfo.changelog.ifBlank { stringResource(R.string.update_version_suffix) + " v${updateInfo.latestVersion}" }
                    )
                }

                // 下载进度区域（固定在滚动区域下方）
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
                                text = stringResource(R.string.common_saved_path, state.file.parentFile?.name ?: "", state.file.name),
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
                        if (updateInfo.downloadUrl.isEmpty()) {
                            // 下载链接为空：提示并引导前往发布页
                            Text(
                                text = stringResource(R.string.update_download_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE_URL))
                                context.startActivity(intent)
                                onDismiss()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_release_page))
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_later))
                            }
                        } else {
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
                                            },
                                            expectedSha256 = updateInfo.sha256
                                        )
                                        downloadState = DownloadState.Completed(file)
                                    } catch (e: Exception) {
                                        downloadState = DownloadState.Error(e.message ?: downloadFailedMsg)
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_download_builtin))
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                                context.startActivity(intent)
                                onDismiss()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_download_browser))
                            }
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.update_later))
                            }
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
                            try {
                                ApkInstaller.installApk(context, state.file)
                                onDismiss()
                            } catch (e: SecurityException) {
                                downloadState = DownloadState.Error(signatureInvalidMsg)
                            }
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
                            Text(stringResource(R.string.update_download_browser))
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_close))
                        }
                    }
                }
            }
        }
    )
}
