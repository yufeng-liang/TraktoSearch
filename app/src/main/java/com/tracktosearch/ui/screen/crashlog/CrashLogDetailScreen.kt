package com.tracktosearch.ui.screen.crashlog

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.local.CrashLogRecord
import com.tracktosearch.ui.component.FeedbackListCardCorner
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.SharedCorner
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.appSharedChrome
import com.tracktosearch.ui.component.appSkipToLookaheadSize
import com.tracktosearch.ui.component.crashLogCardSharedKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun CrashLogDetailScreen(
    recordId: String,
    onBack: () -> Unit,
    viewModel: CrashLogDetailViewModel = hiltViewModel(),
) {
    // 共享元素转场作用域：与反馈页崩溃日志卡片配对，整页作为容器一起变形。
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    // remember 固定按 recordId 复用一个 StateFlow，避免重组时反复新建 stateIn 共享协程
    val record by remember(recordId) { viewModel.record(recordId) }.collectAsStateWithLifecycle()
    // 本地加载态：Store 异步加载完成前 record 为 null，区分「加载中」与「记录不存在」；
    // remember(recordId) 使 recordId 变化时自动重置，避免切到另一条记录时误用旧加载态
    var loaded by remember(recordId) { mutableStateOf(false) }
    LaunchedEffect(record) { if (record != null) loaded = true }

    Scaffold(
        topBar = {
            // 顶栏不参与配对，改为容器基本落位后再淡入：这块顶栏是不透明的，
            // 若从第一帧就实心压在正在放大的卡片上方，观感上等于卡片被一条色带切了一刀。
            Box(modifier = Modifier.appSharedChrome(animatedVisibilityScope)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.crash_detail_title), fontWeight = FontWeight.ExtraBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.content_desc_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        },
        // 容器色置透明、底色改由下面那个共享节点自己画，理由见该处注释。
        // contentColor 显式写成 onBackground：Scaffold 默认取 contentColorFor(containerColor)，
        // 而 contentColorFor(Transparent) 是 Unspecified，会让整页文字颜色退回外层 LocalContentColor。
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        val current = record
        // 与反馈页崩溃日志卡片配对的是整页容器。加载中、记录不存在、正常三个分支之间没有公共父节点，
        // 这里补一个只负责承载共享边界的 Box；各分支自己的 padding 是 Scaffold 给的顶栏避让，
        // 属于内容而不属于容器，因此仍留在分支内部，不上移到 Box 上。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .appSharedBounds(
                    key = crashLogCardSharedKey(recordId),
                    animatedVisibilityScope = animatedVisibilityScope,
                    corner = SharedCorner.flattenFrom(FeedbackListCardCorner),
                )
                // 页面底色挪进共享节点内侧，并把 Scaffold 的容器色置透明。
                // 否则 Scaffold 会在共享节点之外先铺满一整屏不透明底色，转场第一帧整屏就已经是本页的背景，
                // 「卡片长成页面」退化成「页面已经在了，只是内容从一个小矩形里长出来」。
                // 挪进来之后底色跟着动画边界一起长大，且被上面那层圆角动画裁剪，落定后与原来逐像素相同。
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                current == null -> {
                    // 加载态与不存在区分：Store 异步加载完成前显示加载中，加载完成后仍为 null 才是记录不存在
                    if (!loaded) {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.crash_detail_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        // 整页参与容器变形时按落定尺寸布局：否则列表会跟着容器逐帧变宽，
                        // 一次转场里重复决定「哪些项可见、每项多宽」几十遍
                        .appSkipToLookaheadSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { InfoCard(current) }
                    item {
                        Text(
                            text = stringResource(R.string.crash_detail_log_content),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                text = current.logContent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.fillMaxWidth().padding(16.dp)
                            )
                        }
                    }
                    // 待上传/失败：提供立即上传入口（上传成功后记录自动刷新）
                    if (current.status == CrashLogRecord.Status.PENDING ||
                        current.status == CrashLogRecord.Status.FAILED
                    ) {
                        item {
                            // 上传中禁用：记录刷新为 UPLOADING 后按钮通常随即隐藏，enabled 兜底防重复触发
                            Button(
                                onClick = { viewModel.uploadNow() },
                                enabled = current.status != CrashLogRecord.Status.UPLOADING,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.crash_detail_upload_now)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(record: CrashLogRecord) {
    val statusLabel = when (record.status) {
        CrashLogRecord.Status.PENDING -> R.string.crash_record_status_pending
        CrashLogRecord.Status.UPLOADING -> R.string.crash_record_status_uploading
        CrashLogRecord.Status.SUCCESS -> R.string.crash_record_status_success
        CrashLogRecord.Status.FAILED -> R.string.crash_record_status_failed
    }
    // remember 复用时间格式化器，避免每次重组新建 SimpleDateFormat
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    fun fmt(ts: Long): String = if (ts <= 0) "—" else timeFormat.format(Date(ts))

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoRow(stringResource(R.string.crash_detail_status), stringResource(statusLabel))
            InfoRow(stringResource(R.string.crash_detail_upload_time), fmt(record.uploadTime))
            if (record.error.isNotBlank()) {
                InfoRow(stringResource(R.string.crash_detail_fail_reason), record.error)
            }
            InfoRow(stringResource(R.string.crash_detail_crash_time), fmt(record.crashTime))
            if (record.appVersion.isNotBlank()) {
                InfoRow(stringResource(R.string.crash_detail_app_version), record.appVersion)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}
