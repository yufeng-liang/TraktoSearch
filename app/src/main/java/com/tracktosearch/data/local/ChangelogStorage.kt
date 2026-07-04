package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.changelogDataStore: DataStore<Preferences> by preferencesDataStore(name = "changelog_cache")

@Singleton
class ChangelogStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val key = stringPreferencesKey("full_changelog")

    suspend fun getChangelog(): String? {
        return context.changelogDataStore.data.map { it[key] }.first()
    }

    suspend fun saveChangelog(text: String) {
        context.changelogDataStore.edit { it[key] = text }
    }

    suspend fun clear() {
        context.changelogDataStore.edit { it.remove(key) }
    }
}
