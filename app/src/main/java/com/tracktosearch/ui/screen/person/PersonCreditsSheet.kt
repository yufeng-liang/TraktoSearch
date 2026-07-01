package com.tracktosearch.ui.screen.person

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit

/** 全部参演电影弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllMovieCreditsSheet(
    title: String,
    credits: List<TmdbPersonMovieCredit>,
    resolvingTmdbId: Int?,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: PersonViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.detail_close))
                }
            }

            // Grid 列表
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.85f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(credits, key = { index, credit -> "movie_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                    CreditCard(
                        title = credit.title,
                        subtitle = credit.character,
                        year = credit.release_date.take(4),
                        posterUrl = credit.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                        isResolving = resolvingTmdbId == credit.id,
                        onClick = {
                            viewModel.resolveAndNavigate(
                                tmdbId = credit.id,
                                title = credit.title,
                                isMovie = true,
                                onNavigate = onMovieClick
                            )
                            onDismiss()
                        }
                    )
                }
                if (hasMore) {
                    item(span = { GridItemSpan(3) }) {
                        LaunchedEffect(credits.size) { onLoadMore() }
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

/** 全部参演电视剧弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllTvCreditsSheet(
    title: String,
    credits: List<TmdbPersonTvCredit>,
    resolvingTmdbId: Int?,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: PersonViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.detail_close))
                }
            }

            // Grid 列表
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.85f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(credits, key = { index, credit -> "tv_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                    CreditCard(
                        title = credit.name,
                        subtitle = credit.character,
                        year = credit.first_air_date.take(4),
                        posterUrl = credit.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                        isResolving = resolvingTmdbId == credit.id,
                        onClick = {
                            viewModel.resolveAndNavigate(
                                tmdbId = credit.id,
                                title = credit.name,
                                isMovie = false,
                                onNavigate = onShowClick
                            )
                            onDismiss()
                        }
                    )
                }
                if (hasMore) {
                    item(span = { GridItemSpan(3) }) {
                        LaunchedEffect(credits.size) { onLoadMore() }
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
