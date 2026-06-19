package com.tracktosearch.ui.screen.search

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.dto.DoubanHotItem
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.ui.component.DoubanHotCardSkeleton
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.LoadingView
import com.tracktosearch.ui.component.ResourceItemCard
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ViewedStorageProvider {
    fun viewedItemStorage(): ViewedItemStorage
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialKeyword: String = "",
    onBack: (() -> Unit)? = null,
    onSearchClick: ((String) -> Unit)? = null,
    onOpenWebView: (url: String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf(initialKeyword) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val viewedItemStorage = remember {
        EntryPointAccessors.fromApplication(context, ViewedStorageProvider::class.java).viewedItemStorage()
    }
    val viewedUrls by viewedItemStorage.viewedUrls.collectAsState(initial = emptySet())

    val searchHistoryLoaded by remember { derivedStateOf { uiState.searchHistoryLoaded } }

    // 搜索框焦点状态，用于控制搜索历史展开
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 搜索历史展开时，返回手势收起搜索历史而不是退出页面
    BackHandler(enabled = isSearchFocused) {
        focusManager.clearFocus()
    }

    // 豆瓣热榜全量弹窗状态
    var showDoubanAllDialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(showDoubanAllDialog) {
        val catId = showDoubanAllDialog ?: return@LaunchedEffect
        viewModel.loadDoubanHotAll(catId, limit = 50)
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

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.search_title))
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .widthIn(min = 0.dp)
                                .wrapContentWidth()
                                .padding(end = 16.dp)
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    isSearchFocused = focusState.isFocused
                                },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.search_placeholder),
                                    maxLines = 1,
                                    overflow = TextOverflow.Visible
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (searchQuery.isNotBlank()) {
                                        viewModel.search(searchQuery)
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                }
                            ),
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.search_clear_input),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        TextButton(onClick = {
                                            viewModel.search(searchQuery)
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                        }) {
                                            Text(stringResource(R.string.search_button))
                                        }
                                    }
                                }
                            }
                        )
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.search_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 搜索框聚焦且为空时，在搜索框下方展开搜索历史
            if (isSearchFocused && searchQuery.isEmpty() && uiState.searchHistory.isNotEmpty()) {
                SearchHistoryInline(
                    history = uiState.searchHistory,
                    onHistoryClick = { keyword ->
                        searchQuery = keyword
                        viewModel.search(keyword)
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                    onHistoryDelete = { viewModel.removeHistory(it) },
                    onClearAll = { viewModel.clearHistory() }
                )
            }

            // 内容区域
            when {
                uiState.isLoading -> {
                    LoadingView(message = stringResource(R.string.search_loading))
                }
                uiState.error != null && uiState.resources.isEmpty() -> {
                    EmptyView(message = stringResource(R.string.search_failed, uiState.error ?: ""))
                }
                uiState.resources.isEmpty() && uiState.keyword.isNotEmpty() -> {
                    EmptyView(message = stringResource(R.string.search_no_results))
                }
                uiState.resources.isNotEmpty() -> {
                    // 搜索结果
                    var selectedDiskType by remember { mutableStateOf<DiskType?>(null) }
                    var filterExpanded by remember { mutableStateOf(false) }

                    val filteredResources = remember(uiState.resources, selectedDiskType) {
                        if (selectedDiskType != null) {
                            uiState.resources.filter { it.diskType == selectedDiskType }
                        } else {
                            uiState.resources
                        }
                    }

                    val listState = rememberLazyListState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.search_results, filteredResources.size),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Box {
                                        Row(
                                            modifier = Modifier
                                                .clickable { filterExpanded = true }
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "网盘类型",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                text = selectedDiskType?.let {
                                                    diskTypeDisplayName(it)
                                                } ?: "全部",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Icon(
                                                Icons.Default.ArrowDropDown,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = filterExpanded,
                                            onDismissRequest = { filterExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("全部") },
                                                onClick = {
                                                    selectedDiskType = null
                                                    filterExpanded = false
                                                }
                                            )
                                            DiskType.entries.filter { it != DiskType.OTHER }.forEach { type ->
                                                DropdownMenuItem(
                                                    text = { Text(diskTypeDisplayName(type)) },
                                                    onClick = {
                                                        selectedDiskType = type
                                                        filterExpanded = false
                                                    }
                                                )
                                            }
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
                                    onClick = {
                                        openResourceLink(context, item)
                                        viewModel.markViewed(item.url)
                                    }
                                )
                            }
                        }
                    }
                }
                !isSearchFocused && uiState.resources.isEmpty() && uiState.keyword.isEmpty() -> {
                    // 默认页：豆瓣热榜（搜索框聚焦时隐藏，展示搜索历史）
                    DoubanHotContent(
                        categories = uiState.doubanHotCategories,
                        onItemClick = { item ->
                            val displayTitle = item.title
                                .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
                                .replace(Regex("^#\\d+\\s*"), "")
                            copyToClipboard(context, displayTitle)
                            if (item.url.isNotBlank()) {
                                onOpenWebView(item.url)
                            }
                        },
                        onViewAll = { category ->
                            showDoubanAllDialog = category.id
                        }
                    )
                }
            }
        }
    }

    // 豆瓣热榜全量弹窗
    showDoubanAllDialog?.let { catId ->
        val category = uiState.doubanHotCategories.find { it.id == catId }
        if (category != null) {
            DoubanHotAllSheet(
                category = category,
                onItemClick = { item ->
                    val displayTitle = item.title
                        .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
                        .replace(Regex("^#\\d+\\s*"), "")
                    copyToClipboard(context, displayTitle)
                    if (item.url.isNotBlank()) {
                        onOpenWebView(item.url)
                    }
                },
                onLoadMore = {
                    viewModel.loadDoubanHotAll(catId, page = category.currentPage + 1, limit = 50)
                },
                onDismiss = { showDoubanAllDialog = null }
            )
        }
    }
}

