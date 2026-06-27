package com.tracktosearch.data.repository

import android.content.Context
import android.location.Location
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.data.remote.weather.OpenMeteoApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(name = "weather")

data class WeatherInfo(
    val weatherCode: Int,
    val temperature: Double
)

@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val openMeteoApi: OpenMeteoApi
) {
    companion object {
        private val KEY_WEATHER_CODE = intPreferencesKey("weather_code")
        private val KEY_TEMPERATURE = doublePreferencesKey("temperature")
        private val KEY_CACHE_TIME = longPreferencesKey("cache_time")
        private val CACHE_DURATION = TimeUnit.HOURS.toMillis(3)
    }

    suspend fun getCurrentWeather(location: Location? = null): WeatherInfo? {
        // 先检查缓存
        val cached = readCache()
        if (cached != null) return cached

        // 调用 API
        val lat = location?.latitude ?: 39.9   // 默认北京
        val lon = location?.longitude ?: 116.4

        return try {
            val response = openMeteoApi.getCurrentWeather(lat, lon)
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) {
                    val info = WeatherInfo(
                        weatherCode = body.current.weatherCode,
                        temperature = body.current.temperature
                    )
                    saveCache(info)
                    info
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun readCache(): WeatherInfo? {
        val prefs = context.weatherDataStore.data.first()
        val cacheTime = prefs[KEY_CACHE_TIME] ?: 0
        if (System.currentTimeMillis() - cacheTime > CACHE_DURATION) return null
        val code = prefs[KEY_WEATHER_CODE] ?: return null
        val temp = prefs[KEY_TEMPERATURE] ?: return null
        return WeatherInfo(weatherCode = code, temperature = temp)
    }

    private suspend fun saveCache(info: WeatherInfo) {
        context.weatherDataStore.edit { prefs ->
            prefs[KEY_WEATHER_CODE] = info.weatherCode
            prefs[KEY_TEMPERATURE] = info.temperature
            prefs[KEY_CACHE_TIME] = System.currentTimeMillis()
        }
    }
}
