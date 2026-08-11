package com.tracktosearch.data.repository

import android.content.Context
import android.location.Location
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.weather.XiaomiCurrentWeather
import com.tracktosearch.data.remote.weather.XiaomiLocationResult
import com.tracktosearch.data.remote.weather.XiaomiMeasurement
import com.tracktosearch.data.remote.weather.XiaomiWeatherApi
import com.tracktosearch.data.remote.weather.XiaomiWeatherResponse
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WeatherRepositoryTest {

    private val xiaomiWeatherApi = mockk<XiaomiWeatherApi>(relaxed = true)
    private lateinit var context: Context
    private lateinit var repository: WeatherRepository

    @Before
    fun setUp() {
        clearMocks(xiaomiWeatherApi)
        context = RuntimeEnvironment.getApplication()
        repository = WeatherRepository(context, xiaomiWeatherApi)
        clearWeatherCache()
        coEvery {
            xiaomiWeatherApi.getLocationByCoordinates(any(), any(), any())
        } returns buildLocationResponse()
    }

    @Test
    fun `cache hit returns cached weather without repeating Xiaomi requests`() = runTest {
        val location = buildLocation(40.0, 116.0)
        stubWeather(buildSuccessResponse(weatherCode = 5, temperature = 15.0))

        val first = repository.getCurrentWeather(location)
        val second = repository.getCurrentWeather(location)

        assertThat(first).isEqualTo(WeatherInfo(weatherCode = 5, temperature = 15.0))
        assertThat(second).isEqualTo(first)
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getLocationByCoordinates(40.0, 116.0, XiaomiWeatherApi.LOCALE)
        }
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `cache miss resolves location key before requesting weather`() = runTest {
        val location = buildLocation(31.2, 121.5)
        stubWeather(buildSuccessResponse(weatherCode = 2, temperature = 30.0))

        val result = repository.getCurrentWeather(location)

        assertThat(result).isEqualTo(WeatherInfo(weatherCode = 2, temperature = 30.0))
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getLocationByCoordinates(31.2, 121.5, XiaomiWeatherApi.LOCALE)
        }
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getCurrentWeather(
                0,
                0,
                "weathercn:101010100",
                XiaomiWeatherApi.FORECAST_DAYS,
                XiaomiWeatherApi.APP_KEY,
                XiaomiWeatherApi.SIGN,
                false,
                XiaomiWeatherApi.LOCALE
            )
        }
    }

    @Test
    fun `null location uses default Beijing coordinates for geo lookup`() = runTest {
        stubWeather(buildSuccessResponse())

        val result = repository.getCurrentWeather(null)

        assertThat(result).isNotNull()
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getLocationByCoordinates(39.9, 116.4, XiaomiWeatherApi.LOCALE)
        }
    }

    @Test
    fun `global location key marks weather request as global`() = runTest {
        coEvery {
            xiaomiWeatherApi.getLocationByCoordinates(any(), any(), any())
        } returns buildLocationResponse(locationKey = "accu:328328")
        stubWeather(buildSuccessResponse(weatherCode = 0, temperature = 23.0))

        val result = repository.getCurrentWeather(buildLocation(51.5, -0.1))

        assertThat(result).isEqualTo(WeatherInfo(weatherCode = 0, temperature = 23.0))
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getCurrentWeather(
                0,
                0,
                "accu:328328",
                XiaomiWeatherApi.FORECAST_DAYS,
                XiaomiWeatherApi.APP_KEY,
                XiaomiWeatherApi.SIGN,
                true,
                XiaomiWeatherApi.LOCALE
            )
        }
    }

    @Test
    fun `different location does not reuse cached weather`() = runTest {
        stubWeather(
            buildSuccessResponse(weatherCode = 1, temperature = 10.0),
            buildSuccessResponse(weatherCode = 2, temperature = 20.0)
        )

        repository.getCurrentWeather(buildLocation(31.2, 121.5))
        val result = repository.getCurrentWeather(buildLocation(39.9, 116.4))

        assertThat(result).isEqualTo(WeatherInfo(weatherCode = 2, temperature = 20.0))
        coVerify(exactly = 2) {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `location lookup failure returns null without weather request`() = runTest {
        coEvery {
            xiaomiWeatherApi.getLocationByCoordinates(any(), any(), any())
        } returns buildErrorResponse<List<XiaomiLocationResult>>()

        val result = repository.getCurrentWeather(buildLocation(40.0, 116.0))

        assertThat(result).isNull()
        coVerify(exactly = 0) {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `invalid location key returns null without weather request`() = runTest {
        coEvery {
            xiaomiWeatherApi.getLocationByCoordinates(any(), any(), any())
        } returns buildLocationResponse(locationKey = "other:123")

        val result = repository.getCurrentWeather(buildLocation(40.0, 116.0))

        assertThat(result).isNull()
        coVerify(exactly = 0) {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `weather network exception returns null`() = runTest {
        coEvery {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        } throws IOException("network error")

        val result = repository.getCurrentWeather(buildLocation(40.0, 116.0))

        assertThat(result).isNull()
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `unsuccessful or empty weather response returns null`() = runTest {
        coEvery {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            buildErrorResponse<XiaomiWeatherResponse>(),
            buildNullBodyResponse()
        )

        assertThat(repository.getCurrentWeather(buildLocation(40.0, 116.0))).isNull()
        clearWeatherCache()
        assertThat(repository.getCurrentWeather(buildLocation(40.0, 116.0))).isNull()
    }

    @Test
    fun `expired cache requests fresh Xiaomi weather`() = runTest {
        writeExpiredCache()
        stubWeather(buildSuccessResponse(weatherCode = 1, temperature = 20.0))

        val result = repository.getCurrentWeather(buildLocation(40.0, 116.0))

        assertThat(result).isEqualTo(WeatherInfo(weatherCode = 1, temperature = 20.0))
        coVerify(exactly = 1) {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `Xiaomi current response maps weather code and decimal temperature`() = runTest {
        stubWeather(buildSuccessResponse(weatherCode = 61, temperature = 25.5))

        val result = repository.getCurrentWeather(buildLocation(40.0, 116.0))

        assertThat(result?.weatherCode).isEqualTo(61)
        assertThat(result?.temperature).isEqualTo(25.5)
        assertThat(result?.temperature).isNotEqualTo(25.0)
    }

    private fun stubWeather(vararg responses: Response<XiaomiWeatherResponse>) {
        coEvery {
            xiaomiWeatherApi.getCurrentWeather(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany responses.toList()
    }

    private fun buildLocation(locationLatitude: Double, locationLongitude: Double): Location {
        return Location("test").apply {
            latitude = locationLatitude
            longitude = locationLongitude
        }
    }

    private fun buildLocationResponse(
        locationKey: String = "weathercn:101010100"
    ): Response<List<XiaomiLocationResult>> = Response.success(
        listOf(XiaomiLocationResult(status = 0, locationKey = locationKey))
    )

    private fun buildSuccessResponse(
        weatherCode: Int = 1,
        temperature: Double = 20.0
    ): Response<XiaomiWeatherResponse> = Response.success(
        XiaomiWeatherResponse(
            current = XiaomiCurrentWeather(
                temperature = XiaomiMeasurement(JsonPrimitive(temperature.toString())),
                weather = JsonPrimitive(weatherCode.toString())
            )
        )
    )

    private fun <T> buildErrorResponse(): Response<T> {
        val response = mockk<Response<T>>()
        every { response.isSuccessful } returns false
        return response
    }

    private fun buildNullBodyResponse(): Response<XiaomiWeatherResponse> {
        val response = mockk<Response<XiaomiWeatherResponse>>()
        every { response.isSuccessful } returns true
        every { response.body() } returns null
        return response
    }

    @Suppress("UNCHECKED_CAST")
    private fun getProductionDataStore(): DataStore<Preferences>? {
        return try {
            val kotlinClass = Class.forName("com.tracktosearch.data.repository.WeatherRepositoryKt")
            val getMethod = kotlinClass.getDeclaredMethod("getWeatherDataStore", Context::class.java)
            getMethod.isAccessible = true
            getMethod.invoke(null, context) as DataStore<Preferences>
        } catch (_: Exception) {
            null
        }
    }

    private fun clearWeatherCache() {
        getProductionDataStore()?.let { dataStore ->
            runBlocking { dataStore.edit { it.clear() } }
        }
    }

    private suspend fun writeExpiredCache() {
        getProductionDataStore()?.edit { prefs ->
            prefs[androidx.datastore.preferences.core.intPreferencesKey("xiaomi_weather_code_v1")] = 99
            prefs[androidx.datastore.preferences.core.doublePreferencesKey("xiaomi_temperature_v1")] = 99.0
            prefs[androidx.datastore.preferences.core.longPreferencesKey("xiaomi_cache_time_v1")] = 0L
            prefs[androidx.datastore.preferences.core.doublePreferencesKey("xiaomi_latitude_v1")] = 40.0
            prefs[androidx.datastore.preferences.core.doublePreferencesKey("xiaomi_longitude_v1")] = 116.0
        }
    }
}
