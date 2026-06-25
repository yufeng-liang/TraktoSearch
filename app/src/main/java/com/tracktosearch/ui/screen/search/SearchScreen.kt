package com.tracktosearch.ui.screen.search

import android.content.Intent
import android.net.Uri

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.remote.dto.ResourceType
import com.tracktosearch.data.remote.dto.inferResourceType
import com.tracktosearch.data.local.SearchHistoryItem
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.LoadingView
import com.tracktosearch.ui.component.ResourceItemCard
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.performHaptic
import com.tracktosearch.ui.util.HapticType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ViewedStorageProvider {
    fun viewedItemStorage(): ViewedItemStorage
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
    var searchQuery by rememberSaveable { mutableStateOf(initialKeyword) }
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val viewedItemStorage = remember {
        EntryPointAccessors.fromApplication(context, ViewedStorageProvider::class.java).viewedItemStorage()
    }
    val viewedUrls by viewedItemStorage.viewedUrls.collectAsState(initial = emptySet())

    // 搜索框焦点状态，用于控制搜索历史展开
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 搜索历史展开时，返回手势收起搜索历史而不是退出页面
    BackHandler(enabled = isSearchFocused) {
        focusManager.clearFocus()
    }

    // 有搜索结果时，返回手势清空搜索
    BackHandler(enabled = uiState.resources.isNotEmpty() || uiState.keyword.isNotEmpty()) {
        searchQuery = ""
        viewModel.clearResults()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    var hadQuery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty() && hadQuery) {
            viewModel.clearResults()
        }
        hadQuery = searchQuery.isNotEmpty()
    }

    LaunchedEffect(Unit) {
        if (searchQuery.isEmpty() && uiState.keyword.isNotEmpty()) {
            searchQuery = uiState.keyword
        }
    }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotEmpty()) {
            searchQuery = initialKeyword
            viewModel.search(initialKeyword)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 搜索框始终在顶部，消除布局跳动
        SearchBarTop(
            searchQuery = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = {
                viewModel.search(searchQuery)
                focusManager.clearFocus()
                keyboardController?.hide()
            },
            onClear = { searchQuery = "" },
            onBack = onBack,
            focusRequester = focusRequester,
            onFocusChanged = { isSearchFocused = it },
            searchSourceType = searchSourceType,
            onSearchSourceTypeChange = onSearchSourceTypeChange
        )

        // 根据状态显示不同内容
        when {
            // 搜索结果
            uiState.resources.isNotEmpty() -> {
                // 搜索结果数
                Text(
                    text = stringResource(R.string.search_results, uiState.resources.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
                // 搜索结果列表
                SearchResultsContent(
                    resources = uiState.resources,
                    typeFilter = uiState.typeFilter,
                    viewedUrls = viewedUrls,
                    onTypeFilterChange = { viewModel.setTypeFilter(it) },
                    onItemClick = { item ->
                        openResourceLink(context, item)
                        viewModel.markViewed(item.url)
                    }
                )
            }
            // 加载中
            uiState.isLoading -> {
                LoadingView(message = stringResource(R.string.search_loading))
            }
            // 搜索无结果
            uiState.keyword.isNotEmpty() && uiState.resources.isEmpty() -> {
                EmptyView(message = stringResource(R.string.search_no_results))
            }
            // 默认页：装饰图标 + 搜索历史
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(24.dp))
                    // 装饰图标：白云+电影+放大镜+播放按钮
                    Image(
                        painter = painterResource(id = R.drawable.ic_search_cloud),
                        contentDescription = null,
                        modifier = Modifier.size(140.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // 搜索建议 / 搜索历史 + 热门搜索
                    if (searchQuery.isNotEmpty()) {
                        // 输入时显示自动补全建议
                        val suggestions = remember(searchQuery, uiState.searchHistory) {
                            viewModel.getSuggestions(searchQuery)
                        }
                        if (suggestions.isNotEmpty()) {
                            SearchSuggestionsInline(
                                suggestions = suggestions,
                                onSuggestionClick = { item ->
                                    searchQuery = item.keyword
                                    viewModel.search(item.keyword)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            )
                        }
                    } else {
                        // 搜索历史
                        if (uiState.searchHistory.isNotEmpty()) {
                            SearchHistoryInline(
                                history = uiState.searchHistory,
                                onHistoryClick = { item ->
                                    searchQuery = item.keyword
                                    if (item.type == "disk") {
                                        viewModel.search(item.keyword)
                                    } else {
                                        val st = when (item.type) {
                                            "movie" -> SearchSourceType.MOVIE
                                            "show" -> SearchSourceType.SHOW
                                            "person" -> SearchSourceType.PERSON
                                            else -> SearchSourceType.DISK
                                        }
                                        onTraktSearch?.invoke(st, item.keyword)
                                    }
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                                onHistoryDelete = { viewModel.removeHistory(it.keyword) },
                                onClearAll = { viewModel.clearHistory() }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        // 热门搜索
                        PopularSearchesSection(
                            popularSearches = viewModel.popularSearches,
                            onPopularClick = { keyword ->
                                searchQuery = keyword
                                viewModel.search(keyword)
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        )
                    }
                }
            }
        }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_back))
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                    if (onSearchSourceTypeChange != null) {
                        Box {
                            TextButton(
                                onClick = { showTypeDropdown = true },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
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
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = showTypeDropdown,
                                onDismissRequest = { showTypeDropdown = false }
                            ) {
                                SearchSourceType.entries.forEach { type ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(stringResource(
                                                    when (type) {
                                                        SearchSourceType.DISK -> R.string.search_type_disk
                                                        SearchSourceType.MOVIE -> R.string.search_type_movie
                                                        SearchSourceType.SHOW -> R.string.search_type_show
                                                        SearchSourceType.PERSON -> R.string.search_type_person
                                                    }
                                                ))
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
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.search_clear_input),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        TextButton(onClick = { view.performHaptic(HapticType.CLICK); onSearch() }) {
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
private fun SearchResultsContent(
    resources: List<ResourceItem>,
    typeFilter: ResourceType,
    viewedUrls: Set<String>,
    onTypeFilterChange: (ResourceType) -> Unit,
    onItemClick: (ResourceItem) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var selectedDiskType by remember { mutableStateOf<DiskType?>(null) }

    val filteredResources = remember(resources, selectedDiskType, typeFilter) {
        resources
            .let { rs ->
                if (typeFilter != ResourceType.ALL) {
                    rs.filter { inferResourceType(it.name) == typeFilter }
                } else {
                    rs
                }
            }
            .let { rs ->
                if (selectedDiskType != null) {
                    rs.filter { it.diskType == selectedDiskType }
                } else {
                    rs
                }
            }
    }

    val listState = rememberLazyListState()
    val scrollToTopProvider = LocalScrollToTopProvider.current
    val coroutineScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        scrollToTopProvider.register {
            coroutineScope.launch {
                listState.animateScrollToItem(0)
            }
        }
        onDispose {
            scrollToTopProvider.unregister()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 影视类型筛选
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.search_filter_type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = typeFilter == ResourceType.ALL,
                                onClick = { view.performHaptic(HapticType.TICK); onTypeFilterChange(ResourceType.ALL) },
                                label = { Text(stringResource(R.string.search_filter_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = typeFilter == ResourceType.MOVIE,
                                onClick = { view.performHaptic(HapticType.TICK); onTypeFilterChange(ResourceType.MOVIE) },
                                label = { Text(stringResource(R.string.search_filter_movie), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = typeFilter == ResourceType.SHOW,
                                onClick = { view.performHaptic(HapticType.TICK); onTypeFilterChange(ResourceType.SHOW) },
                                label = { Text(stringResource(R.string.search_filter_show), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
            }
            // 网盘类型筛选
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.search_filter_disk),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(64.dp)
                    )
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            FilterChip(
                                selected = selectedDiskType == null,
                                onClick = { view.performHaptic(HapticType.TICK); selectedDiskType = null },
                                label = { Text(stringResource(R.string.search_filter_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                        items(DiskType.entries.filter { it != DiskType.OTHER }) { type ->
                            FilterChip(
                                selected = selectedDiskType == type,
                                onClick = { view.performHaptic(HapticType.TICK); selectedDiskType = type },
                                label = {
                                    val label = when (type) {
                                        DiskType.QUARK -> stringResource(R.string.disk_quark)
                                        DiskType.BAIDU -> stringResource(R.string.disk_baidu)
                                        DiskType.ALI -> stringResource(R.string.disk_ali)
                                        DiskType.XUNLEI -> stringResource(R.string.disk_xunlei)
                                        DiskType.UC -> stringResource(R.string.disk_uc)
                                        DiskType.ONEONEFIVE -> stringResource(R.string.disk_115)
                                        DiskType.MAGNET -> stringResource(R.string.disk_magnet)
                                        DiskType.OTHER -> stringResource(R.string.disk_other)
                                    }
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            )
                        }
                    }
                }
            }
            itemsIndexed(filteredResources, key = { _, it -> it.url }) { index, item ->
                val isViewed = item.url in viewedUrls
                ResourceItemCard(
                    item = item,
                    isViewed = isViewed,
                    index = index,
                    onClick = { onItemClick(item) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(300),
                        placementSpec = tween(300)
                    )
                )
            }
        }
        // 快速回顶按钮
        ScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp)
        )
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
        "disk" to Color(0xFF9E9E9E),
        "movie" to Color(0xFF2196F3),
        "show" to Color(0xFF4CAF50),
        "person" to Color(0xFF9C27B0)
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
        history.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHistoryClick(item) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = typeColorMap[item.type] ?: Color(0xFF9E9E9E),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = typeNameMap[item.type] ?: item.type,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = item.keyword,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                IconButton(
                    onClick = { onHistoryDelete(item) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.search_history_delete),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

private fun openResourceLink(context: android.content.Context, item: ResourceItem) {
    val url = item.url

    val appPackages = when (item.diskType) {
        DiskType.QUARK -> listOf("com.quark.clouddrive", "com.quark.browser")
        DiskType.BAIDU -> listOf("com.baidu.netdisk")
        DiskType.ALI -> listOf("com.alicloud.databox")
        DiskType.XUNLEI -> listOf("com.xunlei.downloadprovider", "com.xunlei.browser")
        DiskType.UC -> listOf("com.UCMobile")
        DiskType.ONEONEFIVE -> listOf("com.crland.app")
        DiskType.MAGNET, DiskType.OTHER -> emptyList()
    }

    for (pkg in appPackages) {
        try {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (appIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(appIntent)
                return
            }
        } catch (_: Exception) {}
    }
    try {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
    } catch (_: Exception) {}
}

private fun diskTypeDisplayName(type: DiskType): String = when (type) {
    DiskType.QUARK -> "Quark"
    DiskType.BAIDU -> "Baidu"
    DiskType.ALI -> "Ali"
    DiskType.XUNLEI -> "Xunlei"
    DiskType.UC -> "UC"
    DiskType.ONEONEFIVE -> "115"
    DiskType.MAGNET -> "Magnet"
    DiskType.OTHER -> "Other"
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
