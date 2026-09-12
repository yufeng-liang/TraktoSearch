package com.tracktosearch.data.repository

import android.content.Context
import android.location.Location
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.data.remote.weather.XiaomiWeatherApi
import com.tracktosearch.data.remote.weather.XiaomiWeatherResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private val Context.weatherDataStore: DataStore<Preferences> by preferencesDataStore(name = "weather")

data class WeatherInfo(
    val weatherCode: Int,
    val temperature: Double
)

@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val xiaomiWeatherApi: XiaomiWeatherApi
) {
    companion object {
        // 更换数据源后使用新版本 key，避免读取旧 Open-Meteo 缓存。
        private val KEY_WEATHER_CODE = intPreferencesKey("xiaomi_weather_code_v1")
        private val KEY_TEMPERATURE = doublePreferencesKey("xiaomi_temperature_v1")
        private val KEY_CACHE_TIME = longPreferencesKey("xiaomi_cache_time_v1")
        private val KEY_LATITUDE = doublePreferencesKey("xiaomi_latitude_v1")
        private val KEY_LONGITUDE = doublePreferencesKey("xiaomi_longitude_v1")
        private val CACHE_DURATION = TimeUnit.HOURS.toMillis(3)
        private const val DEFAULT_LATITUDE = 39.9
        private const val DEFAULT_LONGITUDE = 116.4
        private const val LOCATION_MATCH_EPSILON = 0.001
    }

    suspend fun getCurrentWeather(location: Location? = null): WeatherInfo? {
        val latitude = location?.latitude
            ?.takeIf { it.isFinite() && it in -90.0..90.0 }
            ?: DEFAULT_LATITUDE
        val longitude = location?.longitude
            ?.takeIf { it.isFinite() && it in -180.0..180.0 }
            ?: DEFAULT_LONGITUDE

        // 先检查同一位置的缓存，避免每次启动都访问坐标反查接口。
        val cached = readCache(latitude, longitude)
        if (cached != null) return cached

        return try {
            val locationResponse = xiaomiWeatherApi.getLocationByCoordinates(
                latitude = latitude,
                longitude = longitude,
                locale = XiaomiWeatherApi.LOCALE
            )
            if (!locationResponse.isSuccessful) return null

            val locationKey = locationResponse.body()
                .orEmpty()
                .firstOrNull { it.status == 0 }
                ?.let { result ->
                    normalizeLocationKey(result.locationKey?.takeIf(String::isNotBlank) ?: result.key)
                }
                ?: return null

            val weatherResponse = xiaomiWeatherApi.getCurrentWeather(
                latitude = 0,
                longitude = 0,
                locationKey = locationKey,
                days = XiaomiWeatherApi.FORECAST_DAYS,
                appKey = XiaomiWeatherApi.APP_KEY,
                sign = XiaomiWeatherApi.SIGN,
                isGlobal = locationKey.startsWith("accu:"),
                locale = XiaomiWeatherApi.LOCALE
            )
            if (!weatherResponse.isSuccessful) return null

            val info = weatherResponse.body()?.toWeatherInfo() ?: return null
            saveCache(info, latitude, longitude)
            info
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** 只读取搜索页已经建立的天气缓存，不触发定位或网络请求。 */
    suspend fun getCachedCurrentWeather(): WeatherInfo? {
        val prefs = context.weatherDataStore.data.first()
        val cacheTime = prefs[KEY_CACHE_TIME] ?: return null
        if (System.currentTimeMillis() - cacheTime > CACHE_DURATION) return null
        val code = prefs[KEY_WEATHER_CODE] ?: return null
        val temperature = prefs[KEY_TEMPERATURE] ?: return null
        return WeatherInfo(weatherCode = code, temperature = temperature)
    }

    private suspend fun readCache(latitude: Double, longitude: Double): WeatherInfo? {
        val prefs = context.weatherDataStore.data.first()
        val cacheTime = prefs[KEY_CACHE_TIME] ?: 0
        if (System.currentTimeMillis() - cacheTime > CACHE_DURATION) return null
        val cachedLatitude = prefs[KEY_LATITUDE] ?: return null
        val cachedLongitude = prefs[KEY_LONGITUDE] ?: return null
        if (abs(cachedLatitude - latitude) > LOCATION_MATCH_EPSILON ||
            abs(cachedLongitude - longitude) > LOCATION_MATCH_EPSILON
        ) return null
        val code = prefs[KEY_WEATHER_CODE] ?: return null
        val temp = prefs[KEY_TEMPERATURE] ?: return null
        return WeatherInfo(weatherCode = code, temperature = temp)
    }

    private suspend fun saveCache(info: WeatherInfo, latitude: Double, longitude: Double) {
        context.weatherDataStore.edit { prefs ->
            prefs[KEY_WEATHER_CODE] = info.weatherCode
            prefs[KEY_TEMPERATURE] = info.temperature
            prefs[KEY_CACHE_TIME] = System.currentTimeMillis()
            prefs[KEY_LATITUDE] = latitude
            prefs[KEY_LONGITUDE] = longitude
        }
    }

    private fun normalizeLocationKey(rawKey: String?): String? {
        val key = rawKey?.trim().orEmpty()
        return when {
            CHINA_CITY_ID.matches(key) -> "weathercn:$key"
            key.startsWith("weathercn:") &&
                CHINA_CITY_ID.matches(key.removePrefix("weathercn:")) -> key
            key.startsWith("accu:") &&
                ACCU_LOCATION_ID.matches(key.removePrefix("accu:")) -> key
            else -> null
        }
    }
}

private fun XiaomiWeatherResponse.toWeatherInfo(): WeatherInfo? {
    val current = current ?: return null
    val temperature = current.temperature?.value.toDoubleOrNull() ?: return null
    val weatherCode = current.weather.toIntOrNull() ?: return null
    return WeatherInfo(weatherCode = weatherCode, temperature = temperature)
}

private fun JsonElement?.toDoubleOrNull(): Double? = when (val element = this) {
    null, JsonNull -> null
    is JsonPrimitive -> element.content.toDoubleOrNull()
    else -> null
}

private fun JsonElement?.toIntOrNull(): Int? = when (val element = this) {
    null, JsonNull -> null
    is JsonPrimitive -> element.content.toIntOrNull()
    else -> null
}

private val CHINA_CITY_ID = Regex("\\d{9}")
private val ACCU_LOCATION_ID = Regex("[A-Za-z0-9_-]{1,128}")
