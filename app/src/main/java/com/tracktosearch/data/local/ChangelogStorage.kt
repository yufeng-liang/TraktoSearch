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
    // v3：更新日志真相源迁到仓库内 CHANGELOG.md 并全量重写。
    // 旧 key 无 TTL、命中即返回且 clear() 无人调用，不换 key 老用户永远读旧日志。
    private val key = stringPreferencesKey("full_changelog_v3")
    private val fullChangelogCompleteKey = booleanPreferencesKey("full_changelog_complete_v3")
    private val lastCheckTsKey = longPreferencesKey("last_update_check_ts")
    private val cachedVersionKey = stringPreferencesKey("cached_latest_version")
    private val cachedChangelogKey = stringPreferencesKey("cached_update_changelog")
    private val cachedHasUpdateKey = booleanPreferencesKey("cached_has_update")
    private val cachedDownloadUrlKey = stringPreferencesKey("cached_download_url")
    private val cachedSha256Key = stringPreferencesKey("cached_sha256")
    private val cachedFileSizeKey = longPreferencesKey("cached_file_size")

    suspend fun getChangelog(): String? {
        return context.changelogDataStore.data.map { it[key] }.first()
    }

    /**
     * 只写正文、不动完整性标记。给「检查更新后把新版本日志预热追加进缓存」用——
     * 那次写入只是局部拼接，不能冒充一次完整拉取的结果。
     */
    suspend fun saveChangelog(text: String) {
        context.changelogDataStore.edit { it[key] = text }
    }

    /** 写正文并标记为完整。只允许由一次成功的全量拉取调用。 */
    suspend fun saveCompleteChangelog(text: String) {
        context.changelogDataStore.edit {
            it[key] = text
            it[fullChangelogCompleteKey] = true
        }
    }

    /**
     * 磁盘上的全量日志是否来自一次完整拉取。
     * 缺省 false 是有意的：修复前被预热追加污染过的缓存没有这个标记，
     * 读作「不完整」→ 重拉一次并覆写，老安装自愈。
     */
    suspend fun isFullChangelogComplete(): Boolean {
        return context.changelogDataStore.data.map { it[fullChangelogCompleteKey] ?: false }.first()
    }

    suspend fun clear() {
        context.changelogDataStore.edit {
            it.remove(key)
            it.remove(fullChangelogCompleteKey)
            it.remove(lastCheckTsKey)
            it.remove(cachedVersionKey)
            it.remove(cachedChangelogKey)
            it.remove(cachedHasUpdateKey)
            it.remove(cachedDownloadUrlKey)
            it.remove(cachedSha256Key)
            it.remove(cachedFileSizeKey)
        }
    }

    /** 上次启动时自动检查更新的时间戳（毫秒），用于 24 小时内不重复请求 */
    suspend fun getLastCheckTimestamp(): Long {
        return context.changelogDataStore.data.map { it[lastCheckTsKey] ?: 0L }.first()
    }

    /**
     * 读取缓存的更新检查结果（24 小时内有效），版本/日志/链接任一缺失返回 null。
     *
     * sha256 与体积按「缺省即空/0」处理而不是判为缓存不完整：清单一向会带，
     * 但 GitHub 降级路径拿不到摘要，若把空摘要当不完整就会每次启动都重拉网络。
     */
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
            downloadUrl = downloadUrl,
            sha256 = prefs[cachedSha256Key].orEmpty(),
            fileSize = prefs[cachedFileSizeKey] ?: 0L
        )
    }

    /** 保存更新检查结果与时间戳（网络请求成功后调用） */
    suspend fun saveCachedUpdateInfo(
        latestVersion: String,
        changelog: String,
        hasUpdate: Boolean,
        downloadUrl: String,
        sha256: String = "",
        fileSize: Long = 0L
    ) {
        context.changelogDataStore.edit {
            it[lastCheckTsKey] = System.currentTimeMillis()
            it[cachedVersionKey] = latestVersion
            it[cachedChangelogKey] = changelog
            it[cachedHasUpdateKey] = hasUpdate
            it[cachedDownloadUrlKey] = downloadUrl
            it[cachedSha256Key] = sha256
            it[cachedFileSizeKey] = fileSize
        }
    }

    data class CachedUpdateInfo(
        val latestVersion: String,
        val changelog: String,
        val hasUpdate: Boolean,
        val downloadUrl: String,
        /** APK 期望 SHA-256；空串表示这次检查没拿到摘要，客户端跳过校验 */
        val sha256: String = "",
        val fileSize: Long = 0L
    )
}
