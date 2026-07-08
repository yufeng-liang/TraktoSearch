package com.tracktosearch.data.remote.tmdb

/** TMDB 图片 URL 构建工具，统一管理各尺寸 base url，避免散落硬编码 */
object TmdbImageUrls {
    private const val BASE = "https://image.tmdb.org/t/p"

    const val W200 = "$BASE/w200"
    const val W500 = "$BASE/w500"
    const val W780 = "$BASE/w780"
    const val H632 = "$BASE/h632"

    /** 拼接完整图片 URL，path 需以 / 开头（TMDB file_path 格式） */
    fun build(path: String, size: String = W500): String = "$size$path"
}
