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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.tracktosearch.ui.component.savePosterToGallery
import com.tracktosearch.ui.component.queryExistingFile
import android.os.Environment
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonMovieCredit
import com.tracktosearch.data.remote.tmdb.dto.TmdbPersonTvCredit
import com.tracktosearch.R
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.MovieCard
import com.tracktosearch.ui.util.showToast
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

private data class AgeInfo(val age: Int, val isDeceased: Boolean)

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
    val context = LocalContext.current

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId)
    }

    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    // 全部作品展开状态
    var showAllMovies by remember { mutableStateOf(false) }
    var showAllTvShows by remember { mutableStateOf(false) }
    var showAllPersonImages by remember { mutableStateOf(false) }
    var selectedPersonImageIndex by remember { mutableIntStateOf(-1) }

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
                                birthday = uiState.person?.birthday ?: uiState.traktPerson?.birthday,
                                deathday = uiState.person?.deathday ?: uiState.traktPerson?.death,
                                placeOfBirth = uiState.person?.place_of_birth,
                                biography = uiState.person?.biography ?: "",
                                knownForDepartment = uiState.person?.known_for_department ?: uiState.traktPerson?.known_for_department ?: "",
                                gender = uiState.person?.gender,
                                traktGender = uiState.traktPerson?.gender ?: "",
                                traktBiography = uiState.traktPerson?.biography ?: "",
                                traktHomepage = uiState.traktPerson?.homepage,
                                isLoading = uiState.isLoading
                            )
                        }

                        // 人物图片横向滑动栏
                        if (uiState.personImages.isNotEmpty() || uiState.isLoadingPersonImages) {
                            item(key = "person_images_section") {
                                Column(modifier = Modifier.padding(top = 16.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stringResource(R.string.person_images),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (uiState.personImages.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.person_all_count, uiState.personImages.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clickable { showAllPersonImages = true }
                                            )
                                        }
                                    }
                                    if (uiState.personImages.isEmpty() && uiState.isLoadingPersonImages) {
                                        // 骨架屏
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            items(5) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(110.dp)
                                                        .height(165.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                )
                                            }
                                        }
                                    } else if (uiState.personImages.isNotEmpty()) {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.personImages, key = { index, url -> "person_img_$index" }) { index, url ->
                                                SubcomposeAsyncImage(
                                                    model = remember(url) {
                                                        ImageRequest.Builder(context)
                                                            .data(url)
                                                            .size(300)
                                                            .crossfade(true)
                                                            .build()
                                                    },
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .width(110.dp)
                                                        .height(165.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { selectedPersonImageIndex = index }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
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
                                        Text(
                                            text = stringResource(R.string.person_all_count, uiState.movieCredits.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { showAllMovies = true }
                                        )
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
                                        Text(
                                            text = stringResource(R.string.person_all_count, uiState.tvCredits.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.clickable { showAllTvShows = true }
                                        )
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

                    // 人物图片大图查看
                    if (selectedPersonImageIndex >= 0 && uiState.personImages.isNotEmpty()) {
                        PersonImagePagerOverlay(
                            images = uiState.personImages,
                            initialIndex = selectedPersonImageIndex,
                            onDismiss = { selectedPersonImageIndex = -1 }
                        )
                    }

                    // 全部人物图片弹窗
                    if (showAllPersonImages && uiState.personImages.isNotEmpty()) {
                        AllPersonImagesSheet(
                            images = uiState.personImages,
                            onImageClick = { index ->
                                showAllPersonImages = false
                                selectedPersonImageIndex = index
                            },
                            onDismiss = { showAllPersonImages = false }
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
    gender: Int? = null,
    traktGender: String = "",
    traktBiography: String = "",
    traktHomepage: String? = null,
    isLoading: Boolean = false
) {
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    var showFullBio by remember { mutableStateOf(false) }

    // 计算年龄数值
    val ageInfo = remember(birthday, deathday) {
        if (birthday != null && birthday.length >= 4) {
            try {
                val birthYear = birthday.substring(0, 4).toInt()
                val birthMonth = if (birthday.length >= 7) birthday.substring(5, 7).toInt() else 1
                val birthDay = if (birthday.length >= 10) birthday.substring(8, 10).toInt() else 1

                if (deathday != null && deathday.length >= 4) {
                    val deathYear = deathday.substring(0, 4).toInt()
                    val deathMonth = if (deathday.length >= 7) deathday.substring(5, 7).toInt() else 12
                    val deathDay = if (deathday.length >= 10) deathday.substring(8, 10).toInt() else 31
                    val age = if (deathMonth > birthMonth || (deathMonth == birthMonth && deathDay >= birthDay)) {
                        deathYear - birthYear
                    } else {
                        deathYear - birthYear - 1
                    }
                    AgeInfo(age, isDeceased = true)
                } else {
                    val now = java.util.Calendar.getInstance()
                    val age = if (now.get(java.util.Calendar.MONTH) + 1 > birthMonth ||
                        (now.get(java.util.Calendar.MONTH) + 1 == birthMonth && now.get(java.util.Calendar.DAY_OF_MONTH) >= birthDay)) {
                        now.get(java.util.Calendar.YEAR) - birthYear
                    } else {
                        now.get(java.util.Calendar.YEAR) - birthYear - 1
                    }
                    AgeInfo(age, isDeceased = false)
                }
            } catch (_: Exception) { null }
        } else null
    }

    // 年龄文本
    val ageText = if (ageInfo != null) {
        if (ageInfo.isDeceased) stringResource(R.string.person_age_deceased, ageInfo.age)
        else "${ageInfo.age}${stringResource(R.string.person_age)}"
    } else null

    // 性别文本：优先用 Trakt（更详细），降级用 TMDB
    val genderText = when {
        traktGender.equals("male", ignoreCase = true) -> stringResource(R.string.person_gender_male)
        traktGender.equals("female", ignoreCase = true) -> stringResource(R.string.person_gender_female)
        traktGender.equals("non_binary", ignoreCase = true) -> stringResource(R.string.person_gender_non_binary)
        gender == 2 -> stringResource(R.string.person_gender_male)
        gender == 1 -> stringResource(R.string.person_gender_female)
        else -> null
    }

    // biography 优先用 TMDB（跟随语言），如果为空则用 Trakt 的
    val displayBiography = if (biography.isNotEmpty()) biography else traktBiography

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
                // 性别显示
                if (genderText != null) {
                    Text(
                        text = genderText,
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
                    // 日期 + 年龄
                    val displayText = if (ageText != null) "$lifeText · $ageText" else lifeText
                    Text(
                        text = displayText,
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
        if (displayBiography.isNotEmpty()) {
            Text(
                text = displayBiography,
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
        // 主页链接
        if (!traktHomepage.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = traktHomepage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(traktHomepage))
                            context.startActivity(intent)
                        } catch (_: Exception) { }
                    }
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

// ==================== 人物图片大图滑动查看 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonImagePagerOverlay(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { images.size })
    // 追踪每张图片的保存状态
    val savedImages = remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 检查当前图片是否已保存
    LaunchedEffect(pagerState.currentPage) {
        val index = pagerState.currentPage
        if (index in savedImages.value) return@LaunchedEffect
        val url = images.getOrNull(index) ?: return@LaunchedEffect
        val fileName = "TrackToSearch_person_${index}.webp"
        val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
        val exists = queryExistingFile(context, fileName, relativePath) != null
        if (exists) {
            savedImages.value = savedImages.value + index
        }
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .statusBarsPadding()
    ) {
        // 图片区域（可点击背景退出）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val imageUrl = images[page]
                AsyncImage(
                    model = remember(imageUrl) {
                        ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(true)
                            .size(1920)
                            .build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // 阻止穿透到背景
                        )
                )
            }
        }

        // 顶部按钮栏（关闭在左，保存在右）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 关闭按钮（左侧）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.detail_close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 保存按钮（右侧）
            val currentIndex = pagerState.currentPage
            val isSaved = currentIndex in savedImages.value
            IconButton(
                onClick = {
                    if (isSaved) {
                        context.showToast(context.getString(R.string.poster_already_saved))
                        return@IconButton
                    }
                    val currentUrl = images[currentIndex]
                    val fileName = "TrackToSearch_person_${currentIndex}.webp"
                    savePosterToGallery(context, scope, currentUrl, fileName) {
                        savedImages.value = savedImages.value + currentIndex
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    if (isSaved) Icons.Filled.Check else Icons.Default.Download,
                    contentDescription = if (isSaved) "已保存" else "保存",
                    tint = if (isSaved) Color(0xFF4CAF50) else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 页码
        Text(
            text = "${pagerState.currentPage + 1}/${images.size}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

// ==================== 全部人物图片弹窗 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AllPersonImagesSheet(
    images: List<String>,
    onImageClick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                    text = stringResource(R.string.person_images),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.detail_close))
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxHeight(0.85f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(images, key = { index, _ -> "person_img_all_$index" }) { index, url ->
                    SubcomposeAsyncImage(
                        model = remember(url) {
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(400)
                                .crossfade(true)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onImageClick(index) }
                    )
                }
            }
        }
    }
}
