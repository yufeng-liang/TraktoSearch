package com.tracktosearch.ui.screen.feedback

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Send
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
import com.tracktosearch.data.remote.feedback.FeedbackReply
import com.tracktosearch.data.remote.feedback.screenshotUrl
import kotlinx.coroutines.launch

private const val MAX_REPLY_SCREENSHOTS = 5

internal fun conversationReplyListItemIndex(replyIndex: Int): Int = replyIndex + 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackDetailScreen(
    feedbackId: String,
    replyId: String? = null,
    onBack: () -> Unit,
    onNewFeedback: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val detailState by viewModel.detailState.collectAsStateWithLifecycle()
    val replyState by viewModel.replyState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var fullscreenUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }

    var replyText by remember { mutableStateOf("") }
    var replyScreenshots by remember { mutableStateOf<List<Pair<ByteArray, String>>>(emptyList()) }
    var replyFullscreenIndex by remember { mutableStateOf<Int?>(null) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val newShots = uris.take(MAX_REPLY_SCREENSHOTS - replyScreenshots.size).mapNotNull { uri ->
            try {
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) bytes to mimeType else null
            } catch (_: Exception) { null }
        }
        replyScreenshots = (replyScreenshots + newShots).take(MAX_REPLY_SCREENSHOTS)
    }

    LaunchedEffect(feedbackId) {
        viewModel.loadDetail(feedbackId)
        viewModel.markAsRead(feedbackId)
    }

    var highlightReplyId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(detailState, replyId) {
        if (replyId != null && detailState is FeedbackViewModel.DetailState.Success) {
            val replies = (detailState as FeedbackViewModel.DetailState.Success).data.replies
            val targetIndex = replies.indexOfFirst { it.id == replyId }
            if (targetIndex >= 0) {
                listState.scrollToItem(conversationReplyListItemIndex(targetIndex))
                highlightReplyId = replyId
            }
        }
    }

    LaunchedEffect(replyState) {
        if (replyState is FeedbackViewModel.ReplyState.Success) {
            replyText = ""
            replyScreenshots = emptyList()
            viewModel.resetReplyState()
            viewModel.loadDetail(feedbackId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val displayId = (detailState as? FeedbackViewModel.DetailState.Success)?.data?.feedback?.display_id
                    if (displayId != null && displayId.isNotBlank()) {
                        Text(text = stringResource(R.string.feedback_id_format, displayId), fontWeight = FontWeight.ExtraBold)
                    } else {
                        Text(stringResource(R.string.feedback_title), fontWeight = FontWeight.ExtraBold)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (val state = detailState) {
            is FeedbackViewModel.DetailState.Loading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            is FeedbackViewModel.DetailState.Error -> {
                Column(Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { viewModel.loadDetail(feedbackId) }) { Text(stringResource(R.string.feedback_retry)) }
                }
            }
            is FeedbackViewModel.DetailState.Success -> {
                val feedback = state.data.feedback
                val replies = state.data.replies
                val isClosed = feedback.status == "CLOSED"
                val isReplying = replyState is FeedbackViewModel.ReplyState.Uploading || replyState is FeedbackViewModel.ReplyState.Sending

                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "original") { OriginalFeedbackCard(feedback = feedback, onScreenshotClick = { urls, index -> fullscreenUrls = urls; fullscreenIndex = index }) }
                        if (replies.isNotEmpty()) {
                            item(key = "conv_title") { Text(text = stringResource(R.string.feedback_conversation), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                        }
                        items(replies, key = { it.id }) { reply -> ConversationBubble(reply = reply, highlight = highlightReplyId == reply.id, onHighlightDone = { if (highlightReplyId == reply.id) highlightReplyId = null }, onScreenshotClick = { urls, index -> fullscreenUrls = urls; fullscreenIndex = index }) }
                        item(key = "new_feedback") { Button(onClick = onNewFeedback, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.feedback_new)) } }
                    }
                    if (isClosed) {
                        Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text(text = stringResource(R.string.feedback_closed_hint), modifier = Modifier.padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else {
                        ReplyBar(text = replyText, onTextChange = { if (it.length <= 2000) replyText = it }, screenshots = replyScreenshots, enabled = !isReplying, onAddScreenshot = { pickImageLauncher.launch("image/*") }, onRemoveScreenshot = { idx -> replyScreenshots = replyScreenshots.toMutableList().apply { removeAt(idx) } }, onScreenshotClick = { idx -> replyFullscreenIndex = idx }, onSend = { if (replyText.isNotBlank()) { viewModel.reply(feedbackId = feedbackId, content = replyText, screenshotBytes = replyScreenshots.map { it.first }, screenshotMimeTypes = replyScreenshots.map { it.second }) } }, isSending = isReplying, replyState = replyState)
                    }
                }
            }
        }
    }

    fullscreenIndex?.let { index -> if (fullscreenUrls.isNotEmpty()) { ScreenshotFullscreenOverlay(images = fullscreenUrls, initialIndex = index, onDismiss = { fullscreenIndex = null }) } }
    replyFullscreenIndex?.let { index -> if (replyScreenshots.isNotEmpty()) { ScreenshotFullscreenOverlay(images = replyScreenshots.map { it.first }, initialIndex = index.coerceIn(0, replyScreenshots.size - 1), onDismiss = { replyFullscreenIndex = null }) } }
}

