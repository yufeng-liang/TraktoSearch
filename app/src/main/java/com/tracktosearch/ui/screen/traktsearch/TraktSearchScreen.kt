package com.tracktosearch.ui.screen.traktsearch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tracktosearch.R
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.ui.component.EmptyView
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.component.MovieCardSkeleton
import com.tracktosearch.ui.util.performHapticClick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraktSearchScreen(
    initialQuery: String,
    type: MediaType,
    onBack: () -> Unit,
    onItemClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: TraktSearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var searchQuery by remember(initialQuery) { mutableStateOf(initialQuery) }

    LaunchedEffect(initialQuery, type) {
        viewModel.initSearch(initialQuery, type)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                if (type == MediaType.MOVIE) stringResource(R.string.trakt_search_hint_movies)
                                else stringResource(R.string.trakt_search_hint_shows)
                            )
                        },
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
                            IconButton(onClick = {
                                if (searchQuery.isNotBlank()) {
                                    viewModel.search(searchQuery)
                                }
                            }) {
                                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.watchlist_search))
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(9) { MovieCardSkeleton() }
                    }
                }
                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            EmptyView(message = uiState.error!!)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.search(uiState.query) }) {
                                Text(stringResource(R.string.watchlist_retry))
                            }
                        }
                    }
                }
                uiState.results.isEmpty() && uiState.hasSearched -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (type == MediaType.MOVIE)
                                stringResource(R.string.trakt_search_no_movies)
                            else
                                stringResource(R.string.trakt_search_no_shows),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.results.size, key = { uiState.results[it].traktId }) { index ->
                            val item = uiState.results[index]
                            MovieCard(
                                title = item.displayTitle,
                                year = item.year,
                                genres = item.genres,
                                posterUrl = item.posterUrl,
                                tmdbId = item.tmdbId,
                                onClick = {
                                    context.performHapticClick()
                                    onItemClick(item.traktId, item.tmdbId, item.title, item.imdbId, item.traktRating)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
