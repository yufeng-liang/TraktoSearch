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
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.data.remote.feedback.MessageItem
import com.tracktosearch.ui.screen.feedback.FeedbackViewModel
import com.tracktosearch.ui.component.EmptyStateCard
import com.tracktosearch.ui.component.hasListScrolled
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
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
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
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
    // 空态里的「全部」与底部「加载更多」两处 Material 按钮共用；LazyColumn 的 item 不换宿主 View
    val haptics = rememberAppHaptics()

    LaunchedEffect(Unit) { viewModel.loadMessages(refresh = true) }
    // 换筛选后内容整批换掉，滚动位置留在原处会停在半空。
    LaunchedEffect(filter) { listState.scrollToItem(0) }

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
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
                contentPadding = PaddingValues(
                    top = 64.dp + statusBarHeight,
                    bottom = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start)
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
                        if (visibleItems.isEmpty()) {
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
                            items(visibleItems, key = { it.id }) { item ->
                                MessageItemRow(
                                    item = item,
                                    onClick = { onMessageClick(item.feedback_id, item.id) },
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                            if (state.hasMore) {
                                item(key = "load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        TextButton(
                                            onClick = {
                                                haptics.lightTap()
                                                viewModel.loadMessages(refresh = false)
                                            }
                                        ) {
                                            Text(stringResource(R.string.feedback_load_more))
                                        }
                                    }
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
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.feedback_messages_title),
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back)
                        )
                    }
                },
                actions = {
                    // 一次把所有未读清掉，有实际后果，按带文字的主操作给 tap
                    TextButton(
                        onClick = {
                            haptics.tap()
                            onMarkAllRead()
                        }
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                windowInsets = WindowInsets(0, 0, 0, 0)
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
private fun MessageFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val isSelected = selected
    Surface(
        modifier = Modifier
            // 无障碍：Surface 本身没有语义，读屏只念文字，念不出这是可点的筛选项、有没有选中
            .semantics {
                this.role = Role.Button
                this.selected = isSelected
            }
            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                onClick()
            },
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 0.dp else 1.dp,
        shadowElevation = if (selected) 0.dp else 1.dp
    ) {
        Text(text = label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun MessageItemRow(
    item: MessageItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDeveloper = item.author_role == "developer"
    val avatarColor = if (isDeveloper) Color(0xFF34D399) else MaterialTheme.colorScheme.primary
    val avatarLabel = if (isDeveloper) "D" else "我"
    val typeColor = when (item.type) { "FEATURE" -> Color(0xFF34D399); "BUG" -> Color(0xFFFB7185); "UX" -> Color(0xFFFBBF24); else -> Color(0xFF9CA3AF) }
    val hasScreenshot = item.screenshots.isNotEmpty()

    Row(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (item.is_unread) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent).hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onClick() }.padding(horizontal = 8.dp, vertical = 10.dp).alpha(if (item.is_unread) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
