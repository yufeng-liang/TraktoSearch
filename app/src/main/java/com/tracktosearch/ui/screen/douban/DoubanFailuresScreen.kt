package com.tracktosearch.ui.screen.douban

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 豆瓣同步失败项查看页 ViewModel。
 *
 * 从 [DoubanRetryManager] 加载全部失败项,内存中按 status(WISH/COLLECT) × mediaType(movie/show/null) 分组,
 * UI 通过 selectedMode × selectedTab 索引展示对应子集。
 */
@HiltViewModel
class DoubanFailuresViewModel @Inject constructor(
    private val doubanRetryManager: DoubanRetryManager
) : ViewModel() {

    data class DoubanFailuresUiState(
        val isLoading: Boolean = true,
        val failures: List<DoubanSyncFailure> = emptyList(),
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(DoubanFailuresUiState())
    val uiState: StateFlow<DoubanFailuresUiState> = _uiState.asStateFlow()

    /** 从 doubanRetryManager 加载全部失败项 */
    fun loadFailures() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = doubanRetryManager.getAllFailures()
                _uiState.value = DoubanFailuresUiState(
                    isLoading = false,
                    failures = list,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = DoubanFailuresUiState(
                    isLoading = false,
                    failures = emptyList(),
                    error = e.message ?: "load failed"
                )
            }
        }
    }

    /** 更新单条媒体类型标注(movie/show/null) */
    fun setMediaType(doubanId: String, mediaType: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            doubanRetryManager.updateMediaType(doubanId, mediaType)
            // 本地同步更新,避免重新加载整表
            _uiState.value = _uiState.value.copy(
                failures = _uiState.value.failures.map {
                    if (it.doubanId == doubanId) it.copy(mediaType = mediaType) else it
                }
            )
        }
    }

    /** 删除单条失败项 */
    fun deleteFailure(doubanId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            doubanRetryManager.deleteFailure(doubanId)
            _uiState.value = _uiState.value.copy(
                failures = _uiState.value.failures.filterNot { it.doubanId == doubanId }
            )
        }
    }

    /** 清空全部失败项 */
    fun clearAllFailures() {
        viewModelScope.launch(Dispatchers.IO) {
            doubanRetryManager.clearAllFailures()
            _uiState.value = _uiState.value.copy(failures = emptyList())
        }
    }
}

/**
 * 豆瓣同步失败项查看页。
 *
 * 数据流:
 * - selectedMode: 0=想看(WISH), 1=已看(COLLECT)
 * - selectedTab: 0=电影(movie), 1=电视剧(show), 2=未分类(null)
 * - 卡片点击 → onItemClick(doubanId)
 * - 卡片长按 → 弹 AlertDialog 菜单(标注类型/删除)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DoubanFailuresScreen(
    onBack: () -> Unit,
    onItemClick: (doubanId: String) -> Unit,
    viewModel: DoubanFailuresViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current

    // 0=想看(WISH), 1=已看(COLLECT)
    var selectedMode by rememberSaveable { mutableIntStateOf(0) }
    // 0=电影, 1=电视剧, 2=未分类
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // 进入页面时加载
    LaunchedEffect(Unit) {
        viewModel.loadFailures()
    }

    // 按 status × mediaType 过滤当前列表
    val currentStatus = if (selectedMode == 0) DoubanMarkStatus.WISH else DoubanMarkStatus.COLLECT
    val filtered = remember(uiState.failures, selectedMode, selectedTab) {
        uiState.failures.filter { failure ->
            failure.status == currentStatus &&
                when (selectedTab) {
                    0 -> failure.mediaType == "movie"
                    1 -> failure.mediaType == "show"
                    else -> failure.mediaType == null
                }
        }
    }

    // 各分类数量(用于 Tab 徽标)
    val movieCount = remember(uiState.failures, selectedMode) {
        uiState.failures.count { it.status == currentStatus && it.mediaType == "movie" }
    }
    val showCount = remember(uiState.failures, selectedMode) {
        uiState.failures.count { it.status == currentStatus && it.mediaType == "show" }
    }
    val uncategorizedCount = remember(uiState.failures, selectedMode) {
        uiState.failures.count { it.status == currentStatus && it.mediaType == null }
    }

    // 长按菜单目标条目
    var menuFailure by remember { mutableStateOf<DoubanSyncFailure?>(null) }
    // 清空确认对话框
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_douban_failures_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.douban_retry_cancel)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        view.performHaptic(HapticType.CLICK)
                        showClearConfirm = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.screen_douban_failures_clear_all)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 胶囊切换条:想看 / 已看(简化版双段切换,固定宽度)
            ModeCapsuleToggle(
                selectedMode = selectedMode,
                onModeChange = { newMode ->
                    view.performHaptic(HapticType.CLICK)
                    selectedMode = newMode
                }
            )

            // PrimaryTabRow:电影 / 电视剧 / 未分类(带数量徽标)
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        view.performHaptic(HapticType.CLICK)
                        selectedTab = 0
                    },
                    text = {
                        Text("${stringResource(R.string.watchlist_tab_movies)}($movieCount)")
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        view.performHaptic(HapticType.CLICK)
                        selectedTab = 1
                    },
                    text = {
                        Text("${stringResource(R.string.watchlist_tab_shows)}($showCount)")
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = {
                        view.performHaptic(HapticType.CLICK)
                        selectedTab = 2
                    },
                    text = {
                        Text("${stringResource(R.string.screen_douban_failures_tab_uncategorized)}($uncategorizedCount)")
                    }
                )
            }

            // 内容区:空状态 / 失败项网格
            Box(modifier = Modifier.fillMaxSize()) {
                if (!uiState.isLoading && uiState.failures.isEmpty()) {
                    // 全部为空 → 居中提示 + 返回按钮
                    EmptyStateView(
                        text = stringResource(R.string.screen_douban_failures_empty_all),
                        showBackButton = true,
                        onBack = onBack
                    )
                } else if (!uiState.isLoading && filtered.isEmpty()) {
                    // 当前模式为空 → 对应提示
                    val emptyText = if (selectedMode == 0) {
                        stringResource(R.string.screen_douban_failures_empty_wish)
                    } else {
                        stringResource(R.string.screen_douban_failures_empty_collect)
                    }
                    EmptyStateView(text = emptyText, showBackButton = false, onBack = null)
                } else if (!uiState.isLoading && filtered.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            count = filtered.size,
                            key = { filtered[it].doubanId },
                            contentType = { "failure_card" }
                        ) { index ->
                            val failure = filtered[index]
                            FailureCard(
                                failure = failure,
                                onClick = {
                                    view.performHaptic(HapticType.CLICK)
                                    onItemClick(failure.doubanId)
                                },
                                onLongClick = {
                                    view.performHaptic(HapticType.HEAVY_CLICK)
                                    menuFailure = failure
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 长按菜单 AlertDialog
    menuFailure?.let { failure ->
        FailureActionDialog(
            failure = failure,
            onDismiss = { menuFailure = null },
            onMarkMovie = {
                viewModel.setMediaType(failure.doubanId, "movie")
                menuFailure = null
            },
            onMarkShow = {
                viewModel.setMediaType(failure.doubanId, "show")
                menuFailure = null
            },
            onClearMark = {
                viewModel.setMediaType(failure.doubanId, null)
                menuFailure = null
            },
            onDelete = {
                viewModel.deleteFailure(failure.doubanId)
                menuFailure = null
            }
        )
    }

    // 清空确认对话框
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.screen_douban_failures_clear_all)) },
            text = { Text(stringResource(R.string.screen_douban_failures_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearAllFailures()
                }) {
                    Text(stringResource(R.string.douban_retry_clear_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.douban_retry_clear_confirm_no))
                }
            }
        )
    }
}

/**
 * 胶囊切换条:想看(WISH) / 已看(COLLECT)。
 *
 * 简化版双段切换,固定宽度各占一半,带触觉反馈。
 */
