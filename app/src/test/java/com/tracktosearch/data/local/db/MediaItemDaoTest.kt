package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
    private var migrationHelper: SupportSQLiteOpenHelper? = null
    private var migrationDatabaseName: String? = null

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaItemDao()
    }

    @After
    fun teardown() {
        db.close()
        migrationHelper?.close()
        migrationDatabaseName?.let { RuntimeEnvironment.getApplication().deleteDatabase(it) }
    }

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
    fun insertAll_相同traktId的其他分类不会覆盖想看电影快照() = runTest {
        val watchlistMovies = (1..10).map { traktId ->
            sample(
                traktId = traktId,
                type = "watchlist_movie",
                title = when (traktId) {
                    9 -> "爱情公寓"
                    10 -> "布达佩斯大饭店"
                    else -> "电影$traktId"
                },
                listedAt = "2024-06-${(21 - traktId).toString().padStart(2, '0')}T10:00:00Z"
            )
        }
        dao.insertAll(watchlistMovies)

        // 剧集与电影的 Trakt ID 分属不同命名空间；写入同号剧集不能挤掉第 9 部电影。
        dao.insertAll(
            listOf(
                sample(
                    traktId = 9,
                    type = "watchlist_show",
                    title = "同号剧集"
                )
            )
        )

        val movies = dao.getByTypeList("watchlist_movie")
        assertThat(movies).hasSize(10)
        assertThat(movies[8].title).isEqualTo("爱情公寓")
        assertThat(movies[9].title).isEqualTo("布达佩斯大饭店")
        assertThat(dao.getByTypeList("watchlist_show").single().title).isEqualTo("同号剧集")
    }

    @Test
    fun migration16To17_保留旧快照并允许不同分类使用相同traktId() {
        val context: Context = RuntimeEnvironment.getApplication()
        val databaseName = "media-item-migration-16-17-${System.nanoTime()}.db"
        migrationDatabaseName = databaseName
        migrationHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE media_items (
                                traktId INTEGER NOT NULL PRIMARY KEY,
                                tmdbId INTEGER NOT NULL,
                                type TEXT NOT NULL,
                                title TEXT NOT NULL,
                                displayTitle TEXT NOT NULL,
                                year INTEGER,
                                genres TEXT NOT NULL,
                                posterUrl TEXT,
                                imdbId TEXT NOT NULL,
                                traktRating REAL NOT NULL,
                                listedAt TEXT NOT NULL,
                                cachedAt INTEGER NOT NULL
                            )""".trimIndent()
                        )
                        db.execSQL(
                            """INSERT INTO media_items VALUES
                                (9, 109, 'watchlist_movie', '爱情公寓', '爱情公寓', 2018, '', NULL, 'tt1', 8.0, '2024-06-12T10:00:00Z', 1),
                                (10, 110, 'watchlist_movie', '布达佩斯大饭店', '布达佩斯大饭店', 2014, '', NULL, 'tt2', 8.0, '2024-06-11T10:00:00Z', 1)
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        val migratedDatabase = migrationHelper!!.writableDatabase

        DatabaseModule.MIGRATION_16_17.migrate(migratedDatabase)
        migratedDatabase.execSQL(
            """INSERT INTO media_items VALUES
                (9, 209, 'watchlist_show', '同号剧集', '同号剧集', 2024, '', NULL, 'tt3', 8.0, '2024-06-20T10:00:00Z', 2)
            """.trimIndent()
        )

        migratedDatabase.query(
            "SELECT title FROM media_items WHERE type = 'watchlist_movie' ORDER BY listedAt DESC"
        ).use { cursor ->
            val titles = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertThat(titles).containsExactly("爱情公寓", "布达佩斯大饭店").inOrder()
        }
        migratedDatabase.query(
            "SELECT title FROM media_items WHERE type = 'watchlist_show' AND traktId = 9"
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("同号剧集")
        }
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
    fun nullable字段_year和posterUrl支持null() = runTest {
        dao.insertAll(listOf(sample(traktId = 1, year = null, posterUrl = null)))
        val result = dao.getByTypeList("watchlist_movie")[0]
        assertThat(result.year).isNull()
        assertThat(result.posterUrl).isNull()
    }

    // ==================== 字段内容回读测试（与 bug 同字段）====================

    /**
     * 验证 displayTitle(中文) 和 posterUrl(非 null) 写入后能正确回读。
     * MediaItemEntity 用于想看/已看列表缓存，displayTitle/posterUrl 字段与
     * "标记记录列表标题英文+海报不显示"bug 同字段，需确保非 null 值正确持久化。
     */
    @Test
    fun insert_全字段回读_displayTitle和posterUrl非null() = runTest {
        dao.insertAll(listOf(sample(
            traktId = 100,
            tmdbId = 500,
            type = "watchlist_movie",
            title = "Inception",
            displayTitle = "盗梦空间",
            posterUrl = "https://image.tmdb.org/t/p/w500/inception.jpg",
            year = 2010,
            genres = "action,sci-fi",
            imdbId = "tt1375666",
            traktRating = 8.8,
            listedAt = "2024-06-15T10:00:00Z"
        )))
        val result = dao.getByTypeList("watchlist_movie")[0]
        // title 是 Trakt 原始英文标题
        assertThat(result.title).isEqualTo("Inception")
        // displayTitle 是 TMDB 中文标题 —— bug 核心字段
        assertThat(result.displayTitle).isEqualTo("盗梦空间")
        // posterUrl 是完整 URL —— bug 核心字段
        assertThat(result.posterUrl).isEqualTo("https://image.tmdb.org/t/p/w500/inception.jpg")
        // 其他字段也应正确回读
        assertThat(result.tmdbId).isEqualTo(500)
        assertThat(result.year).isEqualTo(2010)
        assertThat(result.genres).isEqualTo("action,sci-fi")
        assertThat(result.imdbId).isEqualTo("tt1375666")
        assertThat(result.traktRating).isEqualTo(8.8)
        assertThat(result.listedAt).isEqualTo("2024-06-15T10:00:00Z")
    }
}
