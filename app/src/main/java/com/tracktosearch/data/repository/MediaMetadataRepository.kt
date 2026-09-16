package com.tracktosearch.data.repository

import com.tracktosearch.data.local.db.MediaMetadataDao
import com.tracktosearch.data.local.db.MediaMetadataEntity
import com.tracktosearch.data.remote.media.MediaMetadataApiService
import com.tracktosearch.data.remote.media.dto.MediaCastDto
import com.tracktosearch.data.remote.media.dto.MediaCollectionDto
import com.tracktosearch.data.remote.media.dto.MediaCreditsDto
import com.tracktosearch.data.remote.media.dto.MediaCrewDto
import com.tracktosearch.data.remote.media.dto.MediaDetailBundleDto
import com.tracktosearch.data.remote.media.dto.MediaImageDto
import com.tracktosearch.data.remote.media.dto.MediaSimilarDto
import com.tracktosearch.data.remote.media.dto.MediaSummaryDto
import com.tracktosearch.data.remote.tmdb.dto.TmdbCast
import com.tracktosearch.data.remote.tmdb.dto.TmdbCreditsResponse
import com.tracktosearch.data.remote.tmdb.dto.TmdbCrew
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.data.util.TtlCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 客户端公开影视元数据仓库。
 *
 * 网络形状由服务端归一化，本地用 Room 持久化摘要/详情，进程内再用 [TtlCache] 做热点缓存。
 * 列表只批量补摘要；详情先返回旧值，再按 section 刷新，避免进入详情时标题、海报、演职员
 * 因请求返回顺序产生二次替换。
 */
