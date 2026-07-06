package com.tracktosearch.ui.screen.douban

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.screen.detail.PosterFullscreenOverlay
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.showToast
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// ==================== ViewModel ====================

/**
 * 豆瓣条目详情页 UI 状态
 */
data class DoubanItemDetailUiState(
    val isLoading: Boolean = true,
    val failure: DoubanSyncFailure? = null,
    val error: String? = null,
    // 资源搜索
    val searchResults: List<ResourceItem> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val searchWithSubtitle: Boolean = true,
    val searchAttempted: Boolean = false,
    // 筛选器
    val availableSources: List<String> = emptyList(),
    val enabledSources: Set<String> = ResourceRepository.ALL_SOURCES,
    val customSourceNames: Map<String, String> = emptyMap(),
    val enabledDiskTypes: Set<DiskType> = ResourceRepository.ALL_DISK_TYPES,
    // 弹窗
    val showSubtitleDialog: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    // 重试
    val retryStarted: Boolean = false,
    val retryStartFailed: Boolean = false,
    // 海报主色调(沉浸式渐变背景用,null 表示尚未提取)
    val posterDominantColor: Color? = null
)

/**
 * 豆瓣条目详情页 ViewModel。
 *
 * 职责:
 * - 加载单条失败项数据([DoubanRetryManager.getFailure])
 * - 资源搜索:主标题 + 子标题并行搜索(参照 [com.tracktosearch.ui.screen.detail.DetailViewModel])
 * - 子标题编辑 → 持久化 → 自动刷新搜索
 * - 单条重试 → 启动 [DoubanSyncManager] 重试流程
 * - 删除失败记录 → 触发 onBack
 */
