package com.tracktosearch.ui.screen.markrecord

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.repository.TraktRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
}
