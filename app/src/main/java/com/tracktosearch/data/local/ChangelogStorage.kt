package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
    private val lastCheckTsKey = longPreferencesKey("last_update_check_ts")
    private val cachedVersionKey = stringPreferencesKey("cached_latest_version")
    private val cachedChangelogKey = stringPreferencesKey("cached_update_changelog")
    private val cachedHasUpdateKey = booleanPreferencesKey("cached_has_update")
    private val cachedDownloadUrlKey = stringPreferencesKey("cached_download_url")

    suspend fun getChangelog(): String? {
        return context.changelogDataStore.data.map { it[key] }.first()
    }

    suspend fun saveChangelog(text: String) {
        context.changelogDataStore.edit { it[key] = text }
    }

    suspend fun clear() {
        context.changelogDataStore.edit {
            it.remove(key)
            it.remove(lastCheckTsKey)
            it.remove(cachedVersionKey)
            it.remove(cachedChangelogKey)
            it.remove(cachedHasUpdateKey)
            it.remove(cachedDownloadUrlKey)
        }
    }

    /** 上次启动时自动检查更新的时间戳（毫秒），用于 24 小时内不重复请求 */
    suspend fun getLastCheckTimestamp(): Long {
        return context.changelogDataStore.data.map { it[lastCheckTsKey] ?: 0L }.first()
    }

    /** 读取缓存的更新检查结果（24 小时内有效），任一字段缺失返回 null */
    suspend fun getCachedUpdateInfo(): CachedUpdateInfo? {
        val prefs = context.changelogDataStore.data.first()
        val version = prefs[cachedVersionKey] ?: return null
        val changelog = prefs[cachedChangelogKey] ?: return null
        val hasUpdate = prefs[cachedHasUpdateKey] ?: return null
        val downloadUrl = prefs[cachedDownloadUrlKey] ?: return null
        return CachedUpdateInfo(
            latestVersion = version,
            changelog = changelog,
            hasUpdate = hasUpdate,
            downloadUrl = downloadUrl
        )
    }

    /** 保存更新检查结果与时间戳（网络请求成功后调用） */
    suspend fun saveCachedUpdateInfo(
        latestVersion: String,
        changelog: String,
        hasUpdate: Boolean,
        downloadUrl: String
    ) {
        context.changelogDataStore.edit {
            it[lastCheckTsKey] = System.currentTimeMillis()
            it[cachedVersionKey] = latestVersion
            it[cachedChangelogKey] = changelog
            it[cachedHasUpdateKey] = hasUpdate
            it[cachedDownloadUrlKey] = downloadUrl
        }
    }

    data class CachedUpdateInfo(
        val latestVersion: String,
        val changelog: String,
        val hasUpdate: Boolean,
        val downloadUrl: String
    )
}
