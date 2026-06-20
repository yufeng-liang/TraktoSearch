package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.notificationDataStore: DataStore<Preferences> by preferencesDataStore(name = "notification_settings")

@Singleton
class NotificationStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 通知总开关 */
    val enabled: Flow<Boolean> = context.notificationDataStore.data.map { prefs ->
        prefs[KEY_ENABLED] ?: false
    }

    /** 上映提醒（电影即将上映/已上映） */
    val releaseReminderEnabled: Flow<Boolean> = context.notificationDataStore.data.map { prefs ->
        prefs[KEY_RELEASE] ?: true
    }

    /** 新季开播提醒（电视剧新一季） */
    val newSeasonReminderEnabled: Flow<Boolean> = context.notificationDataStore.data.map { prefs ->
        prefs[KEY_NEW_SEASON] ?: true
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
        }
    }

    suspend fun setReleaseReminderEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_RELEASE] = enabled
        }
    }

    suspend fun setNewSeasonReminderEnabled(enabled: Boolean) {
        context.notificationDataStore.edit { prefs ->
            prefs[KEY_NEW_SEASON] = enabled
        }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("notification_enabled")
        private val KEY_RELEASE = booleanPreferencesKey("release_reminder")
        private val KEY_NEW_SEASON = booleanPreferencesKey("new_season_reminder")
    }
}
