package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme")

@Singleton
class ThemeStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 同步获取当前配置（用于 StateFlow 初始值，避免加载跳动） */
    fun getCurrentValueSync(): String = runBlocking {
        themeMode.first()
    }

    val themeMode: Flow<String> = context.themeDataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: MODE_SYSTEM
    }

    suspend fun setThemeMode(mode: String) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode
        }
    }

    companion object {
        const val MODE_SYSTEM = "system"
        const val MODE_DARK = "dark"
        const val MODE_LIGHT = "light"
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }
}
