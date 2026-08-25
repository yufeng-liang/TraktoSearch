package com.tracktosearch.ui.screen.watchlist

enum class WatchlistEmptyState {
    NO_ACCOUNTS,
    TRAKT_ONLY,
    DOUBAN_NOT_IMPORTED,
    IMPORTED_EMPTY
}

fun resolveWatchlistEmptyState(
    traktConnected: Boolean,
    doubanLoggedIn: Boolean,
    doubanImported: Boolean
): WatchlistEmptyState = when {
    doubanImported -> WatchlistEmptyState.IMPORTED_EMPTY
    doubanLoggedIn -> WatchlistEmptyState.DOUBAN_NOT_IMPORTED
    traktConnected -> WatchlistEmptyState.TRAKT_ONLY
    else -> WatchlistEmptyState.NO_ACCOUNTS
}

/**
 * 取当前「想看/已看 × 电影/剧/其他」分区的加载失败原因，null 表示这一分区没有失败。
 *
 * ViewModel 只在缓存也为空时才写这些字段（缓存非空时降级为静默失败），所以非 null 就意味着
 * 这一分区确实没有内容可显示，UI 应当用「原因 + 重试」替代空态引导。
 *
 * @param selectedMode 0=想看，1=已看
 * @param selectedTab 0=电影，1=剧，2=其他
 */
fun resolveWatchlistSectionError(
    state: WatchlistUiState,
    selectedMode: Int,
    selectedTab: Int
): String? = when {
    selectedMode == 0 && selectedTab == 0 -> state.moviesError
    selectedMode == 0 && selectedTab == 1 -> state.showsError
    selectedMode == 0 && selectedTab == 2 -> state.othersError
    selectedMode == 1 && selectedTab == 0 -> state.historyMoviesError
    selectedMode == 1 && selectedTab == 1 -> state.historyShowsError
    else -> state.historyOthersError
}
