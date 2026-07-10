package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.searchSourceDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_sources")

@Singleton
class SearchSourceStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pansouEnabled = MutableStateFlow(true)
    val pansouEnabled: StateFlow<Boolean> = _pansouEnabled.asStateFlow()

    private val _panhubEnabled = MutableStateFlow(false)
    val panhubEnabled: StateFlow<Boolean> = _panhubEnabled.asStateFlow()

    private val _zresoEnabled = MutableStateFlow(true)
    val zresoEnabled: StateFlow<Boolean> = _zresoEnabled.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.searchSourceDataStore.data.first()
            _pansouEnabled.value = prefs[KEY_PANSOU] ?: true
            _panhubEnabled.value = prefs[KEY_PANHUB] ?: false
            _zresoEnabled.value = prefs[KEY_ZRESO] ?: true
        }
    }

    suspend fun setPansouEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_PANSOU] = enabled
        }
        _pansouEnabled.value = enabled
    }

    suspend fun setPanhubEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_PANHUB] = enabled
        }
        _panhubEnabled.value = enabled
    }

    suspend fun setZresoEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_ZRESO] = enabled
        }
        _zresoEnabled.value = enabled
    }

    companion object {
        private val KEY_PANSOU = booleanPreferencesKey("pansou_enabled")
        private val KEY_PANHUB = booleanPreferencesKey("panhub_enabled")
        private val KEY_ZRESO = booleanPreferencesKey("zreso_enabled")
    }
}
