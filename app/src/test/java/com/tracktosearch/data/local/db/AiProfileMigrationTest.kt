package com.tracktosearch.data.local.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiProfileMigrationTest {

    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun teardown() {
        helper?.close()
    }

    @Test
    fun migration17To18CreatesProfileTablesAndPreservesExistingRows() {
        val context: Context = RuntimeEnvironment.getApplication()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("ai-profile-migration-${System.nanoTime()}.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE media_items (
                                traktId INTEGER NOT NULL PRIMARY KEY,
                                title TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL("INSERT INTO media_items (traktId, title) VALUES (7, '旧数据')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        val db = helper!!.writableDatabase
        DatabaseModule.MIGRATION_17_18.migrate(db)

        db.query("SELECT title FROM media_items WHERE traktId = 7").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("旧数据")
        }

        val expectedTables = listOf(
            "ai_profile_settings",
            "ai_profile_media",
            "ai_profile_media_source",
            "ai_profile_behavior_daily",
            "ai_profile_outbox",
            "ai_profile_snapshot"
        )
        expectedTables.forEach { table ->
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
            }
        }

        db.query("PRAGMA index_list('ai_profile_media')").use { cursor ->
            val indexes = buildList {
                val nameColumn = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) add(cursor.getString(nameColumn))
            }
            assertThat(indexes).contains("index_ai_profile_media_friendId")
        }
    }
}
