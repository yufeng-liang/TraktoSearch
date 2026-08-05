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
