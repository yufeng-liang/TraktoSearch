package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.local.DoubanUserProfile
import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 豆瓣详情页解析结果的持久化缓存条目。
 *
 * 包含 Notion 备份所需的所有字段（条目标题/封面/标记时间/标记状态/短评/评分/豆瓣链接
 * 由 DoubanMarkItem 承载，这里保存详情页补充信息）。doubanId → 这些字段不会变，永久缓存。
 */
@Serializable
data class DoubanDetailCacheEntry(
    val imdbId: String?,
    val isTvShow: Boolean,
    val genres: List<String> = emptyList(),
    val year: String? = null,
    val countries: List<String> = emptyList(),
    val directors: List<String> = emptyList()
)

/**
 * 豆瓣爬取协调器。
 *
 * 职责：
 * - 分页爬取「想看/看过」标记列表
 * - 爬取条目详情页补全 imdbId（带持久化缓存，避免重复爬取）
 * - 反爬延迟（列表页 5-10 秒，详情页 3-5 秒）
 *
 * 使用独立的 OkHttpClient（不走 Trakt 拦截器），设置豆瓣所需的 Cookie/UA/Referer。
 */
class DoubanRepository(
    private val detailCache: PersistentTtlCache<DoubanDetailCacheEntry>
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 爬取指定状态的标记列表，回调每页结果。
     *
     * @param userId 豆瓣用户 ID（从 dbcl2 cookie 解析）
     * @param cookie 完整 Cookie 字符串
     * @param status 想看 / 看过
     * @param onPage 每页结果回调
     * @param onProgress 进度回调（已处理条目数，总数）
     * @param isCancelled 取消检查回调,返回 true 时立即停止爬取(每页爬完检查一次)
     * @return true=正常爬完或无更多条目；false=Cookie 过期中断
     */
    suspend fun fetchMarkList(
        userId: String,
        cookie: String,
        status: DoubanMarkStatus,
        onPage: suspend (List<DoubanMarkItem>, pageIndex: Int) -> Unit,
        onProgress: (current: Int, total: Int?) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        var start = 0
        var pageIndex = 1
        var cookieExpired = false
        var knownTotal: Int? = null

        while (true) {
            // 每页爬取前检查取消标志,避免取消后还要爬完当前页(5-10 秒反爬延迟)
            if (isCancelled()) break
            val url = "https://movie.douban.com/people/${userId}/${status.path}?start=${start}&sort=time&mode=grid"
            val html = fetchHtml(url, cookie)
            if (DoubanSpider.isLoginPage(html)) {
                cookieExpired = true
                break
            }
            val page = DoubanSpider.parseMarkListPage(html)
            if (page.items.isEmpty()) break

            // 第一页拿到总数后贯穿整个爬取过程
            if (knownTotal == null && page.totalCount != null && page.totalCount > 0) {
                knownTotal = page.totalCount
            }

            onPage(page.items, pageIndex)
            onProgress(start + page.items.size, knownTotal)
            start += 15
            pageIndex++
            // 反爬延迟前再检查一次,取消后不再等待
            if (isCancelled()) break
            // 反爬延迟：每页间 5-10 秒
            delay(Random.nextLong(5000, 10000))
        }
        return !cookieExpired
    }

    /**
     * 爬取条目详情页,返回 imdbId 等补充信息。null 表示 Cookie 过期或失败。
     * 优先查持久化缓存(doubanId → imdbId/isTvShow 永久不变),未命中才爬取详情页。
     * 首次失败时自动重试一次(豆瓣偶发 403/超时)。
     *
     * @param onProgress 进度回调:
     *   - phase="start": 开始处理此条目(title 参数为标题)
     *   - phase="cache_hit": 缓存命中,跳过爬取
     *   - phase="fetching": 正在爬取详情页
     *   - phase="done": 此条目处理完成
     *   - phase="failed": 此条目处理失败
     * @return Pair<详情, 是否命中缓存>,详情为 null 表示失败
     */
    suspend fun fetchDetail(
        doubanUrl: String,
        cookie: String,
        title: String? = null,
        onProgress: (phase: String, title: String?) -> Unit = { _, _ -> }
    ): Pair<DoubanDetailInfo?, Boolean> {
        // 从 URL 解析 doubanId 作为缓存 key
        val doubanId = Regex("""subject/(\d+)""").find(doubanUrl)?.groupValues?.get(1) ?: doubanUrl

        // 优先查缓存(命中则跳过详情页爬取,省 3-5 秒反爬延迟)
        detailCache.get(doubanId)?.let { entry ->
            val info = DoubanDetailInfo(
                imdbId = entry.imdbId,
                isTvShow = entry.isTvShow,
                genres = entry.genres,
                year = entry.year,
                countries = entry.countries,
                directors = entry.directors
            )
            onProgress("cache_hit", title)
            return Pair(info, true) // 命中缓存
        }
        // 缓存未命中时等待磁盘加载完成再查一次,避免 loadFromDisk 未完成时误判为缓存未命中
        // 导致不必要的 3-5 秒反爬延迟与详情页爬取
        detailCache.awaitLoaded()
        detailCache.get(doubanId)?.let { entry ->
            val info = DoubanDetailInfo(
                imdbId = entry.imdbId,
                isTvShow = entry.isTvShow,
                genres = entry.genres,
                year = entry.year,
                countries = entry.countries,
                directors = entry.directors
            )
            onProgress("cache_hit", title)
            return Pair(info, true) // 命中缓存
        }

        // 未命中缓存，爬取详情页（带一次重试）
        onProgress("fetching", title)
        var lastHtml: String? = null
        for (attempt in 0..1) {
            if (attempt > 0) {
                delay(Random.nextLong(2000, 4000)) // 重试前等待
            }
            delay(Random.nextLong(3000, 5000)) // 反爬延迟 3-5 秒
            val html = fetchHtml(doubanUrl, cookie)
            if (DoubanSpider.isLoginPage(html)) return Pair(null, false) // Cookie 过期，不重试
            lastHtml = html
            // 解析成功就跳出
            val parsed = DoubanSpider.parseDetail(html)
            if (parsed.imdbId != null) {
                // 写入持久化缓存（永久，下次再导入同一部影片直接命中；保留所有字段）
                detailCache.put(
                    doubanId,
                    DoubanDetailCacheEntry(
                        imdbId = parsed.imdbId,
                        isTvShow = parsed.isTvShow,
                        genres = parsed.genres,
                        year = parsed.year,
                        countries = parsed.countries,
                        directors = parsed.directors
                    )
                )
                onProgress("done", title)
                return Pair(parsed, false)
            }
            // imdbId 为空可能是页面结构变化或加载不全，重试一次
        }

        // 两次都失败，返回最后一次解析结果（可能 imdbId 为 null）
        onProgress("failed", title)
        return Pair(lastHtml?.let { DoubanSpider.parseDetail(it) }, false)
    }

    private suspend fun fetchHtml(url: String, cookie: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/")
            .build()
        client.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    }

    /**
     * 抓取豆瓣用户主页,解析头像、昵称、ID。
     *
     * 头像解析策略(从准到粗,任一命中即用):
     *   1. URL 中含 `u{userId}` 的图片(豆瓣用户头像 URL 模式 https://img.doubaocdn.com/u{userId}-*.jpg)
     *   2. class/alt 含 "user"/"face"/"avatar" 的 <img>
     *   3. og:image meta(排除指向豆瓣主站 logo / brand 的 URL)
     *
     * 昵称解析:og:title(格式「XXX的豆瓣主页」)→ <title> → 截断「的豆瓣...」后缀。
     *
     * @return DoubanUserProfile 或 null(网络失败 / Cookie 过期 / 解析失败)
     */
    suspend fun fetchUserProfile(userId: String, cookie: String): DoubanUserProfile? = withContext(Dispatchers.IO) {
        try {
            // PC 版用户主页:og:image 通常就是用户头像,且页面结构稳定
            val url = "https://www.douban.com/people/$userId/"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Cookie", cookie)
                .header("Referer", "https://www.douban.com/")
                .build()
            val html = client.newCall(request).execute().use { response ->
                response.body?.string() ?: return@withContext null
            }
            // Cookie 过期或被风控时通常重定向到登录页
            if (DoubanSpider.isLoginPage(html)) return@withContext null

            val doc: Document = Jsoup.parse(html)
            val avatarUrl = extractUserAvatar(doc, userId)
            // 昵称：优先 og:title（格式通常是「XXX的豆瓣主页」），其次 <title>，截断到「的」之前
            val nickname = (extractOgTitle(html) ?: extractTitle(html))?.let { cleanDoubanNick(it) }
            DoubanUserProfile(
                userId = userId,
                nickname = nickname,
                avatarUrl = avatarUrl
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 多策略提取用户头像:
     *   1. <img> 的 src 中含 `u{userId}` 路径段(豆瓣用户头像 URL 模式)
     *   2. <img> 的 class 含 "user-face"/"userface"/"avatar" 或 alt 非空且非"豆瓣"
     *   3. og:image meta(排除指向主站 brand/pics 的 logo)
     */
    private fun extractUserAvatar(doc: Document, userId: String): String? {
        // 策略 1:src 含 `u{userId}` 的图片(最准,豆瓣用户头像固定 URL 模式)
        val userAvatarImgRegex = Regex("""u${Regex.escape(userId)}[-\w]*\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
        for (img in doc.select("img")) {
            val src = img.absUrl("src").ifEmpty { img.attr("src") }
            if (src.isNotBlank() && userAvatarImgRegex.containsMatchIn(src)) {
                return src
            }
        }
        // 策略 2:class / alt 匹配用户头像
        val avatarLike = doc.select("img").firstOrNull { el ->
            val cls = el.className().lowercase()
            val alt = el.attr("alt").lowercase()
            (cls.contains("user-face") || cls.contains("userface") || cls.contains("avatar") || cls.contains("pic"))
                && !alt.equals("豆瓣", ignoreCase = true)
                && !alt.contains("logo")
        }
        avatarLike?.let {
            val src = it.absUrl("src").ifEmpty { it.attr("src") }
            if (src.isNotBlank()) return src
        }
        // 策略 3:og:image(排除主站 logo / brand 路径)
        val og = extractOgImage(doc.outerHtml())
        if (og != null && !og.contains("/brand/", ignoreCase = true) && !og.contains("pics", ignoreCase = true)) {
            return og
        }
        return null
    }

    /** 从 og:image meta 提取头像 URL */
    private fun extractOgImage(html: String): String? {
        val regex = Regex("""<meta\s+property\s*=\s*["']og:image["']\s+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 从 og:title meta 提取标题文本 */
    private fun extractOgTitle(html: String): String? {
        val regex = Regex("""<meta\s+property\s*=\s*["']og:title["']\s+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /** 从 <title>...</title> 提取标题文本 */
    private fun extractTitle(html: String): String? {
        val regex = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 清理豆瓣页面标题中的「的豆瓣」「| 豆瓣」等后缀，得到纯昵称 */
    private fun cleanDoubanNick(raw: String): String {
        var s = raw.trim()
        // 移除常见后缀：「的豆瓣」「的豆瓣主页」「| 豆瓣」「- 豆瓣」
        s = s.replace(Regex("""\s*[|｜\-]\s*豆瓣.*"""), "")
        s = s.replace(Regex("""的豆瓣(主页)?.*$"""), "")
        return s.trim().ifBlank { raw }
    }
}

/** 豆瓣标记状态 */
enum class DoubanMarkStatus(val path: String) {
    WISH("wish"),       // 想看
    COLLECT("collect");  // 看过

    companion object {
        /** 从字符串反序列化(容错:未知值降级为 WISH) */
        fun fromString(value: String?): DoubanMarkStatus =
            entries.firstOrNull { it.path == value || it.name == value } ?: WISH
    }
}
