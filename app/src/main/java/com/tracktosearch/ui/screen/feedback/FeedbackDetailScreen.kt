package com.tracktosearch.ui.screen.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackDetailScreen(
    feedbackId: String,
    onBack: () -> Unit,
    onNewFeedback: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    // 截图全屏查看状态：null=未打开，Int=当前查看的截图索引
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    // 当前详情页所有截图的完整 URL 列表（用于全屏 overlay 切换）
    val fullscreenUrls = remember(detailState) {
        (detailState as? FeedbackViewModel.DetailState.Success)?.data?.feedback?.screenshots
            ?.let { parseScreenshots(it) }
            ?.map { com.tracktosearch.data.remote.feedback.screenshotUrl(it) }
            ?: emptyList()
    }

    LaunchedEffect(feedbackId) { viewModel.loadDetail(feedbackId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_title), fontWeight = FontWeight.ExtraBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (val state = detailState) {
            is FeedbackViewModel.DetailState.Loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is FeedbackViewModel.DetailState.Error -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.loadDetail(feedbackId) }) {
                        Text(stringResource(R.string.feedback_retry))
                    }
                }
            }
            is FeedbackViewModel.DetailState.Success -> {
                val feedback = state.data.feedback
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 类型 + 状态 + 时间
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(feedbackTypeLabel(feedback.type), fontSize = 12.sp, color = feedbackTypeColor(feedback.type), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(feedbackStatusLabel(feedback.status), fontSize = 12.sp, color = feedbackStatusColor(feedback.status))
                    }

                    // 正文
                    Text(
                        text = feedback.content,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // 截图（如有）— 用 Coil AsyncImage 加载真实图片
                    val screenshots = parseScreenshots(feedback.screenshots)
                    if (screenshots.isNotEmpty()) {
                        Text(stringResource(R.string.feedback_screenshots), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(screenshots) { key ->
                                val context = LocalContext.current
                                val url = com.tracktosearch.data.remote.feedback.screenshotUrl(key)
                                var loadFailed by remember { mutableStateOf(false) }
                                Box(
                                    Modifier
                                        .size(120.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .clickable { fullscreenIndex = screenshots.indexOf(key) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (loadFailed) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text("⚠️", fontSize = 20.sp)
                                            Text(
                                                stringResource(R.string.feedback_screenshot_expired),
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        AsyncImage(
                                            model = remember(url) {
                                                ImageRequest.Builder(context)
                                                    .data(url)
                                                    .crossfade(true)
                                                    .build()
                                            },
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(120.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            onError = { loadFailed = true }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 应用信息卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val doubanLabel = stringResource(R.string.detail_info_douban_rating)
                            val contactLabel = stringResource(R.string.feedback_contact)
                            Text(stringResource(R.string.feedback_app_info), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            InfoRow("App", feedback.app_version)
                            InfoRow("OS", feedback.os_version)
                            InfoRow("Device", feedback.device_model)
                            feedback.trakt_username?.let { InfoRow("Trakt", it) }
                            feedback.douban_username?.let { InfoRow(doubanLabel, it) }
                            feedback.contact?.let { InfoRow(contactLabel, it) }
                        }
                    }

                    // 开发者回复
                    if (state.data.replies.isNotEmpty()) {
                        Text(stringResource(R.string.feedback_dev_reply), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        state.data.replies.forEach { reply ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(reply.content, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(formatTime(reply.created_at), fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }

                    // 底部「写新反馈」按钮
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onNewFeedback, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.feedback_new))
                    }
                }
            }
        }
    }

    // 截图全屏查看 overlay
    fullscreenIndex?.let { index ->
        if (fullscreenUrls.isNotEmpty()) {
            ScreenshotFullscreenOverlay(
                images = fullscreenUrls,
                initialIndex = index,
                onDismiss = { fullscreenIndex = null }
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun parseScreenshots(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        // 简单 JSON 数组解析
        val trimmed = json.trim().removeSurrounding("[", "]").split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        trimmed
    } catch (e: Exception) {
        emptyList()
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp * 1000))
}

@Composable
private fun feedbackTypeLabel(type: String): String = stringResource(
    when (type) {
        "FEATURE" -> R.string.feedback_type_feature
        "BUG" -> R.string.feedback_type_bug
        "UX" -> R.string.feedback_type_ux
        else -> R.string.feedback_type_other
    }
)

@Composable
private fun feedbackTypeColor(type: String): Color = when (type) {
    "FEATURE" -> Color(0xFF34D399)
    "BUG" -> Color(0xFFFB7185)
    "UX" -> Color(0xFFFBBF24)
    else -> Color(0xFF9CA3AF)
}

@Composable
private fun feedbackStatusLabel(status: String): String = stringResource(
    when (status) {
        "PENDING" -> R.string.feedback_status_pending
        "REPLIED" -> R.string.feedback_status_replied
        else -> R.string.feedback_status_closed
    }
)

@Composable
private fun feedbackStatusColor(status: String): Color = when (status) {
    "PENDING" -> MaterialTheme.colorScheme.primary
    "REPLIED" -> Color(0xFF10B981)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
