package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchSourceDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_sources")

@Singleton
class SearchSourceStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 同步获取当前配置（用于 StateFlow 初始值，避免加载跳动） */
    fun getPansouEnabledSync(): Boolean = runBlocking {
        pansouEnabled.first()
    }

    fun getPanhubEnabledSync(): Boolean = runBlocking {
        panhubEnabled.first()
    }

    fun getZresoEnabledSync(): Boolean = runBlocking {
        zresoEnabled.first()
    }

    val pansouEnabled: Flow<Boolean> = context.searchSourceDataStore.data.map { prefs ->
        prefs[KEY_PANSOU] ?: true
    }

    val panhubEnabled: Flow<Boolean> = context.searchSourceDataStore.data.map { prefs ->
        prefs[KEY_PANHUB] ?: true
    }

    val zresoEnabled: Flow<Boolean> = context.searchSourceDataStore.data.map { prefs ->
        prefs[KEY_ZRESO] ?: true
    }

    suspend fun setPansouEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_PANSOU] = enabled
        }
    }

    suspend fun setPanhubEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_PANHUB] = enabled
        }
    }

    suspend fun setZresoEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_ZRESO] = enabled
        }
    }

    companion object {
        private val KEY_PANSOU = booleanPreferencesKey("pansou_enabled")
        private val KEY_PANHUB = booleanPreferencesKey("panhub_enabled")
        private val KEY_ZRESO = booleanPreferencesKey("zreso_enabled")
    }
}
