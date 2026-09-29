package com.tracktosearch.ui.screen.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.SubPageTopBar
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppPullToRefreshIndicator
import com.tracktosearch.data.remote.feedback.MessageItem
import com.tracktosearch.ui.screen.feedback.FeedbackIdLabel
import com.tracktosearch.ui.screen.feedback.FeedbackTimeLabels
import com.tracktosearch.ui.screen.feedback.FeedbackTypeBadge
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import com.tracktosearch.ui.screen.feedback.RoleAvatar
import com.tracktosearch.ui.screen.feedback.ScreenshotCountBadge
import com.tracktosearch.ui.screen.feedback.rememberFeedbackPagingTracker
import com.tracktosearch.ui.screen.feedback.rememberFeedbackTimeLabels
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberAppPullToRefreshState
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onBack: () -> Unit,
    onMessageClick: (feedbackId: String, replyId: String) -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val filter by viewModel.messagesFilter.collectAsStateWithLifecycle()
    val unreadCount by viewModel.unreadCount.collectAsStateWithLifecycle()
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
    val successState = messagesState as? FeedbackViewModel.MessagesState.Success
    val visibleItems = remember(successState?.items) {
        successState?.items?.filter { it.author_role == "developer" }.orEmpty()
    }
    // 空态里的「全部」按钮用；LazyColumn 的 item 不换宿主 View。
    // 「加载更多」已换成共享的 LoadMoreFooter，那一记归它自己发
    val haptics = rememberAppHaptics()
    // 日期分隔：同一天的消息只在第一条上方给一次日期标签
    val rows = remember(visibleItems, timeLabels) {
        var previousLabel: String? = null
        visibleItems.map { item ->
            val label = timeLabels.dayLabel(item.created_at)
            val header = label.takeIf { it != previousLabel }
            previousLabel = label
            header to item
        }
    }
    val pagingTracker = rememberFeedbackPagingTracker()
    val pullToRefreshState = rememberAppPullToRefreshState {
        pagingTracker.reset()
        viewModel.loadMessages(refresh = true)
    }
    LaunchedEffect(successState?.isRefreshing, messagesState) {
        if (successState?.isRefreshing != true) pullToRefreshState.finishRefresh()
    }

    LaunchedEffect(Unit) { viewModel.loadMessages(refresh = true) }
    // 换筛选后内容整批换掉，滚动位置留在原处会停在半空。
    LaunchedEffect(filter) { listState.scrollToItem(0) }

    // 触底自动翻页
    val shouldLoadMore by remember {
        derivedStateOf {
            val success = messagesState as? FeedbackViewModel.MessagesState.Success
                ?: return@derivedStateOf false
            if (!success.hasMore || success.isRefreshing) return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            lastVisible >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            pagingTracker.onRequest(successState?.items?.size ?: 0)
            viewModel.loadMessages(refresh = false)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            AppPullToRefreshIndicator(
                state = pullToRefreshState,
                contentTop = statusBarHeight + 64.dp
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pullToRefreshState.connection)
                    .graphicsLayer { translationY = pullToRefreshState.offset.floatValue }
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    top = 64.dp + statusBarHeight,
                    bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start)
                    ) {
                        MessageFilterChip(
                            label = stringResource(R.string.feedback_filter_all),
                            selected = filter == FeedbackViewModel.MessageFilter.ALL,
                            onClick = {
                                viewModel.setMessagesFilter(FeedbackViewModel.MessageFilter.ALL)
                            }
                        )
                        MessageFilterChip(
                            label = stringResource(R.string.feedback_filter_unread),
                            selected = filter == FeedbackViewModel.MessageFilter.UNREAD,
                            badgeCount = unreadCount,
                            onClick = {
                                viewModel.setMessagesFilter(FeedbackViewModel.MessageFilter.UNREAD)
                            }
                        )
                    }
                }

                when (val state = messagesState) {
                    is FeedbackViewModel.MessagesState.Loading -> {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    is FeedbackViewModel.MessagesState.Error -> {
                        item(key = "error") {
                            AppErrorState(
                                message = state.message,
                                onRetry = { viewModel.loadMessages(refresh = true) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 320.dp)
                                    .padding(16.dp),
                                retryLabel = stringResource(R.string.feedback_retry)
                            )
                        }
                    }

                    is FeedbackViewModel.MessagesState.Success -> {
                        if (rows.isEmpty()) {
                            item(key = "empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(320.dp)
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    EmptyStateCard(
                                        isDark = isAppDarkTheme(),
                                        title = if (filter == FeedbackViewModel.MessageFilter.UNREAD) {
                                            stringResource(R.string.feedback_messages_empty_unread)
                                        } else {
                                            stringResource(R.string.feedback_messages_empty)
                                        },
                                        icon = Icons.Rounded.Inbox,
                                        actions = {
                                            if (filter == FeedbackViewModel.MessageFilter.UNREAD) {
                                                // EmptyStateCard 是纯容器，actions 槽自己发；
                                                // 这一下等于把筛选挪回「全部」，与筛选 chip 同一种状态变化，走刻度感
                                                TextButton(
                                                    onClick = {
                                                        haptics.segmentTick()
                                                        viewModel.setMessagesFilter(
                                                            FeedbackViewModel.MessageFilter.ALL
                                                        )
                                                    }
                                                ) {
                                                    Text(stringResource(R.string.feedback_filter_all))
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(rows, key = { _, row -> row.second.id }) { _, (dayLabel, item) ->
                                Column {
                                    if (dayLabel != null) {
                                        Text(
                                            text = dayLabel,
                                            modifier = Modifier.padding(
                                                start = 20.dp,
                                                top = 14.dp,
                                                bottom = 6.dp
                                            ),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    SwipeableMessageRow(
                                        item = item,
                                        timeLabels = timeLabels,
                                        onClick = { onMessageClick(item.feedback_id, item.id) },
                                        onMarkRead = { viewModel.markAsRead(item.feedback_id) }
                                    )
                                }
                            }
                            if (state.hasMore) {
                                item(key = "load_more") {
                                    LoadMoreFooter(
                                        state = pagingTracker.footerState(
                                            loading = state.isRefreshing,
                                            currentSize = state.items.size
                                        ),
                                        onRetry = {
                                            pagingTracker.onRequest(state.items.size)
                                            viewModel.loadMessages(refresh = false)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MessagesTopBar(
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                isContentUnderTopBar = hasContentUnderTopBar,
                refreshing = successState?.isRefreshing == true,
                hasUnread = unreadCount > 0,
                onBack = onBack,
                onMarkAllRead = viewModel::markAllRead
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MessagesTopBar(
    hazeState: HazeState,
    hazeStyle: HazeBlurStyle,
    isContentUnderTopBar: Boolean,
    refreshing: Boolean,
    hasUnread: Boolean,
    onBack: () -> Unit,
    onMarkAllRead: () -> Unit
) {
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hazeTopBar(
                state = hazeState,
                style = hazeStyle,
                blurRadius = 24.dp,
                isContentUnderTopBar = isContentUnderTopBar
            )
            // 顶栏覆盖列表，拦截空白区域点击，避免穿透到下面的消息条目。
            .clickable(enabled = false, onClick = {})
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        Box {
            SubPageTopBar(
                title = stringResource(R.string.feedback_messages_title),
                onBack = onBack,
                actions = {
                    // 一次把所有未读清掉，有实际后果，按带文字的主操作给 tap。
                    // 全读完了就没有可清的了，禁用比留个点了没反应的按钮清楚
                    TextButton(
                        onClick = {
                            haptics.tap()
                            onMarkAllRead()
                        },
                        enabled = hasUnread
                    ) {
                        Icon(
                            Icons.Rounded.DoneAll,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.feedback_messages_all_read),
                            fontSize = 13.sp
                        )
                    }
                },
            )
            if (refreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }
        }
    }
}

/** 筛选胶囊：换成 M3 FilterChip，选中态语义、勾选图标、触控尺寸都由组件给。 */
@Composable
private fun MessageFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badgeCount: Int = 0
) {
    val haptics = rememberAppHaptics()
    FilterChip(
        selected = selected,
        onClick = {
            // 三个筛选互斥单选，只是把选中位挪一格
            haptics.segmentTick()
            onClick()
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(text = label, fontSize = 13.sp, maxLines = 1)
                if (badgeCount > 0) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = badgeCount.toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else {
            null
        },
        shape = RoundedCornerShape(50)
    )
}

/**
 * 消息条目 + 右滑标已读。
 *
 * 标已读不是删除：滑到阈值就触发回调、条目弹回原位，然后靠 is_unread 变化换成已读样式。
 * 已读条目不挂手势——没有可标的，还会和列表纵向滚动抢事件。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SwipeableMessageRow(
    item: MessageItem,
    timeLabels: FeedbackTimeLabels,
    onClick: () -> Unit,
    onMarkRead: () -> Unit
) {
    if (!item.is_unread) {
        MessageRow(item = item, timeLabels = timeLabels, onClick = onClick)
        return
    }
    val haptics = rememberAppHaptics()
    // confirmValueChange 这个重载已废弃（官方要求改用动态 anchors），但本行只需要「滑过阈值就
    // 通报一次、随后弹回」：按官方写法要重做 anchors、并把震动时机挪到状态落定之后，会改变
    // 侧滑手感。这里保留原行为并显式抑制，等 Material3 给出等价回调再迁移。
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                // 侧滑越过阈值那一刻发，正是「够了、可以松手」的那记
                haptics.thresholdArmed()
                onMarkRead()
            }
            // 一律拒绝状态变更，让 Box 自己弹回去
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { MarkReadSwipeBackground() },
        enableDismissFromEndToStart = false
    ) {
        MessageRow(item = item, timeLabels = timeLabels, onClick = onClick)
    }
}

/** 右滑露出的底层：主色淡底 + 已读图标文案。 */
@Composable
private fun MarkReadSwipeBackground() {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Rounded.MarkEmailRead,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(R.string.feedback_mark_read),
            fontSize = 13.sp,
            color = accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 单条开发者回复。
 *
 * 未读原先靠给已读项整体 `alpha(0.6f)` 来区分，正文对比度直接掉到不合格，
 * 而且淡掉的是「已经读过的内容」而不是「已经处理完」。改成：未读给主色淡底 + 圆点 + 正文用
 * onSurface，已读正文降到 onSurfaceVariant，两者字号字重不变。
 */
@Composable
private fun MessageRow(
    item: MessageItem,
    timeLabels: FeedbackTimeLabels,
    onClick: () -> Unit
) {
    val unread = item.is_unread
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 侧滑时底层会露出来，行背景必须不透明
            .background(
                if (unread) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                        .compositeOver(MaterialTheme.colorScheme.background)
                } else {
                    MaterialTheme.colorScheme.background
                }
            )
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RoleAvatar(isDeveloper = true, size = 38.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FeedbackTypeBadge(type = item.type)
                FeedbackIdLabel(displayId = item.display_id)
                ScreenshotCountBadge(count = item.screenshots.size)
                Spacer(Modifier.weight(1f))
                Text(
                    text = timeLabels.clock(item.created_at),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (unread) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                }
            }
            Text(
                text = item.content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                // 一行截断经常正好切在关键词上，两行足够看出这条回复讲什么
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                color = if (unread) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
