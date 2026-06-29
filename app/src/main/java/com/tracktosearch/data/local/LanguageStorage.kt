package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.languageDataStore: DataStore<Preferences> by preferencesDataStore(name = "language_settings")

@Singleton
class LanguageStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_CHINESE = "zh"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_JAPANESE = "ja"
        const val LANGUAGE_KOREAN = "ko"
        private val KEY_LANGUAGE = stringPreferencesKey("language")
    }

    val language: Flow<String> = context.languageDataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: LANGUAGE_SYSTEM
    }.distinctUntilChanged()

    suspend fun setLanguage(language: String) {
        context.languageDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language
        }
    }
}
