package com.tracktosearch.data.remote.tmdb

import com.tracktosearch.BuildConfig

/** TMDB 图片 URL 构建工具，统一管理各尺寸 base url，避免散落硬编码 */
object TmdbImageUrls {
    /**
     * 图片 base URL：默认走网关图片代理（/api/tmdb-image-v2/ 路由，带 CF 边缘缓存）。
     * v2：网关回源请求 WebP（比 JPEG 小 30-50%），缓存 key 与 v1（JPEG）隔离，WebP 立即生效。
     * 国内直连 image.tmdb.org 慢/不稳定，走网关后首次回源缓存、后续命中边缘零回源。
     * 网关地址由 local.properties 的 gateway.base.url 配置，可整体切换。
     */
    private val BASE: String = run {
        val gateway = BuildConfig.GATEWAY_BASE_URL.trimEnd('/')
        "$gateway/api/tmdb-image-v2/t/p"
    }

    val W92: String = "$BASE/w92"
    val W200: String = "$BASE/w200"
    val W342: String = "$BASE/w342"
    val W500: String = "$BASE/w500"
    val W780: String = "$BASE/w780"
    val H632: String = "$BASE/h632"
    val ORIGINAL: String = "$BASE/original"

    /** 拼接完整图片 URL，path 需以 / 开头（TMDB file_path 格式） */
    fun build(path: String, size: String = W342): String = "$size$path"

    /**
     * 将图片 URL 的 TMDB 尺寸段（/t/p/<size>/...）替换为指定尺寸名（如 "w342"、"original"）。
     * 用非贪婪前缀匹配任意主机与网关前缀（/gateway-api/api/tmdb-image/t/p/...），
     * 兼容直连与网关两种形态；非 /t/p/ 结构（如豆瓣图）原样返回。
     */
    fun swapSize(url: String, sizeName: String): String {
        if (url.isBlank()) return url
        val pattern = Regex("^(https?://[^/]+/.*?/t/p/)[^/]+(/.*)$")
        val match = pattern.matchEntire(url)
        return if (match != null) "${match.groupValues[1]}$sizeName${match.groupValues[2]}" else url
    }
}