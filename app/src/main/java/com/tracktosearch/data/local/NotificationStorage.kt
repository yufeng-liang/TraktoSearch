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

private val Context.notificationDataStore: DataStore<Preferences> by preferencesDataStore(name = "notification_settings")

@Singleton
class NotificationStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 通知总开关 */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 上映提醒（电影即将上映/已上映） */
    private val _releaseReminderEnabled = MutableStateFlow(true)
    val releaseReminderEnabled: StateFlow<Boolean> = _releaseReminderEnabled.asStateFlow()

    /** 新季开播提醒（电视剧新一季） */
    private val _newSeasonReminderEnabled = MutableStateFlow(true)
    val newSeasonReminderEnabled: StateFlow<Boolean> = _newSeasonReminderEnabled.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.notificationDataStore.data.first()
            _enabled.value = prefs[KEY_ENABLED] ?: false
            _releaseReminderEnabled.value = prefs[KEY_RELEASE] ?: true
            _newSeasonReminderEnabled.value = prefs[KEY_NEW_SEASON] ?: true
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
        }
        _enabled.value = enabled
    }

    suspend fun setReleaseReminderEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_RELEASE] = enabled
        }
        _releaseReminderEnabled.value = enabled
    }

    suspend fun setNewSeasonReminderEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_NEW_SEASON] = enabled
        }
        _newSeasonReminderEnabled.value = enabled
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_RELEASE = booleanPreferencesKey("release_reminder")
        private val KEY_NEW_SEASON = booleanPreferencesKey("new_season_reminder")
    }
}