@Composable
private fun DoubanHotContent(
    categories: List<DoubanHotCategory>,
    onItemClick: (DoubanHotItem) -> Unit,
    onViewAll: (DoubanHotCategory) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 24.dp,
            bottom = 80.dp // 底部安全区，避免被悬浮导航栏遮挡
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 标题
        item {
            Text(
                text = "豆瓣热榜",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 各榜单
        items(categories, key = { it.id }) { category ->
            DoubanHotCategorySection(
                category = category,
                onItemClick = onItemClick,
                onViewAll = { onViewAll(category) }
            )
        }
    }
}

@Composable
private fun DoubanHotCategorySection(
    category: DoubanHotCategory,
    onItemClick: (DoubanHotItem) -> Unit,
    onViewAll: () -> Unit
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
                text = category.label,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Row(
                modifier = Modifier
                    .clickable { onViewAll() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (category.id == "douban-top250") "全部250 >" else "全部10 >",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (category.isLoading) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(5) {
                    DoubanHotCardSkeleton()
                }
            }
        } else if (category.error != null) {
            Text(
                text = category.error ?: "加载失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            // 横向滚动卡片列表
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(category.items, key = { it.id ?: it.title }) { item ->
                    DoubanHotCard(
                        item = item,
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
                                    Icons.Default.ArrowForwardIos,
                                    contentDescription = "查看全部",
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
private fun DoubanHotCard(
    item: DoubanHotItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 从标题中提取评分，如 【7.7】痴迷 → 7.7
    val ratingMatch = Regex("【(\\d+\\.?\\d*)】").find(item.title)
    val rating = ratingMatch?.groupValues?.get(1)
    val displayTitle = item.title
        .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
        .replace(Regex("^#\\d+\\s*"), "")

    Card(
        modifier = Modifier
            .width(99.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // 海报 - 使用 2:3 宽高比，和 MovieCard 一致
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            ) {
                if (!item.cover.isNullOrBlank()) {
                    val imageRequest = remember(item.cover) {
                        ImageRequest.Builder(context)
                            .data(item.cover)
                            .size(300)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = { Log.e("DoubanHot", "Failed to load cover: ${item.cover}, error: ${it.result.throwable}") }
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
                // 评分标签 - 海报右上角
                if (rating != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF68BD5B) // 豆瓣绿色
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
            }
            // 名字
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
private fun DoubanHotAllSheet(
    category: DoubanHotCategory,
    onItemClick: (DoubanHotItem) -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }

            // Grid 列表
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.8f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(category.items, key = { it.id ?: it.title }) { item ->
                    DoubanHotGridItem(
                        item = item,
                        onClick = {
                            onItemClick(item)
                            onDismiss()
                        }
                    )
                }
                // 加载更多
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
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // 从标题中提取评分
    val ratingMatch = Regex("【(\\d+\\.?\\d*)】").find(item.title)
    val rating = ratingMatch?.groupValues?.get(1)
    val displayTitle = item.title
        .replace(Regex("【\\d+\\.?\\d*】\\s*"), "")
        .replace(Regex("^#\\d+\\s*"), "")

    Card(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // 海报
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
            ) {
                if (!item.cover.isNullOrBlank()) {
                    val imageRequest = remember(item.cover) {
                        ImageRequest.Builder(context)
                            .data(item.cover)
                            .size(300)
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onError = { Log.e("DoubanHot", "Failed to load cover: ${item.cover}, error: ${it.result.throwable}") }
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
                // 评分标签
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
            }
            // 标题（黑体）+ 描述
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
private fun SearchHistoryInline(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
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
        history.forEach { keyword ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHistoryClick(keyword) }
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
                Text(
                    text = keyword,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                IconButton(
                    onClick = { onHistoryDelete(keyword) },
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

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("影片名", text))
    Toast.makeText(context, "已复制: $text", Toast.LENGTH_SHORT).show()
}

private fun diskTypeDisplayName(type: DiskType): String = when (type) {
    DiskType.QUARK -> "夸克"
    DiskType.BAIDU -> "百度"
    DiskType.ALI -> "阿里"
    DiskType.XUNLEI -> "迅雷"
    DiskType.UC -> "UC"
    DiskType.ONEONEFIVE -> "115"
    DiskType.MAGNET -> "磁力"
    DiskType.OTHER -> "其他"
}
