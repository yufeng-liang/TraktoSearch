package com.tracktosearch.ui.screen.detail

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DetailSectionStorage
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.TokenStorage
import com.tracktosearch.data.local.ViewedItemStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarDetail
import com.tracktosearch.data.remote.douban.DoubanRexxarImage
import com.tracktosearch.data.remote.douban.DoubanRexxarMediaType
import com.tracktosearch.data.remote.douban.DoubanRexxarPhoto
import com.tracktosearch.data.remote.douban.DoubanRexxarPhotoPage
import com.tracktosearch.data.remote.douban.DoubanRexxarRepository
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.MediaMetadataRepository
import com.tracktosearch.data.repository.MediaSummary
import com.tracktosearch.data.repository.TitleSource
import com.tracktosearch.data.repository.MultiRatings
import com.tracktosearch.data.repository.RatingsRepository
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.repository.TmdbRepository
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.repository.UserReviewRepository
import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.data.session.SessionModeManager
import com.tracktosearch.data.util.CommentTranslator
import com.tracktosearch.data.util.PosterColorExtractor
import com.tracktosearch.test.MainDispatcherRule
import com.tracktosearch.ui.navigation.DetailSeedStore
import com.tracktosearch.ui.component.SharedOrigin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DetailViewModelDoubanTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var tmdbRepository: TmdbRepository
    private lateinit var mediaMetadataRepository: MediaMetadataRepository
    private lateinit var traktRepository: TraktRepository
    private lateinit var resourceRepository: ResourceRepository
    private lateinit var ratingsRepository: RatingsRepository
    private lateinit var viewedItemStorage: ViewedItemStorage
    private lateinit var commentTranslator: CommentTranslator
    private lateinit var tokenStorage: TokenStorage
    private lateinit var detailSectionStorage: DetailSectionStorage
    private lateinit var languageStorage: LanguageStorage
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var doubanRexxarRepository: DoubanRexxarRepository
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var sessionModeManager: SessionModeManager
    private lateinit var sessionMode: MutableStateFlow<SessionMode>
    private lateinit var traktConnected: MutableStateFlow<Boolean>
    private lateinit var posterColorExtractor: PosterColorExtractor
    private lateinit var userReviewRepository: UserReviewRepository
    private lateinit var aiProfileBehaviorRecorder: com.tracktosearch.data.ai.AiProfileBehaviorRecorder
    private lateinit var viewModel: DetailViewModel

    @Before
    fun setup() {
        val cacheField = DetailViewModel::class.java.getDeclaredField("detailCache")
        cacheField.isAccessible = true
        (cacheField.get(null) as MutableMap<*, *>).clear()
        // 首帧种子暂存是进程级单例，跨用例会互相污染 year/posterUrl 的兜底值
        DetailSeedStore.clear()

        tmdbRepository = mockk(relaxed = true)
        mediaMetadataRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        resourceRepository = mockk(relaxed = true)
        ratingsRepository = mockk(relaxed = true)
        viewedItemStorage = mockk(relaxed = true)
        commentTranslator = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)
        detailSectionStorage = mockk(relaxed = true)
        languageStorage = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        doubanRexxarRepository = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        sessionModeManager = mockk(relaxed = true)
        sessionMode = MutableStateFlow(SessionMode.TRAKT)
        traktConnected = MutableStateFlow(false)
        posterColorExtractor = mockk(relaxed = true)
        userReviewRepository = mockk(relaxed = true)
        aiProfileBehaviorRecorder = mockk(relaxed = true)

        every { tokenStorage.accessToken } returns flowOf(null)
        every { detailSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { languageStorage.language } returns MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)
        every { sessionModeManager.sessionMode } returns sessionMode
        every { sessionModeManager.traktConnected } returns traktConnected
        every { resourceRepository.filterItems(any(), any(), any()) } returns emptyList()
        every {
            ratingsRepository.fetchRatingsStream(any(), any(), any())
        } returns flowOf(MultiRatings())
        coEvery {
            doubanRexxarRepository.getDetail(any(), any(), any())
        } returns Result.failure(IllegalStateException("Rexxar not stubbed"))
        coEvery {
            doubanRexxarRepository.getPhotos(any(), any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("Rexxar photos not stubbed"))
        every { mediaMetadataRepository.peekSummaryLocal(any()) } returns null
        coEvery { mediaMetadataRepository.getCachedSummary(any()) } returns null
        coEvery { mediaMetadataRepository.getSummaries(any(), any()) } returns emptyList()

        viewModel = DetailViewModel(
            tmdbRepository,
            mediaMetadataRepository,
            traktRepository,
            resourceRepository,
            ratingsRepository,
            viewedItemStorage,
            commentTranslator,
            tokenStorage,
            detailSectionStorage,
            languageStorage,
            doubanRepository,
            doubanRexxarRepository,
            doubanAuthStorage,
            doubanSyncedItemDao,
            sessionModeManager,
            posterColorExtractor,
            userReviewRepository, aiProfileBehaviorRecorder
        )
    }

    @Test
    fun doubanModeKeepsCommentsSectionVisible() = runTest {
        sessionMode.value = SessionMode.DOUBAN
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.sectionVisible.comments).isTrue()
    }

    @Test
    fun suppliedDoubanIdLoadsSnapshotAndCachedDoubanDetailForBasicDetails() = runTest {
        val item = DoubanSyncedItem(
            doubanId = "db-1",
            imdbId = "tt1234567",
            traktId = null,
            title = "Snapshot title",
            status = "wish",
            rating = 4,
            syncedAt = 100L,
            mediaType = "movie",
            displayTitle = "Local display title",
            year = 2020,
            genres = "Drama / Mystery",
            posterUrl = "https://img.example/snapshot.jpg",
            doubanUrl = "https://movie.douban.com/subject/db-1/",
            comment = "My note",
            markedAt = "2024-01-02",
            subtitle = "Original title"
        )
        val detail = DoubanDetailCacheEntry(
            imdbId = "tt1234567",
            isTvShow = false,
            title = "Cached Douban title",
            posterUrl = "https://img.example/cache.jpg",
            genres = listOf("Drama", "Mystery"),
            year = "2020",
            countries = listOf("China"),
            directors = listOf("Director"),
            doubanRating = 8.7,
            summary = "Cached summary",
            runtime = "120 minutes"
        )
        coEvery { doubanSyncedItemDao.getByDoubanId("db-1") } returns item
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf("db-1" to detail)

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Route fallback",
            mediaType = MediaType.MOVIE,
            imdbId = "tt1234567",
            doubanId = "db-1"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayTitle).isEqualTo("Local display title")
        assertThat(state.overview).isEqualTo("Cached summary")
        assertThat(state.genres).isEqualTo("Drama / Mystery")
        assertThat(state.country).isEqualTo("China")
        // 纯豆瓣条目(tmdbId=0)无 TMDB 海报，用豆瓣海报兜底
        assertThat(state.posterUrl).isEqualTo("https://img.example/snapshot.jpg")
        assertThat(state.ratings?.doubanRating).isEqualTo(8.7)
        assertThat(state.userRating).isEqualTo(8)
        assertThat(state.userComment).isEqualTo("My note")
        assertThat(state.doubanIdForSync).isEqualTo("db-1")
        assertThat(state.ratingSource).isEqualTo(DetailRatingSource.DOUBAN)
        assertThat(state.isMarkedWatchlist).isTrue()
        assertThat(state.isMarkedWatched).isFalse()
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.getComments(any(), any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.getShowSeasons(any()) }
    }

    @Test
    fun missingTmdbAndTraktIdsDoNotTriggerInvalidRequests() = runTest {
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { doubanSyncedItemDao.getByDoubanId(any()) } returns null
        coEvery { doubanSyncedItemDao.getByImdbId("tt1234567") } returns null
        coEvery { doubanRepository.findDoubanId(0, "tt1234567", "movie", 0) } returns null

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Fallback title",
            mediaType = MediaType.MOVIE,
            imdbId = "tt1234567"
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.displayTitle).isEqualTo("Fallback title")
        assertThat(viewModel.uiState.value.ratingSource).isEqualTo(DetailRatingSource.NORMAL)
        coVerify(exactly = 0) { tmdbRepository.enrichMovie(any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.getComments(any(), any(), any(), any()) }
        coVerify(exactly = 0) { traktRepository.getShowSeasons(any()) }
        coVerify(exactly = 0) { traktRepository.checkInWatchlist(any(), any()) }
        coVerify(exactly = 0) { traktRepository.checkWatched(any(), any()) }
    }

    @Test
    fun imdbMappingIsResolvedWhenTraktIdIsZero() = runTest {
        val detail = DoubanDetailCacheEntry(
            imdbId = "tt7654321",
            isTvShow = false,
            title = "IMDb-only Douban title",
            doubanRating = 8.6
        )
        coEvery { doubanSyncedItemDao.getByImdbId("tt7654321") } returns null
        coEvery { doubanRepository.findDoubanId(0, "tt7654321", "movie", 0) } returns "db-imdb-only"
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf("db-imdb-only" to detail)

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Fallback title",
            mediaType = MediaType.MOVIE,
            imdbId = "tt7654321"
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanIdForSync).isEqualTo("db-imdb-only")
        assertThat(viewModel.uiState.value.ratingSource).isEqualTo(DetailRatingSource.DOUBAN)
        assertThat(viewModel.uiState.value.ratings?.doubanRating).isEqualTo(8.6)
        coVerify(exactly = 1) { doubanRepository.findDoubanId(0, "tt7654321", "movie", 0) }
    }

    @Test
    fun lateDoubanIdResolutionFromPreviousDetailDoesNotOverwriteCurrentDetail() = runTest {
        val releaseFirstLookup = CompletableDeferred<Unit>()
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns null
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { traktRepository.getRelatedMovies(any()) } returns Result.success(emptyList())
        coEvery {
            doubanRepository.findDoubanId(1, "tt-first", "movie", 0)
        } coAnswers {
            releaseFirstLookup.await()
            "db-first"
        }
        coEvery {
            doubanRepository.findDoubanId(2, "tt-second", "movie", 0)
        } returns "db-second"

        viewModel.loadDetail(
            traktId = 1,
            tmdbId = 0,
            title = "First",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-first"
        )
        advanceUntilIdle()

        viewModel.loadDetail(
            traktId = 2,
            tmdbId = 0,
            title = "Second",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-second"
        )
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.doubanIdForSync).isEqualTo("db-second")

        releaseFirstLookup.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.doubanIdForSync).isEqualTo("db-second")
    }

    @Test
    fun lateDoubanIdResolutionLoadsPublicRatingFromSnapshot() = runTest {
        val detail = DoubanDetailCacheEntry(
            imdbId = "tt7654321",
            isTvShow = false,
            doubanRating = 8.8
        )
        coEvery { doubanSyncedItemDao.getByImdbId("tt7654321") } returns null
        coEvery { doubanRepository.findDoubanId(100, "tt7654321", "movie", 0) } returns "db-2"
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf("db-2" to detail)
        coEvery { traktRepository.getRelatedMovies(100) } returns Result.success(emptyList())
        every {
            ratingsRepository.fetchRatingsStream(any(), any(), any())
        } returns flowOf(MultiRatings(imdbRating = "8.0"))

        viewModel.loadDetail(
            traktId = 100,
            tmdbId = 0,
            title = "Late Douban mapping",
            mediaType = MediaType.MOVIE,
            imdbId = "tt7654321"
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.ratingSource).isEqualTo(DetailRatingSource.DOUBAN)
        assertThat(viewModel.uiState.value.ratings?.doubanRating).isEqualTo(8.8)
    }

    @Test
    fun normalDetailStatusChangePreservesDoubanSnapshotFields() = runTest {
        val snapshot = DoubanSyncedItem(
            doubanId = "db-1",
            imdbId = "tt1234567",
            traktId = 321,
            title = "Snapshot title",
            status = "wish",
            rating = 4,
            syncedAt = 100L,
            mediaType = "movie",
            tmdbId = 654,
            displayTitle = "Snapshot display title",
            year = 2020,
            genres = "Drama / Mystery",
            posterUrl = "https://img.example/snapshot.jpg",
            doubanUrl = "https://movie.douban.com/subject/db-1/",
            comment = "My note",
            markedAt = "2024-01-02",
            subtitle = "Original title"
        )
        val savedItem = slot<List<DoubanSyncedItem>>()

        every { doubanAuthStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        coEvery { doubanSyncedItemDao.getByDoubanId("db-1") } returns snapshot
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { doubanRepository.markCollect("db-1", "cookie", null) } returns true
        coEvery { traktRepository.getRelatedMovies(any()) } returns Result.success(emptyList())
        coEvery { doubanSyncedItemDao.insertAll(capture(savedItem)) } returns Unit

        sessionMode.value = SessionMode.DOUBAN
        advanceUntilIdle()
        viewModel.loadDetail(
            traktId = 321,
            tmdbId = 0,
            title = "Route fallback",
            mediaType = MediaType.MOVIE,
            imdbId = "tt1234567",
            doubanId = "db-1"
        )
        advanceUntilIdle()

        viewModel.toggleWatched()
        advanceUntilIdle()

        val persisted = savedItem.captured.single()
        assertThat(persisted.status).isEqualTo("collect")
        assertThat(persisted.doubanUrl).isEqualTo(snapshot.doubanUrl)
        assertThat(persisted.comment).isEqualTo(snapshot.comment)
        assertThat(persisted.markedAt).isEqualTo(snapshot.markedAt)
        assertThat(persisted.subtitle).isEqualTo(snapshot.subtitle)
        assertThat(persisted.posterUrl).isEqualTo(snapshot.posterUrl)
    }

    @Test
    fun knownDoubanIdLoadsRexxarAndReplacesBasicDetailsAndPhotos() = runTest {
        val snapshot = DoubanSyncedItem(
            doubanId = "db-rexxar",
            imdbId = "tt-rexxar",
            traktId = null,
            title = "Snapshot title",
            status = "wish",
            rating = null,
            syncedAt = 100L,
            mediaType = "movie",
            displayTitle = "Snapshot display",
            year = 1994,
            genres = "Drama",
            posterUrl = "https://img.example/snapshot.jpg"
        )
        val html = DoubanDetailCacheEntry(
            imdbId = "tt-rexxar",
            isTvShow = false,
            title = "HTML title",
            summary = "HTML summary",
            posterUrl = "https://img.example/html.jpg"
        )
        val rexxar = DoubanRexxarDetail(
            doubanId = "db-rexxar",
            type = DoubanRexxarMediaType.MOVIE,
            title = "Rexxar title",
            score = 9.2,
            ratingCount = 1234,
            poster = DoubanRexxarImage(largeUrl = "https://img.example/rexxar-large.jpg"),
            summary = "Rexxar summary"
        )
        coEvery { doubanSyncedItemDao.getByDoubanId("db-rexxar") } returns snapshot
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf("db-rexxar" to html)
        coEvery {
            doubanRexxarRepository.getDetail("db-rexxar", DoubanRexxarMediaType.MOVIE, false)
        } returns Result.success(rexxar)
        coEvery {
            doubanRexxarRepository.getPhotos("db-rexxar", DoubanRexxarMediaType.MOVIE, 0, 20, false)
        } returns Result.success(
            DoubanRexxarPhotoPage(
                total = 1,
                start = 0,
                count = 1,
                photos = listOf(
                    DoubanRexxarPhoto(
                        id = "photo-1",
                        largeUrl = "https://img.example/still-large.jpg"
                    )
                )
            )
        )

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Route fallback",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-rexxar",
            doubanId = "db-rexxar"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayTitle).isEqualTo("Rexxar title")
        assertThat(state.overview).isEqualTo("Rexxar summary")
        // 纯豆瓣条目无 TMDB 海报，保留豆瓣海报（快照优先），rexxar 大图不再替换
        assertThat(state.posterUrl).isEqualTo("https://img.example/snapshot.jpg")
        assertThat(state.ratings?.doubanRating).isEqualTo(9.2)
        assertThat(state.backdrops).containsExactly("https://img.example/still-large.jpg")
        coVerify(exactly = 0) {
            doubanSyncedItemDao.updatePosterUrl(any(), any())
        }
    }

    @Test
    fun rexxarFailureKeepsCachedDoubanValues() = runTest {
        val snapshot = DoubanSyncedItem(
            doubanId = "db-failed",
            imdbId = "tt-failed",
            traktId = null,
            title = "Snapshot title",
            status = "wish",
            rating = null,
            syncedAt = 100L,
            mediaType = "movie",
            displayTitle = "Snapshot display",
            posterUrl = "https://img.example/snapshot.jpg"
        )
        val html = DoubanDetailCacheEntry(
            imdbId = "tt-failed",
            isTvShow = false,
            title = "HTML title",
            summary = "HTML summary",
            posterUrl = "https://img.example/html.jpg"
        )
        coEvery { doubanSyncedItemDao.getByDoubanId("db-failed") } returns snapshot
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf("db-failed" to html)
        coEvery {
            doubanRexxarRepository.getDetail("db-failed", DoubanRexxarMediaType.MOVIE, false)
        } returns Result.failure(IllegalStateException("rexxar unavailable"))
        coEvery {
            doubanRexxarRepository.getPhotos("db-failed", DoubanRexxarMediaType.MOVIE, 0, 20, false)
        } returns Result.failure(IllegalStateException("photos unavailable"))

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Route fallback",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-failed",
            doubanId = "db-failed"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayTitle).isEqualTo("Snapshot display")
        assertThat(state.overview).isEqualTo("HTML summary")
        // 纯豆瓣条目无 TMDB 海报，保留豆瓣海报（快照优先）
        assertThat(state.posterUrl).isEqualTo("https://img.example/snapshot.jpg")
        assertThat(state.backdrops).isEmpty()
        coVerify(exactly = 0) { doubanSyncedItemDao.updatePosterUrl(any(), any()) }
    }

    @Test
    fun resolvedImdbMappingStartsRexxarDetailLoad() = runTest {
        coEvery { doubanSyncedItemDao.getByImdbId("tt-late") } returns null
        coEvery { doubanRepository.findDoubanId(0, "tt-late", "movie", 0) } returns "db-late"
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery {
            doubanRexxarRepository.getDetail("db-late", DoubanRexxarMediaType.MOVIE, false)
        } returns Result.success(
            DoubanRexxarDetail(
                doubanId = "db-late",
                type = DoubanRexxarMediaType.MOVIE,
                title = "Late Rexxar title"
            )
        )
        coEvery {
            doubanRexxarRepository.getPhotos("db-late", DoubanRexxarMediaType.MOVIE, 0, 20, false)
        } returns Result.failure(IllegalStateException("no photos"))

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 0,
            title = "Fallback title",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-late"
        )
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.displayTitle).isEqualTo("Late Rexxar title")
        coVerify(exactly = 1) {
            doubanRexxarRepository.getDetail("db-late", DoubanRexxarMediaType.MOVIE, false)
        }
    }

    @Test
    fun lateRexxarMergeDoesNotRewriteHeaderMetaAlreadyFilledByTmdb() = runTest {
        // 发现页口碑榜入口：路由只带 traktId/tmdbId/title/imdbId，没有 doubanId。
        // TMDB 富化先落地头部元信息，doubanId 稍后才解析出来并触发 rexxar 合并。
        coEvery {
            tmdbRepository.enrichMovie(555, "Route title", null)
        } returns TmdbRepository.MovieEnrichment(
            posterUrl = "https://image.tmdb.org/poster.jpg",
            chineseTitle = "欢迎来龙餐馆",
            originalTitle = "Once Upon a Time in the Middle East",
            overview = "TMDB overview",
            genres = "剧情 · 战争",
            year = 2026,
            rating = 7.9,
            runtime = 140,
            releaseDate = "2026-08-11",
            country = "中国",
            status = "Released"
        )
        coEvery { doubanSyncedItemDao.getByImdbId("tt-meta") } returns null
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { doubanRepository.findDoubanId(0, "tt-meta", "movie", 555) } returns "db-meta"
        coEvery { traktRepository.getRelatedMovies(any()) } returns Result.success(emptyList())
        coEvery {
            doubanRexxarRepository.getDetail("db-meta", DoubanRexxarMediaType.MOVIE, false)
        } returns Result.success(
            DoubanRexxarDetail(
                doubanId = "db-meta",
                type = DoubanRexxarMediaType.MOVIE,
                title = "豆瓣标题",
                year = "2025",
                score = 8.7,
                countries = listOf("大陆"),
                initialReleaseDates = listOf("2026-06-20(上海国际电影节)"),
                runtime = "132分钟"
            )
        )
        coEvery {
            doubanRexxarRepository.getPhotos("db-meta", DoubanRexxarMediaType.MOVIE, 0, 20, false)
        } returns Result.failure(IllegalStateException("no photos"))

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 555,
            title = "Route title",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-meta"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // 头部胶囊在 TMDB 富化时已经定稿，rexxar 回来后一个字都不许变
        assertThat(state.country).isEqualTo("中国")
        assertThat(state.releaseDate).isEqualTo("2026-08-11")
        assertThat(state.runtime).isEqualTo(140)
        assertThat(state.year).isEqualTo(2026)
        assertThat(state.genres).isEqualTo("剧情 · 战争")
        assertThat(state.displayTitle).isEqualTo("欢迎来龙餐馆")
        // 豆瓣分仍要补进评分卡：只是元信息不覆盖，不是整块丢弃
        assertThat(state.ratings?.doubanRating).isEqualTo(8.7)
    }

    @Test
    fun failedTmdbEnrichmentKeepsSeededHeaderFields() = runTest {
        // peek 命中让首帧就有海报/类型/日期，随后富化请求失败：以前会把这些统统清成空。
        every {
            tmdbRepository.peekMovieEnrichment(556, "Route title", null)
        } returns TmdbRepository.MovieEnrichment(
            posterUrl = "https://image.tmdb.org/seed.jpg",
            chineseTitle = "种子标题",
            originalTitle = "Seed Original",
            overview = "Seed overview",
            genres = "剧情",
            year = 2026,
            rating = 7.0,
            runtime = 100,
            releaseDate = "2026-08-11",
            country = "中国",
            status = "Released"
        )
        coEvery {
            tmdbRepository.enrichMovie(556, "Route title", null)
        } throws IllegalStateException("tmdb unavailable")
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns null
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { doubanRepository.findDoubanId(any(), any(), any(), any()) } returns null
        coEvery { traktRepository.getRelatedMovies(any()) } returns Result.success(emptyList())

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 556,
            title = "Route title",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-seed"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.posterUrl).isEqualTo("https://image.tmdb.org/seed.jpg")
        assertThat(state.genres).isEqualTo("剧情")
        assertThat(state.releaseDate).isEqualTo("2026-08-11")
        assertThat(state.country).isEqualTo("中国")
        assertThat(state.runtime).isEqualTo(100)
        assertThat(state.status).isEqualTo("Released")
        assertThat(state.displayTitle).isEqualTo("种子标题")
    }

    @Test
    fun cardSeedOriginalTitleShowsOnFirstFrameAndSurvivesEnrichment() = runTest {
        // 发现页/筛选页入口：列表接口带了原著原名，卡片点击时写进种子。
        // 详情页首帧就要显示原名，否则富化回来那一行插入会把评分卡与下方内容整体下推。
        DetailSeedStore.remember(
            tmdbId = 558,
            posterUrl = "https://image.tmdb.org/seed558.jpg",
            year = 2026,
            origin = SharedOrigin.DISCOVER,
            originalTitle = "Seed Original 558"
        )
        // 富化稍后才返回（含同名原名）：种子值不能阻止富化，也不能被清成空。
        coEvery {
            tmdbRepository.enrichMovie(558, "Route title 558", null)
        } returns TmdbRepository.MovieEnrichment(
            posterUrl = "https://image.tmdb.org/poster558.jpg",
            chineseTitle = "中文标题 558",
            originalTitle = "Seed Original 558",
            overview = "overview",
            genres = "剧情",
            year = 2026,
            rating = 7.5,
            runtime = 120,
            releaseDate = "2026-08-11",
            country = "中国",
            status = "Released"
        )
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns null
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { doubanRepository.findDoubanId(any(), any(), any(), any()) } returns null
        coEvery { traktRepository.getRelatedMovies(any()) } returns Result.success(emptyList())

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 558,
            title = "Route title 558",
            mediaType = MediaType.MOVIE
        )
        // 富化挂起期间的这一份状态就是首帧：原名必须已经在
        assertThat(viewModel.uiState.value.originalTitle).isEqualTo("Seed Original 558")

        advanceUntilIdle()
        assertThat(viewModel.uiState.value.originalTitle).isEqualTo("Seed Original 558")
    }

    @Test
    fun seedOriginalTitleEqualtoDisplayTitleIsNotShownAsOriginal() = runTest {
        // 中文片名没有外文原名时列表也会回 original_title（和 title 相同）。
        // 这种值不能当作原名，否则「原名」行会重复显示一遍片名。
        DetailSeedStore.remember(
            tmdbId = 559,
            posterUrl = "https://image.tmdb.org/seed559.jpg",
            year = 2026,
            origin = SharedOrigin.DISCOVER,
            originalTitle = "同名标题"
        )
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns null
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { doubanRepository.findDoubanId(any(), any(), any(), any()) } returns null
        coEvery { traktRepository.getRelatedMovies(any()) } returns Result.success(emptyList())

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 559,
            title = "同名标题",
            mediaType = MediaType.MOVIE
        )
        assertThat(viewModel.uiState.value.originalTitle).isEmpty()
    }

    @Test
    fun metadataSummaryNoneTitleFallsBackToTraktAndKeepsOverview() = runTest {
        coEvery { mediaMetadataRepository.getSummaries(any(), any()) } returns listOf(
            MediaSummary(
                mediaType = "movie",
                tmdbId = 557,
                locale = "zh-CN",
                title = "Original only",
                originalTitle = "Original only",
                overview = "摘要简介",
                posterPath = null,
                year = 2025,
                genres = listOf("剧情"),
                voteAverage = 7.0,
                runtime = 100,
                countries = listOf("中国"),
                status = "Released",
                imdbId = "tt-none",
                collectionId = null,
                titleSource = TitleSource.NONE
            )
        )
        coEvery { doubanSyncedItemDao.getByImdbId(any()) } returns null
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { doubanRepository.findDoubanId(any(), any(), any(), any()) } returns null
        coEvery { traktRepository.getRelatedMovies(any()) } returns Result.success(emptyList())

        viewModel.loadDetail(
            traktId = 0,
            tmdbId = 557,
            title = "Trakt 标题",
            mediaType = MediaType.MOVIE,
            imdbId = "tt-none"
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.displayTitle).isEqualTo("Trakt 标题")
        assertThat(state.overview).isEqualTo("摘要简介")
        assertThat(state.year).isEqualTo(2025)
    }
}
