package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.UserReviewDao
import com.tracktosearch.data.local.db.UserReviewEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * UserReviewRepository 单元测试。
 *
 * 验证仓储层对 DAO 的薄封装：
 * - getReview/saveReview/getAllReviews/getReviewsByType/deleteReview 的转发
 * - saveReview 写盘时刷新 syncedAt
 * - 空表场景返回空列表
 */
class UserReviewRepositoryTest {

    private val dao = mockk<UserReviewDao>(relaxed = true)
    private val repo = UserReviewRepository(dao)

    private fun sampleEntity(
        traktId: Long = 1L,
        mediaType: String = "movie",
        syncedAt: Long = 1_000L
    ) = UserReviewEntity(
        traktId = traktId,
        tmdbId = 100,
        imdbId = "tt0000001",
        mediaType = mediaType,
        title = "样本影视",
        year = 2024,
        rating = 8.5f,
        comment = "短评",
        liked = true,
        createdAt = 1_000L,
        updatedAt = 2_000L,
        syncedAt = syncedAt
    )

    @Test
    fun getReview_exists_returnsEntity() = runTest {
        val entity = sampleEntity(traktId = 1L)
        coEvery { dao.getByTraktId(1L) } returns entity

        val result = repo.getReview(1L)

        assertThat(result).isEqualTo(entity)
        coVerify { dao.getByTraktId(1L) }
    }

    @Test
    fun getReview_notExists_returnsNull() = runTest {
        coEvery { dao.getByTraktId(99L) } returns null

        val result = repo.getReview(99L)

        assertThat(result).isNull()
    }

    @Test
    fun saveReview_callsDaoUpsert() = runTest {
        val review = sampleEntity(traktId = 1L)

        repo.saveReview(review)

        coVerify { dao.upsert(any()) }
    }

    @Test
    fun saveReview_overwritesSyncedAtToNow() = runTest {
        val before = System.currentTimeMillis()
        // 构造一个明显过期的 syncedAt，验证会被覆盖
        val review = sampleEntity(traktId = 1L, syncedAt = 1L)
        val captured = slot<UserReviewEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        repo.saveReview(review)
        val after = System.currentTimeMillis()

        // 其余字段透传不变
        assertThat(captured.captured.traktId).isEqualTo(1L)
        assertThat(captured.captured.tmdbId).isEqualTo(100)
        assertThat(captured.captured.imdbId).isEqualTo("tt0000001")
        assertThat(captured.captured.mediaType).isEqualTo("movie")
        assertThat(captured.captured.title).isEqualTo("样本影视")
        assertThat(captured.captured.rating).isEqualTo(8.5f)
        assertThat(captured.captured.comment).isEqualTo("短评")
        // syncedAt 被刷新为 "现在"，落点在调用前后时刻之间
        assertThat(captured.captured.syncedAt).isAtLeast(before)
        assertThat(captured.captured.syncedAt).isAtMost(after)
    }

    @Test
    fun getAllReviews_returnsList() = runTest {
        val entities = listOf(
            sampleEntity(traktId = 1L, mediaType = "movie"),
            sampleEntity(traktId = 2L, mediaType = "show")
        )
        coEvery { dao.getAll() } returns entities

        val result = repo.getAllReviews()

        assertThat(result).hasSize(2)
        assertThat(result).isEqualTo(entities)
    }

    @Test
    fun getAllReviews_emptyTable_returnsEmptyList() = runTest {
        coEvery { dao.getAll() } returns emptyList()

        val result = repo.getAllReviews()

        assertThat(result).isEmpty()
    }

    @Test
    fun getReviewsByType_returnsMappedList() = runTest {
        val entities = listOf(
            sampleEntity(traktId = 1L, mediaType = "movie"),
            sampleEntity(traktId = 2L, mediaType = "movie")
        )
        coEvery { dao.getByMediaType("movie") } returns entities

        val result = repo.getReviewsByType("movie")

        assertThat(result).hasSize(2)
        assertThat(result).isEqualTo(entities)
    }

    @Test
    fun getReviewsByType_emptyTable_returnsEmptyList() = runTest {
        coEvery { dao.getByMediaType(any()) } returns emptyList()

        val result = repo.getReviewsByType("movie")

        assertThat(result).isEmpty()
    }

    @Test
    fun deleteReview_callsDaoDeleteByTraktId() = runTest {
        repo.deleteReview(1L)

        coVerify { dao.deleteByTraktId(1L) }
    }

    @Test
    fun deleteReview_nonExistentId_doesNotThrow() = runTest {
        // relaxed mock 的 deleteByTraktId 默认返回 Unit，不会抛异常
        // 验证不存在的 id 也能安全调用
        repo.deleteReview(999L)
    }
}
