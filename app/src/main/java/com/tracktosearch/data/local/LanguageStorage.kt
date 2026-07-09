package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

private val Context.languageDataStore: DataStore<Preferences> by preferencesDataStore(name = "language_settings")

@Singleton
class LanguageStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_CHINESE = "zh"
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_JAPANESE = "ja"
        const val LANGUAGE_KOREAN = "ko"
        private val KEY_LANGUAGE = stringPreferencesKey("language")
    }

    private val _language = MutableStateFlow(LANGUAGE_SYSTEM)
    val language: StateFlow<String> = _language.asStateFlow()

    init {
        // 预加载:从 DataStore 读取首值填入 StateFlow,消除 stateIn 默认值跳变
        scope.launch {
            val prefs = context.languageDataStore.data.first()
            _language.value = prefs[KEY_LANGUAGE] ?: LANGUAGE_SYSTEM
        }
    }

    suspend fun setLanguage(language: String) {
        context.languageDataStore.edit { prefs ->
            prefs[KEY_LANGUAGE] = language
        }
        _language.value = language
    }
}
