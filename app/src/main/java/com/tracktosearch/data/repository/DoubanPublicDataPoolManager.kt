package com.tracktosearch.data.repository

import android.util.Base64
import android.util.Log
import com.tracktosearch.data.remote.cloud.GiteeContentRequest
import com.tracktosearch.data.remote.cloud.GiteeContentResponse
import com.tracktosearch.data.remote.cloud.GiteeContentsApi
import com.tracktosearch.data.remote.cloud.GiteePublicRawApi
import com.tracktosearch.data.remote.douban.DoubanRexxarDetail
import com.tracktosearch.data.remote.douban.DoubanRexxarMediaType
import com.tracktosearch.data.remote.douban.DoubanRexxarPhoto
import com.tracktosearch.data.remote.douban.DoubanRexxarPhotoCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRexxarShortCommentPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/** 公共池详情文件协议。只包含豆瓣公开 Rexxar 字段。 */
@Serializable
data class DoubanPublicDetailDocument(
    val version: Int = 2,
    val complete: Boolean = false,
    val detail: DoubanRexxarDetail
)

/** 公共池剧照文件协议。URL 永久保留，lastFetchedAt 用于判断是否需要检查新增。 */
@Serializable
data class DoubanPublicPhotosDocument(
    val version: Int = 1,
    val cache: DoubanRexxarPhotoCacheEntry
)

/** 公共池短评文件协议。短评是公开数据，fetchedAt 仅用于本地 TTL 判断。 */
@Serializable
data class DoubanPublicCommentsDocument(
    val version: Int = 1,
    val page: DoubanRexxarShortCommentPage,
    val fetchedAt: Long
)

/** 公共池 ID 映射分片协议。key 只允许 imdb/trakt/tmdb 三类公共外部 ID。 */
@Serializable
data class DoubanPublicMappingDocument(
    val version: Int = 1,
    val entries: Map<String, String> = emptyMap()
)

