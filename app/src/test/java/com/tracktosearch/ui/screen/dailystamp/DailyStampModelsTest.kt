package com.tracktosearch.ui.screen.dailystamp

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.SplashPosterUrls
import com.tracktosearch.data.local.SplashQuote
import com.tracktosearch.data.repository.DailyStamp
import org.junit.Test
import java.time.LocalDate

/**
 * 日签格子与卡片的映射单测，外加四种日子的判定。
 *
 * 映射把「哪种语言看到什么」定了下来：英文原文的条目片名仍跟界面语言、
 * 中文语境下印章底下要有英文小字、台词下线的那天不能凑出一张空卡。
 * [dayKind] 定的是另一件事：一格空白到底空在哪儿。都是纯函数，直接测。
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
        // 签到过的那天当天就把台词念出来了，不存在没读过，永远不该是糊的
        assertThat(cell.blurred).isFalse()
    }

    @Test
    fun `错过那天没读过时只给糊海报不印关键词`() {
        val cell = stamp(quote()).toMissedCell("zh", read = false)

        // 海报照画，只是糊着：它说的是「那天有一句话」。词一印上去就先透了底
        assertThat(cell.poster).isEqualTo("poster-model")
        assertThat(cell.blurred).isTrue()
        assertThat(cell.keyword).isEmpty()
    }

    @Test
    fun `错过那天读过之后印清晰的图和词`() {
        val cell = stamp(quote()).toMissedCell("zh", read = true)

        assertThat(cell.blurred).isFalse()
        assertThat(cell.keyword).isEqualTo("命定")
        assertThat(cell.poster).isEqualTo("poster-model")
    }

    @Test
    fun `台词下线的那天格子仍在但没有关键词`() {
        val cell = stamp(null).toCell("zh")

        assertThat(cell.date).isEqualTo(date)
        assertThat(cell.keyword).isEmpty()
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
        // 没查过 TMDB 的条目退回台词库自带的路径，卡片照样有地址可给详情页
        assertThat(card.posterUrl).endsWith("/poster.jpg")
    }

    /**
     * 查过 TMDB 的条目跟着那一版海报走。
     *
     * 详情页首屏和沉浸取色都按这个地址找缓存，三处对齐才不用重下一张图、重算一次主色。
     * 用独立 id：SplashPosterUrls 是进程级缓存，登记过的条目别的用例也看得见。
     */
    @Test
    fun `解析过海报路径的条目用 TMDB 那一版地址`() {
        val resolved = quote(id = "resolved-poster")
        SplashPosterUrls.remember(resolved.id, "zh", "/from-tmdb.jpg")

        val card = stamp(resolved).toCard("zh")!!

        assertThat(card.posterUrl).endsWith("/from-tmdb.jpg")
    }

    /** TMDB 海报分语言，一种语言查到的不能顶另一种用，否则中文界面会拿到英文那张 */
    @Test
    fun `另一种语言不共用已解析的海报地址`() {
        val resolved = quote(id = "lang-split-poster")
        SplashPosterUrls.remember(resolved.id, "zh", "/only-zh.jpg")

        val card = stamp(resolved).toCard("en")!!

        assertThat(card.posterUrl).endsWith("/poster.jpg")
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

    /**
     * 未来那天只带日期和一张最小档海报地址，台词、片名、印文一个字都不带出来。
     *
     * 这是保密的实现方式：不显示不是「显示了再遮住」——遮罩会随实现走样，
     * 而没进 UI 层的字段谁也渲染不出来。地址取 w92 那一档，卡片解到十几个像素再放大。
     */
    @Test
    fun `未来那天的卡片只带日期和最小档海报地址`() {
        val latent = stamp(quote()).toLatent("zh")

        assertThat(latent.date).isEqualTo(date)
        assertThat(latent.tinyPosterUrl).contains("w92")
        assertThat(latent.tinyPosterUrl).endsWith("/poster.jpg")
    }

    @Test
    fun `签到过的那天算来过`() {
        assertThat(dayKind(date, today = date, firstUse = date, stamped = true))
            .isEqualTo(DayKind.Stamped)
    }

    @Test
    fun `还没到的日子既不算错过也不算空白`() {
        val kind = dayKind(
            date = date.plusDays(1),
            today = date,
            firstUse = date.minusDays(10),
            stamped = false,
        )

        assertThat(kind).isEqualTo(DayKind.Future)
    }

    /**
     * 「你来之前」和「你错过了」得分开：前者不是错过，不该拿虚线空框去责备。
     *
     * 一条签到都没有（firstUse 为 null）时同理——没有任何证据说明这个人来过，
     * 整本日历的过去都只是空白。
     */
    @Test
    fun `初次使用之前那些天算你还没来`() {
        val firstUse = date.minusDays(5)

        assertThat(dayKind(firstUse.minusDays(1), date, firstUse, stamped = false))
            .isEqualTo(DayKind.Unarrived)
        assertThat(dayKind(date.minusDays(1), date, firstUse = null, stamped = false))
            .isEqualTo(DayKind.Unarrived)
    }

    /**
     * 来过之后没打开的那天才是错过。
     *
     * 今天也可能是错过的：拿不到台词时 checkIn 不写库（见 DailyStampRepository.checkIn），
     * 那一格该能翻开、该显示补看的台词，不能因为「就是今天」而算成还没到。
     */
    @Test
    fun `初次使用之后没打开的那天算错过`() {
        val firstUse = date.minusDays(5)

        assertThat(dayKind(date.minusDays(2), date, firstUse, stamped = false))
            .isEqualTo(DayKind.Missed)
        assertThat(dayKind(date, date, firstUse, stamped = false))
            .isEqualTo(DayKind.Missed)
    }
}
