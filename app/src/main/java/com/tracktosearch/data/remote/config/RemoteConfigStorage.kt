package com.tracktosearch.data.remote.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "remote_config_cache")

/**
 * 远程配置 DataStore 持久化层。
 *
 * 存储内容:
 * - 配置 JSON 字符串(内存缓存 + DataStore 二级结构,App 重启后可恢复)
 * - 上次拉取时间戳(用于 24h 缓存判断)
 * - schema 版本号(用于数据格式变更时让旧缓存自动失效)
 *
 * key 加 `_v1` 后缀,数据格式变更时通过版本号让旧缓存自动失效。
 */
@Singleton
class RemoteConfigStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_CONFIG_JSON_V1 = "config_json_v1"
        private const val KEY_LAST_FETCH_TS_V1 = "last_fetch_ts_v1"
        private const val KEY_SCHEMA_VERSION_V1 = "schema_version_v1"
    }

    private val configJsonKey = stringPreferencesKey(KEY_CONFIG_JSON_V1)
    private val lastFetchTsKey = longPreferencesKey(KEY_LAST_FETCH_TS_V1)
    private val schemaVersionKey = intPreferencesKey(KEY_SCHEMA_VERSION_V1)

    /** 读取缓存的配置 JSON。无缓存返回 null。 */
    suspend fun getCachedConfigJson(): String? {
        return context.remoteConfigDataStore.data.map { it[configJsonKey] }.first()
    }

    /** 读取上次拉取时间戳(毫秒)。无记录返回 0。 */
    suspend fun getLastFetchTimestamp(): Long {
        return context.remoteConfigDataStore.data.map { it[lastFetchTsKey] ?: 0L }.first()
    }

    /** 读取缓存的 schema 版本号。无记录返回 null。 */
    suspend fun getCachedSchemaVersion(): Int? {
        return context.remoteConfigDataStore.data.map { it[schemaVersionKey] }.first()
    }

    /** 保存配置 + 时间戳 + schema 版本号(原子写入)。 */
    suspend fun saveConfig(json: String, schemaVersion: Int) {
        context.remoteConfigDataStore.edit {
            it[configJsonKey] = json
            it[lastFetchTsKey] = System.currentTimeMillis()
            it[schemaVersionKey] = schemaVersion
        }
    }

    /** 清空缓存(切换用户或重置时调用)。 */
    suspend fun clear() {
        context.remoteConfigDataStore.edit {
            it.remove(configJsonKey)
            it.remove(lastFetchTsKey)
            it.remove(schemaVersionKey)
        }
    }
}
