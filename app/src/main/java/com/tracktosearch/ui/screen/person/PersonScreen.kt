package com.tracktosearch.ui.screen.person

import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.util.showToast
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
    val context = LocalContext.current

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId)
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { resId ->
            context.showToast(context.getString(resId))
        }
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
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        item(key = "person_header") {
                            PersonHeaderContent(
                                personId = uiState.person?.id ?: personId,
                                name = uiState.person?.name ?: personName,
                                profileUrl = profileUrl ?: uiState.person?.profile_path?.let { TmdbImageUrls.build(it) },
                                birthday = uiState.person?.birthday ?: uiState.traktPerson?.birthday,
                                deathday = uiState.person?.deathday ?: uiState.traktPerson?.death,
                                placeOfBirth = uiState.person?.place_of_birth,
                                biography = uiState.person?.biography ?: "",
                                knownForDepartment = uiState.person?.known_for_department ?: uiState.traktPerson?.known_for_department ?: "",
                                gender = uiState.person?.gender,
                                traktGender = uiState.traktPerson?.gender ?: "",
                                traktBiography = uiState.traktPerson?.biography ?: "",
                                traktHomepage = uiState.traktPerson?.homepage,
                                isLoading = uiState.isLoading,
                                facebookId = uiState.traktPerson?.social_ids?.facebook,
                                instagramId = uiState.traktPerson?.social_ids?.instagram,
                                twitterId = uiState.traktPerson?.social_ids?.twitter,
                                wikipediaUrl = uiState.traktPerson?.social_ids?.wikipedia,
                                originalName = uiState.originalName,
                                traktPerson = uiState.traktPerson,
                                isLoadingTrakt = uiState.isLoadingTrakt
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
                                            itemsIndexed(uiState.personImages, key = { index, url -> "person_img_$index" }, contentType = { _, _ -> "image" }) { index, url ->
                                                SubcomposeAsyncImage(
                                                    model = remember(url) {
                                                        ImageRequest.Builder(context)
                                                            .data(url)
                                                            .size(200)
                                                            .crossfade(false)
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

                        if (uiState.movieCredits.isNotEmpty() || uiState.isLoadingMovies) {
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
                                        if (uiState.movieCredits.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.person_all_count, uiState.movieCredits.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clickable { showAllMovies = true }
                                            )
                                        }
                                    }
                                    if (uiState.movieCredits.isEmpty() && uiState.isLoadingMovies) {
                                        // 骨架屏
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            items(5) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(120.dp)
                                                        .height(180.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                )
                                            }
                                        }
                                    } else {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.movieCredits, key = { index, credit -> "movie_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                                                CreditCard(
                                                    title = credit.title,
                                                    subtitle = credit.character,
                                                    year = credit.release_date.take(4),
                                                    posterUrl = credit.poster_path?.let { TmdbImageUrls.build(it) },
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
                                            if (uiState.hasMoreMovies) {
                                                item(key = "movie_load_more") {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(60.dp)
                                                            .height(90.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (uiState.isLoadingMoreMovies) {
                                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                        } else {
                                                            LaunchedEffect(Unit) { viewModel.loadMoreMovies() }
                                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.tvCredits.isNotEmpty() || uiState.isLoadingTvShows) {
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
                                        if (uiState.tvCredits.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.person_all_count, uiState.tvCredits.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.clickable { showAllTvShows = true }
                                            )
                                        }
                                    }
                                    if (uiState.tvCredits.isEmpty() && uiState.isLoadingTvShows) {
                                        // 骨架屏
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            items(5) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(120.dp)
                                                        .height(180.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                )
                                            }
                                        }
                                    } else {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.tvCredits, key = { index, credit -> "tv_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                                                CreditCard(
                                                    title = credit.name,
                                                    subtitle = credit.character,
                                                    year = credit.first_air_date.take(4),
                                                    posterUrl = credit.poster_path?.let { TmdbImageUrls.build(it) },
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
                                            if (uiState.hasMoreTvShows) {
                                                item(key = "tv_load_more") {
                                                    Box(
                                                        modifier = Modifier
                                                            .width(60.dp)
                                                            .height(90.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (uiState.isLoadingMoreTvShows) {
                                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                        } else {
                                                            LaunchedEffect(Unit) { viewModel.loadMoreTvShows() }
                                                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                                        }
                                                    }
                                                }
                                            }
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
                            Icons.AutoMirrored.Rounded.ArrowBack,
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

// 此文件已拆分，提取的组件位于：
// - PersonCreditsSheet.kt: AllMovieCreditsSheet, AllTvCreditsSheet
// - PersonSkeletonContent.kt: PersonSkeletonContent
// - PersonHeaderContent.kt: PersonHeaderContent
// - PersonCreditCard.kt: CreditCard
// - PersonImageOverlay.kt: PersonImagePagerOverlay, AllPersonImagesSheet
