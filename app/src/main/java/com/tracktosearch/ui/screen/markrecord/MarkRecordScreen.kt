package com.tracktosearch.ui.screen.markrecord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropSource
import com.tracktosearch.ui.component.rememberCachedPosterAmbientColor
import com.tracktosearch.ui.component.hazeTopBar
import com.tracktosearch.ui.component.isAppDarkTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkRecordScreen(
    onBack: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: MarkRecordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = isAppDarkTheme()
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeMaterials.thin()
    val listState = rememberLazyGridState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    val markRecordGlassScene = glassSceneForContent(
        contentCount = uiState.items.size,
        readabilityDemand = when {
            searchExpanded && uiState.searchQuery.isNotBlank() -> 0.88f
            searchExpanded || uiState.searchQuery.isNotBlank() -> 0.74f
            else -> 0.60f
        },
        ambientColor = rememberCachedPosterAmbientColor(
            posterUrls = uiState.items.mapNotNull { it.posterUrl },
            fallback = MaterialTheme.colorScheme.background
        ),
        contentCapacity = 36,
        loadingCount = if (uiState.isLoading || uiState.isLoadingMore) 1 else 0,
        loadingItemWeight = 4
    )
    val statusBarHeight = WindowInsets.statusBars
        .asPaddingValues().calculateTopPadding()
    val stickyHeaderHeight = statusBarHeight + 100.dp

    // 滚动到底部前若干条时加载下一页
    LaunchedEffect(listState, uiState.items) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 10
        }.distinctUntilChanged().filter { it }.collect {
            viewModel.loadNextPage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // ========== 网格内容 ==========
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .backdropSource(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = stickyHeaderHeight + 5.dp,
                bottom = 40.dp
            )
        ) {
            when {
                uiState.isLoading && uiState.items.isEmpty() -> {
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                uiState.error != null -> {
                    item(span = { GridItemSpan(2) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                uiState.error!!,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.mark_records_retry))
                            }
                        }
                    }
                }
                uiState.items.isEmpty() -> {
                    item(span = { GridItemSpan(2) }) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.Inbox,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                stringResource(when (uiState.currentTab) {
                                    MarkRecordTab.ALL -> R.string.mark_records_empty_all
                                    MarkRecordTab.WATCHLIST -> R.string.mark_records_empty_watchlist
                                    MarkRecordTab.WATCHED -> R.string.mark_records_empty_watched
                                    MarkRecordTab.REMOVED -> R.string.mark_records_empty_removed
                                }),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                else -> {
                    items(
                        uiState.items,
                        key = { "${it.traktId}_${it.actedAt}_${it.actionType}" }
                    ) { item ->
                        MarkRecordItemRow(
                            item = item,
                            posterColorExtractor = viewModel.posterColorExtractor,
                            onClick = {
                                val onClick = if (item.mediaType == "movie") onMovieClick else onShowClick
                                onClick(
                                    item.traktId, item.tmdbId,
                                    item.displayTitle.ifBlank { item.title },
                                    item.imdbId, 0.0
                                )
                            }
                        )
                    }
                    if (uiState.isLoadingMore) {
                        item(span = { GridItemSpan(2) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }

        // ========== 吸顶栏（Blur + 半透明背景） ==========
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hazeTopBar(
                    state = hazeState,
                    style = hazeStyle,
                    blurRadius = 24.dp,
                    scene = markRecordGlassScene
                )
        ) {
            Spacer(modifier = Modifier.statusBarsPadding())

            // 标题栏 + 搜索/筛选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    if (searchExpanded) {
                        IconButton(onClick = {
                            searchExpanded = false
                            viewModel.updateSearchQuery("")
                        }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.content_desc_back)
                            )
                        }
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.mark_records_search_hint), color = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)) },
                            singleLine = true,
                            shape = RoundedCornerShape(999.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Search
                            )
                        )
                        NeumorphicIconButton(
                            onClick = { showFilterSheet = true },
                            isDark = isDark,
                            lightBorderAlpha = 0.35f,
                            hazeState = hazeState,
                            scene = markRecordGlassScene
                        ) {
                            Icon(
                                Icons.Rounded.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.content_desc_back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = stringResource(R.string.mark_records_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        NeumorphicIconButton(
                            onClick = { searchExpanded = true },
                            isDark = isDark,
                            lightBorderAlpha = 0.35f,
                            hazeState = hazeState,
                            scene = markRecordGlassScene
                        ) {
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        NeumorphicIconButton(
                            onClick = { showFilterSheet = true },
                            isDark = isDark,
                            lightBorderAlpha = 0.35f,
                            hazeState = hazeState,
                            scene = markRecordGlassScene
                        ) {
                            Icon(
                                Icons.Rounded.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Tab 切换栏（透明背景）
            PrimaryTabRow(
                selectedTabIndex = uiState.currentTab.ordinal,
                containerColor = Color.Transparent
            ) {
                MarkRecordTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.currentTab == tab,
                        onClick = { viewModel.switchTab(tab) },
                        text = {
                            Text(stringResource(when (tab) {
                                MarkRecordTab.ALL -> R.string.mark_records_tab_all
                                MarkRecordTab.WATCHLIST -> R.string.mark_records_tab_watchlist
                                MarkRecordTab.WATCHED -> R.string.mark_records_tab_watched
                                MarkRecordTab.REMOVED -> R.string.mark_records_tab_removed
                            }))
                        }
                    )
                }
            }
        }

        ScrollToTopButton(
            gridState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp),
            hazeState = hazeState,
            hazeStyle = hazeStyle,
            scene = markRecordGlassScene
        )
    }

    // 筛选弹窗
    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            FilterSheetContent(
                mediaTypes = uiState.filterMediaTypes,
                datePreset = uiState.filterDatePreset,
                dateRange = uiState.filterDateRange,
                ascending = uiState.sortAscending,
                onConfirm = { mediaTypes, preset, range, asc ->
                    viewModel.updateFilter(mediaTypes, preset, range, asc)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.updateFilter(emptySet(), DatePreset.ALL, null, false)
                    showFilterSheet = false
                }
            )
        }
    }
}