@HiltViewModel
class DoubanItemDetailViewModel @Inject constructor(
    private val doubanRetryManager: DoubanRetryManager,
    private val doubanSyncManager: DoubanSyncManager,
    private val resourceRepository: ResourceRepository,
    val posterColorExtractor: PosterColorExtractor
) : ViewModel() {

    private val _uiState = MutableStateFlow(DoubanItemDetailUiState())
    val uiState: StateFlow<DoubanItemDetailUiState> = _uiState.asStateFlow()

    private var currentDoubanId: String = ""
    private var searchJob: Job? = null
    /** 全量资源结果(未按筛选器过滤),用于本地切换筛选器时即时过滤 */
    private var allResources: List<ResourceItem> = emptyList()

    /** 加载指定豆瓣条目的失败项数据 */
    fun loadFailure(doubanId: String) {
        if (currentDoubanId == doubanId && _uiState.value.failure != null) return
        currentDoubanId = doubanId
        _uiState.value = DoubanItemDetailUiState(isLoading = true)
        viewModelScope.launch {
            try {
                val failure = doubanRetryManager.getFailure(doubanId)
                if (failure == null) {
                    // 条目已被删除或不存在,UI 层检测 failure==null 后自动 onBack
                    _uiState.value = DoubanItemDetailUiState(isLoading = false, failure = null)
                    return@launch
                }
                _uiState.value = DoubanItemDetailUiState(
                    isLoading = false,
                    failure = failure,
                    // 默认开启「同时用子标题搜索」(仅当 subtitle 非空时才生效)
                    searchWithSubtitle = !failure.subtitle.isNullOrBlank()
                )
                // 尽早从缓存预查海报主色,让沉浸背景在海报图片加载前显示
                prefetchPosterColor(failure.posterUrl)
                // 加载完成后自动触发资源搜索
                searchResources()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "unknown error"
                )
            }
        }
    }

    /** 更新子标题,持久化后刷新本地状态并自动重新搜索 */
    fun updateSubtitle(doubanId: String, subtitle: String?) {
        viewModelScope.launch {
            doubanRetryManager.updateSubtitle(doubanId, subtitle)
            val current = _uiState.value.failure ?: return@launch
            // updateSubtitle 内部会 trim 空串转 null,这里同步处理
            val normalized = subtitle?.trim()?.takeIf { it.isNotBlank() }
            _uiState.value = _uiState.value.copy(
                failure = current.copy(subtitle = normalized),
                searchWithSubtitle = !normalized.isNullOrBlank()
            )
            searchResources()
        }
    }

    /**
     * 资源搜索:主标题 + 子标题(可选)并行搜索,结果按 URL 去重合并。
     * 参照 [com.tracktosearch.ui.screen.detail.DetailViewModel.updateSearchResults]。
     *
     * @param forceRefresh true 时强制清缓存重拉(用于手动刷新按钮);
     * 默认 false 先查缓存命中即用,5 分钟内复用避免二次进入转圈。
     */
    fun searchResources(forceRefresh: Boolean = false) {
        val failure = _uiState.value.failure ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // 从设置页读取启用的搜索源(含自定义源)
            val storageEnabledSources = resourceRepository.getEnabledSources()
            val customSources = resourceRepository.getEnabledCustomSources()
            val customNames = customSources.associate { it.id to it.name }

            _uiState.value = _uiState.value.copy(
                isSearching = true,
                searchError = null,
                searchAttempted = false,
                availableSources = storageEnabledSources.toList(),
                enabledSources = storageEnabledSources,
                customSourceNames = customNames
            )

            // 构造关键词:主标题 + 子标题(可选)
            val keywords = mutableListOf(failure.title)
            val useSubtitle = !failure.subtitle.isNullOrBlank() && _uiState.value.searchWithSubtitle
            if (useSubtitle && failure.subtitle != null) {
                keywords.add(failure.subtitle)
            }

            // 电视剧类型按多季优先排序
            val isShow = failure.mediaType == "show"

            try {
                // 多关键词并行搜索
                val results = coroutineScope {
                    val deferreds = keywords.map { kw ->
                        async {
                            // forceRefresh=true 清缓存重拉;false 先查缓存命中即用
                            val result = if (forceRefresh) {
                                resourceRepository.refreshResources(
                                    keyword = kw,
                                    enabledSources = storageEnabledSources,
                                    enabledDiskTypes = _uiState.value.enabledDiskTypes,
                                    isShow = isShow
                                )
                            } else {
                                resourceRepository.searchResources(
                                    keyword = kw,
                                    enabledSources = storageEnabledSources,
                                    enabledDiskTypes = _uiState.value.enabledDiskTypes,
                                    isShow = isShow
                                )
                            }
                            result.getOrDefault(emptyList())
                        }
                    }
                    deferreds.awaitAll()
                }

                // 按 URL 去重合并(保留先出现的)
                val seenUrls = mutableSetOf<String>()
                val merged = mutableListOf<ResourceItem>()
                for (items in results) {
                    for (item in items) {
                        if (item.url !in seenUrls) {
                            seenUrls.add(item.url)
                            merged.add(item)
                        }
                    }
                }

                // 写入缓存并按筛选器过滤
                allResources = if (merged.isNotEmpty()) {
                    resourceRepository.mergeAndCacheResources(
                        keyword = failure.title,
                        items = merged,
                        isShow = isShow
                    )
                } else {
                    resourceRepository.getCachedAllResources(failure.title)
                }

                val filtered = resourceRepository.filterItems(
                    allResources,
                    _uiState.value.enabledSources,
                    _uiState.value.enabledDiskTypes
                )

                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    searchResults = filtered
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    searchError = e.message
                )
            }
        }
    }

    /** 手动刷新入口(供 TopAppBar 刷新按钮调用):强制清缓存重拉 */
    fun refreshResources() {
        searchResources(forceRefresh = true)
    }

    /** 切换「同时用子标题搜索」开关,切换后自动重新搜索 */
    fun toggleSearchWithSubtitle() {
        val newValue = !_uiState.value.searchWithSubtitle
        _uiState.value = _uiState.value.copy(searchWithSubtitle = newValue)
        searchResources()
    }

    /** 切换搜索源筛选(至少保留 1 个) */
    fun toggleSource(source: String) {
        val current = _uiState.value.enabledSources
        val newSources = if (source in current) {
            if (current.size == 1) current else current - source
        } else {
            current + source
        }
        if (newSources == current) return
        val filtered = resourceRepository.filterItems(
            allResources, newSources, _uiState.value.enabledDiskTypes
        )
        _uiState.value = _uiState.value.copy(
            enabledSources = newSources,
            searchResults = filtered
        )
    }

    /** 切换网盘类型筛选(至少保留 1 个) */
    fun toggleDiskType(type: DiskType) {
        val current = _uiState.value.enabledDiskTypes
        val newTypes = if (type in current) {
            if (current.size == 1) current else current - type
        } else {
            current + type
        }
        if (newTypes == current) return
        val filtered = resourceRepository.filterItems(
            allResources, _uiState.value.enabledSources, newTypes
        )
        _uiState.value = _uiState.value.copy(
            enabledDiskTypes = newTypes,
            searchResults = filtered
        )
    }

    fun showSubtitleDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSubtitleDialog = show)
    }

    fun showDeleteConfirm(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDeleteConfirm = show)
    }

    /**
     * 启动单条重试。
     * - true:已启动,UI 跳转到 DoubanSyncDialog
     * - false:同步正在运行(防重入),UI Toast 提示
     */
    fun retrySingle() {
        val failure = _uiState.value.failure ?: return
        val started = doubanSyncManager.startRetry(
            failures = listOf(failure),
            selectedReasons = setOf(failure.failureReason)
        )
        if (started) {
            _uiState.value = _uiState.value.copy(retryStarted = true)
        } else {
            _uiState.value = _uiState.value.copy(retryStartFailed = true)
        }
    }

    /** 消费重试启动失败标记(Toast 显示后调用) */
    fun consumeRetryStartFailed() {
        _uiState.value = _uiState.value.copy(retryStartFailed = false)
    }

    /** 删除当前失败项记录,删除后 failure 置 null 触发 UI 自动 onBack */
    fun deleteFailure() {
        val failure = _uiState.value.failure ?: return
        viewModelScope.launch {
            doubanRetryManager.deleteFailure(failure.doubanId)
            _uiState.value = _uiState.value.copy(failure = null, showDeleteConfirm = false)
        }
    }

    /** 手动标注当前条目的媒体类型(movie/show/null),用于资源搜索时按类型筛选 */
    fun setMediaType(mediaType: String?) {
        val failure = _uiState.value.failure ?: return
        viewModelScope.launch {
            doubanRetryManager.updateMediaType(failure.doubanId, mediaType)
            _uiState.value = _uiState.value.copy(
                failure = failure.copy(mediaType = mediaType)
            )
        }
    }

    /** 海报主色调提取回调:更新沉浸式渐变背景色 */
    fun updatePosterColor(color: Color) {
        _uiState.value = _uiState.value.copy(posterDominantColor = color)
    }

    /**
     * 进入详情页时尽早从缓存预查海报主色(不需要 bitmap)。
     * 命中则瞬间设置 posterDominantColor,让沉浸背景在海报图片加载前显示。
     * 未命中仍由 DoubanItemHeader 的 Coil listener 走 [updatePosterColor] 流程。
     */
    private fun prefetchPosterColor(posterUrl: String?) {
        if (posterUrl.isNullOrEmpty()) return
        if (_uiState.value.posterDominantColor != null) return
        viewModelScope.launch {
            posterColorExtractor.getCachedColor(posterUrl)?.let { argb ->
                if (argb != 0L) {
                    _uiState.value = _uiState.value.copy(posterDominantColor = Color(argb))
                }
            }
        }
    }
}

