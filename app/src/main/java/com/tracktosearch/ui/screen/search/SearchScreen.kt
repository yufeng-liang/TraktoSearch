package com.tracktosearch.ui.screen.search

import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.tracktosearch.R
import com.tracktosearch.data.local.CloudPermissionStorage
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.ui.component.CloudEasterEgg
import com.tracktosearch.ui.component.CloudOverlay
import com.tracktosearch.ui.component.CloudThemeManager
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.compose.foundation.lazy.grid.items as gridItems

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ViewedStorageProvider {
    fun viewedItemStorage(): ViewedItemStorage
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudThemeProvider {
    fun cloudThemeManager(): CloudThemeManager
}

enum class SearchSourceType { DISK, MOVIE, SHOW, PERSON }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialKeyword: String = "",
    onBack: (() -> Unit)? = null,
    onSearchClick: ((String) -> Unit)? = null,
    onTraktSearch: ((SearchSourceType, String) -> Unit)? = null,
    onOpenWebView: (url: String) -> Unit = {},
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    searchSourceType: SearchSourceType = SearchSourceType.DISK,
    onSearchSourceTypeChange: ((SearchSourceType) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hotSearches by viewModel.hotSearches.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf(initialKeyword) }
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val viewedItemStorage = remember {
        EntryPointAccessors.fromApplication(context, ViewedStorageProvider::class.java).viewedItemStorage()
    }
    val viewedUrls by viewedItemStorage.viewedUrls.collectAsStateWithLifecycle(initialValue = emptySet())

    // 白云彩蛋主题管理
    val cloudThemeManager = remember {
        EntryPointAccessors.fromApplication(context, CloudThemeProvider::class.java).cloudThemeManager()
    }
    val cloudPermissionStorage = remember {
        CloudPermissionStorage(context.applicationContext)
    }
    val scope = rememberCoroutineScope()
    // 定位权限申请
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val loc = getLastKnownLocation(context)
            cloudThemeManager.onPermissionGranted()
            cloudThemeManager.loadTheme(loc)
        } else {
            cloudThemeManager.onPermissionDismissed()
        }
    }
    LaunchedEffect(Unit) {
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            cloudThemeManager.onPermissionGranted()
            cloudThemeManager.loadTheme(getLastKnownLocation(context))
        } else {
            // 未授权时只加载缓存主题，不自动请求权限
            cloudThemeManager.loadTheme(null)
        }
    }
    val showPermissionDialog by cloudThemeManager.showPermissionDialog.collectAsStateWithLifecycle()
    val easterEggRes by cloudThemeManager.easterEggRes.collectAsStateWithLifecycle()
    val easterMessage by cloudThemeManager.easterMessage.collectAsStateWithLifecycle()

    // 搜索框焦点状态，用于控制搜索历史展开
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Animation state for search box width
    val animatedWidthFraction = remember { Animatable(0.75f) }
    val isActive = isSearchFocused || searchQuery.isNotEmpty()

    // Trigger animation when active state changes
    LaunchedEffect(isActive) {
        if (isActive) {
            animatedWidthFraction.animateTo(
                targetValue = 1f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        } else {
            animatedWidthFraction.animateTo(
                targetValue = 0.88f,
                animationSpec = tween(300, easing = FastOutSlowInEasing)
            )
        }
    }

    // 搜索历史展开时，返回手势收起搜索历史而不是退出页面
    BackHandler(enabled = isSearchFocused) {
        focusManager.clearFocus()
    }

    var hadQuery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searchQuery) {
        hadQuery = searchQuery.isNotEmpty()
    }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotEmpty()) {
            searchQuery = initialKeyword
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Main content area
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部留空给搜索框覆盖层（云朵 182dp + 间距 16dp + 搜索框 56dp + 缓冲）
            Spacer(modifier = Modifier.height(260.dp))

            // Content below search box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                when {
                    else -> {
                        if (searchQuery.isNotEmpty()) {
                            val suggestions = remember(searchQuery, uiState.searchHistory) {
                                viewModel.getSuggestions(searchQuery)
                            }
                            if (suggestions.isNotEmpty()) {
                                SearchSuggestionsInline(
                                    suggestions = suggestions,
                                    onSuggestionClick = { item ->
                                        searchQuery = item.keyword
                                        onTraktSearch?.invoke(searchSourceType, item.keyword)
                                        focusManager.clearFocus()
                                    }
                                )
                            }
                        } else {
                            if (uiState.searchHistory.isNotEmpty()) {
                                SearchHistoryInline(
                                    history = uiState.searchHistory,
                                    onHistoryClick = { item ->
                                        searchQuery = item.keyword
                                        val st = when (item.type) {
                                            "movie" -> SearchSourceType.MOVIE
                                            "show" -> SearchSourceType.SHOW
                                            "person" -> SearchSourceType.PERSON
                                            else -> SearchSourceType.DISK
                                        }
                                        onTraktSearch?.invoke(st, item.keyword)
                                        focusManager.clearFocus()
                                    },
                                    onHistoryDelete = { viewModel.removeHistory(it.keyword) },
                                    onClearAll = { viewModel.clearHistory() }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            PopularSearchesSection(
                                popularSearches = hotSearches,
                                onPopularClick = { keyword ->
                                    searchQuery = keyword
                                    // 热门搜索默认用网盘tab页搜索
                                    onTraktSearch?.invoke(SearchSourceType.DISK, keyword)
                                    focusManager.clearFocus()
                                }
                            )
                        }
                    }
                }
            }
        }

        // 搜索框覆盖层
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.50f))
        ) {
            // 云朵图标 — 带显隐动画
            CloudIconWithAnimation(cloudThemeManager, isActive)

            // 权限提示弹窗
            if (showPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { cloudThemeManager.onPermissionDismissed() },
                    text = {
                        Text("想知道白云会变身吗？授权位置后，它会随天气和节日悄悄变化哦~")
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                cloudThemeManager.onPermissionDismissed()
                                locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            }
                        ) {
                            Text("去授权")
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                cloudThemeManager.onPermissionDismissed()
                                scope.launch(Dispatchers.IO) {
                                    cloudPermissionStorage.setDismissed(true)
                                }
                            }
                        ) {
                            Text("取消")
                        }
                    }
                )
            }

            // Spacer between icon and search box
            Spacer(modifier = Modifier.height(16.dp))

            // Search box - animated width
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(animatedWidthFraction.value)
                ) {
                    SearchBarTop(
                        searchQuery = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = {
                            viewModel.addTraktHistory(searchQuery, searchSourceType.name.lowercase())
                            onTraktSearch?.invoke(searchSourceType, searchQuery)
                            focusManager.clearFocus()
                        },
                        onClear = { searchQuery = "" },
                        onBack = if (isActive) onBack else null,
                        focusRequester = focusRequester,
                        onFocusChanged = { isSearchFocused = it },
                        searchSourceType = searchSourceType,
                        onSearchSourceTypeChange = onSearchSourceTypeChange
                    )
                }
            }

            // Spacer between search box and content
            Spacer(modifier = Modifier.height(24.dp))
        }

        // 全屏彩蛋 Overlay
        CloudOverlay(
            easterEggRes = easterEggRes,
            message = easterMessage,
            onDismiss = { cloudThemeManager.onEasterDismissed() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarTop(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onBack: (() -> Unit)?,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    searchSourceType: SearchSourceType = SearchSourceType.DISK,
    onSearchSourceTypeChange: ((SearchSourceType) -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    var showTypeDropdown by remember { mutableStateOf(false) }
    val typeColorMap = mapOf(
        SearchSourceType.DISK to Color(0xFF26A69A),    // Teal
        SearchSourceType.MOVIE to Color(0xFF7986CB),   // Indigo
        SearchSourceType.SHOW to Color(0xFFFFD54F),    // Amber
        SearchSourceType.PERSON to Color(0xFFF48FB1)   // Rose
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_back), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    onFocusChanged(focusState.isFocused)
                },
            placeholder = {
                Text(
                    text = stringResource(
                        when (searchSourceType) {
                            SearchSourceType.MOVIE -> R.string.search_placeholder_movie
                            SearchSourceType.SHOW -> R.string.search_placeholder_show
                            SearchSourceType.PERSON -> R.string.search_placeholder_person
                            SearchSourceType.DISK -> R.string.search_placeholder
                        }
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Visible
                )
            },
            leadingIcon = {
                if (onSearchSourceTypeChange != null) {
                    Box {
                        TextButton(
                            onClick = { showTypeDropdown = true },
                            contentPadding = PaddingValues(start = 8.dp, top = 0.dp, end = 4.dp, bottom = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    when (searchSourceType) {
                                        SearchSourceType.DISK -> R.string.search_type_disk
                                        SearchSourceType.MOVIE -> R.string.search_type_movie
                                        SearchSourceType.SHOW -> R.string.search_type_show
                                        SearchSourceType.PERSON -> R.string.search_type_person
                                    }
                                ),
                                fontSize = 15.sp,
                                color = typeColorMap[searchSourceType] ?: Color(0xFF4CAF50),
                                maxLines = 1
                            )
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = typeColorMap[searchSourceType] ?: Color(0xFF4CAF50)
                            )
                        }
                        DropdownMenu(
                            expanded = showTypeDropdown,
                            onDismissRequest = { showTypeDropdown = false },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            val orderedTypes = listOf(SearchSourceType.MOVIE, SearchSourceType.SHOW, SearchSourceType.PERSON, SearchSourceType.DISK)
                            orderedTypes.forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                stringResource(
                                                    when (type) {
                                                        SearchSourceType.DISK -> R.string.search_type_disk
                                                        SearchSourceType.MOVIE -> R.string.search_type_movie
                                                        SearchSourceType.SHOW -> R.string.search_type_show
                                                        SearchSourceType.PERSON -> R.string.search_type_person
                                                    }
                                                ),
                                                color = typeColorMap[type] ?: Color(0xFF4CAF50)
                                            )
                                            if (type == searchSourceType) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    onClick = {
                                        showTypeDropdown = false
                                        if (type != searchSourceType) {
                                            onSearchSourceTypeChange?.invoke(type)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    if (searchQuery.isEmpty()) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.search_clear_input),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        TextButton(
                            onClick = { view.performHaptic(HapticType.CLICK); onSearch() },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(stringResource(R.string.search_button))
                        }
                    }
                }
            }
        )
        // 当有返回按钮时，右侧添加等宽 Spacer 保持左右边距一致
        if (onBack != null) {
            Spacer(modifier = Modifier.width(44.dp))
        }
    }
}

