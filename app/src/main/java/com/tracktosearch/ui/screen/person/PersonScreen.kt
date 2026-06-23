package com.tracktosearch.ui.screen.person

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.ui.platform.LocalContext
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit
import com.tracktosearch.R
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.MovieCard
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

@Composable
fun PersonScreen(
    personId: Int,
    personName: String,
    profileUrl: String? = null,
    onBack: () -> Unit = {},
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    viewModel: PersonViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId)
    }

    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    // 全部作品展开状态
    var showAllMovies by remember { mutableStateOf(false) }
    var showAllTvShows by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        ) {
            when {
                uiState.isLoading && profileUrl.isNullOrBlank() -> {
                    PersonSkeletonContent()
                }
                uiState.error != null && uiState.person == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.error!!,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .hazeSource(state = hazeState),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        item(key = "person_header") {
                            PersonHeaderContent(
                                personId = uiState.person?.id ?: personId,
                                name = uiState.person?.name ?: personName,
                                profileUrl = profileUrl ?: uiState.person?.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                                birthday = uiState.person?.birthday,
                                deathday = uiState.person?.deathday,
                                placeOfBirth = uiState.person?.place_of_birth,
                                biography = uiState.person?.biography ?: "",
                                knownForDepartment = uiState.person?.known_for_department ?: "",
                                isLoading = uiState.isLoading
                            )
                        }

                        if (uiState.movieCredits.isNotEmpty()) {
                            item(key = "movie_credits_section") {
                                Column {
                                    // 标题 + 全部按钮
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.person_movie_credits),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(
                                            modifier = Modifier
                                                .clickable { showAllMovies = true }
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.person_all),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp)
                                    ) {
                                        itemsIndexed(uiState.movieCredits, key = { index, credit -> "movie_${credit.id}_$index" }) { _, credit ->
                                            CreditCard(
                                                title = credit.title,
                                                subtitle = credit.character,
                                                year = credit.release_date.take(4),
                                                posterUrl = credit.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                                                isResolving = uiState.resolvingTmdbId == credit.id,
                                                onClick = {
                                                    viewModel.resolveAndNavigate(
                                                        tmdbId = credit.id,
                                                        title = credit.title,
                                                        isMovie = true,
                                                        onNavigate = onMovieClick
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.tvCredits.isNotEmpty()) {
                            item(key = "tv_credits_section") {
                                Column {
                                    // 标题 + 全部按钮
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.person_tv_credits),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(
                                            modifier = Modifier
                                                .clickable { showAllTvShows = true }
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.person_all),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp)
                                    ) {
                                        itemsIndexed(uiState.tvCredits, key = { index, credit -> "tv_${credit.id}_$index" }) { _, credit ->
                                            CreditCard(
                                                title = credit.name,
                                                subtitle = credit.character,
                                                year = credit.first_air_date.take(4),
                                                posterUrl = credit.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                                                isResolving = uiState.resolvingTmdbId == credit.id,
                                                onClick = {
                                                    viewModel.resolveAndNavigate(
                                                        tmdbId = credit.id,
                                                        title = credit.name,
                                                        isMovie = false,
                                                        onNavigate = onShowClick
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 返回按钮
                    Box(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(start = 12.dp, top = 4.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .hazeEffect(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    blurRadius = 20.dp,
                                    noiseFactor = 0f,
                                    tint = null
                                )
                            )
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
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
                                onClick = { onBack() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    ScrollToTopButton(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp, end = 16.dp),
                        hazeState = hazeState
                    )

                    // 全部参演电影
                    if (showAllMovies) {
                        AllMovieCreditsSheet(
                            title = stringResource(R.string.person_movie_credits),
                            credits = uiState.movieCredits,
                            resolvingTmdbId = uiState.resolvingTmdbId,
                            hasMore = uiState.hasMoreMovies,
                            onLoadMore = { viewModel.loadMoreMovies() },
                            onMovieClick = onMovieClick,
                            viewModel = viewModel,
                            onDismiss = { showAllMovies = false }
                        )
                    }

                    // 全部参演电视剧
                    if (showAllTvShows) {
                        AllTvCreditsSheet(
                            title = stringResource(R.string.person_tv_credits),
                            credits = uiState.tvCredits,
                            resolvingTmdbId = uiState.resolvingTmdbId,
                            hasMore = uiState.hasMoreTvShows,
                            onLoadMore = { viewModel.loadMoreTvShows() },
                            onShowClick = onShowClick,
                            viewModel = viewModel,
                            onDismiss = { showAllTvShows = false }
                        )
                    }
                }
            }
        }
    }
}

/** 全部参演电影弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllMovieCreditsSheet(
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
                itemsIndexed(credits, key = { index, credit -> "movie_${credit.id}_$index" }) { _, credit ->
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
private fun AllTvCreditsSheet(
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
                itemsIndexed(credits, key = { index, credit -> "tv_${credit.id}_$index" }) { _, credit ->
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

@Composable
private fun PersonSkeletonContent() {
    // 骨架背景色
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    // 文字骨架圆角
    val textShape = RoundedCornerShape(4.dp)
    // 头像/卡片骨架圆角
    val cardShape = RoundedCornerShape(8.dp)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        // 顶部头部区域骨架
        item(key = "skeleton_header") {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 48.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 头像骨架（120x180 圆角矩形）
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(180.dp)
                            .clip(cardShape)
                            .background(skeletonColor)
                    )
                    // 右侧文字骨架
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))
                        // 姓名骨架（宽一些）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(24.dp)
                                .clip(textShape)
                                .background(skeletonColor)
                        )
                        // 信息骨架（窄一些）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.5f)
                                .height(16.dp)
                                .clip(textShape)
                                .background(skeletonColor)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(16.dp)
                                .clip(textShape)
                                .background(skeletonColor)
                        )
                    }
                }

                // 简介区域骨架（3-4 行不同宽度）
                Spacer(modifier = Modifier.height(24.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(16.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
            }
        }

        // 电影作品区域骨架
        item(key = "skeleton_movie_section") {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                // 标题骨架
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .width(120.dp)
                        .height(20.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 横向滚动的卡片骨架
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(4) { _ ->
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(150.dp)
                                .clip(cardShape)
                                .background(skeletonColor)
                        )
                    }
                }
            }
        }

        // 电视剧作品区域骨架
        item(key = "skeleton_tv_section") {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                // 标题骨架
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .width(120.dp)
                        .height(20.dp)
                        .clip(textShape)
                        .background(skeletonColor)
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 横向滚动的卡片骨架
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    items(4) { _ ->
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(150.dp)
                                .clip(cardShape)
                                .background(skeletonColor)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PersonHeaderContent(
    personId: Int,
    name: String,
    profileUrl: String?,
    birthday: String?,
    deathday: String?,
    placeOfBirth: String?,
    biography: String,
    knownForDepartment: String,
    isLoading: Boolean = false
) {
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    var showFullBio by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 48.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头像
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .width(120.dp)
                    .height(180.dp)
            ) {
                if (profileUrl != null) {
                    val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = "person-avatar-$personId"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                .fillMaxSize()
                        }
                    } else {
                        Modifier.fillMaxSize()
                    }
                    SubcomposeAsyncImage(
                        model = remember(profileUrl) {
                            ImageRequest.Builder(context)
                                .data(profileUrl)
                                .size(240)
                                .crossfade(true)
                                .build()
                        },
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = imageModifier,
                        loading = {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        error = {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // 右侧信息
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isLoading && name.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (isLoading && knownForDepartment.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else if (knownForDepartment.isNotEmpty()) {
                    Text(
                        text = knownForDepartment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (isLoading && birthday.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else if (birthday != null && birthday.isNotEmpty()) {
                    val dateText = if (birthday.length >= 10) {
                        "${birthday.substring(0, 4)}-${birthday.substring(5, 7)}-${birthday.substring(8, 10)}"
                    } else birthday
                    val lifeText = if (deathday != null && deathday.isNotEmpty()) {
                        val deathText = if (deathday.length >= 4) deathday.substring(0, 4) else deathday
                        val birthYear = if (birthday.length >= 4) birthday.substring(0, 4) else ""
                        "$birthYear - $deathText"
                    } else {
                        dateText
                    }
                    Text(
                        text = lifeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLoading && placeOfBirth.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else if (placeOfBirth != null && placeOfBirth.isNotEmpty()) {
                    Text(
                        text = placeOfBirth,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 简介
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.detail_overview_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (biography.isNotEmpty()) {
            Text(
                text = biography,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showFullBio) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showFullBio = !showFullBio }
            )
        } else if (isLoading) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(
                                when (index) {
                                    0 -> 1f
                                    1 -> 0.9f
                                    else -> 0.7f
                                }
                            )
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.person_no_biography),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreditCard(
    title: String,
    subtitle: String,
    year: String,
    posterUrl: String?,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(100.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !isResolving,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(100.dp)
                .height(150.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxSize()
            ) {
                if (posterUrl != null) {
                    SubcomposeAsyncImage(
                        model = remember(posterUrl) {
                            ImageRequest.Builder(context)
                                .data(posterUrl)
                                .size(200)
                                .build()
                        },
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        error = {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                } else {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (year.isNotEmpty()) {
            Text(
                text = year,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