@Singleton
class MediaMetadataRepository @Inject constructor(
    private val api: MediaMetadataApiService,
    private val dao: MediaMetadataDao,
    private val json: Json
) {
    internal constructor(
        api: MediaMetadataApiService,
        dao: MediaMetadataDao,
        json: Json,
        refreshScope: CoroutineScope
    ) : this(api, dao, json) {
        this.refreshScope = refreshScope
    }

    internal var refreshScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val summaryMemory = TtlCache<TimedValue<MediaSummary>>(
        ttlMillis = Long.MAX_VALUE,
        maxSize = 2000
    )
    private val detailMemory = TtlCache<TimedValue<MediaDetailBundle>>(
        ttlMillis = Long.MAX_VALUE,
        maxSize = 500
    )
    /** 后台详情刷新去重：同一 key 在 60 秒内只回源一次。 */
    private val detailRefreshDedup = TtlCache<Unit>(
        ttlMillis = DETAIL_REFRESH_DEDUP_MS,
        maxSize = 500
    )

    /**
     * 批量读取摘要。
     *
     * 本地先按 key 一次读取 Room；只有缺失或超过 24 小时的条目才进入批量网络请求。
     * 网络失败时继续返回本地旧值，不会清空调用方已有数据。
     */
    suspend fun getSummaries(
        keys: List<MediaKey>,
        forceRefresh: Boolean = false
    ): List<MediaSummary> {
        val requested = keys
            .map(MediaKey::normalized)
            .distinct()
        if (requested.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val rows = loadRows(requested.map(MediaKey::cacheKey))
        val local = LinkedHashMap<MediaKey, MediaSummary>()
        val stale = mutableListOf<MediaKey>()

        requested.forEach { key ->
            val cacheKey = key.cacheKey()
            val memory = summaryMemory.get(cacheKey)?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            val entity = rows[cacheKey]?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            val entitySummary = entity?.let(::decodeSummary)
            val summary = memory?.value ?: entitySummary
            if (summary != null) {
                local[key] = summary
            }
            val refreshedAt = maxOf(
                memory?.refreshedAt ?: 0L,
                entity?.summaryRefreshedAt ?: 0L
            )
            if (forceRefresh || summary == null ||
                now - refreshedAt >= SUMMARY_TTL_MS
            ) {
                stale += key
            }
        }

        if (stale.isNotEmpty()) {
            stale.groupBy { it.locale }.forEach { (locale, localeKeys) ->
                localeKeys.chunked(MAX_SUMMARY_BATCH).forEach { chunk ->
                    val fetched = fetchSummaries(locale, chunk)
                    if (fetched != null) {
                        val fetchedByKey = fetched.associateBy { summary ->
                            MediaKey(summary.mediaType, summary.tmdbId, summary.locale)
                                .normalized()
                                .cacheKey()
                        }
                        persistSummaries(
                            summaries = fetched,
                            existingByKey = rows,
                            refreshedAt = now
                        )
                        fetched.forEach { summary ->
                            val key = MediaKey(summary.mediaType, summary.tmdbId, summary.locale)
                                .normalized()
                            fetchedByKey[key.cacheKey()]?.let { local[key] = it }
                        }
                    }
                }
            }
        }

        return requested.mapNotNull { local[it] }
    }

    /** 只从 Room 读取摘要，不触发网络；命中有效内存缓存时直接返回。 */
    suspend fun getCachedSummary(key: MediaKey): MediaSummary? {
        val normalized = key.normalized()
        summaryMemory.get(normalized.cacheKey())
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            ?.value
            ?.let { return it }
        val entity = dao.getByKey(normalized.cacheKey())
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            ?: return null
        val summary = decodeSummary(entity) ?: return null
        if (isSummaryFresh(entity, System.currentTimeMillis())) {
            summaryMemory.put(
                normalized.cacheKey(),
                TimedValue(summary, entity.summaryRefreshedAt, SCHEMA_VERSION)
            )
        }
        return summary
    }

    /**
     * 读取详情 bundle。
     *
     * - 6 小时内且所需 section 齐全：直接返回；
     * - 已过期但有旧值：先返回旧值，再后台单飞刷新；
     * - 无旧值或缺 section：同步请求本次需要的 section。
     */
    suspend fun getDetail(
        key: MediaKey,
        sections: Set<String>,
        forceRefresh: Boolean = false
    ): MediaDetailBundle? {
        val normalized = key.normalized()
        val requestedSections = normalizeSections(sections)
        val cacheKey = normalized.cacheKey()
        val now = System.currentTimeMillis()
        val memory = detailMemory.get(cacheKey)?.takeIf { it.schemaVersion == SCHEMA_VERSION }
        val memoryBundle = memory?.value
        val memoryFresh = memory != null && now - memory.refreshedAt < DETAIL_TTL_MS
        if (!forceRefresh && memoryFresh && memoryBundle != null &&
            hasAllSections(memoryBundle, requestedSections)
        ) {
            return memoryBundle
        }

        val entity = dao.getByKey(cacheKey)?.takeIf { it.schemaVersion == SCHEMA_VERSION }
        val cached = memoryBundle ?: entity?.let(::decodeDetail)
        val cachedFresh = entity != null &&
            entity.detailRefreshedAt > 0L &&
            now - entity.detailRefreshedAt < DETAIL_TTL_MS
        val missingSections = if (cached == null) {
            requestedSections
        } else {
            requestedSections.filterNot { hasSection(cached, it) }
        }

        if (!forceRefresh && cached != null && cachedFresh && missingSections.isEmpty()) {
            detailMemory.put(
                cacheKey,
                TimedValue(cached, entity.detailRefreshedAt, SCHEMA_VERSION)
            )
            return cached
        }
        if (!forceRefresh && cached != null && cachedFresh && missingSections.isNotEmpty()) {
            val fetched = fetchDetail(normalized, requestedSections) ?: return cached
            val merged = mergeDetail(cached, fetched, requestedSections)
            persistDetail(normalized, merged, now)
            return merged
        }
        if (!forceRefresh && cached != null && missingSections.isEmpty()) {
            detailMemory.put(
                cacheKey,
                TimedValue(cached, entity?.detailRefreshedAt ?: 0L, SCHEMA_VERSION)
            )
            scheduleDetailRefresh(normalized, requestedSections)
            return cached
        }

        val fetched = fetchDetail(normalized, requestedSections)
        if (fetched == null) {
            cached?.let {
                detailMemory.put(cacheKey, TimedValue(it, now, SCHEMA_VERSION))
            }
            return cached
        }
        val merged = if (cached == null) fetched else mergeDetail(cached, fetched, requestedSections)
        persistDetail(normalized, merged, now)
        return merged
    }

    /** 同步读取内存摘要；未命中返回 null，不读 Room、不发网络。 */
    suspend fun peekSummary(key: MediaKey): MediaSummary? =
        summaryMemory.get(key.normalized().cacheKey())?.value

    /** 真正同步的内存摘要读取，供详情首帧 seed 使用。 */
    fun peekSummaryLocal(key: MediaKey): MediaSummary? =
        summaryMemory.get(key.normalized().cacheKey())
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION }
            ?.value

    /** 让列表和详情共用同一份摘要落盘入口。 */
    suspend fun putSummary(summary: MediaSummary) {
        val key = MediaKey(summary.mediaType, summary.tmdbId, summary.locale).normalized()
        val now = System.currentTimeMillis()
        val existing = dao.getByKey(key.cacheKey())
        val existingByKey = if (existing == null) {
            emptyMap()
        } else {
            mapOf(key.cacheKey() to existing)
        }
        persistSummaries(listOf(summary), existingByKey, now)
    }

    private suspend fun fetchSummaries(
        locale: String,
        keys: List<MediaKey>
    ): List<MediaSummary>? {
        return try {
            val response = api.getSummaries(
                locale = locale,
                ids = keys.joinToString(",") { it.wireId() }
            )
            if (!response.isSuccessful) return null
            response.body()?.data?.items?.map(MediaSummaryDto::toModel).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchDetail(
        key: MediaKey,
        sections: List<String>
    ): MediaDetailBundle? {
        return try {
            val response = api.getDetail(
                type = key.mediaType,
                id = key.tmdbId,
                locale = key.locale,
                sections = sections.joinToString(",")
            )
            if (!response.isSuccessful) return null
            response.body()?.data?.toModel()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun scheduleDetailRefresh(
        key: MediaKey,
        sections: List<String>
    ) {
        val refreshKey = key.cacheKey() + "|" + sections.joinToString(",")
        refreshScope.launch {
            detailRefreshDedup.getOrAwait(refreshKey) {
                val fetched = fetchDetail(key, sections) ?: return@getOrAwait
                val existing = dao.getByKey(key.cacheKey())
                val cached = existing?.let(::decodeDetail)
                val merged = if (cached == null) {
                    fetched
                } else {
                    mergeDetail(cached, fetched, sections)
                }
                persistDetail(key, merged, System.currentTimeMillis())
            }
        }
    }

    private suspend fun loadRows(
        cacheKeys: List<String>
    ): Map<String, MediaMetadataEntity> {
        if (cacheKeys.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, MediaMetadataEntity>()
        cacheKeys.distinct().chunked(DAO_KEY_BATCH).forEach { chunk ->
            dao.getByKeys(chunk).forEach { entity ->
                result[entity.mediaKey] = entity
            }
        }
        return result
    }

    private suspend fun persistSummaries(
        summaries: List<MediaSummary>,
        existingByKey: Map<String, MediaMetadataEntity>,
        refreshedAt: Long
    ) {
        if (summaries.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = summaries.map { summary ->
            val key = MediaKey(summary.mediaType, summary.tmdbId, summary.locale).normalized()
            val existing = existingByKey[key.cacheKey()]
            (existing ?: newEntity(key)).copy(
                mediaType = key.mediaType,
                tmdbId = key.tmdbId,
                locale = key.locale,
                summaryJson = json.encodeToString(MediaSummary.serializer(), summary),
                detailJson = existing?.detailJson,
                schemaVersion = SCHEMA_VERSION,
                summaryRefreshedAt = refreshedAt,
                detailRefreshedAt = existing?.detailRefreshedAt ?: 0L,
                updatedAt = now
            )
        }
        dao.upsertAll(rows)
        rows.forEach { row ->
            row.summaryJson?.let { summaryJson ->
                runCatching { json.decodeFromString(MediaSummary.serializer(), summaryJson) }
                    .getOrNull()
                    ?.let { summary ->
                        summaryMemory.put(
                            row.mediaKey,
                            TimedValue(summary, row.summaryRefreshedAt, SCHEMA_VERSION)
                        )
                    }
            }
        }
    }

    private suspend fun persistDetail(
        key: MediaKey,
        bundle: MediaDetailBundle,
        refreshedAt: Long
    ) {
        val existing = dao.getByKey(key.cacheKey())
        val now = System.currentTimeMillis()
        val summary = bundle.summary ?: existing?.let(::decodeSummary)
        val row = (existing ?: newEntity(key)).copy(
            mediaType = key.mediaType,
            tmdbId = key.tmdbId,
            locale = key.locale,
            // summary=null 是“只有 R2 section 可用”的部分响应，不能把空摘要写进本地缓存。
            summaryJson = summary?.let {
                json.encodeToString(MediaSummary.serializer(), it)
            } ?: existing?.summaryJson,
            detailJson = json.encodeToString(MediaDetailBundle.serializer(), bundle),
            schemaVersion = SCHEMA_VERSION,
            // 详情 section 刷新只更新 section 时间；摘要 TTL 由摘要接口单独维护。
            summaryRefreshedAt = existing?.summaryRefreshedAt ?: now,
            detailRefreshedAt = refreshedAt,
            updatedAt = now
        )
        dao.upsertAll(listOf(row))
        summary?.let {
            summaryMemory.put(
                row.mediaKey,
                TimedValue(it, row.summaryRefreshedAt, SCHEMA_VERSION)
            )
        }
        detailMemory.put(row.mediaKey, TimedValue(bundle, refreshedAt, SCHEMA_VERSION))
    }

    private fun decodeSummary(entity: MediaMetadataEntity): MediaSummary? {
        val raw = entity.summaryJson ?: return null
        return runCatching {
            json.decodeFromString(MediaSummary.serializer(), raw)
        }.getOrNull()
    }

    private fun decodeDetail(entity: MediaMetadataEntity): MediaDetailBundle? {
        val raw = entity.detailJson ?: return null
        return runCatching {
            json.decodeFromString(MediaDetailBundle.serializer(), raw)
        }.getOrNull()
    }

    private fun newEntity(key: MediaKey) = MediaMetadataEntity(
        mediaKey = key.cacheKey(),
        mediaType = key.mediaType,
        tmdbId = key.tmdbId,
        locale = key.locale
    )

    private data class TimedValue<T>(
        val value: T,
        val refreshedAt: Long,
        val schemaVersion: Int
    )

    companion object {
        // v4: v3 的 countries 直接透传 TMDB 的英文国名（如 "Germany"），
        // 服务端已改为按 locale 输出译名，这里隔离本地旧值，让详情页重新取回中文国名。
        // v3: v2 可能已缓存中文请求下的空 videos；隔离旧详情，避免列表/详情误用。
        private const val SCHEMA_VERSION = 4
        private const val MAX_SUMMARY_BATCH = 20
        private const val DAO_KEY_BATCH = 800
        private const val SUMMARY_TTL_MS = 24 * 60 * 60 * 1000L
        private const val DETAIL_TTL_MS = 6 * 60 * 60 * 1000L
        private const val DETAIL_REFRESH_DEDUP_MS = 60 * 1000L
        private val SECTION_NAMES = setOf(
            "credits",
            "videos",
            "images",
            "similar",
            "collection"
        )

        private fun isSummaryFresh(
            entity: MediaMetadataEntity,
            now: Long
        ): Boolean = entity.summaryRefreshedAt > 0L &&
            now - entity.summaryRefreshedAt < SUMMARY_TTL_MS

        private fun normalizeSections(sections: Set<String>): List<String> =
            sections
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter { it in SECTION_NAMES }
                .distinct()
                .sorted()

        private fun hasAllSections(
            bundle: MediaDetailBundle,
            sections: List<String>
        ): Boolean = sections.all { hasSection(bundle, it) }

        private fun hasSection(
            bundle: MediaDetailBundle,
            section: String
        ): Boolean = when (section) {
            "credits" -> bundle.credits != null
            // 空数组是服务端确认“无预告片”的有效结果；旧 schema 的空值已由版本号隔离。
            "videos" -> bundle.videos != null
            "images" -> bundle.images != null
            "similar" -> bundle.similar != null
            // summary.collectionId=null 表示服务端已确认“无系列”，与尚未加载区分。
            "collection" -> bundle.collection != null || bundle.summary?.collectionId == null && bundle.summary != null
            else -> true
        }

        private fun mergeDetail(
            old: MediaDetailBundle,
            fetched: MediaDetailBundle,
            sections: List<String>
        ): MediaDetailBundle = old.copy(
            summary = old.summary,
            credits = if ("credits" in sections) fetched.credits ?: old.credits else old.credits,
            videos = if ("videos" in sections) fetched.videos ?: old.videos else old.videos,
            images = if ("images" in sections) fetched.images ?: old.images else old.images,
            similar = if ("similar" in sections) fetched.similar ?: old.similar else old.similar,
            collection = if ("collection" in sections) {
                fetched.collection ?: old.collection
            } else {
                old.collection
            }
        )
    }
}

/**
 * 服务端影视条目的客户端 key。
 *
 * [mediaType] 只允许 movie/tv；[locale] 保持 BCP-47 的连字符形式（如 zh-CN）。
 */
data class MediaKey(
    val mediaType: String,
    val tmdbId: Int,
    val locale: String
) {
    fun cacheKey(): String =
        "${mediaType.trim().lowercase(Locale.ROOT)}:$tmdbId:${locale.trim().ifEmpty { "zh-CN" }}"

    /** 批量摘要接口只接受 `movie:550` / `tv:1396`，locale 通过独立参数传递。 */
    fun wireId(): String = "${mediaType.trim().lowercase(Locale.ROOT)}:$tmdbId"

    internal fun normalized(): MediaKey {
        val normalizedType = mediaType.trim().lowercase(Locale.ROOT)
        return copy(
            mediaType = normalizedType,
            locale = locale.trim().ifEmpty { "zh-CN" }
        )
    }
}

enum class TitleSource(val wireValue: String) {
    DETAIL("DETAIL"),
    ALTERNATIVE("ALTERNATIVE"),
    NONE("NONE");

    companion object {
        fun fromWire(value: String?): TitleSource = entries.firstOrNull {
            it.wireValue.equals(value?.trim(), ignoreCase = true)
        } ?: NONE
    }
}

@Serializable
data class MediaSummary(
    val mediaType: String,
    val tmdbId: Int,
    val locale: String,
    val title: String = "",
    val originalTitle: String = "",
    val overview: String = "",
    val posterPath: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val voteAverage: Double? = null,
    val runtime: Int? = null,
    val countries: List<String> = emptyList(),
    val status: String = "",
    val imdbId: String? = null,
    val collectionId: Int? = null,
    val titleSource: TitleSource = TitleSource.NONE
)

@Serializable
data class MediaStill(
    val filePath: String,
    val width: Int? = null,
    val height: Int? = null,
    val iso6391: String? = null,
    val source: String = "tmdb"
)

@Serializable
data class MediaSimilar(
    val tmdbId: Int,
    val title: String = "",
    val originalTitle: String = "",
    val posterPath: String? = null,
    val releaseDate: String? = null,
    val voteAverage: Double? = null
)

@Serializable
data class MediaCollection(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val parts: List<MediaSimilar> = emptyList()
)

@Serializable
data class MediaDetailBundle(
    val summary: MediaSummary? = null,
    val credits: TmdbCreditsResponse? = null,
    val videos: List<TmdbVideo>? = null,
    val images: List<MediaStill>? = null,
    val similar: List<MediaSimilar>? = null,
    val collection: MediaCollection? = null
)

private fun MediaSummaryDto.toModel(): MediaSummary = MediaSummary(
    mediaType = mediaType.trim().lowercase(Locale.ROOT),
    tmdbId = tmdbId,
    locale = locale.trim().ifEmpty { "zh-CN" },
    title = title,
    originalTitle = originalTitle,
    overview = overview,
    posterPath = posterPath,
    year = year,
    genres = genres,
    voteAverage = voteAverage,
    runtime = runtime,
    countries = countries,
    status = status,
    imdbId = imdbId,
    collectionId = collectionId,
    titleSource = TitleSource.fromWire(titleSource)
)

private fun MediaDetailBundleDto.toModel(): MediaDetailBundle = MediaDetailBundle(
    summary = summary?.toModel(),
    credits = credits?.toModel(),
    videos = videos,
    images = images?.map(MediaImageDto::toModel),
    similar = similar?.map(MediaSimilarDto::toModel),
    collection = collection?.toModel()
)

private fun MediaCreditsDto.toModel(): TmdbCreditsResponse = TmdbCreditsResponse(
    cast = cast.map(MediaCastDto::toModel),
    crew = crew.map(MediaCrewDto::toModel)
)

private fun MediaCastDto.toModel(): TmdbCast = TmdbCast(
    id = id,
    name = name,
    original_name = "",
    profile_path = profilePath,
    character = character,
    order = order
)

private fun MediaCrewDto.toModel(): TmdbCrew = TmdbCrew(
    id = id,
    name = name,
    original_name = "",
    profile_path = profilePath,
    job = job,
    department = department
)

private fun MediaImageDto.toModel(): MediaStill = MediaStill(
    filePath = filePath,
    width = width,
    height = height,
    iso6391 = iso6391,
    source = source.ifBlank { "tmdb" }
)

private fun MediaSimilarDto.toModel(): MediaSimilar = MediaSimilar(
    tmdbId = tmdbId,
    title = title,
    originalTitle = originalTitle,
    posterPath = posterPath,
    releaseDate = releaseDate,
    voteAverage = voteAverage
)

private fun MediaCollectionDto.toModel(): MediaCollection = MediaCollection(
    id = id,
    name = name,
    overview = overview,
    posterPath = posterPath,
    backdropPath = backdropPath,
    parts = parts.map(MediaSimilarDto::toModel)
)
