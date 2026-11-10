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

    // zreso（zreso.cn）：需要人工/自动人机验证，且有 IP 日限，默认关
    private val _zresoEnabled = MutableStateFlow(false)
    val zresoEnabled: StateFlow<Boolean> = _zresoEnabled.asStateFlow()

    // 盘搜（pan.useai.sbs）：pansou 同构实例，结果与 PanSou 重复且偶尔很慢，默认关
    private val _useaiEnabled = MutableStateFlow(false)
    val useaiEnabled: StateFlow<Boolean> = _useaiEnabled.asStateFlow()

    // 云搜（yunso.net）：自有 JSON 结构，免鉴权直连，默认开
    private val _yunsoEnabled = MutableStateFlow(true)
    val yunsoEnabled: StateFlow<Boolean> = _yunsoEnabled.asStateFlow()

    // 阿涛搜（so.ataw.top）：需要 x-miniapp-session 头，逆向接入，默认开
    private val _atawEnabled = MutableStateFlow(true)
    val atawEnabled: StateFlow<Boolean> = _atawEnabled.asStateFlow()

    // 影盘（melost.cn）：POST JSON 接口，结果多、带文件清单，默认开
    private val _melostEnabled = MutableStateFlow(true)
    val melostEnabled: StateFlow<Boolean> = _melostEnabled.asStateFlow()

    // 盘小子（pan.xiaozi.cc）：SSR RSC payload 解析接入，以夸克为主但量少，默认关
    private val _panxiaoziEnabled = MutableStateFlow(false)
    val panxiaoziEnabled: StateFlow<Boolean> = _panxiaoziEnabled.asStateFlow()

    // 宝藏郎（baozang.asia）：免鉴权但响应慢（10-25s），默认关
    private val _baoysEnabled = MutableStateFlow(false)
    val baoysEnabled: StateFlow<Boolean> = _baoysEnabled.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.searchSourceDataStore.data.first()
            _pansouEnabled.value = prefs[KEY_PANSOU] ?: true
            _panhubEnabled.value = prefs[KEY_PANHUB] ?: false
            _zresoEnabled.value = prefs[KEY_ZRESO] ?: false
            _useaiEnabled.value = prefs[KEY_USEAI] ?: false
            _yunsoEnabled.value = prefs[KEY_YUNSO] ?: true
            _atawEnabled.value = prefs[KEY_ATAW] ?: true
            _melostEnabled.value = prefs[KEY_MELOST] ?: true
            _panxiaoziEnabled.value = prefs[KEY_PANXIAOZI] ?: false
            _baoysEnabled.value = prefs[KEY_BAOYS] ?: false
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

    suspend fun setMelostEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_MELOST] = enabled
        }
        _melostEnabled.value = enabled
    }

    suspend fun setPanxiaoziEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_PANXIAOZI] = enabled
        }
        _panxiaoziEnabled.value = enabled
    }

    suspend fun setBaoysEnabled(enabled: Boolean) {
        context.searchSourceDataStore.edit { prefs ->
            prefs[KEY_BAOYS] = enabled
        }
        _baoysEnabled.value = enabled
    }

    companion object {
        private val KEY_PANSOU = booleanPreferencesKey("pansou_enabled")
        private val KEY_PANHUB = booleanPreferencesKey("panhub_enabled")
        private val KEY_ZRESO = booleanPreferencesKey("zreso_enabled")
        private val KEY_USEAI = booleanPreferencesKey("useai_enabled")
        private val KEY_YUNSO = booleanPreferencesKey("yunso_enabled")
        private val KEY_ATAW = booleanPreferencesKey("ataw_enabled")
        private val KEY_MELOST = booleanPreferencesKey("melost_enabled")
        private val KEY_PANXIAOZI = booleanPreferencesKey("panxiaozi_enabled")
        private val KEY_BAOYS = booleanPreferencesKey("baoys_enabled")
    }
}
