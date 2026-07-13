package com.tracktosearch.data.remote.douban

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** 豆瓣标记列表中解析出的单条条目（未含 imdbId，需详情页爬取补全） */
data class DoubanMarkItem(
    val doubanId: String,        // 从链接 /subject/123456/ 解析
    val title: String,
    val rating: Int?,            // 1-5，null 表示未评分
    val comment: String?,        // 短评
    val markedAt: String,        // 标记时间 yyyy-MM-dd
    val doubanUrl: String,
    val posterUrl: String?
)

/** 详情页补充信息（含条目元数据，参考 Notion 备份字段清单） */
data class DoubanDetailInfo(
    val imdbId: String?,
    val isTvShow: Boolean,        // 集数不为空即电视剧
    val title: String? = null,           // 条目标题(og:title)
    val posterUrl: String? = null,       // 海报地址(og:image)
    val genres: List<String>,
    val year: String?,           // 上映年份（4 位数字字符串）
    val countries: List<String>, // 制片国家/地区
    val directors: List<String>,  // 导演
    // 扩展字段（豆瓣条目页额外提取，用于详情页展示与集数自动分类）
    val doubanRating: Double? = null,      // 豆瓣评分（10 分制，如 9.2；null 表示暂无评分）
    val ratingCount: Int? = null,          // 评分人数
    val summary: String? = null,           // 剧情简介
    val episodeCount: Int? = null,         // 集数（电视剧才有）
    val episodeDuration: String? = null,   // 单集片长（电视剧才有，如"45分钟"）
    val aka: List<String> = emptyList(),   // 又名/译名
    val runtime: String? = null,           // 片长（电影才有，如"120分钟"）
    val writers: List<String> = emptyList(),   // 编剧
    val cast: List<String> = emptyList(),     // 主演名列表
    val languages: List<String> = emptyList(), // 语言
    val initialReleaseDates: List<String> = emptyList(),  // 首播日期(可能多个,如"2026-04-18(韩国)")
    val ratingDistribution: List<Double> = emptyList(),   // 评分分布 5星→1星百分比
    val celebrities: List<DoubanCelebrity> = emptyList()  // 演职员(导演/编剧/主演,含头像和角色)
)

/** 豆瓣演职员条目(导演/编剧/主演统一结构) */
data class DoubanCelebrity(
    val name: String,               // 中文名
    val doubanPersonageUrl: String?, // 豆瓣 personage 链接
    val avatarUrl: String?,         // 头像 URL
    val role: String?               // 角色,如"导演" / "饰 黄东万" / null
)

/** 列表页解析结果（条目列表 + 总条目数，总数解析失败时为 null） */
data class DoubanMarkListPage(
    val items: List<DoubanMarkItem>,
    val totalCount: Int?
)

/**
 * 豆瓣移动端搜索页（m.douban.com/search/?query={imdbId}）解析出的单条影视结果。
 *
 * @param doubanId 豆瓣条目 ID（从 /movie/subject/{id}/ 链接解析）
 * @param title 条目标题
 * @param doubanUrl 豆瓣条目相对链接（如 /movie/subject/37090502/）
 * @param posterUrl 海报 URL（可能为空）
 * @param rating 豆瓣评分（10 分制，如 8.0；null 表示暂无评分）
 */
data class DoubanSearchResultItem(
    val doubanId: String,
    val title: String,
    val doubanUrl: String,
    val posterUrl: String?,
    val rating: Double?
)

/**
 * 豆瓣 HTML 解析器（基于 Jsoup）。
 *
 * 解析逻辑：
 * - parseMarkList：解析「想看/看过」分页列表 HTML，提取条目基本信息
 * - parseDetail：解析条目详情页 HTML，提取 IMDb ID、年份、国家、导演、类型（用于反查 Trakt + 备份）
 * - isLoginPage：检测 HTML 是否为登录页（Cookie 过期时豆瓣会重定向到登录页）
 */
object DoubanSpider {

    /** 判断 HTML 是否为登录页（Cookie 过期） */
    fun isLoginPage(html: String): Boolean {
        val doc: Document = Jsoup.parse(html)
        return doc.selectFirst("form#lzform") != null || doc.title().contains("登录")
    }

