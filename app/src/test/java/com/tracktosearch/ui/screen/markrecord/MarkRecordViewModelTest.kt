package com.tracktosearch.ui.screen.markrecord

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.repository.MediaType
import com.tracktosearch.data.repository.TraktRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var viewModel: MarkRecordViewModel

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dao = mockk(relaxed = true)
        traktRepo = mockk(relaxed = true)
        coEvery { dao.count() } returns 0
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns emptyList()
        viewModel = MarkRecordViewModel(dao, traktRepo)
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
        coEvery { traktRepo.fetchWatchHistory(any()) } returns Result.success(page)

        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items).hasSize(1)
        assertThat(viewModel.uiState.value.items[0].title).isEqualTo("Trakt Movie")
        assertThat(viewModel.uiState.value.items[0].actionType).isEqualTo("WATCHED")
    }

    @Test
    fun `WATCHED_Tab_Trakt失败设置error`() = runTest {
        coEvery { traktRepo.fetchWatchHistory(any()) } returns Result.failure(RuntimeException("network error"))

        viewModel.switchTab(MarkRecordTab.WATCHED)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(viewModel.uiState.value.error).contains("network error")
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
        coEvery { traktRepo.fetchWatchHistory(any()) } returns Result.success(
            TraktRepository.WatchHistoryPage(listOf(traktItem), 1, 1, 1)
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
        coEvery { traktRepo.fetchWatchHistory(any()) } returns Result.failure(RuntimeException("fail"))

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
}
