package com.tracktosearch.data.local

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.MonetAccent
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.widget.QuickSearchWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme")

@Singleton
class ThemeStorage private constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, context.themeDataStore, Unit)

    /** 为冷启动时序测试注入可控的 DataStore，生产构造仍使用应用级单例。 */
    internal constructor(
        context: Context,
        dataStore: DataStore<Preferences>
    ) : this(context, dataStore, Unit)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationComplete = CompletableDeferred<Unit>()

    private val _themeMode = MutableStateFlow(MODE_SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    /** null = 用户主动选择动态壁纸取色；未配置时使用复古票根主题。*/
    private val _accentColor = MutableStateFlow<MonetAccent?>(MonetAccent.VINTAGE_TICKET)
    val accentColor: StateFlow<MonetAccent?> = _accentColor.asStateFlow()

    private val _visualEffectMode = MutableStateFlow(VisualEffectMode.BLUR)
    val visualEffectMode: StateFlow<VisualEffectMode> = _visualEffectMode.asStateFlow()

    private val _glassVariant = MutableStateFlow(GlassVariant.CLEAR)
    val glassVariant: StateFlow<GlassVariant> = _glassVariant.asStateFlow()

    /** 自定义色调 ARGB（null = 未设置，非 null = 用户自由调色激活） */
    private val _customAccentArgb = MutableStateFlow<Long?>(null)
    val customAccentArgb: StateFlow<Long?> = _customAccentArgb.asStateFlow()

    // 主页面背景彩色弥散光晕：预设(枚举名)与开关。默认 BLOOM（星云需解锁霉粉彩蛋）
    private val _meshPreset = MutableStateFlow("BLOOM")
    val meshPreset: StateFlow<String> = _meshPreset.asStateFlow()

    // 背景动效默认关闭，用户需要时再手动开启
    private val _meshEnabled = MutableStateFlow(false)
    val meshEnabled: StateFlow<Boolean> = _meshEnabled.asStateFlow()

    init {
        // 预加载 DataStore 首值到 StateFlow，避免 stateIn 默认值抖动。
        scope.launch {
            val prefs = dataStore.data.first()
            _themeMode.value = prefs[KEY_THEME_MODE] ?: MODE_SYSTEM
            _accentColor.value = decodeAccentName(prefs[KEY_ACCENT_COLOR])
            _visualEffectMode.value = VisualEffectMode.fromStorageValue(prefs[KEY_VISUAL_EFFECT_MODE])
            _glassVariant.value = GlassVariant.fromStorageValue(prefs[KEY_GLASS_VARIANT])
            _customAccentArgb.value = prefs[KEY_CUSTOM_ACCENT_ARGB]
            _meshPreset.value = prefs[KEY_MESH_PRESET] ?: "BLOOM"
            _meshEnabled.value = prefs[KEY_MESH_ENABLED] ?: false
            initializationComplete.complete(Unit)
        }.invokeOnCompletion { throwable ->
            if (throwable != null && !initializationComplete.isCompleted) {
                initializationComplete.completeExceptionally(throwable)
            }
        }
    }

    /** 冷启动同步读取已持久化的主题模式（等待 DataStore 首值加载完成）。 */
    suspend fun readThemeModeSnapshot(): String {
        initializationComplete.await()
        return _themeMode.value
    }

    suspend fun setThemeMode(mode: String) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
        _themeMode.value = mode
        // App 内主题模式联动系统 uiMode（API 31+ 内部走 UiModeManager.setApplicationNightMode），
        // 让下次冷启动时系统 Splash 直接按 app 设置选择 values/values-night 资源。
        applyThemeModeToSystem(mode)
    }

    suspend fun setAccentColor(accent: MonetAccent?) {
        initializationComplete.await()
        val previousAccent = _accentColor.value
        dataStore.edit { prefs ->
            prefs[KEY_ACCENT_COLOR] = accent?.name ?: DYNAMIC_ACCENT
        }
        _accentColor.value = accent
        if (previousAccent != accent) {
            // Widget 刷新是附加效果，不能让主题设置因桌面组件状态异常而失败。
            try {
                QuickSearchWidget().updateAll(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 未安装 Widget 或启动器暂不可用时，主题设置仍然有效。
            }
        }
    }

    suspend fun setVisualEffectMode(mode: VisualEffectMode) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            prefs[KEY_VISUAL_EFFECT_MODE] = mode.storageValue
            prefs[KEY_GLASS_VARIANT] = GlassVariant.CLEAR.storageValue
        }
        _visualEffectMode.value = mode
        _glassVariant.value = GlassVariant.CLEAR
    }

    suspend fun setVisualEffectSelection(
        mode: VisualEffectMode,
        glassVariant: GlassVariant
    ) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            prefs[KEY_VISUAL_EFFECT_MODE] = mode.storageValue
            prefs[KEY_GLASS_VARIANT] = GlassVariant.CLEAR.storageValue
        }
        _visualEffectMode.value = mode
        _glassVariant.value = GlassVariant.CLEAR
    }

    suspend fun setCustomAccent(argb: Long?) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            if (argb != null) {
                prefs[KEY_CUSTOM_ACCENT_ARGB] = argb
            } else {
                prefs.remove(KEY_CUSTOM_ACCENT_ARGB)
            }
        }
        _customAccentArgb.value = argb
    }

    suspend fun readAccentColorSnapshot(): MonetAccent? {
        initializationComplete.await()
        val prefs = dataStore.data.first()
        return decodeAccentName(prefs[KEY_ACCENT_COLOR])
    }

    suspend fun readMeshPresetSnapshot(): String {
        initializationComplete.await()
        val prefs = dataStore.data.first()
        return prefs[KEY_MESH_PRESET] ?: MeshPreset.BLOOM.name
    }

    /**
     * 存的是枚举名字符串，所以删枚举项会读到认不出的名字。
     *
     * [RETIRED_ACCENTS] 把删掉的色调映射到色相最近的幸存者（差 2°-9°，
     * 用户基本看不出换了），而不是一律掉回默认的复古票根 ——
     * 从「麦田金黄」跳到棕色是能一眼看出来的，从它跳到「干草堆金」不会。
     * 迁移是静默的，不写回存储：下次用户主动改色调时自然会覆盖掉旧值。
     */
    private fun decodeAccentName(name: String?): MonetAccent? {
        return when (name) {
            DYNAMIC_ACCENT -> null
            null -> MonetAccent.VINTAGE_TICKET
            else -> RETIRED_ACCENTS[name]
                ?: runCatching { MonetAccent.valueOf(name) }
                    .getOrElse { MonetAccent.VINTAGE_TICKET }
        }
    }

    suspend fun setMeshPreset(preset: String) {
        initializationComplete.await()
        dataStore.edit { prefs -> prefs[KEY_MESH_PRESET] = preset }
        _meshPreset.value = preset
    }

    suspend fun setMeshEnabled(enabled: Boolean) {
        initializationComplete.await()
        dataStore.edit { prefs -> prefs[KEY_MESH_ENABLED] = enabled }
        _meshEnabled.value = enabled
    }

    companion object {
        /**
         * 把 app 内主题模式应用到系统 uiMode，让系统 Splash（starting window）在冷启动时
         * 按 app 自己的深浅设置选资源，而不是只看系统夜间模式。
         *
         * dark/light 会覆盖 app 进程的 uiMode（API 31+ 由 AppCompat 走
         * UiModeManager.setApplicationNightMode），system 则恢复跟随系统。
         */
        fun applyThemeModeToSystem(mode: String) {
            val appCompatMode = when (mode) {
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(appCompatMode)
        }

        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        private const val DYNAMIC_ACCENT = "dynamic"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val KEY_VISUAL_EFFECT_MODE = stringPreferencesKey("visual_effect_mode")
        private val KEY_GLASS_VARIANT = stringPreferencesKey("glass_variant")
        private val KEY_CUSTOM_ACCENT_ARGB = longPreferencesKey("custom_accent_argb")
        private val KEY_MESH_PRESET = stringPreferencesKey("mesh_preset")
        private val KEY_MESH_ENABLED = booleanPreferencesKey("mesh_enabled")

        /**
         * 已下线的色调 -> 色相最近的幸存者。见 [decodeAccentName]。
         *
         * 这三个当初是重复色：括号里是删除前后两者的 Lab 色相差，
         * 同彩度同明度加上这个色差，肉眼分不出来，所以迁移过去不算换主题。
         */
        private val RETIRED_ACCENTS = mapOf(
            "WHEAT_FIELD" to MonetAccent.HAYSTACK,             // 82° -> 91°
            "ROUEN_CATHEDRAL" to MonetAccent.WATER_LILY,       // 302° -> 304°
            "WATER_LILY_GREEN" to MonetAccent.JAPANESE_BRIDGE, // 154° -> 152°
        )
    }
}