@Composable
private fun OriginalFeedbackCard(feedback: com.tracktosearch.data.remote.feedback.FeedbackDetail, onScreenshotClick: (urls: List<String>, index: Int) -> Unit) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(feedbackTypeLabel(feedback.type), fontSize = 12.sp, color = feedbackTypeColor(feedback.type), fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text(feedbackStatusLabel(feedback.status), fontSize = 12.sp, color = feedbackStatusColor(feedback.status)) }
            Text(text = feedback.content, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            val screenshots = parseScreenshots(feedback.screenshots)
            if (screenshots.isNotEmpty()) { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(screenshots) { key -> val url = screenshotUrl(key); Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).clickable { onScreenshotClick(screenshots.map { screenshotUrl(it) }, screenshots.indexOf(key)) }, contentAlignment = Alignment.Center) { AsyncImage(model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(true).build() }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp))) } } } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("App ${feedback.app_version}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(feedback.device_model, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            feedback.trakt_username?.let { if (it.isNotBlank()) Text("Trakt: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            feedback.douban_username?.let { if (it.isNotBlank()) Text("${stringResource(R.string.detail_info_douban_rating)}: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            feedback.contact?.let { if (it.isNotBlank()) Text("${stringResource(R.string.feedback_contact)}: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ConversationBubble(reply: FeedbackReply, highlight: Boolean, onHighlightDone: () -> Unit, onScreenshotClick: (urls: List<String>, index: Int) -> Unit) {
    val context = LocalContext.current
    val isDeveloper = reply.author_role == "developer"
    val arrangement = if (isDeveloper) Arrangement.Start else Arrangement.End
    val bubbleColor = if (isDeveloper) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
    val bubbleContentColor = if (isDeveloper) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
    val borderRadius = if (isDeveloper) RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp) else RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    val avatarColor = if (isDeveloper) Color(0xFF34D399) else MaterialTheme.colorScheme.primary
    val avatarLabel = if (isDeveloper) "D" else "我"

    val targetAlpha = if (highlight) 0.3f else 1f
    val animatedAlpha by animateFloatAsState(targetValue = targetAlpha, animationSpec = if (highlight) repeatable(iterations = 3, animation = tween(1500), repeatMode = RepeatMode.Reverse) else tween(300), finishedListener = { if (highlight) onHighlightDone() }, label = "highlight")
    val finalBubbleColor = if (isDeveloper) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = animatedAlpha) else MaterialTheme.colorScheme.primary.copy(alpha = animatedAlpha)

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalAlignment = Alignment.Top) {
        if (isDeveloper) { Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(avatarColor), contentAlignment = Alignment.Center) { Text(avatarLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }; Spacer(Modifier.width(8.dp)) }
        Column(modifier = Modifier.widthIn(max = 280.dp), horizontalAlignment = if (isDeveloper) Alignment.Start else Alignment.End) {
            Text(text = if (isDeveloper) stringResource(R.string.feedback_role_developer) else stringResource(R.string.feedback_role_me), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Surface(shape = borderRadius, color = finalBubbleColor) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = reply.content, fontSize = 14.sp, color = bubbleContentColor)
                    val screenshots = reply.screenshots
                    if (screenshots.isNotEmpty()) { LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(screenshots) { key -> val url = screenshotUrl(key); AsyncImage(model = remember(url) { ImageRequest.Builder(context).data(url).crossfade(true).build() }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).clickable { onScreenshotClick(screenshots.map { screenshotUrl(it) }, screenshots.indexOf(key)) }) } } }
                }
            }
            Text(text = formatTime(reply.created_at), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!isDeveloper) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).background(avatarColor), contentAlignment = Alignment.Center) { Text(avatarLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
    }
}

