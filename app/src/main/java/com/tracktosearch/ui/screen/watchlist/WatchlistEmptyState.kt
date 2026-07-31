package com.tracktosearch.ui.screen.watchlist

enum class WatchlistEmptyState {
    NO_ACCOUNTS,
    TRAKT_ONLY,
    DOUBAN_NOT_IMPORTED,
    IMPORTED_WITH_FAILURES,
    IMPORTED_EMPTY
}

fun resolveWatchlistEmptyState(
    traktConnected: Boolean,
    doubanLoggedIn: Boolean,
    doubanImported: Boolean,
    failureCount: Int
): WatchlistEmptyState = when {
    doubanImported && failureCount > 0 -> WatchlistEmptyState.IMPORTED_WITH_FAILURES
    doubanImported -> WatchlistEmptyState.IMPORTED_EMPTY
    doubanLoggedIn -> WatchlistEmptyState.DOUBAN_NOT_IMPORTED
    traktConnected -> WatchlistEmptyState.TRAKT_ONLY
    else -> WatchlistEmptyState.NO_ACCOUNTS
}
