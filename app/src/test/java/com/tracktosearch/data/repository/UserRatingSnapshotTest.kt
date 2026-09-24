package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.UserReviewDao
import com.tracktosearch.data.local.db.UserReviewEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * UserRatingSnapshot 单元测试。
 *
 * 镜像只服务详情页首帧：warmUp 全量灌入，peek 纯内存同步读，写入时写穿。
 * 核心不变量：
 * - Trakt 评分 1-10 原样存；豆瓣 1-5 换算成 1-10（×2），与 DetailUiState.userRating 同量纲
 * - 电影/剧集 traktId 同号不互相覆盖（key 带 mediaType）
 * - 无评分（rating=null）不写入镜像，peek 落空
 */
class UserRatingSnapshotTest {

    private val userReviewDao = mockk<UserReviewDao>(relaxed = true)
    private val doubanSyncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)
    private val snapshot = UserRatingSnapshot(userReviewDao, doubanSyncedItemDao)

    private fun review(
        traktId: Long = 1L,
        mediaType: String = "movie",
        rating: Float? = 8f
    ) = UserReviewEntity(
        traktId = traktId,
        tmdbId = 100,
        imdbId = "tt0000001",
        mediaType = mediaType,
        title = "样本影视",
        year = 2024,
        rating = rating,
        comment = null,
        liked = null,
        createdAt = null,
        updatedAt = null
    )

    private fun syncedItem(
        doubanId: String = "1234567",
        rating: Int? = 4
    ) = DoubanSyncedItem(
        doubanId = doubanId,
        imdbId = "tt0000001",
        traktId = 1,
        title = "样本影视",
        status = "collect",
        rating = rating,
        syncedAt = 1_000L,
        mediaType = "movie"
    )

    @Test
    fun peekTrakt_beforeWarmUp_returnsNull() {
        assertThat(snapshot.peekTrakt(1L, "movie")).isNull()
    }

    @Test
    fun warmUp_loadsTraktRatings() = runTest {
        coEvery { userReviewDao.getAll() } returns listOf(review(traktId = 1L, rating = 8f))

        snapshot.warmUp()

        assertThat(snapshot.peekTrakt(1L, "movie")).isEqualTo(8)
    }

    @Test
    fun warmUp_loadsDoubanRatingConvertedToTenScale() = runTest {
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns listOf(syncedItem(rating = 4))

        snapshot.warmUp()

        // 豆瓣 4 星 → 8 分，与 Trakt 1-10 同量纲
        assertThat(snapshot.peekDouban("1234567")).isEqualTo(8)
    }

    @Test
    fun warmUp_sameTraktIdDifferentMediaType_keptSeparately() = runTest {
        coEvery { userReviewDao.getAll() } returns listOf(
            review(traktId = 7L, mediaType = "movie", rating = 6f),
            review(traktId = 7L, mediaType = "show", rating = 10f)
        )

        snapshot.warmUp()

        assertThat(snapshot.peekTrakt(7L, "movie")).isEqualTo(6)
        assertThat(snapshot.peekTrakt(7L, "show")).isEqualTo(10)
    }

    @Test
    fun warmUp_nullRating_doesNotOccupyEntry() = runTest {
        coEvery { userReviewDao.getAll() } returns listOf(review(traktId = 1L, rating = null))
        coEvery { doubanSyncedItemDao.getAllSyncedItems() } returns listOf(syncedItem(rating = null))

        snapshot.warmUp()

        assertThat(snapshot.peekTrakt(1L, "movie")).isNull()
        assertThat(snapshot.peekDouban("1234567")).isNull()
    }

    @Test
    fun warmUp_replacesStaleEntries() = runTest {
        coEvery { userReviewDao.getAll() } returns listOf(review(traktId = 1L, rating = 8f))
        snapshot.warmUp()
        // 第二次 warmUp 表里已无该条目（被取消评分），镜像不能残留旧值
        coEvery { userReviewDao.getAll() } returns emptyList()

        snapshot.warmUp()

        assertThat(snapshot.peekTrakt(1L, "movie")).isNull()
    }

    @Test
    fun putTrakt_thenPeek_returnsValue() {
        snapshot.putTrakt(1L, "movie", 9)

        assertThat(snapshot.peekTrakt(1L, "movie")).isEqualTo(9)
    }

    @Test
    fun putTrakt_null_removesEntry() {
        snapshot.putTrakt(1L, "movie", 9)

        snapshot.putTrakt(1L, "movie", null)

        assertThat(snapshot.peekTrakt(1L, "movie")).isNull()
    }

    @Test
    fun removeTrakt_dropsEntry() {
        snapshot.putTrakt(1L, "movie", 9)

        snapshot.removeTrakt(1L, "movie")

        assertThat(snapshot.peekTrakt(1L, "movie")).isNull()
    }

    @Test
    fun putDouban_convertsToTenScale() {
        snapshot.putDouban("1234567", 3)

        assertThat(snapshot.peekDouban("1234567")).isEqualTo(6)
    }

    @Test
    fun putDouban_null_removesEntry() {
        snapshot.putDouban("1234567", 3)

        snapshot.putDouban("1234567", null)

        assertThat(snapshot.peekDouban("1234567")).isNull()
    }
}
