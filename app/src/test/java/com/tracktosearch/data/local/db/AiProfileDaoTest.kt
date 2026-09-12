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
class AiProfileDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AiProfileDao

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.aiProfileDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun profileRowsAreIsolatedByFriendId() = runTest {
        dao.upsertSettings(AiProfileSettingsEntity(friendId = "friend-a", profileConsent = true))
        dao.upsertSettings(AiProfileSettingsEntity(friendId = "friend-b", profileConsent = false))
        dao.upsertMedia(media(friendId = "friend-a", mediaKey = "movie:1"))
        dao.upsertMedia(media(friendId = "friend-b", mediaKey = "movie:1"))
        dao.upsertOutbox(outbox(friendId = "friend-a", batchId = "batch-a"))

        assertThat(dao.getSettings("friend-a")!!.profileConsent).isTrue()
        assertThat(dao.getSettings("friend-b")!!.profileConsent).isFalse()
        assertThat(dao.getMedia("friend-a", "movie:1")!!.friendId).isEqualTo("friend-a")
        assertThat(dao.getMedia("friend-b", "movie:1")!!.friendId).isEqualTo("friend-b")
        assertThat(dao.getPendingOutbox("friend-b")).isEmpty()
    }

    @Test
    fun outboxIsIdempotentPerFriendAndBatch() = runTest {
        dao.upsertOutbox(outbox(friendId = "friend-a", batchId = "batch-1", payloadJson = "{\"v\":1}"))
        dao.upsertOutbox(outbox(friendId = "friend-a", batchId = "batch-1", payloadJson = "{\"v\":2}"))

        val rows = dao.getPendingOutbox("friend-a")
        assertThat(rows).hasSize(1)
        assertThat(rows.single().payloadJson).isEqualTo("{\"v\":2}")
        assertThat(dao.getOutbox("friend-a", "batch-1")!!.batchId).isEqualTo("batch-1")
    }

    @Test
    fun clearProfileRemovesOnlyTargetFriendAndDisablesAutoImport() = runTest {
        dao.upsertSettings(
            AiProfileSettingsEntity(
                friendId = "friend-a",
                profileConsent = true,
                behaviorConsent = true,
                shouldAutoImport = true
            )
        )
        dao.upsertSettings(AiProfileSettingsEntity(friendId = "friend-b", shouldAutoImport = true))
        dao.upsertMedia(media(friendId = "friend-a", mediaKey = "movie:1"))
        dao.upsertMedia(media(friendId = "friend-b", mediaKey = "movie:1"))
        dao.upsertOutbox(outbox(friendId = "friend-a", batchId = "batch-a"))
        dao.upsertOutbox(outbox(friendId = "friend-b", batchId = "batch-b"))

        dao.clearProfile("friend-a", clearedAt = 99L)

        assertThat(dao.getMedia("friend-a", "movie:1")).isNull()
        assertThat(dao.getPendingOutbox("friend-a")).isEmpty()
        assertThat(dao.getSettings("friend-a")!!.shouldAutoImport).isFalse()
        assertThat(dao.getSettings("friend-a")!!.profileConsent).isFalse()
        assertThat(dao.getMedia("friend-b", "movie:1")).isNotNull()
        assertThat(dao.getPendingOutbox("friend-b")).hasSize(1)
        assertThat(dao.getSettings("friend-b")!!.shouldAutoImport).isTrue()
    }

    @Test
    fun behaviorAndSnapshotAreScopedByFriendId() = runTest {
        dao.upsertBehavior(
            AiProfileBehaviorDailyEntity(
                friendId = "friend-a",
                mediaKey = "movie:1",
                day = "2026-08-13",
                searchClickCount = 2
            )
        )
        dao.upsertSnapshot(
            AiProfileSnapshotEntity(
                friendId = "friend-a",
                profileVersion = 4L,
                summaryJson = "{\"taste\":[]}"
            )
        )

        assertThat(dao.getBehavior("friend-a", "movie:1", "2026-08-13")!!.searchClickCount).isEqualTo(2)
        assertThat(dao.getBehavior("friend-b", "movie:1", "2026-08-13")).isNull()
        assertThat(dao.getSnapshot("friend-a")!!.profileVersion).isEqualTo(4L)
        assertThat(dao.getSnapshot("friend-b")).isNull()
    }

    private fun media(friendId: String, mediaKey: String) = AiProfileMediaEntity(
        friendId = friendId,
        mediaKey = mediaKey,
        mediaType = "movie",
        title = "标题"
    )

    private fun outbox(friendId: String, batchId: String, payloadJson: String = "{}") =
        AiProfileOutboxEntity(
            friendId = friendId,
            batchId = batchId,
            payloadJson = payloadJson,
            payloadDigest = "digest-$batchId"
        )
}
