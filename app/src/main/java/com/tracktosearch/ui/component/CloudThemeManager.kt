package com.tracktosearch.ui.component

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import com.tracktosearch.R
import com.tracktosearch.data.local.SwiftieEggStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.repository.WeatherInfo
import com.tracktosearch.data.repository.WeatherRepository
import com.tracktosearch.ui.screen.swiftie.CloudAction
import com.tracktosearch.ui.screen.swiftie.SwiftieEggController
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.MonetAccent
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
    private val holidayDetector: HolidayDetector,
    private val swiftieEggStorage: SwiftieEggStorage,
    private val themeStorage: ThemeStorage
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

    // 霉粉彩蛋题面是否正在显示。SearchScreen 用它加入 blocking 集合，避免 AI 精灵乱入。
    private val _swiftieEggVisible = MutableStateFlow(false)
    val swiftieEggVisible: StateFlow<Boolean> = _swiftieEggVisible

    val swiftieUnlocked: StateFlow<Boolean> = swiftieEggStorage.unlocked

    // 解题机会是否已消耗：搜索关键词拦截只在未解题时生效
    val swiftieQuizSolved: StateFlow<Boolean> = swiftieEggStorage.quizSolved

    init {
        scope.launch {
            swiftieEggStorage.migrateLegacyNebulaUser(themeStorage.readMeshPresetSnapshot())
            reconcileSwiftieUnlock()
        }
    }

    // 随机去重：记录最近播放过的彩蛋索引
    private val recentEasterIndices = ArrayDeque<Int>(2)

    // 会话级 guard：标记本进程内是否已做过初始主题加载（含位置访问）
    // 防止 SearchScreen 的 LaunchedEffect(Unit) 在导航返回时重新触发 getLastKnownLocation，
    // 进而反复触发系统位置权限使用提示（Android 12+ 的状态栏位置图标）
    @Volatile
    private var themeInitialized: Boolean = false

    /**
     * 初始化主题：仅在本进程内首次调用时执行真正的加载（含位置访问）。
     * 后续调用（如 SearchScreen 因导航返回而 LaunchedEffect 重新执行）会直接复用已加载的主题。
     *
     * 注意：[locationProvider] 只在首次调用时被执行，避免不必要的位置访问。
     * 用户在权限弹窗中授权后，应直接调用 [loadTheme] 而非此方法，以便用真实位置重新加载。
     */
    fun initializeTheme(locationProvider: () -> Location?) {
        if (themeInitialized) return
        themeInitialized = true
        loadTheme(locationProvider())
    }

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

    /**
     * 用户点击白云。分派由 [SwiftieEggController.resolveCloudAction] 决定，
     * 本方法只负责推进计数和落状态。
     *
     * @param onboardingCompleted 新手引导是否已完成。未完成时点击被完全忽略、计数也不推进，
     *   否则用户走完引导第一下就已经不是「第一下」了。
     */
    fun onCloudClicked(onboardingCompleted: Boolean) {
        if (!SwiftieEggController.shouldCountCloudClick(onboardingCompleted)) return

        val countBefore = swiftieEggStorage.cloudClickCount.value
        val action = SwiftieEggController.resolveCloudAction(
            clickCountBefore = countBefore,
            quizSolved = swiftieEggStorage.quizSolved.value,
            onboardingCompleted = onboardingCompleted,
            hasLocationPermission = _hasLocationPermission.value
        )
        scope.launch { swiftieEggStorage.incrementCloudClick() }

        when (action) {
            CloudAction.IGNORED -> Unit
            CloudAction.SWIFTIE_EGG -> _swiftieEggVisible.value = true
            CloudAction.LOCATION_PERMISSION -> _showPermissionDialog.value = true
            CloudAction.RANDOM_LOTTIE -> showRandomEasterEgg()
        }
    }

    /** 供搜索关键词命中、关于页连点等其他入口直接拉起题面。 */
    fun openSwiftieEgg() {
        _swiftieEggVisible.value = true
    }

    fun onSwiftieEggDismissed(solved: Boolean) {
        _swiftieEggVisible.value = false
        // 正常路径在 T1100 已经写过了。这里兜住提前退出的情况（「减少动效」直接给终态、
        // 序列中途被杀等）。
        //
        // 已经 unlocked 就什么都不做：那说明接管早就完成了，没有要补的。**不能**无条件
        // 重跑 —— 存储的两个键是幂等的，但强调色与网格预设不是，用户解锁之后自己改过
        // 主题，重看一次纪念页再退出就会被静默改回 RENOIR + NEBULA
        if (solved && !swiftieEggStorage.unlocked.value) commitSwiftieUnlock()
    }

    /**
     * 主题接管。**必须在扩散铺满全屏的那一帧调用**（`SwiftieTimeline.THEME_COMMIT_AT`），
     * 早于此会露出颜色跳变。不可逆，不保存解锁前旧值（Spec §9）。
     */
    fun commitSwiftieUnlock() {
        scope.launch { applySwiftieUnlock() }
    }

    /**
     * 五个写入的实际顺序。**`markUnlocked` 必须排在 `markQuizSolved` 之前。**
     *
     * 这几个写入不在一个事务里，进程随时可能死在中间（用户在扩散那一帧划掉任务、
     * DataStore 写失败）。两种中间态的代价差别很大：
     *
     * - 先写 `quizSolved`：用户**消耗掉了唯一一次解题机会却没拿到任何东西**，而
     *   `resolveCloudAction` 与搜索关键词拦截都按 `quizSolved` 判断要不要再给题面 ——
     *   于是他永远拉不起彩蛋，死局。
     * - 先写 `unlocked`：用户拿到了星云背景，解题机会还留着。纯赚。
     *
     * 所以顺序是 `unlocked` → `quizSolved` → 三个主题键，越靠后的写入丢了越不痛。
     */
    private suspend fun applySwiftieUnlock() {
        swiftieEggStorage.markUnlocked()
        swiftieEggStorage.markQuizSolved()
        themeStorage.setAccentColor(MonetAccent.RENOIR)
        themeStorage.setMeshPreset(MeshPreset.NEBULA.name)
        themeStorage.setMeshEnabled(true)
    }

    /**
     * 启动时补齐上次没写完的接管。
     *
     * 判据是 `quizSolved && !unlocked`。按 [applySwiftieUnlock] 的顺序，这个组合
     * **只可能**来自「写到一半进程死了」—— 正常路径写完 `unlocked` 才写 `quizSolved`。
     * 所以这里可以放心把整套重跑一遍，包括三个主题键：这个状态下接管从未完成过，
     * 不存在「用户解锁后自己改的主题」会被覆盖的情况。
     */
    private suspend fun reconcileSwiftieUnlock() {
        swiftieEggStorage.awaitReady()
        if (swiftieEggStorage.quizSolved.value && !swiftieEggStorage.unlocked.value) {
            applySwiftieUnlock()
        }
    }

    private fun showRandomEasterEgg() {
        val available = EASTER_EGGS.indices.filter { it !in recentEasterIndices }
        val index = if (available.isNotEmpty()) available.random() else EASTER_EGGS.indices.random()
        if (recentEasterIndices.size >= 2) recentEasterIndices.removeFirst()
        recentEasterIndices.addLast(index)
        val egg = EASTER_EGGS[index]
        _easterEggRes.value = egg.animRes
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
        return when {
            weather.weatherCode == 0 || weather.weatherCode == 1 -> CloudTheme.SUNNY
            weather.weatherCode == 2 -> CloudTheme.CLOUDY
            weather.weatherCode == 3 -> CloudTheme.OVERCAST
            weather.weatherCode in 4..10 || weather.weatherCode in setOf(19, 21, 22) ->
                CloudTheme.RAINY
            weather.weatherCode in 11..12 || weather.weatherCode in 23..25 ->
                CloudTheme.THUNDER
            weather.weatherCode in 13..17 || weather.weatherCode in 26..28 ->
                CloudTheme.SNOWY
            weather.weatherCode in 18..20 ||
                weather.weatherCode in 29..32 ||
                weather.weatherCode == 49 ||
                weather.weatherCode in 53..58 -> CloudTheme.MIST
            else -> CloudTheme.SUNNY
        }
    }
}
