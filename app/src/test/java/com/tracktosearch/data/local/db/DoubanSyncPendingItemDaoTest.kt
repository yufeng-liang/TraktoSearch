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
class DoubanSyncPendingItemDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DoubanSyncPendingItemDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.doubanSyncPendingItemDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sample(
        doubanId: String = "d1",
        title: String = "电影A",
        posterUrl: String? = null,
        rating: Int? = 5,
        comment: String? = "好看",
        markedAt: String = "2024-06-15",
        doubanUrl: String = "https://movie.douban.com/subject/1/",
        status: String = "wish",
        crawledAt: Long = 1000L
    ) = DoubanSyncPendingItemEntity(
        doubanId = doubanId, title = title, posterUrl = posterUrl,
        rating = rating, comment = comment, markedAt = markedAt,
        doubanUrl = doubanUrl, status = status, crawledAt = crawledAt
    )

    @Test
    fun insertAll_批量插入_可查询() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1"),
            sample(doubanId = "d2")
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
    fun getAll_返回全部() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", title = "A"),
            sample(doubanId = "d2", title = "B")
        ))
        val all = dao.getAll()
        assertThat(all).hasSize(2)
        assertThat(all.map { it.title }).containsExactly("A", "B")
    }

    @Test
    fun getAll_空表_返回空列表() = runTest {
        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun count_空表_返回0() = runTest {
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun getByStatus_过滤wish() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", status = "wish"),
            sample(doubanId = "d2", status = "collect"),
            sample(doubanId = "d3", status = "wish")
        ))
        val wishItems = dao.getByStatus("wish")
        assertThat(wishItems).hasSize(2)
        assertThat(wishItems.map { it.doubanId }).containsExactly("d1", "d3")
    }

    @Test
    fun getByStatus_过滤collect() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", status = "wish"),
            sample(doubanId = "d2", status = "collect")
        ))
        assertThat(dao.getByStatus("collect")).hasSize(1)
    }

    @Test
    fun deleteByDoubanIds_批量删除() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1"),
            sample(doubanId = "d2"),
            sample(doubanId = "d3"),
            sample(doubanId = "d4")
        ))
        dao.deleteByDoubanIds(listOf("d1", "d3"))
        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.getAll().map { it.doubanId }).containsExactly("d2", "d4")
    }

    @Test
    fun deleteByDoubanIds_空列表_无副作用() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1")))
        dao.deleteByDoubanIds(emptyList())
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun deleteByDoubanIds_不存在的ID_无副作用() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1")))
        dao.deleteByDoubanIds(listOf("not_exists"))
        assertThat(dao.count()).isEqualTo(1)
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
            sample(doubanId = "old1"),
            sample(doubanId = "old2")
        ))
        dao.replaceAll(listOf(
            sample(doubanId = "new1"),
            sample(doubanId = "new2")
        ))
        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.getAll().map { it.doubanId }).containsExactly("new1", "new2")
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

    // ==================== deleteByDoubanIds 分块删除 ====================
    // 超过 SQLite 绑定变量上限(999)时必须分块，否则整条 IN 语句抛 SQLiteException。
    // 正向 IN 可以直接分块：各块并集等价于整条语句。

    @Test
    fun deleteByDoubanIds_删除命中项且保留其余() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1"), sample(doubanId = "d2")))

        dao.deleteByDoubanIds(listOf("d1"))

        assertThat(dao.getAll().map { it.doubanId }).containsExactly("d2")
    }

    @Test
    fun deleteByDoubanIds_条目数超过单块上限_全部删除() = runTest {
        val ids = (1..1200).map { "d$it" }
        dao.insertAll(ids.map { sample(doubanId = it) })

        dao.deleteByDoubanIds(ids)

        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun deleteByDoubanIds_跨块部分删除_只删命中项() = runTest {
        val ids = (1..1200).map { "d$it" }
        dao.insertAll(ids.map { sample(doubanId = it) })

        dao.deleteByDoubanIds(ids.take(1000))

        assertThat(dao.count()).isEqualTo(200)
        assertThat(dao.getAll().map { it.doubanId }).containsExactlyElementsIn(ids.drop(1000))
    }
}
