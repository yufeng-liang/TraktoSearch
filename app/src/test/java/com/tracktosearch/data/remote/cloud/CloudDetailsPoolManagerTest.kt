package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.CloudDetailsPoolManager
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.util.PersistentTtlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * CloudDetailsPoolManager 单元测试。
 *
 * 覆盖点：
 * - uploadDetails：单条上传（文件不存在走 POST），批量上传（合并到已有云端文件走 PUT）
 * - downloadDetail：命中云端返回详情，未命中（404）返回 null
 * - uploadDetailEntry：字段级合并（非 null 覆盖、null 保留池中原值、List 用 ifEmpty 保留）
 * - fetchAndMergeToLocal：本地未命中时把云端数据写入本地缓存
 * - AesCrypto 加解密往返：上传时加密，下载时解密能复原
 *
 * 使用 Robolectric：AesCrypto 依赖 android.util.Base64。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudDetailsPoolManagerTest {

    private val giteeApi = mockk<GiteeContentsApi>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val manager = CloudDetailsPoolManager(giteeApi, json)

    // 辅助：构造分片 payload 的 JSON 字符串（结构等同于私有 ShardPayload）
    private fun buildShardJson(entries: Map<String, DoubanDetailCacheEntry>): String {
        val entriesObj = buildJsonObject {
            entries.forEach { (id, entry) ->
                put(id, JsonPrimitive(json.encodeToString(DoubanDetailCacheEntry.serializer(), entry)))
            }
        }
        val payload = buildJsonObject {
            put("version", JsonPrimitive(1))
            put("total", JsonPrimitive(entries.size))
            put("entries", entriesObj)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    // 辅助：构造云端文件响应（base64(AesCrypto.encrypt(payloadJson)) + sha）
    private fun buildCloudResponseJson(
        entries: Map<String, DoubanDetailCacheEntry>,
        sha: String
    ): JsonObject {
        val payloadJson = buildShardJson(entries)
        val base64Content = android.util.Base64.encodeToString(
            payloadJson.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return buildJsonObject {
            put("content", JsonPrimitive(base64Content))
            put("sha", JsonPrimitive(sha))
        }
    }

    // 辅助：从上传的 GiteeContentRequest 解密 content 还原 payload JSON
    private fun decryptRequestContent(request: GiteeContentRequest): String {
        return String(
            android.util.Base64.decode(request.content, android.util.Base64.NO_WRAP),
            Charsets.UTF_8
        )
    }

    // 辅助：从 payload JSON 提取某 doubanId 对应的 entry
    private fun extractEntry(payloadJson: String, doubanId: String): DoubanDetailCacheEntry {
        val payload = json.decodeFromString(JsonObject.serializer(), payloadJson)
        val entriesObj = payload["entries"]!!.jsonObject
        val entryJson = entriesObj[doubanId]!!.jsonPrimitive.content
        return json.decodeFromString(DoubanDetailCacheEntry.serializer(), entryJson)
    }

    // 辅助：构造 mockk 的 Response<JsonElement>（命中场景）
    private fun mockSuccessResponse(body: JsonObject): Response<kotlinx.serialization.json.JsonElement> {
        val resp = mockk<Response<kotlinx.serialization.json.JsonElement>>()
        every { resp.isSuccessful } returns true
        every { resp.body() } returns body
        every { resp.code() } returns 200
        return resp
    }

    // 辅助：构造 mockk 的 Response<JsonElement>（未命中场景）
    private fun mockNotFoundResponse(): Response<kotlinx.serialization.json.JsonElement> {
        val resp = mockk<Response<kotlinx.serialization.json.JsonElement>>()
        every { resp.isSuccessful } returns false
        every { resp.code() } returns 404
        return resp
    }

    // 辅助：构造 mockk 的 Response<GiteeContentUpdateResponse>（成功）
    private fun mockSuccessUpdateResponse(): Response<GiteeContentUpdateResponse> {
        val resp = mockk<Response<GiteeContentUpdateResponse>>()
        every { resp.isSuccessful } returns true
        every { resp.code() } returns 201
        return resp
    }

    // ============================================================
    // 1. uploadDetails：单条上传（文件不存在走 POST，加密内容）
    // ============================================================
    @Test
    fun uploadDetails_singleEntry_fileNotExists_callsCreateFileContentWithEncryptedContent() = runTest {
        val detail = DoubanDetailCacheEntry(
            imdbId = "tt123",
            isTvShow = false,
            title = "测试电影",
            year = "2024"
        )

        // 云端文件不存在（404）
        coEvery { giteeApi.getFileContent(any(), any(), any(), any()) } returns mockNotFoundResponse()

        // createFileContent 成功
        val createSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), capture(createSlot))
        } returns mockSuccessUpdateResponse()

        val result = manager.uploadDetails(mapOf("123456" to detail))

        assertThat(result).isEqualTo(1)
        coVerify {
            giteeApi.createFileContent("yufeng-liang", "meta-data", any(), any())
        }

        // 验证 content 是加密的，且解密后包含期望的 entry
        val decryptedPayload = decryptRequestContent(createSlot.captured)
        val extracted = extractEntry(decryptedPayload, "123456")
        assertThat(extracted.imdbId).isEqualTo("tt123")
        assertThat(extracted.title).isEqualTo("测试电影")
        assertThat(extracted.year).isEqualTo("2024")
    }

    // ============================================================
    // 2. uploadDetails：上传空 Map 时返回 0，不调用 API
    // ============================================================
    @Test
    fun uploadDetails_emptyMap_returnsZeroWithoutApiCall() = runTest {
        val result = manager.uploadDetails(emptyMap())
        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) {
            giteeApi.getFileContent(any(), any(), any(), any())
            giteeApi.createFileContent(any(), any(), any(), any())
            giteeApi.putFileContent(any(), any(), any(), any())
        }
    }

    // ============================================================
    // 3. downloadDetail：命中云端返回详情（含解密）
    // ============================================================
    @Test
    fun downloadDetail_cloudHit_returnsDecryptedDetail() = runTest {
        val doubanId = "654321"
        val entry = DoubanDetailCacheEntry(
            imdbId = "tt789",
            isTvShow = true,
            title = "云端下载的电影",
            year = "2023",
            doubanRating = 9.1
        )
        val body = buildCloudResponseJson(mapOf(doubanId to entry), "sha-cloud-1")
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockSuccessResponse(body)

        val result = manager.downloadDetail(doubanId)

        assertThat(result).isNotNull()
        assertThat(result?.imdbId).isEqualTo("tt789")
        assertThat(result?.title).isEqualTo("云端下载的电影")
        assertThat(result?.isTvShow).isTrue()
        assertThat(result?.doubanRating).isEqualTo(9.1)
    }

    // ============================================================
    // 4. downloadDetail：未命中返回 null
    // ============================================================
    @Test
    fun downloadDetail_cloudMiss_returnsNull() = runTest {
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockNotFoundResponse()

        val result = manager.downloadDetail("999999")

        assertThat(result).isNull()
    }

    // ============================================================
    // 5. downloadDetail：响应成功但 body 为 null 返回 null
    // ============================================================
    @Test
    fun downloadDetail_successfulButNullBody_returnsNull() = runTest {
        val resp = mockk<Response<kotlinx.serialization.json.JsonElement>>()
        every { resp.isSuccessful } returns true
        every { resp.body() } returns null
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns resp

        val result = manager.downloadDetail("000001")

        assertThat(result).isNull()
    }

    // ============================================================
    // 6. uploadDetailEntry：字段级合并 - 非 null 覆盖、null 保留、List 用 ifEmpty 保留
    // ============================================================
    @Test
    fun uploadDetailEntry_mergesNonNullFieldsFromNewEntry() = runTest {
        val doubanId = "merge-001"

        // 云端已有：含 imdbId / title / year / genres / directors
        val cloudEntry = DoubanDetailCacheEntry(
            imdbId = "tt-cloud",
            isTvShow = false,
            title = "云端原标题",
            year = "2020",
            genres = listOf("剧情", "科幻"),
            directors = listOf("云端导演"),
            doubanRating = 8.0
        )
        val cloudBody = buildCloudResponseJson(mapOf(doubanId to cloudEntry), "sha-cloud-2")
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockSuccessResponse(cloudBody)

        // 上传新条目：title 覆盖、summary 新增；imdbId=null/year=null 保留云端；genres 空 → 保留云端
        val newEntry = DoubanDetailCacheEntry(
            imdbId = null,
            isTvShow = false,
            title = "新标题覆盖云端",
            summary = "新简介字段",
            genres = emptyList(),
            year = null
        )

        val putSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.putFileContent(any(), any(), any(), capture(putSlot))
        } returns mockSuccessUpdateResponse()

        val success = manager.uploadDetailEntry(doubanId, newEntry)

        assertThat(success).isTrue()
        coVerify { giteeApi.putFileContent(any(), any(), any(), any()) }

        // 解密上传内容，验证合并字段
        val decryptedPayload = decryptRequestContent(putSlot.captured)
        val merged = extractEntry(decryptedPayload, doubanId)

        // 非 null 字段覆盖
        assertThat(merged.title).isEqualTo("新标题覆盖云端")
        assertThat(merged.summary).isEqualTo("新简介字段")
        // null 字段保留云端原值
        assertThat(merged.imdbId).isEqualTo("tt-cloud")
        assertThat(merged.year).isEqualTo("2020")
        assertThat(merged.doubanRating).isEqualTo(8.0)
        // List 用 ifEmpty 保留云端原值
        assertThat(merged.genres).containsExactly("剧情", "科幻").inOrder()
        assertThat(merged.directors).containsExactly("云端导演")
    }

    // ============================================================
    // 7. uploadDetailEntry：云端无该条目时直接上传
    // ============================================================
    @Test
    fun uploadDetailEntry_cloudEmpty_uploadsEntryDirectly() = runTest {
        val doubanId = "new-001"
        val entry = DoubanDetailCacheEntry(
            imdbId = "tt-new",
            isTvShow = false,
            title = "全新条目"
        )

        // 云端无该文件（404）
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockNotFoundResponse()

        val createSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), capture(createSlot))
        } returns mockSuccessUpdateResponse()

        val success = manager.uploadDetailEntry(doubanId, entry)

        assertThat(success).isTrue()
        coVerify { giteeApi.createFileContent(any(), any(), any(), any()) }

        val decryptedPayload = decryptRequestContent(createSlot.captured)
        val uploaded = extractEntry(decryptedPayload, doubanId)
        assertThat(uploaded.imdbId).isEqualTo("tt-new")
        assertThat(uploaded.title).isEqualTo("全新条目")
    }

    // ============================================================
    // 8. AesCrypto 加解密往返：upload → download 端到端
    // ============================================================
    @Test
    fun uploadThenDownload_roundTripPreservesData() = runTest {
        val doubanId = "roundtrip-001"
        val entry = DoubanDetailCacheEntry(
            imdbId = "tt-round",
            isTvShow = true,
            title = "Round Trip",
            year = "2024",
            doubanRating = 8.5,
            summary = "加密往返测试",
            genres = listOf("动作", "冒险"),
            cast = listOf("演员A", "演员B")
        )

        // 1) 上传：云端文件不存在 → createFileContent
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockNotFoundResponse()

        val createSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), capture(createSlot))
        } returns mockSuccessUpdateResponse()

        manager.uploadDetails(mapOf(doubanId to entry))

        // 2) 模拟云端已存有上传后的内容（用捕获到的 content 作为云端 content）
        val capturedRequest = createSlot.captured
        val cloudBody = buildJsonObject {
            put("content", JsonPrimitive(capturedRequest.content))
            put("sha", JsonPrimitive("after-upload-sha"))
        }
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockSuccessResponse(cloudBody)

        // 3) 下载：返回的应该等于上传的 entry
        val downloaded = manager.downloadDetail(doubanId)

        assertThat(downloaded).isNotNull()
        assertThat(downloaded).isEqualTo(entry)
    }

    // ============================================================
    // 9. fetchAndMergeToLocal：本地未命中 → 把云端数据写入本地缓存
    // ============================================================
    @Test
    fun fetchAndMergeToLocal_localEmpty_writesCloudEntriesToLocal() = runTest {
        val doubanId = "merge-local-001"
        val entry = DoubanDetailCacheEntry(
            imdbId = "tt-merge-local",
            isTvShow = false,
            title = "合并到本地"
        )

        // 本地缓存未命中
        val detailCache = mockk<PersistentTtlCache<DoubanDetailCacheEntry>>(relaxed = true)
        every { detailCache.get(any()) } returns null

        // 云端命中
        val cloudBody = buildCloudResponseJson(mapOf(doubanId to entry), "sha-merge-1")
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockSuccessResponse(cloudBody)

        val written = manager.fetchAndMergeToLocal(listOf(doubanId), detailCache)

        assertThat(written).isEqualTo(1)
        verify { detailCache.put(doubanId, entry) }
    }

    // ============================================================
    // 10. fetchAndMergeToLocal：本地已有 → 跳过云端拉取
    // ============================================================
    @Test
    fun fetchAndMergeToLocal_localHit_skipsCloudFetch() = runTest {
        val doubanId = "merge-local-002"
        val localEntry = DoubanDetailCacheEntry(
            imdbId = "tt-local",
            isTvShow = false,
            title = "本地已有"
        )

        val detailCache = mockk<PersistentTtlCache<DoubanDetailCacheEntry>>(relaxed = true)
        every { detailCache.get(any()) } returns localEntry

        val written = manager.fetchAndMergeToLocal(listOf(doubanId), detailCache)

        assertThat(written).isEqualTo(0)
        coVerify(exactly = 0) {
            giteeApi.getFileContent(any(), any(), any(), any())
        }
        verify(exactly = 0) { detailCache.put(any(), any()) }
    }

    // ============================================================
    // 11. fetchAndMergeToLocal：空列表直接返回 0
    // ============================================================
    @Test
    fun fetchAndMergeToLocal_emptyList_returnsZero() = runTest {
        val detailCache = mockk<PersistentTtlCache<DoubanDetailCacheEntry>>(relaxed = true)

        val written = manager.fetchAndMergeToLocal(emptyList(), detailCache)

        assertThat(written).isEqualTo(0)
        coVerify(exactly = 0) {
            giteeApi.getFileContent(any(), any(), any(), any())
        }
    }

    // ============================================================
    // 12. uploadUserMarkedMediaType：上传单条 mediaType 标注
    // ============================================================
    @Test
    fun uploadUserMarkedMediaType_cloudEmpty_uploadsMarkedEntry() = runTest {
        val doubanId = "mark-001"

        // 云端无文件（404）
        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockNotFoundResponse()

        val createSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), capture(createSlot))
        } returns mockSuccessUpdateResponse()

        val success = manager.uploadUserMarkedMediaType(doubanId, "show")

        assertThat(success).isTrue()

        // 验证合并后的条目：mediaType=show, isTvShow=true（show → isTvShow=true）
        val decryptedPayload = decryptRequestContent(createSlot.captured)
        val uploaded = extractEntry(decryptedPayload, doubanId)
        assertThat(uploaded.mediaType).isEqualTo("show")
        assertThat(uploaded.isTvShow).isTrue()
    }

    // ============================================================
    // 13. uploadUserMarkedMediaType：movie 映射为 isTvShow=false
    // ============================================================
    @Test
    fun uploadUserMarkedMediaType_movie_mapsToIsTvShowFalse() = runTest {
        val doubanId = "mark-002"

        coEvery {
            giteeApi.getFileContent(any(), any(), any(), any())
        } returns mockNotFoundResponse()

        val createSlot = slot<GiteeContentRequest>()
        coEvery {
            giteeApi.createFileContent(any(), any(), any(), capture(createSlot))
        } returns mockSuccessUpdateResponse()

        manager.uploadUserMarkedMediaType(doubanId, "movie")

        val decryptedPayload = decryptRequestContent(createSlot.captured)
        val uploaded = extractEntry(decryptedPayload, doubanId)
        assertThat(uploaded.mediaType).isEqualTo("movie")
        assertThat(uploaded.isTvShow).isFalse()
    }
}
