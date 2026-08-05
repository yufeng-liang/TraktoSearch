package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
class DoubanWatchlistDataLayerTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DoubanSyncedItemDao
    private var migrationHelper: SupportSQLiteOpenHelper? = null

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.doubanSyncedItemDao()
    }

    @After
    fun teardown() {
        db.close()
        migrationHelper?.close()
    }

    @Test
    fun snapshot_withoutExternalIds_preservesDetailFieldsAndMediaTypes() = runTest {
        val items = listOf("movie", "show", "variety", "documentary", "other").mapIndexed { index, mediaType ->
            snapshot(
                doubanId = "douban-$index",
                mediaType = mediaType,
                imdbId = null,
                traktId = null,
                tmdbId = null
            )
        }

        dao.upsertAll(items)

        val result = dao.getAllSyncedItems().sortedBy { it.doubanId }
        assertThat(result.map { it.mediaType })
            .containsExactly("movie", "show", "variety", "documentary", "other")
            .inOrder()
        assertThat(result.all { it.imdbId == null && it.traktId == null && it.tmdbId == null }).isTrue()
        assertThat(result.first { it.doubanId == "douban-0" }.doubanUrl)
            .isEqualTo("https://movie.douban.com/subject/douban-0/")
        assertThat(result.first { it.doubanId == "douban-0" }.comment).isEqualTo("短评")
        assertThat(result.first { it.doubanId == "douban-0" }.markedAt).isEqualTo("2024-06-15")
        assertThat(result.first { it.doubanId == "douban-0" }.subtitle).isEqualTo("原文标题")
    }

    @Test
    fun queries_byStatusAndAll_returnWatchlistSnapshots() = runTest {
        dao.upsertAll(listOf(
            snapshot(doubanId = "wish-1", status = "wish", syncedAt = 100L),
            snapshot(doubanId = "collect-1", status = "collect", syncedAt = 200L),
            snapshot(doubanId = "wish-2", status = "wish", syncedAt = 300L)
        ))

        assertThat(dao.getByStatus("wish").map { it.doubanId })
            .containsExactly("wish-2", "wish-1")
            .inOrder()
        assertThat(dao.getAllSyncedItems().map { it.doubanId })
            .containsExactly("wish-2", "collect-1", "wish-1")
            .inOrder()
    }

    @Test
    fun upsertUpdateAndDelete_areSafeForExistingAndMissingRows() = runTest {
        val original = snapshot(doubanId = "d1", title = "旧标题")
        dao.upsert(original)
        dao.upsert(original.copy(title = "新标题", status = "collect"))

        assertThat(dao.count()).isEqualTo(1)
        assertThat(dao.getByDoubanId("d1")!!.title).isEqualTo("新标题")

        dao.update(original.copy(title = "更新标题", status = "wish"))
        assertThat(dao.getByDoubanId("d1")!!.title).isEqualTo("更新标题")

        dao.update(snapshot(doubanId = "missing"))
        dao.delete(snapshot(doubanId = "missing"))
        dao.delete(dao.getByDoubanId("d1")!!)
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun migration12To13_importsFailureSnapshotsAndKeepsFailureTable() {
        val context: Context = RuntimeEnvironment.getApplication()
        val databaseName = "douban-migration-${System.nanoTime()}.db"
        migrationHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE douban_synced_items (
                                doubanId TEXT NOT NULL PRIMARY KEY,
                                imdbId TEXT,
                                traktId INTEGER,
                                title TEXT NOT NULL,
                                status TEXT NOT NULL,
                                rating INTEGER,
                                syncedAt INTEGER NOT NULL,
                                mediaType TEXT NOT NULL,
                                tmdbId INTEGER,
                                displayTitle TEXT,
                                year INTEGER,
                                genres TEXT,
                                posterUrl TEXT,
                                listedAt TEXT,
                                pendingSync INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE douban_sync_failures (
                                doubanId TEXT NOT NULL PRIMARY KEY,
                                title TEXT NOT NULL,
                                posterUrl TEXT,
                                rating INTEGER,
                                comment TEXT,
                                markedAt TEXT NOT NULL,
                                doubanUrl TEXT NOT NULL,
                                status TEXT NOT NULL,
                                failureReason TEXT NOT NULL,
                                failedAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL DEFAULT 0,
                                attemptCount INTEGER NOT NULL DEFAULT 0,
                                mediaType TEXT,
                                mediaTypeCleared INTEGER NOT NULL DEFAULT 0,
                                subtitle TEXT
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO douban_synced_items (
                                doubanId, imdbId, traktId, title, status, rating, syncedAt, mediaType,
                                tmdbId, displayTitle, year, genres, posterUrl, listedAt, pendingSync
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                "conflict-1", "tt-existing", 42, "旧同步标题", "wish", 2, 2000L, "movie",
                                99, "旧展示标题", 2020, "Drama", "old-poster", "2024-01-01", 0
                            )
                        )
                        db.execSQL(
                            """
                            INSERT INTO douban_sync_failures (
                                doubanId, title, posterUrl, rating, comment, markedAt, doubanUrl,
                                status, failureReason, failedAt, mediaType, subtitle
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                "failed-1", "失败电影", "poster", 4, "评论", "2024-01-02",
                                "https://movie.douban.com/subject/1/", "wish", "NETWORK_ERROR",
                                1234L, null, "副标题"
                            )
                        )
                        db.execSQL(
                            """
                            INSERT INTO douban_sync_failures (
                                doubanId, title, posterUrl, rating, comment, markedAt, doubanUrl,
                                status, failureReason, failedAt, mediaType, subtitle
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                "conflict-1", "失败时标题", "new-poster", 5, "冲突评论", "2024-02-03",
                                "https://movie.douban.com/subject/conflict-1/", "collect", "NETWORK_ERROR",
                                3000L, "show", "冲突副标题"
                            )
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        val oldDatabase = migrationHelper!!.writableDatabase
        DatabaseModule.MIGRATION_12_13.migrate(oldDatabase)

        oldDatabase.query(
            """
            SELECT imdbId, traktId, title, status, rating, mediaType, posterUrl, listedAt,
                   doubanUrl, comment, markedAt, subtitle, displayTitle, syncedAt
            FROM douban_synced_items WHERE doubanId = 'failed-1'
            """.trimIndent()
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.getString(2)).isEqualTo("失败电影")
            assertThat(cursor.getString(3)).isEqualTo("wish")
            assertThat(cursor.getInt(4)).isEqualTo(4)
            assertThat(cursor.getString(5)).isEqualTo("other")
            assertThat(cursor.getString(6)).isEqualTo("poster")
            assertThat(cursor.getString(7)).isEqualTo("2024-01-02")
            assertThat(cursor.getString(8)).isEqualTo("https://movie.douban.com/subject/1/")
            assertThat(cursor.getString(9)).isEqualTo("评论")
            assertThat(cursor.getString(10)).isEqualTo("2024-01-02")
            assertThat(cursor.getString(11)).isEqualTo("副标题")
            assertThat(cursor.getString(12)).isEqualTo("失败电影")
            assertThat(cursor.getLong(13)).isEqualTo(1234L)
        }
        oldDatabase.query("SELECT COUNT(*) FROM douban_sync_failures").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(2)
        }

        oldDatabase.query(
            """
            SELECT imdbId, traktId, title, status, rating, mediaType, posterUrl, listedAt,
                   doubanUrl, comment, markedAt, subtitle, displayTitle, syncedAt, tmdbId
            FROM douban_synced_items WHERE doubanId = 'conflict-1'
            """.trimIndent()
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("tt-existing")
            assertThat(cursor.getInt(1)).isEqualTo(42)
            assertThat(cursor.getString(2)).isEqualTo("失败时标题")
            assertThat(cursor.getString(3)).isEqualTo("collect")
            assertThat(cursor.getInt(4)).isEqualTo(5)
            assertThat(cursor.getString(5)).isEqualTo("movie")
             assertThat(cursor.getString(6)).isEqualTo("old-poster")
             assertThat(cursor.getString(7)).isEqualTo("2024-01-01")
            assertThat(cursor.getString(8)).isEqualTo("https://movie.douban.com/subject/conflict-1/")
            assertThat(cursor.getString(9)).isEqualTo("冲突评论")
            assertThat(cursor.getString(10)).isEqualTo("2024-02-03")
            assertThat(cursor.getString(11)).isEqualTo("冲突副标题")
            assertThat(cursor.getString(12)).isEqualTo("旧展示标题")
            assertThat(cursor.getLong(13)).isEqualTo(3000L)
            assertThat(cursor.getInt(14)).isEqualTo(99)
        }
    }

    private fun snapshot(
        doubanId: String = "d1",
        title: String = "电影A",
        status: String = "wish",
        mediaType: String = "movie",
        imdbId: String? = "tt0000001",
        traktId: Int? = 100,
        tmdbId: Int? = 200,
        syncedAt: Long = 1000L
    ) = DoubanSyncedItem(
        doubanId = doubanId,
        imdbId = imdbId,
        traktId = traktId,
        title = title,
        status = status,
        rating = 5,
        syncedAt = syncedAt,
        mediaType = mediaType,
        tmdbId = tmdbId,
        displayTitle = title,
        posterUrl = "https://img.example/$doubanId.jpg",
        doubanUrl = "https://movie.douban.com/subject/$doubanId/",
        comment = "短评",
        markedAt = "2024-06-15",
        listedAt = "2024-06-15",
        subtitle = "原文标题"
    )
}
