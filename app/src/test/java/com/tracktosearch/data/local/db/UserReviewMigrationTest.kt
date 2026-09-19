package com.tracktosearch.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v19 -> v20 迁移测试：user_review 主键从裸 traktId 改为 (traktId, mediaType)。
 *
 * Trakt 电影/剧集 ID 分属不同命名空间可能同号（§5 防回归硬约束），裸主键会让
 * 两边的评分/短评互相覆盖。迁移只重建表结构，不改动/清空已有行。
 */
@RunWith(RobolectricTestRunner::class)
// application 用系统空 Application：默认会起真 TraktSearchApp，Hilt 成员注入
// traktRepository 时直接解析 AppDatabase，SQLCipher loadLibs 在 JVM 无 native 库必炸。
// 本文件只测 Room schema/迁移 SQL，不需要 Hilt 图。
@Config(sdk = [33], application = android.app.Application::class)
class UserReviewMigrationTest {

    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun teardown() {
        helper?.close()
    }

    @Test
    fun migration19To20RebuildsPrimaryKeyAndPreservesExistingRows() {
        val context: Context = RuntimeEnvironment.getApplication()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("user-review-migration-${System.nanoTime()}.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // v19 的 user_review：主键只有 traktId
                        db.execSQL(
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
                                traktCommentId INTEGER,
                                commentCheckedAt INTEGER,
                                liked INTEGER,
                                createdAt INTEGER,
                                updatedAt INTEGER,
                                syncedAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("CREATE INDEX index_user_review_mediaType ON user_review(mediaType)")
                        db.execSQL("CREATE INDEX index_user_review_tmdbId ON user_review(tmdbId)")
                        db.execSQL("CREATE INDEX index_user_review_imdbId ON user_review(imdbId)")
                        db.execSQL(
                            """
                            INSERT INTO user_review VALUES
                            (550, 550, 'tt0137523', 'movie', 'Fight Club', 1999, 9.0, '好片', 101, NULL, 1, 1000, 2000, 3000),
                            (601, 601, NULL, 'show', '示例剧集', 2020, 8.0, NULL, NULL, 500, NULL, NULL, NULL, 4000)
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        val db = helper!!.writableDatabase
        DatabaseModule.MIGRATION_19_20.migrate(db)

        // 既有行必须原样保留（含各列的值）
        db.query("SELECT traktId, mediaType, rating, comment, traktCommentId FROM user_review ORDER BY mediaType").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(550L)
            assertThat(cursor.getString(1)).isEqualTo("movie")
            assertThat(cursor.getDouble(2)).isEqualTo(9.0)
            assertThat(cursor.getString(3)).isEqualTo("好片")
            assertThat(cursor.getLong(4)).isEqualTo(101L)
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(601L)
            assertThat(cursor.getString(1)).isEqualTo("show")
        }

        // 复合主键生效：同 traktId 不同 mediaType 可以各存一行（迁移前会被主键拒绝）
        db.execSQL(
            """
            INSERT INTO user_review
            (traktId, tmdbId, imdbId, mediaType, title, year, rating, comment,
             traktCommentId, commentCheckedAt, liked, createdAt, updatedAt, syncedAt)
            VALUES (550, 601, NULL, 'show', '同名剧集', 2021, 7.5, NULL, NULL, NULL, NULL, NULL, NULL, 5000)
            """.trimIndent()
        )
        db.query("SELECT COUNT(*) FROM user_review WHERE traktId = 550").use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(2)
        }

        // 与 Room 亲建的 v20 参考库逐列、逐索引对照，任何偏差都会让真机打开时崩溃
        val reference = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val referenceDb = reference.openHelper.writableDatabase
            assertThat(actualSchema(db)).isEqualTo(actualSchema(referenceDb))
        } finally {
            reference.close()
        }
    }

    /** 归一化 user_review 的列定义与显式索引，用于跨库结构比对。 */
    private fun actualSchema(db: SupportSQLiteDatabase): Map<String, Any> {
        val columns = buildList {
            db.query("PRAGMA table_info('user_review')").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val typeIndex = cursor.getColumnIndex("type")
                val notNullIndex = cursor.getColumnIndex("notnull")
                val pkIndex = cursor.getColumnIndex("pk")
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(nameIndex),
                            cursor.getString(typeIndex),
                            cursor.getInt(notNullIndex) == 1,
                            cursor.getInt(pkIndex)
                        )
                    )
                }
            }
        }.sortedBy { it[0] as String }
        val indexes = buildList {
            db.query("PRAGMA index_list('user_review')").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    if (name.startsWith("index_user_review_")) add(name)
                }
            }
        }.sorted()
        return mapOf("columns" to columns, "indexes" to indexes)
    }
}