    /**
     * 从详情页 HTML 解析 CSRF 凭证 ck(写接口必需)。
     * PC 详情页内嵌 `<input type="hidden" name="ck" value="...">`，
     * 优先用 Jsoup 取 input[name=ck]，失败再用正则兜底。
     */
    fun parseCsrfToken(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("input[name=ck]")?.attr("value")?.takeIf { it.isNotBlank() }?.let { return it }
        return Regex("""name=["']ck["']\s+value=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 解析单页标记列表 HTML，返回条目列表。空列表表示无更多条目 */
    fun parseMarkList(html: String): List<DoubanMarkItem> = parseMarkListPage(html).items

    /** 解析单页标记列表 HTML，返回条目列表 + 总条目数（用于进度分母） */
    fun parseMarkListPage(html: String): DoubanMarkListPage {
        val doc: Document = Jsoup.parse(html)
        val items = doc.select("div.item").mapNotNull { el ->
            val titleEl = el.selectFirst("em") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            val linkEl = el.selectFirst("a[href]") ?: return@mapNotNull null
            val doubanUrl = linkEl.attr("href")
            val doubanId = Regex("""subject/(\d+)""").find(doubanUrl)?.groupValues?.get(1)
                ?: return@mapNotNull null

            // 评分星级从 class 名解析（如 "rating3-t" 表示 3 星）
            val rating = el.selectFirst("span[class~=rating\\d-t]")?.className()
                ?.let { Regex("rating(\\d)-t").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            val comment = el.selectFirst("span.comment")?.text()?.takeIf { it.isNotEmpty() }
            val markedAt = el.selectFirst("span.date")?.text() ?: ""
            val posterUrl = el.selectFirst("img")?.attr("src")

            DoubanMarkItem(doubanId, title, rating, comment, markedAt, doubanUrl, posterUrl)
        }

        // 解析总条目数：豆瓣列表页通常在标题或分页区显示「共 255 条」/「(共255部)」等
        val totalCount = Regex("""(?:共|全部|总计)\s*[:(（]?\s*(\d+)\s*[)）部条]?\s*(?:条|部)?""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        return DoubanMarkListPage(items, totalCount)
    }

    /** 解析详情页 HTML，提取 imdbId、类型、年份、国家、导演、评分、简介、集数等 */
    fun parseDetail(html: String): DoubanDetailInfo {
        val doc: Document = Jsoup.parse(html)
        val genres = doc.select("span[property=v:genre]").map { it.text() }

        // 条目标题:优先 og:title(纯净),其次 #mainpic 旁的 h1(可能含年份)
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        // 海报:优先 og:image,其次 #mainpic img src
        val posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("#mainpic img")?.absUrl("src")?.takeIf { it.isNotEmpty() }

        // IMDb ID 在 <span class="pl">IMDb:</span> 后面的文本节点（可能含附属文本如"tt39528392（主）"，用正则精确提取）
        val imdbId = doc.select("span.pl").firstOrNull { it.text().contains("IMDb") }
            ?.nextSibling()?.toString()?.trim()
            ?.let { Regex("tt\\d+").find(it)?.value }

        // 上映年份：优先 <span class="year">(2023)</span>，其次 h1 标题里的 (YYYY)
        val year = doc.selectFirst("span.year")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.getOrNull(1) }
            ?: doc.selectFirst("h1")?.text()
                ?.let { Regex("""\((\d{4})\)""").find(it)?.groupValues?.getOrNull(1) }

        // 制片国家/地区、导演、编剧、又名、集数、单集片长、语言：从 #info 区块里「<span class="pl">字段名:</span>」后面提取
        val infoEl = doc.selectFirst("#info")
        val countries = parseInfoField(infoEl, "制片国家/地区")
            ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val directors = parseInfoField(infoEl, "导演")
            ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val writers = parseInfoField(infoEl, "编剧")
            ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val aka = parseInfoField(infoEl, "又名")
            ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val languages = parseInfoField(infoEl, "语言")
            ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        // 集数（电视剧）：从 #info 里「集数:」字段提取数字
        val episodeCount = parseInfoField(infoEl, "集数")
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        // isTvShow:集数不为空即为电视剧
        val isTvShow = episodeCount != null
        // 单集片长（电视剧）：从 #info 里「单集片长:」字段提取（如"45分钟"）
        val episodeDuration = parseInfoField(infoEl, "单集片长")?.trim()?.takeIf { it.isNotEmpty() }

        // 主演:从 #info 里 rel="v:starring" 的 <a> 提取(比纯文本分割更准)
        val cast = doc.select("#info a[rel=v:starring]").map { it.text() }

        // 首播日期:<span property="v:initialReleaseDate" content="2026-04-18(韩国)">,可能多个
        val initialReleaseDates = doc.select("#info span[property=v:initialReleaseDate]")
            .map { it.attr("content").ifBlank { it.text() } }
            .filter { it.isNotEmpty() }

        // 豆瓣评分（10 分制）：<strong class="ll rating_num" property="v:average">9.2</strong>
        val doubanRating = doc.selectFirst("strong.rating_num")?.text()?.trim()
            ?.let { it.toDoubleOrNull()?.takeIf { d -> d in 0.0..10.0 } }
        // 评分人数：<span property="v:votes">12345</span>
        val ratingCount = doc.selectFirst("span[property=v:votes]")?.text()?.trim()?.toIntOrNull()

        // 评分分布:5星→1星百分比,div.ratings-on-weight span.rating_per
        val ratingDistribution = doc.select("div.ratings-on-weight span.rating_per")
            .mapNotNull { it.text().replace("%", "").toDoubleOrNull() }

        // 剧情简介：<span property="v:summary" class="...">...</span>，需清理豆瓣的 <br> 和空白
        val summary = doc.selectFirst("span[property=v:summary]")?.let { el ->
            el.html()
                .replace("<br>", "\n")
                .replace("<br/>", "\n")
                .replace("<br />", "\n")
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        // 片长（电影）：<span property="v:runtime" content="120">120分钟</span>
        val runtime = doc.selectFirst("span[property=v:runtime]")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }

        // 演职员:#celebrities li.celebrity,含头像和角色
        val celebrities = doc.select("#celebrities li.celebrity").mapNotNull { li ->
            val name = li.selectFirst("div.info a.name")?.text() ?: return@mapNotNull null
            val personageUrl = li.selectFirst("a[href*=/personage/]")?.absUrl("href")
            // 头像:div.avatar 的 style="background-image: url(...)"
            val avatarUrl = li.selectFirst("div.avatar")?.attr("style")
                ?.let { Regex("url\\(([^)]+)\\)").find(it)?.groupValues?.getOrNull(1) }
            val role = li.selectFirst("span.role")?.attr("title")?.takeIf { it.isNotBlank() }
            DoubanCelebrity(name, personageUrl, avatarUrl, role)
        }

        return DoubanDetailInfo(
            imdbId = imdbId,
            isTvShow = isTvShow,
            title = title,
            posterUrl = posterUrl,
            genres = genres,
            year = year,
            countries = countries,
            directors = directors,
            doubanRating = doubanRating,
            ratingCount = ratingCount,
            summary = summary,
            episodeCount = episodeCount,
            episodeDuration = episodeDuration,
            aka = aka,
            runtime = runtime,
            writers = writers,
            cast = cast,
            languages = languages,
            initialReleaseDates = initialReleaseDates,
            ratingDistribution = ratingDistribution,
            celebrities = celebrities
        )
    }

