package com.tracktosearch.ui.screen.person

import android.app.Activity
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.OpenImageViewerItem
import com.tracktosearch.ui.component.openImageViewer
import com.tracktosearch.ui.component.recordOpenImageBounds
import com.tracktosearch.ui.component.rememberOpenImageBounds
import com.tracktosearch.ui.component.AppErrorState
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.LocalBackdrop
import com.tracktosearch.ui.component.PosterCard
import com.tracktosearch.ui.component.rememberPosterPrefetch
import com.tracktosearch.ui.component.AdaptiveTwoLineTitle
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.NeumorphicIconButtonStyle
import com.tracktosearch.ui.component.DetailTopBarIcon
import com.tracktosearch.ui.component.ScrollToTopButton
import com.tracktosearch.ui.component.LoadMoreFooter
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.component.SectionHeader
import com.tracktosearch.ui.component.glassSceneForContent
import com.tracktosearch.ui.component.backdropContentSource
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.component.SharedCorner
import com.tracktosearch.ui.component.SharedOrigin
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.posterSharedKey
import com.tracktosearch.ui.navigation.DetailSeedStore
import com.tracktosearch.ui.haptic.HapticOutcomeEffect
import com.tracktosearch.ui.util.ToastEffect
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource

/**
 * 人物页作品栏的 origin 基名。
 *
 * [SharedOrigin] 只收跨页面配对用到的公共值，某个屏幕私有的细分值就近声明在该屏幕文件里。
 */
private const val PERSON_CREDIT_ORIGIN_BASE = "person-credit"

/**
 * 人物页某一条作品的 origin。
 *
 * 一个人的作品表里同一个 tmdbId 可能出现两次（既演又导），电影栏与电视剧栏也各有一份，
 * 只靠 tmdbId 分不出点的是哪一张。槽位与 LazyRow 的 item key 同构（栏目 + tmdbId + 下标）。
 */
