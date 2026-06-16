package com.tracktosearch.ui.screen.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.local.ViewedItemStorage
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
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf(initialKeyword) }
    val context = LocalContext.current
    val viewedItemStorage = remember {
        EntryPointAccessors.fromApplication(context, ViewedStorageProvider::class.java).viewedItemStorage()
    }
    val viewedUrls by viewedItemStorage.viewedUrls.collectAsState(initial = emptySet())

    // 搜索历史是否已加载完成（避免异步加载时闪过默认空页面）
    val searchHistoryLoaded by remember { derivedStateOf { uiState.searchHistoryLoaded } }

    // 搜索框清空时同步清除结果（仅当之前有内容时才清除，避免切换Tab回来时误清）
    var hadQuery by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty() && hadQuery) {
            viewModel.clearResults()
        }
        hadQuery = searchQuery.isNotEmpty()
    }

    LaunchedEffect(initialKeyword) {
        if (initialKeyword.isNotEmpty()) {
            searchQuery = initialKeyword
            viewModel.search(initialKeyword)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
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
            // 搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (searchQuery.isNotBlank()) {
                            viewModel.search(searchQuery)
                        }
                    }
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 清空输入图标
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
                            // 搜索按钮
                            TextButton(onClick = {
                                viewModel.search(searchQuery)
                            }) {
                                Text(stringResource(R.string.search_button))
                            }
                        }
                    }
                }
            )

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
                    // 网盘类型筛选器
                    var selectedDiskType by remember { mutableStateOf<DiskType?>(null) }
                    var filterExpanded by remember { mutableStateOf(false) }

                    val filteredResources = if (selectedDiskType != null) {
                        uiState.resources.filter { it.diskType == selectedDiskType }
                    } else {
                        uiState.resources
                    }

                    // 搜索结果列表
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
                                    // 网盘类型筛选器
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
                                            DiskType.entries.forEach { type ->
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
                else -> {
                    // 搜索历史
                    if (uiState.searchHistory.isNotEmpty()) {
                        SearchHistoryList(
                            history = uiState.searchHistory,
                            onHistoryClick = { keyword ->
                                searchQuery = keyword
                                viewModel.search(keyword)
                            },
                            onHistoryDelete = { viewModel.removeHistory(it) },
                            onClearAll = { viewModel.clearHistory() }
                        )
                    } else if (searchHistoryLoaded) {
                        // 默认空白页（搜索历史加载完成后显示）
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.search_default_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryList(
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryDelete: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 标题行：搜索历史 + 清空按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onClearAll) {
                Text(
                    text = stringResource(R.string.search_history_clear_all),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            items(history, key = { it }) { keyword ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHistoryClick(keyword) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = keyword,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    IconButton(
                        onClick = { onHistoryDelete(keyword) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.search_history_delete),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun openResourceLink(context: android.content.Context, item: ResourceItem) {
    val url = item.url

    // 网盘类型对应的 App 包名（优先网盘独立App，其次浏览器）
    val appPackages = when (item.diskType) {
        DiskType.QUARK -> listOf("com.quark.clouddrive", "com.quark.browser")
        DiskType.BAIDU -> listOf("com.baidu.netdisk")
        DiskType.ALI -> listOf("com.alicloud.databox")
        DiskType.XUNLEI -> listOf("com.xunlei.downloadprovider", "com.xunlei.browser")
        DiskType.UC -> listOf("com.UCMobile")
        DiskType.ONEONEFIVE -> listOf("com.crland.app")
        DiskType.MAGNET, DiskType.OTHER -> emptyList()
    }

    // 优先尝试用对应网盘 App 打开实际 URL
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
    // Fallback: 浏览器打开
    try {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(browserIntent)
    } catch (_: Exception) {}
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
