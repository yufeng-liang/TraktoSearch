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

private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "onboarding")

@Singleton
class OnboardingStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val isCompleted: Flow<Boolean> = context.onboardingDataStore.data.map { prefs ->
        prefs[KEY_COMPLETED] ?: false
    }

    suspend fun setCompleted(completed: Boolean) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_COMPLETED] = completed
        }
    }
}
