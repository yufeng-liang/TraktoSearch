package com.tracktosearch.ui.screen.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.remote.feedback.MessageItem
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onBack: () -> Unit,
    onMessageClick: (feedbackId: String, replyId: String) -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val filter by viewModel.messagesFilter.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.loadMessages(refresh = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feedback_messages_title), fontWeight = FontWeight.ExtraBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null) } },
                actions = {
                    TextButton(onClick = { viewModel.markAllRead() }) {
                        Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.feedback_messages_all_read), fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)) {
                MessageFilterChip(stringResource(R.string.feedback_filter_all), filter == FeedbackViewModel.MessageFilter.ALL) { viewModel.setMessagesFilter(FeedbackViewModel.MessageFilter.ALL) }
                MessageFilterChip(stringResource(R.string.feedback_filter_unread), filter == FeedbackViewModel.MessageFilter.UNREAD) { viewModel.setMessagesFilter(FeedbackViewModel.MessageFilter.UNREAD) }
                MessageFilterChip(stringResource(R.string.feedback_filter_developer), filter == FeedbackViewModel.MessageFilter.DEVELOPER) { viewModel.setMessagesFilter(FeedbackViewModel.MessageFilter.DEVELOPER) }
            }
            when (val state = messagesState) {
                is FeedbackViewModel.MessagesState.Loading -> { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                is FeedbackViewModel.MessagesState.Error -> { Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(state.message, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(8.dp)); TextButton(onClick = { viewModel.loadMessages(refresh = true) }) { Text(stringResource(R.string.feedback_retry)) } } }
                is FeedbackViewModel.MessagesState.Success -> {
                    val visibleItems = state.items.filter { it.author_role == "developer" }
                    if (visibleItems.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.feedback_messages_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visibleItems, key = { it.id }) { item -> MessageItemRow(item = item, onClick = { onMessageClick(item.feedback_id, item.id) }) }
                            if (state.hasMore) { item(key = "load_more") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { TextButton(onClick = { viewModel.loadMessages(refresh = false) }) { Text(stringResource(R.string.feedback_load_more)) } } } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.clickable { onClick() }, shape = RoundedCornerShape(8.dp), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface, tonalElevation = if (selected) 0.dp else 1.dp, shadowElevation = if (selected) 0.dp else 1.dp) {
        Text(text = label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun MessageItemRow(item: MessageItem, onClick: () -> Unit) {
    val isDeveloper = item.author_role == "developer"
    val avatarColor = if (isDeveloper) Color(0xFF34D399) else MaterialTheme.colorScheme.primary
    val avatarLabel = if (isDeveloper) "D" else "我"
    val typeColor = when (item.type) { "FEATURE" -> Color(0xFF34D399); "BUG" -> Color(0xFFFB7185); "UX" -> Color(0xFFFBBF24); else -> Color(0xFF9CA3AF) }
    val hasScreenshot = item.screenshots.isNotEmpty()

    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (item.is_unread) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 10.dp).alpha(if (item.is_unread) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box {
            Box(Modifier.size(36.dp).clip(CircleShape).background(avatarColor), contentAlignment = Alignment.Center) { Text(avatarLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            if (item.is_unread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(Color(0xFFFB7185))
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = item.display_id, fontSize = 12.sp, color = typeColor, fontWeight = FontWeight.SemiBold)
                Text("·", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = if (isDeveloper) stringResource(R.string.feedback_role_developer) else stringResource(R.string.feedback_role_me), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hasScreenshot) { Text("📷", fontSize = 11.sp) }
            }
            Text(text = item.content, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(text = formatTime(item.created_at), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp * 1000))
}
