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
@Config(sdk = [33], application = android.app.Application::class)
class DoubanSyncFailureDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DoubanSyncFailureDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.doubanSyncFailureDao()
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
        failureReason: String = "NETWORK_ERROR",
        failedAt: Long = 1000L,
        updatedAt: Long = 0L,
        attemptCount: Int = 0,
        mediaType: String? = null,
        mediaTypeCleared: Boolean = false,
        subtitle: String? = null
    ) = DoubanSyncFailureEntity(
        doubanId = doubanId, title = title, posterUrl = posterUrl,
        rating = rating, comment = comment, markedAt = markedAt,
        doubanUrl = doubanUrl, status = status, failureReason = failureReason,
        failedAt = failedAt, updatedAt = updatedAt, attemptCount = attemptCount,
        mediaType = mediaType, mediaTypeCleared = mediaTypeCleared, subtitle = subtitle
    )

    // ==================== 基础 CRUD ====================

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
        dao.insertAll(listOf(sample(doubanId = "d1", title = "旧标题")))
        dao.insertAll(listOf(sample(doubanId = "d1", title = "新标题")))
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getById("d1")!!.title).isEqualTo("新标题")
    }

    @Test
    fun getById_命中() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", title = "电影A")))
        val result = dao.getById("d1")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("电影A")
    }

    // ==================== 按 status 过滤与删除 ====================

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
    fun deleteByDoubanId_删除单条() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1"),
            sample(doubanId = "d2")
        ))
        dao.deleteByDoubanId("d1")
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getById("d1")).isNull()
    }

    @Test
    fun deleteByStatus_只删同status的() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", status = "wish"),
            sample(doubanId = "d2", status = "wish"),
            sample(doubanId = "d3", status = "collect")
        ))
        dao.deleteByStatus("wish")
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getAll()[0].doubanId).isEqualTo("d3")
    }

    @Test
    fun deleteByDoubanIds_批量删除() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1"),
            sample(doubanId = "d2"),
            sample(doubanId = "d3"),
            sample(doubanId = "d4")
        ))
        dao.deleteByDoubanIds(listOf("d1", "d3", "d5"))  // d5 不存在
        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.getAll().map { it.doubanId }).containsExactly("d2", "d4")
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

    // ==================== updateMediaType（关键：mediaTypeCleared 联动） ====================

    @Test
    fun updateMediaType_设置为movie_同时mediaTypeCleared置false() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", mediaType = null, mediaTypeCleared = false)))
        dao.updateMediaType("d1", "movie", now = 5000L)
        val result = dao.getById("d1")!!
        assertThat(result.mediaType).isEqualTo("movie")
        assertThat(result.mediaTypeCleared).isFalse()
        assertThat(result.updatedAt).isEqualTo(5000L)
    }

    @Test
    fun updateMediaType_清除标注为null_同时mediaTypeCleared置true() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", mediaType = "movie", mediaTypeCleared = false)))
        dao.updateMediaType("d1", null, now = 5000L)
        val result = dao.getById("d1")!!
        assertThat(result.mediaType).isNull()
        assertThat(result.mediaTypeCleared).isTrue()
        assertThat(result.updatedAt).isEqualTo(5000L)
    }

    // ==================== updateMediaTypeBatch（批量标注） ====================

    @Test
    fun updateMediaTypeBatch_批量设置movie() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", mediaType = null),
            sample(doubanId = "d2", mediaType = null),
            sample(doubanId = "d3", mediaType = "show")
        ))
        dao.updateMediaTypeBatch(listOf("d1", "d2", "d3"), "movie", now = 9999L)
        val all = dao.getAll()
        assertThat(all.all { it.mediaType == "movie" }).isTrue()
        assertThat(all.all { !it.mediaTypeCleared }).isTrue()
        assertThat(all.all { it.updatedAt == 9999L }).isTrue()
    }

    @Test
    fun updateMediaTypeBatch_批量清除为null() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", mediaType = "movie"),
            sample(doubanId = "d2", mediaType = "show")
        ))
        dao.updateMediaTypeBatch(listOf("d1", "d2"), null, now = 9999L)
        val all = dao.getAll()
        assertThat(all.all { it.mediaType == null }).isTrue()
        assertThat(all.all { it.mediaTypeCleared }).isTrue()
    }

    // ==================== updateMediaTypeIfNull（全局池填充用） ====================

    @Test
    fun updateMediaTypeIfNull_null字段_更新为指定值() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", mediaType = null)))
        dao.updateMediaTypeIfNull("d1", "movie")
        assertThat(dao.getById("d1")!!.mediaType).isEqualTo("movie")
    }

    @Test
    fun updateMediaTypeIfNull_已有非null字段_不覆盖() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", mediaType = "show")))
        dao.updateMediaTypeIfNull("d1", "movie")
        assertThat(dao.getById("d1")!!.mediaType).isEqualTo("show")  // 未被覆盖
    }

    // ==================== getDoubanIdsWithNullMediaType（关键：排除用户主动清除的） ====================

    @Test
    fun getDoubanIdsWithNullMediaType_返回未标注且未清除的() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", mediaType = null, mediaTypeCleared = false),  // 符合
            sample(doubanId = "d2", mediaType = null, mediaTypeCleared = true),   // 已清除，不符合
            sample(doubanId = "d3", mediaType = "movie", mediaTypeCleared = false), // 已标注，不符合
            sample(doubanId = "d4", mediaType = null, mediaTypeCleared = false)   // 符合
        ))
        val ids = dao.getDoubanIdsWithNullMediaType()
        assertThat(ids).hasSize(2)
        assertThat(ids).containsExactly("d1", "d4")
    }

    @Test
    fun getDoubanIdsWithNullMediaType_全已标注_返回空() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", mediaType = "movie"),
            sample(doubanId = "d2", mediaType = "show")
        ))
        assertThat(dao.getDoubanIdsWithNullMediaType()).isEmpty()
    }

    @Test
    fun getDoubanIdsWithNullMediaType_全已清除_返回空() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", mediaType = null, mediaTypeCleared = true),
            sample(doubanId = "d2", mediaType = null, mediaTypeCleared = true)
        ))
        assertThat(dao.getDoubanIdsWithNullMediaType()).isEmpty()
    }

    // ==================== updateStatus ====================

    @Test
    fun updateStatus_更新状态() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", status = "wish")))
        dao.updateStatus("d1", "collect", now = 5000L)
        val result = dao.getById("d1")!!
        assertThat(result.status).isEqualTo("collect")
        assertThat(result.updatedAt).isEqualTo(5000L)
    }

    // ==================== updateSubtitle ====================

    @Test
    fun updateSubtitle_设置子标题() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", subtitle = null)))
        dao.updateSubtitle("d1", "外文标题", now = 5000L)
        val result = dao.getById("d1")!!
        assertThat(result.subtitle).isEqualTo("外文标题")
        assertThat(result.updatedAt).isEqualTo(5000L)
    }

    @Test
    fun updateSubtitle_清除为null() = runTest {
        dao.insertAll(listOf(sample(doubanId = "d1", subtitle = "旧标题")))
        dao.updateSubtitle("d1", null)
        assertThat(dao.getById("d1")!!.subtitle).isNull()
    }

    // ==================== 事务方法 replaceAll / replaceByStatus ====================

    @Test
    fun replaceAll_事务替换_旧数据清除() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "old1", status = "wish"),
            sample(doubanId = "old2", status = "collect")
        ))
        dao.replaceAll(listOf(
            sample(doubanId = "new1", status = "wish"),
            sample(doubanId = "new2", status = "collect")
        ))
        assertThat(dao.count()).isEqualTo(2)
        assertThat(dao.getAll().map { it.doubanId }).containsExactly("new1", "new2")
    }

    @Test
    fun replaceByStatus_只替换同status的() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "w1", status = "wish"),
            sample(doubanId = "w2", status = "wish"),
            sample(doubanId = "c1", status = "collect"),
            sample(doubanId = "c2", status = "collect")
        ))
        // 替换 wish：删除旧 wish（2条），插入新 wish（3条），collect 不变（2条）
        dao.replaceByStatus("wish", listOf(
            sample(doubanId = "nw1", status = "wish"),
            sample(doubanId = "nw2", status = "wish"),
            sample(doubanId = "nw3", status = "wish")
        ))
        assertThat(dao.count()).isEqualTo(5)  // 3 wish + 2 collect
        assertThat(dao.getByStatus("wish")).hasSize(3)
        assertThat(dao.getByStatus("collect")).hasSize(2)
        assertThat(dao.getByStatus("wish").map { it.doubanId }).containsExactly("nw1", "nw2", "nw3")
    }

    @Test
    fun replaceByStatus_空列表_只清空对应status() = runTest {
        dao.insertAll(listOf(
            sample(doubanId = "d1", status = "wish"),
            sample(doubanId = "d2", status = "collect")
        ))
        dao.replaceByStatus("wish", emptyList())
        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getAll()[0].doubanId).isEqualTo("d2")
    }

    // ==================== attemptCount 字段 ====================

}
