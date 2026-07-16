package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
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
class MediaItemDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaItemDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaItemDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sample(
        traktId: Int = 1,
        tmdbId: Int = 100,
        type: String = "watchlist_movie",
        title: String = "电影A",
        displayTitle: String = "电影A",
        year: Int? = 2024,
        genres: String = "action,sci-fi",
        posterUrl: String? = null,
        imdbId: String = "tt0000001",
        traktRating: Double = 8.0,
        listedAt: String = "2024-06-15T10:00:00Z"
    ) = MediaItemEntity(
        traktId = traktId, tmdbId = tmdbId, type = type, title = title,
        displayTitle = displayTitle, year = year, genres = genres,
        posterUrl = posterUrl, imdbId = imdbId, traktRating = traktRating,
        listedAt = listedAt
    )

    @Test
    fun insertAll_批量插入_可查询() = runTest {
        dao.insertAll(listOf(
            sample(traktId = 1, type = "watchlist_movie"),
            sample(traktId = 2, type = "watchlist_movie")
        ))
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(2)
    }

    @Test
    fun insertAll_重复主键_覆盖更新() = runTest {
        dao.insertAll(listOf(sample(traktId = 1, title = "旧标题")))
        dao.insertAll(listOf(sample(traktId = 1, title = "新标题")))
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(1)
        assertThat(dao.getByTypeList("watchlist_movie")[0].title).isEqualTo("新标题")
    }

    @Test
    fun getByTypeList_按类型过滤() = runTest {
        dao.insertAll(listOf(
            sample(traktId = 1, type = "watchlist_movie", title = "A"),
            sample(traktId = 2, type = "watchlist_show", title = "B"),
            sample(traktId = 3, type = "watchlist_movie", title = "C")
        ))
        val movies = dao.getByTypeList("watchlist_movie")
        assertThat(movies).hasSize(2)
        assertThat(movies.map { it.title }).containsExactly("A", "C")
    }

    @Test
    fun getByTypeList_空结果() = runTest {
        assertThat(dao.getByTypeList("watchlist_movie")).isEmpty()
    }

    @Test
    fun getByType_Flow返回按listedAt倒序() = runTest {
        // listedAt 为字符串 ISO 格式，字典序与时间序一致
        dao.insertAll(listOf(
            sample(traktId = 1, listedAt = "2024-06-15T10:00:00Z"),
            sample(traktId = 2, listedAt = "2024-06-20T10:00:00Z"),
            sample(traktId = 3, listedAt = "2024-06-18T10:00:00Z")
        ))
        val list = dao.getByType("watchlist_movie").first()
        assertThat(list).hasSize(3)
        // ORDER BY listedAt DESC → 最新的在前
        assertThat(list[0].traktId).isEqualTo(2)  // 06-20
        assertThat(list[1].traktId).isEqualTo(3)  // 06-18
        assertThat(list[2].traktId).isEqualTo(1)  // 06-15
    }

    @Test
    fun countByType_按类型计数() = runTest {
        dao.insertAll(listOf(
            sample(traktId = 1, type = "watchlist_movie"),
            sample(traktId = 2, type = "watchlist_movie"),
            sample(traktId = 3, type = "watchlist_show"),
            sample(traktId = 4, type = "history_movie")
        ))
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(2)
        assertThat(dao.countByType("watchlist_show")).isEqualTo(1)
        assertThat(dao.countByType("history_movie")).isEqualTo(1)
    }

    @Test
    fun countByType_空表_返回0() = runTest {
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(0)
    }

    @Test
    fun countDetails_空表_返回0() = runTest {
        assertThat(dao.countDetails()).isEqualTo(0)
    }

    @Test
    fun deleteByType_删除指定类型() = runTest {
        dao.insertAll(listOf(
            sample(traktId = 1, type = "watchlist_movie"),
            sample(traktId = 2, type = "watchlist_show")
        ))
        dao.deleteByType("watchlist_movie")
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(0)
        assertThat(dao.countByType("watchlist_show")).isEqualTo(1)
    }

    @Test
    fun deleteByType_不存在的类型_无副作用() = runTest {
        dao.insertAll(listOf(sample(traktId = 1, type = "watchlist_movie")))
        dao.deleteByType("watchlist_show")
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(1)
    }

    @Test
    fun deleteItem_按type和traktId删除() = runTest {
        dao.insertAll(listOf(
            sample(traktId = 1, type = "watchlist_movie"),
            sample(traktId = 2, type = "watchlist_movie"),
            sample(traktId = 1, type = "watchlist_show")  // 同 traktId 不同 type
        ))
        dao.deleteItem("watchlist_movie", 1)
        // 只删除 watchlist_movie 类型下 traktId=1 的记录
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(1)
        assertThat(dao.countByType("watchlist_show")).isEqualTo(1)
        assertThat(dao.getByTypeList("watchlist_movie")[0].traktId).isEqualTo(2)
    }

    @Test
    fun deleteItem_不存在的记录_无副作用() = runTest {
        dao.insertAll(listOf(sample(traktId = 1, type = "watchlist_movie")))
        dao.deleteItem("watchlist_movie", 999)
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(1)
    }

    @Test
    fun replaceByType_事务替换_同类型旧数据清除() = runTest {
        dao.insertAll(listOf(
            sample(traktId = 1, type = "watchlist_movie", title = "旧A"),
            sample(traktId = 2, type = "watchlist_movie", title = "旧B"),
            sample(traktId = 3, type = "watchlist_show", title = "剧集C")
        ))
        dao.replaceByType("watchlist_movie", listOf(
            sample(traktId = 10, type = "watchlist_movie", title = "新A"),
            sample(traktId = 11, type = "watchlist_movie", title = "新B")
        ))
        // watchlist_movie 已替换为 2 条新数据
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(2)
        val movies = dao.getByTypeList("watchlist_movie")
        assertThat(movies.map { it.title }).containsExactly("新A", "新B")
        // watchlist_show 不受影响
        assertThat(dao.countByType("watchlist_show")).isEqualTo(1)
    }

    @Test
    fun replaceByType_空列表_只清空对应类型() = runTest {
        dao.insertAll(listOf(
            sample(traktId = 1, type = "watchlist_movie"),
            sample(traktId = 2, type = "watchlist_show")
        ))
        dao.replaceByType("watchlist_movie", emptyList())
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(0)
        assertThat(dao.countByType("watchlist_show")).isEqualTo(1)
    }

    @Test
    fun replaceByType_空表上调用_插入新数据() = runTest {
        dao.replaceByType("watchlist_movie", listOf(
            sample(traktId = 1, type = "watchlist_movie")
        ))
        assertThat(dao.countByType("watchlist_movie")).isEqualTo(1)
    }

    @Test
    fun nullable字段_year和posterUrl支持null() = runTest {
        dao.insertAll(listOf(sample(traktId = 1, year = null, posterUrl = null)))
        val result = dao.getByTypeList("watchlist_movie")[0]
        assertThat(result.year).isNull()
        assertThat(result.posterUrl).isNull()
    }
}
