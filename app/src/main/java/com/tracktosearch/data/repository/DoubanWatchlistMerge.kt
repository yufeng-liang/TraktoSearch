package com.tracktosearch.data.repository

import java.time.Instant

/** Watchlist 后续适配所需的媒体分类。 */
enum class WatchlistMediaType {
    MOVIE,
    SHOW,
    OTHER
}

/** 豆瓣标记状态。 */
enum class DoubanWatchlistStatus {
    WISH,
    COLLECT
}

/** 合并条目的来源关系。 */
enum class WatchlistEntryOrigin {
    TRAKT_ONLY,
    DOUBAN_ONLY,
    MATCHED
}

/** 一个条目在 watchlist 与 watched 两个维度上的状态。 */
data class WatchlistState(
    val inWatchlist: Boolean,
    val watched: Boolean
)

/** 供后续适配层传入的 Trakt 条目，不依赖 Trakt DTO 或 Android。 */
data class TraktWatchlistRecord(
    val traktId: Int? = null,
    val title: String = "",
    val mediaType: String? = null,
    val imdbId: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val posterUrl: String? = null,
    val listedAt: String? = null,
    val inWatchlist: Boolean = true,
    val watched: Boolean = false
)

/** 供后续适配层传入的豆瓣条目，不依赖 Room、豆瓣 DTO 或 Android。 */
data class DoubanWatchlistRecord(
    val doubanId: String,
    val title: String = "",
    val displayTitle: String? = null,
    val mediaType: String? = null,
    val status: DoubanWatchlistStatus = DoubanWatchlistStatus.WISH,
    val traktId: Int? = null,
    val imdbId: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val posterUrl: String? = null,
    val listedAt: String? = null
)

/** 并集结果，同时保留来源原始模型以便后续适配层继续取补充字段。 */
data class MergedWatchlistItem(
    val origin: WatchlistEntryOrigin,
    val mediaType: WatchlistMediaType,
    val title: String,
    val displayTitle: String,
    val year: Int?,
    val genres: List<String>,
    val posterUrl: String?,
    val imdbId: String?,
    val traktId: Int?,
    val doubanId: String?,
    val listedAt: String?,
    val state: WatchlistState,
    val trakt: TraktWatchlistRecord?,
    val douban: DoubanWatchlistRecord?
)

/** movie/show 保留原分类，其余值（包括空值）统一归为 OTHER。 */
fun mapDoubanMediaType(mediaType: String?): WatchlistMediaType = when (mediaType?.trim()?.lowercase()) {
    "movie" -> WatchlistMediaType.MOVIE
    "show" -> WatchlistMediaType.SHOW
    else -> WatchlistMediaType.OTHER
}

/** IMDb 只用于非空条目匹配；空值不会产生共享匹配键。 */
fun normalizeWatchlistImdbId(imdbId: String?): String? =
    imdbId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

/**
 * 同一豆瓣记录只保留一条：collect 永远优先于 wish，同状态时取最新 listedAt。
 * 不同 doubanId 即使共享 IMDb 也保留为独立条目，确保 Watchlist 不丢失任何豆瓣记录。
 */
fun coalesceDoubanWatchlistEntries(
    entries: List<DoubanWatchlistRecord>
): List<DoubanWatchlistRecord> {
    val byDoubanId = mutableListOf<DoubanWatchlistRecord>()
    val doubanIndexes = mutableMapOf<String, Int>()
    entries.forEach { entry ->
        val key = entry.doubanId.trim()
        val existingIndex = key.takeIf { it.isNotEmpty() }?.let(doubanIndexes::get)
        if (existingIndex == null) {
            if (key.isNotEmpty()) doubanIndexes[key] = byDoubanId.size
            byDoubanId += entry
        } else if (preferDoubanEntry(entry, byDoubanId[existingIndex])) {
            byDoubanId[existingIndex] = entry
        }
    }

    return byDoubanId
}

