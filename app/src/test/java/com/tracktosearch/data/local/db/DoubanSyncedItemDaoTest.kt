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

    /**
     * 验证 getByDoubanId 命中时全字段正确回读。
     * DoubanSyncedItem 用于详情页预查 doubanId，字段完整性影响豆瓣同步流程。
     */
    @Test
    fun getByDoubanId_命中_全字段回读() = runTest {
        dao.insertAll(listOf(sample(
            doubanId = "1234567",
            imdbId = "tt1375666",
            traktId = 100,
            title = "盗梦空间",
            status = "collect",
            rating = 5,
            mediaType = "movie",
            syncedAt = 1700000000000L
        )))
        val result = dao.getByDoubanId("1234567")!!
        assertThat(result.doubanId).isEqualTo("1234567")
        assertThat(result.imdbId).isEqualTo("tt1375666")
        assertThat(result.traktId).isEqualTo(100)
        assertThat(result.title).isEqualTo("盗梦空间")
        assertThat(result.status).isEqualTo("collect")
        assertThat(result.rating).isEqualTo(5)
        assertThat(result.mediaType).isEqualTo("movie")
        assertThat(result.syncedAt).isEqualTo(1700000000000L)
    }

    @Test
    fun getByImdbId_命中() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", imdbId = "tt0000001")))
        val result = dao.getByImdbId("tt0000001")
        assertThat(result).isNotNull()
        assertThat(result!!.doubanId).isEqualTo("d1")
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
        // LIMIT 1 只返回一条，且 doubanId 必须是两者之一
        assertThat(result!!.doubanId).isAnyOf("d1", "d2")
        assertThat(result.title).isAnyOf("A", "B")
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

    // ==================== deleteNotInDoubanIds 分块清理 ====================
    // 超过 SQLite 绑定变量上限(999)时必须分块；但 NOT IN 不能直接切片，
    // 否则 DELETE WHERE doubanId NOT IN (第一块) 会把后续块里的合法记录一并删掉。

    @Test
    fun deleteNotInDoubanIds_删除差集且保留命中项() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "keep1"),
            sample(doubanId = "keep2"),
            sample(doubanId = "stale1")
        ))

        dao.deleteNotInDoubanIds(listOf("keep1", "keep2"))

        assertThat(dao.getAllSyncedDoubanIds()).containsExactly("keep1", "keep2")
    }

    @Test
    fun deleteNotInDoubanIds_条目数超过单块上限_不误删命中项() = runTest {
        // 1200 条 > 分块大小 900，命中项跨越多个块，简单切片 NOT IN 会误删
        val ids = (1..1200).map { "d$it" }
        dao.insertAll(ids.map { sample(doubanId = it) })

        dao.deleteNotInDoubanIds(ids)

        assertThat(dao.count()).isEqualTo(1200)
    }

    @Test
    fun deleteNotInDoubanIds_跨块差集_只删未命中项() = runTest {
        val ids = (1..1200).map { "d$it" }
        dao.insertAll(ids.map { sample(doubanId = it) })
        // 保留前 1000 条，删除后 200 条；保留集合本身也需要跨块
        val keep = ids.take(1000)

        dao.deleteNotInDoubanIds(keep)

        assertThat(dao.count()).isEqualTo(1000)
        assertThat(dao.getAllSyncedDoubanIds()).containsExactlyElementsIn(keep)
    }

    @Test
    fun deleteNotInDoubanIds_空保留列表_清空表() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1"), sample(doubanId = "d2")))

        dao.deleteNotInDoubanIds(emptyList())

        assertThat(dao.count()).isEqualTo(0)
    }
}
