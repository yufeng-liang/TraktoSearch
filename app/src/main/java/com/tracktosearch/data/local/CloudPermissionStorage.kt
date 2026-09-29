package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore

class CloudPermissionStorage(private val context: Context) {
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "cloud_permission")
        private val KEY_DISMISSED = booleanPreferencesKey("cloud_permission_dismissed")
    }

    suspend fun setDismissed(dismissed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DISMISSED] = dismissed
        }
    }
}
