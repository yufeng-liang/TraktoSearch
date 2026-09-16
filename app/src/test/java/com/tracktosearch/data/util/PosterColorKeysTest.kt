package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 海报主色缓存 key 候选展开的单元测试。
 *
 * 背景：看单列表卡片用 Trakt 海报 URL 写入主色，详情页首帧优先用 TMDB 尺寸 URL 查询，
 * 两边 key 不一致就会表现为「先空白、后变色」。这里锁住「同一张海报不同尺寸能互相命中」。
 */
class PosterColorKeysTest {

    @Test
    fun tmdbFullUrl_expandsToAllKnownSizes() {
        val url = "https://example.com/gateway-api/api/tmdb-image-v2/t/p/w342/abc.jpg"

        val candidates = posterCacheKeyCandidates(url)

        assertThat(candidates).contains("https://example.com/gateway-api/api/tmdb-image-v2/t/p/w780/abc.jpg")
        assertThat(candidates).contains(url)
        assertThat(candidates).hasSize(5)
    }

    @Test
    fun traktUrl_staysSingleKey() {
        val url = "https://media.trakt.tv/images/movies/000/138/149/posters/medium/7d9c5a03ac.jpg.webp"

        assertThat(posterCacheKeyCandidates(url)).containsExactly(url)
    }

    @Test
    fun tmdbRelativePath_buildsSizedUrls() {
        val candidates = posterCacheKeyCandidates("/abc.jpg")

        assertThat(candidates).hasSize(5)
        assertThat(candidates.all { it.contains("/t/p/") }).isTrue()
        assertThat(candidates.any { it.contains("/w342/") }).isTrue()
        assertThat(candidates.any { it.contains("/w780/") }).isTrue()
    }

    @Test
    fun blankInput_returnsEmpty() {
        assertThat(posterCacheKeyCandidates("")).isEmpty()
        assertThat(posterCacheKeyCandidates("   ")).isEmpty()
    }
}
