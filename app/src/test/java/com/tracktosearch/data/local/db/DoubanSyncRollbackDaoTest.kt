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
class DoubanSyncRollbackDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DoubanSyncRollbackDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.doubanSyncRollbackDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sample(
        doubanId: String = "d1",
        traktId: Int = 100,
        title: String = "电影A",
        status: String = "wish",
        mediaType: String = "movie",
        rating: Int? = 5,
        rollbackAt: Long = 1000L
    ) = DoubanSyncRollbackEntity(
        doubanId = doubanId, traktId = traktId, title = title,
        status = status, mediaType = mediaType, rating = rating,
        rollbackAt = rollbackAt
    )

    @Test
    fun insertAll_批量插入_可查询() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", traktId = 100),
            sample(doubanId = "d2", traktId = 200)
        ))
        assertThat(dao.count()).isEqualTo(2)
    }

    @Test
    fun insertAll_重复主键_覆盖更新() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", status = "wish")))
        dao.insertAll(listOf(sample(doubanId = "d1", status = "collect")))
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getAll()[0].status).isEqualTo("collect")
    }

    @Test
    fun clearAll_清空() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1"),
            sample(doubanId = "d2")
        ))
        dao.clearAll()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun replaceAll_事务替换_旧数据清除() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "old1", traktId = 1),
            sample(doubanId = "old2", traktId = 2)
        ))
        dao.replaceAll(listOf(
            sample(doubanId = "new1", traktId = 3)
        ))
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getAll()[0].doubanId).isEqualTo("new1")
        assertThat(dao.getAll()[0].traktId).isEqualTo(3)
    }

    @Test
    fun rating字段_支持null() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", rating = null)))
        val result = dao.getAll()[0]
        assertThat(result.rating).isNull()
    }

    @Test
    fun rating字段_存储实际值() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", rating = 4)))
        assertThat(dao.getAll()[0].rating).isEqualTo(4)
    }

    @Test
    fun mediaType_电影和剧集共存() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", mediaType = "movie"),
            sample(doubanId = "d2", mediaType = "show")
        ))
        val all = dao.getAll()
        assertThat(all.map { it.mediaType }).containsExactly("movie", "show")
    }
}