/**
 * 合并 Trakt 与豆瓣 watchlist。结果按最新 listedAt 降序排列，时间相同或缺失时保持输入顺序。
 * 匹配只使用双方都存在的规范化 IMDb，标题、年份等字段不会触发错误去重。
 *
 * 匹配用 traktId / 规范化 IMDb 两张倒排索引（值为按原顺序排列的下标队列），
 * 复杂度从「每条 Trakt 条目全量扫描豆瓣列表」的 O(n×m) 降到 O(n+m)：
 * 队列头即原实现的"第一个未匹配项"，已被另一张索引消费掉的下标出队时跳过。
 * 排序键（trim 后字符串 + 解析好的 Instant）每条只算一次，避免比较器里重复 Instant.parse。
 */
fun mergeTraktAndDoubanWatchlist(
    traktEntries: List<TraktWatchlistRecord> = emptyList(),
    doubanEntries: List<DoubanWatchlistRecord> = emptyList()
): List<MergedWatchlistItem> {
    val trakt = coalesceTraktWatchlistEntries(traktEntries)
    val douban = coalesceDoubanWatchlistEntries(doubanEntries)
    val matchedDoubanIndexes = HashSet<Int>(douban.size * 2)
    val merged = ArrayList<MergedWatchlistItem>(trakt.size + douban.size)

    // 倒排索引：traktId / 规范化 IMDb → 豆瓣条目下标队列（保持原列表顺序）
    val doubanByTraktId = HashMap<Int, ArrayDeque<Int>>()
    val doubanByImdbId = HashMap<String, ArrayDeque<Int>>()
    douban.forEachIndexed { index, entry ->
        entry.traktId?.let { doubanByTraktId.getOrPut(it) { ArrayDeque() }.addLast(index) }
        normalizeWatchlistImdbId(entry.imdbId)?.let {
            doubanByImdbId.getOrPut(it) { ArrayDeque() }.addLast(index)
        }
    }

    /** 取出该键下第一个尚未被匹配的下标；已匹配的下标直接丢弃（不会再被任何键复用）。 */
    fun takeUnmatched(queue: ArrayDeque<Int>?): Int? {
        if (queue == null) return null
        while (queue.isNotEmpty()) {
            val candidate = queue.removeFirst()
            if (candidate !in matchedDoubanIndexes) return candidate
        }
        return null
    }

    trakt.forEach { traktEntry ->
        val traktImdb = normalizeWatchlistImdbId(traktEntry.imdbId)
        // IMDb 可能缺失或被 Trakt 返回为空，优先用本地快照保留的 traktId 合并。
        val doubanIndex = traktEntry.traktId?.let { takeUnmatched(doubanByTraktId[it]) }
            ?: traktImdb?.let { takeUnmatched(doubanByImdbId[it]) }

        if (doubanIndex != null) {
            matchedDoubanIndexes += doubanIndex
            merged += buildMergedItem(traktEntry, douban[doubanIndex])
        } else {
            merged += buildMergedItem(traktEntry, null)
        }
    }

    douban.forEachIndexed { index, doubanEntry ->
        if (index !in matchedDoubanIndexes) {
            merged += buildMergedItem(null, doubanEntry)
        }
    }

    return sortByListedAtDescending(merged)
}

/** listedAt 排序键：trim 后原串 + 预解析 Instant，避免比较器里重复解析。 */
private class ListedAtSortKey(val text: String, val instant: Instant?)

private fun listedAtSortKey(listedAt: String?): ListedAtSortKey {
    val text = listedAt?.trim().orEmpty()
    val instant = if (text.isEmpty()) null else runCatching { Instant.parse(text) }.getOrNull()
    return ListedAtSortKey(text, instant)
}

/**
 * 按 listedAt 降序稳定排序（时间相同或缺失时保持输入顺序）。
 * 比较语义与 [compareListedAt] 完全一致，只是把解析结果提前算好复用。
 */
private fun sortByListedAtDescending(items: List<MergedWatchlistItem>): List<MergedWatchlistItem> {
    if (items.size <= 1) return items
    val keyed = items.map { it to listedAtSortKey(it.listedAt) }
    return keyed.sortedWith { first, second ->
        compareSortKeys(second.second, first.second)
    }.map { it.first }
}

