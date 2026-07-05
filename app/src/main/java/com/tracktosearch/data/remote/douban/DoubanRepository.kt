package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 缓存值：doubanId 对应的 IMDb ID 和类型（电视剧/电影） */
@Serializable
data class DoubanDetailCacheEntry(
    val imdbId: String?,
    val isTvShow: Boolean
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

        // 优先查缓存（命中则跳过详情页爬取，省 3-5 秒反爬延迟）
        detailCache.get(doubanId)?.let { entry ->
            val info = DoubanDetailInfo(
                imdbId = entry.imdbId,
                isTvShow = entry.isTvShow,
                genres = emptyList()
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
                // 写入持久化缓存（永久，下次再导入同一部影片直接命中）
                detailCache.put(doubanId, DoubanDetailCacheEntry(parsed.imdbId, parsed.isTvShow))
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
