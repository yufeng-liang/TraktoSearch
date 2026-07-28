package com.tracktosearch.ui.screen.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    onNewFeedback: () -> Unit,
    onFeedbackClick: (String) -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val listState by viewModel.listState.collectAsStateWithLifecycle()

    // 首次进入加载
    LaunchedEffect(Unit) { viewModel.loadList(refresh = true) }

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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 顶部「写新反馈」按钮
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onNewFeedback() },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        Text(
                            text = stringResource(R.string.feedback_new),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            }

            // 「我的反馈」标题
            item {
                Text(
                    text = stringResource(R.string.feedback_my_feedbacks),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }

            when (val state = listState) {
                is FeedbackViewModel.ListState.Loading -> {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is FeedbackViewModel.ListState.Error -> {
                    item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.loadList(refresh = true) }) {
                                Text(stringResource(R.string.feedback_retry))
                            }
                        }
                    }
                }
                is FeedbackViewModel.ListState.Success -> {
                    if (state.items.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.feedback_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        items(state.items, key = { it.id }) { item ->
                            FeedbackCard(item = item, onClick = { onFeedbackClick(item.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedbackCard(item: com.tracktosearch.data.remote.feedback.FeedbackListItem, onClick: () -> Unit) {
    val typeColor = when (item.type) {
        "FEATURE" -> Color(0xFF34D399)
        "BUG" -> Color(0xFFFB7185)
        "UX" -> Color(0xFFFBBF24)
        else -> Color(0xFF9CA3AF)
    }
    val typeIcon = when (item.type) {
        "FEATURE" -> Icons.Rounded.Lightbulb
        "BUG" -> Icons.Rounded.BugReport
        "UX" -> Icons.Rounded.SentimentDissatisfied
        else -> Icons.Rounded.MoreHoriz
    }
    val typeLabel = when (item.type) {
        "FEATURE" -> R.string.feedback_type_feature
        "BUG" -> R.string.feedback_type_bug
        "UX" -> R.string.feedback_type_ux
        else -> R.string.feedback_type_other
    }
    val statusLabel = when (item.status) {
        "PENDING" -> R.string.feedback_status_pending
        "REPLIED" -> R.string.feedback_status_replied
        else -> R.string.feedback_status_closed
    }
    val statusColor = when (item.status) {
        "PENDING" -> MaterialTheme.colorScheme.primary
        "REPLIED" -> Color(0xFF10B981)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(typeIcon, contentDescription = null, tint = typeColor, modifier = Modifier.size(18.dp))
                Text(stringResource(typeLabel), fontSize = 12.sp, color = typeColor, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(stringResource(statusLabel), fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            }
            Text(
                text = item.content,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatRelativeTime(item.created_at),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 截图角标
                if (!item.screenshots.isNullOrEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text("📷", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp * 1000
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 1440 -> "${minutes / 60}小时前"
        minutes < 43200 -> "${minutes / 1440}天前"
        else -> "${minutes / 43200}个月前"
    }
}
