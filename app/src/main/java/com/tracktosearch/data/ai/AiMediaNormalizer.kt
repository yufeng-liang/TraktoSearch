package com.tracktosearch.data.ai

/**
 * 统一外部影视身份。
 *
 * TMDB 是首选身份；没有 TMDB 时依次使用 IMDb、Trakt、豆瓣的可靠来源 ID。
 * 标题永远只是快照，不能在缺少可靠 ID 时生成主键。
 */
object AiMediaNormalizer {

    fun normalize(snapshot: MediaSourceSnapshot): NormalizedMedia {
        val mediaType = snapshot.mediaType.trim().lowercase().ifBlank {
            throw IllegalArgumentException("mediaType must not be blank")
        }
        val key = mediaKey(
            mediaType = mediaType,
            tmdbId = snapshot.tmdbId,
            traktId = snapshot.traktId,
            imdbId = snapshot.imdbId,
            doubanId = snapshot.doubanId
        )
        return NormalizedMedia(
            mediaKey = key,
            mediaType = mediaType,
            tmdbId = snapshot.tmdbId,
            traktId = snapshot.traktId,
            imdbId = cleanId(snapshot.imdbId),
            doubanId = cleanId(snapshot.doubanId),
            title = snapshot.title,
            year = snapshot.year,
            genres = snapshot.genres.filter { it.isNotBlank() }.map(String::trim).distinct(),
            publicRating = snapshot.publicRating,
            userRating = snapshot.userRating,
            userComment = snapshot.userComment,
            watchedAt = snapshot.watchedAt,
            isWatched = snapshot.isWatched,
            isWatchlist = snapshot.isWatchlist
        )
    }

    fun mediaKey(
        mediaType: String,
        tmdbId: Int? = null,
        traktId: Int? = null,
        imdbId: String? = null,
        doubanId: String? = null
    ): String {
        val normalizedType = mediaType.trim().lowercase().ifBlank {
            throw IllegalArgumentException("mediaType must not be blank")
        }
        val normalizedTmdbId = tmdbId?.takeIf { it > 0 }
        if (normalizedTmdbId != null) return "$normalizedType:$normalizedTmdbId"

        cleanId(imdbId)?.takeIf { it.isNotBlank() }?.let {
            return "$normalizedType:imdb:$it"
        }
        traktId?.takeIf { it > 0 }?.let {
            return "$normalizedType:trakt:$it"
        }
        cleanId(doubanId)?.takeIf { it.isNotBlank() }?.let {
            return "$normalizedType:douban:$it"
        }
        throw IllegalArgumentException("a reliable media id is required")
    }

    private fun cleanId(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}

fun normalizeMedia(snapshot: MediaSourceSnapshot): NormalizedMedia =
    AiMediaNormalizer.normalize(snapshot)

fun mediaKeyFor(
    mediaType: String,
    tmdbId: Int? = null,
    traktId: Int? = null,
    imdbId: String? = null,
    doubanId: String? = null
): String = AiMediaNormalizer.mediaKey(mediaType, tmdbId, traktId, imdbId, doubanId)
