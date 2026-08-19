package com.tracktosearch.ui.screen.person

import androidx.activity.compose.BackHandler
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionEnabled
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.PosterCard
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.NeumorphicIconButtonStyle
import com.tracktosearch.ui.component.DetailTopBarIcon
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.SectionHeader
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropSource
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.ToastEffect
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PersonScreen(
    personId: Int,
    personName: String,
    profileUrl: String? = null,
    avatarColor: androidx.compose.ui.graphics.Color? = null,
    onBack: () -> Unit = {},
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit = { _, _, _, _, _ -> },
    viewModel: PersonViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId, profileUrl, avatarColor)
    }

    ToastEffect(viewModel.toastEvent)

    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    val personGlassScene = glassSceneForContent(
        contentCount = uiState.personImages.size + uiState.movieCredits.size + uiState.tvCredits.size,
        readabilityDemand = when {
            uiState.movieCredits.isNotEmpty() || uiState.tvCredits.isNotEmpty() -> 0.72f
            uiState.personImages.isNotEmpty() -> 0.56f
            else -> 0.38f
        },
        ambientColor = uiState.avatarDominantColor ?: MaterialTheme.colorScheme.background,
        contentCapacity = 48,
        loadingCount = listOf(
            uiState.isLoading,
            uiState.isLoadingMovies,
            uiState.isLoadingTvShows,
            uiState.isLoadingPersonImages,
            uiState.isLoadingTrakt,
            uiState.isLoadingMoreMovies,
            uiState.isLoadingMoreTvShows
        ).count { it },
        loadingItemWeight = 4
    )
    val view = LocalView.current
    // 全部作品展开状态
    var showAllMovies by rememberSaveable { mutableStateOf(false) }
    var showAllTvShows by rememberSaveable { mutableStateOf(false) }
    var showAllPersonImages by rememberSaveable { mutableStateOf(false) }
    var selectedPersonImageIndex by rememberSaveable { mutableIntStateOf(-1) }
    // 当前全屏图片来源前缀：决定 sharedElement 与哪端缩略图配对
    var personImageFullscreenKey by remember { mutableStateOf<String?>(null) }
    // 将 gridState 提升到屏幕级，使用 rememberSaveable 保留导航往返后的滚动位置
    val movieCreditsGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val tvCreditsGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        uiState.avatarDominantColor?.copy(alpha = 0.70f)
                            ?: MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
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
                            .hazeSource(state = hazeState)
                            .backdropSource(),
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
                                isLoadingTrakt = uiState.isLoadingTrakt,
                                avatarDominantColor = uiState.avatarDominantColor
                            )
                        }

                        // 人物图片横向滑动栏
                        if (uiState.personImages.isNotEmpty() || uiState.isLoadingPersonImages) {
                            item(key = "person_images_section") {
                                Column(modifier = Modifier.padding(top = 16.dp)) {
                                    SectionHeader(
                                        title = stringResource(R.string.person_images),
                                        actionText = if (uiState.personImages.isNotEmpty()) {
                                            stringResource(R.string.person_all_count, uiState.personImages.size)
                                        } else null,
                                        onActionClick = if (uiState.personImages.isNotEmpty()) {
                                            { showAllPersonImages = true }
                                        } else null,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp)
                                    )
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
                                                        .clip(RoundedCornerShape(14.dp))
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
                                                val sharedModifier = if (LocalSharedTransitionScope.current != null && LocalAnimatedVisibilityScope.current != null && LocalSharedTransitionEnabled.current) {
                                                    val scope = LocalSharedTransitionScope.current
                                                    with(scope!!) {
                                                        Modifier.sharedElement(
                                                            rememberSharedContentState(key = "person-row-$personId-$index"),
                                                            animatedVisibilityScope = LocalAnimatedVisibilityScope.current!!
                                                        )
                                                    }
                                                } else Modifier
                                                PosterCard(
                                                    imageUrl = url,
                                                    title = uiState.person?.name ?: personName,
                                                    onClick = {
                                                        personImageFullscreenKey = "person-row-$personId"
                                                        selectedPersonImageIndex = index
                                                    },
                                                    modifier = Modifier
                                                        .width(110.dp)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant,
                                                            RoundedCornerShape(14.dp)
                                                        ),
                                                    posterModifier = sharedModifier,
                                                    imageSize = 200
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
                                    SectionHeader(
                                        title = stringResource(R.string.person_movie_credits),
                                        actionText = if (uiState.movieCredits.isNotEmpty()) {
                                            stringResource(R.string.person_all_count, uiState.movieCredits.size)
                                        } else null,
                                        onActionClick = if (uiState.movieCredits.isNotEmpty()) {
                                            { showAllMovies = true }
                                        } else null,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp)
                                    )
                                    if (uiState.movieCredits.isEmpty() && uiState.isLoadingMovies) {
                                        // 骨架屏
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            items(5) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(100.dp)
                                                        .height(150.dp)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                )
                                            }
                                        }
                                    } else {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.movieCredits, key = { index, credit -> "movie_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                                                CreditPosterCard(
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
                                    SectionHeader(
                                        title = stringResource(R.string.person_tv_credits),
                                        actionText = if (uiState.tvCredits.isNotEmpty()) {
                                            stringResource(R.string.person_all_count, uiState.tvCredits.size)
                                        } else null,
                                        onActionClick = if (uiState.tvCredits.isNotEmpty()) {
                                            { showAllTvShows = true }
                                        } else null,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp)
                                    )
                                    if (uiState.tvCredits.isEmpty() && uiState.isLoadingTvShows) {
                                        // 骨架屏
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            items(5) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(100.dp)
                                                        .height(150.dp)
                                                        .clip(RoundedCornerShape(14.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                                )
                                            }
                                        }
                                    } else {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.tvCredits, key = { index, credit -> "tv_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { _, credit ->
                                                CreditPosterCard(
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

                    // 返回按钮：与影视详情页统一使用拟态玻璃 + Haze 背景采样
                    val personIsDark = isAppDarkTheme()
                    NeumorphicIconButton(
                        onClick = {
                            view.performHaptic(HapticType.TICK)
                            onBack()
                        },
                        isDark = personIsDark,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(start = 12.dp, top = 4.dp),
                        hazeState = hazeState,
                        hazeStyle = HazeMaterials.ultraThin(),
                        size = 40.dp,
                        buttonStyle = NeumorphicIconButtonStyle.DetailTopBar,
                        scene = personGlassScene
                    ) {
                        DetailTopBarIcon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back),
                            size = 24.dp
                        )
                    }

                    ScrollToTopButton(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 16.dp, end = 16.dp),
                        hazeState = hazeState,
                        scene = personGlassScene
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
                            onDismiss = { showAllMovies = false },
                            gridState = movieCreditsGridState
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
                            onDismiss = { showAllTvShows = false },
                            gridState = tvCreditsGridState
                        )
                    }

                    // 人物图片大图查看（内联，共享转场需同 window）
                    PersonImagePagerOverlay(
                        visible = selectedPersonImageIndex >= 0 && uiState.personImages.isNotEmpty() && personImageFullscreenKey != null,
                        images = uiState.personImages,
                        initialIndex = selectedPersonImageIndex.coerceAtLeast(0),
                        sharedKeyPrefix = personImageFullscreenKey,
                        onDismiss = { selectedPersonImageIndex = -1 }
                    )

                    // 全部人物图片面板（内联，替代原 ModalBottomSheet）
                    AllPersonImagesPanel(
                        visible = showAllPersonImages && uiState.personImages.isNotEmpty(),
                        images = uiState.personImages,
                        personId = personId,
                        onImageClick = { index ->
                            personImageFullscreenKey = "person-grid-$personId"
                            selectedPersonImageIndex = index
                            showAllPersonImages = false // 关闭面板，全屏从网格原位缩放飞出
                        },
                        onDismiss = { showAllPersonImages = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreditPosterCard(
    title: String,
    subtitle: String,
    year: String,
    posterUrl: String?,
    isResolving: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.width(100.dp)) {
            PosterCard(
                imageUrl = posterUrl,
                title = title,
                year = year.takeIf { it.isNotEmpty() },
                onClick = if (isResolving) null else onClick,
                modifier = Modifier
                    .width(100.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(14.dp)
                    ),
                imageSize = 264
            )
            if (isResolving) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(14.dp))
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
        AdaptiveTwoLineTitle(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, textAlign = TextAlign.Center),
            maxFontSize = 11.sp,
            minFontSize = 11.sp,
            modifier = Modifier.fillMaxWidth()
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// 此文件已拆分，提取的组件位于：
// - PersonCreditsSheet.kt: AllMovieCreditsSheet, AllTvCreditsSheet
// - PersonSkeletonContent.kt: PersonSkeletonContent
// - PersonHeaderContent.kt: PersonHeaderContent
// - PersonCreditCard.kt: CreditCard
// - PersonImageOverlay.kt: PersonImagePagerOverlay, AllPersonImagesPanel
