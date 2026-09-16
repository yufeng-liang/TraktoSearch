package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.MediaMetadataDao
import com.tracktosearch.data.local.db.MediaMetadataEntity
import com.tracktosearch.data.remote.media.MediaMetadataApiService
import com.tracktosearch.data.remote.media.dto.MediaCastDto
import com.tracktosearch.data.remote.media.dto.MediaCollectionDto
import com.tracktosearch.data.remote.media.dto.MediaCreditsDto
import com.tracktosearch.data.remote.media.dto.MediaCrewDto
import com.tracktosearch.data.remote.media.dto.MediaDetailBundleDto
import com.tracktosearch.data.remote.media.dto.MediaDetailEnvelope
import com.tracktosearch.data.remote.media.dto.MediaImageDto
import com.tracktosearch.data.remote.media.dto.MediaSimilarDto
import com.tracktosearch.data.remote.media.dto.MediaSummariesData
import com.tracktosearch.data.remote.media.dto.MediaSummariesResponse
import com.tracktosearch.data.remote.media.dto.MediaSummaryDto
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MediaMetadataRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Test
    fun `摘要按二十条分块请求并保持输入顺序`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = FakeMediaApi()
        val dao = FakeMediaMetadataDao()
        val repository = MediaMetadataRepository(
            api,
            dao,
            json,
            CoroutineScope(SupervisorJob() + dispatcher)
        )
        val keys = (1..21).map { MediaKey("movie", it, "zh-CN") }

        val result = repository.getSummaries(keys)

        assertThat(api.summaryRequests.map { it.second.split(",").size })
            .containsExactly(20, 1).inOrder()
        assertThat(api.summaryRequests.first().second)
            .startsWith("movie:1,movie:2,movie:3")
        assertThat(result.map { it.tmdbId }).containsExactlyElementsIn(keys.map { it.tmdbId })
            .inOrder()
        assertThat(result.first { it.tmdbId == 1 }.title).isEqualTo("标题 1")
    }

    @Test
    fun `摘要网络失败时返回本地旧值`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = MediaKey("movie", 550, "zh-CN")
        val old = summary(key, title = "本地标题")
        val dao = FakeMediaMetadataDao().apply {
            put(
                MediaMetadataEntity(
                    mediaKey = key.cacheKey(),
                    mediaType = key.mediaType,
                    tmdbId = key.tmdbId,
                    locale = key.locale,
                    summaryJson = json.encodeToString(MediaSummary.serializer(), old),
                    schemaVersion = 3,
                    summaryRefreshedAt = 0L
                )
            )
        }
        val repository = MediaMetadataRepository(
            FakeMediaApi(summaryFailure = IOException("offline")),
            dao,
            json,
            CoroutineScope(SupervisorJob() + dispatcher)
        )

        val result = repository.getSummaries(listOf(key))

        assertThat(result).containsExactly(old)
    }

    @Test
    fun `详情刷新只更新 section 且保留摘要时间`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = MediaKey("movie", 550, "zh-CN")
        val old = detailBundle(key, title = "旧标题", videoName = "旧预告")
        val dao = FakeMediaMetadataDao().apply {
            put(
                MediaMetadataEntity(
                    mediaKey = key.cacheKey(),
                    mediaType = key.mediaType,
                    tmdbId = key.tmdbId,
                    locale = key.locale,
                    summaryJson = json.encodeToString(MediaSummary.serializer(), old.summary),
                    detailJson = json.encodeToString(MediaDetailBundle.serializer(), old),
                    schemaVersion = 3,
                    summaryRefreshedAt = 0L,
                    detailRefreshedAt = 0L
                )
            )
        }
        val api = FakeMediaApi(
            detailBundle = MediaDetailBundleDto(
                summary = summaryDto(key, title = "新标题"),
                videos = listOf(
                    TmdbVideo(
                        id = "v1",
                        key = "key",
                        name = "新预告",
                        site = "YouTube",
                        type = "Trailer",
                        official = true,
                        size = 1080
                    )
                )
            )
        )
        val repository = MediaMetadataRepository(
            api,
            dao,
            json,
            CoroutineScope(SupervisorJob() + dispatcher)
        )

        val first = repository.getDetail(key, setOf("videos"))
        val second = repository.getDetail(key, setOf("videos"))
        advanceUntilIdle()

        assertThat(first?.summary?.title).isEqualTo("旧标题")
        assertThat(second?.summary?.title).isEqualTo("旧标题")
        assertThat(api.detailRequests).hasSize(1)
        // 详情刷新只更新 section，必须保留已展示过的摘要标题，避免详情页二次跳变。
        assertThat(dao.getByKey(key.cacheKey())?.detailJson).contains("旧标题")
        assertThat(dao.getByKey(key.cacheKey())?.detailJson).contains("新预告")
        assertThat(dao.getByKey(key.cacheKey())?.summaryRefreshedAt).isEqualTo(0L)
    }

    @Test
    fun `详情解析与 Room JSON 回写`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = MediaKey("tv", 1396, "zh-CN")
        val dao = FakeMediaMetadataDao()
        val api = FakeMediaApi(
            detailBundle = MediaDetailBundleDto(
                summary = summaryDto(key, title = "绝命毒师"),
                credits = MediaCreditsDto(
                    cast = listOf(
                        MediaCastDto(
                            id = 1,
                            name = "演员",
                            character = "角色",
                            profilePath = "/profile.jpg",
                            order = 0
                        )
                    ),
                    crew = listOf(
                        MediaCrewDto(
                            id = 2,
                            name = "导演",
                            job = "Director",
                            department = "Directing",
                            profilePath = "/crew.jpg"
                        )
                    )
                ),
                videos = listOf(
                    TmdbVideo(
                        id = "v1",
                        key = "abc",
                        name = "预告片",
                        site = "YouTube",
                        type = "Trailer",
                        official = true,
                        size = 1080
                    )
                ),
                images = listOf(
                    MediaImageDto(
                        filePath = "/still.jpg",
                        width = 1920,
                        height = 1080,
                        iso6391 = "zh",
                        source = "tmdb"
                    )
                ),
                similar = listOf(
                    MediaSimilarDto(
                        tmdbId = 100,
                        title = "相似剧",
                        originalTitle = "Similar",
                        posterPath = "/similar.jpg",
                        releaseDate = "2020-01-01",
                        voteAverage = 7.5
                    )
                ),
                collection = MediaCollectionDto(
                    id = 10,
                    name = "系列",
                    overview = "简介",
                    posterPath = "/collection.jpg",
                    backdropPath = "/collection-bg.jpg",
                    parts = emptyList()
                )
            )
        )
        val repository = MediaMetadataRepository(
            api,
            dao,
            json,
            CoroutineScope(SupervisorJob() + dispatcher)
        )

        val result = repository.getDetail(
            key,
            setOf("credits", "videos", "images", "similar", "collection")
        )

        assertThat(result?.summary?.title).isEqualTo("绝命毒师")
        assertThat(result?.credits?.cast?.single()?.profile_path).isEqualTo("/profile.jpg")
        assertThat(result?.credits?.crew?.single()?.profile_path).isEqualTo("/crew.jpg")
        assertThat(result?.videos?.single()?.key).isEqualTo("abc")
        assertThat(result?.images?.single()?.filePath).isEqualTo("/still.jpg")
        assertThat(result?.similar?.single()?.tmdbId).isEqualTo(100)
        assertThat(result?.collection?.id).isEqualTo(10)
        val row = dao.getByKey(key.cacheKey())
        assertThat(row?.summaryJson).contains("绝命毒师")
        assertThat(row?.detailJson).contains("/still.jpg")
    }

    @Test
    fun `旧 schema 视频缓存不再直接命中`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = MediaKey("movie", 550, "zh-CN")
        val old = detailBundle(key, title = "旧标题", videoName = "旧预告")
        val dao = FakeMediaMetadataDao().apply {
            put(
                MediaMetadataEntity(
                    mediaKey = key.cacheKey(),
                    mediaType = key.mediaType,
                    tmdbId = key.tmdbId,
                    locale = key.locale,
                    summaryJson = json.encodeToString(MediaSummary.serializer(), old.summary),
                    detailJson = json.encodeToString(MediaDetailBundle.serializer(), old),
                    schemaVersion = 1,
                    summaryRefreshedAt = System.currentTimeMillis(),
                    detailRefreshedAt = System.currentTimeMillis()
                )
            )
        }
        val api = FakeMediaApi(
            detailBundle = MediaDetailBundleDto(
                summary = summaryDto(key, title = "新标题"),
                videos = emptyList()
            )
        )
        val repository = MediaMetadataRepository(
            api,
            dao,
            json,
            CoroutineScope(SupervisorJob() + dispatcher)
        )

        val result = repository.getDetail(key, setOf("videos"))

        assertThat(api.detailRequests).hasSize(1)
        assertThat(result?.videos).isEmpty()
        assertThat(result?.summary?.title).isEqualTo("新标题")
    }

    @Test
    fun `新 schema 空视频列表视为已缓存，不重复请求`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = MediaKey("movie", 550, "zh-CN")
        val dao = FakeMediaMetadataDao()
        val api = FakeMediaApi(
            detailBundle = MediaDetailBundleDto(
                summary = summaryDto(key, title = "搏击俱乐部"),
                videos = emptyList()
            )
        )
        val repository = MediaMetadataRepository(
            api,
            dao,
            json,
            CoroutineScope(SupervisorJob() + dispatcher)
        )

        val first = repository.getDetail(key, setOf("videos"))
        val second = repository.getDetail(key, setOf("videos"))

        assertThat(first?.videos).isEmpty()
        assertThat(second?.videos).isEmpty()
        assertThat(api.detailRequests).hasSize(1)
    }

    private fun summary(key: MediaKey, title: String): MediaSummary = MediaSummary(
        mediaType = key.mediaType,
        tmdbId = key.tmdbId,
        locale = key.locale,
        title = title,
        originalTitle = "Original",
        posterPath = "/poster.jpg",
        year = 2024,
        genres = listOf("剧情"),
        voteAverage = 8.0,
        runtime = 120,
        countries = listOf("美国"),
        status = "Released",
        imdbId = "tt1",
        collectionId = 10,
        titleSource = TitleSource.DETAIL
    )

    private fun detailBundle(
        key: MediaKey,
        title: String,
        videoName: String
    ): MediaDetailBundle = MediaDetailBundle(
        summary = summary(key, title),
        credits = null,
        videos = listOf(
            TmdbVideo(
                id = "v1",
                key = "key",
                name = videoName,
                site = "YouTube",
                type = "Trailer",
                official = true,
                size = 1080
            )
        ),
        images = null,
        similar = null,
        collection = null
    )

    private fun summaryDto(key: MediaKey, title: String): MediaSummaryDto = MediaSummaryDto(
        mediaType = key.mediaType,
        tmdbId = key.tmdbId,
        locale = key.locale,
        title = title,
        originalTitle = "Original",
        posterPath = "/poster.jpg",
        year = 2024,
        genres = listOf("剧情"),
        voteAverage = 8.0,
        runtime = 120,
        countries = listOf("美国"),
        status = "Released",
        imdbId = "tt1",
        collectionId = 10,
        titleSource = "DETAIL"
    )

    private class FakeMediaApi(
        private val summaryFailure: Exception? = null,
        private val detailBundle: MediaDetailBundleDto? = null
    ) : MediaMetadataApiService {

        val summaryRequests = mutableListOf<Pair<String, String>>()
        val detailRequests = mutableListOf<Triple<String, Int, String>>()

        override suspend fun getSummaries(
            locale: String,
            ids: String
        ): Response<MediaSummariesResponse> {
            summaryRequests += locale to ids
            summaryFailure?.let { throw it }
            val items = ids.split(",")
                .mapNotNull { raw ->
                    val parts = raw.split(":")
                    if (parts.size != 2) return@mapNotNull null
                    MediaSummaryDto(
                        mediaType = parts[0],
                        tmdbId = parts[1].toInt(),
                        locale = locale,
                        title = "标题 ${parts[1]}",
                        originalTitle = "Original ${parts[1]}",
                        posterPath = "/poster-${parts[1]}.jpg",
                        year = 2024,
                        genres = listOf("剧情"),
                        voteAverage = 8.0,
                        runtime = 120,
                        countries = listOf("美国"),
                        status = "Released",
                        imdbId = "tt${parts[1]}",
                        collectionId = null,
                        titleSource = "DETAIL"
                    )
                }
            return Response.success(
                MediaSummariesResponse(
                    data = MediaSummariesData(items = items)
                )
            )
        }

        override suspend fun getDetail(
            type: String,
            id: Int,
            locale: String,
            sections: String
        ): Response<MediaDetailEnvelope> {
            detailRequests += Triple(type, id, sections)
            val bundle = detailBundle ?: MediaDetailBundleDto(
                summary = MediaSummaryDto(
                    mediaType = type,
                    tmdbId = id,
                    locale = locale,
                    title = "标题 $id"
                )
            )
            return Response.success(MediaDetailEnvelope(data = bundle))
        }
    }

    private class FakeMediaMetadataDao : MediaMetadataDao {
        private val rows = LinkedHashMap<String, MediaMetadataEntity>()

        fun put(entity: MediaMetadataEntity) {
            rows[entity.mediaKey] = entity
        }

        override suspend fun getByKeys(keys: List<String>): List<MediaMetadataEntity> =
            keys.mapNotNull(rows::get)

        override suspend fun getByKey(key: String): MediaMetadataEntity? = rows[key]

        override suspend fun upsertAll(items: List<MediaMetadataEntity>) {
            items.forEach { rows[it.mediaKey] = it }
        }

        override suspend fun deleteOlderThan(before: Long): Int {
            val removed = rows.values.filter { it.updatedAt < before }
            removed.forEach { rows.remove(it.mediaKey) }
            return removed.size
        }
    }
}
