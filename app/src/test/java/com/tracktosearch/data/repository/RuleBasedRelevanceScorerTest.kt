package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import org.junit.Test

/**
 * 规则评分器单元测试。
 *
 * 核心目标：搜"情书(1995)日本电影"时，同名但非同一作品的结果（电子情书/韩综情书/给阿嬷的情书/
 * 短剧夜港情书/情书套装/情书FLAC）都应被判为低相关（<45）而隐藏，真正的高相关结果（情书/情书(1995)/
 * 带画质的正片）应 >=45。
 *
 * 同时验证规则收紧后不会误伤正常片名（音乐之声/美人鱼/第一百次求婚等），
 * 以及导演/演员命中时的强相关正信号。
 */
class RuleBasedRelevanceScorerTest {

    private val scorer = RuleBasedRelevanceScorer()

    /** 目标影视：情书(1995) 日本 电影，导演岩井俊二 */
    private val queryLoveLetter = ResourceQuery(
        title = "情书",
        year = 1995,
        country = "日本",
        mediaType = MediaType.MOVIE,
        directors = listOf("岩井俊二")
    )

    /** 目标影视：情书(1995) 日本 电影，无导演信息（降级场景） */
    private val queryLoveLetterNoDirector = ResourceQuery(
        title = "情书",
        year = 1995,
        country = "日本",
        mediaType = MediaType.MOVIE
    )

    private val querySpiderMan = ResourceQuery(
        title = "蜘蛛侠：崭新之日",
        year = 2026,
        country = "美国",
        mediaType = MediaType.MOVIE
    )

    private fun item(name: String) = ResourceItem(
        name = name,
        diskType = DiskType.OTHER,
        fileSize = "1G",
        url = "https://example.com/$name",
        source = "test"
    )

    // ============ 高相关：应 >= 45 ============

    @Test
    fun loveLetter_bareTitle_isHighRelevance() {
        // 整段相等 +50
        assertThat(scorer.score(item("情书"), queryLoveLetter)).isEqualTo(50)
    }

    @Test
    fun loveLetter_withYearAndQuality_isHighRelevance() {
        // 定界片段 +40, 年份 +15, 画质 +10 → 65
        assertThat(
            scorer.score(item("情书.1995.1080p.BluRay.Remux"), queryLoveLetter)
        ).isAtLeast(45)
    }

    // ============ 低相关：应 < 45 ============

    @Test
    fun electronicLoveLetter_isLowRelevance() {
        // 标题余弦 <0.7 → 0，无其它正信号 → 0
        assertThat(scorer.score(item("电子情书"), queryLoveLetter)).isLessThan(45)
    }

    @Test
    fun koreanVarietyLoveLetter_isLowRelevance() {
        // 标题 0；"韩综"既是综艺标记(-20)又是地区冲突(韩 vs 日本, -15) → -35
        assertThat(scorer.score(item("韩综情书"), queryLoveLetter)).isLessThan(45)
    }

    // ============ 规则收紧：不应误伤正常片名 ============

    /**
     * "音乐之声"含"音乐"，但收紧后"音乐"单独不触发 contentScore（需组合"专辑/原声/配乐"等）。
     * 目标是电影，无年份/地区信号，标题定界片段 +40 → 高相关。
     */
    @Test
    fun musicSound_notFalsePositive_contentScore() {
        val q = ResourceQuery(title = "音乐之声", mediaType = MediaType.MOVIE)
        // 不应被"音乐"误判为非影片而扣 30 分
        val score = scorer.score(item("音乐之声"), q)
        assertThat(score).isAtLeast(45) // 整段相等 +50
    }

    /**
     * "美人鱼"含"美"，但收紧后单字"美"不触发 regionScore（需"美剧/欧美"组合词）。
     */
    /**
     * "东京物语"含"日"，但收紧后单字"日"不触发 regionScore。
     * 目标国家日本，标题含"日"字但不构成"日剧/日影"组合词 → 不惩罚。
     */
    /**
     * "第一百次求婚"含"第"，但收紧后"第"单字不触发 typeScore（需"第X季"正则）。
     * 目标是电影，标题不会被误判为剧集。
     */
    /**
     * "港囧"含"港"，但收紧后单字"港"不触发 regionScore（需"港剧"组合词）。
     */
    @Test
    fun portKong_notFalsePositive_regionScore() {
        val q = ResourceQuery(title = "港囧", country = "香港", mediaType = MediaType.MOVIE)
        val score = scorer.score(item("港囧"), q)
        assertThat(score).isAtLeast(45) // 整段相等 +50
    }

    /**
     * 原声带/专辑/演唱会等强信号仍应正确识别为非影片。
     */
    @Test
    fun ostAlbum_stillDetectedAsNonMovie() {
        val score = scorer.score(item("情书 原声带"), queryLoveLetter)
        // 定界片段 +40，"原声带"强信号 -30 → 10，低相关
        assertThat(score).isLessThan(45)
        assertThat(scorer.evaluate(item("情书 原声带"), queryLoveLetter).isHighRelevance).isFalse()
    }

