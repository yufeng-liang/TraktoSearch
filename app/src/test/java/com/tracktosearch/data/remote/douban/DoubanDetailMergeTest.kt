package com.tracktosearch.data.remote.douban

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.DoubanSyncedItem
import org.junit.Test

class DoubanDetailMergeTest {

    @Test
    fun rexxarValuesWinForBaseFieldsAndHtmlExtensionsRemain() {
        val html = DoubanDetailCacheEntry(
            imdbId = "tt-html",
            isTvShow = true,
            title = "HTML title",
            genres = listOf("HTML genre"),
            episodeCount = 12,
            ratingDistribution = listOf(60.0, 25.0, 10.0, 3.0, 2.0),
            celebrities = listOf(
                DoubanCelebrityCacheEntry(
                    name = "HTML actor",
                    role = "饰角色"
                )
            )
        )
        val snapshot = DoubanSyncedItem(
            doubanId = "db-1",
            imdbId = "tt-snapshot",
            traktId = null,
            title = "Snapshot title",
            status = "wish",
            rating = null,
            syncedAt = 1L,
            mediaType = "show",
            displayTitle = "Snapshot display title",
            genres = "Snapshot genre"
        )
        val rexxar = DoubanRexxarDetail(
            doubanId = "db-1",
            type = DoubanRexxarMediaType.TV,
            title = "Rexxar title",
            score = 9.1,
            ratingCount = 1000,
            genres = listOf("Rexxar genre"),
            poster = DoubanRexxarImage(largeUrl = "https://img.example/large.jpg"),
            summary = "Rexxar summary",
            imdbId = "tt-rexxar"
        )

        val result = mergeDoubanDetail(
            rexxar = rexxar,
            html = html,
            snapshot = snapshot,
            existing = DoubanDetailPresentation(overview = "Old overview")
        )

        assertThat(result.title).isEqualTo("Rexxar title")
        assertThat(result.score).isEqualTo(9.1)
        assertThat(result.ratingCount).isEqualTo(1000)
        assertThat(result.genres).containsExactly("Rexxar genre")
        assertThat(result.overview).isEqualTo("Rexxar summary")
        assertThat(result.imdbId).isEqualTo("tt-rexxar")
        assertThat(result.episodeCount).isEqualTo(12)
        assertThat(result.ratingDistribution).containsExactly(60.0, 25.0, 10.0, 3.0, 2.0).inOrder()
        assertThat(result.celebrities).hasSize(1)
        assertThat(result.celebrities.single().role).isEqualTo("饰角色")
    }

    @Test
    fun missingRexxarValuesFallBackToHtmlThenSnapshotThenExisting() {
        val existing = DoubanDetailPresentation(
            title = "Existing title",
            overview = "Existing overview",
            genres = listOf("Existing genre"),
            originalTitle = "Existing original"
        )
        val snapshot = DoubanSyncedItem(
            doubanId = "db-2",
            imdbId = null,
            traktId = null,
            title = "Snapshot title",
            status = "collect",
            rating = null,
            syncedAt = 2L,
            mediaType = "movie",
            displayTitle = "Snapshot display",
            year = 1994,
            genres = "Snapshot genre",
            subtitle = "Snapshot original"
        )
        val html = DoubanDetailCacheEntry(
            imdbId = "tt-html",
            isTvShow = false,
            title = "HTML title",
            genres = listOf("HTML genre"),
            year = "1994",
            summary = "HTML overview",
            aka = listOf("HTML alias"),
            runtime = "142 minutes"
        )

        val result = mergeDoubanDetail(
            rexxar = DoubanRexxarDetail(
                doubanId = "db-2",
                type = DoubanRexxarMediaType.MOVIE
            ),
            html = html,
            snapshot = snapshot,
            existing = existing
        )

        assertThat(result.title).isEqualTo("HTML title")
        assertThat(result.overview).isEqualTo("HTML overview")
        assertThat(result.genres).containsExactly("HTML genre")
        assertThat(result.year).isEqualTo(1994)
        assertThat(result.aliases).containsExactly("HTML alias")
        assertThat(result.runtime).isEqualTo("142 minutes")
        assertThat(result.originalTitle).isEqualTo("Snapshot original")

        val onlyExisting = mergeDoubanDetail(
            rexxar = null,
            html = null,
            snapshot = null,
            existing = existing
        )
        assertThat(onlyExisting.title).isEqualTo("Existing title")
        assertThat(onlyExisting.overview).isEqualTo("Existing overview")
        assertThat(onlyExisting.genres).containsExactly("Existing genre")
    }

    @Test
    fun onlyRexxarLargePosterReplacesExistingPoster() {
        assertThat(
            choosePosterUrl(
                existing = "https://img.example/existing.jpg",
                rexxarPoster = DoubanRexxarImage(normalUrl = "https://img.example/normal.jpg"),
                htmlPoster = "https://img.example/html.jpg",
                snapshotPoster = "https://img.example/snapshot.jpg"
            )
        ).isEqualTo("https://img.example/existing.jpg")

        assertThat(
            choosePosterUrl(
                existing = "https://img.example/existing.jpg",
                rexxarPoster = DoubanRexxarImage(largeUrl = "https://img.example/large.jpg"),
                htmlPoster = null,
                snapshotPoster = null
            )
        ).isEqualTo("https://img.example/large.jpg")

        assertThat(
            choosePosterUrl(
                existing = null,
                rexxarPoster = DoubanRexxarImage(normalUrl = "https://img.example/normal.jpg"),
                htmlPoster = "https://img.example/html.jpg",
                snapshotPoster = "https://img.example/snapshot.jpg"
            )
        ).isEqualTo("https://img.example/normal.jpg")

        val merged = mergeDoubanDetail(
            rexxar = DoubanRexxarDetail(
                doubanId = "db-3",
                type = DoubanRexxarMediaType.MOVIE,
                poster = DoubanRexxarImage(largeUrl = "https://img.example/large.jpg")
            ),
            html = null,
            snapshot = null,
            existing = DoubanDetailPresentation(posterUrl = "https://img.example/old.jpg")
        )
        assertThat(merged.largePosterUrl).isEqualTo("https://img.example/large.jpg")
        assertThat(merged.posterUrl).isEqualTo("https://img.example/large.jpg")
    }
}
