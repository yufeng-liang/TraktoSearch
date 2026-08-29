package com.tracktosearch.ui.screen.douban

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.MarkWriteResult
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncFailure
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.FailureReason
import com.tracktosearch.data.repository.ResourceRepository
import com.tracktosearch.data.util.PosterColorExtractor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DoubanItemDetailViewModelTest {

    private lateinit var retryManager: DoubanRetryManager
    private lateinit var syncManager: DoubanSyncManager
    private lateinit var resourceRepository: ResourceRepository
    private lateinit var doubanRepository: DoubanRepository
    private lateinit var authStorage: DoubanAuthStorage
    private lateinit var syncedItemDao: DoubanSyncedItemDao
    private lateinit var posterColorExtractor: PosterColorExtractor
    private val context = mockk<Context>(relaxed = true)
    private lateinit var viewModel: DoubanItemDetailViewModel

    @Before
    fun setup() {
        retryManager = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        resourceRepository = mockk(relaxed = true)
        doubanRepository = mockk(relaxed = true)
        authStorage = mockk(relaxed = true)
        syncedItemDao = mockk(relaxed = true)
        posterColorExtractor = mockk(relaxed = true)
        every { authStorage.getCredentials() } returns null

        viewModel = DoubanItemDetailViewModel(
            retryManager,
            syncManager,
            resourceRepository,
            doubanRepository,
            authStorage,
            syncedItemDao,
            posterColorExtractor
        )
    }

    @Test
    fun syncedSnapshotWinsOverLegacyFailureAndDoesNotExposeFailureMetadata() = runTest {
        val item = DoubanSyncedItem(
            doubanId = "db-1",
            imdbId = null,
            traktId = null,
            title = "No IMDb title",
            status = "wish",
            rating = 3,
            syncedAt = 100L,
            mediaType = "movie",
            displayTitle = "No IMDb display title",
            year = 2021,
            genres = "Drama",
            posterUrl = "https://img.example/poster.jpg",
            doubanUrl = "https://movie.douban.com/subject/db-1/",
            comment = "Snapshot comment",
            markedAt = "2024-01-02",
            subtitle = "Foreign title"
        )
        val detail = DoubanDetailCacheEntry(
            imdbId = null,
            isTvShow = false,
            title = "No IMDb title",
            genres = listOf("Drama"),
            year = "2021",
            doubanRating = 8.1,
            summary = "Cached detail"
        )
        val legacyFailure = DoubanSyncFailure(
            doubanId = "db-1",
            title = "Legacy failure title",
            posterUrl = null,
            rating = null,
            comment = null,
            markedAt = "",
            doubanUrl = "https://movie.douban.com/subject/db-1/",
            status = DoubanMarkStatus.WISH,
            failureReason = FailureReason.DETAIL_FETCH_FAILED,
            failedAt = 999L,
            attemptCount = 9
        )
        coEvery { syncedItemDao.getByDoubanId("db-1") } returns item
        coEvery { doubanRepository.getDetailSnapshot() } returns mapOf("db-1" to detail)
        coEvery { retryManager.getFailure("db-1") } returns legacyFailure

        viewModel.loadFailure("db-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLegacyFailure).isFalse()
        assertThat(state.failure?.title).isEqualTo("No IMDb display title")
        assertThat(state.failure?.posterUrl).isEqualTo(item.posterUrl)
        assertThat(state.failure?.comment).isEqualTo("Snapshot comment")
        assertThat(state.failure?.markedAt).isEqualTo("2024-01-02")
        assertThat(state.failure?.subtitle).isEqualTo("Foreign title")
        assertThat(state.detailInfo?.doubanRating).isEqualTo(8.1)
        assertThat(state.detailLoadPhase).isEqualTo(DetailLoadPhase.DONE)
        coVerify(exactly = 0) { retryManager.getFailure("db-1") }
    }

    @Test
    fun missingSnapshotFallsBackToLegacyFailureData() = runTest {
        val legacyFailure = DoubanSyncFailure(
            doubanId = "db-legacy",
            title = "Legacy title",
            posterUrl = null,
            rating = null,
            comment = null,
            markedAt = "",
            doubanUrl = "https://movie.douban.com/subject/db-legacy/",
            status = DoubanMarkStatus.WISH,
            failureReason = FailureReason.DETAIL_FETCH_FAILED,
            failedAt = 999L,
            attemptCount = 3
        )
        coEvery { syncedItemDao.getByDoubanId("db-legacy") } returns null
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { retryManager.getFailure("db-legacy") } returns legacyFailure

        viewModel.loadFailure("db-legacy")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLegacyFailure).isTrue()
        assertThat(viewModel.uiState.value.failure).isEqualTo(legacyFailure)
        coVerify(exactly = 1) { retryManager.getFailure("db-legacy") }
    }

    @Test
    fun syncedItemWithoutDetailSnapshotStillUsesSyncedItemAndDoesNotBecomeLegacyFailure() = runTest {
        val item = DoubanSyncedItem(
            doubanId = "db-no-cache",
            imdbId = "tt1234567",
            traktId = null,
            title = "Synced title",
            status = "collect",
            rating = null,
            syncedAt = 200L,
            mediaType = "show",
            posterUrl = "https://img.example/synced.jpg",
            doubanUrl = "https://movie.douban.com/subject/db-no-cache/"
        )
        coEvery { syncedItemDao.getByDoubanId("db-no-cache") } returns item
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        coEvery { retryManager.getFailure("db-no-cache") } returns null

        viewModel.loadFailure("db-no-cache")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLegacyFailure).isFalse()
        assertThat(state.failure?.title).isEqualTo("Synced title")
        assertThat(state.failure?.mediaType).isEqualTo("show")
        assertThat(state.detailInfo).isNull()
        coVerify(exactly = 0) { retryManager.getFailure("db-no-cache") }
    }

    @Test
    fun successfulStatusWriteExposesWatchlistAndWatchedRefreshChanges() = runTest {
        val item = DoubanSyncedItem(
            doubanId = "db-mark-change",
            imdbId = null,
            traktId = null,
            title = "状态变更条目",
            status = "wish",
            rating = null,
            syncedAt = 300L,
            mediaType = "movie",
            doubanUrl = "https://movie.douban.com/subject/db-mark-change/"
        )
        coEvery { syncedItemDao.getByDoubanId("db-mark-change") } returns item
        coEvery { doubanRepository.getDetailSnapshot() } returns emptyMap()
        every { authStorage.getCredentials() } returns DoubanCredentials("user-1", "cookie")
        coEvery { doubanRepository.fetchCsrfToken("db-mark-change", "cookie") } returns "ck"
        coEvery {
            doubanRepository.markInterestByCk("collect", "db-mark-change", "cookie", "ck")
        } returns MarkWriteResult(success = true, statusCode = 200, message = "ok")
        coEvery { retryManager.getFailure("db-mark-change") } returns null

        viewModel.loadFailure("db-mark-change")
        advanceUntilIdle()

        viewModel.markCollect()
        advanceUntilIdle()

        assertThat(viewModel.markChanges.value).isEqualTo(
            DoubanDetailMarkChanges(watchlistChanged = true, watchedChanged = true)
        )
        coVerify { retryManager.updateStatus("db-mark-change", DoubanMarkStatus.COLLECT) }
    }
}
