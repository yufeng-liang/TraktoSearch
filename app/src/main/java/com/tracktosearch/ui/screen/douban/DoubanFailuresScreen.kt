package com.tracktosearch.ui.screen.douban

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Nature
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.repository.DoubanFailureExporter
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.data.repository.ImportResult
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/** 标记时间区间预设 */
enum class MarkedTimePreset { SEVEN_DAYS, THIRTY_DAYS, ALL }

/** 标记时间排序方式 */
enum class SortOrder { ASC, DESC }

/**
 * 豆瓣同步失败项查看页 ViewModel。
 *
 * 从 [DoubanRetryManager] 加载全部失败项,内存中按 status(WISH/COLLECT) × mediaType(movie/show/null) 分组,
 * UI 通过 selectedMode × selectedTab 索引展示对应子集。
 * 支持搜索(标题/副标题)+ 筛选(失败原因多选 + 标记时间区间 + 排序)。
 */
@HiltViewModel
class DoubanFailuresViewModel @Inject constructor(
    private val doubanRetryManager: DoubanRetryManager,
    private val doubanFailureExporter: DoubanFailureExporter,
    private val doubanRepository: DoubanRepository
) : ViewModel() {

    data class DoubanFailuresUiState(
        val isLoading: Boolean = true,
        val failures: List<DoubanSyncFailure> = emptyList(),
        val error: String? = null,
        /** doubanId → 豆瓣评分(10分制),来自详情缓存(爬取过的条目才有) */
        val doubanRatings: Map<String, Double> = emptyMap(),
        /** doubanId → 豆瓣类型列表(如["剧情","喜剧"]),来自详情缓存(爬取过的条目才有) */
        val doubanGenres: Map<String, List<String>> = emptyMap(),
        /** 当前失败项集合中出现过的所有类型(去重排序,用于筛选弹窗展示) */
        val availableGenres: List<String> = emptyList()
    )

    /** 筛选状态:失败原因多选 + 类型多选 + 豆瓣评分区间 + 标记时间区间 + 排序方式 */
    data class FilterState(
        val selectedReasons: Set<FailureReason> = emptySet(),
        val selectedGenres: Set<String> = emptySet(),
        val ratingRange: ClosedFloatingPointRange<Float> = 0f..10f,
        val markedTimePreset: MarkedTimePreset = MarkedTimePreset.ALL,
        val markedTimeOrder: SortOrder = SortOrder.DESC
    )

    private val _uiState = MutableStateFlow(DoubanFailuresUiState())
    val uiState: StateFlow<DoubanFailuresUiState> = _uiState.asStateFlow()

    private val _filterState = MutableStateFlow(FilterState())
    val filterState: StateFlow<FilterState> = _filterState.asStateFlow()

    /** 是否有激活的筛选条件(用于筛选按钮图标高亮) */
    val hasActiveFilters: StateFlow<Boolean> = _filterState.map { state ->
        state.selectedReasons.isNotEmpty() ||
        state.selectedGenres.isNotEmpty() ||
        state.ratingRange != 0f..10f ||
        state.markedTimePreset != MarkedTimePreset.ALL ||
        state.markedTimeOrder != SortOrder.DESC
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 从 doubanRetryManager 加载全部失败项 */
    fun loadFailures() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = doubanRetryManager.getAllFailures()
                // 从豆瓣详情缓存批量查询评分和类型(爬取过的条目才有)
                val snapshot = runCatching { doubanRepository.getDetailSnapshot() }.getOrDefault(emptyMap())
                val ratings = snapshot.mapNotNull { (id, entry) ->
                    entry.doubanRating?.let { id to it }
                }.toMap()
                val genresMap = snapshot.mapNotNull { (id, entry) ->
                    if (entry.genres.isNotEmpty()) id to entry.genres else null
                }.toMap()
                // 聚合失败项中出现过的所有类型(去重排序)
                val availableGenres = genresMap.values
                    .flatten()
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                _uiState.value = DoubanFailuresUiState(
                    isLoading = false,
                    failures = list,
                    error = null,
                    doubanRatings = ratings,
                    doubanGenres = genresMap,
                    availableGenres = availableGenres
                )
                // 后台静默从全局池刷新其他用户标注的类型，有更新则重新加载列表
                launch {
                    val updated = runCatching {
                        doubanRetryManager.refreshMediaTypesFromCloudPool()
                    }.getOrElse { 0 }
                    if (updated > 0) {
                        val refreshed = doubanRetryManager.getAllFailures()
                        _uiState.value = _uiState.value.copy(failures = refreshed)
                    }
                }
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

    /** 批量更新媒体类型(多选模式标注用) */
    fun batchSetMediaType(doubanIds: List<String>, mediaType: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            doubanRetryManager.batchUpdateMediaType(doubanIds, mediaType)
            // 本地同步更新
            val idSet = doubanIds.toSet()
            _uiState.value = _uiState.value.copy(
                failures = _uiState.value.failures.map {
                    if (it.doubanId in idSet) it.copy(mediaType = mediaType) else it
                }
            )
        }
    }

    /** 批量删除失败项(多选模式删除用) */
    fun batchDeleteFailures(doubanIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            doubanRetryManager.batchDeleteFailures(doubanIds)
            val idSet = doubanIds.toSet()
            _uiState.value = _uiState.value.copy(
                failures = _uiState.value.failures.filterNot { it.doubanId in idSet }
            )
        }
    }

    fun updateSelectedReasons(reasons: Set<FailureReason>) {
        _filterState.value = _filterState.value.copy(selectedReasons = reasons)
    }

    fun updateSelectedGenres(genres: Set<String>) {
        _filterState.value = _filterState.value.copy(selectedGenres = genres)
    }

    fun updateRatingRange(range: ClosedFloatingPointRange<Float>) {
        _filterState.value = _filterState.value.copy(ratingRange = range)
    }

    fun updateMarkedTimePreset(preset: MarkedTimePreset) {
        _filterState.value = _filterState.value.copy(markedTimePreset = preset)
    }

    fun updateMarkedTimeOrder(order: SortOrder) {
        _filterState.value = _filterState.value.copy(markedTimeOrder = order)
    }

    fun resetFilters() {
        _filterState.value = FilterState()
    }

    /** 从用户选择的 JSON 文件导入失败项,成功后重新加载列表 */
    suspend fun importFailuresFromJson(context: Context, uri: Uri): ImportResult {
        val result = doubanFailureExporter.importToRoom(context, uri)
        if (result is ImportResult.Success) {
            loadFailures()
        }
        return result
    }
}

