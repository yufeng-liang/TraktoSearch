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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.panHubConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "panhub_config")

@Singleton
class PanHubConfigStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val config: Flow<PanHubConfig> = context.panHubConfigDataStore.data.map { prefs ->
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

        PanHubConfig(
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
    }

    suspend fun setTimeoutMs(value: Int) {
        context.panHubConfigDataStore.edit { prefs ->
            prefs[KEY_TIMEOUT_MS] = value
        }
    }

    suspend fun setEnabledPlugins(plugins: Set<String>) {
        context.panHubConfigDataStore.edit { prefs ->
            prefs[KEY_ENABLED_PLUGINS] = plugins.joinToString(",")
        }
    }

    suspend fun setEnabledChannels(channels: Set<String>) {
        context.panHubConfigDataStore.edit { prefs ->
            prefs[KEY_ENABLED_CHANNELS] = channels.joinToString(",")
        }
    }

    companion object {
        private val KEY_CONCURRENCY = intPreferencesKey("panhub_concurrency")
        private val KEY_TIMEOUT_MS = intPreferencesKey("panhub_timeout_ms")
        private val KEY_ENABLED_PLUGINS = stringPreferencesKey("panhub_enabled_plugins")
        private val KEY_ENABLED_CHANNELS = stringPreferencesKey("panhub_enabled_channels")
    }
}
