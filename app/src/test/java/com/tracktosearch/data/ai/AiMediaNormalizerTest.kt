package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiMediaNormalizerTest {

    @Test
    fun mediaKeyPrefersTmdbAndRetainsReliableSourceIds() {
        val result = normalizeMedia(
            MediaSourceSnapshot(
                mediaType = "movie",
                tmdbId = 123,
                traktId = 456,
                imdbId = "tt0000123",
                doubanId = "db-1",
                title = "标题只作为快照"
            )
        )

        assertThat(result.mediaKey).isEqualTo("movie:123")
        assertThat(result.tmdbId).isEqualTo(123)
        assertThat(result.traktId).isEqualTo(456)
        assertThat(result.imdbId).isEqualTo("tt0000123")
        assertThat(result.doubanId).isEqualTo("db-1")
    }

    @Test
    fun mediaKeyFallsBackToReliableIdButNeverUsesTitle() {
        val trakt = normalizeMedia(
            MediaSourceSnapshot(mediaType = "show", traktId = 456, title = "同名剧集")
        )
        val renamed = normalizeMedia(
            MediaSourceSnapshot(mediaType = "show", traktId = 456, title = "改名后的剧集")
        )

        assertThat(trakt.mediaKey).isEqualTo("show:trakt:456")
        assertThat(renamed.mediaKey).isEqualTo(trakt.mediaKey)
    }

    @Test
    fun mediaKeyUsesSourcePriorityAndRejectsMediaWithoutReliableId() {
        val imdb = normalizeMedia(
            MediaSourceSnapshot(mediaType = "movie", imdbId = "  tt7654321  ", doubanId = "db-2")
        )
        val douban = normalizeMedia(
            MediaSourceSnapshot(mediaType = "movie", doubanId = "  db-2  ")
        )
        val missing = runCatching {
            normalizeMedia(MediaSourceSnapshot(mediaType = "movie", title = "没有 ID 的标题"))
        }

        assertThat(imdb.mediaKey).isEqualTo("movie:imdb:tt7654321")
        assertThat(douban.mediaKey).isEqualTo("movie:douban:db-2")
        assertThat(missing.isFailure).isTrue()
    }
}