private fun compareSortKeys(first: ListedAtSortKey, second: ListedAtSortKey): Int {
    if (first.text.isEmpty() || second.text.isEmpty()) {
        return first.text.length.compareTo(second.text.length)
    }
    return if (first.instant != null && second.instant != null) {
        first.instant.compareTo(second.instant)
    } else {
        first.text.compareTo(second.text)
    }
}

private fun coalesceTraktWatchlistEntries(
    entries: List<TraktWatchlistRecord>
): List<TraktWatchlistRecord> {
    val result = mutableListOf<TraktWatchlistRecord>()
    val imdbIndexes = mutableMapOf<String, Int>()
    entries.forEach { entry ->
        val key = normalizeWatchlistImdbId(entry.imdbId)
        val existingIndex = key?.let(imdbIndexes::get)
        if (existingIndex == null) {
            if (key != null) imdbIndexes[key] = result.size
            result += entry
        } else if (compareListedAt(entry.listedAt, result[existingIndex].listedAt) > 0) {
            result[existingIndex] = entry
        }
    }
    return result
}

private fun preferDoubanEntry(
    candidate: DoubanWatchlistRecord,
    existing: DoubanWatchlistRecord
): Boolean {
    if (candidate.status != existing.status) {
        return candidate.status == DoubanWatchlistStatus.COLLECT
    }
    return compareListedAt(candidate.listedAt, existing.listedAt) > 0
}

private fun buildMergedItem(
    trakt: TraktWatchlistRecord?,
    douban: DoubanWatchlistRecord?
): MergedWatchlistItem {
    val title = trakt?.title.takeIf(::hasText)
        ?: douban?.displayTitle.takeIf(::hasText)
        ?: douban?.title.orEmpty()
    val displayTitle = douban?.displayTitle.takeIf(::hasText) ?: title
    val state = WatchlistState(
        inWatchlist = trakt?.inWatchlist == true || douban?.status == DoubanWatchlistStatus.WISH,
        watched = trakt?.watched == true || douban?.status == DoubanWatchlistStatus.COLLECT
    )

    return MergedWatchlistItem(
        origin = when {
            trakt != null && douban != null -> WatchlistEntryOrigin.MATCHED
            trakt != null -> WatchlistEntryOrigin.TRAKT_ONLY
            else -> WatchlistEntryOrigin.DOUBAN_ONLY
        },
        mediaType = mapDoubanMediaType(trakt?.mediaType ?: douban?.mediaType),
        title = title,
        displayTitle = displayTitle,
        year = trakt?.year ?: douban?.year,
        genres = trakt?.genres?.takeIf { it.isNotEmpty() } ?: douban?.genres.orEmpty(),
        posterUrl = trakt?.posterUrl.takeIf(::hasText) ?: douban?.posterUrl,
        imdbId = trakt?.imdbId?.trim().takeIf(::hasText) ?: douban?.imdbId?.trim().takeIf(::hasText),
        traktId = trakt?.traktId ?: douban?.traktId,
        doubanId = douban?.doubanId,
        listedAt = latestListedAt(trakt?.listedAt, douban?.listedAt),
        state = state,
        trakt = trakt,
        douban = douban
    )
}

private fun hasText(value: String?): Boolean = !value.isNullOrBlank()

private fun latestListedAt(first: String?, second: String?): String? = when {
    first == null -> second
    second == null -> first
    compareListedAt(second, first) > 0 -> second
    else -> first
}

private fun compareListedAt(first: String?, second: String?): Int {
    val firstValue = first?.trim().orEmpty()
    val secondValue = second?.trim().orEmpty()
    if (firstValue.isEmpty() || secondValue.isEmpty()) {
        return firstValue.length.compareTo(secondValue.length)
    }

    val firstInstant = runCatching { Instant.parse(firstValue) }.getOrNull()
    val secondInstant = runCatching { Instant.parse(secondValue) }.getOrNull()
    return if (firstInstant != null && secondInstant != null) {
        firstInstant.compareTo(secondInstant)
    } else {
        firstValue.compareTo(secondValue)
    }
}
