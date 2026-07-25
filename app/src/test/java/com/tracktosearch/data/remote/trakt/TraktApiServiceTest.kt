package com.tracktosearch.data.remote.trakt

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

class TraktApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TraktApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/trakt/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(TraktApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getUserProfileByUsername_usesPublicProfileEndpoint() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"username":"yuhu","images":{"avatar":{"full":"https://walter.trakt.tv/avatar.jpg"}}}""")
        )

        val result = api.getUserProfileByUsername("yuhu")

        assertThat(result.body()?.images?.avatar?.full)
            .isEqualTo("https://walter.trakt.tv/avatar.jpg")
        val request = server.takeRequest()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/api/trakt/users/yuhu/profile")
        assertThat(request.requestUrl?.queryParameter("extended")).isEqualTo("full")
    }

    @Test
    fun getUserByUsername_usesPublicUserEndpoint() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"username":"yuhu","images":{"avatar":{"full":"https://walter.trakt.tv/avatar.jpg"}}}""")
        )

        val result = api.getUserByUsername("yuhu")

        assertThat(result.body()?.images?.avatar?.full)
            .isEqualTo("https://walter.trakt.tv/avatar.jpg")
        val request = server.takeRequest()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/api/trakt/users/yuhu")
        assertThat(request.requestUrl?.queryParameter("extended")).isEqualTo("full")
    }
}
