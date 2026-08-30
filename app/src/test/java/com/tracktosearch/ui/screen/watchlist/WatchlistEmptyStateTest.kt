package com.tracktosearch.ui.screen.watchlist

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchlistEmptyStateTest {

    @Test
    fun `未登录 Trakt 和豆瓣时显示双登录入口`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = false,
                doubanLoggedIn = false,
                doubanImported = false
            )
        ).isEqualTo(WatchlistEmptyState.NO_ACCOUNTS)
    }

    @Test
    fun `仅登录 Trakt 时引导豆瓣登录和导入`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = true,
                doubanLoggedIn = false,
                doubanImported = false
            )
        ).isEqualTo(WatchlistEmptyState.TRAKT_ONLY)
    }

    @Test
    fun `登录豆瓣但未完整导入时直接引导导入`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = false,
                doubanLoggedIn = true,
                doubanImported = false
            )
        ).isEqualTo(WatchlistEmptyState.DOUBAN_NOT_IMPORTED)
    }

    @Test
    fun `完整导入后仍有内部重试记录时不显示失败入口`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = true,
                doubanLoggedIn = true,
                doubanImported = true
            )
        ).isEqualTo(WatchlistEmptyState.IMPORTED_EMPTY)
    }

    @Test
    fun `完整导入后无失败项时显示已导入空列表`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = false,
                doubanLoggedIn = true,
                doubanImported = true
            )
        ).isEqualTo(WatchlistEmptyState.IMPORTED_EMPTY)
    }

    @Test
    fun `完整导入状态优先于未登录状态`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = false,
                doubanLoggedIn = false,
                doubanImported = true
            )
        ).isEqualTo(WatchlistEmptyState.IMPORTED_EMPTY)
    }

    @Test
    fun `六个分区各自取自己的失败原因`() {
        val state = WatchlistUiState(
            moviesError = "movies failed",
            showsError = "shows failed",
            othersError = "others failed",
            historyMoviesError = "history movies failed",
            historyShowsError = "history shows failed",
            historyOthersError = "history others failed"
        )
        assertThat(resolveWatchlistSectionError(state, 0, 0)).isEqualTo("movies failed")
        assertThat(resolveWatchlistSectionError(state, 0, 1)).isEqualTo("shows failed")
        assertThat(resolveWatchlistSectionError(state, 0, 2)).isEqualTo("others failed")
        assertThat(resolveWatchlistSectionError(state, 1, 0)).isEqualTo("history movies failed")
        assertThat(resolveWatchlistSectionError(state, 1, 1)).isEqualTo("history shows failed")
        assertThat(resolveWatchlistSectionError(state, 1, 2)).isEqualTo("history others failed")
    }

    @Test
    fun `分区没有失败时返回 null`() {
        val state = WatchlistUiState(showsError = "shows failed")
        assertThat(resolveWatchlistSectionError(state, 0, 0)).isNull()
        assertThat(resolveWatchlistSectionError(state, 0, 1)).isEqualTo("shows failed")
        assertThat(resolveWatchlistSectionError(state, 1, 1)).isNull()
    }

    @Test
    fun `访客不显示观看统计入口`() {
        assertThat(canOpenWatchStatistics(traktConnected = false, doubanMode = false)).isFalse()
    }

    @Test
    fun `任一平台提供观看记录时显示统计入口`() {
        assertThat(canOpenWatchStatistics(traktConnected = true, doubanMode = false)).isTrue()
        assertThat(canOpenWatchStatistics(traktConnected = false, doubanMode = true)).isTrue()
    }
}
