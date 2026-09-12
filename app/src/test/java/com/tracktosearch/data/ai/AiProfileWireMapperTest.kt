package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.AiProfileBehaviorDailyEntity
import com.tracktosearch.data.local.db.AiProfileMediaEntity
import com.tracktosearch.data.local.db.AiProfileMediaSourceEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AiProfileWireMapperTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun mediaPayloadUsesNestedSourcesAndWorkerFieldNames() {
        val payload = AiProfileMediaEntity(
            friendId = "friend-1",
            mediaKey = "movie:123",
            mediaType = "movie",
            tmdbId = 123,
            traktId = 456,
            title = "片名",
            genresJson = "[\"Drama\",\"Romance\"]",
            userRating = 8.0,
            userComment = "喜欢",
            isWatched = true,
            updatedAt = 1_700_000_000_000L
        ).toWirePayload(
            sources = listOf(
                AiProfileMediaSourceEntity(
                    friendId = "friend-1",
                    mediaKey = "movie:123",
                    source = "trakt",
                    sourceId = "456",
                    updatedAt = 1_700_000_000_100L
                )
            )
        )

        val encoded = json.encodeToString(payload)
        val jsonObjectValue = Json.parseToJsonElement(encoded).jsonObject
        assertThat(jsonObjectValue["mediaIds"]).isNotNull()
        assertThat(jsonObjectValue["sources"]).isNotNull()
        assertThat(jsonObjectValue["tmdbId"]).isNull()
        assertThat(jsonObjectValue["mediaIds"]!!.jsonObject["tmdbId"]!!.jsonPrimitive.content).isEqualTo("123")
        assertThat(payload.sources.single().ratingScale).isEqualTo(10)
        assertThat(payload.sources.single().watched).isTrue()
        assertThat(payload.sources.single().sourceUpdatedAt).isEqualTo(1_700_000_000L)
        assertThat(payload.sources.single().clientUpdatedAt).isEqualTo(1_700_000_000L)
        assertThat(payload.genres).containsExactly("Drama", "Romance").inOrder()
    }

    @Test
    fun behaviorPayloadUsesEventDayAndCoarseProgressBuckets() {
        val payload = AiProfileBehaviorDailyEntity(
            friendId = "friend-1",
            mediaKey = "show:88",
            day = "2026-08-13",
            dwell10To30Count = 1,
            dwell120PlusCount = 1,
            searchClickCount = 2,
            episodeStartCount = 3,
            episodeCompleteCount = 2,
            progress25Count = 4,
            progress50Count = 3,
            progress75Count = 2,
            progress100Count = 1,
            updatedAt = 1_700_000_000_000L
        ).toWirePayload()

        assertThat(payload.eventDay).isEqualTo("2026-08-13")
        assertThat(payload.detailDwellBucket).isEqualTo("OVER_ONE_HUNDRED_TWENTY_SECONDS")
        assertThat(payload.playerProgressBuckets).containsExactlyEntriesIn(
            mapOf("25" to 4, "50" to 3, "75" to 2, "completed" to 1)
        )
        assertThat(payload.episodeStartedCount).isEqualTo(3)
        assertThat(payload.episodeCompletedCount).isEqualTo(2)
        assertThat(payload.lastEventAt).isEqualTo(1_700_000_000L)
    }

    @Test
    fun malformedGenresAreIgnoredInsteadOfSplitAsText() {
        val payload = AiProfileMediaEntity(
            friendId = "friend-1",
            mediaKey = "movie:123",
            mediaType = "movie",
            genresJson = "not-json"
        ).toWirePayload(sources = emptyList())

        assertThat(payload.genres).isEmpty()
    }
}