@Composable
private fun ModeCapsuleToggle(
    selectedMode: Int,
    onModeChange: (Int) -> Unit
) {
    val wishLabel = stringResource(R.string.watchlist_mode_watchlist)
    val collectLabel = stringResource(R.string.watchlist_mode_watched)
    val isWish = selectedMode == 0

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 选中指示器(占一半宽度,通过 Alignment 切换左右)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .align(if (isWish) Alignment.TopStart else Alignment.TopEnd)
            )
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onModeChange(0) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = wishLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = if (isWish) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onModeChange(1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = collectLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = if (!isWish) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 空状态视图:居中显示提示文案,可选返回按钮。
 */
@Composable
private fun EmptyStateView(
    text: String,
    showBackButton: Boolean,
    onBack: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (showBackButton && onBack != null) {
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.douban_retry_cancel))
                }
            }
        }
    }
}

/**
 * 失败项卡片(简化版 MovieCard)。
 *
 * - 海报:豆瓣 posterUrl 完整 URL,无海报时显示标题首两字符占位
 * - 评分角标:5 分制转★显示(rating=4 → ★★★★☆),null 不显示
 * - 失败原因图标:右下角小角标,可恢复用橙色 Warning,不可恢复用灰色 Block
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailureCard(
    failure: DoubanSyncFailure,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            ) {
                val posterUrl = failure.posterUrl
                if (!posterUrl.isNullOrBlank()) {
                    val imageRequest = remember(posterUrl) {
                        ImageRequest.Builder(context)
                            .data(posterUrl)
                            .size(200)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = failure.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // 无海报时显示标题首两字符占位
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = failure.title.take(2),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // 评分角标(左上角):5 分制转★显示
                failure.rating?.let { rating ->
                    val stars = buildString {
                        repeat(5) { i ->
                            append(if (i < rating) "★" else "☆")
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC000000)
                    ) {
                        Text(
                            text = stars,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFFFFC107),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // 失败原因图标(右下角小角标)
                val reason = failure.failureReason
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (reason.recoverable) Color(0xCCFF9800)
                            else Color(0xCC757575)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (reason.recoverable) Icons.Default.Warning else Icons.Default.Block,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            // 标题区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 4.dp)
            ) {
                Text(
                    text = failure.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!failure.subtitle.isNullOrBlank()) {
                    Text(
                        text = failure.subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 长按菜单:标注类型 / 删除条目。
 */
@Composable
private fun FailureActionDialog(
    failure: DoubanSyncFailure,
    onDismiss: () -> Unit,
    onMarkMovie: () -> Unit,
    onMarkShow: () -> Unit,
    onClearMark: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = failure.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                ActionItem(stringResource(R.string.screen_douban_failures_mark_as_movie)) {
                    onMarkMovie()
                }
                ActionItem(stringResource(R.string.screen_douban_failures_mark_as_show)) {
                    onMarkShow()
                }
                ActionItem(stringResource(R.string.screen_douban_failures_clear_mark)) {
                    onClearMark()
                }
                ActionItem(
                    text = stringResource(R.string.screen_douban_failures_delete_item),
                    color = MaterialTheme.colorScheme.error,
                    onClick = onDelete
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.douban_retry_cancel))
            }
        }
    )
}

/** 长按菜单中的单项操作 */
@Composable
private fun ActionItem(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
    }
}
