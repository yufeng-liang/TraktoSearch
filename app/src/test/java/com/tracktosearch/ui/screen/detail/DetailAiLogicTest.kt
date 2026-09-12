package com.tracktosearch.ui.screen.detail

import com.tracktosearch.data.ai.AiDetailRecommendation
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.repository.MediaType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DetailAiLogicTest {

    @Test
    fun scenePriorityPrefersReviewedWatchedOverWatchlist() {
        assertThat(detailAiScene(watched = true, rating = 8, comment = "很好", watchlist = true))
            .isEqualTo(DetailAiScene.WATCHED_REVIEWED)
        assertThat(detailAiScene(watched = true, rating = null, comment = null, watchlist = true))
            .isEqualTo(DetailAiScene.WATCHED_NEEDS_REVIEW)
        assertThat(detailAiScene(watched = false, rating = null, comment = null, watchlist = true))
            .isEqualTo(DetailAiScene.WATCHLIST_CONTEXT)
    }

    @Test
    fun automaticEntryNeedsForegroundReadyDetailAndExactlyOneMediaVisit() {
        assertThat(shouldRevealDetailSprite(true, true, 3_499L, false)).isFalse()
        assertThat(shouldRevealDetailSprite(true, true, 3_500L, false)).isTrue()
        assertThat(shouldRevealDetailSprite(false, true, 9_000L, false)).isFalse()
        assertThat(shouldRevealDetailSprite(true, true, 9_000L, true)).isFalse()
    }

    @Test
    fun detailContentIdentityChangesWhenMediaIdentityChanges() {
        val first = detailContentIdentity(
            traktId = 1,
            tmdbId = 10,
            mediaType = MediaType.MOVIE,
            title = "第一部",
            doubanId = "douban-1"
        )

        assertThat(
            detailContentIdentity(
                traktId = 2,
                tmdbId = 20,
                mediaType = MediaType.SHOW,
                title = "第二部",
                doubanId = "douban-2"
            )
        ).isNotEqualTo(first)
    }

    @Test
    fun environmentKeyUsesOnlyCoarseWeather() {
        assertThat(
            detailEnvironmentKey(
                localDate = "2026-08-13",
                weekday = 4,
                timeOfDay = "EVENING",
                season = "SUMMER",
                weatherTag = "RAIN",
            )
        ).isEqualTo("2026-08-13|4|EVENING|SUMMER|RAIN")
    }

    @Test
    fun aiRecommendationsComeFirstAndDeduplicateByReliableMediaIdentity() {
        val merged = mergeAiRecommendations(
            currentMediaKey = "movie:1",
            existing = listOf(
                detailRecommendation(tmdbId = 2, title = "旧推荐二"),
                detailRecommendation(tmdbId = 3, title = "旧推荐三"),
            ),
            ai = listOf(
                detailRecommendation(tmdbId = 3, title = "重复推荐三"),
                detailRecommendation(tmdbId = 4, title = "AI 推荐四"),
                detailRecommendation(tmdbId = 1, title = "当前影视"),
            ),
        )

        assertThat(merged.map { it.tmdbId }).containsExactly(4, 3, 2).inOrder()
    }

    @Test
    fun recommendationTabCountUsesAiMergedItemsAfterRanking() {
        val existing = listOf(detailRecommendation(tmdbId = 2, title = "原推荐"))
        val ai = listOf(
            detailRecommendation(tmdbId = 3, title = "AI 推荐一"),
            detailRecommendation(tmdbId = 4, title = "AI 推荐二")
        )

        assertThat(detailRecommendationCount(aiLoaded = false, ai = emptyList(), existing = existing))
            .isEqualTo(1)
        assertThat(detailRecommendationCount(aiLoaded = true, ai = ai, existing = existing))
            .isEqualTo(2)
    }

    @Test
    fun recommendationWireCandidateRequiresReliableMediaIdentity() {
        assertThat(
            detailRecommendationWire(
                item = detailRecommendation(tmdbId = 42, title = "有身份的推荐"),
                mediaType = "movie"
            )
        ).isNotNull()
        assertThat(
            detailRecommendationWire(
                item = detailRecommendation(tmdbId = 0, title = "只有标题的推荐"),
                mediaType = "movie"
            )
        ).isNull()
    }

    @Test
    fun workerRecommendationRestoresIdsFromMediaKeyWhenIdsAreSparse() {
        val result = detailRecommendationFromAi(
            AiDetailRecommendation(
                mediaKey = "movie:trakt:77",
                mediaType = "movie",
                title = "AI 推荐",
                year = 2024,
                genres = listOf("Drama"),
                mediaIds = AiMediaIdsDto(),
                posterUrl = null,
                reason = "与你的评分相近"
            )
        )

        assertThat(result).isNotNull()
        assertThat(result!!.traktId).isEqualTo(77)
        assertThat(result.tmdbId).isEqualTo(0)
    }

    @Test
    fun shortDetailDwellIsNotACompletedPositiveSignal() {
        assertThat(shouldRecordDetailDwell(9_999L)).isFalse()
        assertThat(shouldRecordDetailDwell(10_000L)).isTrue()
    }
}