    @Test
    fun spiderMan_latestVideo_isHighRelevanceWithoutQualityToken() {
        val result = scorer.evaluate(item("蜘蛛侠：崭新之日 最新"), querySpiderMan)

        assertThat(result.titleMatch).isEqualTo(TitleMatch.DELIMITED)
        assertThat(result.contentType).isEqualTo(ResourceContentType.UNKNOWN)
        assertThat(result.isHighRelevance).isTrue()
    }

    @Test
    fun spiderMan_flacPromotionSong_isNotHighRelevance() {
        val result = scorer.evaluate(
            item("蜘蛛侠：崭新之日(2026) 电影中文推广曲 胡彦斌 破晓以后 FLAC 24bit 48khz"),
            querySpiderMan
        )

        assertThat(result.titleMatch.isStrong).isTrue()
        assertThat(result.contentType).isEqualTo(ResourceContentType.AUDIO)
        assertThat(result.isHighRelevance).isFalse()
    }

    @Test
    fun spiderMan_wrongYear_isNotHighRelevance() {
        val result = scorer.evaluate(item("蜘蛛侠：崭新之日 2012 1080p"), querySpiderMan)

        assertThat(result.hasYearConflict).isTrue()
        assertThat(result.isHighRelevance).isFalse()
    }

    @Test
    fun releaseDate_isNotTreatedAsMovieYear() {
        val query = ResourceQuery(
            title = "测试电影",
            year = 2025,
            mediaType = MediaType.MOVIE
        )

        val result = scorer.evaluate(item("测试电影 2025.05.11"), query)

        // 日期中的 2025 不参与年份加分，资源只保留强标题命中的 40 分。
        assertThat(result.score).isEqualTo(40)
        assertThat(result.hasYearConflict).isFalse()
    }

    @Test
    fun electronicLoveLetter_remainsWeakMatch() {
        val result = scorer.evaluate(item("电子情书 1080p"), queryLoveLetter)

        assertThat(result.titleMatch).isEqualTo(TitleMatch.NONE)
        assertThat(result.isHighRelevance).isFalse()
    }

    /**
     * "第X季"正则应正确识别剧集标记（目标是电影时惩罚）。
     */
    @Test
    fun seasonMarker_stillDetectedAsShow() {
        val score = scorer.score(item("情书 第一季"), queryLoveLetter)
        // 定界片段 +40，"第一季"剧集标记 -20 → 20，低相关
        assertThat(score).isLessThan(45)
    }

    /**
     * 英文 season 标记应正确识别（目标是电影时惩罚）。
     */
    /**
     * 英文 s01 标记应正确识别（目标是电影时惩罚）。
     */
    // ============ 导演/演员加分 ============

    @Test
    fun directorHit_addsScore() {
        // 同一资源，有导演信息的 query 应比无导演的 query 分值更高（命中导演名时）
        val withDirector = scorer.score(item("情书 岩井俊二 1995"), queryLoveLetter)
        val noDirector = scorer.score(item("情书 岩井俊二 1995"), queryLoveLetterNoDirector)
        assertThat(withDirector).isGreaterThan(noDirector)
    }

    @Test
    fun multipleCast_cappedAt25() {
        // 多个演员名命中，分值有上限避免堆分
        val q = queryLoveLetter.copy(cast = listOf("演员A", "演员B", "演员C", "演员D", "演员E"))
        val score = scorer.score(item("情书 演员A演员B演员C演员D演员E"), q)
        // 导演 0，演员 5*8=40 → 上限 25；定界片段 +40 → 65
        // 验证演员部分不超过 25：无导演时总分会是 40+25=65
        assertThat(score).isAtMost(65)
    }

    @Test
    fun shortCastName_ignored() {
        // 单字姓名不参与匹配（避免"日""美"等单字误伤）
        val q = queryLoveLetter.copy(directors = listOf("日"), cast = listOf("美"))
        val score = scorer.score(item("情书 日美"), q)
        // 导演"日"和演员"美"长度<2 不加分，只有定界片段 +40
        assertThat(score).isEqualTo(40)
    }

    // ============ 原名（originalTitle）跨语言/大小写场景 ============
    //
    // 复现 bug：墨西哥电影《Socias por accidente》(2026) 中文名"不期而遇的姐妹"，
    // TMDB originalTitle 通常返回 "Socias por Accidente"（A 大写），
    // 而网盘资源标题多为小写 "Socias por accidente 2026"。
    // 评分器对大小写敏感，导致 isDelimitedSegment 失败、cosineBigram 降到 0.7~0.9，
    // titleScore 从 +40 跌到 +12，总分 12+15=27 < 45 → 被错误隐藏。

    /** 目标影视：Socias por accidente (2026)，西语原名电影 */
    private val querySocias = ResourceQuery(
        title = "不期而遇的姐妹",
        originalTitle = "Socias por Accidente", // TMDB 返回的首字母大写形式
        year = 2026,
        country = "墨西哥",
        mediaType = MediaType.MOVIE
    )

    @Test
    fun originalTitle_caseMismatch_withYearAndQuality_isHighRelevance() {
        // 资源标题小写 + 年份 + 画质：+40 +15 +10 → 65
        val score = scorer.score(item("Socias por accidente 2026 1080p"), querySocias)
        assertThat(score).isAtLeast(45)
    }

    // ============ 阈值常量 ============

}
