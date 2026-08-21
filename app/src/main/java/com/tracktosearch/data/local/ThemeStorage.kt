package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import com.tracktosearch.ui.theme.GlassVariant
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

    private val _visualEffectMode = MutableStateFlow(VisualEffectMode.GLASS)
    val visualEffectMode: StateFlow<VisualEffectMode> = _visualEffectMode.asStateFlow()

    private val _glassVariant = MutableStateFlow(GlassVariant.CLEAR)
    val glassVariant: StateFlow<GlassVariant> = _glassVariant.asStateFlow()

    /** 自定义色调 ARGB（null = 未设置，非 null = 用户自由调色激活） */
    private val _customAccentArgb = MutableStateFlow<Long?>(null)
    val customAccentArgb: StateFlow<Long?> = _customAccentArgb.asStateFlow()

    // 主页面背景彩色弥散光晕：预设(枚举名)与开关
    private val _meshPreset = MutableStateFlow("AURORA")
    val meshPreset: StateFlow<String> = _meshPreset.asStateFlow()

    private val _meshEnabled = MutableStateFlow(true)
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
            _meshPreset.value = prefs[KEY_MESH_PRESET] ?: "AURORA"
            _meshEnabled.value = prefs[KEY_MESH_ENABLED] ?: true
            initializationComplete.complete(Unit)
        }.invokeOnCompletion { throwable ->
            if (throwable != null && !initializationComplete.isCompleted) {
                initializationComplete.completeExceptionally(throwable)
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        initializationComplete.await()
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
        _themeMode.value = mode
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

    private fun decodeAccentName(name: String?): MonetAccent? {
        return when (name) {
            DYNAMIC_ACCENT -> null
            null -> MonetAccent.VINTAGE_TICKET
            else -> runCatching { MonetAccent.valueOf(name) }
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
    }
}
