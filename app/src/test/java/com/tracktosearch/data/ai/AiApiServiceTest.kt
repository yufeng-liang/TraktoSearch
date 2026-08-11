package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AiApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AiApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("api/ai/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(AiApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listCharacters_usesStableAiRoute() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":"SUCCESS","message":"OK","requestId":"r1","data":{"characters":[]}}""")
        )

        api.listCharacters()

        assertThat(server.takeRequest().path).isEqualTo("/api/ai/characters")
    }

    @Test
    fun api_doesNotExposeDeadActivationAsrRoute() {
        assertThat(AiApiService::class.java.methods.any { it.name == "recognizeActivation" }).isFalse()
    }

    @Test
    fun dailyKnowledge_usesPostRouteWithSessionBody() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":"SUCCESS","data":{"id":"daily-1"}}""")
        )

        api.getDailyKnowledge(AiDailyRequest(sessionId = "sprite-session"))

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/api/ai/daily")
        assertThat(request.body.readUtf8()).contains("sprite-session")
    }
}
