package com.tracktosearch.ui.component

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tracktosearch.BuildConfig
import com.tracktosearch.R
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.data.util.ApkDownloader
import com.tracktosearch.data.util.ApkInstaller
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.floatingDialogColor
import com.tracktosearch.ui.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

// 下载兜底入口：清单与 APK 都在官网上，主链接失效时把用户送到官网下载区。
// 锚点必须是 #section-download —— 官网下载区的 id 就是它（站内导航同款），
// 写成 #download 会是个能打开但滚不动的死锚点。
// 不能声明成 const —— BuildConfig 字段不是 Kotlin 编译期常量。
private val RELEASE_PAGE_URL = BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/#section-download"

/** 下载速度采样窗口：只保留最近 3 秒样本计算平均速度 */
private const val SPEED_WINDOW_MS = 3000L

private sealed class DownloadState {
    object Idle : DownloadState()

    /** progress: 0..1；totalBytes 未知（<=0）时 UI 用 indeterminate 进度条，不显示百分比 */
    data class Downloading(val progress: Float, val bytesRead: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * DownloadState 的 Saver,用于 rememberSaveable 保留旋屏后的下载状态。
 *
 * - Idle: 保存为 "idle"
 * - Downloading: 保存为 "downloading:{progress}:{bytesRead}:{totalBytes}"
 *   （旋屏后 job 丢失,会触发重置为 Idle；旧格式 "downloading:{progress}" 兼容恢复,bytes/total 填 0）
 * - Completed: 保存为 "completed:{file.absolutePath}"（保留文件路径,可重新唤起安装）
 * - Error: 保存为 "error:{message}"（保留错误信息,用户可重试）
 */
private val DownloadStateSaver = Saver<DownloadState, String>(
    save = { state ->
        when (state) {
            is DownloadState.Idle -> "idle"
            is DownloadState.Downloading -> "downloading:${state.progress}:${state.bytesRead}:${state.totalBytes}"
            is DownloadState.Completed -> "completed:${state.file.absolutePath}"
            is DownloadState.Error -> "error:${state.message}"
        }
    },
    restore = { saved ->
        when {
            saved == "idle" || saved.isBlank() -> DownloadState.Idle
            saved.startsWith("downloading:") -> {
                // 兼容旧格式 "downloading:{progress}"：切分不足 3 段时 bytesRead/totalBytes 填 0
                val parts = saved.removePrefix("downloading:").split(':')
                val progress = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
                val bytesRead = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                val totalBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                DownloadState.Downloading(progress, bytesRead, totalBytes)
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

/** 字节数格式化：B/KB/MB/GB；≥1MB 保留 1 位小数；Locale.US 避免本地化千分位逗号 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.1f GB", mb / 1024.0)
}

/** 下载速度格式化："2.4 MB/s" */
private fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

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
 * - 标题区填充色由调用方传 [headerColor]，须与宿主容器同色：静态看不出色块，吸顶挡内容时也无异色
 *
 * 使用 LazyColumn 的 stickyHeader API 而非 overlay 浮层：
 * overlay 模式会遮住首行文字；stickyHeader 是标准吸顶，标题占位而非覆盖。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickyHeaderChangelogContent(
    text: String,
    headerColor: Color
) {
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
                    color = headerColor
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

/**
 * 下载进度区：进度条 + 「已下载/总量 + 速度·百分比」信息行。
 * totalBytes 未知（<=0）时用 indeterminate 进度条，只显示已下载字节数与速度，不显示百分比。
 */
@Composable
private fun DownloadProgressContent(state: DownloadState.Downloading) {
    // 速度采样：记录 (elapsedRealtime, bytesRead) 样本，只保留最近 3 秒，取窗口首尾差算平均速度
    val samples = remember { mutableStateListOf<Pair<Long, Long>>() }
    var lastSampledBytes by remember { mutableStateOf(-1L) }
    SideEffect {
        // 字节数回退（如取消后重下）说明是新一轮下载，旧样本作废
        val lastBytes = samples.lastOrNull()?.second ?: -1L
        if (state.bytesRead < lastBytes) samples.clear()
        // 仅在收到新进度时采样，避免重组本身触发追加
        if (state.bytesRead != lastSampledBytes) {
            lastSampledBytes = state.bytesRead
            val now = SystemClock.elapsedRealtime()
            samples.add(now to state.bytesRead)
            while (samples.isNotEmpty() && now - samples.first().first > SPEED_WINDOW_MS) {
                samples.removeAt(0)
            }
        }
    }
    // 窗口平均速度；样本不足（<2 个）或窗口内无进展返回 0，UI 显示 "—"
    val bytesPerSecond: Long = if (samples.size >= 2) {
        val dtMs = samples.last().first - samples.first().first
        val deltaBytes = samples.last().second - samples.first().second
        if (dtMs > 0 && deltaBytes > 0) deltaBytes * 1000 / dtMs else 0L
    } else {
        0L
    }

    val determinate = state.totalBytes > 0
    // 进度平滑：进度条目标值变化时 300ms 过渡
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "downloadProgress"
    )

    if (determinate) {
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    } else {
        // 总大小未知：indeterminate 进度条
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // 左侧：已下载/总量；总量未知时只显示已下载
        Text(
            text = if (determinate) {
                "${formatBytes(state.bytesRead)} / ${formatBytes(state.totalBytes)}"
            } else {
                formatBytes(state.bytesRead)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 右侧：速度 + 百分比（仅总量已知时显示百分比）
        Text(
            text = buildString {
                append(if (bytesPerSecond > 0) formatSpeed(bytesPerSecond) else "—")
                if (determinate) append(" · ${(state.progress * 100).toInt().coerceIn(0, 100)}%")
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 主按钮：48dp 高、14dp 圆角 */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    Button(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 次按钮：44dp 高、14dp 圆角，弱于主按钮形成层次 */
@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    val haptics = rememberAppHaptics()
    OutlinedButton(
        onClick = {
            haptics.lightTap()
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
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
    val openFailedMsg = stringResource(R.string.common_open_failed)
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()

    // 统一封装浏览器跳转：设备无可用浏览器时显示 Toast,避免崩溃
    // 失败分支不关弹窗，所以这里必须用 Toast——Snackbar 会被更新弹窗的窗口挡住
    fun openUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            onDismiss()
        } catch (_: ActivityNotFoundException) {
            haptics.reject()
            Toast.makeText(context, openFailedMsg, Toast.LENGTH_SHORT).show()
        }
    }
    // 下载状态用 rememberSaveable 保留旋屏后的状态（Completed/Error 不丢失）；
    // Downloading 状态旋屏后 job 丢失，下方 LaunchedEffect 会重置为 Idle
    var downloadState by rememberSaveable(stateSaver = DownloadStateSaver) { mutableStateOf(DownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // 失败态统一从这里落：下载失败与三处安装失败散在四个 catch 里，各自记得配一记 reject()
    // 迟早会漏一个。触感与错误文案同一个出口，就漏不掉了
    fun failDownload(message: String) {
        haptics.reject()
        downloadState = DownloadState.Error(message)
    }

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
                failDownload(signatureInvalidMsg)
            } catch (_: Exception) {
                // 文件被清理/FileProvider 异常/无安装器等，避免崩溃
                failDownload(downloadFailedMsg)
            }
        }
    }

    Dialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(28.dp),
            color = floatingDialogColor()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // 头部：图标容器 + 徽标 + 版本迁移行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdateAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.update_dialog_badge),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        // 版本迁移行：旧版本号与箭头弱化，新版本号 primary 加粗
                        Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    append("v${BuildConfig.VERSION_NAME} → ")
                                }
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append("v${updateInfo.latestVersion}")
                                }
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 更新日志区：surface 背景营造层次，圆角裁剪；changelog 为空时兜底显示版本号
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // 横向留白：日志组件标题自带 8dp 内边距，正文行无边距，这里统一加内边距防贴圆角边
                    Column(
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    ) {
                        StickyHeaderChangelogContent(
                            text = updateInfo.changelog.ifBlank {
                                stringResource(R.string.update_version_suffix) + " v${updateInfo.latestVersion}"
                            },
                            // 标题吸顶后的填充色跟日志区圆角盒同色
                            headerColor = MaterialTheme.colorScheme.surface
                        )
                    }
                }

                // 下载进度区：Downloading 时展开，切走时收起（进出场动效）
                AnimatedVisibility(
                    visible = downloadState is DownloadState.Downloading,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    // 退出动画期间 state 已切走（Completed/Error），保留最近一次 Downloading 值渲染，避免内容瞬间消失
                    var heldDownloading by remember { mutableStateOf<DownloadState.Downloading?>(null) }
                    SideEffect {
                        (downloadState as? DownloadState.Downloading)?.let { heldDownloading = it }
                    }
                    heldDownloading?.let { ds ->
                        Spacer(Modifier.height(16.dp))
                        DownloadProgressContent(ds)
                    }
                }

                // 下载完成态：勾选图标 + 提示文案（自动唤起安装由上方 LaunchedEffect 处理）
                val completedState = downloadState as? DownloadState.Completed
                if (completedState != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.update_download_complete),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // 下载失败态：显示具体错误信息（toUserMessage 结果），供用户决定重试或改走浏览器
                val errorState = downloadState as? DownloadState.Error
                if (errorState != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = errorState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 按钮区：随状态切换
                when (val state = downloadState) {
                    is DownloadState.Idle -> {
                        if (updateInfo.downloadUrl.isEmpty()) {
                            // 下载链接为空：提示并引导前往发布页
                            Text(
                                text = stringResource(R.string.update_download_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton(text = stringResource(R.string.update_release_page)) {
                                openUrl(RELEASE_PAGE_URL)
                            }
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton(text = stringResource(R.string.update_later), onClick = onDismiss)
                        } else {
                            PrimaryButton(text = stringResource(R.string.update_download_builtin)) {
                                downloadState = DownloadState.Downloading(0f, 0L, 0L)
                                downloadJob = scope.launch {
                                    try {
                                        val file = ApkDownloader.downloadApk(
                                            context = context,
                                            url = updateInfo.downloadUrl,
                                            fileName = "TraktoSearch-v${updateInfo.latestVersion}.apk",
                                            onProgress = { bytesRead, totalBytes ->
                                                // 总量未知（<=0）时 progress 置 0，UI 走 indeterminate
                                                val progress = if (totalBytes > 0) {
                                                    bytesRead.toFloat() / totalBytes
                                                } else {
                                                    0f
                                                }
                                                downloadState = DownloadState.Downloading(progress, bytesRead, totalBytes)
                                            },
                                            expectedSha256 = updateInfo.sha256
                                        )
                                        downloadState = DownloadState.Completed(file)
                                        // 校验通过、文件落盘了才算成功；紧接着自动唤起安装器，
                                        // 这一记是「包拿到了」，安装结果由上面那个 effect 各自表态
                                        haptics.confirm()
                                    } catch (e: CancellationException) {
                                        // 「取消下载」按钮 cancel 掉这个 job，downloadApk 从挂起点抛
                                        // CancellationException 也会落进下面那个 catch。取消不是失败：
                                        // 既不该弹「下载失败」也不该震 reject，状态由取消处自己置回 Idle
                                        throw e
                                    } catch (e: Exception) {
                                        failDownload(e.toUserMessage(context, R.string.update_download_failed))
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton(text = stringResource(R.string.update_download_browser)) {
                                openUrl(updateInfo.downloadUrl)
                            }
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton(text = stringResource(R.string.update_later), onClick = onDismiss)
                        }
                    }
                    is DownloadState.Downloading -> {
                        // 取消下载：终止协程并回到 Idle
                        SecondaryButton(text = stringResource(R.string.update_cancel_download)) {
                            downloadJob?.cancel()
                            downloadState = DownloadState.Idle
                        }
                    }
                    is DownloadState.Completed -> {
                        PrimaryButton(text = stringResource(R.string.update_install)) {
                            try {
                                ApkInstaller.installApk(context, state.file)
                                onDismiss()
                            } catch (e: SecurityException) {
                                failDownload(signatureInvalidMsg)
                            } catch (_: Exception) {
                                // 文件被清理/FileProvider 异常/无安装器等,避免崩溃
                                failDownload(downloadFailedMsg)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(text = stringResource(R.string.update_later), onClick = onDismiss)
                    }
                    is DownloadState.Error -> {
                        PrimaryButton(text = stringResource(R.string.error_retry)) {
                            downloadState = DownloadState.Idle
                        }
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(text = stringResource(R.string.update_download_browser)) {
                            openUrl(updateInfo.downloadUrl)
                        }
                        Spacer(Modifier.height(8.dp))
                        SecondaryButton(text = stringResource(R.string.update_close), onClick = onDismiss)
                    }
                }
            }
        }
    }
}