    /**
     * 从 #info 区块里查找「<span class="pl">字段名:</span>」后面的文本内容。
     * 字段值可能跨多个兄弟节点（文本节点 + <a> 链接），拼接为单个字符串。
     * 遇到下一个「<span class="pl">」字段标签时停止。
     */
    private fun parseInfoField(infoEl: Element?, fieldName: String): String? {
        if (infoEl == null) return null
        // :contains 匹配 span.pl 文本（如「制片国家/地区:」或「制片国家/地区」）
        val pl = infoEl.select("span.pl").firstOrNull { it.text().contains(fieldName) }
            ?: return null
        val sb = StringBuilder()
        var sibling = pl.nextSibling()
        while (sibling != null) {
            val text = when (sibling) {
                is TextNode -> sibling.text()
                is Element -> {
                    // 遇到下一个字段标签 <span class="pl"> 停止
                    if (sibling.tagName() == "span" && sibling.hasClass("pl")) break
                    sibling.text()
                }
                else -> sibling.toString()
            }
            // 遇到「:」也视为字段分隔符，停止
            if (sibling is TextNode && text.trimEnd().endsWith(":")) {
                break
            }
            sb.append(text)
            sibling = sibling.nextSibling()
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 解析豆瓣移动端搜索页 HTML（m.douban.com/search/?query={imdbId}），提取影视条目列表。
     *
     * 搜索结果位于 `<ul class="search_results_subjects">` 内的 `<li>` 中：
     * - doubanId 从 `<a href="/movie/subject/{id}/">` 链接解析
     * - title 从 `<span class="subject-title">` 解析
     * - posterUrl 从 `<img src="...">` 解析
     * - rating 从 `<span class="rating-stars" data-rating="80.0">` 的 data-rating 属性解析（10 分制 = 值/10）
     *
     * @return 解析出的搜索结果列表（按页面顺序），空列表表示无搜索结果或非搜索结果页
     */
    fun parseSearchByImdb(html: String): List<DoubanSearchResultItem> {
        val doc: Document = Jsoup.parse(html)
        val items = doc.select("ul.search_results_subjects li").mapNotNull { li ->
            val linkEl = li.selectFirst("a[href]") ?: return@mapNotNull null
            val doubanUrl = linkEl.attr("href")
            val doubanId = Regex("""subject/(\d+)""").find(doubanUrl)?.groupValues?.get(1)
                ?: return@mapNotNull null

            val title = li.selectFirst("span.subject-title")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val posterUrl = li.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotEmpty() }

            // data-rating 值如 "80.0" 表示 8.0 分（10 分制 = 值 / 10）
            val rating = li.selectFirst("span.rating-stars")?.attr("data-rating")
                ?.toDoubleOrNull()?.let { it / 10.0 }

            DoubanSearchResultItem(doubanId, title, doubanUrl, posterUrl, rating)
        }
        return items
    }
}
