package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.cloud.GiteeContentRequest
import com.tracktosearch.data.remote.cloud.GiteeContentUpdateResponse
import com.tracktosearch.data.remote.cloud.GiteeContentsApi
import com.tracktosearch.data.remote.cloud.GiteePublicRawApi
import com.tracktosearch.data.remote.douban.DoubanRexxarDetail
import com.tracktosearch.data.remote.douban.DoubanRexxarImage
import com.tracktosearch.data.remote.douban.DoubanRexxarMediaType
import com.tracktosearch.data.remote.douban.DoubanRexxarPhoto
import com.tracktosearch.data.remote.douban.DoubanRexxarPhotoCacheEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanPublicDataPoolManagerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun getDetail_readsPublicDocument_withoutUsingGatewayContentsApi() = runTest {
        val rawApi = FakeRawApi()
        val contentsApi = mockk<GiteeContentsApi>(relaxed = true)
        val detail = testDetail()
        rawApi.files["details/movie/1295644.json"] = json.encodeToString(
            DoubanPublicDetailDocument.serializer(),
            DoubanPublicDetailDocument(version = 2, complete = true, detail = detail)
        ).toResponseBody("application/json".toMediaType())
        val manager = DoubanPublicDataPoolManager(rawApi, contentsApi, json)

        val result = manager.getDetail("1295644", DoubanRexxarMediaType.MOVIE)

        assertThat(result).isEqualTo(detail)
        assertThat(rawApi.requestedPaths).containsExactly("details/movie/1295644.json")
        coVerify(exactly = 0) { contentsApi.getFileContent(any(), any(), any(), any()) }
    }

    @Test
    fun getDetail_rejectsOldOrIncompletePublicDocuments() = runTest {
        val rawApi = FakeRawApi()
        val contentsApi = mockk<GiteeContentsApi>(relaxed = true)
        val detail = testDetail()
        rawApi.files["details/movie/old.json"] = json.encodeToString(
            DoubanPublicDetailDocument.serializer(),
            DoubanPublicDetailDocument(version = 1, complete = true, detail = detail.copy(doubanId = "old"))
        ).toResponseBody("application/json".toMediaType())
        rawApi.files["details/movie/incomplete.json"] = json.encodeToString(
            DoubanPublicDetailDocument.serializer(),
            DoubanPublicDetailDocument(version = 2, complete = false, detail = detail.copy(doubanId = "incomplete"))
        ).toResponseBody("application/json".toMediaType())
        val manager = DoubanPublicDataPoolManager(rawApi, contentsApi, json)

        assertThat(manager.getDetail("old", DoubanRexxarMediaType.MOVIE)).isNull()
        assertThat(manager.getDetail("incomplete", DoubanRexxarMediaType.MOVIE)).isNull()
    }

    @Test
    fun getDetail_samePathUsesSingleFlightAndNegativeCache() = runTest {
        val rawApi = FakeRawApi()
        val contentsApi = mockk<GiteeContentsApi>(relaxed = true)
        val detail = testDetail()
        rawApi.files["details/movie/1295644.json"] = json.encodeToString(
            DoubanPublicDetailDocument.serializer(),
            DoubanPublicDetailDocument(version = 2, complete = true, detail = detail)
        ).toResponseBody("application/json".toMediaType())
        rawApi.beforeRead = CompletableDeferred()
        rawApi.waitBeforeResponse = CompletableDeferred()
        val manager = DoubanPublicDataPoolManager(rawApi, contentsApi, json)

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            manager.getDetail("1295644", DoubanRexxarMediaType.MOVIE)
        }
        rawApi.beforeRead!!.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            manager.getDetail("1295644", DoubanRexxarMediaType.MOVIE)
        }
        rawApi.waitBeforeResponse!!.complete(Unit)

        assertThat(first.await()).isEqualTo(detail)
        assertThat(second.await()).isEqualTo(detail)
        assertThat(rawApi.requestedPaths).containsExactly("details/movie/1295644.json")

        val missingRawApi = FakeRawApi()
        val missingManager = DoubanPublicDataPoolManager(missingRawApi, contentsApi, json)
        assertThat(missingManager.getDetail("missing", DoubanRexxarMediaType.MOVIE)).isNull()
        assertThat(missingManager.getDetail("missing", DoubanRexxarMediaType.MOVIE)).isNull()
        assertThat(missingRawApi.requestedPaths).containsExactly("details/movie/missing.json")
    }

    @Test
    fun publicDocuments_doNotContainPrivateFields() {
        val detailJson = json.encodeToString(
            DoubanPublicDetailDocument.serializer(),
            DoubanPublicDetailDocument(detail = testDetail())
        )
        val photosJson = json.encodeToString(
            DoubanPublicPhotosDocument.serializer(),
            DoubanPublicPhotosDocument(
                cache = DoubanRexxarPhotoCacheEntry(
                    total = 1,
                    photos = listOf(DoubanRexxarPhoto(id = "p1", normalUrl = "https://img.example/p1.jpg")),
                    lastFetchedAt = 100L
                )
            )
        )

        assertThat(detailJson).doesNotContain("mediaType")
        assertThat(detailJson).doesNotContain("userRating")
        assertThat(detailJson).doesNotContain("userComment")
        assertThat(detailJson).doesNotContain("cookie")
        assertThat(photosJson).doesNotContain("mediaType")
        assertThat(photosJson).doesNotContain("userRating")
        assertThat(photosJson).doesNotContain("userComment")
    }

    @Test
    fun uploadPhotos_conflictMergeKeepsExistingAndNewPhotos() = runTest {
        val rawApi = FakeRawApi()
        val contentsApi = mockk<GiteeContentsApi>(relaxed = true)
        val existing = DoubanPublicPhotosDocument(
            cache = DoubanRexxarPhotoCacheEntry(
                total = 1,
                photos = listOf(DoubanRexxarPhoto(id = "old", normalUrl = "https://img.example/old.jpg")),
                lastFetchedAt = 100L
            )
        )
        val existingGiteeResponse = Response.success<JsonElement>(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"content":"${Base64.getEncoder().withoutPadding().encodeToString(json.encodeToString(DoubanPublicPhotosDocument.serializer(), existing).toByteArray())}","sha":"old-sha"}"""
            )
        )
        coEvery { contentsApi.getFileContent(any(), any(), any(), any()) } returns existingGiteeResponse
        coEvery { contentsApi.putFileContent(any(), any(), any(), any()) } returns
            Response.success(GiteeContentUpdateResponse())
        val manager = DoubanPublicDataPoolManager(rawApi, contentsApi, json)

        manager.uploadPhotos(
            "1295644",
            DoubanRexxarMediaType.MOVIE,
            DoubanRexxarPhotoCacheEntry(
                total = 2,
                photos = listOf(DoubanRexxarPhoto(id = "new", normalUrl = "https://img.example/new.jpg")),
                lastFetchedAt = 200L
            )
        )

        val requestSlot = slot<GiteeContentRequest>()
        coVerify { contentsApi.putFileContent(any(), any(), "photos/movie/1295644.json", capture(requestSlot)) }
        val uploadedJson = String(Base64.getDecoder().decode(requestSlot.captured.content))
        assertThat(uploadedJson).contains("old")
        assertThat(uploadedJson).contains("new")
        assertThat(uploadedJson).doesNotContain("mediaType")
        assertThat(requestSlot.captured.sha).isEqualTo("old-sha")
    }

    private fun testDetail() = DoubanRexxarDetail(
        doubanId = "1295644",
        title = "这个杀手不太冷",
        type = DoubanRexxarMediaType.MOVIE,
        score = 9.4,
        year = "1994",
        poster = DoubanRexxarImage(normalUrl = "https://img.example/poster.jpg"),
        directors = listOf("吕克·贝松"),
        summary = "职业杀手与女孩的故事",
        imdbId = "tt0110413"
    )

    private class FakeRawApi : GiteePublicRawApi {
        val files = mutableMapOf<String, ResponseBody>()
        val requestedPaths = mutableListOf<String>()
        var beforeRead: CompletableDeferred<Unit>? = null
        var waitBeforeResponse: CompletableDeferred<Unit>? = null

        override suspend fun getRawFile(path: String): Response<ResponseBody> {
            requestedPaths += path
            beforeRead?.complete(Unit)
            waitBeforeResponse?.await()
            return files[path]?.let { Response.success(it) } ?: Response.error(404, "not found".toResponseBody())
        }
    }
}
