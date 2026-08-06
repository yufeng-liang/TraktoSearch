package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class GiteePublicRawApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GiteePublicRawApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .build()
            .create(GiteePublicRawApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getRawFile_preservesNestedPath_withoutGatewayToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val response = api.getRawFile("details/movie/1295644.json")
        val request = server.takeRequest()

        assertThat(response.code()).isEqualTo(200)
        assertThat(request.path).isEqualTo("/details/movie/1295644.json")
        assertThat(request.getHeader("Authorization")).isNull()
    }

    @Test
    fun getRawFile_notFound_returnsMiss() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val response = api.getRawFile("mappings/abc.json")

        assertThat(response.code()).isEqualTo(404)
    }
}
