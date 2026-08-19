package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
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
class UserReviewDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: UserReviewDao
    private var migrationHelper: SupportSQLiteOpenHelper? = null

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.userReviewDao()
    }

    @After
    fun teardown() {
        db.close()
        migrationHelper?.close()
    }

    private fun sample(
        traktId: Long = 1L,
        tmdbId: Int? = 100,
        imdbId: String? = "tt0000001",
        mediaType: String = "movie",
        title: String? = "电影A",
        year: Int? = 2024,
        rating: Float? = 8f,
        comment: String? = "好看",
        liked: Boolean? = true,
        createdAt: Long? = 1000L,
        updatedAt: Long? = 2000L
    ) = UserReviewEntity(
        traktId = traktId, tmdbId = tmdbId, imdbId = imdbId,
        mediaType = mediaType, title = title, year = year,
        rating = rating, comment = comment, liked = liked,
        createdAt = createdAt, updatedAt = updatedAt
    )

    @Test
    fun upsert_新增_可查到() = runTest {
        dao.upsert(sample(traktId = 1L, title = "电影A"))
        val result = dao.getByTraktId(1L)
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("电影A")
    }

    @Test
    fun upsert_已有记录_覆盖更新() = runTest {
        dao.upsert(sample(traktId = 1L, rating = 7f, comment = "旧评"))
        dao.upsert(sample(traktId = 1L, rating = 9f, comment = "新评"))
        val result = dao.getByTraktId(1L)
        assertThat(result!!.rating).isEqualTo(9f)
        assertThat(result.comment).isEqualTo("新评")
    }

    @Test
    fun upsert_短评同步元数据_可查到() = runTest {
        dao.upsert(sample(traktId = 1L).copy(
            traktCommentId = 12345,
            commentCheckedAt = 1_700_000_000_000L
        ))

        val result = dao.getByTraktId(1L)

        assertThat(result!!.traktCommentId).isEqualTo(12345)
        assertThat(result.commentCheckedAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun migration13to14_保留短评并新增同步元数据列() {
        val context: Context = RuntimeEnvironment.getApplication()
        val databaseName = "user-review-v13-to-v14-test.db"
        context.deleteDatabase(databaseName)
        migrationHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                        database.execSQL(
                            """
                            CREATE TABLE user_review (
                                traktId INTEGER NOT NULL PRIMARY KEY,
                                tmdbId INTEGER,
                                imdbId TEXT,
                                mediaType TEXT NOT NULL,
                                title TEXT,
                                year INTEGER,
                                rating REAL,
                                comment TEXT,
                                liked INTEGER,
                                createdAt INTEGER,
                                updatedAt INTEGER,
                                syncedAt INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(
                        database: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        val oldDatabase = migrationHelper!!.writableDatabase
        oldDatabase.execSQL(
            "INSERT INTO user_review (traktId, mediaType, comment, syncedAt) VALUES (1, 'movie', '旧短评', 100)"
        )

        DatabaseModule.MIGRATION_13_14.migrate(oldDatabase)

        oldDatabase.query("SELECT comment, traktCommentId, commentCheckedAt FROM user_review WHERE traktId = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("旧短评")
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
        }
    }

    @Test
    fun getByTraktId_未命中_返回null() = runTest {
        assertThat(dao.getByTraktId(999L)).isNull()
    }

    @Test
    fun upsertAll_批量插入() = runTest {
        dao.upsertAll(listOf(
            sample(traktId = 1L, title = "A"),
            sample(traktId = 2L, title = "B"),
            sample(traktId = 3L, title = "C")
        ))
        assertThat(dao.getAll()).hasSize(3)
    }

    @Test
    fun upsertAll_重复主键_覆盖更新() = runTest {
        dao.upsertAll(listOf(
            sample(traktId = 1L, rating = 7f),
            sample(traktId = 2L, rating = 8f)
        ))
        dao.upsertAll(listOf(
            sample(traktId = 1L, rating = 9f)
        ))
        val all = dao.getAll()
        assertThat(all).hasSize(2)
        assertThat(all.first { it.traktId == 1L }.rating).isEqualTo(9f)
    }

    @Test
    fun getAll_空表_返回空列表() = runTest {
        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun getByMediaType_过滤电影() = runTest {
        dao.upsertAll(listOf(
            sample(traktId = 1L, mediaType = "movie", title = "电影1"),
            sample(traktId = 2L, mediaType = "show", title = "剧集1"),
            sample(traktId = 3L, mediaType = "movie", title = "电影2")
        ))
        val movies = dao.getByMediaType("movie")
        assertThat(movies).hasSize(2)
        assertThat(movies.map { it.title }).containsExactly("电影1", "电影2")
    }

    @Test
    fun getByMediaType_过滤剧集() = runTest {
        dao.upsertAll(listOf(
            sample(traktId = 1L, mediaType = "movie"),
            sample(traktId = 2L, mediaType = "show"),
            sample(traktId = 3L, mediaType = "show")
        ))
        assertThat(dao.getByMediaType("show")).hasSize(2)
    }

    @Test
    fun deleteByTraktId_删除指定() = runTest {
        dao.upsertAll(listOf(
            sample(traktId = 1L),
            sample(traktId = 2L),
            sample(traktId = 3L)
        ))
        dao.deleteByTraktId(2L)
        assertThat(dao.getAll()).hasSize(2)
        assertThat(dao.getByTraktId(2L)).isNull()
    }

    @Test
    fun deleteByTraktId_不存在的ID_无副作用() = runTest {
        dao.upsert(sample(traktId = 1L))
        dao.deleteByTraktId(999L)
        assertThat(dao.getAll()).hasSize(1)
    }

    @Test
    fun clear_清空全部() = runTest {
        dao.upsertAll(listOf(
            sample(traktId = 1L),
            sample(traktId = 2L)
        ))
        dao.clear()
        assertThat(dao.getAll()).isEmpty()
    }

    @Test
    fun nullable字段_支持null值存储() = runTest {
        dao.upsert(sample(
            traktId = 1L,
            tmdbId = null, imdbId = null,
            title = null, year = null,
            rating = null, comment = null,
            liked = null, createdAt = null, updatedAt = null
        ))
        val result = dao.getByTraktId(1L)
        assertThat(result).isNotNull()
        assertThat(result!!.tmdbId).isNull()
        assertThat(result.imdbId).isNull()
        assertThat(result.title).isNull()
        assertThat(result.rating).isNull()
    }
}
