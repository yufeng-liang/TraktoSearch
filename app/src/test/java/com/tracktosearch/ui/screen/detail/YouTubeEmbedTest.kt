package com.tracktosearch.ui.screen.detail

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import org.junit.Test

class YouTubeEmbedTest {

    // ==================== 只接受合法 YouTube video key ====================

    @Test
    fun accepts_real_youtube_keys() {
        assertThat(YouTubeEmbed.isValidVideoKey("M7lc1UVf-VE")).isTrue()
        assertThat(YouTubeEmbed.isValidVideoKey("dQw4w9WgXcQ")).isTrue()
        assertThat(YouTubeEmbed.isValidVideoKey("a_b-c_1-234")).isTrue()
    }

    /** 远端数据可能被篡改，任何能拼进脚本的字符都不许通过。 */
    @Test
    fun rejects_keys_that_could_break_out_of_the_script() {
        assertThat(YouTubeEmbed.isValidVideoKey("abc';alert(1)//")).isFalse()
        assertThat(YouTubeEmbed.isValidVideoKey("abc<script>")).isFalse()
        assertThat(YouTubeEmbed.isValidVideoKey("short")).isFalse()
        assertThat(YouTubeEmbed.isValidVideoKey("")).isFalse()
        assertThat(YouTubeEmbed.isValidVideoKey("M7lc1UVf-VE\"")).isFalse()
    }

    // ==================== 客户端身份（官方要求的 Referer/origin） ====================

    @Test
    fun base_url_identifies_the_client_by_application_id() {
        assertThat(YouTubeEmbed.baseUrl("com.tracktosearch")).isEqualTo("https://com.tracktosearch/")
    }

    @Test
    fun html_carries_origin_and_the_validated_key_only() {
        val html = YouTubeEmbed.html(
            videoKey = "M7lc1UVf-VE",
            applicationId = "com.tracktosearch",
            languageTag = "zh-Hans-CN"
        )

        assertThat(html).contains("videoId: 'M7lc1UVf-VE'")
        assertThat(html).contains("origin:'https://com.tracktosearch'")
        assertThat(html).contains("hl:'zh-Hans-CN'")
        assertThat(html).contains("https://www.youtube.com/iframe_api")
        // 面板态播放，不自动全屏抢焦点
        assertThat(html).contains("playsinline:1")
        assertThat(html).doesNotContain("autoplay:1")
    }

    @Test
    fun html_rejects_an_invalid_key_instead_of_emitting_script() {
        val failure = runCatching { YouTubeEmbed.html("abc';alert(1)//", "com.tracktosearch", "en") }
        assertThat(failure.isFailure).isTrue()
    }

    // ==================== 导航白名单：只放行播放器自身的子框架 ====================

    @Test
    fun blocks_top_level_navigation_of_the_player_document() {
        assertThat(YouTubeEmbed.allowsNavigation("https://com.tracktosearch/", true)).isFalse()
        assertThat(YouTubeEmbed.allowsNavigation("https://www.youtube.com/embed/x", true)).isFalse()
    }

    @Test
    fun allows_youtube_subframes_only_over_https() {
        assertThat(YouTubeEmbed.allowsNavigation("https://www.youtube.com/embed/M7lc1UVf-VE", false)).isTrue()
        assertThat(YouTubeEmbed.allowsNavigation("https://www.youtube-nocookie.com/embed/x", false)).isTrue()
        assertThat(YouTubeEmbed.allowsNavigation("https://youtube.com/embed/x", false)).isTrue()
    }

    @Test
    fun blocks_non_youtube_targets_and_external_schemes() {
        assertThat(YouTubeEmbed.allowsNavigation("https://evil-youtube.com/embed/x", false)).isFalse()
        assertThat(YouTubeEmbed.allowsNavigation("https://youtube.com.evil.test/", false)).isFalse()
        assertThat(YouTubeEmbed.allowsNavigation("http://www.youtube.com/embed/x", false)).isFalse()
        assertThat(YouTubeEmbed.allowsNavigation("intent://scan/#Intent;scheme=zxing;end", false)).isFalse()
        assertThat(YouTubeEmbed.allowsNavigation("file:///data/data/com.tracktosearch/x", false)).isFalse()
    }

    // ==================== 错误码 → 可执行文案 ====================

    @Test
    fun maps_player_errors_to_actionable_messages() {
        // 加载失败与超时都归到「网络/VPN」这一档，用户知道该去开 VPN 或重试
        assertThat(YouTubeEmbed.errorMessage(-1)).isEqualTo(R.string.trailer_network_failed)
        assertThat(YouTubeEmbed.errorMessage(5)).isEqualTo(R.string.trailer_playback_failed)
        assertThat(YouTubeEmbed.errorMessage(100)).isEqualTo(R.string.trailer_unavailable)
        assertThat(YouTubeEmbed.errorMessage(101)).isEqualTo(R.string.trailer_embed_disabled)
        assertThat(YouTubeEmbed.errorMessage(150)).isEqualTo(R.string.trailer_embed_disabled)
        assertThat(YouTubeEmbed.errorMessage(153)).isEqualTo(R.string.trailer_identity_failed)
        assertThat(YouTubeEmbed.errorMessage(2)).isEqualTo(R.string.trailer_unavailable)
    }
}
