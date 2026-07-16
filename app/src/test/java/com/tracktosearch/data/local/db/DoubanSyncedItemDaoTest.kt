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
class DoubanSyncedItemDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DoubanSyncedItemDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.doubanSyncedItemDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sample(
        doubanId: String = "d1",
        imdbId: String? = "tt0000001",
        traktId: Int? = 100,
        title: String = "电影A",
        status: String = "wish",
        rating: Int? = 5,
        mediaType: String = "movie",
        syncedAt: Long = 1000L
    ) = DoubanSyncedItem(
        doubanId = doubanId, imdbId = imdbId, traktId = traktId,
        title = title, status = status, rating = rating,
        syncedAt = syncedAt, mediaType = mediaType
    )

    @Test
    fun insertAll_批量插入_可查询() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", title = "A"),
            sample(doubanId = "d2", title = "B")
        ))
        assertThat(dao.count()).isEqualTo(2)
    }

    @Test
    fun insertAll_重复主键_覆盖更新() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", status = "wish")))
        dao.insertAll(listOf(sample(doubanId = "d1", status = "collect")))
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getByDoubanId("d1")!!.status).isEqualTo("collect")
    }

    @Test
    fun getByDoubanId_命中() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", title = "电影A")))
        val result = dao.getByDoubanId("d1")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("电影A")
    }

    @Test
    fun getByDoubanId_未命中_返回null() = runTest {
        assertThat(dao.getByDoubanId("not_exists")).isNull()
    }

    @Test
    fun getByImdbId_命中() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", imdbId = "tt0000001")))
        val result = dao.getByImdbId("tt0000001")
        assertThat(result).isNotNull()
        assertThat(result!!.doubanId).isEqualTo("d1")
    }

    @Test
    fun getByImdbId_未命中_返回null() = runTest {
        assertThat(dao.getByImdbId("tt9999")).isNull()
    }

    @Test
    fun getByImdbId_多条相同imdbId_LIMIT1只返回一条() = runTest {
        // 理论上 imdbId 是可以重复的（同一 imdbId 对应的豆瓣条目可能不同）
        dao.insertAll(listOf(
            sample(doubanId = "d1", imdbId = "tt0000001", title = "A"),
            sample(doubanId = "d2", imdbId = "tt0000001", title = "B")
        ))
        val result = dao.getByImdbId("tt0000001")
        assertThat(result).isNotNull()
        // LIMIT 1 不保证顺序，但只返回一条
    }

    @Test
    fun getByImdbId_imdbId为null_不匹配() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", imdbId = null)))
        assertThat(dao.getByImdbId("tt0000001")).isNull()
    }

    @Test
    fun getAllSyncedDoubanIds_返回所有doubanId() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1"),
            sample(doubanId = "d2"),
            sample(doubanId = "d3")
        ))
        val ids = dao.getAllSyncedDoubanIds()
        assertThat(ids).hasSize(3)
        assertThat(ids).containsExactly("d1", "d2", "d3")
    }

    @Test
    fun getAllSyncedItems_返回完整实体() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", title = "A", status = "wish")))
        val items = dao.getAllSyncedItems()
        assertThat(items).hasSize(1)
        assertThat(items[0].title).isEqualTo("A")
    }

    @Test
    fun count_空表_返回0() = runTest {
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun clearAll_清空() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1"), sample(doubanId = "d2")))
        dao.clearAll()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun updateStatus_更新状态() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", status = "wish")))
        dao.updateStatus("d1", "collect")
        assertThat(dao.getByDoubanId("d1")!!.status).isEqualTo("collect")
    }

    @Test
    fun updateStatus_不存在的doubanId_无副作用() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", status = "wish")))
        dao.updateStatus("not_exists", "collect")
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getByDoubanId("d1")!!.status).isEqualTo("wish")
    }

    @Test
    fun replaceAll_事务替换_旧数据清除() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "old1"), sample(doubanId = "old2")
        ))
        dao.replaceAll(listOf(
            sample(doubanId = "new1"), sample(doubanId = "new2"), sample(doubanId = "new3")
        ))
        assertThat(dao.count()).isEqualTo(3)
        assertThat(dao.getAllSyncedDoubanIds()).containsExactly("new1", "new2", "new3")
    }

    @Test
    fun replaceAll_空列表_清空表() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1")))
        dao.replaceAll(emptyList())
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun replaceAll_空表上调用_插入新数据() = runTest {
        dao.replaceAll(listOf(sample(doubanId = "d1")))
        assertThat(dao.count()).isEqualTo(1)
    }
}
