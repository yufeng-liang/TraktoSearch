package com.tracktosearch.data.remote.douban

import com.tracktosearch.data.remote.douban.dto.DoubanRexxarDetailDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarImageDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarInterestDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarPhotoDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarPhotoPageDto
import com.tracktosearch.data.repository.DoubanPublicDataPoolManager
import com.tracktosearch.data.util.PersistentTtlCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Rexxar 支持的公开媒体类型。 */
@Serializable
enum class DoubanRexxarMediaType(val path: String) {
    MOVIE("movie"),
    TV("tv")
}

@Serializable
data class DoubanRexxarImage(
    val largeUrl: String? = null,
    val normalUrl: String? = null,
    val smallUrl: String? = null
)

@Serializable
data class DoubanRexxarDetail(
    val doubanId: String,
    val title: String? = null,
    val type: DoubanRexxarMediaType,
    val score: Double? = null,
    val ratingCount: Int? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val poster: DoubanRexxarImage? = null,
    val originalTitle: String? = null,
    val countries: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val summary: String? = null,
    val languages: List<String> = emptyList(),
    val initialReleaseDates: List<String> = emptyList(),
    val durations: List<String> = emptyList(),
    val runtime: String? = null,
    val aka: List<String> = emptyList(),
    val imdbId: String? = null
)

@Serializable
data class DoubanRexxarPhoto(
    val id: String,
    val largeUrl: String? = null,
    val normalUrl: String? = null,
    val smallUrl: String? = null,
    val position: Int? = null
)

@Serializable
data class DoubanRexxarPhotoPage(
    val total: Int = 0,
    val start: Int = 0,
    val count: Int = 0,
    val photos: List<DoubanRexxarPhoto> = emptyList()
)

/** 剧照 URL 集合的持久化条目，新的分页结果会按剧照 ID 合并进去。 */
@Serializable
data class DoubanRexxarPhotoCacheEntry(
    val total: Int = 0,
    val photos: List<DoubanRexxarPhoto> = emptyList(),
    val lastFetchedAt: Long = 0L
)

@Serializable
data class DoubanRexxarShortComment(
    val id: String,
    val authorName: String? = null,
    val ratingStars: Int? = null,
    val text: String? = null,
    val createdAt: String? = null,
    /** 点赞数；旧缓存与旧公共池文件没有该字段，缺失即 null。 */
    val voteCount: Int? = null
)

@Serializable
data class DoubanRexxarShortCommentPage(
    val total: Int = 0,
    val start: Int = 0,
    val count: Int = 0,
    val comments: List<DoubanRexxarShortComment> = emptyList()
)

/** Rexxar 请求失败。message 保持英文，供 ViewModel 记录和分类。 */
open class DoubanRexxarException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class DoubanRexxarHttpException(
    val statusCode: Int,
    message: String = "Douban Rexxar request failed with HTTP $statusCode"
) : DoubanRexxarException(message)

class DoubanRexxarNetworkException(
    message: String = "Douban Rexxar network request failed",
    cause: Throwable? = null
) : DoubanRexxarException(message, cause)

class DoubanRexxarResponseException(
    message: String = "Douban Rexxar returned an empty response",
    cause: Throwable? = null
) : DoubanRexxarException(message, cause)

/**
 * 通过 Rexxar 获取公开影视数据。
 *
 * 详情、剧照 URL 集合和短评页分别使用独立的持久化 TTL 缓存；缓存本身保存 expireAt，
 * 因此应用重启后会继续沿用原 TTL，而不会从零开始计时。
 */
