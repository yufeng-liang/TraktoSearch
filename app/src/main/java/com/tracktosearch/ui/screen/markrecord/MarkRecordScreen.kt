package com.tracktosearch.ui.screen.markrecord

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
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
    val listState = rememberLazyGridState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.mark_records_search_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(999.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                imeAction = ImeAction.Search
                            )
                        )
                    } else {
                        Text(stringResource(R.string.mark_records_title))
                    }
                },
                navigationIcon = {
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
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.content_desc_back)
                            )
                        }
                    }
                },
                actions = {
                    if (searchExpanded) {
                        IconButton(onClick = { showFilterSheet = true }) {
                            Icon(Icons.Rounded.FilterList, contentDescription = null)
                        }
                    } else {
                        IconButton(onClick = { searchExpanded = true }) {
                            Icon(Icons.Rounded.Search, contentDescription = null)
                        }
                        IconButton(onClick = { showFilterSheet = true }) {
                            Icon(Icons.Rounded.FilterList, contentDescription = null)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab - 居中分散一行
            PrimaryTabRow(
                selectedTabIndex = uiState.currentTab.ordinal
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
            // 内容
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    uiState.error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.mark_records_retry))
                            }
                        }
                    }
                    uiState.items.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Rounded.Inbox, contentDescription = null, modifier = Modifier.size(48.dp))
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
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            items(uiState.items, key = { "${it.traktId}_${it.actedAt}_${it.actionType}" }) { item ->
                                MarkRecordItemRow(
                                    item = item,
                                    posterColorExtractor = viewModel.posterColorExtractor,
                                    onClick = {
                                        val onClick = if (item.mediaType == "movie") onMovieClick else onShowClick
                                        onClick(item.traktId, item.tmdbId, item.displayTitle.ifBlank { item.title }, item.imdbId, 0.0)
                                    }
                                )
                            }
                            if (uiState.isLoadingMore) {
                                item(span = { GridItemSpan(2) }) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 筛选弹窗
    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState
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
