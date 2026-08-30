package com.tracktosearch.ui.screen.dailystamp

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.repository.DailyStamp
import org.junit.Test
import java.time.LocalDate

/**
 * 日签格子与卡片的映射单测。
 *
 * 这两个映射把「哪种语言看到什么」定了下来：英文原文的条目片名仍跟界面语言、
 * 中文语境下印章底下要有英文小字、台词下线的那天不能凑出一张空卡。
 * 都是纯函数，直接测。
 */
class DailyStampModelsTest {

    private val date = LocalDate.of(2026, 8, 29)

    /**
     * 台词库里真有的一条，只是故意不给 ja：ja 那条测试要的就是缺语言时的回落。
     * 海报路径用假的，断言里看得出拼出来的 URL 长什么样。
     */
    private fun quote(
        id: String = "slumdog-millionaire",
        preferOriginal: Boolean = false,
        mediaType: String = SplashQuote.MEDIA_TYPE_MOVIE,
    ) = SplashQuote(
        id = id,
        year = 2008,
        mediaType = mediaType,
        tmdbId = 12405,
        posterPath = "/poster.jpg",
        preferOriginal = preferOriginal,
        lines = mapOf(
            "en" to listOf("It is written."),
            "zh" to listOf("这是命中注定。"),
        ),
        title = mapOf("en" to "Slumdog Millionaire", "zh" to "贫民窟的百万富翁"),
        keyword = mapOf("en" to "It Is Written", "zh" to "命定"),
    )

    private fun stamp(quote: SplashQuote?) = DailyStamp(
        date = date,
        quote = quote,
        poster = "poster-model",
    )

    @Test
    fun `格子带上关键词和海报`() {
        val cell = stamp(quote()).toCell("zh")

        assertThat(cell.date).isEqualTo(date)
        assertThat(cell.keyword).isEqualTo("命定")
        assertThat(cell.poster).isEqualTo("poster-model")
        assertThat(cell.openable).isTrue()
    }

    @Test
    fun `台词下线的那天格子仍在但打不开`() {
        val cell = stamp(null).toCell("zh")

        assertThat(cell.keyword).isEmpty()
        assertThat(cell.openable).isFalse()
    }

    @Test
    fun `台词下线的那天凑不出卡片`() {
        assertThat(stamp(null).toCard("zh")).isNull()
    }

    @Test
    fun `中文卡片走中文台词并在印章下留英文小字`() {
        val card = stamp(quote()).toCard("zh")!!

        assertThat(card.lines).containsExactly("这是命中注定。")
        assertThat(card.isEnglish).isFalse()
        assertThat(card.title).isEqualTo("贫民窟的百万富翁")
        assertThat(card.titleWrap).isEqualTo("《" to "》")
        assertThat(card.keyword).isEqualTo("命定")
        assertThat(card.keywordLatin).isEqualTo("It Is Written")
    }

    @Test
    fun `英文界面不重复印一遍英文关键词`() {
        val card = stamp(quote()).toCard("en")!!

        assertThat(card.keyword).isEqualTo("It Is Written")
        assertThat(card.keywordLatin).isNull()
        assertThat(card.titleWrap).isEqualTo("" to "")
    }

    /** preferOriginal 的条目台词走英文原文，片名仍跟界面语言：混排时才认得出是哪部片 */
    @Test
    fun `英文原文条目在中文界面下台词英文片名中文`() {
        val card = stamp(quote(preferOriginal = true)).toCard("zh")!!

        assertThat(card.lines).containsExactly("It is written.")
        assertThat(card.isEnglish).isTrue()
        assertThat(card.title).isEqualTo("贫民窟的百万富翁")
        assertThat(card.keyword).isEqualTo("命定")
    }

    @Test
    fun `日文界面用日式书名号并回落英文文案`() {
        val card = stamp(quote()).toCard("ja")!!

        assertThat(card.titleWrap).isEqualTo("『" to "』")
        // 这条测试用的台词没有 ja，按约定回落英文而不是空着
        assertThat(card.lines).containsExactly("It is written.")
        assertThat(card.title).isEqualTo("Slumdog Millionaire")
    }

    @Test
    fun `卡片带上详情页要用的 tmdbId 和海报地址`() {
        val card = stamp(quote()).toCard("zh")!!

        assertThat(card.tmdbId).isEqualTo(12405)
        assertThat(card.mediaType).isEqualTo(SplashQuote.MEDIA_TYPE_MOVIE)
        assertThat(card.posterUrl).endsWith("/poster.jpg")
        assertThat(card.posterUrl).contains("w342")
    }

    /**
     * 剧集条目要把 show 原样带到卡片上。
     *
     * 详情页按它决定查 /movie/ 还是 /tv/，而两个命名空间的 tmdbId 各自编号，
     * 丢了这个字段就会打开另一部作品。
     */
    @Test
    fun `剧集条目的卡片带上 show`() {
        val card = stamp(quote(mediaType = SplashQuote.MEDIA_TYPE_SHOW)).toCard("zh")!!

        assertThat(card.mediaType).isEqualTo("show")
    }
}
