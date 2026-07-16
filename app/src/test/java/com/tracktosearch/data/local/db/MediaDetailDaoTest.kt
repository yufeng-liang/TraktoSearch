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
class MediaDetailDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaDetailDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaDetailDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sample(
        traktId: Int = 1,
        tmdbId: Int = 100,
        mediaType: String = "movie",
        title: String = "电影A",
        displayTitle: String = "电影A",
        overview: String = "剧情简介",
        posterUrl: String? = null,
        backdropUrl: String? = null,
        year: Int? = 2024,
        genres: String = "action,sci-fi",
        rating: Double = 8.0,
        runtime: Int? = 120,
        releaseDate: String = "2024-06-15"
    ) = MediaDetailEntity(
        traktId = traktId, tmdbId = tmdbId, mediaType = mediaType,
        title = title, displayTitle = displayTitle, overview = overview,
        posterUrl = posterUrl, backdropUrl = backdropUrl, year = year,
        genres = genres, rating = rating, runtime = runtime,
        releaseDate = releaseDate
    )

    @Test
    fun insert_新增_可查询() = runTest {
        dao.insert(sample(traktId = 1, title = "电影A"))
        val result = dao.getByTraktId(1)
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("电影A")
    }

    @Test
    fun insert_已有记录_覆盖更新() = runTest {
        dao.insert(sample(traktId = 1, title = "旧标题", rating = 7.0))
        dao.insert(sample(traktId = 1, title = "新标题", rating = 9.0))
        val result = dao.getByTraktId(1)!!
        assertThat(result.title).isEqualTo("新标题")
        assertThat(result.rating).isEqualTo(9.0)
    }

    @Test
    fun getByTraktId_未命中_返回null() = runTest {
        assertThat(dao.getByTraktId(999)).isNull()
    }

    @Test
    fun delete_删除指定() = runTest {
        dao.insert(sample(traktId = 1))
        dao.insert(sample(traktId = 2))
        dao.delete(1)
        assertThat(dao.getByTraktId(1)).isNull()
        assertThat(dao.getByTraktId(2)).isNotNull()
    }

    @Test
    fun delete_不存在的traktId_无副作用() = runTest {
        dao.insert(sample(traktId = 1))
        dao.delete(999)
        assertThat(dao.getByTraktId(1)).isNotNull()
    }

    @Test
    fun clearAll_清空全部() = runTest {
        dao.insert(sample(traktId = 1))
        dao.insert(sample(traktId = 2))
        dao.clearAll()
        assertThat(dao.getByTraktId(1)).isNull()
        assertThat(dao.getByTraktId(2)).isNull()
    }

    @Test
    fun clearAll_空表_无副作用() = runTest {
        dao.clearAll()
        assertThat(dao.getByTraktId(1)).isNull()
    }

    @Test
    fun nullable字段_year和runtime支持null() = runTest {
        dao.insert(sample(traktId = 1, year = null, runtime = null, posterUrl = null, backdropUrl = null))
        val result = dao.getByTraktId(1)!!
        assertThat(result.year).isNull()
        assertThat(result.runtime).isNull()
        assertThat(result.posterUrl).isNull()
        assertThat(result.backdropUrl).isNull()
    }

    @Test
    fun mediaType_电影和剧集共存() = runTest {
        dao.insert(sample(traktId = 1, mediaType = "movie"))
        dao.insert(sample(traktId = 2, mediaType = "show"))
        assertThat(dao.getByTraktId(1)!!.mediaType).isEqualTo("movie")
        assertThat(dao.getByTraktId(2)!!.mediaType).isEqualTo("show")
    }

    @Test
    fun 长文本overview_完整存储() = runTest {
        val longOverview = "这是".repeat(500) + "很长的剧情简介"
        dao.insert(sample(traktId = 1, overview = longOverview))
        assertThat(dao.getByTraktId(1)!!.overview).isEqualTo(longOverview)
    }

    // ==================== 全字段回读测试（bug 根因表）====================

    /**
     * 验证 MediaDetailEntity 全字段写入后能正确回读。
     * media_details 表是"标记记录列表标题英文+海报不显示"bug 的根因表——
     * 如果此表字段（尤其 displayTitle/posterUrl）未正确写入或查询，会导致列表降级到 Trakt 英文标题。
     */
    @Test
    fun insert_全字段回读_displayTitle和posterUrl非null() = runTest {
        dao.insert(sample(
            traktId = 100,
            tmdbId = 500,
            mediaType = "movie",
            title = "Inception",
            displayTitle = "盗梦空间",
            overview = "一个关于梦境的故事",
            posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
            backdropUrl = "https://image.tmdb.org/t/p/original/inception_bg.jpg",
            year = 2010,
            genres = "action,sci-fi",
            rating = 8.8,
            runtime = 148,
            releaseDate = "2010-07-16"
        ))
        val result = dao.getByTraktId(100)!!
        // title 是 Trakt 原始英文标题
        assertThat(result.title).isEqualTo("Inception")
        // displayTitle 是 TMDB 中文标题 —— bug 核心字段
        assertThat(result.displayTitle).isEqualTo("盗梦空间")
        // posterUrl 是完整 URL —— bug 核心字段
        assertThat(result.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
        assertThat(result.backdropUrl).isEqualTo("https://image.tmdb.org/t/p/original/inception_bg.jpg")
        // 其他字段也应正确回读
        assertThat(result.tmdbId).isEqualTo(500)
        assertThat(result.mediaType).isEqualTo("movie")
        assertThat(result.overview).isEqualTo("一个关于梦境的故事")
        assertThat(result.year).isEqualTo(2010)
        assertThat(result.genres).isEqualTo("action,sci-fi")
        assertThat(result.rating).isEqualTo(8.8)
        assertThat(result.runtime).isEqualTo(148)
        assertThat(result.releaseDate).isEqualTo("2010-07-16")
    }

    /**
     * 验证 show 类型（剧集）的全字段回读，确保 mediaType 差异不影响字段映射。
     */
    @Test
    fun insert_剧集类型_全字段回读() = runTest {
        dao.insert(sample(
            traktId = 200,
            tmdbId = 600,
            mediaType = "show",
            title = "Breaking Bad",
            displayTitle = "绝命毒师",
            posterUrl = "https://image.tmdb.org/t/p/w500/bb.jpg",
            genres = "drama,crime",
            rating = 9.5,
            runtime = 45,
            releaseDate = "2008-01-20"
        ))
        val result = dao.getByTraktId(200)!!
        assertThat(result.mediaType).isEqualTo("show")
        assertThat(result.displayTitle).isEqualTo("绝命毒师")
        assertThat(result.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/bb.jpg")
        assertThat(result.rating).isEqualTo(9.5)
    }
}
