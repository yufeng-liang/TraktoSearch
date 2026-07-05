package com.tracktosearch.ui.component

import android.content.Context
import android.content.SharedPreferences
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
    HALLOWEEN(R.raw.cloud_halloween),
    // 新增天气主题
    CLOUDY(R.raw.cloud_cloudy),
    OVERCAST(R.raw.cloud_overcast),
    MIST(R.raw.cloud_mist),
    NIGHT(R.raw.cloud_night),
    // 新增节日主题
    NATIONAL(R.raw.cloud_national),
    MIDAUTUMN(R.raw.cloud_midautumn),
    DRAGONBOAT(R.raw.cloud_dragonboat),
    LABOR(R.raw.cloud_labor);
}

/** 彩蛋：动画 + 语义匹配的候选文案池（1:N 配对） */
private data class EasterEgg(
    val animRes: Int,
    val messageResIds: List<Int>
)

private val EASTER_EGGS = listOf(
    EasterEgg(R.raw.easter_shy, listOf(R.string.easter_msg_shy_1, R.string.easter_msg_shy_2, R.string.easter_msg_shy_3)),
    EasterEgg(R.raw.easter_cat, listOf(R.string.easter_msg_cat_1, R.string.easter_msg_cat_2, R.string.easter_msg_cat_3)),
    EasterEgg(R.raw.easter_sleepy, listOf(R.string.easter_msg_sleepy_1, R.string.easter_msg_sleepy_2, R.string.easter_msg_sleepy_3)),
    EasterEgg(R.raw.easter_dog, listOf(R.string.easter_msg_dog_1, R.string.easter_msg_dog_2, R.string.easter_msg_dog_3)),
    EasterEgg(R.raw.easter_bunny, listOf(R.string.easter_msg_bunny_1, R.string.easter_msg_bunny_2, R.string.easter_msg_bunny_3)),
    EasterEgg(R.raw.easter_panda, listOf(R.string.easter_msg_panda_1, R.string.easter_msg_panda_2, R.string.easter_msg_panda_3)),
    EasterEgg(R.raw.easter_rainbow, listOf(R.string.easter_msg_rainbow_1, R.string.easter_msg_rainbow_2, R.string.easter_msg_rainbow_3)),
    EasterEgg(R.raw.easter_firework, listOf(R.string.easter_msg_firework_1, R.string.easter_msg_firework_2, R.string.easter_msg_firework_3)),
    EasterEgg(R.raw.easter_lantern, listOf(R.string.easter_msg_lantern_1, R.string.easter_msg_lantern_2, R.string.easter_msg_lantern_3))
)

private const val CACHE_PREFS_NAME = "cloud_theme"
private const val KEY_THEME = "cloud_theme"
private const val KEY_THEME_TIME = "cloud_theme_time"
private const val CACHE_VALID_MS = 6 * 60 * 60 * 1000L // 6 小时