// ==================== Composable ====================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class)
@Composable
fun DoubanItemDetailScreen(
    doubanId: String,
    onBack: () -> Unit,
    onRetryStarted: () -> Unit,
    viewModel: DoubanItemDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showPosterFullscreen by remember { mutableStateOf(false) }
    var subtitleInput by remember { mutableStateOf("") }
    // 手动标记媒体类型下拉菜单展开状态
    var showMarkMenu by remember { mutableStateOf(false) }

    // 内容就绪状态:沉浸背景优先显示,其他内容(tab/搜索/资源)淡入
    // posterDominantColor 就绪 → 80ms 后标记就绪;未就绪 → 400ms 兜底
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.posterDominantColor) {
        if (uiState.posterDominantColor != null) {
            kotlinx.coroutines.delay(80)
            contentReady = true
        } else {
            kotlinx.coroutines.delay(400)
            contentReady = true
        }
    }
    val contentAlpha = if (contentReady) 1f else 0f

    // 拦截系统返回手势
    BackHandler(enabled = true) {
        onBack()
    }

    // doubanId 变化时重新加载
    LaunchedEffect(doubanId) {
        viewModel.loadFailure(doubanId)
    }

    // 加载完成后若 failure == null(条目已被删除/不存在),自动返回
    LaunchedEffect(uiState.failure, uiState.isLoading) {
        if (!uiState.isLoading && uiState.failure == null && uiState.error == null) {
            onBack()
        }
    }

    // 重试启动后跳转到 DoubanSyncDialog
    LaunchedEffect(uiState.retryStarted) {
        if (uiState.retryStarted) {
            onRetryStarted()
        }
    }

    // 重试启动失败 Toast 提示
    LaunchedEffect(uiState.retryStartFailed) {
        if (uiState.retryStartFailed) {
            context.showToast(context.getString(R.string.screen_douban_item_detail_retry_in_progress))
            viewModel.consumeRetryStartFailed()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 海报主色调垂直渐变背景(顶部主色 45% 透明度 → 底部主题背景色)
                .then(
                    uiState.posterDominantColor?.let { c ->
                        Modifier.background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    c.copy(alpha = 0.70f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                    } ?: Modifier
                )
        ) {
            val failure = uiState.failure

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (failure != null) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = hazeState),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    // 头部区域:海报 + 标题 + 子标题 + 评分 + 标记信息 + 短评
                    item(key = "header") {
                        DoubanItemHeader(
                            failure = failure,
                            posterColor = uiState.posterDominantColor,
                            onPosterClick = { showPosterFullscreen = true },
                            onSubtitleClick = {
                                subtitleInput = failure.subtitle ?: ""
                                viewModel.showSubtitleDialog(true)
                            },
                            posterColorExtractor = viewModel.posterColorExtractor,
                            onPosterColorExtracted = viewModel::updatePosterColor
                        )
                    }

                    // Tab 行(吸顶) — 容器色透明,让海报主色调透出
                    stickyHeader(key = "tab_row") {
                        PrimaryTabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color.Transparent,
                            modifier = Modifier.alpha(contentAlpha)
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = {
                                    view.performHaptic(HapticType.CLICK)
                                    selectedTab = 0
                                },
                                text = {
                                    Text(
                                        stringResource(R.string.screen_douban_item_detail_tab_resources),
                                        maxLines = 1
                                    )
                                }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = {
                                    view.performHaptic(HapticType.CLICK)
                                    selectedTab = 1
                                },
                                text = {
                                    Text(
                                        stringResource(R.string.screen_douban_item_detail_tab_info),
                                        maxLines = 1
                                    )
                                }
                            )
                        }
                    }

                    // Tab 内容
                    when (selectedTab) {
                        0 -> {
                            // 资源搜索 Tab
                            item(key = "search_keyword_bar") {
                                Box(modifier = Modifier.alpha(contentAlpha)) {
                                    DoubanSearchKeywordBar(
                                        failure = failure,
                                        searchWithSubtitle = uiState.searchWithSubtitle,
                                        onToggleSearchWithSubtitle = { viewModel.toggleSearchWithSubtitle() }
                                    )
                                }
                            }
                            item(key = "filter_section") {
                                Box(modifier = Modifier.alpha(contentAlpha)) {
                                    DoubanFilterSection(
                                        availableSources = uiState.availableSources,
                                        enabledSources = uiState.enabledSources,
                                        customSourceNames = uiState.customSourceNames,
                                        enabledDiskTypes = uiState.enabledDiskTypes,
                                        onToggleSource = { viewModel.toggleSource(it) },
                                        onToggleDiskType = { viewModel.toggleDiskType(it) }
                                    )
                                }
                            }
                            when {
                                uiState.isSearching -> {
                                    item(key = "searching") {
                                        Box(modifier = Modifier.alpha(contentAlpha)) { DoubanSearchingState() }
                                    }
                                }
                                uiState.searchResults.isEmpty() && uiState.searchAttempted -> {
                                    item(key = "empty") {
                                        Box(modifier = Modifier.alpha(contentAlpha)) { DoubanEmptyState(onRetry = { viewModel.searchResources() }) }
                                    }
                                }
                                else -> {
                                    item(key = "resource_count") {
                                        Text(
                                            text = stringResource(
                                                R.string.detail_found_resources,
                                                uiState.searchResults.size
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                start = 16.dp,
                                                end = 16.dp,
                                                bottom = 4.dp
                                            )
                                        )
                                    }
                                    itemsIndexed(
                                        items = uiState.searchResults,
                                        key = { _, item -> item.url },
                                        contentType = { _, _ -> "resource" }
                                    ) { index, item ->
                                        ResourceItemCard(
                                            item = item,
                                            isViewed = false,
                                            onClick = {
                                                view.performHaptic(HapticType.CLICK)
                                                openResourceLink(context, item)
                                            },
                                            onLongClick = {
                                                view.performHaptic(HapticType.HEAVY_CLICK)
                                                copyResourceLink(context, item)
                                            },
                                            index = index
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            // 详情信息 Tab
                            item(key = "info_actions") {
                                Box(modifier = Modifier.alpha(contentAlpha)) {
                                    DoubanDetailInfoTab(
                                        failure = failure,
                                        onOpenDouban = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(failure.doubanUrl))
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: ActivityNotFoundException) {
                                                context.showToast(
                                                    context.getString(R.string.screen_douban_item_detail_open_douban)
                                                )
                                            }
                                        },
                                        onRetry = { viewModel.retrySingle() },
                                        onDelete = { viewModel.showDeleteConfirm(true) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (uiState.error != null) {
                // 加载错误
                val errorMessage = uiState.error ?: ""
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { onBack() }) {
                            Text(stringResource(R.string.douban_retry_cancel))
                        }
                    }
                }
            }

            // 海报大图查看(复用 DetailPosterOverlay,支持保存到相册)
            val posterUrl = failure?.posterUrl
            if (showPosterFullscreen && posterUrl != null) {
                PosterFullscreenOverlay(
                    posterUrl = posterUrl,
                    title = failure.title,
                    onDismiss = { showPosterFullscreen = false }
                )
            }

            // Haze 模糊覆盖层:状态栏 + 顶部悬浮按钮区域(参考正常详情页)
            // alpha 0.15:降低白色强度,让海报主色调更沉浸(+15% 增强)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin()
                    )
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f))
            ) {
                Column {
                    // 状态栏 Spacer
                    Spacer(
                        modifier = Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                    )
                    // 顶部悬浮按钮行:左侧返回 + 右侧标记 + 分享(参考正常详情页圆形按钮)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 返回按钮
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .hazeEffect(
                                    state = hazeState,
                                    style = HazeStyle(
                                        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                                        blurRadius = 20.dp,
                                        noiseFactor = 0f,
                                        tint = null
                                    )
                                )
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.30f),
                                    shape = CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    shape = CircleShape
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        view.performHaptic(HapticType.TICK)
                                        onBack()
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.detail_back),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        // 手动标记媒体类型按钮(下拉菜单)
                        val failure = uiState.failure
                        Box {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .hazeEffect(
                                        state = hazeState,
                                        style = HazeStyle(
                                            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                                            blurRadius = 20.dp,
                                            noiseFactor = 0f,
                                            tint = null
                                        )
                                    )
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.30f),
                                        shape = CircleShape
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        shape = CircleShape
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            view.performHaptic(HapticType.CLICK)
                                            showMarkMenu = true
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Label,
                                    contentDescription = stringResource(R.string.screen_douban_failures_mark_as_movie),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMarkMenu,
                                onDismissRequest = { showMarkMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.screen_douban_failures_mark_as_movie)) },
                                    leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                                    onClick = {
                                        viewModel.setMediaType("movie")
                                        showMarkMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.screen_douban_failures_mark_as_show)) },
                                    leadingIcon = { Icon(Icons.Default.Tv, contentDescription = null) },
                                    onClick = {
                                        viewModel.setMediaType("show")
                                        showMarkMenu = false
                                    }
                                )
                                if (failure?.mediaType != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.screen_douban_failures_clear_mark)) },
                                        onClick = {
                                            viewModel.setMediaType(null)
                                            showMarkMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // 分享按钮(分享影视标题、评分、豆瓣链接)
                        val shareContext = LocalContext.current
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .hazeEffect(
                                    state = hazeState,
                                    style = HazeStyle(
                                        backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
                                        blurRadius = 20.dp,
                                        noiseFactor = 0f,
                                        tint = null
                                    )
                                )
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.30f),
                                    shape = CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    shape = CircleShape
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        view.performHaptic(HapticType.TICK)
                                        val f = uiState.failure
                                        if (f != null) {
                                            val shareText = buildString {
                                                append(f.title)
                                                f.rating?.let { append(" - ${shareContext.getString(R.string.share_douban_rating)}: $it") }
                                                if (f.doubanUrl.isNotBlank()) {
                                                    append("\n")
                                                    append(f.doubanUrl)
                                                }
                                            }
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            }
                                            shareContext.startActivity(
                                                Intent.createChooser(sendIntent, null)
                                            )
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = stringResource(R.string.detail_share),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 子标题编辑弹窗
    if (uiState.showSubtitleDialog) {
        val failure = uiState.failure
        if (failure != null) {
            AlertDialog(
                onDismissRequest = { viewModel.showSubtitleDialog(false) },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                title = { Text(stringResource(R.string.screen_douban_item_detail_subtitle_edit)) },
                text = {
                    OutlinedTextField(
                        value = subtitleInput,
                        onValueChange = { subtitleInput = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.updateSubtitle(failure.doubanId, subtitleInput)
                        viewModel.showSubtitleDialog(false)
                    }) {
                        Text(stringResource(R.string.douban_sync_mode_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showSubtitleDialog(false) }) {
                        Text(stringResource(R.string.douban_retry_cancel))
                    }
                }
            )
        }
    }

    // 删除确认弹窗
    if (uiState.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.showDeleteConfirm(false) },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.screen_douban_item_detail_delete)) },
            text = { Text(stringResource(R.string.screen_douban_item_detail_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteFailure() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.douban_sync_mode_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDeleteConfirm(false) }) {
                    Text(stringResource(R.string.douban_retry_cancel))
                }
            }
        )
    }
}

