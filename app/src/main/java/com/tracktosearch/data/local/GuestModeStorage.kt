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

private val Context.guestDataStore: DataStore<Preferences> by preferencesDataStore(name = "guest")

@Singleton
class GuestModeStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_IS_GUEST = booleanPreferencesKey("is_guest_mode")
    }

    val isGuestMode: Flow<Boolean> = context.guestDataStore.data.map { prefs ->
        prefs[KEY_IS_GUEST] ?: false
    }

    suspend fun setGuestMode(isGuest: Boolean) {
        context.guestDataStore.edit { prefs ->
            prefs[KEY_IS_GUEST] = isGuest
        }
    }
}