@Composable
private fun ReplyBar(text: String, onTextChange: (String) -> Unit, screenshots: List<Pair<ByteArray, String>>, enabled: Boolean, onAddScreenshot: () -> Unit, onRemoveScreenshot: (Int) -> Unit, onScreenshotClick: (Int) -> Unit, onSend: () -> Unit, isSending: Boolean, replyState: FeedbackViewModel.ReplyState) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (screenshots.isNotEmpty()) { LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { itemsIndexed(screenshots, key = { _, pair -> pair.first }) { index, (bytes, _) -> Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) { AsyncImage(model = remember(bytes) { ImageRequest.Builder(context).data(bytes).crossfade(true).build() }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(60.dp).clickable { onScreenshotClick(index) }); if (enabled) { Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(9.dp)).clickable { onRemoveScreenshot(index) }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp)) } } } } } }
            (replyState as? FeedbackViewModel.ReplyState.Error)?.let { Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            val progressText = when (replyState) { is FeedbackViewModel.ReplyState.Uploading -> stringResource(R.string.feedback_reply_uploading, replyState.current + 1, replyState.total); is FeedbackViewModel.ReplyState.Sending -> stringResource(R.string.feedback_submitting); else -> null }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (enabled && screenshots.size < MAX_REPLY_SCREENSHOTS) { IconButton(onClick = onAddScreenshot, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, contentDescription = null) } }
                OutlinedTextField(value = text, onValueChange = onTextChange, modifier = Modifier.weight(1f), placeholder = { Text(stringResource(R.string.feedback_reply_placeholder), fontSize = 13.sp) }, enabled = enabled, maxLines = 4, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
                Button(onClick = onSend, enabled = enabled && text.isNotBlank(), modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { if (isSending) { CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) } else { Icon(Icons.Rounded.Send, contentDescription = null) } }
            }
            progressText?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun parseScreenshots(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try { val trimmed = json.trim().removeSurrounding("[", "]").split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }; trimmed } catch (_: Exception) { emptyList() }
}

private fun formatTime(timestamp: Long): String { val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()); return sdf.format(java.util.Date(timestamp * 1000)) }

@Composable private fun feedbackTypeLabel(type: String): String = stringResource(when (type) { "FEATURE" -> R.string.feedback_type_feature; "BUG" -> R.string.feedback_type_bug; "UX" -> R.string.feedback_type_ux; else -> R.string.feedback_type_other })
@Composable private fun feedbackTypeColor(type: String): Color = when (type) { "FEATURE" -> Color(0xFF34D399); "BUG" -> Color(0xFFFB7185); "UX" -> Color(0xFFFBBF24); else -> Color(0xFF9CA3AF) }
@Composable private fun feedbackStatusLabel(status: String): String = stringResource(when (status) { "PENDING" -> R.string.feedback_status_pending; "REPLIED" -> R.string.feedback_status_replied; else -> R.string.feedback_status_closed })
@Composable private fun feedbackStatusColor(status: String): Color = when (status) { "PENDING" -> MaterialTheme.colorScheme.primary; "REPLIED" -> Color(0xFF10B981); else -> MaterialTheme.colorScheme.onSurfaceVariant }
