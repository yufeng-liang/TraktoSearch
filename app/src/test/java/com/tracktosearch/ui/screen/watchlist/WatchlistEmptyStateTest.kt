package com.tracktosearch.ui.screen.watchlist

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.trakt.TraktConnectionState
import com.tracktosearch.data.session.SessionMode
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
    fun `默认状态六个分区都未完成首次加载`() {
        val state = WatchlistUiState()
        assertThat(isWatchlistSectionLoaded(state, 0, 0)).isFalse()
        assertThat(isWatchlistSectionLoaded(state, 0, 1)).isFalse()
        assertThat(isWatchlistSectionLoaded(state, 0, 2)).isFalse()
        assertThat(isWatchlistSectionLoaded(state, 1, 0)).isFalse()
        assertThat(isWatchlistSectionLoaded(state, 1, 1)).isFalse()
        assertThat(isWatchlistSectionLoaded(state, 1, 2)).isFalse()
    }

    @Test
    fun `加载完成后空列表也算已加载`() {
        val state = WatchlistUiState(
            moviesLoaded = true,
            showsLoaded = true,
            othersLoaded = true,
            historyMoviesLoaded = true,
            historyShowsLoaded = true,
            historyOthersLoaded = true
        )
        assertThat(isWatchlistSectionLoaded(state, 0, 0)).isTrue()
        assertThat(isWatchlistSectionLoaded(state, 0, 1)).isTrue()
        assertThat(isWatchlistSectionLoaded(state, 0, 2)).isTrue()
        assertThat(isWatchlistSectionLoaded(state, 1, 0)).isTrue()
        assertThat(isWatchlistSectionLoaded(state, 1, 1)).isTrue()
        assertThat(isWatchlistSectionLoaded(state, 1, 2)).isTrue()
    }

    @Test
    fun `Trakt首帧未加载且无数据时显示骨架`() {
        assertThat(
            shouldShowWatchlistInitialSkeleton(
                state = WatchlistUiState(),
                selectedMode = 0,
                selectedTab = 0,
                sessionMode = SessionMode.TRAKT,
                traktConnectionState = TraktConnectionState.CONNECTED,
                hasData = false
            )
        ).isTrue()
    }

    @Test
    fun `离线快照已有数据时不显示骨架`() {
        assertThat(
            shouldShowWatchlistInitialSkeleton(
                state = WatchlistUiState(),
                selectedMode = 0,
                selectedTab = 0,
                sessionMode = SessionMode.TRAKT,
                traktConnectionState = TraktConnectionState.CONNECTED,
                hasData = true
            )
        ).isFalse()
    }

    @Test
    fun `已加载的空列表显示空态而不是骨架`() {
        assertThat(
            shouldShowWatchlistInitialSkeleton(
                state = WatchlistUiState(moviesLoaded = true),
                selectedMode = 0,
                selectedTab = 0,
                sessionMode = SessionMode.TRAKT,
                traktConnectionState = TraktConnectionState.CONNECTED,
                hasData = false
            )
        ).isFalse()
    }

    @Test
    fun `访客模式不等待首次加载`() {
        assertThat(
            shouldShowWatchlistInitialSkeleton(
                state = WatchlistUiState(),
                selectedMode = 0,
                selectedTab = 0,
                sessionMode = SessionMode.GUEST,
                traktConnectionState = TraktConnectionState.DISCONNECTED,
                hasData = false
            )
        ).isFalse()
    }

    @Test
    fun `Trakt连接检查中即使暂时是访客也先显示骨架`() {
        assertThat(
            shouldShowWatchlistInitialSkeleton(
                state = WatchlistUiState(),
                selectedMode = 0,
                selectedTab = 0,
                sessionMode = SessionMode.GUEST,
                traktConnectionState = TraktConnectionState.CHECKING,
                hasData = false
            )
        ).isTrue()
    }

    @Test
    fun `访客不显示观看统计入口`() {
        assertThat(canOpenWatchStatistics(traktConnected = false, doubanMode = false)).isFalse()
    }

    @Test
    fun `无搜索且筛选为默认值时可以同步直出列表`() {
        assertThat(canUseUnfilteredList("", FilterState())).isTrue()
    }

    @Test
    fun `有搜索词时不能同步直出`() {
        assertThat(canUseUnfilteredList("盗梦", FilterState())).isFalse()
    }

    @Test
    fun `任一项筛选条件非默认时不能同步直出`() {
        assertThat(canUseUnfilteredList("", FilterState(selectedGenres = setOf("动作")))).isFalse()
        assertThat(canUseUnfilteredList("", FilterState(selectedDecadeKeys = setOf(2020)))).isFalse()
        assertThat(canUseUnfilteredList("", FilterState(markedTimePreset = MarkedTimePreset.SEVEN_DAYS))).isFalse()
        assertThat(canUseUnfilteredList("", FilterState(markedTimeOrder = SortOrder.ASC))).isFalse()
        assertThat(canUseUnfilteredList("", FilterState(ratingRange = 7f..10f))).isFalse()
    }

    @Test
    fun `已按标记时间降序排列的列表被视为默认顺序`() {
        val items = listOf(
            sampleItem(traktId = 3, listedAt = "2024-06-20T10:00:00Z"),
            sampleItem(traktId = 2, listedAt = "2024-06-18T10:00:00Z"),
            sampleItem(traktId = 1, listedAt = "2024-06-15T10:00:00Z")
        )
        assertThat(isWatchlistListInDefaultOrder(items)).isTrue()
    }

    @Test
    fun `平局按 selectionKey 升序才算默认顺序`() {
        val sorted = listOf(
            sampleItem(traktId = 1, listedAt = "2024-06-20T10:00:00Z"),
            sampleItem(traktId = 2, listedAt = "2024-06-20T10:00:00Z")
        )
        assertThat(isWatchlistListInDefaultOrder(sorted)).isTrue()

        val reversed = listOf(
            sampleItem(traktId = 2, listedAt = "2024-06-20T10:00:00Z"),
            sampleItem(traktId = 1, listedAt = "2024-06-20T10:00:00Z")
        )
        assertThat(isWatchlistListInDefaultOrder(reversed)).isFalse()
    }

    @Test
    fun `升序或乱序列表不算默认顺序`() {
        val ascending = listOf(
            sampleItem(traktId = 1, listedAt = "2024-06-15T10:00:00Z"),
            sampleItem(traktId = 2, listedAt = "2024-06-20T10:00:00Z")
        )
        assertThat(isWatchlistListInDefaultOrder(ascending)).isFalse()

        val shuffled = listOf(
            sampleItem(traktId = 1, listedAt = "2024-06-15T10:00:00Z"),
            sampleItem(traktId = 3, listedAt = "2024-06-20T10:00:00Z"),
            sampleItem(traktId = 2, listedAt = "2024-06-18T10:00:00Z")
        )
        assertThat(isWatchlistListInDefaultOrder(shuffled)).isFalse()
    }

    private fun sampleItem(traktId: Int, listedAt: String) = MediaUiItem(
        traktId = traktId,
        tmdbId = traktId * 10,
        title = "Title $traktId",
        displayTitle = "Title $traktId",
        year = 2024,
        genres = "",
        posterUrl = null,
        listedAt = listedAt
    )

    @Test
    fun `任一平台提供观看记录时显示统计入口`() {
        assertThat(canOpenWatchStatistics(traktConnected = true, doubanMode = false)).isTrue()
        assertThat(canOpenWatchStatistics(traktConnected = false, doubanMode = true)).isTrue()
    }
}
