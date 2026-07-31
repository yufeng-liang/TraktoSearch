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
                doubanImported = false,
                failureCount = 0
            )
        ).isEqualTo(WatchlistEmptyState.NO_ACCOUNTS)
    }

    @Test
    fun `仅登录 Trakt 时引导豆瓣登录和导入`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = true,
                doubanLoggedIn = false,
                doubanImported = false,
                failureCount = 0
            )
        ).isEqualTo(WatchlistEmptyState.TRAKT_ONLY)
    }

    @Test
    fun `登录豆瓣但未完整导入时直接引导导入`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = false,
                doubanLoggedIn = true,
                doubanImported = false,
                failureCount = 0
            )
        ).isEqualTo(WatchlistEmptyState.DOUBAN_NOT_IMPORTED)
    }

    @Test
    fun `完整导入后有失败项时优先显示失败入口`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = true,
                doubanLoggedIn = true,
                doubanImported = true,
                failureCount = 2
            )
        ).isEqualTo(WatchlistEmptyState.IMPORTED_WITH_FAILURES)
    }

    @Test
    fun `完整导入后无失败项时显示已导入空列表`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = false,
                doubanLoggedIn = true,
                doubanImported = true,
                failureCount = 0
            )
        ).isEqualTo(WatchlistEmptyState.IMPORTED_EMPTY)
    }

    @Test
    fun `完整导入状态优先于未登录状态`() {
        assertThat(
            resolveWatchlistEmptyState(
                traktConnected = false,
                doubanLoggedIn = false,
                doubanImported = true,
                failureCount = 0
            )
        ).isEqualTo(WatchlistEmptyState.IMPORTED_EMPTY)
    }
}
