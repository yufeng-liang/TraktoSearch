package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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
        val KEY_THEME_SELECTION_COMPLETED = booleanPreferencesKey("theme_selection_completed")
    }

    val isCompleted: Flow<Boolean> = context.onboardingDataStore.data.map { prefs ->
        prefs[KEY_COMPLETED] ?: false
    }.distinctUntilChanged()

    @Deprecated(
        "引导阶段已不再弹主题选择弹窗。key 保留不删，避免对存量数据多做一次迁移。",
        level = DeprecationLevel.WARNING
    )
    val isThemeSelectionCompleted: Flow<Boolean> = context.onboardingDataStore.data.map { prefs ->
        prefs[KEY_THEME_SELECTION_COMPLETED] ?: false
    }.distinctUntilChanged()

    suspend fun setCompleted(completed: Boolean) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_COMPLETED] = completed
        }
    }

    @Deprecated(
        "引导阶段已不再弹主题选择弹窗。key 保留不删，避免对存量数据多做一次迁移。",
        level = DeprecationLevel.WARNING
    )
    suspend fun setThemeSelectionCompleted(completed: Boolean) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_THEME_SELECTION_COMPLETED] = completed
        }
    }
}