// ==================== 头部区域 ====================

@Composable
private fun DoubanItemHeader(
    failure: DoubanSyncFailure,
    posterColor: Color?,
    onPosterClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    posterColorExtractor: PosterColorExtractor,
    onPosterColorExtracted: (Color) -> Unit
) {
    val scope = rememberCoroutineScope()
    // 根据海报主色调亮度自适应文字颜色,增强沉浸背景下的可读性
    val onPosterColor = posterColor?.let { c ->
        if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.92f) else Color.White
    } ?: MaterialTheme.colorScheme.onSurface
    val onPosterVariantColor = posterColor?.let { c ->
        if (c.luminance() > 0.5f) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.72f)
    } ?: MaterialTheme.colorScheme.onSurfaceVariant
    // 处理 title 中包含 / 的中英文名分隔:主标题取 / 前面,子标题优先用已有,为空时取 / 后面
    val titleContainsSlash = failure.title.contains("/")
    val displayTitle = if (titleContainsSlash) failure.title.substringBefore("/") else failure.title
    val displaySubtitle = when {
        !failure.subtitle.isNullOrBlank() -> failure.subtitle
        titleContainsSlash -> failure.title.substringAfter("/", "").takeIf { it.isNotEmpty() }
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 顶部留白 = 状态栏 + TopAppBar 高度(64dp),避免海报被标题栏遮住
            .statusBarsPadding()
            .padding(top = 64.dp)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 海报(2:3 比例,120dp 宽)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(width = 120.dp, height = 180.dp)
                    .then(if (failure.posterUrl != null) Modifier.clickable { onPosterClick() } else Modifier)
            ) {
                if (failure.posterUrl != null) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = remember(failure.posterUrl) {
                            ImageRequest.Builder(context)
                                .data(failure.posterUrl)
                                .size(360)
                                .listener(
                                    onSuccess = { _, result ->
                                        // 图片加载成功后提取主色调,用于沉浸式背景渐变
                                        // 外层已判空且 failure 为 val 参数,posterUrl 在此非 null
                                        val bitmap = result.drawable.toBitmap()
                                        scope.launch {
                                            val argb = posterColorExtractor.extractDominantColor(failure.posterUrl, bitmap)
                                            if (argb != 0L) {
                                                onPosterColorExtracted(Color(argb))
                                            }
                                        }
                                    }
                                )
                                .build()
                        },
                        contentDescription = displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = "🎬",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 右侧信息列(与海报等高,失败原因栏底部对齐海报底部)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // 标题(主标题,去除 / 后的英文名部分)
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onPosterColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // 子标题(可点击编辑;为空时若 title 含 / 则取 / 后面)
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSubtitleClick() }
                ) {
                    Text(
                        text = displaySubtitle
                            ?: stringResource(R.string.screen_douban_item_detail_subtitle_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (displaySubtitle != null)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 评分(5★)
                if (failure.rating != null) {
                    DoubanStarRating(rating = failure.rating, textColor = onPosterVariantColor)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // 标记时间 + 状态
                val statusText = when (failure.status) {
                    DoubanMarkStatus.WISH -> stringResource(R.string.watchlist_mode_watchlist)
                    DoubanMarkStatus.COLLECT -> stringResource(R.string.watchlist_mode_watched)
                }
                Text(
                    text = "${failure.markedAt} · $statusText",
                    style = MaterialTheme.typography.bodySmall,
                    color = onPosterVariantColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 占位将失败原因栏推到底部,与海报底部对齐
                Spacer(modifier = Modifier.weight(1f))

                // 失败原因栏(从 header 之后的独立 banner 移入此处)
                DoubanFailureBanner(
                    failure = failure,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 短评(若有)
        if (!failure.comment.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "「${failure.comment}」",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/** 5 星评分显示(豆瓣 1-5 评分) */
@Composable
private fun DoubanStarRating(rating: Int, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            Icon(
                imageVector = if (i <= rating) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = null,
                tint = if (i <= rating) Color(0xFFFFC107) else textColor.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$rating/5",
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

// ==================== 提示横幅 ====================

@Composable
private fun DoubanFailureBanner(
    failure: DoubanSyncFailure,
    modifier: Modifier = Modifier
) {
    val reasonText = failure.failureReason.toLocalizedString()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.screen_douban_item_detail_not_synced),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.screen_douban_item_detail_failure_reason,
                    reasonText
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** 失败原因本地化 */
@Composable
private fun FailureReason.toLocalizedString(): String = when (this) {
    FailureReason.NO_IMDB_ID -> stringResource(R.string.douban_failure_reason_no_imdb_id)
    FailureReason.DETAIL_FETCH_FAILED -> stringResource(R.string.douban_failure_reason_detail_fetch_failed)
    FailureReason.TRAKT_NOT_FOUND -> stringResource(R.string.douban_failure_reason_trakt_not_found)
    FailureReason.TRAKT_WRITE_TIMEOUT -> stringResource(R.string.douban_failure_reason_trakt_write_timeout)
    FailureReason.TRAKT_WRITE_FAILED -> stringResource(R.string.douban_failure_reason_trakt_write_failed)
}

// ==================== 资源搜索 Tab ====================

@Composable
private fun DoubanSearchKeywordBar(
    failure: DoubanSyncFailure,
    searchWithSubtitle: Boolean,
    onToggleSearchWithSubtitle: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = if (failure.subtitle != null && searchWithSubtitle)
                "${failure.title} + ${failure.subtitle}"
            else
                failure.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // 子标题非空时显示「同时用子标题搜索」开关
        if (!failure.subtitle.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.screen_douban_item_detail_search_with_subtitle),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = searchWithSubtitle,
                    onCheckedChange = { onToggleSearchWithSubtitle() }
                )
            }
        }
    }
}

/**
 * 资源筛选器(参照 [com.tracktosearch.ui.screen.detail.FilterSection],
 * 因原 FilterSection 为 internal 无法跨包访问,此处自建简化版)。
 */
@Composable
private fun DoubanFilterSection(
    availableSources: List<String>,
    enabledSources: Set<String>,
    customSourceNames: Map<String, String>,
    enabledDiskTypes: Set<DiskType>,
    onToggleSource: (String) -> Unit,
    onToggleDiskType: (DiskType) -> Unit
) {
    val view = LocalView.current
    val labelWidth = 64.dp

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        // 搜索源
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_filter_sources),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(labelWidth)
            )
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = availableSources,
                    key = { it }
                ) { source ->
                    val label = when (source) {
                        "pansou" -> "PanSou"
                        "panhub" -> "PanHub"
                        "zreso" -> "Zreso"
                        else -> customSourceNames[source] ?: source
                    }
                    androidx.compose.material3.FilterChip(
                        selected = source in enabledSources,
                        onClick = {
                            view.performHaptic(HapticType.TICK)
                            onToggleSource(source)
                        },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 网盘类型
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.detail_filter_disk_types),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(labelWidth)
            )
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = ResourceRepository.ALL_DISK_TYPES.toList(),
                    key = { it.name }
                ) { type ->
                    val label = when (type) {
                        DiskType.QUARK -> stringResource(R.string.detail_disk_type_quark)
                        DiskType.BAIDU -> stringResource(R.string.detail_disk_type_baidu)
                        DiskType.ALI -> stringResource(R.string.detail_disk_type_ali)
                        DiskType.XUNLEI -> stringResource(R.string.detail_disk_type_xunlei)
                        DiskType.UC -> stringResource(R.string.detail_disk_type_uc)
                        DiskType.ONEONEFIVE -> stringResource(R.string.detail_disk_type_115)
                        DiskType.MAGNET -> stringResource(R.string.detail_disk_type_magnet)
                        DiskType.OTHER -> stringResource(R.string.detail_disk_type_other)
                    }
                    androidx.compose.material3.FilterChip(
                        selected = type in enabledDiskTypes,
                        onClick = {
                            view.performHaptic(HapticType.TICK)
                            onToggleDiskType(type)
                        },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DoubanSearchingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.detail_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DoubanEmptyState(onRetry: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.screen_douban_item_detail_no_resources),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = {
                view.performHaptic(HapticType.CLICK)
                onRetry()
            }) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.detail_retry))
            }
        }
    }
}

