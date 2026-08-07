package com.tracktosearch.ui.navigation

import com.tracktosearch.data.repository.WatchlistMediaType

/**
 * 已知电影/剧集统一进入普通详情页，让 DetailViewModel 复用 Rexxar 与现有备用数据链路。
 * 只有媒体类型未知时才进入豆瓣专属详情页，避免把综艺、纪录片按电影或剧集请求。
 */
internal fun shouldUseDoubanItemDetail(
    doubanId: String?,
    imdbId: String,
    mediaType: WatchlistMediaType,
    traktId: Int,
    tmdbId: Int
): Boolean = !doubanId.isNullOrBlank() && mediaType == WatchlistMediaType.OTHER
