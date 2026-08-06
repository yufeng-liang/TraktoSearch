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
import com.tracktosearch.data.repository.MediaType
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
    private lateinit var traktRepository: TraktRepository
    private lateinit var resourceRepository: ResourceRepository
    private lateinit var ratingsRepository: RatingsRepository
    private lateinit var viewedItemStorage: ViewedItemStorage
    private lateinit var commentTranslator: CommentTranslator
    private lateinit var tokenStorage: TokenStorage
    private lateinit var detailSectionStorage: DetailSectionStorage
    private lateinit var languageStorage: LanguageStorage
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var doubanAuthStorage: DoubanAuthStorage
    private lateinit var doubanSyncedItemDao: DoubanSyncedItemDao
    private lateinit var sessionModeManager: SessionModeManager
    private lateinit var sessionMode: MutableStateFlow<SessionMode>
    private lateinit var traktConnected: MutableStateFlow<Boolean>
    private lateinit var posterColorExtractor: PosterColorExtractor
    private lateinit var userReviewRepository: UserReviewRepository
    private lateinit var viewModel: DetailViewModel

    @Before
    fun setup() {
        val cacheField = DetailViewModel::class.java.getDeclaredField("detailCache")
        cacheField.isAccessible = true
        (cacheField.get(null) as MutableMap<*, *>).clear()

        tmdbRepository = mockk(relaxed = true)
        traktRepository = mockk(relaxed = true)
        resourceRepository = mockk(relaxed = true)
        ratingsRepository = mockk(relaxed = true)
        viewedItemStorage = mockk(relaxed = true)
        commentTranslator = mockk(relaxed = true)
        tokenStorage = mockk(relaxed = true)
        detailSectionStorage = mockk(relaxed = true)
        languageStorage = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        doubanAuthStorage = mockk(relaxed = true)
        doubanSyncedItemDao = mockk(relaxed = true)
        sessionModeManager = mockk(relaxed = true)
        sessionMode = MutableStateFlow(SessionMode.TRAKT)
        traktConnected = MutableStateFlow(false)
        posterColorExtractor = mockk(relaxed = true)
        userReviewRepository = mockk(relaxed = true)

        every { tokenStorage.accessToken } returns flowOf(null)
        every { detailSectionStorage.sectionConfigs } returns MutableStateFlow(emptyList())
        every { languageStorage.language } returns MutableStateFlow(LanguageStorage.LANGUAGE_ENGLISH)
        every { sessionModeManager.sessionMode } returns sessionMode
        every { sessionModeManager.traktConnected } returns traktConnected
        every { resourceRepository.filterItems(any(), any(), any()) } returns emptyList()
        every {
            ratingsRepository.fetchRatingsStream(any(), any(), any())
        } returns flowOf(MultiRatings())

        viewModel = DetailViewModel(
            tmdbRepository,
            traktRepository,
            resourceRepository,
            ratingsRepository,
            viewedItemStorage,
            commentTranslator,
            tokenStorage,
            detailSectionStorage,
            languageStorage,
            doubanRepository,
            doubanAuthStorage,
            doubanSyncedItemDao,
            sessionModeManager,
            posterColorExtractor,
            userReviewRepository
        )
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
}
