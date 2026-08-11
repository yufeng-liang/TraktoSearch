package com.tracktosearch.data.remote.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class XiaomiLocationResult(
    @SerialName("status") val status: Int = -1,
    @SerialName("locationKey") val locationKey: String? = null,
    @SerialName("key") val key: String? = null
)

@Serializable
data class XiaomiWeatherResponse(
    @SerialName("current") val current: XiaomiCurrentWeather? = null
)

@Serializable
data class XiaomiCurrentWeather(
    @SerialName("temperature") val temperature: XiaomiMeasurement? = null,
    @SerialName("weather") val weather: JsonElement? = null
)

@Serializable
data class XiaomiMeasurement(
    @SerialName("value") val value: JsonElement? = null
)
