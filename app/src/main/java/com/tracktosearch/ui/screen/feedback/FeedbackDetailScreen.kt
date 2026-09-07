package com.tracktosearch.ui.screen.feedback

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.ImageView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
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
import com.tracktosearch.ui.component.OpenImageViewerItem
import com.tracktosearch.ui.component.openImageViewer
import com.tracktosearch.ui.component.recordOpenImageBounds
import com.tracktosearch.ui.component.rememberOpenImageBounds
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.data.remote.feedback.FeedbackDetail
import com.tracktosearch.data.remote.feedback.FeedbackReply
import com.tracktosearch.data.remote.feedback.screenshotUrl
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.FeedbackListCardCorner
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.SharedCorner
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.appSkipToLookaheadSize
import com.tracktosearch.ui.component.feedbackCardSharedKey
import com.tracktosearch.ui.component.isAppSharedTransitionActive
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FeedbackDetailScreen(
    feedbackId: String,
    replyId: String? = null,
    onBack: () -> Unit,
    onNewFeedback: () -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    // 共享元素转场作用域：与反馈列表里那张卡片配对，整页作为容器一起变形。
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
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

    var replyText by remember { mutableStateOf("") }
    var replyScreenshots by remember { mutableStateOf<List<Pair<ByteArray, String>>>(emptyList()) }

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

    val outcomeHaptics = rememberAppHaptics()
    LaunchedEffect(replyState) {
        when (replyState) {
            is FeedbackViewModel.ReplyState.Success -> {
                outcomeHaptics.confirm()
                replyText = ""
                replyScreenshots = emptyList()
                viewModel.resetReplyState()
                viewModel.loadDetail(feedbackId)
            }
            // 失败态在输入区上方渲染成错误卡片，触感是对那张卡片的补充
            is FeedbackViewModel.ReplyState.Error -> outcomeHaptics.reject()
            // 上传中/发送中/空闲都不是结果
            FeedbackViewModel.ReplyState.Idle,
            is FeedbackViewModel.ReplyState.Uploading,
            FeedbackViewModel.ReplyState.Sending,
            -> Unit
        }
    }

    Scaffold(
        contentWindowInsets = feedbackDetailScaffoldContentWindowInsets(),
        // 容器色置透明、底色改由下面那个共享节点自己画，理由见该处注释。
        // contentColor 显式写成 onBackground：Scaffold 默认取 contentColorFor(containerColor)，
        // 而 contentColorFor(Transparent) 是 Unspecified，会让整页文字颜色退回外层 LocalContentColor。
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 与反馈列表卡片配对的是整页，而不是顶栏：卡片放大成页面、返回时收回成卡片。
                // 挂在这个 Box 上是因为它是加载/错误/成功三个状态分支唯一的公共节点。
                // 卡片侧圆角 FeedbackListCardCorner，页面侧是 0，转场期间在两者之间插值。
                .appSharedBounds(
                    key = feedbackCardSharedKey(feedbackId),
                    animatedVisibilityScope = animatedVisibilityScope,
                    corner = SharedCorner.flattenFrom(FeedbackListCardCorner),
                    // 容器变形要的是「内容不变形、被裁剪逐渐露出」，默认的 scaleToBounds 会把内容
                    // 跟着容器一起缩放绘制。逐帧重测的代价由内容侧的 appSkipToLookaheadSize 挡掉。
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                )
                // 页面底色挪进共享节点内侧，并把 Scaffold 的容器色置透明。
                // 否则 Scaffold 会在共享节点之外先铺满一整屏不透明底色，转场第一帧整屏就已经是本页的背景，
                // 「卡片长成页面」退化成「页面已经在了，只是内容从一个小矩形里长出来」。
                // 挪进来之后底色跟着动画边界一起长大，且被上面那层圆角动画裁剪，落定后与原来逐像素相同。
                .background(MaterialTheme.colorScheme.background)
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
                                // 整页参与容器变形时按落定尺寸布局：否则列表会跟着容器逐帧变宽，
                                // 一次转场里重复决定「哪些项可见、每项多宽」几十遍
                                .appSkipToLookaheadSize()
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
                                    timeLabels = timeLabels
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
                                    highlight = highlightReplyId == reply.id,
                                    onHighlightDone = {
                                        if (highlightReplyId == reply.id) highlightReplyId = null
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
                                enabled = !isReplying,
                                onAddScreenshot = { pickImageLauncher.launch("image/*") },
                                onRemoveScreenshot = { idx ->
                                    replyScreenshots = replyScreenshots.toMutableList()
                                        .apply { removeAt(idx) }
                                },
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
                onNewFeedback = onNewFeedback,
                // 顶栏不参与配对，但从第一帧就在：与内容一样按落定尺寸布局，跟着容器裁剪逐渐露出。
                // 延迟入场会让容器长大的那段时间顶栏位置空着，落位时再整片闪出来。
                modifier = Modifier.appSkipToLookaheadSize()
            )
        }
    }

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
    onNewFeedback: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 容器变形期间每帧背景都在变，此时还做实时模糊采样正是掉帧最集中的地方，先让 haze 停下来。
    val transitionActive = isAppSharedTransitionActive()
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 顶栏不参与配对：来源侧那张卡片上没有对应的标题栏，硬配对会把一行标题从卡片尺寸拉过来。
            // 调用点因此传的是延迟入场的修饰符，观感上是「卡片先长成页面，页面再把顶栏放上来」。
            .then(modifier)
            .hazeTopBar(
                state = hazeState,
                style = hazeStyle,
                blurRadius = 24.dp,
                isContentUnderTopBar = isContentUnderTopBar && !transitionActive
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
                IconButton(onClick = {
                    haptics.lightTap()
                    onNewFeedback()
                }) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OriginalFeedbackCard(
    feedback: FeedbackDetail,
    timeLabels: FeedbackTimeLabels
) {
    val context = LocalContext.current
    val screenshots = remember(feedback.screenshots) { parseScreenshots(feedback.screenshots) }
    // 服务端截图 URL（/feedback-api/screenshot/<key>）无尺寸段：查看器大图与缩略图同 URL
    val screenshotViewerItems = remember(screenshots) {
        screenshots.map { OpenImageViewerItem(largeUrl = screenshotUrl(it)) }
    }
    val screenshotBounds = rememberOpenImageBounds()
    val activity = context as? Activity

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
                                // 记录该缩略图的 window 矩形，OpenImage 打开/返回动画以它为落点
                                .recordOpenImageBounds(index, screenshotBounds)
                                .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                                    val currentActivity = activity
                                    if (currentActivity != null) {
                                        // 反馈截图缩略图是 ContentScale.Fit，动画占位须用 FIT_CENTER 对齐
                                        openImageViewer(
                                            activity = currentActivity,
                                            items = screenshotViewerItems,
                                            bounds = screenshotBounds,
                                            clickedIndex = index,
                                            thumbnailScaleType = ImageView.ScaleType.FIT_CENTER
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = remember(url) {
                                    ImageRequest.Builder(context).data(url).crossfade(true).build()
                                },
                                contentDescription = stringResource(R.string.feedback_screenshots),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
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

@Composable
private fun ConversationBubble(
    reply: FeedbackReply,
    dayLabel: String?,
    timeLabels: FeedbackTimeLabels,
    highlight: Boolean,
    onHighlightDone: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
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
                        // 每条回复气泡独立一份 bounds/items，下标只在本气泡内对齐
                        val bubbleBounds = rememberOpenImageBounds()
                        val bubbleViewerItems = remember(screenshots) {
                            screenshots.map { OpenImageViewerItem(largeUrl = screenshotUrl(it)) }
                        }
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
                                        .recordOpenImageBounds(index, bubbleBounds)
                                        .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                                            val currentActivity = activity
                                            if (currentActivity != null) {
                                                openImageViewer(
                                                    activity = currentActivity,
                                                    items = bubbleViewerItems,
                                                    bounds = bubbleBounds,
                                                    clickedIndex = index,
                                                    thumbnailScaleType = ImageView.ScaleType.FIT_CENTER
                                                )
                                            }
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

@Composable
private fun ReplyBar(
    text: String,
    onTextChange: (String) -> Unit,
    screenshots: List<Pair<ByteArray, String>>,
    enabled: Boolean,
    onAddScreenshot: () -> Unit,
    onRemoveScreenshot: (Int) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    replyState: FeedbackViewModel.ReplyState
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
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
                // 本地 ByteArray 截图：OpenImage 只收 URL，点开前先把整组截图落 cache 成文件
                val previewBounds = rememberOpenImageBounds()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(screenshots, key = { _, pair -> pair.first }) { index, (bytes, _) ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .recordOpenImageBounds(index, previewBounds)
                                .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) {
                                    val currentActivity = activity
                                    if (currentActivity != null) {
                                        scope.launch {
                                            // IO 线程落盘，完成后回主线程打开查看器（可横滑整组）
                                            val files = withContext(Dispatchers.IO) {
                                                screenshots.mapIndexedNotNull { i, (imageBytes, imageMime) ->
                                                    writeScreenshotToCache(context, imageBytes, imageMime, "fb_reply", i)
                                                }
                                            }
                                            if (files.size == screenshots.size) {
                                                openImageViewer(
                                                    activity = currentActivity,
                                                    items = files.map {
                                                        OpenImageViewerItem(largeUrl = Uri.fromFile(it).toString())
                                                    },
                                                    bounds = previewBounds,
                                                    clickedIndex = index,
                                                    thumbnailScaleType = ImageView.ScaleType.FIT_CENTER
                                                )
                                            }
                                        }
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = remember(bytes) {
                                    ImageRequest.Builder(context).data(bytes).crossfade(true).build()
                                },
                                contentDescription = stringResource(R.string.feedback_screenshots),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (enabled) {
                                // 删除键原先是默认 48dp 的 IconButton，盖住整块缩略图，
                                // 想点开预览反而先删了；收到 32dp，下半块留给预览
                                IconButton(
                                    onClick = {
                                        haptics.lightTap()
                                        onRemoveScreenshot(index)
                                    },
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
                    IconButton(
                        onClick = {
                            haptics.lightTap()
                            onAddScreenshot()
                        }
                    ) {
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
                        haptics.tap()
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

/**
 * 把本地 ByteArray 截图落到 cacheDir/openimage_preview/ 临时文件，供 OpenImage 查看器加载。
 * OpenImage 只收 URL；cache 目录由系统管理可清理，查看器打开后不主动删文件。
 * 落盘失败返回 null，调用方按实际写入数量决定是否打开查看器。
 */
private fun writeScreenshotToCache(
    context: Context,
    bytes: ByteArray,
    mimeType: String,
    tag: String,
    index: Int,
): File? = try {
    val extension = when {
        mimeType.equals("image/png", ignoreCase = true) -> "png"
        mimeType.equals("image/webp", ignoreCase = true) -> "webp"
        mimeType.equals("image/gif", ignoreCase = true) -> "gif"
        else -> "jpg"
    }
    val dir = File(context.cacheDir, "openimage_preview").apply { mkdirs() }
    File(dir, "${tag}_${System.currentTimeMillis()}_$index.$extension")
        .apply { writeBytes(bytes) }
} catch (_: Exception) {
    null
}
















