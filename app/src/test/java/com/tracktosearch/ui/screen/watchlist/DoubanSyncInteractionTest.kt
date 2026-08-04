package com.tracktosearch.ui.screen.watchlist

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.repository.DoubanSyncLoginTarget
import com.tracktosearch.data.repository.DoubanSyncProgress
import com.tracktosearch.data.repository.DoubanSyncStage
import com.tracktosearch.data.repository.DoubanSyncSubStage
import org.junit.Test

class DoubanSyncInteractionTest {

    @Test
    fun `运行中横幅点击打开进度`() {
        assertThat(
            DoubanSyncProgress(
                isRunning = true,
                stage = DoubanSyncStage.FETCHING_LIST
            ).bannerClickAction()
        ).isEqualTo(DoubanSyncBannerAction.SHOW_PROGRESS)
    }

    @Test
    fun `完成横幅点击打开结果`() {
        assertThat(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.COMPLETED
            ).bannerClickAction()
        ).isEqualTo(DoubanSyncBannerAction.SHOW_RESULT)
    }

    @Test
    fun `豆瓣登录过期横幅点击直接进入登录`() {
        assertThat(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.LOGIN_REQUIRED,
                loginTarget = DoubanSyncLoginTarget.DOUBAN,
                cookieExpired = true
            ).bannerClickAction()
        ).isEqualTo(DoubanSyncBannerAction.NAVIGATE_TO_DOUBAN_LOGIN)
    }

    @Test
    fun `Trakt 未登录横幅点击进入 Trakt 登录`() {
        assertThat(
            DoubanSyncProgress(
                isComplete = true,
                stage = DoubanSyncStage.LOGIN_REQUIRED,
                loginTarget = DoubanSyncLoginTarget.TRAKT
            ).bannerClickAction()
        ).isEqualTo(DoubanSyncBannerAction.NAVIGATE_TO_TRAKT_LOGIN)
    }

    @Test
    fun `列表子阶段由状态标签承载_横幅不再重复显示子阶段`() {
        assertThat(
            DoubanSyncProgress(
                stage = DoubanSyncStage.FETCHING_LIST,
                subStage = DoubanSyncSubStage.FETCHING_WISH_LIST
            ).bannerSubStageRes()
        ).isNull()
    }

    @Test
    fun `详情子阶段保留本地化标签`() {
        assertThat(
            DoubanSyncProgress(
                stage = DoubanSyncStage.PARSING_DATA,
                subStage = DoubanSyncSubStage.FETCHING_DETAIL
            ).bannerSubStageRes()
        ).isEqualTo(R.string.douban_sync_substage_fetching_detail)
    }
}
