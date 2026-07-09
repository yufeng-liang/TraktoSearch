package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.ui.theme.MonetAccent
import dagger.hilt.android.qualifiers.ApplicationContext
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

    /** null = 动态壁纸取色（默认） */
    private val _accentColor = MutableStateFlow<MonetAccent?>(null)
    val accentColor: StateFlow<MonetAccent?> = _accentColor.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.themeDataStore.data.first()
            _themeMode.value = prefs[KEY_THEME_MODE] ?: MODE_SYSTEM
            _accentColor.value = prefs[KEY_ACCENT_COLOR]?.let { name ->
                runCatching { MonetAccent.valueOf(name) }.getOrNull()
            }
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
        _themeMode.value = mode
    }

    suspend fun setAccentColor(accent: MonetAccent?) {
        context.themeDataStore.edit { prefs ->
            if (accent == null) prefs.remove(KEY_ACCENT_COLOR)
            else prefs[KEY_ACCENT_COLOR] = accent.name
        }
        _accentColor.value = accent
    }

    companion object {
        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
    }
}
