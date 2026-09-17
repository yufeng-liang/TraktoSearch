package com.tracktosearch.ui.screen.watchlist

import com.tracktosearch.data.session.SessionMode
import com.tracktosearch.data.remote.trakt.TraktConnectionState

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

/** 观看统计需要至少一个可提供观看记录的平台账号。 */
fun canOpenWatchStatistics(
    traktConnected: Boolean,
    doubanMode: Boolean
): Boolean = traktConnected || doubanMode

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

/** 当前分区是否已经完成过一次加载。 */
fun isWatchlistSectionLoaded(
    state: WatchlistUiState,
    selectedMode: Int,
    selectedTab: Int
): Boolean = when {
    selectedMode == 0 && selectedTab == 0 -> state.moviesLoaded
    selectedMode == 0 && selectedTab == 1 -> state.showsLoaded
    selectedMode == 0 && selectedTab == 2 -> state.othersLoaded
    selectedMode == 1 && selectedTab == 0 -> state.historyMoviesLoaded
    selectedMode == 1 && selectedTab == 1 -> state.historyShowsLoaded
    else -> state.historyOthersLoaded
}

/**
 * 搜索词为空且筛选条件全为默认值时，过滤/排序结果恒等于原列表。
 *
 * 这种情况必须让调用方同步直出原列表，不能等 [Dispatchers.Default] 上的过滤任务返回：
 * 冷启动列表落地那几帧里，计数徽标直接读 UiState 已经变成 290，而网格读到的还是
 * 上一批的空结果，屏幕上先后闪出「空态引导卡片」和「一片空白」，之后才出现海报。
 */
fun canUseUnfilteredList(searchQuery: String, filter: FilterState): Boolean =
    searchQuery.isBlank() && filter == FilterState()

/**
 * 列表是否已按默认排序（标记时间降序 + selectionKey 平局键）排好。
 *
 * 同步直出原列表的前提是它已经处于默认顺序，否则会改变用户看到的次序。
 * 这里只做一次 O(n) 相邻比较，不做整表排序，避免把索引构建/排序拉回主线程。
 */
fun isWatchlistListInDefaultOrder(items: List<MediaUiItem>): Boolean {
    if (items.size < 2) return true
    val comparator = watchlistListedAtComparator()
    for (index in 0 until items.size - 1) {
        if (comparator.compare(items[index], items[index + 1]) > 0) return false
    }
    return true
}

/**
 * 冷启动首帧是否应显示首次加载骨架。
 *
 * ViewModel 的 [WatchlistUiState] 初始值既不是 loading，也没有 loaded 标记；如果 UI 只看
 * `isLoadingX`，首帧会被当成“已加载且为空”而闪一下空态。这里把“还没有数据、也还没完成首次
 * 加载”明确归入骨架屏。访客模式没有远端列表可等，直接走空态。
 *
 * [hasData] 用于保留离线快照：缓存已经有内容时，即使 loaded 尚未置位也不能用骨架盖住列表。
 */
fun shouldShowWatchlistInitialSkeleton(
    state: WatchlistUiState,
    selectedMode: Int,
    selectedTab: Int,
    sessionMode: SessionMode,
    traktConnectionState: TraktConnectionState,
    hasData: Boolean
): Boolean = (sessionMode != SessionMode.GUEST || traktConnectionState == TraktConnectionState.CHECKING) &&
    !hasData &&
    !isWatchlistSectionLoaded(state, selectedMode, selectedTab)
