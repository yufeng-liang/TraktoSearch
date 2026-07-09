package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.tracktosearch.data.remote.panhub.PanHubConfig
import com.tracktosearch.data.remote.panhub.PanHubChannel
import com.tracktosearch.data.remote.panhub.PanHubPlugin
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

private val Context.panHubConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "panhub_config")

@Singleton
class PanHubConfigStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _config = MutableStateFlow(PanHubConfig())
    val config: StateFlow<PanHubConfig> = _config.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.panHubConfigDataStore.data.first()
            _config.value = readConfig(prefs)
        }
    }

    private fun readConfig(prefs: Preferences): PanHubConfig {
        val concurrency = prefs[KEY_CONCURRENCY] ?: PanHubConfig.CONCURRENCY_DEFAULT
        val timeoutMs = prefs[KEY_TIMEOUT_MS] ?: PanHubConfig.TIMEOUT_DEFAULT
        val pluginsStr = prefs[KEY_ENABLED_PLUGINS] ?: ""
        val channelsStr = prefs[KEY_ENABLED_CHANNELS] ?: ""

        val enabledPlugins = if (pluginsStr.isBlank()) {
            PanHubPlugin.entries.map { it.id }.toSet()
        } else {
            pluginsStr.split(",").filter { it.isNotEmpty() }.toSet()
        }

        val enabledChannels = if (channelsStr.isBlank()) {
            PanHubChannel.entries.map { it.id }.toSet()
        } else {
            channelsStr.split(",").filter { it.isNotEmpty() }.toSet()
        }

        return PanHubConfig(
            concurrency = concurrency.coerceIn(PanHubConfig.CONCURRENCY_MIN, PanHubConfig.CONCURRENCY_MAX),
            timeoutMs = timeoutMs,
            enabledPlugins = enabledPlugins,
            enabledChannels = enabledChannels
        )
    }

    suspend fun setConcurrency(value: Int) {
        context.panHubConfigDataStore.edit { prefs ->
            prefs[KEY_CONCURRENCY] = value.coerceIn(PanHubConfig.CONCURRENCY_MIN, PanHubConfig.CONCURRENCY_MAX)
        }
        _config.value = _config.value.copy(
            concurrency = value.coerceIn(PanHubConfig.CONCURRENCY_MIN, PanHubConfig.CONCURRENCY_MAX)
        )
    }

    suspend fun setTimeoutMs(value: Int) {
        context.panHubConfigDataStore.edit { prefs ->
            prefs[KEY_TIMEOUT_MS] = value
        }
        _config.value = _config.value.copy(timeoutMs = value)
    }

    suspend fun setEnabledPlugins(plugins: Set<String>) {
        context.panHubConfigDataStore.edit { prefs ->
            prefs[KEY_ENABLED_PLUGINS] = plugins.joinToString(",")
        }
        _config.value = _config.value.copy(enabledPlugins = plugins)
    }

    suspend fun setEnabledChannels(channels: Set<String>) {
        context.panHubConfigDataStore.edit { prefs ->
            prefs[KEY_ENABLED_CHANNELS] = channels.joinToString(",")
        }
        _config.value = _config.value.copy(enabledChannels = channels)
    }

    companion object {
        private val KEY_CONCURRENCY = intPreferencesKey("panhub_concurrency")
        private val KEY_TIMEOUT_MS = intPreferencesKey("panhub_timeout_ms")
        private val KEY_ENABLED_PLUGINS = stringPreferencesKey("panhub_enabled_plugins")
        private val KEY_ENABLED_CHANNELS = stringPreferencesKey("panhub_enabled_channels")
    }
}
