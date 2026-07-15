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
        actedAt: Long = 1000L
    ) = MarkActionRecordEntity(
        traktId = traktId, tmdbId = 10, imdbId = "tt1",
        mediaType = mediaType, title = title, displayTitle = title,
        posterUrl = null, year = 2024, actionType = actionType,
        actedAt = actedAt, episodeInfo = null
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
}
