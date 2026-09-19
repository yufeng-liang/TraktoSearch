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
 * v18 -> v19 迁移测试。
 *
 * 看单离线快照沿用 media_items 表，迁移只做加法：新增 media_metadata 表与两个索引，
 * 不得改动或清空已有快照数据。
 */
@RunWith(RobolectricTestRunner::class)
// application 用系统空 Application：默认会起真 TraktSearchApp，Hilt 成员注入
// traktRepository 时直接解析 AppDatabase，SQLCipher loadLibs 在 JVM 无 native 库必炸。
// 本文件只测 Room schema/迁移 SQL，不需要 Hilt 图。
@Config(sdk = [33], application = android.app.Application::class)
class MediaMetadataMigrationTest {

    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun teardown() {
        helper?.close()
    }

    @Test
    fun migration18To19CreatesMediaMetadataTableAndPreservesExistingRows() {
        val context: Context = RuntimeEnvironment.getApplication()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("media-metadata-migration-${System.nanoTime()}.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE media_items (
                                traktId INTEGER NOT NULL,
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
                                cachedAt INTEGER NOT NULL,
                                PRIMARY KEY(traktId, type)
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO media_items VALUES
                            (7, 550, 'watchlist_movie', 'Fight Club', '搏击俱乐部', 1999, '剧情', NULL, 'tt0137523', 8.4, '2024-06-15T10:00:00Z', 1)
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        val db = helper!!.writableDatabase
        DatabaseModule.MIGRATION_18_19.migrate(db)

        // 既有快照数据必须原样保留
        db.query("SELECT title, displayTitle FROM media_items WHERE traktId = 7").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Fight Club")
            assertThat(cursor.getString(1)).isEqualTo("搏击俱乐部")
        }

        // 与 Room 亲建的 v19 参考库逐列、逐索引对照，任何偏差都会让真机打开时崩溃。
        val reference = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val referenceDb = reference.openHelper.writableDatabase
            assertThat(actualSchema(db)).isEqualTo(actualSchema(referenceDb))
        } finally {
            reference.close()
        }

        // 新表可正常写入并回读，验证列顺序之外的可用性
        db.execSQL(
            """
            INSERT INTO media_metadata
            (mediaKey, mediaType, tmdbId, locale, summaryJson, detailJson, schemaVersion, summaryRefreshedAt, detailRefreshedAt, updatedAt)
            VALUES ('movie:550:zh-CN', 'movie', 550, 'zh-CN', '{"title":"搏击俱乐部"}', NULL, 1, 100, 0, 100)
            """.trimIndent()
        )
        db.query("SELECT summaryJson FROM media_metadata WHERE mediaKey = 'movie:550:zh-CN'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("{\"title\":\"搏击俱乐部\"}")
        }
    }

    /** 归一化 media_metadata 的列定义与显式索引，用于跨库结构比对。 */
    private fun actualSchema(db: SupportSQLiteDatabase): Map<String, Any> {
        val columns = buildList {
            db.query("PRAGMA table_info('media_metadata')").use { cursor ->
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
            db.query("PRAGMA index_list('media_metadata')").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    // 跳过 Room 为唯一约束生成的内置索引，双方命名规则由 SQLite 保证一致
                    if (name.startsWith("index_media_metadata_")) add(name)
                }
            }
        }.sorted()
        return mapOf("columns" to columns, "indexes" to indexes)
    }
}