class DoubanRexxarRepository(
    private val service: DoubanRexxarApiService,
    private val detailCache: PersistentTtlCache<DoubanRexxarDetail>,
    private val photosCache: PersistentTtlCache<DoubanRexxarPhotoCacheEntry>,
    private val commentsCache: PersistentTtlCache<DoubanRexxarShortCommentPage>,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val publicDataPoolManager: DoubanPublicDataPoolManager? = null
) {
    private val photoLocks = ConcurrentHashMap<String, Mutex>()
    private val publicUploadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    suspend fun getDetail(
        doubanId: String,
        mediaType: DoubanRexxarMediaType,
        forceRefresh: Boolean = false
    ): Result<DoubanRexxarDetail> = try {
        detailCache.awaitLoaded()
        val key = subjectKey(doubanId, mediaType)
        if (!forceRefresh) {
            detailCache.get(key)?.let { return Result.success(it) }
        }
        val detail = detailCache.getOrAwait(key, skipCache = forceRefresh) {
            val publicDetail = if (!forceRefresh) {
                publicDataPoolManager?.getDetail(doubanId, mediaType)
            } else {
                null
            }
            publicDetail ?: requestWithRetry {
                service.getDetail(mediaType.path, doubanId)
            }.let { response ->
                mapDetail(response, doubanId, mediaType).also { freshDetail ->
                    publicDataPoolManager?.let { pool ->
                        publicUploadScope.launch {
                            runCatching { pool.uploadDetail(freshDetail) }
                        }
                    }
                }
            }
        }
        Result.success(detail)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * 获取剧照分页。URL 集合本身永久保留，后续分页按剧照 ID 合并；lastFetchedAt 控制
     * 每 24 小时重新向 Rexxar 查询一次，图片文件仍由现有 Coil 磁盘缓存管理。
     */
    suspend fun getPhotos(
        doubanId: String,
        mediaType: DoubanRexxarMediaType,
        start: Int = 0,
        count: Int = DEFAULT_PAGE_SIZE,
        forceRefresh: Boolean = false
    ): Result<DoubanRexxarPhotoPage> = try {
        requireValidPage(start, count)
        photosCache.awaitLoaded()
        val key = subjectKey(doubanId, mediaType)
        val lock = photoLocks.computeIfAbsent(key) { Mutex() }
        lock.withLock {
            var existing = photosCache.get(key)
            val now = System.currentTimeMillis()
            if (!forceRefresh && existing != null && existing.isFresh(now) && existing.satisfies(start, count)) {
                return@withLock Result.success(existing.toPage(start, count))
            }
            if (!forceRefresh) {
                val publicPhotos = publicDataPoolManager?.getPhotos(doubanId, mediaType)
                if (publicPhotos != null) {
                    // mergePhotoEntries 恒返回非空条目：原先再判一次 null 在 K2 下被判成恒真条件
                    val pooled = mergePhotoEntries(existing, publicPhotos)
                    existing = pooled
                    photosCache.put(key, pooled)
                    if (pooled.isFresh(now) && pooled.satisfies(start, count)) {
                        return@withLock Result.success(pooled.toPage(start, count))
                    }
                }
            }
            val page = requestWithRetry {
                service.getPhotos(mediaType.path, doubanId, start, count)
            }.let(::mapPhotoPage)
            val merged = mergePhotos(
                existing = photosCache.get(key) ?: existing,
                page = page,
                fetchedAt = System.currentTimeMillis()
            )
            photosCache.put(key, merged)
            publicDataPoolManager?.let { pool ->
                publicUploadScope.launch {
                    runCatching { pool.uploadPhotos(doubanId, mediaType, merged) }
                }
            }
            Result.success(page)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getShortComments(
        doubanId: String,
        mediaType: DoubanRexxarMediaType,
        start: Int = 0,
        count: Int = DEFAULT_PAGE_SIZE,
        forceRefresh: Boolean = false
    ): Result<DoubanRexxarShortCommentPage> = try {
        requireValidPage(start, count)
        commentsCache.awaitLoaded()
        val key = pageKey(doubanId, mediaType, start, count)
        if (!forceRefresh) {
            commentsCache.get(key)?.let { return Result.success(it) }
        }
        if (!forceRefresh) {
            val publicComments = publicDataPoolManager?.getComments(doubanId, mediaType, start, count)
            val fetchedAt = publicComments?.fetchedAt ?: 0L
            if (publicComments != null &&
                fetchedAt > 0L &&
                System.currentTimeMillis() - fetchedAt in 0..COMMENTS_TTL_MILLIS
            ) {
                commentsCache.putWithExpireAt(
                    key = key,
                    value = publicComments.page,
                    expireAt = fetchedAt + COMMENTS_TTL_MILLIS
                )
                return Result.success(publicComments.page)
            }
        }
        val page = commentsCache.getOrAwait(key, skipCache = forceRefresh) {
            requestWithRetry {
                service.getInterests(mediaType.path, doubanId, start, count)
            }.let { response ->
                mapComments(response).also { freshPage ->
                    publicDataPoolManager?.let { pool ->
                        publicUploadScope.launch {
                            runCatching {
                                pool.uploadComments(doubanId, mediaType, start, count, freshPage)
                            }
                        }
                    }
                }
            }
        }
        Result.success(page)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun <T> requestWithRetry(
        request: suspend () -> Response<T>
    ): T {
        var retryUsed = false
        while (true) {
            val response = try {
                request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!retryUsed) {
                    retryUsed = true
                    retryDelay(randomRetryDelay())
                    continue
                }
                throw DoubanRexxarNetworkException(cause = e)
            }

            if (response.isSuccessful) {
                return response.body()
                    ?: run {
                        // 2xx 但空 body：关闭响应避免泄漏后抛异常
                        response.closeQuietly()
                        throw DoubanRexxarResponseException()
                    }
            }
            if (response.code() in 500..599 && !retryUsed) {
                retryUsed = true
                // 重试前关闭本次错误响应，避免频繁 5xx 场景连接池被掏空
                response.closeQuietly()
                retryDelay(randomRetryDelay())
                continue
            }
            response.closeQuietly()
            throw DoubanRexxarHttpException(response.code())
        }
    }

    /**
     * 关闭错误响应体释放 OkHttp 连接（非 2xx/重试路径避免连接泄漏）。
     * retrofit2.Response 无 close()，经 raw() 取底层 okhttp3.Response 关闭；
     * 成功路径 body 已被 Retrofit converter 消费至 EOF，无需手动关闭。
     */
    private fun Response<*>.closeQuietly() {
        runCatching { raw().close() }
    }

    private fun mapDetail(
        dto: DoubanRexxarDetailDto,
        requestedId: String,
        requestedType: DoubanRexxarMediaType
    ): DoubanRexxarDetail {
        val releaseDates = (dto.pubdate + extractStringValues(dto.releaseDate)).distinct()
        val durations = (extractStringValues(dto.durations) + listOfNotNull(dto.duration)).distinct()
        val type = when (dto.type ?: dto.subtype) {
            "tv", "show" -> DoubanRexxarMediaType.TV
            "movie" -> DoubanRexxarMediaType.MOVIE
            else -> requestedType
        }
        return DoubanRexxarDetail(
            doubanId = dto.id.ifBlank { requestedId },
            title = dto.title,
            type = type,
            score = dto.rating?.value ?: dto.rating?.average,
            ratingCount = dto.rating?.count,
            year = extractYear(dto.year, dto.pubdate, dto.cardSubtitle),
            genres = dto.genres,
            poster = mapImage(dto.cover?.image, dto.pic),
            originalTitle = dto.originalTitle,
            countries = extractStringValues(dto.countries, dto.region),
            directors = extractStringValues(dto.directors, dto.director),
            writers = extractStringValues(dto.writers, dto.writer),
            cast = extractStringValues(dto.casts, dto.cast, dto.actors, dto.actor),
            summary = dto.summary ?: dto.intro,
            languages = extractStringValues(dto.languages, dto.language),
            initialReleaseDates = releaseDates,
            durations = durations,
            runtime = dto.duration ?: durations.firstOrNull(),
            aka = extractStringValues(dto.aka, dto.alias),
            imdbId = dto.imdb ?: dto.imdbId
        )
    }

    /** 将 Rexxar 的字符串、数组及演职员对象统一转换成字符串列表。 */
    private fun extractStringValues(vararg elements: JsonElement?): List<String> {
        return elements
            .asSequence()
            .filterNotNull()
            .flatMap { extractStringValues(it).asSequence() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
    }

    private fun extractStringValues(element: JsonElement): List<String> = when (element) {
        is JsonArray -> element.flatMap(::extractStringValues)
        is JsonObject -> listOfNotNull(
            "name",
            "value",
            "title",
            "text"
        ).asSequence()
            .mapNotNull { key -> element[key]?.asStringOrNull() }
            .firstOrNull()
            ?.let(::listOf)
            ?: emptyList()
        is JsonPrimitive -> element.contentOrNull?.let(::listOf) ?: emptyList()
    }

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun mapImage(
        image: DoubanRexxarImageDto?,
        pic: com.tracktosearch.data.remote.douban.dto.DoubanRexxarPicDto?
    ): DoubanRexxarImage? {
        val result = DoubanRexxarImage(
            largeUrl = image?.large?.url ?: pic?.large,
            normalUrl = image?.normal?.url ?: pic?.normal,
            smallUrl = image?.small?.url ?: pic?.small
        )
        return result.takeIf {
            it.largeUrl != null || it.normalUrl != null || it.smallUrl != null
        }
    }

    private fun mapPhoto(dto: DoubanRexxarPhotoDto, position: Int): DoubanRexxarPhoto {
        return DoubanRexxarPhoto(
            id = dto.id,
            largeUrl = dto.image?.large?.url,
            normalUrl = dto.image?.normal?.url,
            smallUrl = dto.image?.small?.url,
            position = position
        )
    }

    private fun mapPhotoPage(dto: DoubanRexxarPhotoPageDto): DoubanRexxarPhotoPage {
        return DoubanRexxarPhotoPage(
            total = dto.total,
            start = dto.start,
            count = dto.count,
            photos = dto.photos.mapIndexed { index, photo -> mapPhoto(photo, dto.start + index) }
        )
    }

    private fun mapComments(dto: com.tracktosearch.data.remote.douban.dto.DoubanRexxarInterestPageDto): DoubanRexxarShortCommentPage {
        return DoubanRexxarShortCommentPage(
            total = dto.total,
            start = dto.start,
            count = dto.count,
            comments = dto.interests.map(::mapComment)
        )
    }

    private fun mapComment(dto: DoubanRexxarInterestDto): DoubanRexxarShortComment {
        val rating = dto.rating?.value?.let { value ->
            val max = dto.rating.max ?: 5
            val stars = if (max > 5) value / max * 5.0 else value
            stars.toInt().coerceIn(1, 5)
        }
        return DoubanRexxarShortComment(
            id = dto.id,
            authorName = dto.user?.name,
            ratingStars = rating,
            text = dto.comment,
            createdAt = dto.createTime,
            voteCount = dto.voteCount
        )
    }

    private fun mergePhotos(
        existing: DoubanRexxarPhotoCacheEntry?,
        page: DoubanRexxarPhotoPage,
        fetchedAt: Long
    ): DoubanRexxarPhotoCacheEntry {
        val merged = LinkedHashMap<String, DoubanRexxarPhoto>()
        existing?.photos?.forEach { merged[it.id] = it }
        page.photos.forEach { photo ->
            if (photo.id.isNotBlank()) {
                merged[photo.id] = photo
            }
        }
        return DoubanRexxarPhotoCacheEntry(
            total = maxOf(existing?.total ?: 0, page.total, merged.size),
            photos = merged.values.toList(),
            lastFetchedAt = fetchedAt
        )
    }

    private fun mergePhotoEntries(
        existing: DoubanRexxarPhotoCacheEntry?,
        incoming: DoubanRexxarPhotoCacheEntry
    ): DoubanRexxarPhotoCacheEntry {
        val merged = LinkedHashMap<String, DoubanRexxarPhoto>()
        existing?.photos?.forEach { merged[it.id] = it }
        incoming.photos.forEach { photo ->
            if (photo.id.isNotBlank()) merged[photo.id] = photo
        }
        return DoubanRexxarPhotoCacheEntry(
            total = maxOf(existing?.total ?: 0, incoming.total, merged.size),
            photos = merged.values.toList(),
            lastFetchedAt = maxOf(existing?.lastFetchedAt ?: 0L, incoming.lastFetchedAt)
        )
    }

    private fun extractYear(
        explicitYear: String?,
        pubdates: List<String>,
        cardSubtitle: String?
    ): String? {
        val candidates = buildList {
            explicitYear?.let(::add)
            addAll(pubdates)
            cardSubtitle?.let(::add)
        }
        return candidates.asSequence()
            .mapNotNull { YEAR_PATTERN.find(it)?.value }
            .firstOrNull()
    }

    private fun subjectKey(doubanId: String, mediaType: DoubanRexxarMediaType): String =
        "${mediaType.path}:$doubanId"

    private fun pageKey(
        doubanId: String,
        mediaType: DoubanRexxarMediaType,
        start: Int,
        count: Int
    ): String = "${subjectKey(doubanId, mediaType)}:$start:$count"

    private fun requireValidPage(start: Int, count: Int) {
        require(start >= 0) { "start must be non-negative" }
        require(count in 1..MAX_PAGE_SIZE) { "count must be between 1 and $MAX_PAGE_SIZE" }
    }

    private fun randomRetryDelay(): Long = Random.nextLong(MIN_RETRY_DELAY, MAX_RETRY_DELAY + 1)

    private fun DoubanRexxarPhotoCacheEntry.satisfies(start: Int, count: Int): Boolean {
        if (total > 0 && start >= total) return true
        if (photos.all { it.position != null }) {
            val end = if (total > 0) minOf(start + count, total) else start + count
            return (start until end).all { position ->
                photos.any { it.position == position }
            }
        }
        return start + count <= photos.size
    }

    private fun DoubanRexxarPhotoCacheEntry.isFresh(now: Long): Boolean {
        return lastFetchedAt > 0L && now >= lastFetchedAt && now - lastFetchedAt < PHOTO_REFRESH_INTERVAL
    }

    private fun DoubanRexxarPhotoCacheEntry.toPage(start: Int, count: Int): DoubanRexxarPhotoPage {
        val pagePhotos = if (photos.all { it.position != null }) {
            photos
                .asSequence()
                .filter { photo -> photo.position in start until (start + count) }
                .sortedBy { it.position }
                .toList()
        } else {
            val end = minOf(start + count, photos.size)
            if (start >= end) emptyList() else photos.subList(start, end)
        }
        return DoubanRexxarPhotoPage(
            total = total,
            start = start,
            count = pagePhotos.size,
            photos = pagePhotos
        )
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        const val COMMENTS_TTL_MILLIS = 6 * 60 * 60 * 1000L
        const val MIN_RETRY_DELAY = 2_000L
        const val MAX_RETRY_DELAY = 4_000L
        const val PHOTO_REFRESH_INTERVAL = 24 * 60 * 60 * 1000L
        val YEAR_PATTERN = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    }
}
