package com.tracktosearch.ui.screen.douban
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Nature
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.OpenImageViewerItem
import com.tracktosearch.ui.component.openImageViewer
import com.tracktosearch.ui.component.recordOpenImageBounds
import com.tracktosearch.ui.component.rememberOpenImageBounds
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanCelebrity
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.ResourceQuery
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.ui.component.ActionButtonRow
import com.tracktosearch.ui.component.ActionItem
import com.tracktosearch.ui.component.AppPullToRefreshIndicator
import com.tracktosearch.ui.component.DetailTopBarIcon
import com.tracktosearch.ui.component.DropdownAnchorMenu
import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.NeumorphicIconButtonStyle
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.detailTopBarIconColor
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.rememberAppPullToRefreshState
import com.tracktosearch.ui.haptic.HapticOutcome
import com.tracktosearch.ui.haptic.HapticOutcomeEffect
import com.tracktosearch.ui.haptic.HapticOutcomeEmitter
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.detail.DETAIL_TOP_BAR_HEIGHT
import com.tracktosearch.ui.screen.detail.DetailMetaChip
import com.tracktosearch.ui.screen.detail.DetailMetaChips
import com.tracktosearch.ui.screen.detail.DetailSectionHeader
import com.tracktosearch.ui.screen.detail.DoubanHeaderSkeleton
import com.tracktosearch.ui.screen.detail.ExpandableText
import com.tracktosearch.ui.screen.detail.FilterSection
import com.tracktosearch.ui.screen.detail.detailBarColor
import com.tracktosearch.ui.screen.detail.detailOnPosterColor
import com.tracktosearch.ui.screen.detail.detailOnPosterVariantColor
import com.tracktosearch.ui.theme.RatingGold
import com.tracktosearch.ui.theme.RatingGoldDim
import com.tracktosearch.ui.util.ToastEffect
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.util.showToast
import com.tracktosearch.ui.util.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull


// ==================== ViewModel ====================

/**
 * 豆瓣详情加载阶段（详情Tab内联状态用）。
 * - IDLE: 初始/未开始
 * - FETCHING: 正在爬取豆瓣详情页（3-5秒反爬延迟+网络请求）
 * - DONE: 完成（含缓存命中；detailInfo 为 null 表示爬取失败但已尝试）
 * - FAILED: 爬取失败（显示重试按钮）
 */
enum class DetailLoadPhase { IDLE, FETCHING, DONE, FAILED }

/**
 * 豆瓣条目详情页 UI 状态
 */
data class DoubanItemDetailUiState(
    val isLoading: Boolean = true,
    val failure: DoubanSyncFailure? = null,
    /** true 表示仅为兼容旧技术失败记录，false 表示同步表/豆瓣快照中的正常条目。 */
    val isLegacyFailure: Boolean = false,
    /** 加载失败原始异常(VM 不做本地化,UI 组合期用 toUserMessage 转文案) */
    val error: Throwable? = null,
    /** 三表(同步表/详情快照/旧失败表)都查不到:不自动 onBack,由 UI 显示「条目未同步」错误卡片 */
    val entryNotFound: Boolean = false,
    // 资源搜索
    val searchResults: List<ResourceItem> = emptyList(),
    val lowRelevanceHiddenCount: Int = 0,
    val showHighRelevanceOnly: Boolean = false,
    val isSearching: Boolean = false,
    /** 资源搜索失败原始异常(VM 不做本地化,UI 组合期用 toUserMessage 转文案) */
    val searchError: Throwable? = null,
    val searchWithSubtitle: Boolean = true,
    val searchAttempted: Boolean = false,
    // 筛选器
    val availableSources: List<String> = emptyList(),
    val enabledSources: Set<String> = ResourceRepository.ALL_SOURCES,
    val customSourceNames: Map<String, String> = emptyMap(),
    val enabledDiskTypes: Set<DiskType> = ResourceRepository.ALL_DISK_TYPES,
    // 弹窗
    val showSubtitleDialog: Boolean = false,
    // 重试
    val retryStarted: Boolean = false,
    val retryStartFailed: Boolean = false,
    // 海报主色调(沉浸式渐变背景用,null 表示尚未提取)
    val posterDominantColor: Color? = null,
    // 豆瓣详情补充信息(年份/制片国家/地区/导演/类型),命中缓存秒回,失败或未加载为 null
    val detailInfo: DoubanDetailInfo? = null,
    // 豆瓣详情加载阶段(详情Tab内联状态用)
    val detailLoadPhase: DetailLoadPhase = DetailLoadPhase.IDLE,
    // 写回豆瓣标记操作进行中(想看/已看/取消按钮统一 loading)
    val marking: Boolean = false
)

/** 豆瓣详情页返回时需要通知 Watchlist 重读的标记变更。 */
data class DoubanDetailMarkChanges(
    val watchlistChanged: Boolean = false,
    val watchedChanged: Boolean = false
)

/**
 * 豆瓣条目详情页 ViewModel。
 *
 * 职责:
 * - 加载单条失败项数据([DoubanRetryManager.getFailure])
 * - 资源搜索:主标题 + 子标题并行搜索(参照 [com.tracktosearch.ui.screen.detail.DetailViewModel])
 * - 子标题编辑 → 持久化 → 自动刷新搜索
 * - 单条重试 → 启动 [DoubanSyncManager] 重试流程
 * - 强制重新爬取豆瓣详情(forceRefresh=true,跳过缓存)
 * - 加载豆瓣详情补充信息(年份/国家/导演/类型),命中持久化缓存秒回
 */
