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

    // 盘搜（pan.useai.sbs）：pansou 同构实例，插件多、结果量大，默认开
    private val _useaiEnabled = MutableStateFlow(true)
    val useaiEnabled: StateFlow<Boolean> = _useaiEnabled.asStateFlow()

    // 云搜（yunso.net）：自有 JSON 结构，免鉴权直连，默认开
    private val _yunsoEnabled = MutableStateFlow(true)
    val yunsoEnabled: StateFlow<Boolean> = _yunsoEnabled.asStateFlow()

    // 阿涛搜（so.ataw.top）：需要 x-miniapp-session 头，逆向接入，默认开
    private val _atawEnabled = MutableStateFlow(true)
    val atawEnabled: StateFlow<Boolean> = _atawEnabled.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.searchSourceDataStore.data.first()
            _pansouEnabled.value = prefs[KEY_PANSOU] ?: true
            _panhubEnabled.value = prefs[KEY_PANHUB] ?: false
            _zresoEnabled.value = prefs[KEY_ZRESO] ?: true
            _useaiEnabled.value = prefs[KEY_USEAI] ?: true
            _yunsoEnabled.value = prefs[KEY_YUNSO] ?: true
            _atawEnabled.value = prefs[KEY_ATAW] ?: true
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

    suspend fun setUseaiEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_USEAI] = enabled
        }
        _useaiEnabled.value = enabled
    }

    suspend fun setYunsoEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_YUNSO] = enabled
        }
        _yunsoEnabled.value = enabled
    }

    suspend fun setAtawEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_ATAW] = enabled
        }
        _atawEnabled.value = enabled
    }

    companion object {
        private val KEY_PANSOU = booleanPreferencesKey("pansou_enabled")
        private val KEY_PANHUB = booleanPreferencesKey("panhub_enabled")
        private val KEY_ZRESO = booleanPreferencesKey("zreso_enabled")
        private val KEY_USEAI = booleanPreferencesKey("useai_enabled")
        private val KEY_YUNSO = booleanPreferencesKey("yunso_enabled")
        private val KEY_ATAW = booleanPreferencesKey("ataw_enabled")
    }
}
