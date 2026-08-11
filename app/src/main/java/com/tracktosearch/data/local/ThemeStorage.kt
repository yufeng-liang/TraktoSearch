package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import com.tracktosearch.ui.theme.MonetAccent
import com.tracktosearch.ui.theme.VisualEffectMode
import com.tracktosearch.widget.QuickSearchWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme")

@Singleton
class ThemeStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _themeMode = MutableStateFlow(MODE_SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    /** null = 用户主动选择动态壁纸取色；未配置时使用复古票根主题。 */
    private val _accentColor = MutableStateFlow<MonetAccent?>(MonetAccent.VINTAGE_TICKET)
    val accentColor: StateFlow<MonetAccent?> = _accentColor.asStateFlow()

    private val _visualEffectMode = MutableStateFlow(VisualEffectMode.BLUR)
    val visualEffectMode: StateFlow<VisualEffectMode> = _visualEffectMode.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.themeDataStore.data.first()
            _themeMode.value = prefs[KEY_THEME_MODE] ?: MODE_SYSTEM
            _accentColor.value = decodeAccentName(prefs[KEY_ACCENT_COLOR])
            _visualEffectMode.value = VisualEffectMode.fromStorageValue(prefs[KEY_VISUAL_EFFECT_MODE])
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
        _themeMode.value = mode
    }

    suspend fun setAccentColor(accent: MonetAccent?) {
        val previousAccent = _accentColor.value
        context.themeDataStore.edit { prefs ->
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
                // 未添加 Widget 或启动器暂时不可用时，主题设置仍然有效。
            }
        }
    }

    suspend fun setVisualEffectMode(mode: VisualEffectMode) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_VISUAL_EFFECT_MODE] = mode.storageValue
        }
        _visualEffectMode.value = mode
    }

    suspend fun readAccentColorSnapshot(): MonetAccent? {
        val prefs = context.themeDataStore.data.first()
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

    companion object {
        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        private const val DYNAMIC_ACCENT = "dynamic"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val KEY_VISUAL_EFFECT_MODE = stringPreferencesKey("visual_effect_mode")
    }
}
