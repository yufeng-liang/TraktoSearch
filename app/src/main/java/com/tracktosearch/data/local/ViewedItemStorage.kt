package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.viewedDataStore: DataStore<Preferences> by preferencesDataStore(name = "viewed")

@Singleton
class ViewedItemStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_VIEWED_URLS = stringSetPreferencesKey("viewed_urls")
    }

    @Volatile
    private var cachedUrls: Set<String>? = null

    val viewedUrls: Flow<Set<String>> = context.viewedDataStore.data.map { prefs ->
        prefs[KEY_VIEWED_URLS] ?: emptySet()
    }

    suspend fun getviewedUrls(): Set<String> {
        cachedUrls?.let { return it }
        val urls = context.viewedDataStore.data.map { it[KEY_VIEWED_URLS] ?: emptySet() }.first()
        cachedUrls = urls
        return urls
    }

    fun isViewedSync(url: String): Boolean {
        return cachedUrls?.contains(url) == true
    }

    suspend fun markViewed(url: String) {
        context.viewedDataStore.edit { prefs ->
            val current = prefs[KEY_VIEWED_URLS] ?: emptySet()
            prefs[KEY_VIEWED_URLS] = current + url
        }
        cachedUrls = (cachedUrls ?: emptySet()) + url
    }
}
