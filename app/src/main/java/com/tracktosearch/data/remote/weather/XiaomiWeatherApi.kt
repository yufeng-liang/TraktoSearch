package com.tracktosearch.data.remote.weather

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** 小米天气 wtr-v3 公开协议。 */
interface XiaomiWeatherApi {

    @GET("location/city/geo")
    suspend fun getLocationByCoordinates(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("locale") locale: String = LOCALE
    ): Response<List<XiaomiLocationResult>>

    @GET("weather/all")
    suspend fun getCurrentWeather(
        @Query("latitude") latitude: Int,
        @Query("longitude") longitude: Int,
        @Query("locationKey") locationKey: String,
        @Query("days") days: Int = FORECAST_DAYS,
        @Query("appKey") appKey: String = APP_KEY,
        @Query("sign") sign: String = SIGN,
        @Query("isGlobal") isGlobal: Boolean,
        @Query("locale") locale: String = LOCALE
    ): Response<XiaomiWeatherResponse>

    companion object {
        // 小米公开客户端协议中的固定标识，不是用户密钥。
        const val APP_KEY = "weather20151024"
        const val SIGN = "zUFJoAR2ZVrDy1vF3D07"
        const val LOCALE = "zh_cn"
        const val FORECAST_DAYS = 15
    }
}
