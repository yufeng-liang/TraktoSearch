package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.defaultTabDataStore: DataStore<Preferences> by preferencesDataStore(name = "default_tab")

@Singleton
class DefaultTabStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 同步获取当前配置（用于 StateFlow 初始值，避免加载跳动） */
    fun getCurrentValueSync(): Int = runBlocking {
        defaultTab.first()
    }

    val defaultTab: Flow<Int> = context.defaultTabDataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_TAB] ?: DEFAULT_TAB_SEARCH
    }

    suspend fun setDefaultTab(tab: Int) {
        context.defaultTabDataStore.edit { prefs ->
            prefs[KEY_DEFAULT_TAB] = tab
        }
    }

    companion object {
        const val DEFAULT_TAB_SEARCH = 0
        const val DEFAULT_TAB_DISCOVER = 1
        const val DEFAULT_TAB_PROFILE = 2
        private val KEY_DEFAULT_TAB = intPreferencesKey("default_tab")
    }
}
