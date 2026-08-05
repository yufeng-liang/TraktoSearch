package com.tracktosearch.ui.navigation

import com.tracktosearch.data.repository.WatchlistMediaType

/**
 * 所有缺少 IMDb 的豆瓣条目使用豆瓣详情页。
 * 未知媒体类型不能静默按电影处理，否则综艺、纪录片会请求错误的详情链路。
 */
internal fun shouldUseDoubanItemDetail(
    doubanId: String?,
    imdbId: String,
    mediaType: WatchlistMediaType,
    traktId: Int,
    tmdbId: Int
): Boolean = !doubanId.isNullOrBlank() &&
    (mediaType == WatchlistMediaType.OTHER || imdbId.isBlank())
