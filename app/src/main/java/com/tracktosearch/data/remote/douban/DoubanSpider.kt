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
    val isTvShow: Boolean,        // 类型含「电视剧」即为 true
    val genres: List<String>,
    val year: String?,           // 上映年份（4 位数字字符串）
    val countries: List<String>, // 制片国家/地区
    val directors: List<String>  // 导演
)

/** 列表页解析结果（条目列表 + 总条目数，总数解析失败时为 null） */
data class DoubanMarkListPage(
    val items: List<DoubanMarkItem>,
    val totalCount: Int?
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

    /** 解析详情页 HTML，提取 imdbId、类型、年份、国家、导演 */
    fun parseDetail(html: String): DoubanDetailInfo {
        val doc: Document = Jsoup.parse(html)
        val genres = doc.select("span[property=v:genre]").map { it.text() }
        val isTvShow = genres.any { it.contains("电视剧") || it.contains("综艺") }

        // IMDb ID 在 <span class="pl">IMDb:</span> 后面的文本节点
        val imdbId = doc.select("span.pl").firstOrNull { it.text().contains("IMDb") }
            ?.nextSibling()?.toString()?.trim()?.takeIf { it.startsWith("tt") }

        // 上映年份：优先 <span class="year">(2023)</span>，其次 h1 标题里的 (YYYY)
        val year = doc.selectFirst("span.year")?.text()
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.getOrNull(1) }
            ?: doc.selectFirst("h1")?.text()
                ?.let { Regex("""\((\d{4})\)""").find(it)?.groupValues?.getOrNull(1) }

        // 制片国家/地区、导演：从 #info 区块里「<span class="pl">字段名:</span>」后面提取
        val infoEl = doc.selectFirst("#info")
        val countries = parseInfoField(infoEl, "制片国家/地区")
            ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val directors = parseInfoField(infoEl, "导演")
            ?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

        return DoubanDetailInfo(imdbId, isTvShow, genres, year, countries, directors)
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
}