@Composable
private fun SearchHistoryInline(
    history: List<SearchHistoryItem>,
    onHistoryClick: (SearchHistoryItem) -> Unit,
    onHistoryDelete: (SearchHistoryItem) -> Unit,
    onClearAll: () -> Unit
) {
    val typeColorMap = mapOf(
        "disk" to Color(0xFF26A69A),
        "movie" to Color(0xFF7986CB),
        "show" to Color(0xFFFFD54F),
        "person" to Color(0xFFF48FB1)
    )
    val typeNameMap = mapOf(
        "disk" to stringResource(R.string.search_type_disk),
        "movie" to stringResource(R.string.search_type_movie),
        "show" to stringResource(R.string.search_type_show),
        "person" to stringResource(R.string.search_type_person)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onClearAll, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = stringResource(R.string.search_history_clear_all),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
        ) {
            history.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowItems.forEach { item ->
                        val tagColor = typeColorMap[item.type] ?: Color(0xFF4CAF50)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onHistoryClick(item) },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            tonalElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = tagColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = typeNameMap[item.type] ?: item.type,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = tagColor,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = item.keyword,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                IconButton(
                                    onClick = { onHistoryDelete(item) },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.search_history_delete),
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (rowItems.size < 2) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestionsInline(
    suggestions: List<SearchHistoryItem>,
    onSuggestionClick: (SearchHistoryItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.search_suggestions_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        suggestions.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestionClick(item) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = item.keyword,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopularSearchesSection(
    popularSearches: List<String>,
    onPopularClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.search_popular_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            popularSearches.forEach { keyword ->
                FilterChip(
                    selected = false,
                    onClick = { onPopularClick(keyword) },
                    label = { Text(keyword) }
                )
            }
        }
    }
}

// ========== 豆瓣热榜组件（供 DiscoverScreen 复用） ==========

@Composable
fun DoubanHotCategorySection(
    category: DoubanHotCategory,
    resolvingItemId: Int?,
    onItemClick: (DoubanHotItem) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit
) {
    Column {
        // 榜单标题 + 全部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = doubanCategoryLabel(category.id),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Row(
                modifier = Modifier
                    .clickable { onViewAll() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_view_all_count, doubanCategoryTotal(category.id)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (category.isLoading) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(5) {
                    com.tracktosearch.ui.component.DoubanHotCardSkeleton()
                }
            }
        } else if (category.error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.common_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.error_retry), style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(category.items, key = { it.id ?: it.title }) { item ->
                    DoubanHotCard(
                        item = item,
                        isResolving = resolvingItemId == item.id,
                        onClick = { onItemClick(item) }
                    )
                }
                // Top250 保留箭头卡片
                if (category.id == "douban-top250") {
                    item {
                        Card(
                            modifier = Modifier
                                .width(40.dp)
                                .height(172.dp)
                                .clickable { onViewAll() },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = stringResource(R.string.content_desc_view_all),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DoubanHotCard(
    item: DoubanHotItem,
    isResolving: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val ratingMatch = Regex("【(\\d+\\.?\\d*)】").find(item.title)
    val rating = ratingMatch?.groupValues?.get(1)
    val displayTitle = item.title
        .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
        .replace(Regex("^#\\d+\\s*"), "")

    Card(
        modifier = Modifier
            .width(105.dp)
            .clickable(enabled = !isResolving) { onClick() },
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
                if (!item.cover.isNullOrBlank()) {
                    val imageRequest = remember(item.cover) {
                        coil.request.ImageRequest.Builder(context)
                            .data(item.cover)
                            .size(300)
                            .crossfade(true)
                            .build()
                    }
                    coil.compose.AsyncImage(
                        model = imageRequest,
                        contentDescription = displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
                if (rating != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF68BD5B)
                    ) {
                        Text(
                            text = rating,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanHotAllSheet(
    category: DoubanHotCategory,
    resolvingItemId: Int?,
    onItemClick: (DoubanHotItem) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = doubanCategoryLabel(category.id),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }

            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(category.items, key = { it.id ?: it.title }) { item ->
                    DoubanHotGridItem(
                        item = item,
                        isResolving = resolvingItemId == item.id,
                        onClick = {
                            onItemClick(item)
                            onDismiss()
                        }
                    )
                }
                if (category.hasMore) {
                    item(span = { GridItemSpan(3) }) {
                        LaunchedEffect(category.currentPage) { onLoadMore() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoubanHotGridItem(
    item: DoubanHotItem,
    isResolving: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val ratingMatch = Regex("【(\\d+\\.?\\d*)】").find(item.title)
    val rating = ratingMatch?.groupValues?.get(1)
    val displayTitle = item.title
        .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
        .replace(Regex("^#\\d+\\s*"), "")

    Card(
        modifier = Modifier.clickable(enabled = !isResolving) { onClick() },
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
                if (!item.cover.isNullOrBlank()) {
                    val imageRequest = remember(item.cover) {
                        coil.request.ImageRequest.Builder(context)
                            .data(item.cover)
                            .size(300)
                            .crossfade(true)
                            .build()
                    }
                    coil.compose.AsyncImage(
                        model = imageRequest,
                        contentDescription = displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
                if (rating != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF68BD5B)
                    ) {
                        Text(
                            text = rating,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (item.desc.isNotBlank()) {
                    Text(
                        text = item.desc,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun doubanCategoryLabel(categoryId: String): String = when (categoryId) {
    "douban-movie" -> stringResource(R.string.discover_douban_new_movies)
    "douban-weekly" -> stringResource(R.string.discover_douban_weekly)
    "douban-top250" -> stringResource(R.string.discover_douban_top250)
    "douban-us-box" -> stringResource(R.string.discover_douban_us_box)
    else -> categoryId
}

private fun doubanCategoryTotal(categoryId: String): Int = when (categoryId) {
    "douban-top250" -> 250
    else -> 10
}

private fun getLastKnownLocation(context: android.content.Context): Location? {
    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        // 优先网络定位（粗略），再 GPS
        lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (_: SecurityException) {
        null
    }
}

@Composable
private fun CloudIconWithAnimation(cloudThemeManager: CloudThemeManager, isActive: Boolean) {
    val hasPermission by cloudThemeManager.hasLocationPermission.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (isActive) 0.dp else 182.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = !isActive,
            enter = fadeIn(animationSpec = tween(300)) + expandVertically(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200))
        ) {
            if (hasPermission) {
                CloudEasterEgg(
                    themeManager = cloudThemeManager,
                    modifier = Modifier
                        .size(182.dp)
                        .padding(top = 16.dp)
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_search_cloud),
                    contentDescription = null,
                    modifier = Modifier
                        .size(182.dp)
                        .padding(top = 16.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { cloudThemeManager.onCloudClicked() }
                        )
                )
            }
        }
    }
}
