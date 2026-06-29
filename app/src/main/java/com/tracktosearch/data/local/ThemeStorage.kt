package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.ui.theme.MonetAccent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme")

@Singleton
class ThemeStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val themeMode: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: MODE_SYSTEM
    }.distinctUntilChanged()

    /** null = 动态壁纸取色（默认） */
    val accentColor: Flow<MonetAccent?> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_ACCENT_COLOR]?.let { name ->
            runCatching { MonetAccent.valueOf(name) }.getOrNull()
        }
    }.distinctUntilChanged()

    suspend fun setThemeMode(mode: String) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
    }

    suspend fun setAccentColor(accent: MonetAccent?) {
        context.themeDataStore.edit { prefs ->
            if (accent == null) prefs.remove(KEY_ACCENT_COLOR)
            else prefs[KEY_ACCENT_COLOR] = accent.name
        }
    }

    companion object {
        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
    }
}
