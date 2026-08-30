package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationRecordDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationRecordDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.notificationRecordDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sample(
        id: Long = 0L,
        traktId: Int = 1,
        tmdbId: Int = 100,
        mediaType: String = "movie",
        title: String = "电影A",
        type: String = "release",
        payload: String = "2024-06-15",
        notifiedAt: Long = 1000L
    ) = NotificationRecordEntity(
        id = id, traktId = traktId, tmdbId = tmdbId,
        mediaType = mediaType, title = title, type = type,
        payload = payload, notifiedAt = notifiedAt
    )

    @Test
    fun insert_返回自增id() = runTest {
        val id1 = dao.insert(sample(traktId = 1, title = "A"))
        val id2 = dao.insert(sample(traktId = 2, title = "B"))
        assertThat(id1).isGreaterThan(0L)
        assertThat(id2).isGreaterThan(id1)
    }

    @Test
    fun find_按traktIdTypePayload精确匹配_命中() = runTest {
        dao.insert(sample(traktId = 1, type = "release", payload = "2024-06-15"))
        val result = dao.find(traktId = 1, type = "release", payload = "2024-06-15")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("电影A")
    }

    @Test
    fun find_不同traktId_未命中() = runTest {
        dao.insert(sample(traktId = 1, type = "release", payload = "2024-06-15"))
        assertThat(dao.find(traktId = 2, type = "release", payload = "2024-06-15")).isNull()
    }

    @Test
    fun find_多条匹配_LIMIT1只返回一条() = runTest {
        dao.insert(sample(traktId = 1, type = "release", payload = "2024-06-15", title = "A"))
        dao.insert(sample(traktId = 1, type = "release", payload = "2024-06-15", title = "B"))
        val result = dao.find(traktId = 1, type = "release", payload = "2024-06-15")
        assertThat(result).isNotNull()
    }

    @Test
    fun findByTraktId_按traktId和type查询() = runTest {
        dao.insert(sample(traktId = 1, type = "release", payload = "p1"))
        dao.insert(sample(traktId = 1, type = "release", payload = "p2"))
        dao.insert(sample(traktId = 1, type = "new_season", payload = "p3"))
        dao.insert(sample(traktId = 2, type = "release", payload = "p4"))
        val results = dao.findByTraktId(traktId = 1, type = "release")
        assertThat(results).hasSize(2)
        assertThat(results.map { it.payload }).containsExactly("p1", "p2")
    }

    @Test
    fun findByTraktId_空结果() = runTest {
        assertThat(dao.findByTraktId(traktId = 999, type = "release")).isEmpty()
    }

    @Test
    fun deleteOlderThan_删除早于阈值的() = runTest {
        dao.insert(sample(traktId = 1, notifiedAt = 500L))
        dao.insert(sample(traktId = 2, notifiedAt = 1000L))
        dao.insert(sample(traktId = 3, notifiedAt = 1500L))
        dao.deleteOlderThan(before = 1000L)
        // 500L < 1000L 被删，1000L 和 1500L 保留
        val remaining = dao.findByTraktId(traktId = 1, type = "release")
        // traktId=1 的记录 notifiedAt=500 已被删
        assertThat(remaining).isEmpty()
        // traktId=2 和 3 仍在（通过 findByTraktId 验证）
        assertThat(dao.findByTraktId(traktId = 2, type = "release")).hasSize(1)
        assertThat(dao.findByTraktId(traktId = 3, type = "release")).hasSize(1)
    }

    @Test
    fun deleteOlderThan_无早于阈值的_无副作用() = runTest {
        dao.insert(sample(traktId = 1, notifiedAt = 2000L))
        dao.deleteOlderThan(before = 1000L)
        assertThat(dao.findByTraktId(traktId = 1, type = "release")).hasSize(1)
    }

    @Test
    fun deleteOlderThan_边界值等于阈值_不删除() = runTest {
        // < before 才删，= before 不删
        dao.insert(sample(traktId = 1, notifiedAt = 1000L))
        dao.deleteOlderThan(before = 1000L)
        assertThat(dao.findByTraktId(traktId = 1, type = "release")).hasSize(1)
    }

    @Test
    fun clearAll_清空全部() = runTest {
        dao.insert(sample(traktId = 1))
        dao.insert(sample(traktId = 2))
        dao.clearAll()
        assertThat(dao.findByTraktId(traktId = 1, type = "release")).isEmpty()
        assertThat(dao.findByTraktId(traktId = 2, type = "release")).isEmpty()
    }

    @Test
    fun insert_同traktId不同payload_共存() = runTest {
        // 同一影视可能有多条通知记录（不同 payload）
        dao.insert(sample(traktId = 1, type = "release", payload = "2024-06-15"))
        dao.insert(sample(traktId = 1, type = "release", payload = "2024-06-16"))
        val results = dao.findByTraktId(traktId = 1, type = "release")
        assertThat(results).hasSize(2)
    }

    @Test
    fun insert_同traktId不同type_共存() = runTest {
        dao.insert(sample(traktId = 1, type = "release", payload = "p1"))
        dao.insert(sample(traktId = 1, type = "new_season", payload = "p2"))
        assertThat(dao.findByTraktId(traktId = 1, type = "release")).hasSize(1)
        assertThat(dao.findByTraktId(traktId = 1, type = "new_season")).hasSize(1)
    }
}
