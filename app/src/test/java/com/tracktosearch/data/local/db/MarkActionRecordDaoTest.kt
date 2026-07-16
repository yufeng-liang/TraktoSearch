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
class MarkActionRecordDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MarkActionRecordDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.markActionRecordDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sampleRecord(
        actionType: String = MarkActionType.ADD_WATCHLIST.value,
        mediaType: String = "movie",
        traktId: Int = 1,
        title: String = "Test Movie",
        displayTitle: String = title,
        posterUrl: String? = null,
        episodeInfo: String? = null,
        imdbId: String = "tt1",
        year: Int? = 2024,
        actedAt: Long = 1000L
    ) = MarkActionRecordEntity(
        traktId = traktId, tmdbId = 10, imdbId = imdbId,
        mediaType = mediaType, title = title, displayTitle = displayTitle,
        posterUrl = posterUrl, year = year, actionType = actionType,
        actedAt = actedAt, episodeInfo = episodeInfo
    )

    @Test
    fun insert_and_query_all() = runTest {
        dao.insert(sampleRecord(traktId = 1, actedAt = 1000L))
        dao.insert(sampleRecord(traktId = 2, actedAt = 2000L))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(2)
        // 倒序：actedAt=2000 在前
        assertThat(result[0].traktId).isEqualTo(2)
    }

    @Test
    fun query_filter_by_actionType() = runTest {
        dao.insert(sampleRecord(actionType = MarkActionType.ADD_WATCHLIST.value, traktId = 1))
        dao.insert(sampleRecord(actionType = MarkActionType.REMOVE_WATCHLIST.value, traktId = 2))
        val result = dao.query(
            actionTypes = listOf(MarkActionType.REMOVE_WATCHLIST.value), actionTypesEmpty = false,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].actionType).isEqualTo(MarkActionType.REMOVE_WATCHLIST.value)
    }

    @Test
    fun query_filter_by_mediaType() = runTest {
        dao.insert(sampleRecord(mediaType = "movie", traktId = 1))
        dao.insert(sampleRecord(mediaType = "show", traktId = 2))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = listOf("show"), mediaTypesEmpty = false,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].mediaType).isEqualTo("show")
    }

    @Test
    fun query_filter_by_time_range() = runTest {
        dao.insert(sampleRecord(traktId = 1, actedAt = 1000L))
        dao.insert(sampleRecord(traktId = 2, actedAt = 2000L))
        dao.insert(sampleRecord(traktId = 3, actedAt = 3000L))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 1500, endTime = 2500, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].traktId).isEqualTo(2)
    }

    @Test
    fun query_filter_by_title() = runTest {
        dao.insert(sampleRecord(title = "Inception", traktId = 1))
        dao.insert(sampleRecord(title = "Interstellar", traktId = 2))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = "%Incep%",
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].title).isEqualTo("Inception")
    }

    @Test
    fun query_pagination() = runTest {
        repeat(5) { i -> dao.insert(sampleRecord(traktId = i + 1, actedAt = (i + 1) * 1000L)) }
        val page1 = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 2, offset = 0
        )
        val page2 = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 2, offset = 2
        )
        assertThat(page1).hasSize(2)
        assertThat(page2).hasSize(2)
        assertThat(page1[0].traktId).isEqualTo(5)  // 倒序最新
        assertThat(page2[1].traktId).isEqualTo(2)  // 第二页最后一条（倒序第4条）
    }

    @Test
    fun query_ascending() = runTest {
        dao.insert(sampleRecord(traktId = 1, actedAt = 1000L))
        dao.insert(sampleRecord(traktId = 2, actedAt = 2000L))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = true, limit = 50, offset = 0
        )
        assertThat(result[0].traktId).isEqualTo(1)  // 正序最旧在前
    }

    @Test
    fun count_and_deleteOldest() = runTest {
        repeat(3) { i -> dao.insert(sampleRecord(traktId = i + 1, actedAt = (i + 1) * 1000L)) }
        assertThat(dao.count()).isEqualTo(3)
        dao.deleteOldest(1)
        assertThat(dao.count()).isEqualTo(2)
        // 删的是最旧的（actedAt=1000，traktId=1）
        val all = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = true, limit = 50, offset = 0
        )
        assertThat(all.map { it.traktId }).doesNotContain(1)
    }

    @Test
    fun deleteAll() = runTest {
        dao.insert(sampleRecord(traktId = 1))
        dao.insert(sampleRecord(traktId = 2))
        dao.deleteAll()
        assertThat(dao.count()).isEqualTo(0)
    }

    // ==================== 字段内容回读测试（直击"标题英文+海报不显示"bug）====================

    /**
     * 验证 displayTitle(中文) 和 posterUrl(非 null) 写入后能正确回读。
     * 这是"标记记录列表中影视标题显示英文且海报未显示"bug 的直接防护——
     * 如果 Entity 字段映射或 DAO 查询漏掉了这两列，此测试会失败。
     */
    @Test
    fun insert_全字段回读_displayTitle和posterUrl() = runTest {
        dao.insert(sampleRecord(
            traktId = 100,
            title = "Inception",
            displayTitle = "盗梦空间",
            posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
            imdbId = "tt1375666",
            year = 2010,
            episodeInfo = null
        ))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = true, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        val entity = result[0]
        // title 保留原始英文（Trakt 标题）
        assertThat(entity.title).isEqualTo("Inception")
        // displayTitle 是中文标题（来自 TMDB enrichment）—— bug 核心字段
        assertThat(entity.displayTitle).isEqualTo("盗梦空间")
        // posterUrl 是完整 URL（来自 TMDB enrichment）—— bug 核心字段
        assertThat(entity.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
        // 其他字段也应正确回读
        assertThat(entity.imdbId).isEqualTo("tt1375666")
        assertThat(entity.year).isEqualTo(2010)
        assertThat(entity.tmdbId).isEqualTo(10)
        assertThat(entity.mediaType).isEqualTo("movie")
    }

    /**
     * 验证 titleQuery 同时匹配 title 和 displayTitle 列。
     * 插入 title="Inception" displayTitle="盗梦空间"，用中文关键词查询应命中 displayTitle。
     */
    @Test
    fun query_titleQuery匹配displayTitle不匹配title() = runTest {
        dao.insert(sampleRecord(
            traktId = 1,
            title = "Inception",
            displayTitle = "盗梦空间"
        ))
        dao.insert(sampleRecord(
            traktId = 2,
            title = "Interstellar",
            displayTitle = "星际穿越"
        ))
        // 用中文关键词查询，应只命中 displayTitle="盗梦空间"
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = "%盗梦%",
            ascending = true, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].traktId).isEqualTo(1)
        assertThat(result[0].displayTitle).isEqualTo("盗梦空间")
    }

    /**
     * 验证 episodeInfo 字段（"S01E03"）写入后能正确回读。
     * 该字段仅取消单集已看时填，用于区分单集操作。
     */
    @Test
    fun insert_episodeInfo回读() = runTest {
        dao.insert(sampleRecord(
            traktId = 100,
            actionType = MarkActionType.UNMARK_WATCHED.value,
            mediaType = "show",
            episodeInfo = "S01E03"
        ))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = true, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].episodeInfo).isEqualTo("S01E03")
        assertThat(result[0].actionType).isEqualTo(MarkActionType.UNMARK_WATCHED.value)
    }

    /**
     * 验证批量插入 insertAll 方法。
     */
    @Test
    fun insertAll_批量插入() = runTest {
        dao.insertAll(listOf(
            sampleRecord(traktId = 1, actedAt = 1000L),
            sampleRecord(traktId = 2, actedAt = 2000L),
            sampleRecord(traktId = 3, actedAt = 3000L)
        ))
        assertThat(dao.count()).isEqualTo(3)
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = true, limit = 50, offset = 0
        )
        assertThat(result.map { it.traktId }).containsExactly(1, 2, 3)
    }
}
