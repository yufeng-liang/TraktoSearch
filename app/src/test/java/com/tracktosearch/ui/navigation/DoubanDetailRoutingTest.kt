package com.tracktosearch.ui.navigation

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.WatchlistMediaType
import org.junit.Test

class DoubanDetailRoutingTest {

    @Test
    fun `豆瓣条目没有 IMDb 时使用豆瓣详情框架`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = "db-1",
                imdbId = "",
                mediaType = WatchlistMediaType.MOVIE,
                traktId = 0,
                tmdbId = 0
            )
        ).isTrue()
    }

    @Test
    fun `有 IMDb 的电影豆瓣条目使用正常详情框架`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = "db-2",
                imdbId = "tt123",
                mediaType = WatchlistMediaType.MOVIE,
                traktId = 0,
                tmdbId = 0
            )
        ).isFalse()
    }

    @Test
    fun `有 IMDb 但媒体类型未知的豆瓣条目使用豆瓣详情框架`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = "db-3",
                imdbId = "tt456",
                mediaType = WatchlistMediaType.OTHER,
                traktId = 0,
                tmdbId = 0
            )
        ).isTrue()
    }

    @Test
    fun `没有豆瓣 ID 的 OTHER 条目不进入豆瓣详情框架`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = null,
                imdbId = "tt789",
                mediaType = WatchlistMediaType.OTHER,
                traktId = 0,
                tmdbId = 0
            )
        ).isFalse()
    }

    @Test
    fun `no IMDb but valid Trakt mapping still uses Douban detail`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = "db-trakt",
                imdbId = "",
                mediaType = WatchlistMediaType.MOVIE,
                traktId = 123,
                tmdbId = 0
            )
        ).isTrue()
    }

    @Test
    fun `no IMDb but valid TMDB mapping still uses Douban detail`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = "db-tmdb",
                imdbId = "",
                mediaType = WatchlistMediaType.SHOW,
                traktId = 0,
                tmdbId = 456
            )
        ).isTrue()
    }

    @Test
    fun `show with IMDb but without Trakt or TMDB mapping uses normal detail`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = "db-show-imdb",
                imdbId = "tt-show",
                mediaType = WatchlistMediaType.SHOW,
                traktId = 0,
                tmdbId = 0
            )
        ).isFalse()
    }

    @Test
    fun `other item with external mappings still uses Douban detail`() {
        assertThat(
            shouldUseDoubanItemDetail(
                doubanId = "db-other-mapped",
                imdbId = "tt-other",
                mediaType = WatchlistMediaType.OTHER,
                traktId = 123,
                tmdbId = 456
            )
        ).isTrue()
    }
}
