package com.tracktosearch.ui.screen.feedback

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.SubPageTopBar
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.ui.component.AppPullToRefreshIndicator
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.FeedbackListCardCorner
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.ShimmerState
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberAppPullToRefreshState
import com.tracktosearch.ui.component.rememberShimmer
import com.tracktosearch.ui.component.shimmer
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.FeedbackReplied
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    onBack: () -> Unit,
    onNewFeedback: () -> Unit,
    onFeedbackClick: (String) -> Unit,
    onCrashLogClick: (String) -> Unit,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val haptics = rememberAppHaptics()
    val listState by viewModel.listState.collectAsStateWithLifecycle()
    val crashLogRecords by viewModel.crashLogRecords.collectAsStateWithLifecycle()
    // 未读回复由主界面进场时拉过一次，这里只读不再请求（VM 挂在 MAIN 返回栈上，是同一实例）
    val unreadItems by viewModel.unreadItems.collectAsStateWithLifecycle()
    val unreadFeedbackIds = remember(unreadItems) { unreadItems.map { it.feedback_id }.toSet() }
    val feedbackListState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val timeLabels = rememberFeedbackTimeLabels()
    val hasContentUnderTopBar by remember {
        derivedStateOf {
            hasListScrolled(
                firstVisibleItemIndex = feedbackListState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = feedbackListState.firstVisibleItemScrollOffset
            )
        }
    }
    val successState = listState as? FeedbackViewModel.ListState.Success
    val pagingTracker = rememberFeedbackPagingTracker()
    val pullToRefreshState = rememberAppPullToRefreshState {
        pagingTracker.reset()
        viewModel.loadList(refresh = true)
    }
    // 刷新态落地后收起指示器，避免刷新动画一闪而过
    LaunchedEffect(successState?.isRefreshing, listState) {
        if (successState?.isRefreshing != true) pullToRefreshState.finishRefresh()
    }

    // 首次进入加载
    LaunchedEffect(Unit) { viewModel.loadList(refresh = true) }

    // 触底自动翻页：列表尾部进入可视区就取下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val success = listState as? FeedbackViewModel.ListState.Success ?: return@derivedStateOf false
            if (!success.hasMore || success.isRefreshing) return@derivedStateOf false
            val lastVisible = feedbackListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            lastVisible >= feedbackListState.layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            pagingTracker.onRequest(successState?.items?.size ?: 0)
            viewModel.loadList(refresh = false)
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
                state = feedbackListState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(pullToRefreshState.connection)
                    .graphicsLayer { translationY = pullToRefreshState.offset.floatValue }
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 64.dp + statusBarHeight,
                    end = 16.dp,
                    // FAB 高度 + 导航栏，避免最后一张卡被悬浮按钮压住
                    bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "intro") {
                    Text(
                        text = stringResource(R.string.feedback_intro),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                // 崩溃日志上传记录区块（本地数据，无记录隐藏）
                if (crashLogRecords.isNotEmpty()) {
                    item(key = "crash_title") {
                        FeedbackSectionLabel(
                            text = stringResource(R.string.crash_record_section_title),
                            count = crashLogRecords.size
                        )
                    }
                    items(crashLogRecords, key = { it.id }) { record ->
                        CrashLogRecordCard(
                            record = record,
                            timeLabels = timeLabels,
                            onClick = { onCrashLogClick(record.id) }
                        )
                    }
                }

                item(key = "mine_title") {
                    FeedbackSectionLabel(
                        text = stringResource(R.string.feedback_my_feedbacks),
                        count = successState?.items?.size ?: 0
                    )
                }

                when (val state = listState) {
                    is FeedbackViewModel.ListState.Loading -> {
                        item(key = "loading") { FeedbackListSkeleton() }
                    }
                    is FeedbackViewModel.ListState.Error -> {
                        item(key = "error") {
                            AppErrorState(
                                message = state.message,
                                onRetry = { viewModel.loadList(refresh = true) },
                                modifier = Modifier.padding(32.dp),
                                retryLabel = stringResource(R.string.feedback_retry)
                            )
                        }
                    }
                    is FeedbackViewModel.ListState.Success -> {
                        if (state.items.isEmpty()) {
                            item(key = "empty") {
                                EmptyStateCard(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    isDark = isAppDarkTheme(),
                                    icon = Icons.Rounded.Inbox,
                                    title = stringResource(R.string.feedback_empty),
                                    actions = {
                                        Spacer(Modifier.height(12.dp))
                                        Button(onClick = { haptics.tap(); onNewFeedback() }) {
                                            Text(stringResource(R.string.feedback_new))
                                        }
                                    }
                                )
                            }
                        } else {
                            items(state.items, key = { it.id }) { item ->
                                FeedbackCard(
                                    item = item,
                                    hasUnreadReply = item.id in unreadFeedbackIds,
                                    timeLabels = timeLabels,
                                    onClick = { onFeedbackClick(item.id) }
                                )
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
                                            viewModel.loadList(refresh = false)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            FeedbackTopBar(
                hazeState = hazeState,
                hazeStyle = hazeStyle,
                isContentUnderTopBar = hasContentUnderTopBar,
                refreshing = successState?.isRefreshing == true,
                onBack = onBack
            )

            // 写新反馈入口：原先是列表顶部一张大卡，反馈多了就滚出屏幕，
            // 挪成右下悬浮按钮后任何滚动位置都能点到。
            FloatingActionButton(
                onClick = {
                    haptics.tap()
                    onNewFeedback()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.feedback_new))
            }
        }
    }
}

/** 分组小标题：13sp 主色 + 条目数，与设置/隐私页分组标题同款。 */
@Composable
private fun FeedbackSectionLabel(text: String, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp
        )
        if (count > 0) {
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FeedbackTopBar(
    hazeState: HazeState,
    hazeStyle: HazeBlurStyle,
    isContentUnderTopBar: Boolean,
    refreshing: Boolean,
    onBack: () -> Unit
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
            .clickable(enabled = false, onClick = {})
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        Box {
            SubPageTopBar(
                title = stringResource(R.string.feedback_title),
                onBack = onBack,
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

@Composable
private fun FeedbackCard(
    item: com.tracktosearch.data.remote.feedback.FeedbackListItem,
    hasUnreadReply: Boolean,
    timeLabels: FeedbackTimeLabels,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberAppHaptics()
    val screenshotCount = remember(item.screenshots) { parseScreenshots(item.screenshots).size }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 调用方的修饰符挂在卡片本体上，列表项间距仍由 LazyColumn 负责。
            .then(modifier)
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() },
        shape = RoundedCornerShape(FeedbackListCardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FeedbackTypeBadge(item.type)
                FeedbackIdLabel(item.display_id)
                Spacer(Modifier.weight(1f))
                FeedbackStatusPill(item.status)
            }
            Text(
                text = item.content,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = timeLabels.relative(item.created_at),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ScreenshotCountBadge(screenshotCount)
                Spacer(Modifier.weight(1f))
                // 有新回复：列表页直接给出「值得点进去」的信号
                if (hasUnreadReply) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                            Text(
                                text = stringResource(R.string.feedback_unread_reply),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 加载骨架：三张与真实卡片同高的占位卡，比一个居中转圈少一次布局跳动。 */
@Composable
private fun FeedbackListSkeleton() {
    val shimmer = rememberShimmer()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) { FeedbackCardSkeleton(shimmer) }
    }
}

@Composable
private fun FeedbackCardSkeleton(shimmer: ShimmerState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(84.dp).height(22.dp).shimmer(shimmer, RoundedCornerShape(50)))
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(64.dp).height(22.dp).shimmer(shimmer, RoundedCornerShape(50)))
            }
            Box(Modifier.fillMaxWidth().height(14.dp).shimmer(shimmer, RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(0.6f).height(14.dp).shimmer(shimmer, RoundedCornerShape(4.dp)))
            Box(Modifier.width(72.dp).height(12.dp).shimmer(shimmer, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun CrashLogRecordCard(
    record: com.tracktosearch.data.local.CrashLogRecord,
    timeLabels: FeedbackTimeLabels,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = rememberAppHaptics()
    val (statusRes, statusColor) = when (record.status) {
        com.tracktosearch.data.local.CrashLogRecord.Status.PENDING ->
            R.string.crash_record_status_pending to MaterialTheme.colorScheme.onSurfaceVariant
        com.tracktosearch.data.local.CrashLogRecord.Status.UPLOADING ->
            R.string.crash_record_status_uploading to MaterialTheme.colorScheme.primary
        com.tracktosearch.data.local.CrashLogRecord.Status.SUCCESS ->
            R.string.crash_record_status_success to feedbackAccent(FeedbackReplied)
        com.tracktosearch.data.local.CrashLogRecord.Status.FAILED ->
            R.string.crash_record_status_failed to MaterialTheme.colorScheme.error
    }
    val statusIcon = when (record.status) {
        com.tracktosearch.data.local.CrashLogRecord.Status.SUCCESS -> Icons.Rounded.CheckCircle
        com.tracktosearch.data.local.CrashLogRecord.Status.FAILED -> Icons.Rounded.Error
        com.tracktosearch.data.local.CrashLogRecord.Status.UPLOADING -> Icons.Rounded.HourglassTop
        com.tracktosearch.data.local.CrashLogRecord.Status.PENDING -> Icons.Rounded.Schedule
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 与 FeedbackCard 同理：修饰符挂在卡片本体上，量到的才是用户看到的那块圆角面。
            .then(modifier)
            .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() },
        shape = RoundedCornerShape(FeedbackListCardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(statusColor.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(statusRes),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = timeLabels.relative(record.crashTime / 1000),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (record.status == com.tracktosearch.data.local.CrashLogRecord.Status.FAILED &&
                record.error.isNotBlank()
            ) {
                Text(
                    text = record.error,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(0.7f)
                )
            }
        }
    }
}
