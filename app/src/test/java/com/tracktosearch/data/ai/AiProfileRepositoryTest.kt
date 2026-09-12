package com.tracktosearch.data.ai

import android.content.Context
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.AiProfileBehaviorDailyEntity
import com.tracktosearch.data.local.db.AiProfileMediaEntity
import com.tracktosearch.data.local.db.AiProfileMediaSourceEntity
import com.tracktosearch.data.local.db.AiProfileSettingsEntity
import com.tracktosearch.data.local.db.AppDatabase
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
class AiProfileRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AiProfileRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AiProfileRepository(database, database.aiProfileDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun buildSyncBatchOmitsHistoricalBehaviorWhenConsentIsDisabled() = runTest {
        val dao = database.aiProfileDao()
        dao.upsertSettings(
            AiProfileSettingsEntity(
                friendId = "friend-1",
                profileConsent = true,
                behaviorConsent = false
            )
        )
        dao.upsertMedia(
            AiProfileMediaEntity(
                friendId = "friend-1",
                mediaKey = "movie:1",
                mediaType = "movie",
                title = "电影"
            )
        )
        dao.upsertBehavior(
            AiProfileBehaviorDailyEntity(
                friendId = "friend-1",
                mediaKey = "movie:1",
                day = "2026-08-13",
                searchClickCount = 3
            )
        )

        val batch = repository.buildSyncBatch("friend-1", "profile-test")

        assertThat(batch.media).hasSize(1)
        assertThat(batch.behavior).isEmpty()
    }

    @Test
    fun buildSyncBatchSortsNestedSnapshotDataForStablePayloads() = runTest {
        val dao = database.aiProfileDao()
        dao.upsertSettings(
            AiProfileSettingsEntity(
                friendId = "friend-1",
                profileConsent = true,
                behaviorConsent = true
            )
        )
        dao.upsertMedia(
            AiProfileMediaEntity(
                friendId = "friend-1",
                mediaKey = "movie:2",
                mediaType = "movie",
                title = "二",
                updatedAt = 100L
            )
        )
        dao.upsertMedia(
            AiProfileMediaEntity(
                friendId = "friend-1",
                mediaKey = "movie:1",
                mediaType = "movie",
                title = "一",
                updatedAt = 100L
            )
        )
        dao.upsertMediaSources(
            listOf(
                AiProfileMediaSourceEntity(
                    friendId = "friend-1",
                    mediaKey = "movie:1",
                    source = "zeta",
                    sourceId = "z"
                ),
                AiProfileMediaSourceEntity(
                    friendId = "friend-1",
                    mediaKey = "movie:1",
                    source = "alpha",
                    sourceId = "a"
                )
            )
        )
        dao.upsertBehavior(
            AiProfileBehaviorDailyEntity(
                friendId = "friend-1",
                mediaKey = "movie:2",
                day = "2026-08-12",
                searchClickCount = 1
            )
        )
        dao.upsertBehavior(
            AiProfileBehaviorDailyEntity(
                friendId = "friend-1",
                mediaKey = "movie:1",
                day = "2026-08-13",
                searchClickCount = 1
            )
        )

        val batch = repository.buildSyncBatch("friend-1", "profile-test")

        assertThat(batch.media.map { it.mediaKey })
            .containsExactly("movie:1", "movie:2")
            .inOrder()
        assertThat(batch.media.first().sources.map { it.source })
            .containsExactly("alpha", "zeta")
            .inOrder()
        assertThat(batch.behavior.map { it.mediaKey to it.eventDay })
            .containsExactly("movie:1" to "2026-08-13", "movie:2" to "2026-08-12")
            .inOrder()
    }
}
