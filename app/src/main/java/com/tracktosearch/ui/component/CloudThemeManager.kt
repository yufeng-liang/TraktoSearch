package com.tracktosearch.ui.component

import android.content.Context
import android.location.Location
import com.tracktosearch.R
import com.tracktosearch.data.repository.WeatherInfo
import com.tracktosearch.data.repository.WeatherRepository
import com.tracktosearch.util.Holiday
import com.tracktosearch.util.HolidayDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 白云主题，对应不同的 Lottie 动画 */
enum class CloudTheme(val rawRes: Int) {
    SUNNY(R.raw.cloud_sunny),
    RAINY(R.raw.cloud_rainy),
    SNOWY(R.raw.cloud_snowy),
    THUNDER(R.raw.cloud_thunder),
    CHRISTMAS(R.raw.cloud_christmas),
    SPRING_FESTIVAL(R.raw.cloud_spring_festival),
    HALLOWEEN(R.raw.cloud_halloween);
}

/** 彩蛋动画资源列表 */
private val EASTER_EGG_RES = listOf(
    R.raw.easter_shy,
    R.raw.easter_cat,
    R.raw.easter_sleepy
)

/** 趣味文案池 */
private val EASTER_MESSAGES = listOf(
    "今天也要开心哦~ ☁️",
    "摸鱼时间到！🐟",
    "你发现了隐藏彩蛋！🎉",
    "云朵向你比了个心 💕",
    "休息一下，喝杯水吧 ☕",
    "愿你的搜索永远有结果 🔍",
    "天空飘来五个字：那都不是事儿~",
    "你戳到我了，好痒！🤭"
)

@Singleton
class CloudThemeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val holidayDetector: HolidayDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _currentTheme = MutableStateFlow(CloudTheme.SUNNY)
    val currentTheme: StateFlow<CloudTheme> = _currentTheme

    // 彩蛋状态：null 表示未触发
    private val _easterEggRes = MutableStateFlow<Int?>(null)
    val easterEggRes: StateFlow<Int?> = _easterEggRes

    private val _easterMessage = MutableStateFlow<String?>(null)
    val easterMessage: StateFlow<String?> = _easterMessage

    // 随机去重：记录最近播放过的彩蛋索引
    private val recentEasterIndices = ArrayDeque<Int>(2)
    private var lastMessageIndex = -1

    /** 加载天气并更新主题 */
    fun loadTheme(location: Location? = null) {
        scope.launch {
            // 节日优先
            val holiday = holidayDetector.detect()
            if (holiday != null) {
                _currentTheme.value = when (holiday) {
                    Holiday.CHRISTMAS -> CloudTheme.CHRISTMAS
                    Holiday.SPRING_FESTIVAL -> CloudTheme.SPRING_FESTIVAL
                    Holiday.HALLOWEEN -> CloudTheme.HALLOWEEN
                }
                return@launch
            }

            // 天气
            val weather = weatherRepository.getCurrentWeather(location)
            if (weather != null) {
                _currentTheme.value = weatherCodeToTheme(weather)
            }
        }
    }

    /** 用户点击白云，触发彩蛋 */
    fun onCloudClicked() {
        // 选择彩蛋动画（避免连续重复）
        val available = EASTER_EGG_RES.indices.filter { it !in recentEasterIndices }
        val index = if (available.isNotEmpty()) available.random() else EASTER_EGG_RES.indices.random()

        if (recentEasterIndices.size >= 2) recentEasterIndices.removeFirst()
        recentEasterIndices.addLast(index)

        _easterEggRes.value = EASTER_EGG_RES[index]

        // 选择趣味文案（避免连续重复）
        var msgIndex: Int
        do { msgIndex = EASTER_MESSAGES.indices.random() } while (msgIndex == lastMessageIndex && EASTER_MESSAGES.size > 1)
        lastMessageIndex = msgIndex
        _easterMessage.value = EASTER_MESSAGES[msgIndex]
    }

    /** 关闭彩蛋 */
    fun onEasterDismissed() {
        _easterEggRes.value = null
        _easterMessage.value = null
    }

    private fun weatherCodeToTheme(weather: WeatherInfo): CloudTheme {
        return when (weather.weatherCode) {
            0, 1, 2, 3 -> CloudTheme.SUNNY
            45, 48 -> CloudTheme.RAINY
            in 51..67 -> CloudTheme.RAINY
            in 71..86 -> CloudTheme.SNOWY
            in 95..99 -> CloudTheme.THUNDER
            else -> CloudTheme.SUNNY
        }
    }
}
