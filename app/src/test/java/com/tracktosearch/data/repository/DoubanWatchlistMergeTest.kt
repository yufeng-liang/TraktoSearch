package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoubanWatchlistMergeTest {

    @Test
    fun `same non empty imdb ids merge ignoring case and whitespace`() {
        val result = mergeTraktAndDoubanWatchlist(
            traktEntries = listOf(
                trakt(
                    traktId = 7,
                    title = "Trakt title",
                    imdbId = "  TT123  ",
                    year = 2020,
                    genres = listOf("Drama"),
                    posterUrl = "trakt-poster",
                    listedAt = "2024-01-01T00:00:00Z"
                )
            ),
            doubanEntries = listOf(
                douban(
                    doubanId = "douban-7",
                    title = "Douban title",
                    displayTitle = "豆瓣标题",
                    imdbId = "tt123",
                    year = 1999,
                    genres = listOf("Comedy"),
                    posterUrl = "douban-poster",
                    listedAt = "2024-02-01T00:00:00Z"
                )
            )
        )

        val item = result.single()
        assertThat(item.origin).isEqualTo(WatchlistEntryOrigin.MATCHED)
        assertThat(item.title).isEqualTo("Trakt title")
        assertThat(item.displayTitle).isEqualTo("豆瓣标题")
        assertThat(item.year).isEqualTo(2020)
        assertThat(item.genres).containsExactly("Drama")
        assertThat(item.posterUrl).isEqualTo("trakt-poster")
        assertThat(item.doubanId).isEqualTo("douban-7")
        assertThat(item.listedAt).isEqualTo("2024-02-01T00:00:00Z")
        assertThat(item.state).isEqualTo(WatchlistState(inWatchlist = true, watched = false))
    }

    @Test
    fun `entries without imdb ids are never merged`() {
        val result = mergeTraktAndDoubanWatchlist(
            traktEntries = listOf(trakt(traktId = 1, title = "Trakt item", imdbId = " ")),
            doubanEntries = listOf(douban(doubanId = "d1", title = "Douban item", imdbId = null))
        )

        assertThat(result).hasSize(2)
        assertThat(result.map { it.origin })
            .containsExactly(WatchlistEntryOrigin.TRAKT_ONLY, WatchlistEntryOrigin.DOUBAN_ONLY)
            .inOrder()
    }

    @Test
    fun `matching trakt ids merge when imdb ids are missing`() {
        val result = mergeTraktAndDoubanWatchlist(
            traktEntries = listOf(trakt(traktId = 42, title = "Trakt item", imdbId = null)),
            doubanEntries = listOf(
                douban(doubanId = "d42", title = "Douban item", imdbId = null)
                    .copy(traktId = 42)
            )
        )

        assertThat(result).hasSize(1)
        assertThat(result.single().origin).isEqualTo(WatchlistEntryOrigin.MATCHED)
        assertThat(result.single().doubanId).isEqualTo("d42")
        assertThat(result.single().traktId).isEqualTo(42)
    }

    @Test
    fun `collect status wins over wish for the same douban record`() {
        val result = mergeTraktAndDoubanWatchlist(
            doubanEntries = listOf(
                douban(
                    doubanId = "same",
                    status = DoubanWatchlistStatus.WISH,
                    listedAt = "2024-03-01T00:00:00Z"
                ),
                douban(
                    doubanId = "same",
                    status = DoubanWatchlistStatus.COLLECT,
                    listedAt = "2024-01-01T00:00:00Z"
                )
            )
        )

        val item = result.single()
        assertThat(item.douban?.status).isEqualTo(DoubanWatchlistStatus.COLLECT)
        assertThat(item.state).isEqualTo(WatchlistState(inWatchlist = false, watched = true))
    }

    @Test
    fun `media type maps only movie and show while other values become other`() {
        assertThat(mapDoubanMediaType("movie")).isEqualTo(WatchlistMediaType.MOVIE)
        assertThat(mapDoubanMediaType(" SHOW ")).isEqualTo(WatchlistMediaType.SHOW)
        assertThat(mapDoubanMediaType("variety")).isEqualTo(WatchlistMediaType.OTHER)
        assertThat(mapDoubanMediaType("documentary")).isEqualTo(WatchlistMediaType.OTHER)
        assertThat(mapDoubanMediaType(null)).isEqualTo(WatchlistMediaType.OTHER)
    }

    @Test
    fun `union keeps trakt only douban only and matched entries`() {
        val result = mergeTraktAndDoubanWatchlist(
            traktEntries = listOf(
                trakt(traktId = 1, title = "Matched", imdbId = "tt1"),
                trakt(traktId = 2, title = "Trakt only", imdbId = "tt2")
            ),
            doubanEntries = listOf(
                douban(doubanId = "d1", title = "Douban match", imdbId = "TT1"),
                douban(doubanId = "d2", title = "Douban only", imdbId = "tt3")
            )
        )

        assertThat(result.map { it.origin })
            .containsExactly(
                WatchlistEntryOrigin.MATCHED,
                WatchlistEntryOrigin.TRAKT_ONLY,
                WatchlistEntryOrigin.DOUBAN_ONLY
            )
            .inOrder()
    }

    @Test
    fun `latest listed at sorts first and equal dates preserve input order`() {
        val result = mergeTraktAndDoubanWatchlist(
            traktEntries = listOf(
                trakt(traktId = 1, title = "First", listedAt = "2024-01-01T00:00:00Z"),
                trakt(traktId = 2, title = "Latest", listedAt = "2024-03-01T00:00:00Z")
            ),
            doubanEntries = listOf(
                douban(doubanId = "d1", title = "Same date", listedAt = "2024-01-01T00:00:00Z")
            )
        )

        assertThat(result.map { it.title })
            .containsExactly("Latest", "First", "Same date")
            .inOrder()
    }

    @Test
    fun `empty input returns the other side without changing its entries`() {
        val doubanEntries = listOf(
            douban(doubanId = "d1", title = "First"),
            douban(doubanId = "d2", title = "Second")
        )

        val result = mergeTraktAndDoubanWatchlist(doubanEntries = doubanEntries)

        assertThat(result.map { it.doubanId }).containsExactly("d1", "d2").inOrder()
        assertThat(result.map { it.origin })
            .containsExactly(WatchlistEntryOrigin.DOUBAN_ONLY, WatchlistEntryOrigin.DOUBAN_ONLY)
            .inOrder()
        assertThat(result.map { it.title }).containsExactly("First", "Second").inOrder()
    }

    private fun trakt(
        traktId: Int,
        title: String = "Trakt $traktId",
        imdbId: String? = "tt$traktId",
        year: Int? = null,
        genres: List<String> = emptyList(),
        posterUrl: String? = null,
        listedAt: String? = null
    ) = TraktWatchlistRecord(
        traktId = traktId,
        title = title,
        imdbId = imdbId,
        year = year,
        genres = genres,
        posterUrl = posterUrl,
        listedAt = listedAt
    )

    private fun douban(
        doubanId: String,
        title: String = "Douban $doubanId",
        displayTitle: String? = null,
        imdbId: String? = null,
        year: Int? = null,
        genres: List<String> = emptyList(),
        posterUrl: String? = null,
        listedAt: String? = null,
        status: DoubanWatchlistStatus = DoubanWatchlistStatus.WISH,
        mediaType: String? = "movie"
    ) = DoubanWatchlistRecord(
        doubanId = doubanId,
        title = title,
        displayTitle = displayTitle,
        imdbId = imdbId,
        year = year,
        genres = genres,
        posterUrl = posterUrl,
        listedAt = listedAt,
        status = status,
        mediaType = mediaType
    )
}
