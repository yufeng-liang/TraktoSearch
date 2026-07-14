package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class GiteeContentsApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GiteeContentsApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GiteeContentsApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getFileContent_success_returnsBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":"hello"}"""))
        val response = api.getFileContent("owner", "repo", "path", "ref")
        assertThat(response.code()).isEqualTo(200)
        assertThat(response.body()).isNotNull()
    }

    @Test
    fun getFileContent_notFound_returns404() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        val response = api.getFileContent("owner", "repo", "path", "ref")
        assertThat(response.code()).isEqualTo(404)
    }

    @Test
    fun getFileContent_unauthorized_returns401() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))
        val response = api.getFileContent("owner", "repo", "path", "ref")
        assertThat(response.code()).isEqualTo(401)
    }

    @Test
    fun createFileContent_success_returns201() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"content":{"sha":"abc"},"commit":{"sha":"def"}}"""))
        val request = GiteeContentRequest(
            content = "aGVsbG8=",
            message = "test commit",
            branch = "main"
        )
        val response = api.createFileContent("owner", "repo", "path", request)
        assertThat(response.code()).isEqualTo(201)
        assertThat(response.body()?.commit).isNotNull()
    }

    @Test
    fun putFileContent_success_returns200() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"content":{"sha":"abc"},"commit":{"sha":"def"}}"""))
        val request = GiteeContentRequest(
            content = "dXBkYXRlZA==",
            message = "update",
            branch = "main",
            sha = "oldsha"
        )
        val response = api.putFileContent("owner", "repo", "path", request)
        assertThat(response.code()).isEqualTo(200)
    }

    @Test
    fun putFileContent_serverError_returns500() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"message":"Server Error"}"""))
        val request = GiteeContentRequest(
            content = "aGVsbG8=",
            message = "test",
            branch = "main",
            sha = "oldsha"
        )
        val response = api.putFileContent("owner", "repo", "path", request)
        assertThat(response.code()).isEqualTo(500)
    }
}
