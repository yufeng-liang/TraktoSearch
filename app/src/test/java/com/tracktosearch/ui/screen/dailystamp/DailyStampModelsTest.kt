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

    private fun quote(
        id: String = "casablanca",
        preferOriginal: Boolean = false,
    ) = SplashQuote(
        id = id,
        year = 1942,
        tmdbId = 289,
        posterPath = "/poster.jpg",
        preferOriginal = preferOriginal,
        lines = mapOf(
            "en" to listOf("Here's looking at you, kid."),
            "zh" to listOf("为你的眼睛干杯。"),
        ),
        title = mapOf("en" to "Casablanca", "zh" to "卡萨布兰卡"),
        keyword = mapOf("en" to "Farewell", "zh" to "惜别"),
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
        assertThat(cell.keyword).isEqualTo("惜别")
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

        assertThat(card.lines).containsExactly("为你的眼睛干杯。")
        assertThat(card.isEnglish).isFalse()
        assertThat(card.title).isEqualTo("卡萨布兰卡")
        assertThat(card.titleWrap).isEqualTo("《" to "》")
        assertThat(card.keyword).isEqualTo("惜别")
        assertThat(card.keywordLatin).isEqualTo("Farewell")
    }

    @Test
    fun `英文界面不重复印一遍英文关键词`() {
        val card = stamp(quote()).toCard("en")!!

        assertThat(card.keyword).isEqualTo("Farewell")
        assertThat(card.keywordLatin).isNull()
        assertThat(card.titleWrap).isEqualTo("" to "")
    }

    /** preferOriginal 的条目台词走英文原文，片名仍跟界面语言：混排时才认得出是哪部片 */
    @Test
    fun `英文原文条目在中文界面下台词英文片名中文`() {
        val card = stamp(quote(preferOriginal = true)).toCard("zh")!!

        assertThat(card.lines).containsExactly("Here's looking at you, kid.")
        assertThat(card.isEnglish).isTrue()
        assertThat(card.title).isEqualTo("卡萨布兰卡")
        assertThat(card.keyword).isEqualTo("惜别")
    }

    @Test
    fun `日文界面用日式书名号并回落英文文案`() {
        val card = stamp(quote()).toCard("ja")!!

        assertThat(card.titleWrap).isEqualTo("『" to "』")
        // 这条测试用的台词没有 ja，按约定回落英文而不是空着
        assertThat(card.lines).containsExactly("Here's looking at you, kid.")
        assertThat(card.title).isEqualTo("Casablanca")
    }

    @Test
    fun `卡片带上详情页要用的 tmdbId 和海报地址`() {
        val card = stamp(quote()).toCard("zh")!!

        assertThat(card.tmdbId).isEqualTo(289)
        assertThat(card.posterUrl).endsWith("/poster.jpg")
        assertThat(card.posterUrl).contains("w342")
    }
}
