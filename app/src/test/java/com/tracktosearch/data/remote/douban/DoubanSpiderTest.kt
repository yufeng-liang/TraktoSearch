package com.tracktosearch.data.remote.douban

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoubanSpiderTest {

    private fun loadFixture(name: String): String {
        val path = "/fixtures/douban/$name"
        return javaClass.getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("fixture 文件未找到: $path")
    }

    // ==================== isLoginPage ====================

    @Test
    fun isLoginPage_loginPageHtml_returnsTrue() {
        val html = loadFixture("login_page.html")
        assertThat(DoubanSpider.isLoginPage(html)).isTrue()
    }

    @Test
    fun isLoginPage_markListHtml_returnsFalse() {
        val html = loadFixture("mark_list.html")
        assertThat(DoubanSpider.isLoginPage(html)).isFalse()
    }

    // ==================== parseCsrfToken ====================

    @Test
    fun parseCsrfToken_htmlWithToken_returnsToken() {
        val html = loadFixture("login_page.html")
        val token = DoubanSpider.parseCsrfToken(html)
        assertThat(token).isEqualTo("test_csrf_token_123")
    }

    @Test
    fun parseCsrfToken_htmlWithoutToken_returnsNull() {
        val html = "<html><body>no token here</body></html>"
        assertThat(DoubanSpider.parseCsrfToken(html)).isNull()
    }

    // ==================== parseMarkList / parseMarkListPage ====================

    @Test
    fun parseMarkList_markListHtml_returnsItems() {
        val html = loadFixture("mark_list.html")
        val items = DoubanSpider.parseMarkList(html)
        assertThat(items).hasSize(3)

        // 第一条：有评分、有评论
        val first = items[0]
        assertThat(first.doubanId).isEqualTo("1292052")
        assertThat(first.title).isEqualTo("肖申克的救赎")
        assertThat(first.rating).isEqualTo(3)
        assertThat(first.comment).isEqualTo("很好看")
        assertThat(first.markedAt).isEqualTo("2024-01-15")
        assertThat(first.doubanUrl).contains("subject/1292052")
        assertThat(first.posterUrl).isNotNull()

        // 第二条：无评分、无评论
        val second = items[1]
        assertThat(second.doubanId).isEqualTo("1295699")
        assertThat(second.title).isEqualTo("这个杀手不太冷")
        assertThat(second.rating).isNull()
        assertThat(second.comment).isNull()
        assertThat(second.markedAt).isEqualTo("2024-01-10")

        // 第三条：有评分、有评论
        val third = items[2]
        assertThat(third.doubanId).isEqualTo("1292728")
        assertThat(third.title).isEqualTo("阿甘正传")
        assertThat(third.rating).isEqualTo(5)
        assertThat(third.comment).isEqualTo("经典")
    }

    @Test
    fun parseMarkListPage_markListHtml_returnsItemsAndTotalCount() {
        val html = loadFixture("mark_list.html")
        val page = DoubanSpider.parseMarkListPage(html)
        assertThat(page.items).hasSize(3)
        assertThat(page.totalCount).isEqualTo(42)
    }

    // ==================== parseDetail (movie) ====================

    @Test
    fun parseDetail_movieHtml_returnsDetailInfo() {
        val html = loadFixture("detail_movie.html")
        val detail = DoubanSpider.parseDetail(html)

        assertThat(detail.title).isEqualTo("肖申克的救赎")
        assertThat(detail.imdbId).isEqualTo("tt0111161")
        assertThat(detail.year).isEqualTo("1994")
        assertThat(detail.genres).containsExactly("剧情", "犯罪")
        assertThat(detail.countries).containsExactly("美国")
        assertThat(detail.directors).containsExactly("弗兰克·德拉邦特")
        assertThat(detail.writers).containsExactly("弗兰克·德拉邦特")
        assertThat(detail.cast).containsExactly("蒂姆·罗宾斯", "摩根·弗里曼")
        assertThat(detail.aka).containsExactly("月黑高飞(港)", "刺激1995(台)", "Hope(日)")
        assertThat(detail.languages).containsExactly("英语")
        assertThat(detail.doubanRating).isEqualTo(9.7)
        assertThat(detail.ratingCount).isEqualTo(1234567)
        assertThat(detail.runtime).isEqualTo("142分钟")
        assertThat(detail.summary).isEqualTo("这是一部关于希望的电影。")
        assertThat(detail.posterUrl).isEqualTo("https://img.example.com/movie_poster.jpg")
        assertThat(detail.isTvShow).isFalse()
        assertThat(detail.episodeCount).isNull()
        assertThat(detail.episodeDuration).isNull()
        // 评分分布：5星→1星
        assertThat(detail.ratingDistribution).hasSize(5)
        assertThat(detail.ratingDistribution[0]).isEqualTo(80.0)
        // 首播日期
        assertThat(detail.initialReleaseDates).hasSize(2)
        assertThat(detail.initialReleaseDates[0]).isEqualTo("1994-09-10(多伦多电影节)")
        // 演职员
        assertThat(detail.celebrities).hasSize(2)
        assertThat(detail.celebrities[0].name).isEqualTo("弗兰克·德拉邦特")
        assertThat(detail.celebrities[0].role).isEqualTo("导演")
        assertThat(detail.celebrities[1].name).isEqualTo("蒂姆·罗宾斯")
        assertThat(detail.celebrities[1].role).isEqualTo("饰 安迪·杜佛兰")
    }

    // ==================== parseDetail (TV) ====================

    @Test
    fun parseDetail_tvHtml_returnsDetailWithEpisodes() {
        val html = loadFixture("detail_tv.html")
        val detail = DoubanSpider.parseDetail(html)

        assertThat(detail.title).isEqualTo("某某韩剧")
        assertThat(detail.imdbId).isEqualTo("tt12345678")
        assertThat(detail.year).isEqualTo("2026")
        assertThat(detail.isTvShow).isTrue()
        assertThat(detail.episodeCount).isEqualTo(12)
        assertThat(detail.episodeDuration).isEqualTo("45分钟")
        assertThat(detail.countries).containsExactly("韩国")
        assertThat(detail.languages).containsExactly("韩语")
        assertThat(detail.doubanRating).isEqualTo(8.5)
        assertThat(detail.ratingCount).isEqualTo(50000)
        assertThat(detail.aka).containsExactly("某某剧")
        // 电影字段应为 null
        assertThat(detail.runtime).isNull()
    }

    // ==================== parseDetail (malformed) ====================

    @Test
    fun parseDetail_malformedHtml_doesNotCrash() {
        val html = "<html><body>残缺内容</body></html>"
        val detail = DoubanSpider.parseDetail(html)
        // 不崩溃即通过，字段应为默认/空值
        assertThat(detail.genres).isEmpty()
        assertThat(detail.imdbId).isNull()
        assertThat(detail.title).isNull()
        assertThat(detail.isTvShow).isFalse()
    }

    // ==================== parseSearchByImdb ====================

    @Test
    fun parseSearchByImdb_searchResultHtml_returnsItems() {
        val html = loadFixture("search_by_imdb.html")
        val items = DoubanSpider.parseSearchByImdb(html)
        assertThat(items).hasSize(2)

        val first = items[0]
        assertThat(first.doubanId).isEqualTo("37090502")
        assertThat(first.title).isEqualTo("某部电影")
        assertThat(first.doubanUrl).contains("subject/37090502")
        assertThat(first.posterUrl).isEqualTo("https://img.example.com/search_poster1.jpg")
        assertThat(first.rating).isEqualTo(8.0)

        val second = items[1]
        assertThat(second.doubanId).isEqualTo("37090503")
        assertThat(second.title).isEqualTo("另一部电影")
        assertThat(second.rating).isEqualTo(9.5)
    }
}
