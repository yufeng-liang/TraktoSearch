package com.tracktosearch.ui.screen.douban

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Nature
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.ui.component.ActionButtonRow
import com.tracktosearch.ui.component.ActionItem
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.screen.detail.PosterFullscreenOverlay
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.copyResourceLink
import com.tracktosearch.ui.util.openResourceLink
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.ToastEffect
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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
    val posterColorExtractor: PosterColorExtractor
) : ViewModel() {

    private val _uiState = MutableStateFlow(DoubanItemDetailUiState())
    val uiState: StateFlow<DoubanItemDetailUiState> = _uiState.asStateFlow()

    // 一次性 Toast 事件(传 R.string 资源 ID),用 extraBufferCapacity 避免背压丢消息
    private val _toastEvent = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val toastEvent: SharedFlow<Int> = _toastEvent.asSharedFlow()

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
                    // 默认开启「同时用子标题搜索」(subtitle 非空,或 title 含 "/" 可拆出外文标题时生效)
                    searchWithSubtitle = !failure.subtitle.isNullOrBlank() ||
                        failure.title.split("/").map { it.trim() }.filter { it.isNotBlank() }.size > 1
                )
                // 尽早从缓存预查海报主色,让沉浸背景在海报图片加载前显示
                prefetchPosterColor(failure.posterUrl)
                // 异步加载豆瓣详情补充信息(年份/国家/导演/类型),命中缓存秒回
                loadDetailInfo(failure)
                // 资源搜索延迟到用户切换到资源搜索 Tab 时才触发,避免进入页面瞬间 12+ 并发网络请求
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "unknown error"
                )
            }
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
        val failure = _uiState.value.failure ?: return
        loadDetailInfo(failure)
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
            if (subtitle != null && subtitle.isNotBlank() && _uiState.value.searchWithSubtitle) {
                val subNoSeason = removeSeasonInfo(subtitle)
                keywords.add(subtitle)
                if (subNoSeason != subtitle) {
                    keywords.add(subNoSeason)
                }
            }

            // forceRefresh=true 时清缓存
            if (forceRefresh) {
                keywords.forEach { kw ->
                    resourceRepository.refreshResources(
                        keyword = kw,
                        enabledSources = storageEnabledSources,
                        enabledDiskTypes = _uiState.value.enabledDiskTypes,
                        isShow = isShow
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
                        isShow = isShow
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
                    // 排序：含季信息的结果排前面（与查询关键词的「含季版本」匹配的结果更靠前）
                    val sorted = sortResourcesBySeasonPresence(filtered)
                    _uiState.value = _uiState.value.copy(
                        searchResults = sorted,
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchAttempted = true,
                    searchError = e.message
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

    /**
     * 排序：标题/资源名里含「第x季」或「season x」的项排在前面。
     * 含季信息的项内部保持原顺序（来自搜索结果顺序），不含季的项也保持原顺序。
     */
    private fun sortResourcesBySeasonPresence(items: List<ResourceItem>): List<ResourceItem> {
        val withSeason = mutableListOf<ResourceItem>()
        val withoutSeason = mutableListOf<ResourceItem>()
        val seasonRegex = Regex("""第\s*[一二三四五六七八九十0-9]+\s*季|(?i)\bseason\s*\d+\b""")
        for (item in items) {
            if (seasonRegex.containsMatchIn(item.name)) {
                withSeason.add(item)
            } else {
                withoutSeason.add(item)
            }
        }
        return withSeason + withoutSeason
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

    /** 获取豆瓣 cookie(供 WebView 注入用) */
    fun getDoubanCookie(): String? = doubanAuthStorage.getCredentials()?.cookie

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

    /** 强制重新爬取豆瓣详情(跳过缓存和全局池,直接爬取豆瓣) */
    fun retryFetchDetail() {
        val current = _uiState.value.failure ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(detailLoadPhase = DetailLoadPhase.FETCHING)
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
            } ?: run {
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
            } catch (_: Exception) {
                _toastEvent.tryEmit(R.string.douban_detail_mark_failed)
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
                val res = doubanRepository.markInterest(status.path, failure.doubanId, credentials.cookie, ck)
                if (res.success) {
                    doubanRetryManager.updateStatus(failure.doubanId, status)
                    val refreshed = doubanRetryManager.getFailure(failure.doubanId)
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
                    _uiState.value = _uiState.value.copy(marking = false, failure = null)
                    MarkWriteOutcome.Removed
                } else {
                    _uiState.value = _uiState.value.copy(marking = false)
                    MarkWriteOutcome.Failed(res.message)
                }
            }.getOrDefault(MarkWriteOutcome.Failed(null))
            emitWritebackToast(result)
        }
    }

    /** 将写回结果映射为 Toast 文案资源 ID 并发事件 */
    private fun emitWritebackToast(outcome: MarkWriteOutcome) {
        val resId = when (outcome) {
            MarkWriteOutcome.Success -> R.string.douban_writeback_marked_success
            MarkWriteOutcome.Removed -> R.string.douban_writeback_removed
            MarkWriteOutcome.LoginRequired -> R.string.douban_writeback_login_required
            MarkWriteOutcome.CookieExpired -> R.string.douban_writeback_cookie_expired
            MarkWriteOutcome.CkFailed -> R.string.douban_writeback_ck_failed
            is MarkWriteOutcome.Failed -> R.string.douban_writeback_failed
        }
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
    val openDoubanToast = stringResource(R.string.screen_douban_item_detail_open_douban)
    val copiedToast = stringResource(R.string.screen_douban_item_detail_copied)
    val view = LocalView.current
    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showPosterFullscreen by remember { mutableStateOf(false) }
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
    val contentAlpha = if (contentReady) 1f else 0f

    // 拦截系统返回手势
    BackHandler(enabled = true) {
        onBack()
    }

    // doubanId 变化时重新加载
    LaunchedEffect(doubanId) {
        viewModel.loadFailure(doubanId)
    }

    // 收集一次性 Toast 事件(爬取成功/标注成功/失败提示)
    ToastEffect(viewModel.toastEvent)

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

            // Tab 栏底色/文字颜色计算(在 LazyColumn 之外定义,让状态栏区域也能用)
            // - 非吸顶(tab 还在海报下方):底色透明,文字按渐变中段混合色亮度自适应
            // - 吸顶(tab 滚动到顶部固定):底色为沉浸色与白色 0.635 混合,文字按底色亮度自适应
            val isPinned by remember {
                derivedStateOf { listState.firstVisibleItemIndex >= 1 }
            }
            val tabContainerColor = if (isPinned) {
                uiState.posterDominantColor?.let { c ->
                    lerp(MaterialTheme.colorScheme.background, c, 0.635f)
                } ?: MaterialTheme.colorScheme.surface
            } else {
                Color.Transparent
            }
            val tabContentColor = when {
                isPinned && tabContainerColor.luminance() <= 0.5f -> MaterialTheme.colorScheme.onPrimary
                !isPinned -> {
                    // 非吸顶时 tab 在渐变中段,用该位置混合色亮度判断文字颜色
                    val midColor = uiState.posterDominantColor?.let { c ->
                        lerp(c, MaterialTheme.colorScheme.background, 0.8f)
                    } ?: MaterialTheme.colorScheme.background
                    if (midColor.luminance() <= 0.5f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                }
                else -> MaterialTheme.colorScheme.onSurface
            }

            // 吸顶时状态栏区域背景与 tabContainerColor 一致,非吸顶透明(透出渐变)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(tabContainerColor)
                    .align(Alignment.TopCenter)
            )

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
                        .statusBarsPadding()
                        .hazeSource(state = hazeState),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    // 头部区域:海报 + 标题 + 子标题 + 豆瓣评分 + 我的评分 + 标记信息 + 短评
                    item(key = "header") {
                        DoubanItemHeader(
                            failure = failure,
                            detailInfo = uiState.detailInfo,
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

                    // Tab 行(吸顶) —
                    // - 非吸顶(tab 还在海报下方):透明
                    // - 吸顶:沉浸色与白色 0.5f 混合,文字按底色亮度自适应
                    stickyHeader(key = "tab_row") {
                        // isPinned / tabContainerColor / tabContentColor 在 LazyColumn 外已计算
                        PrimaryTabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = tabContainerColor,
                            contentColor = tabContentColor,
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
                                        stringResource(R.string.screen_douban_item_detail_tab_info),
                                        maxLines = 1
                                    )
                                }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = {
                                    view.performHaptic(HapticType.CLICK)
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
                        0 -> {
                            // 详情信息 Tab
                            item(key = "info_actions") {
                                Box(modifier = Modifier.alpha(contentAlpha)) {
                                    DoubanDetailInfoTab(
                                        failure = failure,
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

            // 返回按钮(独立定位,半透明圆形背景 + Haze 模糊,参考正常详情页)
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 4.dp)
                    .align(Alignment.TopStart)
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
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.detail_back),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
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
                            imageVector = Icons.Rounded.Edit,
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
                            text = {
                                Text(
                                    stringResource(
                                        if (failure?.mediaType == "movie") R.string.screen_douban_failures_marked_as_movie
                                        else R.string.screen_douban_failures_mark_as_movie
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.Movie, contentDescription = null) },
                            modifier = if (failure?.mediaType == "movie") Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier,
                            onClick = {
                                viewModel.setMediaType("movie")
                                showMarkMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (failure?.mediaType == "show") R.string.screen_douban_failures_marked_as_show
                                        else R.string.screen_douban_failures_mark_as_show
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.Tv, contentDescription = null) },
                            modifier = if (failure?.mediaType == "show") Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier,
                            onClick = {
                                viewModel.setMediaType("show")
                                showMarkMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (failure?.mediaType == "variety") R.string.screen_douban_failures_marked_as_variety
                                        else R.string.screen_douban_failures_mark_as_variety
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.TheaterComedy, contentDescription = null) },
                            modifier = if (failure?.mediaType == "variety") Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier,
                            onClick = {
                                viewModel.setMediaType("variety")
                                showMarkMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (failure?.mediaType == "documentary") R.string.screen_douban_failures_marked_as_documentary
                                        else R.string.screen_douban_failures_mark_as_documentary
                                    )
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.Nature, contentDescription = null) },
                            modifier = if (failure?.mediaType == "documentary") Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier,
                            onClick = {
                                viewModel.setMediaType("documentary")
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
                // 分享按钮(分享影视标题、评分、豆瓣链接)
                val shareContext = LocalContext.current
                val shareDoubanRatingLabel = stringResource(R.string.share_douban_rating)
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
                                    shareContext.startActivity(
                                        Intent.createChooser(sendIntent, null)
                                    )
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Share,
                        contentDescription = stringResource(R.string.detail_share),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 海报大图查看(放在最后绘制,关闭/保存按钮不被顶部按钮遮住)
            val posterUrl = failure?.posterUrl
            if (showPosterFullscreen && posterUrl != null) {
                PosterFullscreenOverlay(
                    posterUrl = posterUrl,
                    title = failure.title,
                    onDismiss = { showPosterFullscreen = false }
                )
            }

            // 资源搜索 Tab 快速回顶按钮(仅资源搜索 Tab 显示,详情信息 Tab 内容少不需要)
            if (selectedTab == 1) {
                ScrollToTopButton(
                    listState = listState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 16.dp),
                    hazeState = hazeState
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

    // 取消豆瓣标记二次确认弹窗(删除收藏后会移除该失败条目)
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            title = { Text(stringResource(R.string.douban_writeback_remove_confirm_title)) },
            text = { Text(stringResource(R.string.douban_writeback_remove_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirm = false
                        viewModel.removeMark()
                    }
                ) {
                    Text(stringResource(R.string.douban_writeback_remove_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
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
                        IconButton(onClick = { webviewUrl = null }) {
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
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // 注入豆瓣 cookie
                            val cookie = viewModel.getDoubanCookie()
                            if (cookie != null) {
                                CookieManager.getInstance().setCookie("https://movie.douban.com", cookie)
                                CookieManager.getInstance().flush()
                            }
                            webViewClient = WebViewClient()
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
@OptIn(dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class)
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
            hazeState = hazeState,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DoubanItemHeader(
    failure: DoubanSyncFailure,
    detailInfo: DoubanDetailInfo?,
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
            // 顶部留白 = TopAppBar 高度(64dp),状态栏 padding 由 LazyColumn 统一处理
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
                imageVector = if (i <= rating) Icons.Rounded.Star else Icons.Rounded.Star,
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
            color = Color(0xFFFFC107)
        )
        Spacer(modifier = Modifier.width(4.dp))
        // 5 星按 10 分制映射:每星 2 分,半星用 alpha 0.5 区分
        val filledStars = (rating / 2.0).toInt()
        val hasHalf = (rating / 2.0) - filledStars >= 0.5
        for (i in 1..5) {
            val tint = when {
                i <= filledStars -> Color(0xFFFFC107)
                i == filledStars + 1 && hasHalf -> Color(0xFFFFC107).copy(alpha = 0.5f)
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
                        border = if (source in enabledSources) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
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
                        border = if (type in enabledDiskTypes) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
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
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
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
    detailInfo: DoubanDetailInfo?,
    detailLoadPhase: DetailLoadPhase,
    onOpenDouban: () -> Unit,
    onRetry: () -> Unit,
    onRetryFetch: () -> Unit,
    onRetryLoadDetail: () -> Unit,
    onCelebrityClick: (url: String, name: String) -> Unit
) {
    val view = LocalView.current
    val context = LocalContext.current
    Column(modifier = Modifier.padding(16.dp)) {
        // 操作按钮区
        // 仅可恢复原因才显示「重新尝试同步」(单独一行)
        if (failure.failureReason.recoverable) {
            Button(
                onClick = {
                    view.performHaptic(HapticType.CLICK)
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

        // 打开豆瓣页面 + 重新爬取(同一行分散对齐)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    view.performHaptic(HapticType.CLICK)
                    onOpenDouban()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.screen_douban_item_detail_open_douban))
            }
            OutlinedButton(
                onClick = {
                    view.performHaptic(HapticType.CLICK)
                    onRetryFetch()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.screen_douban_item_detail_refetch))
            }
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

        // 豆瓣详情加载状态(仅 detailInfo == null 时显示,有详情时字段卡片自身展示)
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
                                view.performHaptic(HapticType.CLICK)
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
                                label = "IMDb ID",
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
                            MetaRow(
                                label = stringResource(R.string.detail_info_genres),
                                value = detailInfo.genres.joinToString(" / ")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (detailInfo.countries.isNotEmpty()) {
                            MetaRow(
                                label = stringResource(R.string.detail_info_countries),
                                value = detailInfo.countries.joinToString(" / ")
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
                            MetaRow(
                                label = stringResource(R.string.detail_info_languages),
                                value = detailInfo.languages.joinToString(" / ")
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

        // 剧情简介独立卡片
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
                    Text(
                        text = detailInfo.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary)
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
            Text(
                text = stringResource(R.string.detail_info_celebrities),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(detailInfo.celebrities) { celebrity ->
                    Column(
                        modifier = Modifier
                            .width(72.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (!celebrity.doubanPersonageUrl.isNullOrBlank()) {
                                        view.performHaptic(HapticType.CLICK)
                                        onCelebrityClick(celebrity.doubanPersonageUrl, celebrity.name)
                                    }
                                }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                                        modifier = Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = celebrity.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!celebrity.role.isNullOrBlank()) {
                            Text(
                                text = celebrity.role,
                                style = MaterialTheme.typography.labelSmall,
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
private fun MetaRow(label: String, value: String, copyable: Boolean = false) {
    val context = LocalContext.current
    val copiedToast = stringResource(R.string.screen_douban_item_detail_copied)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (copyable) Modifier.clickable {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
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
