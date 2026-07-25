package com.tracktosearch.data.remote.omdb

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

class OmdbApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: OmdbApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/omdb/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(OmdbApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getByImdbId_keepsGatewayBasePathAndQuery() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"Response":"True"}"""))

        api.getByImdbId("tt0369339")

        val request = server.takeRequest()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/api/omdb/")
        assertThat(request.requestUrl?.queryParameter("i")).isEqualTo("tt0369339")
        assertThat(request.requestUrl?.queryParameter("plot")).isEqualTo("short")
    }
}
