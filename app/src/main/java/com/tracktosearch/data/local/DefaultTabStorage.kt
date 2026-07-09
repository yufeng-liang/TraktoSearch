package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

private val Context.defaultTabDataStore: DataStore<Preferences> by preferencesDataStore(name = "default_tab")

@Singleton
class DefaultTabStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _defaultTab = MutableStateFlow(DEFAULT_TAB_SEARCH)
    val defaultTab: StateFlow<Int> = _defaultTab.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.defaultTabDataStore.data.first()
            _defaultTab.value = prefs[KEY_DEFAULT_TAB] ?: DEFAULT_TAB_SEARCH
        }
    }

    suspend fun setDefaultTab(tab: Int) {
        context.defaultTabDataStore.edit { prefs ->
            prefs[KEY_DEFAULT_TAB] = tab
        }
        _defaultTab.value = tab
    }

    companion object {
        const val DEFAULT_TAB_SEARCH = 0
        const val DEFAULT_TAB_DISCOVER = 1
        const val DEFAULT_TAB_PROFILE = 2
        private val KEY_DEFAULT_TAB = intPreferencesKey("default_tab")
    }
}