/**
 * 豆瓣同步失败项查看页。
 *
 * 数据流:
 * - selectedMode: 0=想看(WISH), 1=已看(COLLECT)
 * - selectedTab: 0=电影(movie), 1=电视剧(show), 2=未分类(null)
 * - 搜索:标题 + 副标题模糊匹配
 * - 筛选:失败原因多选 + 标记时间区间 + 标记时间排序
 * - 卡片点击 → onItemClick(doubanId)
 * - 卡片长按 → 弹 AlertDialog 菜单(标注类型/删除)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalHazeMaterialsApi::class, ExperimentalLayoutApi::class)
@Composable
fun DoubanFailuresScreen(
    onBack: () -> Unit,
    onItemClick: (doubanId: String) -> Unit,
    viewModel: DoubanFailuresViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filterState by viewModel.filterState.collectAsStateWithLifecycle()
    val hasActiveFilters by viewModel.hasActiveFilters.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val hazeState = remember { HazeState() }

    // 0=想看(WISH), 1=已看(COLLECT)
    var selectedMode by rememberSaveable { mutableIntStateOf(0) }
    // 0=电影, 1=电视剧, 2=综艺, 3=纪录片, 4=未分类
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 多选模式状态
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateMapOf<String, Boolean>() }
    var searchQuery by remember { mutableStateOf("") }
    var showFilterSheet by remember { mutableStateOf(false) }
    // 搜索框展开状态:未展开显示放大镜图标,展开时搜索框向左扩展遮住标题
    var isSearchExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    /** 收起搜索框:清空文本 + 失焦 + 收起键盘 + 切回图标 */
    fun collapseSearch() {
        isSearchExpanded = false
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    /** 展开搜索框:切换状态后下一帧请求焦点 */
    fun expandSearch() {
        isSearchExpanded = true
    }

    // 展开时延迟请求焦点,等待 BasicTextField 布局完成
    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            kotlinx.coroutines.delay(50)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    // 返回手势拦截:多选模式优先,其次搜索框展开
    BackHandler(enabled = isMultiSelectMode || isSearchExpanded) {
        when {
            isMultiSelectMode -> {
                isMultiSelectMode = false
                isProcessing = false
            }
            isSearchExpanded -> collapseSearch()
        }
    }

    // 退出多选时清空选中
    LaunchedEffect(isMultiSelectMode) {
        if (!isMultiSelectMode) selectedItems.clear()
    }

    // 切 Tab / 切模式时自动退出多选
    LaunchedEffect(selectedMode, selectedTab) {
        if (isMultiSelectMode) {
            isMultiSelectMode = false
            isProcessing = false
        }
    }

    // 进入页面时加载
    LaunchedEffect(Unit) {
        viewModel.loadFailures()
    }

    // 豆瓣失败项 JSON 导入 launcher:选文件 → importToRoom → 重新加载
    val importFailuresLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = viewModel.importFailuresFromJson(context, uri)) {
                    is ImportResult.Success -> {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.snackbar_import_done_json, result.count)
                        )
                    }
                    is ImportResult.InvalidFormat -> {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_invalid_json_format))
                    }
                    is ImportResult.Empty -> {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_empty_csv))
                    }
                    is ImportResult.Error -> {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.error_parse_failed, result.message)
                        )
                    }
                }
            }
        }
    }

    // 按 status × mediaType × 搜索 × 筛选 过滤当前列表
    val currentStatus = if (selectedMode == 0) DoubanMarkStatus.WISH else DoubanMarkStatus.COLLECT
    val filtered = remember(uiState.failures, selectedMode, selectedTab, searchQuery, filterState, uiState.doubanGenres, uiState.doubanRatings) {
        // 1. status × mediaType 分组
        val byStatusAndType = uiState.failures.filter { failure ->
            failure.status == currentStatus &&
                when (selectedTab) {
                    0 -> failure.mediaType == "movie"
                    1 -> failure.mediaType == "show"
                    2 -> failure.mediaType == "variety"
                    3 -> failure.mediaType == "documentary"
                    else -> failure.mediaType == null
                }
        }
        // 2. 搜索:标题 + 副标题模糊匹配
        val bySearch = if (searchQuery.isBlank()) byStatusAndType
        else byStatusAndType.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            (it.subtitle?.contains(searchQuery, ignoreCase = true) ?: false)
        }
        // 3. 失败原因多选
        val byReason = if (filterState.selectedReasons.isEmpty()) bySearch
        else bySearch.filter { it.failureReason in filterState.selectedReasons }
        // 4. 类型多选(豆瓣条目 genres 与选中类型有交集即通过)
        val byGenre = if (filterState.selectedGenres.isEmpty()) byReason
        else byReason.filter { failure ->
            val itemGenres = uiState.doubanGenres[failure.doubanId].orEmpty()
            itemGenres.any { it in filterState.selectedGenres }
        }
        // 5. 豆瓣评分区间(无评分条目仅在默认全区间时通过)
        val byRating = byGenre.filter { failure ->
            val rating = uiState.doubanRatings[failure.doubanId]
            if (rating == null) {
                filterState.ratingRange == 0f..10f
            } else {
                rating.toFloat() >= filterState.ratingRange.start && rating.toFloat() <= filterState.ratingRange.endInclusive
            }
        }
        // 6. 标记时间区间预设
        val byTime = byRating.filter { item ->
            when (filterState.markedTimePreset) {
                MarkedTimePreset.SEVEN_DAYS -> isWithinDays(item.markedAt, 7)
                MarkedTimePreset.THIRTY_DAYS -> isWithinDays(item.markedAt, 30)
                MarkedTimePreset.ALL -> true
            }
        }
        // 7. 标记时间排序(ISO 字符串天然有序)
        if (filterState.markedTimeOrder == SortOrder.DESC) byTime.sortedByDescending { it.markedAt }
        else byTime.sortedBy { it.markedAt }
    }

    // 各分类数量(用于 Tab 徽标,不受搜索/筛选影响,反映实际分组数量)
    // 合并为一次 groupBy 遍历,避免 5 次独立 count
    val categoryCounts = remember(uiState.failures, selectedMode) {
        val byMedia = uiState.failures
            .filter { it.status == currentStatus }
            .groupBy { it.mediaType }
        CategoryCounts(
            movie = byMedia["movie"]?.size ?: 0,
            show = byMedia["show"]?.size ?: 0,
            variety = byMedia["variety"]?.size ?: 0,
            documentary = byMedia["documentary"]?.size ?: 0,
            uncategorized = byMedia[null]?.size ?: 0,
            total = byMedia.values.sumOf { it.size }
        )
    }
    val movieCount = categoryCounts.movie
    val showCount = categoryCounts.show
    val varietyCount = categoryCounts.variety
    val documentaryCount = categoryCounts.documentary
    val uncategorizedCount = categoryCounts.uncategorized

    // 长按菜单目标条目
    var menuFailure by remember { mutableStateOf<DoubanSyncFailure?>(null) }
    // 清空确认对话框
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 沉浸式:内容延伸到状态栏/导航栏区域(参考 WatchlistScreen)
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

            // 内容区:空状态 / 失败项网格
            Box(modifier = Modifier.fillMaxSize()) {
                // 当前模式总数(不受搜索/筛选影响,用于判断"模式为空"和"分类为空")
                val currentModeTotalCount = categoryCounts.total
                // 当前 tab 的数量(用于判断分类为空)
                val currentTabCount = when (selectedTab) {
                    0 -> movieCount
                    1 -> showCount
                    2 -> varietyCount
                    3 -> documentaryCount
                    else -> uncategorizedCount
                }
                // 是否有激活的筛选条件(失败原因多选 + 类型多选 + 评分区间 + 标记时间区间预设)
                val hasActiveFilter = filterState.selectedReasons.isNotEmpty() ||
                    filterState.selectedGenres.isNotEmpty() ||
                    filterState.ratingRange != 0f..10f ||
                    filterState.markedTimePreset != MarkedTimePreset.ALL

                // 判断空状态类型(优先级:总空 > 模式空 > 搜索空 > 筛选空 > 分类空)
                val emptyTextRes = when {
                    !uiState.isLoading && uiState.failures.isEmpty() ->
                        R.string.screen_douban_failures_empty_all
                    !uiState.isLoading && currentModeTotalCount == 0 ->
                        if (selectedMode == 0) R.string.screen_douban_failures_empty_wish
                        else R.string.screen_douban_failures_empty_collect
                    !uiState.isLoading && filtered.isEmpty() && searchQuery.isNotBlank() ->
                        R.string.screen_douban_failures_empty_search
                    !uiState.isLoading && filtered.isEmpty() && hasActiveFilter ->
                        R.string.screen_douban_failures_empty_filter
                    !uiState.isLoading && currentTabCount == 0 ->
                        when (selectedTab) {
                            0 -> R.string.screen_douban_failures_empty_movie
                            1 -> R.string.screen_douban_failures_empty_show
                            2 -> R.string.screen_douban_failures_empty_variety
                            3 -> R.string.screen_douban_failures_empty_documentary
                            else -> R.string.screen_douban_failures_empty_uncategorized
                        }
                    else -> null
                }

                if (emptyTextRes != null) {
                    // 空状态:空图标 + 文案(参考 Watchlist 空白页布局)
                    Box(modifier = Modifier.padding(top = 172.dp + statusBarHeight)) {
                        EmptyStateView(
                            text = stringResource(emptyTextRes),
                            // 仅在"总空"时显示返回按钮(其他情况用户可能想调整搜索/筛选/分类)
                            showBackButton = emptyTextRes == R.string.screen_douban_failures_empty_all,
                            onBack = onBack
                        )
                    }
                } else if (!uiState.isLoading && filtered.isNotEmpty()) {
                    val gridState = rememberLazyGridState()
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 172.dp + statusBarHeight,
                            bottom = 16.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(state = hazeState)
                    ) {
                        items(
                            count = filtered.size,
                            key = { filtered[it].doubanId },
                            contentType = { "failure_card" }
                        ) { index ->
                            val failure = filtered[index]
                            val isSelected = selectedItems.containsKey(failure.doubanId)
                            Box {
                                FailureCard(
                                    failure = failure,
                                    doubanRating = uiState.doubanRatings[failure.doubanId],
                                    onClick = {
                                        if (isMultiSelectMode) {
                                            view.performHaptic(HapticType.CLICK)
                                            if (isSelected) selectedItems.remove(failure.doubanId)
                                            else selectedItems[failure.doubanId] = true
                                            if (selectedItems.isEmpty()) {
                                                isMultiSelectMode = false
                                            }
                                        } else {
                                            view.performHaptic(HapticType.CLICK)
                                            onItemClick(failure.doubanId)
                                        }
                                    },
                                    onLongClick = {
                                        view.performHaptic(HapticType.HEAVY_CLICK)
                                        if (!isMultiSelectMode) {
                                            isMultiSelectMode = true
                                        }
                                        selectedItems[failure.doubanId] = true
                                    }
                                )
                                // 多选模式下的选中遮罩 + 勾选标记(参考 WatchlistScreen)
                                if (isMultiSelectMode) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                                else Color.Transparent
                                            )
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else Color.Transparent
                                            )
                                            .then(
                                                if (!isSelected) Modifier.border(
                                                    2.dp,
                                                    Color.White.copy(alpha = 0.7f),
                                                    CircleShape
                                                ) else Modifier
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 快速回顶按钮(参考社区列表查看页)
                    ScrollToTopButton(
                        gridState = gridState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp, end = 16.dp),
                        hazeState = hazeState
                    )
                }
            }

            // Haze 模糊覆盖层:状态栏 + 标题栏 + 搜索框 + 切换条 + 分类 Tab
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .hazeEffect(
                        state = hazeState,
                        style = HazeMaterials.thin()
                    )
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.50f))
            ) {
                Column {
                    // 状态栏 Spacer
                    Spacer(
                        modifier = Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                    )
                    // 标题栏:标题 + 返回 + 搜索 + JSON 导入 + 筛选 + 清空
                    // 搜索框展开时遮住标题(向左扩展),点击搜索图标切换
                    TopAppBar(
                        title = {
                            // 用 Crossfade 在标题文字和搜索框之间淡入淡出切换
                            // 两者都 fillMaxWidth,navigationIcon 始终保留,占位不变,避免布局跳动
                            Crossfade(
                                targetState = isSearchExpanded,
                                animationSpec = tween(200),
                                label = "searchCrossfade"
                            ) { expanded ->
                                if (!expanded) {
                                    Text(
                                        text = stringResource(R.string.screen_douban_failures_title),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    val searchInteractionSource = remember { MutableInteractionSource() }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(searchFocusRequester),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                                        interactionSource = searchInteractionSource,
                                        decorationBox = { innerTextField ->
                                            OutlinedTextFieldDefaults.DecorationBox(
                                                value = searchQuery,
                                                innerTextField = innerTextField,
                                                enabled = true,
                                                singleLine = true,
                                                visualTransformation = VisualTransformation.None,
                                                interactionSource = searchInteractionSource,
                                                placeholder = {
                                                    Text(
                                                        stringResource(R.string.douban_failure_search_hint),
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                },
                                                trailingIcon = {
                                                    if (searchQuery.isNotEmpty()) {
                                                        IconButton(onClick = { searchQuery = "" }) {
                                                            Icon(
                                                                Icons.Rounded.Close,
                                                                contentDescription = stringResource(R.string.content_desc_clear),
                                                                modifier = Modifier.size(19.dp)
                                                            )
                                                        }
                                                    }
                                                },
                                                contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 0.dp, bottom = 0.dp),
                                                container = {
                                                    OutlinedTextFieldDefaults.Container(
                                                        enabled = true,
                                                        isError = false,
                                                        interactionSource = searchInteractionSource,
                                                        colors = OutlinedTextFieldDefaults.colors(),
                                                        shape = RoundedCornerShape(22.dp)
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            // 始终保留返回按钮占位:展开时点击收起搜索(保留搜索词),未展开时点击返回上一页
                            IconButton(onClick = {
                                view.performHaptic(HapticType.CLICK)
                                if (isSearchExpanded) {
                                    collapseSearch()
                                } else {
                                    onBack()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.douban_retry_cancel)
                                )
                            }
                        },
                        actions = {
                            // 搜索图标(放在导入图标左侧):未展开是放大镜,展开是关闭
                            IconButton(onClick = {
                                view.performHaptic(HapticType.CLICK)
                                if (isSearchExpanded) {
                                    searchQuery = ""
                                    collapseSearch()
                                } else {
                                    expandSearch()
                                }
                            }) {
                                Icon(
                                    imageVector = if (isSearchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                                    contentDescription = stringResource(R.string.douban_failure_search_hint)
                                )
                            }
                            // JSON 导入入口(从设置页移到此处)
                            IconButton(onClick = {
                                view.performHaptic(HapticType.CLICK)
                                importFailuresLauncher.launch(arrayOf("application/json"))
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.FileUpload,
                                    contentDescription = stringResource(R.string.douban_retry_option_json)
                                )
                            }
                            // 筛选按钮(激活时图标变 primary 色)
                            IconButton(onClick = {
                                view.performHaptic(HapticType.CLICK)
                                showFilterSheet = true
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = stringResource(R.string.filter_title),
                                    tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary
                                    else androidx.compose.material3.LocalContentColor.current
                                )
                            }
                        },
                        // 禁用 TopAppBar 默认的 windowInsets(状态栏 padding),避免与前面的 Spacer.statusBarsPadding() 重复
                        windowInsets = WindowInsets(0),
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )

                    // 胶囊切换条:想看 / 已看
                    ModeCapsuleToggle(
                        selectedMode = selectedMode,
                        onModeChange = { newMode ->
                            view.performHaptic(HapticType.CLICK)
                            selectedMode = newMode
                        }
                    )

                    // ScrollableTabRow:电影 / 电视剧 / 综艺 / 纪录片 / 未分类(带数量徽标)
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp
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
                                Text("${stringResource(R.string.screen_douban_failures_tab_variety)}($varietyCount)")
                            }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = {
                                view.performHaptic(HapticType.CLICK)
                                selectedTab = 3
                            },
                            text = {
                                Text("${stringResource(R.string.screen_douban_failures_tab_documentary)}($documentaryCount)")
                            }
                        )
                        Tab(
                            selected = selectedTab == 4,
                            onClick = {
                                view.performHaptic(HapticType.CLICK)
                                selectedTab = 4
                            },
                            text = {
                                Text("${stringResource(R.string.screen_douban_failures_tab_uncategorized)}($uncategorizedCount)")
                            }
                        )
                    }
                }
            }

            // 多选操作栏(顶部滑入,参考 WatchlistScreen)
            AnimatedVisibility(
                visible = isMultiSelectMode,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.thin()
                        )
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                ) {
                    // 状态栏 Spacer
                    Spacer(modifier = Modifier.statusBarsPadding())
                    // 第一行:返回 + 选中数 + 全选 + 取消
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = {
                            isMultiSelectMode = false
                            isProcessing = false
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.screen_douban_failures_selected_count,
                                selectedItems.size
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 全选/取消全选当前 Tab
                            val allSelected = filtered.isNotEmpty() && filtered.all { selectedItems.containsKey(it.doubanId) }
                            TextButton(
                                onClick = {
                                    view.performHaptic(HapticType.CLICK)
                                    if (allSelected) {
                                        selectedItems.clear()
                                    } else {
                                        filtered.forEach { selectedItems[it.doubanId] = true }
                                    }
                                },
                                enabled = !isProcessing
                            ) {
                                Icon(
                                    imageVector = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(if (allSelected) R.string.screen_douban_failures_deselect_all else R.string.screen_douban_failures_select_all))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            TextButton(
                                onClick = {
                                    isMultiSelectMode = false
                                    isProcessing = false
                                },
                                enabled = !isProcessing
                            ) {
                                Text(stringResource(R.string.douban_retry_cancel))
                            }
                        }
                    }
                    // 第二行:标注为电影 / 标注为电视剧 / 清除标注
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MultiSelectActionButton(
                            text = stringResource(R.string.screen_douban_failures_mark_as_movie),
                            icon = Icons.Rounded.Movie,
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                isProcessing = true
                                val ids = selectedItems.keys.toList()
                                viewModel.batchSetMediaType(ids, "movie")
                                isMultiSelectMode = false
                                isProcessing = false
                            }
                        )
                        MultiSelectActionButton(
                            text = stringResource(R.string.screen_douban_failures_mark_as_show),
                            icon = Icons.Rounded.Tv,
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                isProcessing = true
                                val ids = selectedItems.keys.toList()
                                viewModel.batchSetMediaType(ids, "show")
                                isMultiSelectMode = false
                                isProcessing = false
                            }
                        )
                        MultiSelectActionButton(
                            text = stringResource(R.string.screen_douban_failures_clear_mark),
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                isProcessing = true
                                val ids = selectedItems.keys.toList()
                                viewModel.batchSetMediaType(ids, null)
                                isMultiSelectMode = false
                                isProcessing = false
                            }
                        )
                    }
                    // 第三行:标注为综艺 / 标注为纪录片 / 删除
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MultiSelectActionButton(
                            text = stringResource(R.string.screen_douban_failures_mark_as_variety),
                            icon = Icons.Rounded.TheaterComedy,
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                isProcessing = true
                                val ids = selectedItems.keys.toList()
                                viewModel.batchSetMediaType(ids, "variety")
                                isMultiSelectMode = false
                                isProcessing = false
                            }
                        )
                        MultiSelectActionButton(
                            text = stringResource(R.string.screen_douban_failures_mark_as_documentary),
                            icon = Icons.Rounded.Nature,
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                isProcessing = true
                                val ids = selectedItems.keys.toList()
                                viewModel.batchSetMediaType(ids, "documentary")
                                isMultiSelectMode = false
                                isProcessing = false
                            }
                        )
                        MultiSelectActionButton(
                            text = stringResource(R.string.screen_douban_failures_delete_item),
                            icon = Icons.Rounded.Delete,
                            enabled = !isProcessing,
                            isDestructive = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                isProcessing = true
                                val ids = selectedItems.keys.toList()
                                viewModel.batchDeleteFailures(ids)
                                isMultiSelectMode = false
                                isProcessing = false
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    // 筛选 ModalBottomSheet
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            // 统一背景色与发现页查看全部 sheet 一致
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            // 去除默认 drag 条,内容更紧凑
            dragHandle = null
        ) {
            FailureFilterSheet(
                filterState = filterState,
                availableGenres = uiState.availableGenres,
                onReasonsChange = { viewModel.updateSelectedReasons(it) },
                onGenresChange = { viewModel.updateSelectedGenres(it) },
                onRatingRangeChange = { viewModel.updateRatingRange(it) },
                onPresetChange = { viewModel.updateMarkedTimePreset(it) },
                onOrderChange = { viewModel.updateMarkedTimeOrder(it) },
                onReset = { viewModel.resetFilters() },
                onApply = { showFilterSheet = false }
            )
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
            onMarkVariety = {
                viewModel.setMediaType(failure.doubanId, "variety")
                menuFailure = null
            },
            onMarkDocumentary = {
                viewModel.setMediaType(failure.doubanId, "documentary")
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
            // 空图标(参考 Watchlist 空白页布局,以后统一用这个风格)
            Icon(
                imageVector = Icons.Rounded.Inbox,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
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
 * 多选操作栏按钮(图标 + 文字,可标红)。
 */
@Composable
private fun MultiSelectActionButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val containerColor = if (isDestructive) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isDestructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.5f),
            disabledContentColor = contentColor.copy(alpha = 0.5f)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 4.dp,
            vertical = 8.dp
        )
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 失败项卡片(简化版 MovieCard)。
 *
 * - 海报:豆瓣 posterUrl 完整 URL,无海报时显示标题首两字符占位
 * - 评分角标:5 分制转★显示(rating=4 → ★★★★☆),null 不显示
 * - 失败原因图标:右下角小角标,可恢复用橙色 Warning,不可恢复用灰色 Block
 */

/** 各媒体类型分类计数(一次 groupBy 遍历结果) */
private data class CategoryCounts(
    val movie: Int,
    val show: Int,
    val variety: Int,
    val documentary: Int,
    val uncategorized: Int,
    val total: Int
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailureCard(
    failure: DoubanSyncFailure,
    doubanRating: Double?,
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
                            .size(264)
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

                // 用户评分角标(左上角):5 分制转★显示
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

                // 豆瓣评分角标(右上角):10 分制数字
                doubanRating?.let { rating ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC000000)
                    ) {
                        Text(
                            text = String.format("%.1f", rating),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFFFFC107),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // 豆瓣标记时间(左下角)
                if (failure.markedAt.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC000000)
                    ) {
                        Text(
                            text = failure.markedAt,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 标题区
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 4.dp)
            ) {
                // 处理 title 中包含 / 的中英文名分隔(如 "盗梦空间/Inception")
                // 主标题取 / 前面;子标题优先用已有 subtitle,为空时取 / 后面
                val titleContainsSlash = failure.title.contains("/")
                val displayTitle = if (titleContainsSlash) failure.title.substringBefore("/") else failure.title
                val displaySubtitle = when {
                    !failure.subtitle.isNullOrBlank() -> failure.subtitle
                    titleContainsSlash -> failure.title.substringAfter("/", "").takeIf { it.isNotEmpty() }
                    else -> null
                }
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!displaySubtitle.isNullOrBlank()) {
                    Text(
                        text = displaySubtitle,
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
    onMarkVariety: () -> Unit,
    onMarkDocumentary: () -> Unit,
    onClearMark: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                ActionItem(stringResource(R.string.screen_douban_failures_mark_as_variety)) {
                    onMarkVariety()
                }
                ActionItem(stringResource(R.string.screen_douban_failures_mark_as_documentary)) {
                    onMarkDocumentary()
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

/**
 * 筛选 ModalBottomSheet 内容:失败原因多选 + 类型多选 + 豆瓣评分区间 + 标记时间区间 + 排序方式。
 * 布局参考 Watchlist 筛选弹窗:每类之间用 HorizontalDivider 分隔,
 * 标题与 chips/SegmentedButton 共用一行,SpaceBetween 让每行均匀分布。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureFilterSheet(
    filterState: DoubanFailuresViewModel.FilterState,
    availableGenres: List<String>,
    onReasonsChange: (Set<FailureReason>) -> Unit,
    onGenresChange: (Set<String>) -> Unit,
    onRatingRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onPresetChange: (MarkedTimePreset) -> Unit,
    onOrderChange: (SortOrder) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 失败原因多选(chip 按估算宽度降序排列:长块先占位,短块填缝)
        val sortedReasons = remember {
            FailureReason.entries.sortedByDescending { reason ->
                // 估算:不同原因的文本长度,粗略排序
                when (reason) {
                    FailureReason.NO_IMDB_ID -> 80
                    FailureReason.DETAIL_FETCH_FAILED -> 110
                    FailureReason.TRAKT_NOT_FOUND -> 100
                    FailureReason.TRAKT_WRITE_TIMEOUT -> 110
                    FailureReason.TRAKT_WRITE_FAILED -> 100
                }
            }
        }
        Text(
            text = stringResource(R.string.douban_failure_filter_reason),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            sortedReasons.forEach { reason ->
                FilterChip(
                    selected = reason in filterState.selectedReasons,
                    border = if (reason in filterState.selectedReasons) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    onClick = {
                        val newSet = if (reason in filterState.selectedReasons) {
                            filterState.selectedReasons - reason
                        } else {
                            filterState.selectedReasons + reason
                        }
                        onReasonsChange(newSet)
                    },
                    label = { Text(stringResource(reason.localizedStringResCompat())) }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // 类型多选(豆瓣条目 genres,chip 按估算宽度降序排列)
        val sortedGenres = remember(availableGenres) {
            availableGenres.sortedByDescending { genre ->
                val cjkCount = genre.count { it.code in 0x4E00..0x9FFF }
                val otherCount = genre.length - cjkCount
                cjkCount * 14 + otherCount * 8 + 24
            }
        }
        Text(
            text = stringResource(R.string.filter_genre),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (sortedGenres.isEmpty()) {
            Text(
                text = stringResource(R.string.filter_genre_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sortedGenres.forEach { genre ->
                    FilterChip(
                        selected = genre in filterState.selectedGenres,
                        border = if (genre in filterState.selectedGenres) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        onClick = {
                            val newSet = if (genre in filterState.selectedGenres) {
                                filterState.selectedGenres - genre
                            } else {
                                filterState.selectedGenres + genre
                            }
                            onGenresChange(newSet)
                        },
                        label = { Text(genre) }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // 豆瓣评分 RangeSlider(标题 + 滑动条 + 数值同一行)
        val view = LocalView.current
        var lastRatingStart by remember(filterState.ratingRange.start) { mutableStateOf(filterState.ratingRange.start.toInt()) }
        var lastRatingEnd by remember(filterState.ratingRange.endInclusive) { mutableStateOf(filterState.ratingRange.endInclusive.toInt()) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.filter_rating_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            // 滑动条占 65% 宽度,右对齐评分值加大加粗
            // 包一层拦截竖直滑动,避免拖动滑块时触发 sheet 上下移动
            val ratingScrollConnection = remember {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                        Offset(0f, available.y)
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                        Offset(0f, available.y)
                    override suspend fun onPreFling(available: Velocity): Velocity = Velocity(0f, available.y)
                    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                    .nestedScroll(ratingScrollConnection)
                    .draggable(
                        state = rememberDraggableState { _ -> },
                        orientation = Orientation.Vertical,
                        startDragImmediately = true,
                        enabled = true
                    )
            ) {
                RangeSlider(
                    value = filterState.ratingRange,
                    onValueChange = { range ->
                        val newStart = range.start.toInt()
                        val newEnd = range.endInclusive.toInt()
                        if (newStart != lastRatingStart || newEnd != lastRatingEnd) {
                            view.performHaptic(HapticType.TICK)
                            lastRatingStart = newStart
                            lastRatingEnd = newEnd
                        }
                        onRatingRangeChange(range)
                    },
                    valueRange = 0f..10f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(
                text = "%.0f-%.0f".format(filterState.ratingRange.start, filterState.ratingRange.endInclusive),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // 标记时间区间(标题+chips 共用一行,SpaceBetween 让每行均匀分布)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.filter_marked_time),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.weight(1f)
            ) {
                MarkedTimePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = filterState.markedTimePreset == preset,
                        border = if (filterState.markedTimePreset == preset) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        onClick = { onPresetChange(preset) },
                        label = { Text(stringResource(preset.localizedStringRes())) }
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

        // 排序方式(标题左侧 + SegmentedButtonRow 右侧对齐,按钮高度压缩为单行)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.filter_sort_order),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .height(32.dp)
                    .wrapContentWidth()
            ) {
                SegmentedButton(
                    selected = filterState.markedTimeOrder == SortOrder.DESC,
                    onClick = { onOrderChange(SortOrder.DESC) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.widthIn(min = 100.dp),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 0.dp, bottom = 0.dp),
                    label = { Text(stringResource(R.string.filter_sort_desc), maxLines = 1) },
                    icon = {
                        Icon(
                            Icons.Rounded.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                SegmentedButton(
                    selected = filterState.markedTimeOrder == SortOrder.ASC,
                    onClick = { onOrderChange(SortOrder.ASC) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.widthIn(min = 100.dp),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 0.dp, bottom = 0.dp),
                    label = { Text(stringResource(R.string.filter_sort_asc), maxLines = 1) },
                    icon = {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 重置 + 应用
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.filter_reset))
            }
            Button(onClick = onApply) {
                Text(stringResource(R.string.filter_apply))
            }
        }
    }
}

/**
 * 检查 markedAt 是否在最近指定天数内。
 *
 * markedAt 来自豆瓣页面的 `span.date` 文本,可能为多种格式:
 * - ISO 格式:yyyy-MM-dd'T'HH:mm:ss'Z'
 * - 常规格式:yyyy-MM-dd HH:mm:ss / yyyy-MM-dd HH:mm / yyyy-MM-dd
 *
 * 解析失败时返回 true(包含该条目),避免隐藏无法解析日期的项。
 */
private fun isWithinDays(markedAt: String, days: Int): Boolean {
    if (markedAt.isBlank()) return true
    val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
    val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd"
    )
    for (format in formats) {
        try {
            val sdf = SimpleDateFormat(format, Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(markedAt)
            if (date != null) {
                return date.time >= cutoff
            }
        } catch (_: Exception) {
            // 尝试下一个格式
        }
    }
    // 全部格式解析失败,包含该条目(避免隐藏)
    return true
}

/** FailureReason 本地化字符串资源 ID */
private fun FailureReason.localizedStringResCompat(): Int = when (this) {
    FailureReason.NO_IMDB_ID -> R.string.douban_failure_reason_no_imdb_id
    FailureReason.DETAIL_FETCH_FAILED -> R.string.douban_failure_reason_detail_fetch_failed
    FailureReason.TRAKT_NOT_FOUND -> R.string.douban_failure_reason_trakt_not_found
    FailureReason.TRAKT_WRITE_TIMEOUT -> R.string.douban_failure_reason_trakt_write_timeout
    FailureReason.TRAKT_WRITE_FAILED -> R.string.douban_failure_reason_trakt_write_failed
}

/** MarkedTimePreset 本地化字符串资源 ID */
private fun MarkedTimePreset.localizedStringRes(): Int = when (this) {
    MarkedTimePreset.SEVEN_DAYS -> R.string.filter_time_7d
    MarkedTimePreset.THIRTY_DAYS -> R.string.filter_time_30d
    MarkedTimePreset.ALL -> R.string.filter_time_all
}
