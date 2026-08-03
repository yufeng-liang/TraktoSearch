package com.tracktosearch.ui.screen.watchlist

import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanSyncStage

/** Watchlist 横幅点击后的动作，避免 Composable 直接猜测同步状态。 */
enum class DoubanSyncBannerAction {
    SHOW_PROGRESS,
    SHOW_RESULT,
    NAVIGATE_TO_DOUBAN_LOGIN,
    NAVIGATE_TO_TRAKT_LOGIN
}

fun DoubanSyncProgress.bannerClickAction(): DoubanSyncBannerAction {
    if (isRunning || isCancelling) return DoubanSyncBannerAction.SHOW_PROGRESS

    if (cookieExpired || (stage == DoubanSyncStage.LOGIN_REQUIRED && loginTarget == DoubanSyncLoginTarget.DOUBAN)) {
        return DoubanSyncBannerAction.NAVIGATE_TO_DOUBAN_LOGIN
    }
    if (stage == DoubanSyncStage.LOGIN_REQUIRED && loginTarget == DoubanSyncLoginTarget.TRAKT) {
        return DoubanSyncBannerAction.NAVIGATE_TO_TRAKT_LOGIN
    }
    if (isComplete) return DoubanSyncBannerAction.SHOW_RESULT

    return DoubanSyncBannerAction.SHOW_PROGRESS
}
