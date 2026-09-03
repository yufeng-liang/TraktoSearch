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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.data.remote.feedback.FeedbackDetail
import com.tracktosearch.data.remote.feedback.FeedbackReply
import com.tracktosearch.data.remote.feedback.screenshotUrl
import com.tracktosearch.ui.component.LocalFullscreenSharedElement
import com.tracktosearch.ui.component.fullscreenSharedElementKey
import com.tracktosearch.ui.component.ZoomableImageOverlay
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.zoomSharedSource
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

private const val MAX_REPLY_SCREENSHOTS = 5
private const val MAX_REPLY_LENGTH = 2000

/** 字数计数器只在快到上限时出现，平时不在输入框上方占一行。 */
private const val REPLY_COUNTER_VISIBLE_FROM = 1800

// 详情页由系统 adjustResize 避让键盘，Scaffold 不应再次预消费底部 IME inset。
internal fun feedbackDetailScaffoldContentWindowInsets(): WindowInsets = WindowInsets(0, 0, 0, 0)

/**
 * 回复在列表里的下标：0 是原帖卡，1 是「对话」小标题。
 *
 * 日期分隔标签画在气泡 item 内部而不是自己占一个 item，就是为了让这个偏移量保持常数 2，
 * 否则带 replyId 的深链会跳到错的位置。
 */
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
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val timeLabels = rememberFeedbackTimeLabels()
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = listState.firstVisibleItemScrollOffset
            )
        }
    }

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
    CompositionLocalProvider(
        LocalFullscreenSharedElement provides fullscreenSharedElementKey(fullscreenSharedKey)
    ) {

    Scaffold(
        contentWindowInsets = feedbackDetailScaffoldContentWindowInsets(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 内容从顶栏下面穿过去，靠 contentPadding 让首屏不被压住
            val topBarHeight = 64.dp +
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            when (val state = detailState) {
                is FeedbackViewModel.DetailState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topBarHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is FeedbackViewModel.DetailState.Error -> {
                    AppErrorState(
                        message = state.message,
                        onRetry = { viewModel.loadDetail(feedbackId) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topBarHeight),
                        retryLabel = stringResource(R.string.feedback_retry)
                    )
                }
                is FeedbackViewModel.DetailState.Success -> {
                    val feedback = state.data.feedback
                    val replies = state.data.replies
                    val isClosed = feedback.status == "CLOSED"
                    val isReplying = replyState is FeedbackViewModel.ReplyState.Uploading ||
                        replyState is FeedbackViewModel.ReplyState.Sending
                    // 同一天的回复只在第一条上方给一次日期标签
                    val dayLabels = remember(replies, timeLabels) {
                        var previousLabel: String? = null
                        replies.map { reply ->
                            val label = timeLabels.dayLabel(reply.created_at)
                            val header = label.takeIf { it != previousLabel }
                            previousLabel = label
                            header
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .hazeSource(state = hazeState),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = topBarHeight,
                                end = 16.dp,
                                bottom = 24.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item(key = "original") {
                                OriginalFeedbackCard(
                                    feedback = feedback,
                                    timeLabels = timeLabels,
                                    sharedKeyPrefix = "fb-conv-$feedbackId",
                                    onScreenshotClick = { urls, index ->
                                        fullscreenKeyPrefix = "fb-conv-$feedbackId"
                                        fullscreenUrls = urls
                                        fullscreenIndex = index
                                    }
                                )
                            }
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
                            itemsIndexed(replies, key = { _, reply -> reply.id }) { index, reply ->
                                ConversationBubble(
                                    reply = reply,
                                    dayLabel = dayLabels.getOrNull(index),
                                    timeLabels = timeLabels,
                                    sharedKeyPrefix = "fb-reply-${reply.id}",
                                    highlight = highlightReplyId == reply.id,
                                    onHighlightDone = {
                                        if (highlightReplyId == reply.id) highlightReplyId = null
                                    },
                                    onScreenshotClick = { urls, shotIndex ->
                                        fullscreenKeyPrefix = "fb-reply-${reply.id}"
                                        fullscreenUrls = urls
                                        fullscreenIndex = shotIndex
                                    }
                                )
                            }
                        }
                        if (isClosed) {
                            ClosedFeedbackFooter()
                        } else {
                            ReplyBar(
                                text = replyText,
                                onTextChange = { if (it.length <= MAX_REPLY_LENGTH) replyText = it },
                                screenshots = replyScreenshots,
                                sharedKeyPrefix = "fb-compose",
                                enabled = !isReplying,
                                onAddScreenshot = { pickImageLauncher.launch("image/*") },
                                onRemoveScreenshot = { idx ->
                                    replyScreenshots = replyScreenshots.toMutableList()
                                        .apply { removeAt(idx) }
                                },
                                onScreenshotClick = { idx -> replyFullscreenIndex = idx },
                                onSend = {
                                    if (replyText.isNotBlank()) {
                                        viewModel.reply(
                                            feedbackId = feedbackId,
                                            content = replyText,
                                            screenshotBytes = replyScreenshots.map { it.first },
                                            screenshotMimeTypes = replyScreenshots.map { it.second }
                                        )
                                    }
                                },
                                isSending = isReplying,
                                replyState = replyState
                            )
                        }
                    }
                }
            }

            FeedbackDetailTopBar(
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                isContentUnderTopBar = hasContentUnderTopBar,
                displayId = (detailState as? FeedbackViewModel.DetailState.Success)
                    ?.data?.feedback?.display_id,
                onBack = onBack,
                onNewFeedback = onNewFeedback
            )
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
    } // CompositionLocalProvider(LocalFullscreenSharedElement)
}

/** 详情页顶栏：与消息页同一套 haze 贴顶写法，内容从下面穿过去。 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FeedbackDetailTopBar(
    hazeState: HazeState,
    hazeStyle: HazeBlurStyle,
    isContentUnderTopBar: Boolean,
    displayId: String?,
    onBack: () -> Unit,
    onNewFeedback: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = hazeStyle,
                blurRadius = 24.dp,
                isContentUnderTopBar = isContentUnderTopBar
            )
            // 顶栏覆盖列表，拦截空白区域点击，避免穿透到下面的卡片
            .clickable(enabled = false, onClick = {})
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        TopAppBar(
            title = {
                Text(
                    text = if (!displayId.isNullOrBlank()) {
                        stringResource(R.string.feedback_id_format, displayId)
                    } else {
                        stringResource(R.string.feedback_title)
                    },
                    fontWeight = FontWeight.ExtraBold
                )
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
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            windowInsets = WindowInsets(0, 0, 0, 0)
        )
    }
}

/** 已关闭：原先是一条通栏灰底文字，看着像输入框被禁用；改成明确带锁图标的提示条。 */
@Composable
private fun ClosedFeedbackFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        FeedbackNoticeBanner(
            icon = Icons.Rounded.Lock,
            text = stringResource(R.string.feedback_closed_hint),
            accent = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalLayoutApi::class)
@Composable
private fun OriginalFeedbackCard(
    feedback: FeedbackDetail,
    timeLabels: FeedbackTimeLabels,
    sharedKeyPrefix: String? = null,
    onScreenshotClick: (urls: List<String>, index: Int) -> Unit
) {
    val context = LocalContext.current
    val screenshots = remember(feedback.screenshots) { parseScreenshots(feedback.screenshots) }

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
                FeedbackTypeBadge(type = feedback.type)
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeLabels.relative(feedback.created_at),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // 状态只有三个值且单向推进，画成时间线比一个胶囊更能回答「我这条走到哪了」
            FeedbackStatusTimeline(status = feedback.status)

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
                if (feedback.device_model.isNotBlank()) {
                    add(stringResource(R.string.feedback_device_model, feedback.device_model))
                }
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
                // 原先是 chunked(2) 两列 11sp 裸文字，值长了就被截在一半；
                // 换成自动换行的小胶囊，每条按自己的长度占位
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    appInfo.forEach { value ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                        ) {
                            Text(
                                text = value,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ConversationBubble(
    reply: FeedbackReply,
    dayLabel: String?,
    timeLabels: FeedbackTimeLabels,
    sharedKeyPrefix: String? = null,
    highlight: Boolean,
    onHighlightDone: () -> Unit,
    onScreenshotClick: (urls: List<String>, index: Int) -> Unit
) {
    val context = LocalContext.current
    val isDeveloper = reply.author_role == "developer"
    val arrangement = if (isDeveloper) Arrangement.Start else Arrangement.End
    val bubbleColor = if (isDeveloper) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    val bubbleContentColor = if (isDeveloper) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val borderRadius = if (isDeveloper) {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    }

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
        if (dayLabel != null) {
            // 日期分隔放在气泡 item 内部，别单独占 item，否则 conversationReplyListItemIndex 会错位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = dayLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = arrangement,
            verticalAlignment = Alignment.Bottom
        ) {
            // 角色原先是头像里手写的 "D" / "我"，读屏念不出；换成带 contentDescription 的图标头像
            if (isDeveloper) {
                RoleAvatar(isDeveloper = true, size = 32.dp)
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
                    Text(
                        text = reply.content,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = bubbleContentColor
                    )
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
                RoleAvatar(isDeveloper = false, size = 32.dp)
            }
        }
        Text(
            // 绝对时间戳 yyyy-MM-dd HH:mm 在对话流里太长，日期已经由分隔标签给了
            text = timeLabels.relative(reply.created_at),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 2.dp),
            textAlign = if (isDeveloper) TextAlign.Start else TextAlign.End,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ReplyBar(
    text: String,
    onTextChange: (String) -> Unit,
    screenshots: List<Pair<ByteArray, String>>,
    sharedKeyPrefix: String? = null,
    enabled: Boolean,
    onAddScreenshot: () -> Unit,
    onRemoveScreenshot: (Int) -> Unit,
    onScreenshotClick: (Int) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    replyState: FeedbackViewModel.ReplyState
) {
    val context = LocalContext.current
    val view = LocalView.current
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
                                // 删除键原先是默认 48dp 的 IconButton，盖住整块缩略图，
                                // 想点开预览反而先删了；收到 32dp，下半块留给预览
                                IconButton(
                                    onClick = { onRemoveScreenshot(index) },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(32.dp)
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
            // 上传是逐张走的，给确定进度比一个转圈能回答「还剩几张」
            (replyState as? FeedbackViewModel.ReplyState.Uploading)?.let { uploading ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = {
                            if (uploading.total <= 0) 0f
                            else uploading.current.toFloat() / uploading.total
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                    )
                    Text(
                        text = stringResource(
                            R.string.feedback_uploading,
                            (uploading.current + 1).coerceAtMost(uploading.total),
                            uploading.total
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            (replyState as? FeedbackViewModel.ReplyState.Error)?.let {
                FeedbackNoticeBanner(
                    icon = Icons.Rounded.ErrorOutline,
                    text = it.message,
                    accent = MaterialTheme.colorScheme.error
                )
            }
            // 计数器平时不出现：2000 字对一条回复来说基本碰不到，常驻只是噪音
            if (text.length >= REPLY_COUNTER_VISIBLE_FROM) {
                Text(
                    text = "${text.length}/$MAX_REPLY_LENGTH",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    fontSize = 11.sp,
                    color = if (text.length >= MAX_REPLY_LENGTH) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
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
                    // 圆角输入框配右侧圆形发送键，比直角框更像对话输入
                    shape = RoundedCornerShape(22.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
                FilledIconButton(
                    onClick = {
                        view.performHaptic(HapticType.CLICK)
                        onSend()
                    },
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
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
















