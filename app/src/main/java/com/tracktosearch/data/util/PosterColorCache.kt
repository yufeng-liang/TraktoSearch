package com.tracktosearch.data.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.posterColorDataStore by preferencesDataStore(name = "poster_color_cache")

/**
 * 海报主色调持久化缓存。
 * - key: posterUrl(海报图 URL)
 * - value: ARGB Long(海报主色调)
 * - TTL: 永久(海报颜色不会变,符合 AGENTS.md 持久化缓存原则)
 *
 * 内存一级 + DataStore 二级结构,启动时异步加载到内存。
 */
@Singleton
class PosterColorCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val memoryCache = mutableMapOf<String, Long>()

    suspend fun getColor(posterUrl: String): Long? = withContext(Dispatchers.IO) {
        memoryCache[posterUrl] ?: run {
            val ds = context.posterColorDataStore.data.first()
            val v = ds[longPreferencesKey(posterUrl)]
            if (v != null) {
                memoryCache[posterUrl] = v
                v
            } else null
        }
    }

    suspend fun putColor(posterUrl: String, argb: Long) = withContext(Dispatchers.IO) {
        memoryCache[posterUrl] = argb
        val key = longPreferencesKey(posterUrl)
        context.posterColorDataStore.edit { it[key] = argb }
    }
}