private fun personCreditOrigin(section: String, tmdbId: Int, index: Int): String =
    SharedOrigin.of(PERSON_CREDIT_ORIGIN_BASE, "${section}_${tmdbId}_$index")

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
    val activity = context as? Activity

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(personId) {
        viewModel.loadPerson(personId, profileUrl, avatarColor)
    }

    ToastEffect(viewModel.toastEvent)

    // 卡片点开解析失败那几条 toast 配对的 reject 都从这一行出
    HapticOutcomeEffect(viewModel.hapticOutcomes)

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
    // 全部作品展开状态
    var showAllMovies by rememberSaveable { mutableStateOf(false) }
    var showAllTvShows by rememberSaveable { mutableStateOf(false) }
    var showAllPersonImages by rememberSaveable { mutableStateOf(false) }
    // 将 gridState 提升到屏幕级，使用 rememberSaveable 保留导航往返后的滚动位置
    val movieCreditsGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val tvCreditsGridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    // 作品横向滑动列表的滚动状态（配合海报预取）
    val movieCreditsRowState = rememberLazyListState()
    val tvCreditsRowState = rememberLazyListState()

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { padding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        // 透明度与 PersonImmersiveTintAlpha 共用，头部文字据此反推真实底色选前景色
                        uiState.avatarDominantColor?.copy(alpha = PersonImmersiveTintAlpha)
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
                        AppErrorState(
                            message = uiState.error!!,
                            onRetry = { viewModel.loadPerson(personId, profileUrl, avatarColor) }
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
                            .backdropContentSource(),
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
                                        // 顶部人物图横栏的查看器数据与缩略图矩形（下标与 uiState.personImages 对齐）
                                        val personRowViewerItems = remember(uiState.personImages) {
                                            uiState.personImages.map {
                                                OpenImageViewerItem(
                                                    largeUrl = TmdbImageUrls.swapSize(it, "original"),
                                                    coverUrl = it
                                                )
                                            }
                                        }
                                        val personRowBounds = rememberOpenImageBounds()
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.personImages, key = { index, url -> "person_img_$index" }, contentType = { _, _ -> "image" }) { index, url ->
                                                PosterCard(
                                                    imageUrl = url,
                                                    title = uiState.person?.name ?: personName,
                                                    onClick = {
                                                        val currentActivity = activity
                                                        if (currentActivity != null) {
                                                            // 点开即 OpenImage：大图 original，cover 用横栏当前 URL
                                                            openImageViewer(
                                                                activity = currentActivity,
                                                                items = personRowViewerItems,
                                                                bounds = personRowBounds,
                                                                clickedIndex = index
                                                            )
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .width(110.dp)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant,
                                                            RoundedCornerShape(14.dp)
                                                        ),
                                                    // 记录每张海报的 window 矩形，打开动画以点击那张为落点
                                                    posterModifier = Modifier.recordOpenImageBounds(index, personRowBounds),
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
                                        val moviePosterUrls = remember(uiState.movieCredits) {
                                            uiState.movieCredits.map { it.poster_path?.let { p -> TmdbImageUrls.build(p) } }
                                        }
                                        // decodeSizePx 与 CreditPosterCard 的 PosterCard(imageSize = 264) 对齐
                                        rememberPosterPrefetch(movieCreditsRowState, moviePosterUrls, decodeSizePx = 264)
                                        LazyRow(
                                            state = movieCreditsRowState,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.movieCredits, key = { index, credit -> "movie_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { index, credit ->
                                                val posterUrl = credit.poster_path?.let { TmdbImageUrls.build(it) }
                                                val year = credit.release_date.take(4)
                                                val origin = personCreditOrigin("movie", credit.id, index)
                                                // 只有被点过的那一张才挂共享元素修饰符
                                                var clicked by remember { mutableStateOf(false) }
                                                CreditPosterCard(
                                                    title = credit.title,
                                                    subtitle = credit.character,
                                                    year = year,
                                                    posterUrl = posterUrl,
                                                    isResolving = uiState.resolvingTmdbId == credit.id,
                                                    onClick = {
                                                        clicked = true
                                                        // 作品表的海报来自 TMDB 人物作品接口，不写详情缓存，
                                                        // 详情页 peek 必然落空，交给它做首帧种子；
                                                        // origin 让详情页拼出与本卡片相同的共享元素 key
                                                        DetailSeedStore.remember(
                                                            credit.id,
                                                            posterUrl,
                                                            year.toIntOrNull(),
                                                            origin = origin
                                                        )
                                                        viewModel.resolveAndNavigate(
                                                            tmdbId = credit.id,
                                                            title = credit.title,
                                                            isMovie = true,
                                                            onNavigate = onMovieClick
                                                        )
                                                    },
                                                    posterModifier = Modifier.appSharedBounds(
                                                        key = if (clicked) posterSharedKey(credit.id, origin) else null,
                                                        corner = SharedCorner.uniform(12.dp),
                                                    )
                                                )
                                            }
                                            if (uiState.movieCredits.isNotEmpty()) {
                                                item(key = "movie_load_more") {
                                                    if (uiState.hasMoreMovies && !uiState.isLoadingMoreMovies && !uiState.loadMoreMoviesError) {
                                                        LaunchedEffect(Unit) { viewModel.loadMoreMovies() }
                                                    }
                                                    LoadMoreFooter(
                                                        state = when {
                                                            uiState.isLoadingMoreMovies -> LoadMoreFooterState.Loading
                                                            uiState.loadMoreMoviesError -> LoadMoreFooterState.Error
                                                            !uiState.hasMoreMovies -> LoadMoreFooterState.Complete
                                                            else -> LoadMoreFooterState.Hidden
                                                        },
                                                        onRetry = viewModel::loadMoreMovies,
                                                        modifier = Modifier.width(180.dp)
                                                    )
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
                                        val tvPosterUrls = remember(uiState.tvCredits) {
                                            uiState.tvCredits.map { it.poster_path?.let { p -> TmdbImageUrls.build(p) } }
                                        }
                                        // decodeSizePx 与 CreditPosterCard 的 PosterCard(imageSize = 264) 对齐
                                        rememberPosterPrefetch(tvCreditsRowState, tvPosterUrls, decodeSizePx = 264)
                                        LazyRow(
                                            state = tvCreditsRowState,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            itemsIndexed(uiState.tvCredits, key = { index, credit -> "tv_${credit.id}_$index" }, contentType = { _, _ -> "media_card" }) { index, credit ->
                                                val posterUrl = credit.poster_path?.let { TmdbImageUrls.build(it) }
                                                val year = credit.first_air_date.take(4)
                                                val origin = personCreditOrigin("tv", credit.id, index)
                                                // 只有被点过的那一张才挂共享元素修饰符
                                                var clicked by remember { mutableStateOf(false) }
                                                CreditPosterCard(
                                                    title = credit.name,
                                                    subtitle = credit.character,
                                                    year = year,
                                                    posterUrl = posterUrl,
                                                    isResolving = uiState.resolvingTmdbId == credit.id,
                                                    onClick = {
                                                        clicked = true
                                                        // 理由同电影栏：列表接口的海报进不了详情缓存，
                                                        // 海报与 origin 一起交给详情页
                                                        DetailSeedStore.remember(
                                                            credit.id,
                                                            posterUrl,
                                                            year.toIntOrNull(),
                                                            origin = origin
                                                        )
                                                        viewModel.resolveAndNavigate(
                                                            tmdbId = credit.id,
                                                            title = credit.name,
                                                            isMovie = false,
                                                            onNavigate = onShowClick
                                                        )
                                                    },
                                                    posterModifier = Modifier.appSharedBounds(
                                                        key = if (clicked) posterSharedKey(credit.id, origin) else null,
                                                        corner = SharedCorner.uniform(12.dp),
                                                    )
                                                )
                                            }
                                            if (uiState.tvCredits.isNotEmpty()) {
                                                item(key = "tv_load_more") {
                                                    if (uiState.hasMoreTvShows && !uiState.isLoadingMoreTvShows && !uiState.loadMoreTvShowsError) {
                                                        LaunchedEffect(Unit) { viewModel.loadMoreTvShows() }
                                                    }
                                                    LoadMoreFooter(
                                                        state = when {
                                                            uiState.isLoadingMoreTvShows -> LoadMoreFooterState.Loading
                                                            uiState.loadMoreTvShowsError -> LoadMoreFooterState.Error
                                                            !uiState.hasMoreTvShows -> LoadMoreFooterState.Complete
                                                            else -> LoadMoreFooterState.Hidden
                                                        },
                                                        onRetry = viewModel::loadMoreTvShows,
                                                        modifier = Modifier.width(180.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 返回按钮与回顶按钮：置于采样源之外(LocalBackdrop=null)，glass 模式下
                    // 由 appVisualEffect/GlassIconButton 退化到 Haze 实时采样后跟随滚动内容，
                    // 避免 Backdrop 静态快照采样导致按钮停在渐变区时无法实时刷新。
                    CompositionLocalProvider(LocalBackdrop provides null) {
                    // 返回按钮：与影视详情页统一使用拟态玻璃 + Haze 背景采样
                    val personIsDark = isAppDarkTheme()
                    NeumorphicIconButton(
                        onClick = {
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
                    } // CompositionLocalProvider(LocalBackdrop provides null)

                    // 全部参演电影
                    if (showAllMovies) {
                        AllMovieCreditsSheet(
                            title = stringResource(R.string.person_movie_credits),
                            credits = uiState.movieCredits,
                            resolvingTmdbId = uiState.resolvingTmdbId,
                            hasMore = uiState.hasMoreMovies,
                            isLoadingMore = uiState.isLoadingMoreMovies,
                            loadMoreError = uiState.loadMoreMoviesError,
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
                            isLoadingMore = uiState.isLoadingMoreTvShows,
                            loadMoreError = uiState.loadMoreTvShowsError,
                            onLoadMore = { viewModel.loadMoreTvShows() },
                            onShowClick = onShowClick,
                            viewModel = viewModel,
                            onDismiss = { showAllTvShows = false },
                            gridState = tvCreditsGridState
                        )
                    }

                    // 全部人物图片面板（内联，替代原 ModalBottomSheet）
                    // 面板与页面同 window：网格缩略图矩形有效，单元点击直接带转场打开查看器
                    AllPersonImagesPanel(
                        visible = showAllPersonImages && uiState.personImages.isNotEmpty(),
                        images = uiState.personImages,
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
    onClick: () -> Unit,
    /** 作用于海报容器的修饰符，用于共享元素转场；由调用方按自己那一栏的槽位构造 */
    posterModifier: Modifier = Modifier
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
                posterModifier = posterModifier,
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
// - PersonImageOverlay.kt: AllPersonImagesPanel