@Singleton
class CloudThemeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val holidayDetector: HolidayDetector
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs: SharedPreferences = context.getSharedPreferences(CACHE_PREFS_NAME, Context.MODE_PRIVATE)

    // 初始化时读取缓存，避免闪烁
    private val _currentTheme = MutableStateFlow(loadCachedTheme())
    val currentTheme: StateFlow<CloudTheme> = _currentTheme

    // 彩蛋状态：null 表示未触发
    private val _easterEggRes = MutableStateFlow<Int?>(null)
    val easterEggRes: StateFlow<Int?> = _easterEggRes

    // 彩蛋文案的 stringRes ID（UI 层用 stringResource 解析）
    private val _easterMessageRes = MutableStateFlow<Int?>(null)
    val easterMessageRes: StateFlow<Int?> = _easterMessageRes

    // 定位权限状态
    private val _hasLocationPermission = MutableStateFlow(false)
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission

    // 权限提示弹窗显示状态
    private val _showPermissionDialog = MutableStateFlow(false)
    val showPermissionDialog: StateFlow<Boolean> = _showPermissionDialog

    // 夜晚交替状态
    private val _isNightAlternate = MutableStateFlow(false)
    val isNightAlternate: StateFlow<Boolean> = _isNightAlternate

    // 随机去重：记录最近播放过的彩蛋索引
    private val recentEasterIndices = ArrayDeque<Int>(2)

    /** 加载天气并更新主题 */
    fun loadTheme(location: Location? = null) {
        scope.launch {
            // 节日优先
            val holiday = holidayDetector.detect()
            if (holiday != null) {
                val theme = when (holiday) {
                    Holiday.CHRISTMAS -> CloudTheme.CHRISTMAS
                    Holiday.SPRING_FESTIVAL -> CloudTheme.SPRING_FESTIVAL
                    Holiday.HALLOWEEN -> CloudTheme.HALLOWEEN
                    Holiday.NATIONAL_DAY -> CloudTheme.NATIONAL
                    Holiday.MID_AUTUMN -> CloudTheme.MIDAUTUMN
                    Holiday.DRAGON_BOAT -> CloudTheme.DRAGONBOAT
                    Holiday.LABOR_DAY -> CloudTheme.LABOR
                }
                _currentTheme.value = theme
                saveCachedTheme(theme)
                return@launch
            }

            // 天气
            val weather = weatherRepository.getCurrentWeather(location)
            if (weather != null) {
                val theme = weatherCodeToTheme(weather)
                _currentTheme.value = theme
                saveCachedTheme(theme)
            }
        }
    }

    /** 用户点击白云，触发彩蛋 */
    fun onCloudClicked() {
        // 未授权时，弹出权限提示而不是触发彩蛋
        if (!_hasLocationPermission.value) {
            _showPermissionDialog.value = true
            return
        }

        // 选择彩蛋（避免连续重复）
        val available = EASTER_EGGS.indices.filter { it !in recentEasterIndices }
        val index = if (available.isNotEmpty()) available.random() else EASTER_EGGS.indices.random()

        if (recentEasterIndices.size >= 2) recentEasterIndices.removeFirst()
        recentEasterIndices.addLast(index)

        val egg = EASTER_EGGS[index]
        _easterEggRes.value = egg.animRes
        // 从该动画的候选文案池随机选一条
        _easterMessageRes.value = egg.messageResIds.random()
    }

    /** 权限已授权 */
    fun onPermissionGranted() {
        _hasLocationPermission.value = true
        _showPermissionDialog.value = false
    }

    /** 检查当前是否为夜间（20:00-06:00） */
    fun isNightTime(): Boolean {
        val hour = java.time.LocalTime.now().hour
        return hour >= 20 || hour < 6
    }

    /** 切换夜晚交替状态 */
    fun toggleNightAlternate() {
        if (isNightTime()) {
            _isNightAlternate.value = !_isNightAlternate.value
        }
    }

    /** 获取当前应显示的主题 */
    fun getCurrentDisplayTheme(): CloudTheme {
        return if (isNightTime() && _isNightAlternate.value) {
            CloudTheme.NIGHT
        } else {
            _currentTheme.value
        }
    }

    /** 权限弹窗已取消 */
    fun onPermissionDismissed() {
        _showPermissionDialog.value = false
    }

    /** 触发权限提示弹窗（新手引导完成后调用） */
    fun requestPermissionPrompt() {
        if (!_hasLocationPermission.value) {
            _showPermissionDialog.value = true
        }
    }

    /** 关闭彩蛋 */
    fun onEasterDismissed() {
        _easterEggRes.value = null
        _easterMessageRes.value = null
    }

    private fun loadCachedTheme(): CloudTheme {
        val name = prefs.getString(KEY_THEME, null)
        val time = prefs.getLong(KEY_THEME_TIME, 0)
        if (name != null && System.currentTimeMillis() - time < CACHE_VALID_MS) {
            return runCatching { CloudTheme.valueOf(name) }.getOrNull() ?: CloudTheme.SUNNY
        }
        return CloudTheme.SUNNY
    }

    private fun saveCachedTheme(theme: CloudTheme) {
        prefs.edit()
            .putString(KEY_THEME, theme.name)
            .putLong(KEY_THEME_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun weatherCodeToTheme(weather: WeatherInfo): CloudTheme {
        return when (weather.weatherCode) {
            0, 1 -> CloudTheme.SUNNY
            2 -> CloudTheme.CLOUDY
            3 -> CloudTheme.OVERCAST
            45, 48 -> CloudTheme.MIST
            in 51..67 -> CloudTheme.RAINY
            in 71..86 -> CloudTheme.SNOWY
            in 95..99 -> CloudTheme.THUNDER
            else -> CloudTheme.SUNNY
        }
    }
}