@HiltViewModel
class DoubanItemDetailViewModel @Inject constructor(
    private val doubanRetryManager: DoubanRetryManager,
    private val doubanSyncManager: DoubanSyncManager,
    private val resourceRepository: ResourceRepository,
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    val posterColorExtractor: PosterColorExtractor
) : ViewModel() {

    private val _uiState = MutableStateFlow(DoubanItemDetailUiState())
    val uiState: StateFlow<DoubanItemDetailUiState> = _uiState.asStateFlow()

    private val _markChanges = MutableStateFlow(DoubanDetailMarkChanges())
    val markChanges: StateFlow<DoubanDetailMarkChanges> = _markChanges.asStateFlow()

    // 一次性 Toast 事件(传 R.string 资源 ID),用 extraBufferCapacity 避免背压丢消息
    private val _toastEvent = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val toastEvent: SharedFlow<Int> = _toastEvent.asSharedFlow()

    /**
     * 结果类触感的出口，界面侧一行 `HapticOutcomeEffect(viewModel.hapticOutcomes)` 收集。
     *
     * 豆瓣写回的终态可能落在 IO 线程上，而触感要求主线程；收集放在 UI 层一律主线程。
     */
    private val hapticOutcomeEmitter = HapticOutcomeEmitter()
    val hapticOutcomes: SharedFlow<HapticOutcome> = hapticOutcomeEmitter.outcomes

    private var currentDoubanId: String = ""
    private var searchJob: Job? = null
    /** 全量资源结果(未按筛选器过滤),用于本地切换筛选器时即时过滤 */
    private var allResources: List<ResourceItem> = emptyList()
    // 资源相关度评分用的目标影视上下文（搜索开始时构造）
    private var currentResourceQuery: ResourceQuery? = null
    // 当前搜索结果的相关度分值表（url -> score），与 allResources 同生命周期
    private var currentScoreMap: Map<String, Int> = emptyMap()
    // 当前搜索结果的高相关资格表；规则由 Repository 统一计算，页面只负责本地过滤
    private var currentHighRelevanceMap: Map<String, Boolean> = emptyMap()

    /** 累积本次详情页内的状态变更，直到返回 Watchlist 时一次性传递。 */
    private fun recordMarkChange(
        previousStatus: DoubanMarkStatus,
        currentStatus: DoubanMarkStatus?
    ) {
        if (previousStatus == currentStatus) return
        val current = _markChanges.value
        _markChanges.value = current.copy(
            // wish 条目属于 Watchlist；collect 条目属于已看历史。
            watchlistChanged = current.watchlistChanged ||
                previousStatus == DoubanMarkStatus.WISH || currentStatus == DoubanMarkStatus.WISH,
            watchedChanged = current.watchedChanged ||
                previousStatus == DoubanMarkStatus.COLLECT || currentStatus == DoubanMarkStatus.COLLECT
        )
    }

    /**
     * 加载指定豆瓣条目。
     *
     * 豆瓣同步表和详情快照是正常条目的主数据源；旧失败表只负责兼容历史技术失败。
     */
    fun loadFailure(doubanId: String) {
        if (currentDoubanId == doubanId && _uiState.value.failure != null) return
        currentDoubanId = doubanId
        _uiState.value = DoubanItemDetailUiState(isLoading = true)
        viewModelScope.launch {
            try {
                val source = loadDetailSource(doubanId)
                if (source == null) {
                    // 三表都查不到(AI 推荐/深链带来的未同步条目):不再自动 onBack(立即弹回像点击失灵),
                    // 置 entryNotFound 由 UI 显示错误卡片,返回交还给用户
                    _uiState.value = DoubanItemDetailUiState(isLoading = false, failure = null, entryNotFound = true)
                    return@launch
                }
                _uiState.value = DoubanItemDetailUiState(
                    isLoading = false,
                    failure = source.failure,
                    isLegacyFailure = source.isLegacyFailure,
                    // 默认开启「同时用子标题搜索」(subtitle 非空,或 title 含 "/" 可拆出外文标题时生效)
                    searchWithSubtitle = !source.failure.subtitle.isNullOrBlank() ||
                        source.failure.title.split("/").map { it.trim() }.filter { it.isNotBlank() }.size > 1,
                    detailInfo = source.detailInfo,
                    detailLoadPhase = if (source.detailInfo != null) DetailLoadPhase.DONE else DetailLoadPhase.IDLE
                )
                // 尽早从缓存预查海报主色,让沉浸背景在海报图片加载前显示
                prefetchPosterColor(source.failure.posterUrl)
                // 只有旧失败记录才需要兼容原有的详情爬取流程。
                if (source.isLegacyFailure) {
                    loadDetailInfo(source.failure)
                }
                // 资源搜索延迟到用户切换到资源搜索 Tab 时才触发,避免进入页面瞬间 12+ 并发网络请求
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e
                )
            }
        }
    }

    /**
     * 下拉刷新：重新解析详情数据源，并在资源 Tab 上强制重搜。
     *
     * 不能直接复用 [loadFailure]：它有 `failure != null` 早退（同一条目第二次调用直接返回，
     * 刷新等于没发生），且会把整个 state 重置成 `DoubanItemDetailUiState(isLoading = true)`
     * 把已有 failure 抹成 null，导致刷新期间整页闪回空白。这里只置 isLoading，
     * 保留现有内容，请求失败时页面内容也不会被清掉。
     */
    fun pullToRefresh() {
        val doubanId = currentDoubanId.takeIf { it.isNotEmpty() } ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val source = loadDetailSource(doubanId)
                if (source == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    failure = source.failure,
                    isLegacyFailure = source.isLegacyFailure,
                    detailInfo = source.detailInfo,
                    detailLoadPhase = if (source.detailInfo != null) DetailLoadPhase.DONE
                    else _uiState.value.detailLoadPhase
                )
                prefetchPosterColor(source.failure.posterUrl)
                if (source.isLegacyFailure && source.detailInfo == null) {
                    loadDetailInfo(source.failure)
                }
                // 资源已搜过才重搜：没搜过时用户还没进资源 Tab，不该由下拉触发 12+ 并发请求
                if (_uiState.value.searchResults.isNotEmpty() || _uiState.value.searchError != null) {
                    searchResources(forceRefresh = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e)
            }
        }
    }

    private data class DetailSource(
        val failure: DoubanSyncFailure,
        val detailInfo: DoubanDetailInfo?,
        val isLegacyFailure: Boolean
    )

    /** 按同步表 → 详情快照 → 历史失败表的顺序解析详情页数据源。 */
    private suspend fun loadDetailSource(doubanId: String): DetailSource? {
        val syncedItem = runCatching {
            doubanSyncedItemDao.getByDoubanId(doubanId)
        }.getOrNull()
        val detailEntry = runCatching {
            doubanRepository.getDetailSnapshot()[doubanId]
        }.getOrNull()

        if (syncedItem != null || detailEntry != null) {
            val detailInfo = detailEntry?.toDetailInfo(syncedItem)
            val displayItem = syncedItem?.toDisplayFailure(detailInfo)
                ?: detailEntry?.toDisplayFailure(doubanId)
                ?: return null
            return DetailSource(
                failure = displayItem,
                detailInfo = detailInfo,
                isLegacyFailure = false
            )
        }

        return runCatching { doubanRetryManager.getFailure(doubanId) }
            .getOrNull()
            ?.let { failure ->
                DetailSource(
                    failure = failure,
                    detailInfo = null,
                    isLegacyFailure = true
                )
            }
    }

    /**
     * 加载豆瓣详情补充信息(年份/制片国家/地区/导演/类型/评分/简介/集数等)。
     * 查询链路:内存缓存 → 磁盘缓存 → 全局池 → 爬豆瓣(成功后异步上传全局池)。
     * 通过 onProgress 回调映射到 [detailLoadPhase],驱动详情Tab内联状态UI。
     * 成功后根据集数自动推断媒体类型(不覆盖用户已标注的值,静默刷新)。
     */
    private fun loadDetailInfo(failure: DoubanSyncFailure) {
        viewModelScope.launch {
            // 记录是否来自实际爬取(用于决定是否弹乐观 Toast)
            var fromNetwork = false
            try {
                val cookie = doubanAuthStorage.getCredentials()?.cookie ?: run {
                    // Cookie 不存在,直接标记失败
                    _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FAILED)
                    return@launch
                }
                val result = doubanRepository.fetchDetail(
                    doubanUrl = failure.doubanUrl,
                    cookie = cookie,
                    title = failure.title,
                    onProgress = { phase, _ ->
                        when (phase) {
                            "cache_hit" -> {
                                // 缓存命中(本地或全局池),秒回,不弹 Toast
                                _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.DONE)
                            }
                            "fetching" -> {
                                _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FETCHING)
                                fromNetwork = true
                            }
                            "done" -> {
                                _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.DONE)
                                fromNetwork = true
                            }
                            "failed" -> {
                                _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FAILED)
                            }
                        }
                    }
                )
                result.first?.let { info ->
                    _uiState.value = _uiState.value.copy(detailInfo = info)
                    // 媒体类型为 null 时自动推断(缓存命中也可能 mediaType 为 null);
                    // inferMediaTypeFromDetail 内部已判空,已标注的非强类型不会覆盖
                    val shouldInfer = fromNetwork || failure.mediaType == null
                    if (shouldInfer) {
                        val inferred = runCatching {
                            doubanRetryManager.inferMediaTypeFromDetail(failure.doubanId, info)
                        }.getOrDefault(false)
                        if (inferred) {
                            val refreshed = doubanRetryManager.getFailure(failure.doubanId)
                            if (refreshed != null) {
                                _uiState.value = _uiState.value.copy(failure = refreshed)
                            }
                        }
                    }
                    // 实际爬取豆瓣成功 → 弹乐观 Toast(上传全局池在 Repository 内异步执行)
                    if (fromNetwork) {
                        _toastEvent.tryEmit(R.string.douban_detail_updated_and_synced)
                        hapticOutcomeEmitter.emit(HapticOutcome.SUCCESS)
                    }
                }
            } catch (_: Exception) {
                // 异常:标记失败,不阻塞主流程
                _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FAILED)
            }
        }
    }

    /** 重试加载豆瓣详情(详情Tab失败时用户点击重试按钮触发) */
    fun retryLoadDetailInfo() {
        if (!_uiState.value.isLegacyFailure) return
        val failure = _uiState.value.failure ?: return
        loadDetailInfo(failure)
    }

    /** 更新子标题,持久化后刷新本地状态并自动重新搜索 */
    fun updateSubtitle(doubanId: String, subtitle: String?) {
        viewModelScope.launch {
            // 原来这里没有 try/catch：写库抛异常时协程直接挂掉，弹窗关了、子标题没存下、
            // 屏幕上一个字都不变，用户以为存好了。补一条失败提示与一记 reject
            try {
                doubanRetryManager.updateSubtitle(doubanId, subtitle)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _toastEvent.tryEmit(R.string.screen_douban_item_detail_subtitle_save_failed)
                hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
                return@launch
            }
            val current = _uiState.value.failure ?: return@launch
            // updateSubtitle 内部会 trim 空串转 null,这里同步处理
            val normalized = subtitle?.trim()?.takeIf { it.isNotBlank() }
            _uiState.value = _uiState.value.copy(
                failure = current.copy(subtitle = normalized),
                searchWithSubtitle = !normalized.isNullOrBlank()
            )
            // 成功不发触感：紧接着就重搜资源，结果列表刷出来本身就是反馈
            searchResources()
        }
    }

    /**
     * 资源搜索：主标题和子标题分开搜索；标题里若含「第x季」或「season x」，
     * 再分别用「去季版本」和「含季版本」各搜一次（最多 4 个关键词）。
     * 多关键词用 Flow merge 模式实现「先到先显示」；最终结果按是否含季信息排序（含季排前）。
     *
     * @param forceRefresh true 时强制清缓存重拉（用于手动刷新按钮）
     */
    fun searchResources(forceRefresh: Boolean = false) {
        val failure = _uiState.value.failure ?: return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
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

            // 综艺/纪录片也按电视剧模式搜索(多季资源优先)
            val isShow = failure.mediaType in setOf("show", "variety", "documentary")

            // 构造关键词列表（最多 4 个）：主标题(原/去季) + 子标题(原/去季)
            // 豆瓣条目标题常含 "/" 分隔中文/外文标题（如"王朝 第一季 / Dynasties Season 1"），
            // 自动拆分出外文标题作为子标题候选；用户手动编辑的 subtitle 优先
            val keywords = mutableListOf<String>()
            val titleParts = failure.title.split("/").map { it.trim() }.filter { it.isNotBlank() }
            val mainTitle = titleParts.getOrElse(0) { failure.title }
            val titleForeignName = titleParts.getOrNull(1)
            val mainNoSeason = removeSeasonInfo(mainTitle)
            keywords.add(mainTitle)
            if (mainNoSeason != mainTitle) {
                keywords.add(mainNoSeason)
            }
            val subtitle = failure.subtitle ?: titleForeignName
            if (!subtitle.isNullOrBlank() && _uiState.value.searchWithSubtitle) {
                val subNoSeason = removeSeasonInfo(subtitle)
                keywords.add(subtitle)
                if (subNoSeason != subtitle) {
                    keywords.add(subNoSeason)
                }
            }

            // 构造目标影视查询上下文（用于资源相关度评分；缺字段时对应信号自动跳过）
            // 导演/演员作为强相关信号：标题命中导演名明显指向同一作品
            val detailInfo = _uiState.value.detailInfo
            val resourceQuery = ResourceQuery(
                title = mainTitle,
                originalTitle = subtitle?.takeIf { it.isNotBlank() },
                year = detailInfo?.year?.toIntOrNull(),
                country = detailInfo?.countries?.firstOrNull(),
                mediaType = if (isShow) MediaType.SHOW else MediaType.MOVIE,
                directors = detailInfo?.directors ?: emptyList(),
                cast = (detailInfo?.cast ?: emptyList()).take(5)
            )
            currentResourceQuery = resourceQuery

            // forceRefresh=true 时清缓存
            if (forceRefresh) {
                keywords.forEach { kw ->
                    resourceRepository.refreshResources(
                        keyword = kw,
                        enabledSources = storageEnabledSources,
                        enabledDiskTypes = _uiState.value.enabledDiskTypes,
                        isShow = isShow,
                        query = resourceQuery
                    )
                }
            }

            try {
                // 多关键词并行 Flow，每源完成发射累积结果；merge 后逐次更新 UI
                val keywordFlows = keywords.map { kw ->
                    resourceRepository.searchResourcesFlow(
                        keyword = kw,
                        enabledSources = storageEnabledSources,
                        enabledDiskTypes = _uiState.value.enabledDiskTypes,
                        isShow = isShow,
                        query = resourceQuery
                    ).map { items -> kw to items }
                }
                val mergedFlow = if (keywordFlows.size == 1) {
                    keywordFlows.first()
                } else {
                    merge(*keywordFlows.toTypedArray())
                }

                val keywordItemsMap = mutableMapOf<String, List<ResourceItem>>()
                val seenUrls = mutableSetOf<String>()
                var firstResultShown = false

                mergedFlow.collect { (kw, items) ->
                    keywordItemsMap[kw] = items
                    // 重新合并所有关键词当前结果，按 URL 去重（保留先出现的）
                    seenUrls.clear()
                    val merged = mutableListOf<ResourceItem>()
                    for (currentItems in keywordItemsMap.values) {
                        for (item in currentItems) {
                            if (item.url !in seenUrls) {
                                seenUrls.add(item.url)
                                merged.add(item)
                            }
                        }
                    }
                    // 写入缓存并按筛选器过滤
                    if (merged.isNotEmpty()) {
                        val ranked = resourceRepository.mergeAndCacheResources(
                            keyword = failure.title,
                            items = merged,
                            isShow = isShow,
                            query = resourceQuery
                        )
                        allResources = ranked.items
                        currentScoreMap = ranked.scoreMap
                        currentHighRelevanceMap = ranked.highRelevanceMap
                    } else {
                        allResources = resourceRepository.getCachedAllResources(failure.title)
                        currentScoreMap = emptyMap()
                        currentHighRelevanceMap = emptyMap()
                    }
                    val filtered = resourceRepository.filterItems(
                        allResources,
                        _uiState.value.enabledSources,
                        _uiState.value.enabledDiskTypes
                    )
                    // 按"仅显示高相关"过滤并统计隐藏数
                    val (displayed, hiddenCount) = applyHighRelevanceFilter(
                        filtered, _uiState.value.showHighRelevanceOnly
                    )
                    // 不再调用 sortResourcesBySeasonPresence：它会把列表拆成"含季/不含季"两组再拼接，
                    // 完全破坏 Repository 按相关度排好的主序。含季优先已由 multiSeasonScore 作为
                    // comparator 次级条件处理，两个页面行为一致。
                    _uiState.value = _uiState.value.copy(
                        searchResults = displayed,
                        lowRelevanceHiddenCount = hiddenCount,
                        searchAttempted = true
                    )
                    // 第一次有非空结果时关闭搜索状态
                    if (!firstResultShown && items.isNotEmpty()) {
                        firstResultShown = true
                        _uiState.value = _uiState.value.copy(isSearching = false)
                    }
                }

                // 所有 Flow 收集完毕，关闭搜索状态
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    searchError = e
                )
            }
        }
    }

    /**
     * 移除标题中的季信息（「第x季」/「season x」/「Season x」）。
     * 不含季信息时返回原标题。
     */
    private fun removeSeasonInfo(title: String): String {
        var result = title
        // 中文「第x季」「第三季」「第 3 季」等
        result = result.replace(Regex("""第\s*[一二三四五六七八九十0-9]+\s*季"""), "")
        // 英文「season x」「Season x」「SEASON X」
        result = result.replace(Regex("""(?i)\bseason\s*\d+\b"""), "")
        return result.trim()
    }

    /** 切换「同时用子标题搜索」开关,切换后自动重新搜索 */
    fun toggleSearchWithSubtitle() {
        val newValue = !_uiState.value.searchWithSubtitle
        _uiState.value = _uiState.value.copy(searchWithSubtitle = newValue)
        searchResources()
    }

    /**
     * 应用"仅显示高相关"过滤：低于阈值的结果隐藏，返回（展示列表, 隐藏数量）。
     * 资格从 [currentHighRelevanceMap] 读取（url -> Boolean），避免 ResourceItem 作为可变共享状态。
     */
    private fun applyHighRelevanceFilter(
        items: List<ResourceItem>,
        onlyHigh: Boolean
    ): Pair<List<ResourceItem>, Int> {
        if (!onlyHigh || currentResourceQuery == null || currentHighRelevanceMap.isEmpty()) return items to 0
        val kept = items.filter { currentHighRelevanceMap[it.url] == true }
        return kept to (items.size - kept.size)
    }

    /**
     * 切换"仅显示高相关"开关。
     * 关键：必须从 [allResources] 重新过滤，不能用已被旧开关过滤的列表，
     * 否则从"仅高相关"切回"全部"时低相关项已丢失无法恢复。
     * 不再调用 sortResourcesBySeasonPresence：它会破坏 Repository 按相关度排好的主序。
     */
    fun toggleShowHighRelevanceOnly() {
        val newVal = !_uiState.value.showHighRelevanceOnly
        val state = _uiState.value
        val filtered = resourceRepository.filterItems(allResources, state.enabledSources, state.enabledDiskTypes)
        val (displayed, hiddenCount) = applyHighRelevanceFilter(filtered, newVal)
        _uiState.value = _uiState.value.copy(
            showHighRelevanceOnly = newVal,
            searchResults = displayed,
            lowRelevanceHiddenCount = hiddenCount
        )
    }

    /**
     * 切换搜索源筛选（至少保留 1 个）。
     *
     * @return true 已切换；false 被守卫拒绝，状态没动。界面靠这个回执决定发哪一记触感，
     *   判据留在这里而不是让 chip 自己去数 size。与 `DetailViewModel.toggleSource` 同一个约定。
     */
    fun toggleSource(source: String): Boolean {
        val current = _uiState.value.enabledSources
        val newSources = if (source in current) {
            if (current.size == 1) current else current - source
        } else {
            current + source
        }
        if (newSources == current) return false
        val (displayed, hiddenCount) = run {
            val filtered = resourceRepository.filterItems(
                allResources, newSources, _uiState.value.enabledDiskTypes
            )
            applyHighRelevanceFilter(filtered, _uiState.value.showHighRelevanceOnly)
        }
        _uiState.value = _uiState.value.copy(
            enabledSources = newSources,
            searchResults = displayed,
            lowRelevanceHiddenCount = hiddenCount
        )
        return true
    }

    /**
     * 切换网盘类型筛选（至少保留 1 个）。
     *
     * @return true 已切换；false 被守卫拒绝。与 [toggleSource] 同一个约定。
     */
    fun toggleDiskType(type: DiskType): Boolean {
        val current = _uiState.value.enabledDiskTypes
        val newTypes = if (type in current) {
            if (current.size == 1) current else current - type
        } else {
            current + type
        }
        if (newTypes == current) return false
        val (displayed, hiddenCount) = run {
            val filtered = resourceRepository.filterItems(
                allResources, _uiState.value.enabledSources, newTypes
            )
            applyHighRelevanceFilter(filtered, _uiState.value.showHighRelevanceOnly)
        }
        _uiState.value = _uiState.value.copy(
            enabledDiskTypes = newTypes,
            searchResults = displayed,
            lowRelevanceHiddenCount = hiddenCount
        )
        return true
    }

    fun showSubtitleDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSubtitleDialog = show)
    }

    /** 获取豆瓣 cookie(供 WebView 注入用) */
    fun getDoubanCookie(): String? = doubanAuthStorage.getCredentials()?.cookie

    /**
     * 启动单条重试。
     * - true:已启动,UI 跳转到 DoubanSyncDialog
     * - false:同步正在运行(防重入),UI Toast 提示
     */
    fun retrySingle() {
        if (!_uiState.value.isLegacyFailure) return
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

    /** 强制重新爬取豆瓣详情(跳过缓存和全局池,直接爬取豆瓣) */
    fun retryFetchDetail() {
        if (!_uiState.value.isLegacyFailure) return
        val current = _uiState.value.failure ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FETCHING)
            try {
                val cookie = getDoubanCookie()
                val result = doubanRepository.fetchDetail(
                    doubanUrl = current.doubanUrl,
                    cookie = cookie ?: "",
                    title = current.title,
                    forceRefresh = true,
                    onProgress = { phase, _ ->
                        when (phase) {
                            "fetching" -> {
                                _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FETCHING)
                            }
                        }
                    }
                )
                result.first?.let { info ->
                    _uiState.value = _uiState.value.copy(detailInfo = info, detailLoadPhase = DetailLoadPhase.DONE)
                    // 强制重新爬取成功后推断媒体类型
                    val inferred = runCatching {
                        doubanRetryManager.inferMediaTypeFromDetail(current.doubanId, info)
                    }.getOrDefault(false)
                    if (inferred) {
                        val refreshed = doubanRetryManager.getFailure(current.doubanId)
                        if (refreshed != null) {
                            _uiState.value = _uiState.value.copy(failure = refreshed)
                        }
                    }
                    _toastEvent.tryEmit(R.string.douban_detail_updated_and_synced)
                    hapticOutcomeEmitter.emit(HapticOutcome.SUCCESS)
                } ?: run {
                    _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FAILED)
                    // 用户主动点的「重新爬取」，拿不到详情就是失败；界面同时切到 FAILED 态
                    hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 网络错误、解析异常等，避免 UI 永久卡在 FETCHING
                _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FAILED)
            }
        }
    }

    /**
     * 手动标注当前条目的媒体类型(movie/show/variety/documentary/null)。
     * - 标注为具体类型:持久化 + 上传全局池(updateMediaType 内部完成) → Toast「已标注为XX,已同步全局池」
     * - 清除标注(null):仅持久化,不上传全局池 → Toast「已清除标注」
     */
    fun setMediaType(mediaType: String?) {
        val failure = _uiState.value.failure ?: return
        viewModelScope.launch {
            try {
                doubanRetryManager.updateMediaType(failure.doubanId, mediaType)
                _uiState.value = _uiState.value.copy(
                    failure = failure.copy(mediaType = mediaType)
                )
                _toastEvent.tryEmit(
                    if (mediaType != null) when (mediaType) {
                        "movie" -> R.string.douban_detail_marked_and_synced_movie
                        "show" -> R.string.douban_detail_marked_and_synced_show
                        "variety" -> R.string.douban_detail_marked_and_synced_variety
                        "documentary" -> R.string.douban_detail_marked_and_synced_documentary
                        else -> R.string.douban_detail_mark_cleared
                    } else R.string.douban_detail_mark_cleared
                )
                hapticOutcomeEmitter.emit(HapticOutcome.SUCCESS)
            } catch (_: Exception) {
                _toastEvent.tryEmit(R.string.douban_detail_mark_failed)
                hapticOutcomeEmitter.emit(HapticOutcome.FAILURE)
            }
        }
    }

    /** 海报主色调提取回调:更新沉浸式渐变背景色 */
    fun updatePosterColor(color: Color) {
        _uiState.value = _uiState.value.copy(posterDominantColor = color)
    }

    // ==================== 豆瓣标记双向写回 ====================

    /**
     * 写回豆瓣:将当前条目标记为[status](想看/已看)。
     * 流程:取 cookie → 抓详情页解析 ck → 调 [DoubanRepository.markInterest]。
     * 成功后持久化新状态到失败列表(不移除条目),刷新 UI 状态行;失败弹 Toast。
     */
    private fun markToDouban(status: DoubanMarkStatus) {
        val failure = _uiState.value.failure ?: return
        if (_uiState.value.marking) return
        _uiState.value = _uiState.value.copy(marking = true)
        viewModelScope.launch {
            val result = runCatching {
                val credentials = doubanAuthStorage.getCredentials()
                    ?: return@runCatching MarkWriteOutcome.LoginRequired
                val ck = doubanRepository.fetchCsrfToken(failure.doubanId, credentials.cookie)
                    ?: return@runCatching if (doubanRepository.fetchDetailPageHtml(failure.doubanId, credentials.cookie) == null)
                        MarkWriteOutcome.CookieExpired else MarkWriteOutcome.CkFailed
                val res = doubanRepository.markInterestByCk(status.path, failure.doubanId, credentials.cookie, ck)
                if (res.success) {
                    doubanRetryManager.updateStatus(failure.doubanId, status)
                    val refreshed = doubanRetryManager.getFailure(failure.doubanId)
                    recordMarkChange(failure.status, status)
                    _uiState.value = _uiState.value.copy(
                        marking = false,
                        failure = refreshed ?: failure.copy(status = status)
                    )
                    MarkWriteOutcome.Success
                } else {
                    _uiState.value = _uiState.value.copy(marking = false)
                    MarkWriteOutcome.Failed(res.message)
                }
            }.getOrDefault(MarkWriteOutcome.Failed(null))
            // 确保所有分支（含 LoginRequired/CookieExpired/CkFailed/异常）都重置 marking，避免按钮永久禁用
            _uiState.value = _uiState.value.copy(marking = false)
            emitWritebackToast(result)
        }
    }

    /** 想看(wish)写回 */
    fun markWish() = markToDouban(DoubanMarkStatus.WISH)

    /** 已看(collect)写回 */
    fun markCollect() = markToDouban(DoubanMarkStatus.COLLECT)

    /**
     * 取消标记(删除豆瓣收藏)。
     * 成功后删除该失败条目(标记已无意义),UI 自动返回列表。
     * 二次确认由 UI 的 AlertDialog 完成,确认后才调用本方法。
     */
    fun removeMark() {
        val failure = _uiState.value.failure ?: return
        if (_uiState.value.marking) return
        _uiState.value = _uiState.value.copy(marking = true)
        viewModelScope.launch {
            val result = runCatching {
                val credentials = doubanAuthStorage.getCredentials()
                    ?: return@runCatching MarkWriteOutcome.LoginRequired
                val ck = doubanRepository.fetchCsrfToken(failure.doubanId, credentials.cookie)
                    ?: return@runCatching if (doubanRepository.fetchDetailPageHtml(failure.doubanId, credentials.cookie) == null)
                        MarkWriteOutcome.CookieExpired else MarkWriteOutcome.CkFailed
                val res = doubanRepository.removeMark(failure.doubanId, credentials.cookie, ck)
                if (res.success) {
                    doubanRetryManager.deleteFailure(failure.doubanId)
                    recordMarkChange(failure.status, null)
                    _uiState.value = _uiState.value.copy(marking = false, failure = null)
                    MarkWriteOutcome.Removed
                } else {
                    _uiState.value = _uiState.value.copy(marking = false)
                    MarkWriteOutcome.Failed(res.message)
                }
            }.getOrDefault(MarkWriteOutcome.Failed(null))
            // 确保所有分支（含 LoginRequired/CookieExpired/CkFailed/异常）都重置 marking，避免按钮永久禁用
            _uiState.value = _uiState.value.copy(marking = false)
            emitWritebackToast(result)
        }
    }

    /** 将写回结果映射为 Toast 文案资源 ID 并发事件 */
    private fun emitWritebackToast(outcome: MarkWriteOutcome) {
        // 文案与触感方向定在同一个 when 里：往 MarkWriteOutcome 加一种结果时编译器会
        // 同时逼着补两样，分开写就总有一天补了文案忘了触感
        val (resId, haptic) = when (outcome) {
            MarkWriteOutcome.Success ->
                R.string.douban_writeback_marked_success to HapticOutcome.SUCCESS
            MarkWriteOutcome.Removed ->
                R.string.douban_writeback_removed to HapticOutcome.SUCCESS
            MarkWriteOutcome.LoginRequired ->
                R.string.douban_writeback_login_required to HapticOutcome.FAILURE
            MarkWriteOutcome.CookieExpired ->
                R.string.douban_writeback_cookie_expired to HapticOutcome.FAILURE
            MarkWriteOutcome.CkFailed ->
                R.string.douban_writeback_ck_failed to HapticOutcome.FAILURE
            is MarkWriteOutcome.Failed ->
                R.string.douban_writeback_failed to HapticOutcome.FAILURE
        }
        hapticOutcomeEmitter.emit(haptic)
        _toastEvent.tryEmit(resId)
    }

    /** 写回结果枚举(内部用,便于映射 Toast) */
    private sealed interface MarkWriteOutcome {
        data object Success : MarkWriteOutcome
        data object Removed : MarkWriteOutcome
        data object LoginRequired : MarkWriteOutcome
        data object CookieExpired : MarkWriteOutcome
        data object CkFailed : MarkWriteOutcome
        data class Failed(val message: String?) : MarkWriteOutcome
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

/** 将同步表条目映射为详情页现有展示模型，保留完整豆瓣 ID/标记信息。 */
private fun DoubanSyncedItem.toDisplayFailure(detailInfo: DoubanDetailInfo?): DoubanSyncFailure =
    DoubanSyncFailure(
        doubanId = doubanId,
        title = displayTitle?.takeIf { it.isNotBlank() }
            ?: detailInfo?.title?.takeIf { it.isNotBlank() }
            ?: title.ifBlank { doubanId },
        posterUrl = posterUrl ?: detailInfo?.posterUrl,
        rating = rating,
        comment = comment,
        markedAt = markedAt ?: listedAt.orEmpty(),
        doubanUrl = doubanUrl?.takeIf { it.isNotBlank() }
            ?: "https://movie.douban.com/subject/$doubanId/",
        status = DoubanMarkStatus.fromString(status),
        // 复用旧模型字段，但正常同步条目由 isLegacyFailure=false 区分，不会展示失败语义。
        failureReason = FailureReason.NO_IMDB_ID,
        failedAt = syncedAt,
        mediaType = mediaType.takeIf { it.isNotBlank() }
            ?: detailInfo?.let { if (it.isTvShow) "show" else "movie" },
        subtitle = subtitle
    )

/** 仅有详情快照时也能构造最小可展示条目。 */
private fun DoubanDetailCacheEntry.toDisplayFailure(doubanId: String): DoubanSyncFailure =
    DoubanSyncFailure(
        doubanId = doubanId,
        title = title?.takeIf { it.isNotBlank() } ?: doubanId,
        posterUrl = posterUrl,
        rating = null,
        comment = null,
        markedAt = "",
        doubanUrl = "https://movie.douban.com/subject/$doubanId/",
        status = DoubanMarkStatus.WISH,
        failureReason = FailureReason.NO_IMDB_ID,
        failedAt = 0L,
        mediaType = mediaType ?: if (isTvShow) "show" else "movie"
    )

/** 将持久化详情快照转换为页面详情模型，并用同步表中的富化字段兜底。 */
private fun DoubanDetailCacheEntry.toDetailInfo(item: DoubanSyncedItem?): DoubanDetailInfo {
    val resolvedMediaType = mediaType ?: item?.mediaType
    val resolvedIsTvShow = when (resolvedMediaType) {
        "movie" -> false
        "show", "variety", "documentary" -> true
        else -> isTvShow
    }
    val resolvedGenres = genres.ifEmpty {
        item?.genres.orEmpty()
            .split(",", "·")
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
    return DoubanDetailInfo(
        imdbId = imdbId?.takeIf { it.isNotBlank() }
            ?: item?.imdbId?.takeIf { it.isNotBlank() },
        isTvShow = resolvedIsTvShow,
        title = title?.takeIf { it.isNotBlank() }
            ?: item?.displayTitle?.takeIf { it.isNotBlank() }
            ?: item?.title?.takeIf { it.isNotBlank() },
        posterUrl = posterUrl?.takeIf { it.isNotBlank() } ?: item?.posterUrl,
        genres = resolvedGenres,
        year = year?.takeIf { it.isNotBlank() } ?: item?.year?.toString(),
        countries = countries,
        directors = directors,
        doubanRating = doubanRating,
        ratingCount = ratingCount,
        summary = summary,
        episodeCount = episodeCount,
        episodeDuration = episodeDuration,
        aka = aka,
        runtime = runtime,
        writers = writers,
        cast = cast,
        languages = languages,
        initialReleaseDates = initialReleaseDates,
        ratingDistribution = ratingDistribution,
        celebrities = celebrities.map {
            DoubanCelebrity(
                name = it.name,
                doubanPersonageUrl = it.doubanPersonageUrl,
                avatarUrl = it.avatarUrl,
                role = it.role
            )
        }
    )
}

// ==================== Composable ====================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DoubanItemDetailScreen(
    doubanId: String,
    onBack: (watchlistChanged: Boolean, watchedChanged: Boolean) -> Unit,
    onRetryStarted: () -> Unit,
    viewModel: DoubanItemDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val markChanges by viewModel.markChanges.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openDoubanToast = stringResource(R.string.screen_douban_item_detail_open_douban)
    val copiedToast = stringResource(R.string.screen_douban_item_detail_copied)
    val haptics = rememberAppHaptics()
    val listState = rememberLazyListState()
    // 下拉刷新：重新解析详情数据源（+ 已搜过资源时重搜）。整页原先只能靠退出重进刷新。
    var doubanRefreshPending by remember { mutableStateOf(false) }
    val doubanPullToRefreshState = rememberAppPullToRefreshState {
        viewModel.pullToRefresh()
        doubanRefreshPending = true
    }
    LaunchedEffect(doubanRefreshPending) {
        if (!doubanRefreshPending) return@LaunchedEffect
        // 等 isLoading 落下；资源重搜也一并等。10s 兜底，避免某一路请求挂死时指示器不回弹
        withTimeoutOrNull(10_000) {
            snapshotFlow { uiState.isLoading || uiState.isSearching }
                .dropWhile { !it }
                .first { !it }
        }
        doubanPullToRefreshState.finishRefresh()
        doubanRefreshPending = false
    }
    val hazeState = remember { HazeState() }
    val isDarkTheme = isAppDarkTheme()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val doubanGlassScene = glassSceneForContent(
        contentCount = uiState.searchResults.size +
            (uiState.detailInfo?.celebrities?.size ?: 0) +
            if (uiState.detailInfo != null) 1 else 0,
        readabilityDemand = when {
            uiState.isLoading -> 0.82f
            selectedTab == 1 && (uiState.isSearching || uiState.searchResults.isNotEmpty()) -> 0.94f
            uiState.detailInfo != null -> 0.86f
            uiState.failure != null -> 0.78f
            else -> 0.70f
        },
        ambientColor = uiState.posterDominantColor ?: MaterialTheme.colorScheme.background,
        contentCapacity = 64
    )
    val handleBack = {
        onBack(markChanges.watchlistChanged, markChanges.watchedChanged)
    }

    var subtitleInput by remember { mutableStateOf("") }
    // 手动标记媒体类型下拉菜单展开状态
    var showMarkMenu by remember { mutableStateOf(false) }
    // 取消标记二次确认弹窗状态
    var showRemoveConfirm by remember { mutableStateOf(false) }
    // 演职员头像点击 → 应用内 WebView 打开(注入豆瓣 cookie)
    var webviewUrl by remember { mutableStateOf<String?>(null) }
    var webviewTitle by remember { mutableStateOf("") }

    // 内容就绪状态:沉浸背景优先显示,其他内容(tab/搜索/资源)淡入
    // posterDominantColor 就绪 → 80ms 后标记就绪;未就绪 → 200ms 兜底(缩短等待感)
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.posterDominantColor) {
        if (uiState.posterDominantColor != null) {
            kotlinx.coroutines.delay(80)
            contentReady = true
        } else {
            kotlinx.coroutines.delay(200)
            contentReady = true
        }
    }
    // 与普通详情页一致的淡入：原先是 0/1 硬跳变，内容到达时整块闪现
    val contentAlpha by animateFloatAsState(
        targetValue = if (contentReady) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "doubanContentAlpha"
    )

    // 拦截系统返回手势
    BackHandler(enabled = true) {
        handleBack()
    }

    // doubanId 变化时重新加载
    LaunchedEffect(doubanId) {
        viewModel.loadFailure(doubanId)
    }

    // 收集一次性 Toast 事件(爬取成功/标注成功/失败提示)
    ToastEffect(viewModel.toastEvent)

    // 上面那些 toast 与写回结果配对的触感都从这一行出
    HapticOutcomeEffect(viewModel.hapticOutcomes)

    // 加载完成后若 failure == null(条目已被删除/不存在),自动返回;
    // entryNotFound(三表全空)除外:立即弹回像点击失灵,改为页面内错误卡片
    LaunchedEffect(uiState.failure, uiState.isLoading, uiState.entryNotFound) {
        if (!uiState.isLoading && uiState.failure == null && uiState.error == null && !uiState.entryNotFound) {
            handleBack()
        }
    }

    // 重试启动后跳转到 DoubanSyncDialog
    LaunchedEffect(uiState.retryStarted) {
        if (uiState.retryStarted) {
            onRetryStarted()
        }
    }

    val retryInProgressToast = stringResource(R.string.screen_douban_item_detail_retry_in_progress)
    // 重试启动失败 Toast 提示
    LaunchedEffect(uiState.retryStartFailed) {
        if (uiState.retryStartFailed) {
            context.showToast(retryInProgressToast)
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
        ) {
            val immersiveBackgroundModifier = uiState.posterDominantColor?.let { color ->
                Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            color.copy(alpha = 0.70f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
            } ?: Modifier

            val failure = uiState.failure

            // 顶栏与吸顶 Tab 栏共用一条实色底（取色见 DetailVisuals.detailBarColor），与影视详情页同一套。
            // isPinned 判定原先抄的是普通详情页的 `>= 1`，但本页 item 顺序是
            // header(0) / writeback_actions(1) / tab_row(2)，导致 Tab 还在屏幕中段
            // 底色就变实色了。这里按本页真实 index 修正为 `>= 2`。
            // 提到采样源 Box 之外算：状态栏条、吸顶栏、标题淡入三处要用同一份。
            val isPinned by remember {
                derivedStateOf { listState.firstVisibleItemIndex >= 2 }
            }
            val pinnedBarColor = detailBarColor()
            val barColor by animateColorAsState(
                targetValue = if (isPinned) pinnedBarColor else Color.Transparent,
                animationSpec = tween(durationMillis = 180),
                label = "doubanBarColor"
            )
            // 沉浸渐变上的文字色：本页的栏目标题、演职员名等直接画在渐变上（不在卡片里），
            // 按海报亮度自适应。原先取的是吸顶 Tab 栏的文字色，那个值现在跟着实色顶栏走主题色，
            // 拿来染渐变上的文字会在深色海报 + 浅色主题时对比度不足。
            val onImmersiveColor = detailOnPosterColor(uiState.posterDominantColor)

            // 吸顶栏标题行里的条目名：滚过头部才淡入。原先滚过头部就只剩几个孤立的
            // 悬浮圆按钮，页面上没有任何地方还写着在看哪个条目。
            val topBarTitle = uiState.failure?.title
                ?.let { if (it.contains("/")) it.substringBefore("/") else it }
                ?.trim()
                .orEmpty()
            val topBarTitleAlpha by animateFloatAsState(
                targetValue = if (isPinned && topBarTitle.isNotEmpty()) 1f else 0f,
                animationSpec = tween(durationMillis = 220),
                label = "doubanTopBarTitleAlpha"
            )

            // blur 与 glass 都注册同一 Haze source：blur 直接采样，glass 悬浮控件用同一
            // Haze 状态做实时采样（悬浮控件处 LocalBackdrop=null 强制退化），保证沉浸渐变与
            // 滚动内容都能被采到。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState, zIndex = 0f)
                    .then(immersiveBackgroundModifier)
            ) {
            // 状态栏条与顶栏同色，滚过临界点时一起淡入；非吸顶透明（透出渐变）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(barColor)
                    .align(Alignment.TopCenter)
            )

            if (uiState.isLoading && failure == null) {
                // 首屏骨架：原先是整页空白居中转一个圈，海报和标题的位置完全没有预告，
                // 数据到达时整页跳一下。下拉刷新时 failure 已有内容，不该退回骨架。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    DoubanHeaderSkeleton(modifier = Modifier.padding(top = 56.dp))
                }
            } else if (failure != null) {
                // 沉浸渐变上的文字（栏目标题、内部搜索源/网盘类型等）随海报亮度自适应
                CompositionLocalProvider(
                    LocalContentColor provides onImmersiveColor
                ) {
                AppPullToRefreshIndicator(
                    state = doubanPullToRefreshState,
                    contentTop = 8.dp,
                    modifier = Modifier.statusBarsPadding()
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .nestedScroll(doubanPullToRefreshState.connection)
                        .graphicsLayer { translationY = doubanPullToRefreshState.offset.floatValue },
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    // 头部区域:海报 + 标题 + 子标题 + 豆瓣评分 + 我的评分 + 标记信息 + 短评
                    item(key = "header") {
                        DoubanItemHeader(
                            failure = failure,
                            isLegacyFailure = uiState.isLegacyFailure,
                            detailInfo = uiState.detailInfo,
                            posterColor = uiState.posterDominantColor,
                            onSubtitleClick = {
                                subtitleInput = failure.subtitle ?: ""
                                viewModel.showSubtitleDialog(true)
                            },
                            posterColorExtractor = viewModel.posterColorExtractor,
                            onPosterColorExtracted = viewModel::updatePosterColor,
                        )
                    }

                    // 豆瓣标记双向写回操作栏(想看/已看/取消)
                    item(key = "writeback_actions") {
                        Box(modifier = Modifier.alpha(contentAlpha)) {
                            DoubanWritebackActions(
                                failure = failure,
                                marking = uiState.marking,
                                hazeState = hazeState,
                                onWish = { viewModel.markWish() },
                                onCollect = { viewModel.markCollect() },
                                onRemove = { showRemoveConfirm = true }
                            )
                        }
                    }

                    // 标题行 + Tab 行(吸顶)：与影视详情页同一处理 —— 两行同在一个 Column 里
                    // 共用一次铺底、中间不加分隔线，吸顶后就是一整条实色顶栏。原先标题栏是
                    // 页面外层一条独立的 Haze 毛玻璃，与这条吸顶栏深浅不一地叠在一起。
                    // 标题行高度恒定（不吸顶时只是透明占位），否则吸顶瞬间 sticky item
                    // 长高会把下方内容整体往下推一截。
                    stickyHeader(key = "tab_row") {
                        Column(
                            modifier = Modifier
                                .alpha(contentAlpha)
                                .background(barColor)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(DETAIL_TOP_BAR_HEIGHT)
                                    // 左右各让出 64dp 给悬浮的返回按钮与右上角按钮组
                                    .padding(horizontal = 64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = topBarTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.graphicsLayer { alpha = topBarTitleAlpha }
                                )
                            }
                            PrimaryTabRow(
                                selectedTabIndex = selectedTab,
                                containerColor = Color.Transparent,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ) {
                                Tab(
                                    selected = selectedTab == 0,
                                    onClick = {
                                        haptics.segmentTick()
                                        selectedTab = 0
                                    },
                                    text = {
                                        Text(
                                            stringResource(R.string.screen_douban_item_detail_tab_info),
                                            maxLines = 1
                                        )
                                    }
                                )
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = {
                                        haptics.segmentTick()
                                        selectedTab = 1
                                        // 首次切换到资源搜索 Tab 时才触发搜索(延迟加载,减少进入页面时的并发负担)
                                        if (!uiState.searchAttempted && !uiState.isSearching) {
                                            viewModel.searchResources()
                                        }
                                    },
                                    text = {
                                        Text(
                                            "${stringResource(R.string.screen_douban_item_detail_tab_resources)}(${uiState.searchResults.size})",
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Tab 内容
                    when (selectedTab) {
                        1 -> {
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
                                    FilterSection(
                                        availableSources = uiState.availableSources,
                                        enabledSources = uiState.enabledSources,
                                        customSourceNames = uiState.customSourceNames,
                                        enabledDiskTypes = uiState.enabledDiskTypes,
                                        onToggleSource = { viewModel.toggleSource(it) },
                                        onToggleDiskType = { viewModel.toggleDiskType(it) },
                                        relevanceEnabled = uiState.failure?.title?.isNotBlank() == true,
                                        showHighRelevanceOnly = uiState.showHighRelevanceOnly,
                                        onToggleShowHighRelevanceOnly = { viewModel.toggleShowHighRelevanceOnly() }
                                    )
                                }
                            }
                            when {
                                uiState.isSearching -> {
                                    item(key = "searching") {
                                        Box(modifier = Modifier.alpha(contentAlpha)) { DoubanSearchingState() }
                                    }
                                }
                                // 搜索失败(网络异常等):显错误态+重试,与「真空结果」区分,
                                // 否则失败被空态卡片吞掉,用户误以为没有资源
                                uiState.searchError != null && uiState.searchResults.isEmpty() -> {
                                    item(key = "search_error") {
                                        Box(modifier = Modifier.alpha(contentAlpha)) {
                                            DoubanSearchErrorState(onRetry = { viewModel.searchResources() })
                                        }
                                    }
                                }
                                uiState.searchResults.isEmpty() && uiState.searchAttempted -> {
                                    item(key = "empty") {
                                        Box(modifier = Modifier.alpha(contentAlpha)) { DoubanEmptyState(onRetry = { viewModel.searchResources() }) }
                                    }
                                }
                                else -> {
                                    // 低相关隐藏提示（开启"仅显示高相关"且有被隐藏项时）
                                    if (uiState.showHighRelevanceOnly && uiState.lowRelevanceHiddenCount > 0) {
                                        item(key = "low_relevance_hint") {
                                            Box(
                                                modifier = Modifier
                                                    .alpha(contentAlpha)
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                                    // 这块提示只在「仅显示高相关」开着时出现，点它必然是关掉
                                                    .hapticClickable(semantic = HapticSemantic.TOGGLE_OFF) {
                                                        viewModel.toggleShowHighRelevanceOnly()
                                                    }
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        R.string.detail_hidden_low_relevance,
                                                        uiState.lowRelevanceHiddenCount
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                    itemsIndexed(
                                        items = uiState.searchResults,
                                        key = { _, item -> item.url },
                                        contentType = { _, _ -> "resource" }
                                    ) { index, item ->
                                        ResourceItemCard(
                                            item = item,
                                            sourceName = uiState.customSourceNames[item.source],
                                            isViewed = false,
                                            onClick = {
                                                openResourceLink(context, item)
                                            },
                                            onLongClick = {
                                                copyResourceLink(context, item)
                                            },
                                            index = index
                                        )
                                    }
                                }
                            }
                        }
                        0 -> {
                            // 详情信息 Tab
                            item(key = "info_actions") {
                                Box(modifier = Modifier.alpha(contentAlpha)) {
                                    DoubanDetailInfoTab(
                                        failure = failure,
                                        isLegacyFailure = uiState.isLegacyFailure,
                                        detailInfo = uiState.detailInfo,
                                        detailLoadPhase = uiState.detailLoadPhase,
                                        onOpenDouban = {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(failure.doubanUrl))
                                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            try {
                                                context.startActivity(intent)
                                            } catch (_: ActivityNotFoundException) {
                                                context.showToast(openDoubanToast)
                                            }
                                        },
                                        onRetry = { viewModel.retrySingle() },
                                        onRetryFetch = { viewModel.retryFetchDetail() },
                                        onRetryLoadDetail = { viewModel.retryLoadDetailInfo() },
                                        onCelebrityClick = { url, name ->
                                            webviewUrl = url
                                            webviewTitle = name
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                }
            } else if (uiState.entryNotFound) {
                // 三表全查不到(条目未同步到本机):错误卡片替代自动弹回,复用详情加载失败卡片的样式
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.douban_detail_entry_not_found),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                handleBack()
                            }) {
                                Text(stringResource(R.string.detail_back))
                            }
                        }
                    }
                }
            } else if (uiState.error != null) {
                // 加载错误(VM 存原始异常,组合期转本地化文案)
                val errorMessage = uiState.error?.toUserMessage(context, R.string.error_load_failed).orEmpty()
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
                        OutlinedButton(onClick = { handleBack() }) {
                            Text(stringResource(R.string.douban_retry_cancel))
                        }
                    }
                }
            }
            } // 内容 source Box 结束：只包状态栏底色 + 滚动内容

            // 悬浮控件与全屏覆盖层与 hazeSource 保持兄弟关系（避免落入采样源子树被 Behind
            // 以 zIndex 0<0 过滤掉自身导致模糊静默失效）；LocalBackdrop=null 让 glass 退化到
            // Haze 实时采样，再配合 HazeSourceSelection.All 强制采样内容源，blur/glass 都生效。
            CompositionLocalProvider(LocalBackdrop provides null) {
            // 滚动后淡入的标题栏已并进吸顶 stickyHeader（与 Tab 行共用一次铺底），
            // 这里只剩返回/标记/分享等悬浮圆按钮，正好压在标题行两侧留出的 64dp 上。

            // 返回按钮：使用与正常详情页一致的拟态玻璃和 ultraThin Haze。
            NeumorphicIconButton(
                onClick = { handleBack() },
                isDark = isDarkTheme,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 4.dp)
                    .align(Alignment.TopStart),
                hazeState = hazeState,
                hazeStyle = dev.chrisbanes.haze.blur.materials.HazeMaterials.ultraThin(),
                size = 40.dp,
                buttonStyle = NeumorphicIconButtonStyle.DetailTopBar,
                scene = doubanGlassScene,
                sourceSelection = HazeSourceSelection.All
            ) {
                DetailTopBarIcon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.detail_back),
                    size = 24.dp
                )
            }

            // 右上角按钮组:标记媒体类型 + 分享(独立定位,放在同一 Row)
            Row(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 4.dp)
                    .align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 手动标记媒体类型按钮(下拉菜单)
                val failure = uiState.failure
                // 菜单宽度 = 最长文案（覆盖未标记/已标记两种文案 + 清除标记）+ 图标 + 间隔 + 内边距，多语言适配
                val markMenuLabels = listOf(
                    R.string.screen_douban_failures_mark_as_movie, R.string.screen_douban_failures_marked_as_movie,
                    R.string.screen_douban_failures_mark_as_show, R.string.screen_douban_failures_marked_as_show,
                    R.string.screen_douban_failures_mark_as_variety, R.string.screen_douban_failures_marked_as_variety,
                    R.string.screen_douban_failures_mark_as_documentary, R.string.screen_douban_failures_marked_as_documentary,
                    R.string.screen_douban_failures_clear_mark
                ).map { stringResource(it) }
                val markTextMeasurer = rememberTextMeasurer()
                val markLabelStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
                val markMenuWidth = with(LocalDensity.current) {
                    markMenuLabels.maxOf { markTextMeasurer.measure(AnnotatedString(it), markLabelStyle).size.width }.toDp()
                } + 24.dp + 8.dp + 24.dp
                DropdownAnchorMenu(
                    expanded = showMarkMenu,
                    onDismissRequest = { showMarkMenu = false },
                    menuWidth = markMenuWidth,
                    anchor = {
                        NeumorphicIconButton(
                            onClick = { showMarkMenu = true },
                            isDark = isDarkTheme,
                            hazeState = hazeState,
                            hazeStyle = dev.chrisbanes.haze.blur.materials.HazeMaterials.ultraThin(),
                            size = 40.dp,
                            buttonStyle = NeumorphicIconButtonStyle.DetailTopBar,
                            scene = doubanGlassScene,
                            sourceSelection = HazeSourceSelection.All
                        ) {
                            DetailTopBarIcon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.screen_douban_failures_mark_as_movie)
                            )
                        }
                    }
                ) {
                    val markItemHighlight = Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (failure?.mediaType == "movie") markItemHighlight else Modifier)
                            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                viewModel.setMediaType("movie")
                                showMarkMenu = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (failure?.mediaType == "movie") R.string.screen_douban_failures_marked_as_movie
                                else R.string.screen_douban_failures_mark_as_movie
                            ),
                            style = markLabelStyle
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (failure?.mediaType == "show") markItemHighlight else Modifier)
                            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                viewModel.setMediaType("show")
                                showMarkMenu = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (failure?.mediaType == "show") R.string.screen_douban_failures_marked_as_show
                                else R.string.screen_douban_failures_mark_as_show
                            ),
                            style = markLabelStyle
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (failure?.mediaType == "variety") markItemHighlight else Modifier)
                            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                viewModel.setMediaType("variety")
                                showMarkMenu = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.TheaterComedy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (failure?.mediaType == "variety") R.string.screen_douban_failures_marked_as_variety
                                else R.string.screen_douban_failures_mark_as_variety
                            ),
                            style = markLabelStyle
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (failure?.mediaType == "documentary") markItemHighlight else Modifier)
                            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                viewModel.setMediaType("documentary")
                                showMarkMenu = false
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Nature, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (failure?.mediaType == "documentary") R.string.screen_douban_failures_marked_as_documentary
                                else R.string.screen_douban_failures_mark_as_documentary
                            ),
                            style = markLabelStyle
                        )
                    }
                    if (failure?.mediaType != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                                    viewModel.setMediaType(null)
                                    showMarkMenu = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 与带图标的选项文字对齐
                            Spacer(modifier = Modifier.width(32.dp))
                            Text(
                                text = stringResource(R.string.screen_douban_failures_clear_mark),
                                style = markLabelStyle
                            )
                        }
                    }
                }
                // 分享按钮(分享影视标题、评分、豆瓣链接)
                val shareContext = LocalContext.current
                val shareDoubanRatingLabel = stringResource(R.string.share_douban_rating)
                NeumorphicIconButton(
                    onClick = {
                        val f = uiState.failure
                        if (f != null) {
                            val shareText = buildString {
                                append(f.title)
                                f.rating?.let { append(" - ${shareDoubanRatingLabel}: $it") }
                                if (f.doubanUrl.isNotBlank()) {
                                    append("\n")
                                    append(f.doubanUrl)
                                }
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            shareContext.startActivity(Intent.createChooser(sendIntent, null))
                        }
                    },
                    isDark = isDarkTheme,
                    hazeState = hazeState,
                    hazeStyle = dev.chrisbanes.haze.blur.materials.HazeMaterials.ultraThin(),
                    size = 40.dp,
                    buttonStyle = NeumorphicIconButtonStyle.DetailTopBar,
                    scene = doubanGlassScene,
                    sourceSelection = HazeSourceSelection.All
                ) {
                    DetailTopBarIcon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.detail_share)
                    )
                }
            }
            }

            // 资源搜索 Tab 快速回顶按钮(仅资源搜索 Tab 显示,详情信息 Tab 内容少不需要)
            if (selectedTab == 1) {
                ScrollToTopButton(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp),
                    hazeState = hazeState,
                    sourceSelection = HazeSourceSelection.All,
                    scene = doubanGlassScene
                )
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
                    // AlertDialog 的槽是独立 subcomposition，单独取一份
                    val confirmHaptics = rememberAppHaptics()
                    TextButton(onClick = {
                        confirmHaptics.tap()
                        viewModel.updateSubtitle(failure.doubanId, subtitleInput)
                        viewModel.showSubtitleDialog(false)
                    }) {
                        Text(stringResource(R.string.douban_sync_mode_confirm))
                    }
                },
                dismissButton = {
                    val dismissHaptics = rememberAppHaptics()
                    TextButton(onClick = { dismissHaptics.lightTap(); viewModel.showSubtitleDialog(false) }) {
                        Text(stringResource(R.string.douban_retry_cancel))
                    }
                }
            )
        }
    }

    // 取消豆瓣标记二次确认弹窗(删除收藏后会移除该失败条目)
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.douban_writeback_remove_confirm_title)) },
            text = { Text(stringResource(R.string.douban_writeback_remove_confirm_text)) },
            confirmButton = {
                val confirmHaptics = rememberAppHaptics()
                TextButton(
                    onClick = {
                        confirmHaptics.tap()
                        showRemoveConfirm = false
                        viewModel.removeMark()
                    }
                ) {
                    Text(stringResource(R.string.douban_writeback_remove_confirm_yes))
                }
            },
            dismissButton = {
                val dismissHaptics = rememberAppHaptics()
                TextButton(onClick = { dismissHaptics.lightTap(); showRemoveConfirm = false }) {
                    Text(stringResource(R.string.douban_retry_cancel))
                }
            }
        )
    }

    // 演职员头像 → 应用内 WebView(注入豆瓣 cookie)
    val url = webviewUrl
    if (url != null) {
        Dialog(
            onDismissRequest = { webviewUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // Dialog 的内容是独立 subcomposition（有自己的宿主 View），单独取一份
            val webviewHaptics = rememberAppHaptics()
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            text = webviewTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        // 这个 ← 收的是盖在详情页上的一层全屏浮层，不弹导航栈，
                        // 按「对话框的关闭」给 lightTap()；同一个 Dialog 的
                        // onDismissRequest（系统返回手势）仍然静默
                        IconButton(onClick = { webviewHaptics.lightTap(); webviewUrl = null }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.detail_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            CookieManager.getInstance().setAcceptCookie(true)
                            // 安全：禁用第三方 Cookie，演职员页只需 first-party cookie
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            // 注入豆瓣 cookie
                            val cookie = viewModel.getDoubanCookie()
                            if (cookie != null) {
                                CookieManager.getInstance().setCookie("https://movie.douban.com", cookie)
                                CookieManager.getInstance().flush()
                            }
                            // 安全：域名白名单，只允许豆瓣域内跳转，其他域用外置浏览器打开
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val host = request?.url?.host ?: return true
                                    return if (host.endsWith("douban.com")) {
                                        false // 豆瓣域内允许加载
                                    } else {
                                        // 非豆瓣域用外置浏览器打开，阻止 WebView 加载
                                        request.url?.let { uri ->
                                            runCatching {
                                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                view?.context?.startActivity(intent)
                                            }
                                        }
                                        true
                                    }
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

// ==================== 头部区域 ====================

/**
 * 豆瓣标记双向写回操作栏(详情页内容区顶部)。
 * 提供「想看 / 已看 / 取消」三个动作;电影无「在看」故不显示。
 * 当前已标记状态:对应按钮显示"已想看"/"已看过"(选中态),点击不触发操作;
 * 取消标记按钮使用红色警示态。
 * 样式与详情页 ActionButtonRow 统一。
 */
@Composable
private fun DoubanWritebackActions(
    failure: DoubanSyncFailure,
    marking: Boolean,
    hazeState: dev.chrisbanes.haze.HazeState,
    onWish: () -> Unit,
    onCollect: () -> Unit,
    onRemove: () -> Unit
) {
    val status = failure.status
    val isWish = status == DoubanMarkStatus.WISH
    val isCollect = status == DoubanMarkStatus.COLLECT

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.douban_writeback_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        ActionButtonRow(
            actions = listOf(
                ActionItem(
                    icon = if (isWish) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    label = stringResource(if (isWish) R.string.douban_writeback_wished else R.string.douban_writeback_wish),
                    selected = isWish,
                    enabled = !marking,
                    isLoading = marking && isWish,
                    onClick = { if (!isWish) onWish() }
                ),
                ActionItem(
                    icon = if (isCollect) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    label = stringResource(if (isCollect) R.string.douban_writeback_collected else R.string.douban_writeback_collect),
                    selected = isCollect,
                    enabled = !marking,
                    isLoading = marking && isCollect,
                    onClick = { if (!isCollect) onCollect() }
                ),
                ActionItem(
                    icon = Icons.Rounded.Delete,
                    label = stringResource(R.string.douban_writeback_remove),
                    enabled = !marking,
                    isLoading = marking && !isWish && !isCollect,
                    isDestructive = true,
                    onClick = onRemove
                )
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DoubanItemHeader(
    failure: DoubanSyncFailure,
    isLegacyFailure: Boolean,
    detailInfo: DoubanDetailInfo?,
    posterColor: Color?,
    onSubtitleClick: () -> Unit,
    posterColorExtractor: PosterColorExtractor,
    onPosterColorExtracted: (Color) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 豆瓣海报 URL 没有 TMDB 尺寸段：swapSize 对非 /t/p/ 结构原样返回，大图与小图同一 URL
    val posterViewerItems = remember(failure.posterUrl) {
        failure.posterUrl?.let { listOf(OpenImageViewerItem(largeUrl = it, coverUrl = it)) } ?: emptyList()
    }
    // 海报矩形表：单图固定下标 0，供 OpenImage 打开/返回动画落点
    val posterBounds = rememberOpenImageBounds()
    val activity = context as? Activity
    // 点击海报直接打开查看器；拿不到 Activity（如预览环境）时不响应
    val openPosterViewer: () -> Unit = {
        val currentActivity = activity
        if (currentActivity != null && posterViewerItems.isNotEmpty()) {
            openImageViewer(
                activity = currentActivity,
                items = posterViewerItems,
                bounds = posterBounds,
                clickedIndex = 0
            )
        }
    }
    // 沉浸背景下的自适应文字色，判据收在 DetailVisuals（与普通详情页共用同一套）
    val onPosterColor = detailOnPosterColor(posterColor)
    val onPosterVariantColor = detailOnPosterVariantColor(posterColor)
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
            // 顶部留白让位给淡入标题栏（状态栏 padding 由 LazyColumn 统一处理）。
            // 原先是 64dp 魔数「模拟 TopAppBar 高度」，实际本页并没有 TopAppBar：
            // 顶部只有 40dp 悬浮按钮 + 4dp 上边距，56dp 刚好留出一行的量。
            .padding(top = 56.dp)
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
                    // 海报即查看器缩略图：记录 window 矩形，点击后以此矩形做转场落点
                    .recordOpenImageBounds(0, posterBounds)
                    .then(
                        if (failure.posterUrl != null) {
                            Modifier.hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { openPosterViewer() }
                        } else Modifier
                    )
            ) {
                if (failure.posterUrl != null) {
                    AsyncImage(
                        model = remember(failure.posterUrl) {
                            ImageRequest.Builder(context)
                                .data(failure.posterUrl)
                                .size(360)
                                .listener(
                                    onSuccess = { _, result ->
                                        // 图片加载成功后提取主色调,用于沉浸式背景渐变
                                        // 外层已判空且 failure 为 val 参数,posterUrl 在此非 null
                                        scope.launch {
                                            // 位图解码拷贝移出主线程,避免进详情帧卡顿
                                            val bitmap = withContext(Dispatchers.Default) { result.drawable.toBitmap() }
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
                        modifier = Modifier
                            .fillMaxSize()
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 原先是 Text("🎬")：读屏念不出来，字形还跟系统 emoji 字体走。
                        // 与普通详情页无海报时的图标占位统一。
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                // 原先是纯 surfaceVariant 圆角块 + 一行文字，没有任何可点提示，
                // 半透明底色反而更像禁用态。加描边 + 尾部铅笔图标明确「这里能改」。
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .hapticClickable(semantic = HapticSemantic.LIGHT_TAP) { onSubtitleClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
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
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.screen_douban_item_detail_subtitle_edit),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 豆瓣评分(10 分制,详情页抓取)
                if (detailInfo?.doubanRating != null) {
                    DoubanRatingRow(
                        rating = detailInfo.doubanRating,
                        ratingCount = detailInfo.ratingCount,
                        textColor = onPosterVariantColor
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // 我的评分(5★,用户标记评分)
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
                    text = listOfNotNull(failure.markedAt.takeIf { it.isNotBlank() }, statusText)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = onPosterVariantColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 占位将失败原因栏推到底部,与海报底部对齐
                Spacer(modifier = Modifier.weight(1f))

                // 历史技术失败才展示失败原因；正常同步条目不出现失败项概念。
                if (isLegacyFailure) {
                    DoubanFailureBanner(
                        failure = failure,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    text = stringResource(R.string.douban_detail_comment_quote, failure.comment),
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
            // 空星用描边图标与实星区分,不再依赖 alpha 区分
            Icon(
                imageVector = if (i <= rating) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = null,
                tint = if (i <= rating) RatingGold else textColor,
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

/**
 * 豆瓣评分行(10 分制)。
 *
 * 展示格式: `豆瓣 9.2 ★★★★☆ (12,345人评价)`
 * - 评分数字加粗突出
 * - 5 星按 10 分制映射(每 2 分一星,半星精度用 alpha 区分)
 * - 评分人数可选,为 null 时只显示评分+星级
 */
@Composable
private fun DoubanRatingRow(rating: Double, ratingCount: Int?, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.detail_info_douban_rating),
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.8f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = String.format(LocalLocale.current.platformLocale, "%.1f", rating),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = RatingGold
        )
        Spacer(modifier = Modifier.width(4.dp))
        // 5 星按 10 分制映射:每星 2 分,半星用 alpha 0.5 区分
        val filledStars = (rating / 2.0).toInt()
        val hasHalf = (rating / 2.0) - filledStars >= 0.5
        for (i in 1..5) {
            val tint = when {
                i <= filledStars -> RatingGold
                i == filledStars + 1 && hasHalf -> RatingGold.copy(alpha = 0.5f)
                else -> textColor.copy(alpha = 0.3f)
            }
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
        }
        if (ratingCount != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.detail_info_rating_count, ratingCount),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f)
            )
        }
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
    FailureReason.TRAKT_SEARCH_FAILED -> stringResource(R.string.douban_failure_reason_trakt_search_failed)
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
    val haptics = rememberAppHaptics()
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = if (failure.subtitle != null && searchWithSubtitle)
                "${failure.title} + ${failure.subtitle}"
            else
                failure.title,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current,
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
                    color = LocalContentColor.current,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = searchWithSubtitle,
                    onCheckedChange = { checked ->
                        haptics.toggle(checked)
                        onToggleSearchWithSubtitle()
                    }
                )
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
    val haptics = rememberAppHaptics()
    // 裸排不带卡片：玻璃卡片会读作「一张内容为空的资源卡」而不是「搜索没有结果」
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Movie,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.screen_douban_item_detail_no_resources),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = {
            haptics.tap()
            onRetry()
        }) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.detail_retry))
        }
    }
}

/** 资源搜索失败态(与空结果区分):错误图标+「搜索失败」+重试 */
@Composable
private fun DoubanSearchErrorState(onRetry: () -> Unit) {
    val haptics = rememberAppHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.error_search_failed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = {
            haptics.tap()
            onRetry()
        }) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.detail_retry))
        }
    }
}

// ==================== 详情信息 Tab ====================

@Composable
private fun DoubanDetailInfoTab(
    failure: DoubanSyncFailure,
    isLegacyFailure: Boolean,
    detailInfo: DoubanDetailInfo?,
    detailLoadPhase: DetailLoadPhase,
    onOpenDouban: () -> Unit,
    onRetry: () -> Unit,
    onRetryFetch: () -> Unit,
    onRetryLoadDetail: () -> Unit,
    onCelebrityClick: (url: String, name: String) -> Unit
) {
    val haptics = rememberAppHaptics()
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        // 操作按钮区
        // 仅可恢复原因才显示「重新尝试同步」(单独一行)
        if (isLegacyFailure && failure.failureReason.recoverable) {
            Button(
                onClick = {
                    haptics.tap()
                    onRetry()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.screen_douban_item_detail_retry))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 打开豆瓣页面；只有历史技术失败才显示重新爬取按钮。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    onOpenDouban()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.screen_douban_item_detail_open_douban))
            }
            if (isLegacyFailure) {
                OutlinedButton(
                    onClick = {
                        haptics.tap()
                        onRetryFetch()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.screen_douban_item_detail_refetch))
                }
            }
        }

        // 剧情简介：原先埋在信息 Tab 最底部（13 行 MetaRow 之后），
        // 且用裸 Text 全量展开，长简介把评分分布和演职员整段推到屏幕外。
        // 现上移到操作按钮下方第一张卡，正文换详情页共用的 ExpandableText 折叠 4 行。
        if (detailInfo?.summary != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.detail_info_summary),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpandableText(text = detailInfo.summary, maxLines = 4)
                }
            }
        }

        if (isLegacyFailure) {
            Spacer(modifier = Modifier.height(16.dp))

            // 历史技术失败才显示失败元信息。
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 失败时间(左) + 尝试次数(右) 同一行
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // 左:失败时间
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.screen_douban_item_detail_meta_failed_at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = formatTimestamp(failure.failedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        // 右:尝试次数(右对齐)
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = stringResource(R.string.screen_douban_item_detail_meta_attempt_count),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = failure.attemptCount.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.End
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    MetaRow(
                        label = stringResource(R.string.screen_douban_item_detail_meta_douban_id),
                        value = failure.doubanId,
                        copyable = true
                    )
                }
            }

            // 历史失败的详情加载状态(正常同步条目不显示失败/重试语义)。
            if (detailInfo == null) {
                when (detailLoadPhase) {
                DetailLoadPhase.FETCHING -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.douban_detail_fetching),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                DetailLoadPhase.FAILED -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.douban_detail_fetch_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = {
                                haptics.tap()
                                onRetryLoadDetail()
                            }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.douban_detail_retry))
                            }
                        }
                    }
                }
                else -> {
                    // IDLE / DONE 且 detailInfo == null:静默不显示
                }
            }
        }
        }

        // 详情字段卡片(仅在 detailInfo 不为空且有可展示字段时显示)
        if (detailInfo != null) {
            val hasAnyField = detailInfo.year != null
                || detailInfo.countries.isNotEmpty()
                || detailInfo.directors.isNotEmpty()
                || detailInfo.genres.isNotEmpty()
                || !detailInfo.imdbId.isNullOrBlank()
                || detailInfo.aka.isNotEmpty()
                || !detailInfo.runtime.isNullOrBlank()
                || detailInfo.episodeCount != null
                || !detailInfo.episodeDuration.isNullOrBlank()
                || detailInfo.writers.isNotEmpty()
                || detailInfo.cast.isNotEmpty()
                || detailInfo.languages.isNotEmpty()
                || detailInfo.initialReleaseDates.isNotEmpty()
            if (hasAnyField) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (!detailInfo.imdbId.isNullOrBlank()) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_imdb_id),
                                value = detailInfo.imdbId,
                                copyable = true
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (detailInfo.year != null) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_year),
                                value = detailInfo.year
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (detailInfo.genres.isNotEmpty()) {
                            MetaChipRow(
                                label = stringResource(R.string.detail_info_genres),
                                values = detailInfo.genres
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (detailInfo.countries.isNotEmpty()) {
                            MetaChipRow(
                                label = stringResource(R.string.detail_info_countries),
                                values = detailInfo.countries
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (detailInfo.directors.isNotEmpty()) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_directors),
                                value = detailInfo.directors.joinToString(" / ")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 又名(长内容用 Column 布局)
                        if (detailInfo.aka.isNotEmpty()) {
                            MetaColumn(
                                label = stringResource(R.string.detail_info_aka),
                                value = detailInfo.aka.joinToString(" / ")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 片长(电影)
                        if (!detailInfo.runtime.isNullOrBlank()) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_runtime),
                                value = detailInfo.runtime
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 集数(电视剧)
                        if (detailInfo.episodeCount != null) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_episode_count),
                                value = detailInfo.episodeCount.toString()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 单集片长(电视剧)
                        if (!detailInfo.episodeDuration.isNullOrBlank()) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_episode_duration),
                                value = detailInfo.episodeDuration
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 编剧
                        if (detailInfo.writers.isNotEmpty()) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_writers),
                                value = detailInfo.writers.joinToString(" / ")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 主演(长内容用 Column 布局)
                        if (detailInfo.cast.isNotEmpty()) {
                            MetaColumn(
                                label = stringResource(R.string.detail_info_cast),
                                value = detailInfo.cast.joinToString(" / ")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 语言
                        if (detailInfo.languages.isNotEmpty()) {
                            MetaChipRow(
                                label = stringResource(R.string.detail_info_languages),
                                values = detailInfo.languages
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        // 首播日期(长内容用 Column 布局)
                        if (detailInfo.initialReleaseDates.isNotEmpty()) {
                            MetaColumn(
                                label = stringResource(R.string.detail_info_initial_release_dates),
                                value = detailInfo.initialReleaseDates.joinToString(" / ")
                            )
                        }
                    }
                }
            }
        }

        // 评分分布卡片(5星→1星条形图)
        if (detailInfo?.ratingDistribution?.isNotEmpty() == true) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.detail_info_rating_distribution),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val labels = listOf("5★", "4★", "3★", "2★", "1★")
                    val maxPct = detailInfo.ratingDistribution.maxOrNull() ?: 1.0
                    detailInfo.ratingDistribution.forEachIndexed { index, pct ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = labels.getOrElse(index) { "" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            ) {
                                val fraction = if (maxPct > 0) (pct / maxPct).toFloat() else 0f
                                // 原先 5 档同一个 primary，条形图只剩长度一个维度。
                                // 按档位取色后「好评压倒差评」一眼可读：高分金、中性、低分弱化。
                                val barColor = when (index) {
                                    0 -> RatingGold
                                    1 -> RatingGoldDim
                                    2 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    3 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(barColor)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${pct.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(36.dp)
                            )
                        }
                    }
                }
            }
        }

        // 演职员卡片(横向滚动头像列表)
        if (detailInfo?.celebrities?.isNotEmpty() == true) {
            Spacer(modifier = Modifier.height(12.dp))
            // 这行标题直接画在沉浸渐变上（不在卡片里），故走 LocalContentColor 自适应，
            // 与普通详情页各栏目标题同一种画法
            DetailSectionHeader(
                title = stringResource(R.string.detail_info_celebrities),
                modifier = Modifier.padding(start = 4.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(detailInfo.celebrities) { celebrity ->
                    // 尺寸对齐普通详情页 CastCard：68dp 列 + 68×95 海报比例头像 + 6dp 圆角。
                    // 不直接复用 CastCard：它按 TMDB personId 建共享元素 key 并写
                    // PersonAvatarColorStore，豆瓣演职员只有 url + 名字，套进去会让所有人
                    // 共用同一个 key 并污染头像主色缓存。
                    Column(
                        modifier = Modifier
                            .width(68.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (!celebrity.doubanPersonageUrl.isNullOrBlank()) {
                                        haptics.lightTap()
                                        onCelebrityClick(celebrity.doubanPersonageUrl, celebrity.name)
                                    }
                                }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .width(68.dp)
                                .height(95.dp)
                        ) {
                            if (!celebrity.avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(celebrity.avatarUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = celebrity.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.TheaterComedy,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = celebrity.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        if (!celebrity.role.isNullOrBlank()) {
                            Text(
                                text = celebrity.role,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单行元信息。
 *
 * 两处修正：
 * - 值原先一律 `maxLines = 1` 截断，导演/编剧这类多人字段基本必截，想看全得回豆瓣。
 *   放到 2 行，仍然截断但覆盖绝大多数字段。
 * - [copyable] 原先只是「点了会复制」，界面上毫无提示，只能靠试。补一枚 12dp 复制图标。
 */
@Composable
private fun MetaRow(label: String, value: String, copyable: Boolean = false) {
    val context = LocalContext.current
    val copiedToast = stringResource(R.string.screen_douban_item_detail_copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (copyable) Modifier.hapticClickable(semantic = HapticSemantic.TAP) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                    context.showToast(copiedToast)
                } else Modifier
            ),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        )
        if (copyable) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.screen_douban_item_detail_copied),
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 元信息 + 胶囊行。
 *
 * 类型 / 国家 / 语言原先是 `joinToString(" / ")` 拼成一行再 `maxLines = 1` 截断——
 * 「剧情 / 犯罪 / 悬疑 / 惊悚」基本只看得到前两个。拆成胶囊后装不下自然换行。
 */
@Composable
private fun MetaChipRow(label: String, values: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        DetailMetaChips(
            chips = values.map { DetailMetaChip(text = it, description = "$label $it") },
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 长内容字段(又名/主演/首播日期)用 Column 布局,标题在上(黑体),内容在下自然换行 */
@Composable
private fun MetaColumn(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
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