// ==================== 详情信息 Tab ====================

@Composable
private fun DoubanDetailInfoTab(
    failure: DoubanSyncFailure,
    onOpenDouban: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val view = LocalView.current
    Column(modifier = Modifier.padding(16.dp)) {
        // 操作按钮区
        Button(
            onClick = {
                view.performHaptic(HapticType.CLICK)
                onOpenDouban()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.screen_douban_item_detail_open_douban))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 仅可恢复原因才显示「重新尝试同步」
        if (failure.failureReason.recoverable) {
            Button(
                onClick = {
                    view.performHaptic(HapticType.CLICK)
                    onRetry()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.screen_douban_item_detail_retry))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = {
                view.performHaptic(HapticType.CLICK)
                onDelete()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.screen_douban_item_detail_delete))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 失败元信息卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                MetaRow(
                    label = stringResource(R.string.screen_douban_item_detail_meta_failed_at),
                    value = formatTimestamp(failure.failedAt)
                )
                Spacer(modifier = Modifier.height(6.dp))
                MetaRow(
                    label = stringResource(R.string.screen_douban_item_detail_meta_attempt_count),
                    value = failure.attemptCount.toString()
                )
                Spacer(modifier = Modifier.height(6.dp))
                MetaRow(
                    label = stringResource(R.string.screen_douban_item_detail_meta_douban_id),
                    value = failure.doubanId
                )
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 格式化失败时间戳(毫秒 → yyyy-MM-dd HH:mm) */
private fun formatTimestamp(timestampMs: Long): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(timestampMs))
    } catch (_: Exception) {
        timestampMs.toString()
    }
}