/** 公共豆瓣数据池：Raw 直读，Contents API 仅用于通过网关上传。 */
@Singleton
class DoubanPublicDataPoolManager @Inject constructor(
    private val rawApi: GiteePublicRawApi,
    private val contentsApi: GiteeContentsApi,
    private val json: Json
) {

    companion object {
        private const val TAG = "DoubanPublicPool"
        private const val OWNER = "yufeng-liang"
        private const val REPO = "meta-data-public"
        private const val BRANCH = "master"
        private const val DETAILS_PREFIX = "details/"
        private const val PHOTOS_PREFIX = "photos/"
        private const val COMMENTS_PREFIX = "comments/"
        private const val MAPPINGS_PREFIX = "mappings/"
        private const val SHARD_LENGTH = 3
        private const val MAX_RETRY = 3
        private const val CURRENT_DETAIL_DOCUMENT_VERSION = 2
        private const val NEGATIVE_READ_TTL_MILLIS = 5 * 60 * 1000L
    }

    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private val readFlights = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val negativeReadUntil = ConcurrentHashMap<String, Long>()

    suspend fun getDetail(
        doubanId: String,
        mediaType: DoubanRexxarMediaType
    ): DoubanRexxarDetail? = readDocument(
        detailPath(doubanId, mediaType),
        DoubanPublicDetailDocument.serializer()
    )?.takeIf { document ->
        document.version >= CURRENT_DETAIL_DOCUMENT_VERSION &&
            document.complete &&
            document.detail.isCompleteForPublicCache()
    }?.detail

    suspend fun getPhotos(
        doubanId: String,
        mediaType: DoubanRexxarMediaType
    ): DoubanRexxarPhotoCacheEntry? = readDocument(photoPath(doubanId, mediaType), DoubanPublicPhotosDocument.serializer())?.cache

    suspend fun getComments(
        doubanId: String,
        mediaType: DoubanRexxarMediaType,
        start: Int,
        count: Int
    ): DoubanPublicCommentsDocument? = readDocument(
        commentsPath(doubanId, mediaType, start, count),
        DoubanPublicCommentsDocument.serializer()
    )

    /** 按映射分片聚合直读，命中的 key 返回对应 doubanId。 */
    suspend fun getMappings(keys: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val validKeys = keys.filter(::isPublicMappingKey).distinct()
        if (validKeys.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<String, String>()
        validKeys.groupBy(::mappingShard).forEach { (shard, shardKeys) ->
            val document = readDocument(
                "$MAPPINGS_PREFIX$shard.json",
                DoubanPublicMappingDocument.serializer()
            ) ?: return@forEach
            shardKeys.forEach { key ->
                document.entries[key]?.let { result[key] = it }
            }
        }
        result
    }

    suspend fun uploadDetail(detail: DoubanRexxarDetail): Boolean = withContext(Dispatchers.IO) {
        writeMergedDocument(
            path = detailPath(detail.doubanId, detail.type),
            serializer = DoubanPublicDetailDocument.serializer(),
            incoming = DoubanPublicDetailDocument(
                complete = detail.isCompleteForPublicCache(),
                detail = detail
            ),
            merge = { old, new ->
                val mergedDetail = mergeDetail(old.detail, new.detail)
                DoubanPublicDetailDocument(
                    complete = old.complete || new.complete || mergedDetail.isCompleteForPublicCache(),
                    detail = mergedDetail
                )
            }
        )
    }

    suspend fun uploadPhotos(
        doubanId: String,
        mediaType: DoubanRexxarMediaType,
        cache: DoubanRexxarPhotoCacheEntry
    ): Boolean = withContext(Dispatchers.IO) {
        writeMergedDocument(
            path = photoPath(doubanId, mediaType),
            serializer = DoubanPublicPhotosDocument.serializer(),
            incoming = DoubanPublicPhotosDocument(cache = cache),
            merge = { old, new -> DoubanPublicPhotosDocument(cache = mergePhotos(old.cache, new.cache)) }
        )
    }

    suspend fun uploadComments(
        doubanId: String,
        mediaType: DoubanRexxarMediaType,
        start: Int,
        count: Int,
        page: DoubanRexxarShortCommentPage,
        fetchedAt: Long = System.currentTimeMillis()
    ): Boolean = withContext(Dispatchers.IO) {
        writeMergedDocument(
            path = commentsPath(doubanId, mediaType, start, count),
            serializer = DoubanPublicCommentsDocument.serializer(),
            incoming = DoubanPublicCommentsDocument(page = page, fetchedAt = fetchedAt),
            merge = { old, new -> if (new.fetchedAt >= old.fetchedAt) new else old }
        )
    }

    suspend fun uploadMappings(mappings: Map<String, String>): Int = withContext(Dispatchers.IO) {
        val validMappings = mappings.filter { (key, value) ->
            isPublicMappingKey(key) && value.isNotBlank()
        }
        var uploaded = 0
        validMappings.entries.groupBy { mappingShard(it.key) }.forEach { (shard, entries) ->
            val path = "$MAPPINGS_PREFIX$shard.json"
            val success = writeMergedDocument(
                path = path,
                serializer = DoubanPublicMappingDocument.serializer(),
                incoming = DoubanPublicMappingDocument(entries = entries.associate { it.key to it.value }),
                merge = { old, new -> DoubanPublicMappingDocument(entries = old.entries + new.entries) }
            )
            if (success) uploaded++
        }
        uploaded
    }

    private suspend fun <T> readDocument(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>
    ): T? {
        val body = readRawBody(path) ?: return null
        return try {
            json.decodeFromString(serializer, body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Raw 读取按路径合并并发请求；404 只短时负缓存，避免阻塞后续上传可见性。 */
    private suspend fun readRawBody(path: String): String? {
        val now = System.currentTimeMillis()
        negativeReadUntil[path]?.let { expiresAt ->
            if (now < expiresAt) return null
            negativeReadUntil.remove(path, expiresAt)
        }

        val candidate = CompletableDeferred<String?>()
        val existing = readFlights.putIfAbsent(path, candidate)
        if (existing != null) return existing.await()

        return try {
            val response = rawApi.getRawFile(path)
            if (response.code() == 404) {
                negativeReadUntil[path] = System.currentTimeMillis() + NEGATIVE_READ_TTL_MILLIS
                candidate.complete(null)
                null
            } else if (!response.isSuccessful) {
                candidate.complete(null)
                null
            } else {
                val body = response.body()?.string()?.takeIf { it.isNotBlank() }
                if (body == null) {
                    negativeReadUntil[path] = System.currentTimeMillis() + NEGATIVE_READ_TTL_MILLIS
                } else {
                    negativeReadUntil.remove(path)
                }
                candidate.complete(body)
                body
            }
        } catch (e: CancellationException) {
            candidate.completeExceptionally(e)
            throw e
        } catch (_: IOException) {
            candidate.complete(null)
            null
        } catch (_: Exception) {
            candidate.complete(null)
            null
        } finally {
            readFlights.remove(path, candidate)
        }
    }

    private suspend fun <T> writeMergedDocument(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        incoming: T,
        merge: (T, T) -> T
    ): Boolean {
        val lock = fileLocks.computeIfAbsent(path) { Mutex() }
        return lock.withLock {
            repeat(MAX_RETRY) { attempt ->
                try {
                    val current = readWritableDocument(path, serializer)
                    val merged = merge(current?.document ?: incoming, incoming)
                    if (current != null && current.document == merged) {
                        negativeReadUntil.remove(path)
                        return@withLock true
                    }
                    val encoded = Base64.encodeToString(
                        json.encodeToString(serializer, merged).toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                    val request = GiteeContentRequest(
                        content = encoded,
                        message = "public_pool/$path",
                        branch = BRANCH,
                        sha = current?.sha
                    )
                    val response = if (current?.sha == null) {
                        contentsApi.createFileContent(OWNER, REPO, path, request)
                    } else {
                        contentsApi.putFileContent(OWNER, REPO, path, request)
                    }
                    if (response.isSuccessful) {
                        negativeReadUntil.remove(path)
                        return@withLock true
                    }
                    if (response.code() !in setOf(409, 422)) {
                        Log.w(TAG, "上传公共文件失败 path=$path code=${response.code()}")
                        return@withLock false
                    }
                    Log.w(TAG, "公共文件冲突 path=$path retry=${attempt + 1}/$MAX_RETRY")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "上传公共文件异常 path=$path: ${e.message}")
                    return@withLock false
                }
            }
            false
        }
    }

    private suspend fun <T> readWritableDocument(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>
    ): WritableDocument<T>? {
        return try {
            val response = contentsApi.getFileContent(OWNER, REPO, path, BRANCH)
            if (!response.isSuccessful) return null
            val body = response.body() as? JsonObject ?: return null
            val gitee = json.decodeFromJsonElement(GiteeContentResponse.serializer(), body)
            val content = gitee.content ?: return null
            val decoded = String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
            WritableDocument(json.decodeFromString(serializer, decoded), gitee.sha)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun mergeDetail(old: DoubanRexxarDetail, new: DoubanRexxarDetail): DoubanRexxarDetail {
        return old.copy(
            title = new.title ?: old.title,
            type = new.type,
            score = new.score ?: old.score,
            ratingCount = new.ratingCount ?: old.ratingCount,
            year = new.year ?: old.year,
            genres = new.genres.ifEmpty { old.genres },
            poster = new.poster ?: old.poster,
            originalTitle = new.originalTitle ?: old.originalTitle,
            countries = new.countries.ifEmpty { old.countries },
            directors = new.directors.ifEmpty { old.directors },
            writers = new.writers.ifEmpty { old.writers },
            cast = new.cast.ifEmpty { old.cast },
            summary = new.summary ?: old.summary,
            languages = new.languages.ifEmpty { old.languages },
            initialReleaseDates = new.initialReleaseDates.ifEmpty { old.initialReleaseDates },
            durations = new.durations.ifEmpty { old.durations },
            runtime = new.runtime ?: old.runtime,
            aka = new.aka.ifEmpty { old.aka },
            imdbId = new.imdbId ?: old.imdbId
        )
    }

    private fun DoubanRexxarDetail.isCompleteForPublicCache(): Boolean {
        val hasPoster = poster?.let {
            !it.largeUrl.isNullOrBlank() || !it.normalUrl.isNullOrBlank() || !it.smallUrl.isNullOrBlank()
        } == true
        val hasCredits = directors.isNotEmpty() || writers.isNotEmpty() || cast.isNotEmpty()
        return !title.isNullOrBlank() &&
            score != null &&
            !year.isNullOrBlank() &&
            hasPoster &&
            !summary.isNullOrBlank() &&
            hasCredits &&
            !imdbId.isNullOrBlank()
    }

    private fun mergePhotos(
        old: DoubanRexxarPhotoCacheEntry,
        new: DoubanRexxarPhotoCacheEntry
    ): DoubanRexxarPhotoCacheEntry {
        val photos = LinkedHashMap<String, DoubanRexxarPhoto>()
        old.photos.forEach { photos[it.id] = it }
        new.photos.forEach { if (it.id.isNotBlank()) photos[it.id] = it }
        return DoubanRexxarPhotoCacheEntry(
            total = maxOf(old.total, new.total, photos.size),
            photos = photos.values.toList(),
            lastFetchedAt = maxOf(old.lastFetchedAt, new.lastFetchedAt)
        )
    }

    private fun detailPath(doubanId: String, type: DoubanRexxarMediaType) =
        "$DETAILS_PREFIX${type.path}/$doubanId.json"

    private fun photoPath(doubanId: String, type: DoubanRexxarMediaType) =
        "$PHOTOS_PREFIX${type.path}/$doubanId.json"

    private fun commentsPath(doubanId: String, type: DoubanRexxarMediaType, start: Int, count: Int) =
        "$COMMENTS_PREFIX${type.path}/$doubanId/$start-$count.json"

    private fun mappingShard(key: String): String = sha256(key).take(SHARD_LENGTH)

    private fun isPublicMappingKey(key: String): Boolean =
        key.startsWith("imdb:") || key.startsWith("trakt:") || key.startsWith("tmdb:")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class WritableDocument<T>(val document: T, val sha: String?)
}
