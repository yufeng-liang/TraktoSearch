package com.tracktosearch.ui.screen.feedback

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.feedback.FeedbackReply
import com.tracktosearch.data.remote.feedback.screenshotUrl
import com.tracktosearch.ui.component.LocalFullscreenSharedKey
import com.tracktosearch.ui.component.ZoomableImageOverlay
import com.tracktosearch.ui.component.zoomSharedSource

private const val MAX_REPLY_SCREENSHOTS = 5

// 详情页由系统 adjustResize 避让键盘，Scaffold 不应再次预消费底部 IME inset。
internal fun feedbackDetailScaffoldContentWindowInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)

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

    var fullscreenUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var fullscreenIndex by remember { mutableStateOf<Int?>(null) }
    // 当前全屏图片来源前缀：原帖/回复气泡共用同一 fullscreen overlay，
    // 点击时记录来源，确保全屏端 sharedElement key 与缩略图源配对
    var fullscreenKeyPrefix by remember { mutableStateOf("fb-conv-$feedbackId") }

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

    // 全屏查看器打开时把该 key 广播给缩略图源侧，让源侧置不可见。
    // 同一 key 两侧同时是 target 时，SharedTransitionStateMachine 会取先注册的源侧作为
    // 目标边界提供者，打开方向会反转，观感上等于没有缩放动画。
    val fullscreenSharedKey = when {
        fullscreenIndex != null && fullscreenUrls.isNotEmpty() && fullscreenKeyPrefix != null ->
            "$fullscreenKeyPrefix-${fullscreenIndex!!.coerceIn(0, fullscreenUrls.size - 1)}"
        replyFullscreenIndex != null && replyScreenshots.isNotEmpty() ->
            "fb-compose-${replyFullscreenIndex!!.coerceIn(0, replyScreenshots.size - 1)}"
        else -> null
    }
    CompositionLocalProvider(LocalFullscreenSharedKey provides fullscreenSharedKey) {

    Scaffold(
        contentWindowInsets = feedbackDetailScaffoldContentWindowInsets(),
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewFeedback) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.feedback_new)
                        )
                    }
                },
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item(key = "original") { OriginalFeedbackCard(feedback = feedback, sharedKeyPrefix = "fb-conv-$feedbackId", onScreenshotClick = { urls, index -> fullscreenKeyPrefix = "fb-conv-$feedbackId"; fullscreenUrls = urls; fullscreenIndex = index }) }
                        if (replies.isNotEmpty() || state.isRefreshing) {
                            item(key = "conv_title") {
                                Row(
                                    modifier = Modifier.padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.feedback_conversation),
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (state.isRefreshing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                        }
                        items(replies, key = { it.id }) { reply -> ConversationBubble(reply = reply, sharedKeyPrefix = "fb-reply-${reply.id}", highlight = highlightReplyId == reply.id, onHighlightDone = { if (highlightReplyId == reply.id) highlightReplyId = null }, onScreenshotClick = { urls, index -> fullscreenKeyPrefix = "fb-reply-${reply.id}"; fullscreenUrls = urls; fullscreenIndex = index }) }
                    }
                    if (isClosed) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding(),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = stringResource(R.string.feedback_closed_hint),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        ReplyBar(text = replyText, onTextChange = { if (it.length <= 2000) replyText = it }, screenshots = replyScreenshots, sharedKeyPrefix = "fb-compose", enabled = !isReplying, onAddScreenshot = { pickImageLauncher.launch("image/*") }, onRemoveScreenshot = { idx -> replyScreenshots = replyScreenshots.toMutableList().apply { removeAt(idx) } }, onScreenshotClick = { idx -> replyFullscreenIndex = idx }, onSend = { if (replyText.isNotBlank()) { viewModel.reply(feedbackId = feedbackId, content = replyText, screenshotBytes = replyScreenshots.map { it.first }, screenshotMimeTypes = replyScreenshots.map { it.second }) } }, isSending = isReplying, replyState = replyState)
                    }
                }
            }
        }
    }

    // 原帖截图全屏（fullscreenKeyPrefix 由点击来源决定：原帖 "fb-conv-$feedbackId" 或回复气泡 "fb-reply-${reply.id}"）
    ZoomableImageOverlay(
        visible = fullscreenIndex != null && fullscreenUrls.isNotEmpty(),
        images = fullscreenUrls,
        initialIndex = fullscreenIndex?.coerceIn(0, fullscreenUrls.size - 1) ?: 0,
        sharedKeyPrefix = fullscreenKeyPrefix,
        onDismiss = { fullscreenIndex = null }
    )
    // 回复框预览全屏
    ZoomableImageOverlay(
        visible = replyFullscreenIndex != null && replyScreenshots.isNotEmpty(),
        images = replyScreenshots.map { it.first },
        initialIndex = replyFullscreenIndex?.coerceIn(0, replyScreenshots.size - 1) ?: 0,
        sharedKeyPrefix = "fb-compose",
        onDismiss = { replyFullscreenIndex = null }
    )
    } // CompositionLocalProvider(LocalFullscreenSharedKey)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun OriginalFeedbackCard(feedback: com.tracktosearch.data.remote.feedback.FeedbackDetail, sharedKeyPrefix: String? = null, onScreenshotClick: (urls: List<String>, index: Int) -> Unit) {
    val context = LocalContext.current
    val screenshots = parseScreenshots(feedback.screenshots)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = feedbackTypeLabel(feedback.type),
                    fontSize = 13.sp,
                    color = feedbackTypeColor(feedback.type),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = feedbackStatusColor(feedback.status).copy(alpha = 0.14f)
                ) {
                    Text(
                        text = feedbackStatusLabel(feedback.status),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 12.sp,
                        color = feedbackStatusColor(feedback.status),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = feedback.content,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (screenshots.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(screenshots, key = { it }) { key ->
                        val index = screenshots.indexOf(key)
                        val url = screenshotUrl(key)
                        Box(
                            modifier = Modifier
                                .width(112.dp)
                                .height(160.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    onScreenshotClick(screenshots.map(::screenshotUrl), index)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = remember(url) {
                                    ImageRequest.Builder(context).data(url).crossfade(true).build()
                                },
                                contentDescription = stringResource(R.string.feedback_screenshots),
                                contentScale = ContentScale.Fit,
                                // 与全屏端 "$sharedKeyPrefix-$page" 配对；caller-managed visibility
                                // 保证同一 key 同时只有一侧是 target
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zoomSharedSource(key = sharedKeyPrefix?.let { "$it-$index" })
                            )
                        }
                    }
                }
            }

            val appInfo = buildList {
                add(stringResource(R.string.feedback_app_version, feedback.app_version))
                add(stringResource(R.string.feedback_device_model, feedback.device_model))
                feedback.trakt_username?.takeIf { it.isNotBlank() }?.let {
                    add(stringResource(R.string.feedback_trakt_username, it))
                }
                feedback.douban_username?.takeIf { it.isNotBlank() }?.let {
                    add(stringResource(R.string.feedback_douban_username, it))
                }
                feedback.contact?.takeIf { it.isNotBlank() }?.let {
                    add(stringResource(R.string.feedback_contact_value, it))
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.feedback_app_info),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                appInfo.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowItems.forEach { value ->
                            Text(
                                text = value,
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ConversationBubble(reply: FeedbackReply, sharedKeyPrefix: String? = null, highlight: Boolean, onHighlightDone: () -> Unit, onScreenshotClick: (urls: List<String>, index: Int) -> Unit) {
    val context = LocalContext.current
    val isDeveloper = reply.author_role == "developer"
    val arrangement = if (isDeveloper) Arrangement.Start else Arrangement.End
    val bubbleColor = if (isDeveloper) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
    val bubbleContentColor = if (isDeveloper) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
    val borderRadius = if (isDeveloper) RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp) else RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    val avatarColor = if (isDeveloper) Color(0xFF34D399) else MaterialTheme.colorScheme.primary
    val avatarLabel = if (isDeveloper) "D" else "我"

    val highlightProgress by animateFloatAsState(
        targetValue = if (highlight) 1f else 0f,
        animationSpec = if (highlight) {
            repeatable(iterations = 6, animation = tween(180), repeatMode = RepeatMode.Reverse)
        } else {
            tween(250)
        },
        finishedListener = { if (highlight) onHighlightDone() },
        label = "highlight"
    )
    val highlightBorder = if (highlightProgress > 0f) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = highlightProgress))
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalAlignment = Alignment.Bottom) {
        if (isDeveloper) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.feedback_role_developer), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(avatarColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(avatarLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = borderRadius,
            color = bubbleColor,
            border = highlightBorder,
            tonalElevation = if (isDeveloper) 1.dp else 0.dp
        ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = reply.content, fontSize = 14.sp, color = bubbleContentColor)
                    val screenshots = reply.screenshots
                    if (screenshots.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(screenshots, key = { it }) { key ->
                                val url = screenshotUrl(key)
                                val index = screenshots.indexOf(key)
                                AsyncImage(
                                    model = remember(url) {
                                        ImageRequest.Builder(context).data(url).crossfade(true).build()
                                    },
                                    contentDescription = stringResource(R.string.feedback_screenshots),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                                        // 与全屏端 "$sharedKeyPrefix-$page" 配对；caller-managed visibility
                                        // 保证同一 key 同时只有一侧是 target
                                        .zoomSharedSource(key = sharedKeyPrefix?.let { "$it-$index" })
                                        .clickable {
                                            onScreenshotClick(screenshots.map(::screenshotUrl), index)
                                        }
                                )
                            }
                        }
                    }
                }
            }
            if (!isDeveloper) {
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.feedback_role_me), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier.size(32.dp).clip(CircleShape).background(avatarColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(avatarLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(
            text = formatTime(reply.created_at),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp),
            textAlign = if (isDeveloper) TextAlign.Start else TextAlign.End,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ReplyBar(text: String, onTextChange: (String) -> Unit, screenshots: List<Pair<ByteArray, String>>, sharedKeyPrefix: String? = null, enabled: Boolean, onAddScreenshot: () -> Unit, onRemoveScreenshot: (Int) -> Unit, onScreenshotClick: (Int) -> Unit, onSend: () -> Unit, isSending: Boolean, replyState: FeedbackViewModel.ReplyState) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (screenshots.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(screenshots, key = { _, pair -> pair.first }) { index, (bytes, _) ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { onScreenshotClick(index) }
                        ) {
                            AsyncImage(
                                model = remember(bytes) {
                                    ImageRequest.Builder(context).data(bytes).crossfade(true).build()
                                },
                                contentDescription = stringResource(R.string.feedback_screenshots),
                                contentScale = ContentScale.Fit,
                                // 与全屏端 "$sharedKeyPrefix-$page" 配对；caller-managed visibility
                                // 保证同一 key 同时只有一侧是 target
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zoomSharedSource(key = sharedKeyPrefix?.let { "$it-$index" })
                            )
                            if (enabled) {
                                IconButton(
                                    onClick = { onRemoveScreenshot(index) },
                                    modifier = Modifier.align(Alignment.TopEnd)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.cd_delete),
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                            .padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            (replyState as? FeedbackViewModel.ReplyState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (enabled && screenshots.size < MAX_REPLY_SCREENSHOTS) {
                    IconButton(onClick = onAddScreenshot) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.feedback_screenshots)
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    placeholder = {
                        Text(stringResource(R.string.feedback_reply_placeholder), fontSize = 13.sp)
                    },
                    enabled = enabled,
                    maxLines = 4,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.feedback_reply_send)
                        )
                    }
                }
            }
        }
    }
}

internal fun parseScreenshots(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        kotlinx.serialization.json.Json.decodeFromString<List<String>>(json)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun formatTime(timestamp: Long): String { val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()); return sdf.format(java.util.Date(timestamp * 1000)) }

@Composable private fun feedbackTypeLabel(type: String): String = stringResource(when (type) { "FEATURE" -> R.string.feedback_type_feature; "BUG" -> R.string.feedback_type_bug; "UX" -> R.string.feedback_type_ux; else -> R.string.feedback_type_other })
@Composable private fun feedbackTypeColor(type: String): Color = when (type) { "FEATURE" -> Color(0xFF34D399); "BUG" -> Color(0xFFFB7185); "UX" -> Color(0xFFFBBF24); else -> Color(0xFF9CA3AF) }
@Composable private fun feedbackStatusLabel(status: String): String = stringResource(when (status) { "PENDING" -> R.string.feedback_status_pending; "REPLIED" -> R.string.feedback_status_replied; else -> R.string.feedback_status_closed })
@Composable private fun feedbackStatusColor(status: String): Color = when (status) { "PENDING" -> MaterialTheme.colorScheme.primary; "REPLIED" -> Color(0xFF10B981); else -> MaterialTheme.colorScheme.onSurfaceVariant }
