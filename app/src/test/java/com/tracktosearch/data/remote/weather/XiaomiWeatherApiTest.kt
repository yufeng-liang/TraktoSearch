package com.tracktosearch.data.remote.weather

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json

class XiaomiWeatherApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: XiaomiWeatherApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/wtr-v3/").toString())
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(XiaomiWeatherApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `coordinates and weather requests match Xiaomi wtr-v3 protocol`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[{\"status\":0,\"locationKey\":\"weathercn:101010100\"}]")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"current":{"temperature":{"value":25},"weather":9}}
                    """.trimIndent()
                )
        )

        api.getLocationByCoordinates(39.9, 116.4, XiaomiWeatherApi.LOCALE)
        assertThat(server.takeRequest().path).isEqualTo(
            "/wtr-v3/location/city/geo?latitude=39.9&longitude=116.4&locale=zh_cn"
        )

        val response = api.getCurrentWeather(
            latitude = 0,
            longitude = 0,
            locationKey = "weathercn:101010100",
            days = XiaomiWeatherApi.FORECAST_DAYS,
            appKey = XiaomiWeatherApi.APP_KEY,
            sign = XiaomiWeatherApi.SIGN,
            isGlobal = false,
            locale = XiaomiWeatherApi.LOCALE
        )
        assertThat(server.takeRequest().path).isEqualTo(
            "/wtr-v3/weather/all?latitude=0&longitude=0" +
                "&locationKey=weathercn%3A101010100&days=15" +
                "&appKey=weather20151024&sign=zUFJoAR2ZVrDy1vF3D07" +
                "&isGlobal=false&locale=zh_cn"
        )
        assertThat(response.body()?.current?.temperature?.value?.jsonPrimitive?.content)
            .isEqualTo("25")
        assertThat(response.body()?.current?.weather?.jsonPrimitive?.content)
            .isEqualTo("9")
    }
}
