package com.tracktosearch.ui.screen.markrecord

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkRecordViewModelTest {

    private lateinit var dao: MarkActionRecordDao
    private lateinit var traktRepo: TraktRepository
    private lateinit var context: Context
    private lateinit var viewModel: MarkRecordViewModel

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dao = mockk(relaxed = true)
        traktRepo = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getString(R.string.error_load_failed) } returns "Load failed"
        coEvery { dao.count() } returns 0
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns emptyList()
        viewModel = MarkRecordViewModel(
            dao,
            traktRepo,
            mockk(relaxed = true),
            context
        )
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    private fun sampleEntity(actionType: String, traktId: Int, actedAt: Long) = MarkActionRecordEntity(
        traktId = traktId, tmdbId = 1, imdbId = "tt1", mediaType = "movie",
        title = "Test", displayTitle = "Test", posterUrl = null, year = 2024,
        actionType = actionType, actedAt = actedAt, episodeInfo = null
    )

    @Test
    fun initial_state_is_all_tab_loading() = runTest {
        val state = viewModel.uiState.value
        assertThat(state.currentTab).isEqualTo(MarkRecordTab.ALL)
    }

    @Test
    fun switch_tab_updates_current_tab() = runTest {
        viewModel.switchTab(MarkRecordTab.WATCHED)
        assertThat(viewModel.uiState.value.currentTab).isEqualTo(MarkRecordTab.WATCHED)
    }

    @Test
    fun watchlist_tab_queries_dao_with_add_watchlist_type() = runTest {
        coEvery {
            dao.query(
                actionTypes = listOf(MarkActionType.ADD_WATCHLIST.value),
                actionTypesEmpty = false,
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 1, 1000L))
        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        kotlinx.coroutines.delay(100)
        assertThat(viewModel.uiState.value.items).hasSize(1)
    }

    @Test
    fun removed_tab_queries_dao_with_remove_and_unmark_types() = runTest {
        coEvery {
            dao.query(
                actionTypes = listOf(MarkActionType.REMOVE_WATCHLIST.value, MarkActionType.UNMARK_WATCHED.value),
                actionTypesEmpty = false,
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns listOf(sampleEntity(MarkActionType.REMOVE_WATCHLIST.value, 1, 1000L))
        viewModel.switchTab(MarkRecordTab.REMOVED)
        kotlinx.coroutines.delay(100)
        assertThat(viewModel.uiState.value.items).hasSize(1)
    }

    @Test
    fun update_search_query_triggers_reload() = runTest {
        viewModel.updateSearchQuery("Inception")
        kotlinx.coroutines.delay(100)
        coVerify(atLeast = 1) {
            dao.query(any(), any(), any(), any(), any(), any(), eq("%Inception%"), any(), any(), any())
        }
    }

    // ==================== updateFilter ====================

    @Test
    fun `updateFilter_SEVEN_DAYS_计算7天起始时间`() = runTest {
        val startSlot = slot<Long>()
        coEvery {
            dao.query(any(), any(), any(), any(), capture(startSlot), any(), any(), any(), any(), any())
        } returns emptyList()
        val before = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000

        viewModel.updateFilter(emptySet(), DatePreset.SEVEN_DAYS, null, false)
        advanceUntilIdle()

        // 允许 5 秒误差
        assertThat(Math.abs(startSlot.captured - before)).isLessThan(5000L)
    }

    @Test
    fun `updateFilter_THIRTY_DAYS_计算30天起始时间`() = runTest {
        val startSlot = slot<Long>()
        coEvery {
            dao.query(any(), any(), any(), any(), capture(startSlot), any(), any(), any(), any(), any())
        } returns emptyList()
        val before = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000

        viewModel.updateFilter(emptySet(), DatePreset.THIRTY_DAYS, null, false)
        advanceUntilIdle()

        assertThat(Math.abs(startSlot.captured - before)).isLessThan(5000L)
    }

    @Test
    fun `updateFilter_CUSTOM_使用自定义日期范围`() = runTest {
        val startSlot = slot<Long>()
        val endSlot = slot<Long>()
        coEvery {
            dao.query(any(), any(), any(), any(), capture(startSlot), capture(endSlot), any(), any(), any(), any())
        } returns emptyList()
        val customRange = 1000L to 2000L

        viewModel.updateFilter(emptySet(), DatePreset.CUSTOM, customRange, false)
        advanceUntilIdle()

        assertThat(startSlot.captured).isEqualTo(1000L)
        assertThat(endSlot.captured).isEqualTo(2000L)
    }

    @Test
    fun `updateFilter_ALL_时间范围为0`() = runTest {
        val startSlot = slot<Long>()
        val endSlot = slot<Long>()
        coEvery {
            dao.query(any(), any(), any(), any(), capture(startSlot), capture(endSlot), any(), any(), any(), any())
        } returns emptyList()

        viewModel.updateFilter(emptySet(), DatePreset.ALL, null, false)
        advanceUntilIdle()

        assertThat(startSlot.captured).isEqualTo(0L)
        assertThat(endSlot.captured).isEqualTo(0L)
    }

    @Test
    fun `updateFilter_传递媒体类型和排序`() = runTest {
        val mediaSlot = slot<List<String>>()
        val ascSlot = slot<Boolean>()
        coEvery {
            dao.query(any(), any(), capture(mediaSlot), any(), any(), any(), any(), capture(ascSlot), any(), any())
        } returns emptyList()

        viewModel.updateFilter(setOf("movie", "show"), DatePreset.ALL, null, true)
        advanceUntilIdle()

        assertThat(mediaSlot.captured).containsExactly("movie", "show")
        assertThat(ascSlot.captured).isTrue()
    }

    @Test
    fun `updateFilter_重置分页并触发加载`() = runTest {
        viewModel.updateFilter(setOf("movie"), DatePreset.ALL, null, false)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    // ==================== loadNextPage ====================

    @Test
    fun `loadNextPage_加载更多页`() = runTest {
        // 先切换到 WATCHLIST Tab（走 DAO，ALL Tab hasMore 恒 false）
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns List(50) { sampleEntity(MarkActionType.ADD_WATCHLIST.value, it, 1000L + it) }
        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)
        assertThat(viewModel.uiState.value.hasMore).isTrue()

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentPage).isEqualTo(2)
        assertThat(viewModel.uiState.value.isLoadingMore).isFalse()
    }

    @Test
    fun `loadNextPage_加载中时不重复触发`() = runTest {
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns List(50) { sampleEntity(MarkActionType.ADD_WATCHLIST.value, it, 1000L + it) }
        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        // 手动设置 isLoadingMore = true 模拟加载中
        val field = MarkRecordViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<MarkRecordUiState>
        stateFlow.value = stateFlow.value.copy(isLoadingMore = true)

        viewModel.loadNextPage()
        advanceUntilIdle()

        // currentPage 仍为 1，未触发第二页
        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)
    }

    @Test
    fun `loadNextPage_hasMore为false时不触发`() = runTest {
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns List(50) { sampleEntity(MarkActionType.ADD_WATCHLIST.value, it, 1000L + it) }
        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        // 设置 hasMore = false
        val field = MarkRecordViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<MarkRecordUiState>
        stateFlow.value = stateFlow.value.copy(hasMore = false)

        viewModel.loadNextPage()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)
    }

    // ==================== refresh ====================

    @Test
    fun `refresh_清空Trakt缓存并重新加载`() = runTest {
        viewModel.refresh()
        advanceUntilIdle()

        coVerify(atLeast = 1) { traktRepo.clearWatchHistoryCache() }
        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    // ==================== retry ====================

    @Test
    fun `retry_清除错误并重新加载`() = runTest {
        // 先制造错误：让 DAO 抛异常
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("db error")
        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNotNull()

        // 恢复 DAO 并 retry
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns emptyList()
        viewModel.retry()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    // ==================== updateCurrentStatusMap ====================

    @Test
    fun `updateCurrentStatusMap_根据缓存计算状态徽标`() = runTest {
        val entity = sampleEntity(MarkActionType.ADD_WATCHLIST.value, 100, 1000L)
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(entity)
        // mock 缓存：traktId=100 是已看
        val ids = TraktRepository.WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(100)
        )
        coEvery { traktRepo.getWatchlistWatchedIds() } returns ids

        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        val statusMap = viewModel.uiState.value.currentStatusMap
        assertThat(statusMap).containsKey(100)
        assertThat(statusMap[100]).isEqualTo(CurrentMarkStatus.WATCHED)
    }

    @Test
    fun `updateCurrentStatusMap_在想看列表中返回IN_WATCHLIST`() = runTest {
        val entity = sampleEntity(MarkActionType.ADD_WATCHLIST.value, 200, 1000L)
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(entity)
        val ids = TraktRepository.WatchlistWatchedIds(
            movieWatchlistTraktIds = setOf(200)
        )
        coEvery { traktRepo.getWatchlistWatchedIds() } returns ids

        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentStatusMap[200]).isEqualTo(CurrentMarkStatus.IN_WATCHLIST)
    }

    @Test
    fun `updateCurrentStatusMap_无标记返回NONE`() = runTest {
        val entity = sampleEntity(MarkActionType.ADD_WATCHLIST.value, 300, 1000L)
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(entity)
        val ids = TraktRepository.WatchlistWatchedIds()
        coEvery { traktRepo.getWatchlistWatchedIds() } returns ids

        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentStatusMap[300]).isEqualTo(CurrentMarkStatus.NONE)
    }

    @Test
    fun `updateCurrentStatusMap_缓存为null时不更新`() = runTest {
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 400, 1000L))
        coEvery { traktRepo.getWatchlistWatchedIds() } returns null

        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.currentStatusMap).isEmpty()
    }

    // ==================== WATCHED Tab (Trakt API) ====================

    @Test
    fun `WATCHED_Tab_走Trakt历史API`() = runTest {
        val historyItem = TraktRepository.WatchHistoryItem(
            traktId = 500, tmdbId = 501, imdbId = "tt500", mediaType = "movie",
            title = "Trakt Movie", displayTitle = "Trakt Movie", posterUrl = null,
            year = 2023, watchedAt = 1000L, episodeInfo = null
        )
        val page = TraktRepository.WatchHistoryPage(
            items = listOf(historyItem), currentPage = 1, totalPages = 1, totalCount = 1
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = page.items, isComplete = true)
        )

        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items).hasSize(1)
        assertThat(viewModel.uiState.value.items[0].title).isEqualTo("Trakt Movie")
        assertThat(viewModel.uiState.value.items[0].actionType).isEqualTo("WATCHED")
    }

    @Test
    fun `WATCHED_Tab_Trakt失败设置error`() = runTest {
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = emptyList(), isComplete = true, error = "network error")
        )

        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.error).isEqualTo("Load failed")
    }

    @Test
    fun `WATCHED_Tab_首批emit即isLoadingFalse`() = runTest {
        val item1 = TraktRepository.WatchHistoryItem(
            traktId = 1, tmdbId = 10, imdbId = "tt1",
            mediaType = "movie", title = "Test", displayTitle = "Test",
            posterUrl = null, year = 2024, watchedAt = 10000L, episodeInfo = null
        )
        val item2 = TraktRepository.WatchHistoryItem(
            traktId = 2, tmdbId = 20, imdbId = "tt2",
            mediaType = "movie", title = "Test2", displayTitle = "Test2",
            posterUrl = null, year = 2024, watchedAt = 5000L, episodeInfo = null
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flow {
            emit(TraktRepository.WatchHistoryEmit(items = listOf(item1), isComplete = false))
            emit(TraktRepository.WatchHistoryEmit(items = listOf(item1, item2), isComplete = true))
        }

        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.items.size).isEqualTo(2)
    }

    // ==================== ALL Tab 合并逻辑 ====================

    @Test
    fun `ALL_Tab_合并DAO和Trakt历史按时间倒序`() = runTest {
        // DAO 返回 1 条（时间戳 2000）
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 1, 2000L))
        // Trakt 返回 1 条（时间戳 5000）
        val traktItem = TraktRepository.WatchHistoryItem(
            traktId = 2, tmdbId = 3, imdbId = "tt2", mediaType = "movie",
            title = "Trakt", displayTitle = "Trakt", posterUrl = null,
            year = 2024, watchedAt = 5000L, episodeInfo = null
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = listOf(traktItem), isComplete = true)
        )

        // init 已用空 mock 加载过，需 refresh 重新加载
        viewModel.refresh()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items).hasSize(2)
        // 时间倒序：5000 在前，2000 在后
        assertThat(viewModel.uiState.value.items[0].actedAt).isEqualTo(5000L)
        assertThat(viewModel.uiState.value.items[1].actedAt).isEqualTo(2000L)
    }

    @Test
    fun `ALL_Tab_Trakt失败不影响DAO展示`() = runTest {
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 1, 1000L))
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = emptyList(), isComplete = true, error = "fail")
        )

        // init 已用空 mock 加载过，需 refresh 重新加载
        viewModel.refresh()
        advanceUntilIdle()

        // DAO 数据仍展示，无 error
        assertThat(viewModel.uiState.value.items).hasSize(1)
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `ALL_Tab_hasMore恒为false`() = runTest {
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasMore).isFalse()
    }

    // ==================== computeTimeRange（反射）====================

    private fun computeTimeRange(state: MarkRecordUiState): Pair<Long, Long> {
        val method = MarkRecordViewModel::class.java.getDeclaredMethod(
            "computeTimeRange", MarkRecordUiState::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(viewModel, state) as Pair<Long, Long>
    }

    @Test
    fun `computeTimeRange_SEVEN_DAYS返回7天前到0`() {
        val state = MarkRecordUiState(filterDatePreset = DatePreset.SEVEN_DAYS)
        val (start, end) = computeTimeRange(state)
        val expectedStart = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        assertThat(Math.abs(start - expectedStart)).isLessThan(5000L)
        assertThat(end).isEqualTo(0L)
    }

    @Test
    fun `computeTimeRange_THIRTY_DAYS返回30天前到0`() {
        val state = MarkRecordUiState(filterDatePreset = DatePreset.THIRTY_DAYS)
        val (start, end) = computeTimeRange(state)
        val expectedStart = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        assertThat(Math.abs(start - expectedStart)).isLessThan(5000L)
        assertThat(end).isEqualTo(0L)
    }

    @Test
    fun `computeTimeRange_CUSTOM使用自定义范围`() {
        val state = MarkRecordUiState(
            filterDatePreset = DatePreset.CUSTOM,
            filterDateRange = 1000L to 2000L
        )
        val (start, end) = computeTimeRange(state)
        assertThat(start).isEqualTo(1000L)
        assertThat(end).isEqualTo(2000L)
    }

    @Test
    fun `computeTimeRange_CUSTOM无范围返回0到0`() {
        val state = MarkRecordUiState(filterDatePreset = DatePreset.CUSTOM, filterDateRange = null)
        val (start, end) = computeTimeRange(state)
        assertThat(start).isEqualTo(0L)
        assertThat(end).isEqualTo(0L)
    }

    @Test
    fun `computeTimeRange_ALL返回0到0`() {
        val state = MarkRecordUiState(filterDatePreset = DatePreset.ALL)
        val (start, end) = computeTimeRange(state)
        assertThat(start).isEqualTo(0L)
        assertThat(end).isEqualTo(0L)
    }

    // ==================== WATCHED Tab Trakt 分页补充 ====================

    @Test
    fun `WATCHED_Tab_满50条hasMore为true`() = runTest {
        val items = List(50) { i ->
            TraktRepository.WatchHistoryItem(
                traktId = 1000 + i, tmdbId = 2000 + i, imdbId = "tt$i",
                mediaType = "movie", title = "Movie $i", displayTitle = "Movie $i",
                posterUrl = null, year = 2024, watchedAt = 10000L - i, episodeInfo = null
            )
        }
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = items, isComplete = true)
        )
        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).hasSize(50)
        assertThat(viewModel.uiState.value.hasMore).isTrue()
    }

    @Test
    fun `WATCHED_Tab_不足50条hasMore为false`() = runTest {
        val items = List(30) { i ->
            TraktRepository.WatchHistoryItem(
                traktId = 1000 + i, tmdbId = 2000 + i, imdbId = "tt$i",
                mediaType = "movie", title = "Movie $i", displayTitle = "Movie $i",
                posterUrl = null, year = 2024, watchedAt = 10000L - i, episodeInfo = null
            )
        }
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = items, isComplete = true)
        )
        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).hasSize(30)
        assertThat(viewModel.uiState.value.hasMore).isFalse()
    }

    @Test
    fun `WATCHED_Tab_loadNextPage加载第二页`() = runTest {
        val page1Items = List(50) { i ->
            TraktRepository.WatchHistoryItem(
                traktId = 1000 + i, tmdbId = 2000 + i, imdbId = "tt$i",
                mediaType = "movie", title = "Movie $i", displayTitle = "Movie $i",
                posterUrl = null, year = 2024, watchedAt = 10000L - i, episodeInfo = null
            )
        }
        val page2Items = List(10) { i ->
            TraktRepository.WatchHistoryItem(
                traktId = 2000 + i, tmdbId = 3000 + i, imdbId = "tt2_$i",
                mediaType = "movie", title = "Movie P2 $i", displayTitle = "Movie P2 $i",
                posterUrl = null, year = 2024, watchedAt = 5000L - i, episodeInfo = null
            )
        }
        coEvery { traktRepo.fetchWatchHistory(1) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = page1Items, isComplete = true)
        )
        coEvery { traktRepo.fetchWatchHistory(2) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = page2Items, isComplete = true)
        )
        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.currentPage).isEqualTo(1)
        assertThat(viewModel.uiState.value.hasMore).isTrue()

        viewModel.loadNextPage()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.currentPage).isEqualTo(2)
        assertThat(viewModel.uiState.value.items).hasSize(60)
        // 第二页只有 10 条（< 50），hasMore 为 false
        assertThat(viewModel.uiState.value.hasMore).isFalse()
    }

    @Test
    fun `WATCHED_Tab_retry后重新加载`() = runTest {
        // 第一次失败
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = emptyList(), isComplete = true, error = "network error")
        )
        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.items).isEmpty()

        // 恢复并 retry
        val item = TraktRepository.WatchHistoryItem(
            traktId = 500, tmdbId = 501, imdbId = "tt500", mediaType = "movie",
            title = "Trakt Movie", displayTitle = "Trakt Movie", posterUrl = null,
            year = 2023, watchedAt = 1000L, episodeInfo = null
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = listOf(item), isComplete = true)
        )
        viewModel.retry()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.items).hasSize(1)
        assertThat(viewModel.uiState.value.items[0].title).isEqualTo("Trakt Movie")
    }

    // ==================== ALL Tab 合并补充 ====================

    @Test
    fun `ALL_Tab_同traktId不去重显示两条`() = runTest {
        // DAO 和 Trakt 都返回 traktId=100 的记录，合并后应显示两条（不去重）
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 100, 2000L))
        val traktItem = TraktRepository.WatchHistoryItem(
            traktId = 100, tmdbId = 200, imdbId = "tt100", mediaType = "movie",
            title = "Test", displayTitle = "Test", posterUrl = null,
            year = 2024, watchedAt = 5000L, episodeInfo = null
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = listOf(traktItem), isComplete = true)
        )
        viewModel.refresh()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).hasSize(2)
        assertThat(viewModel.uiState.value.items.all { it.traktId == 100 }).isTrue()
    }

    // ==================== 状态徽标一致性 ====================

    @Test
    fun `loadNextPage后新item也在currentStatusMap中`() = runTest {
        // 第一页 50 条（traktId 0-49），第二页 10 条（traktId 50-59）
        val page1 = List(50) { sampleEntity(MarkActionType.ADD_WATCHLIST.value, it, 1000L + it) }
        val page2 = List(10) { sampleEntity(MarkActionType.ADD_WATCHLIST.value, 50 + it, 1000L + 50 + it) }
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(0))
        } returns page1
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(50))
        } returns page2
        // 缓存非 null，使 updateCurrentStatusMap 执行
        coEvery { traktRepo.getWatchlistWatchedIds() } returns TraktRepository.WatchlistWatchedIds()

        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()
        // 第一页的 item 在 currentStatusMap 中
        assertThat(viewModel.uiState.value.currentStatusMap).isNotEmpty()
        assertThat(viewModel.uiState.value.currentStatusMap).hasSize(50)

        viewModel.loadNextPage()
        advanceUntilIdle()
        // 第二页的 item (traktId 50-59) 同样进入 currentStatusMap，并填充 NONE 占位
        (50..59).forEach { id ->
            assertThat(viewModel.uiState.value.currentStatusMap).containsKey(id)
            assertThat(viewModel.uiState.value.currentStatusMap[id]).isEqualTo(CurrentMarkStatus.NONE)
        }
        // 全部 60 条均在 map 中
        assertThat(viewModel.uiState.value.currentStatusMap).hasSize(60)
        val newItems = viewModel.uiState.value.items.filter { it.traktId in 50..59 }
        assertThat(newItems).hasSize(10)
        assertThat(newItems.all { it.currentStatus == CurrentMarkStatus.NONE }).isTrue()
    }

    // ==================== 边界情况 ====================

    @Test
    fun `updateSearchQuery空字符串触发重载`() = runTest {
        advanceUntilIdle() // 等 init 完成（init 调用 dao.query 一次）
        viewModel.updateSearchQuery("")
        advanceUntilIdle()
        // init (1) + updateSearchQuery (1) = 2 次
        coVerify(exactly = 2) {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `loadNextPage_ALL_Tab安全返回不触发加载`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.currentTab).isEqualTo(MarkRecordTab.ALL)
        assertThat(viewModel.uiState.value.hasMore).isFalse()

        val pageBefore = viewModel.uiState.value.currentPage
        viewModel.loadNextPage()
        advanceUntilIdle()
        // ALL Tab hasMore 恒为 false，loadNextPage 早退，currentPage 不变
        assertThat(viewModel.uiState.value.currentPage).isEqualTo(pageBefore)
        // dao.query 仅 init 调用一次，未额外触发
        coVerify(exactly = 1) {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `switchTab同Tab重复点击早退`() = runTest {
        advanceUntilIdle() // 等 init 完成
        // init 已调用 dao.query 一次（ALL Tab page 1）
        coVerify(exactly = 1) {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        // 同 Tab 重复点击，应早退不触发额外加载
        viewModel.switchTab(MarkRecordTab.ALL)
        advanceUntilIdle()
        coVerify(exactly = 1) {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ==================== displayTitle / posterUrl 字段传递测试 ====================
    // 回归测试：DAO entity 和 Trakt 历史返回的 displayTitle/posterUrl 必须原样传递到 UI item。
    // 之前 sampleEntity 用 displayTitle="Test"、posterUrl=null，且只断言 title，
    // 导致「标题英文+海报不显示」bug 无法被测试覆盖。
    // 这里使用 title 与 displayTitle 不同的数据，强制区分两个字段的传递。

    /**
     * 测试：DAO 路径下 MarkActionRecordEntity 的 displayTitle/posterUrl 字段
     * 必须原样映射到 MarkRecordItem 的 displayTitle/posterUrl 字段。
     *
     * 回归场景：DAO 中 displayTitle="盗梦空间"、posterUrl="https://..."，
     * 若 toMarkRecordItem() 字段映射错误，UI 上标题显示英文 title 而非 displayTitle。
     */
    @Test
    fun `WATCHLIST_Tab_displayTitle和posterUrl字段从DAO正确传递`() = runTest {
        val entity = MarkActionRecordEntity(
            traktId = 100, tmdbId = 200, imdbId = "tt1375666", mediaType = "movie",
            title = "Inception", displayTitle = "盗梦空间",
            posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
            year = 2010, actionType = MarkActionType.ADD_WATCHLIST.value,
            actedAt = 5000L, episodeInfo = null
        )
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(entity)
        coEvery { traktRepo.getWatchlistWatchedIds() } returns TraktRepository.WatchlistWatchedIds()

        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        val item = viewModel.uiState.value.items[0]
        // title 与 displayTitle 不同，验证两个字段都正确传递
        assertThat(item.title).isEqualTo("Inception")
        assertThat(item.displayTitle).isEqualTo("盗梦空间")
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
        // 其他字段也验证
        assertThat(item.traktId).isEqualTo(100)
        assertThat(item.tmdbId).isEqualTo(200)
        assertThat(item.imdbId).isEqualTo("tt1375666")
        assertThat(item.mediaType).isEqualTo("movie")
        assertThat(item.year).isEqualTo(2010)
        assertThat(item.actionType).isEqualTo(MarkActionType.ADD_WATCHLIST.value)
        assertThat(item.actedAt).isEqualTo(5000L)
    }

    /**
     * 测试：Trakt 历史路径下 WatchHistoryItem 的 displayTitle/posterUrl 字段
     * 必须原样映射到 MarkRecordItem 的 displayTitle/posterUrl 字段。
     */
    @Test
    fun `WATCHED_Tab_displayTitle和posterUrl字段从Trakt历史正确传递`() = runTest {
        val historyItem = TraktRepository.WatchHistoryItem(
            traktId = 500, tmdbId = 501, imdbId = "tt0816692", mediaType = "movie",
            title = "Interstellar", displayTitle = "星际穿越",
            posterUrl = "https://image.tmdb.org/t/p/w500/interstellar.jpg",
            year = 2014, watchedAt = 1000L, episodeInfo = null
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = listOf(historyItem), isComplete = true)
        )

        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()

        val item = viewModel.uiState.value.items[0]
        assertThat(item.title).isEqualTo("Interstellar")
        assertThat(item.displayTitle).isEqualTo("星际穿越")
        assertThat(item.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/interstellar.jpg")
        assertThat(item.traktId).isEqualTo(500)
        assertThat(item.tmdbId).isEqualTo(501)
        assertThat(item.imdbId).isEqualTo("tt0816692")
        assertThat(item.year).isEqualTo(2014)
        assertThat(item.actionType).isEqualTo("WATCHED")
        assertThat(item.actedAt).isEqualTo(1000L)
    }

    /**
     * 测试：episodeInfo 字段从 DAO entity 正确传递到 MarkRecordItem。
     * 剧集标记记录的 episodeInfo 形如 "S01E03"，UI 用于显示季集信息。
     */
    @Test
    fun `WATCHLIST_Tab_episodeInfo字段从DAO正确传递`() = runTest {
        val entity = MarkActionRecordEntity(
            traktId = 100, tmdbId = 200, imdbId = "tt1", mediaType = "show",
            title = "Breaking Bad", displayTitle = "绝命毒师",
            posterUrl = "https://image.tmdb.org/t/p/w500/bb.jpg",
            year = 2008, actionType = MarkActionType.ADD_WATCHLIST.value,
            actedAt = 5000L, episodeInfo = "S01E03"
        )
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(entity)
        coEvery { traktRepo.getWatchlistWatchedIds() } returns TraktRepository.WatchlistWatchedIds()

        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items[0].episodeInfo).isEqualTo("S01E03")
    }

    // ==================== 筛选 mediaTypes 生效验证 ====================
    // 回归测试：ALL/WATCHED Tab 应用 filterMediaTypes 过滤
    // 之前 bug：loadFromTraktHistory 忽略 filterMediaTypes，导致选「电视剧」筛选不生效

    @Test
    fun `WATCHED_Tab_updateFilter_只选电视剧时过滤掉movie`() = runTest {
        val movieItem = TraktRepository.WatchHistoryItem(
            traktId = 1, tmdbId = 11, imdbId = "tt1", mediaType = "movie",
            title = "Movie", displayTitle = "Movie", posterUrl = null,
            year = 2024, watchedAt = 10000L, episodeInfo = null
        )
        val showItem = TraktRepository.WatchHistoryItem(
            traktId = 2, tmdbId = 22, imdbId = "tt2", mediaType = "show",
            title = "Show", displayTitle = "Show", posterUrl = null,
            year = 2024, watchedAt = 5000L, episodeInfo = null
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = listOf(movieItem, showItem), isComplete = true)
        )

        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()
        // 初始：movie + show 都展示
        assertThat(viewModel.uiState.value.items).hasSize(2)

        // 筛选仅电视剧
        viewModel.updateFilter(setOf("show"), DatePreset.ALL, null, false)
        advanceUntilIdle()

        // 修复后：只保留 show
        assertThat(viewModel.uiState.value.items).hasSize(1)
        assertThat(viewModel.uiState.value.items[0].mediaType).isEqualTo("show")
    }

    @Test
    fun `ALL_Tab_updateFilter_只选电视剧时过滤掉movie`() = runTest {
        // DAO 返回 1 条 show 记录
        coEvery {
            dao.query(any(), any(), eq(listOf("show")), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 100, 5000L).copy(mediaType = "show"))
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 100, 5000L).copy(mediaType = "show"))

        // Trakt 返回 movie + show 各 1 条
        val movieItem = TraktRepository.WatchHistoryItem(
            traktId = 1, tmdbId = 11, imdbId = "tt1", mediaType = "movie",
            title = "Movie", displayTitle = "Movie", posterUrl = null,
            year = 2024, watchedAt = 10000L, episodeInfo = null
        )
        val showItem = TraktRepository.WatchHistoryItem(
            traktId = 2, tmdbId = 22, imdbId = "tt2", mediaType = "show",
            title = "Show", displayTitle = "Show", posterUrl = null,
            year = 2024, watchedAt = 3000L, episodeInfo = null
        )
        coEvery { traktRepo.fetchWatchHistory(any()) } returns flowOf(
            TraktRepository.WatchHistoryEmit(items = listOf(movieItem, showItem), isComplete = true)
        )

        viewModel.updateFilter(setOf("show"), DatePreset.ALL, null, false)
        advanceUntilIdle()

        // 修复后：Trakt 端 movie 被过滤掉，DAO 端 show + Trakt 端 show = 2 条
        val allItems = viewModel.uiState.value.items
        assertThat(allItems.all { it.mediaType == "show" }).isTrue()
        assertThat(allItems).hasSize(2)
    }
}
