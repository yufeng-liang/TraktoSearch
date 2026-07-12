package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.doubanSyncMetaStore: DataStore<Preferences> by preferencesDataStore(name = "douban_sync_meta")

/**
 * 豆瓣同步元信息本地存储。
 *
 * 用于「跨设备云端同步」的「近期跳过列表」决策：
 * - [lastFullSyncAt] 上次完整同步完成时间戳。B 手机拉取云端 sync_meta 时更新此值。
 *   增量同步开始前检查此值：< 7 天且无 pending → 跳过豆瓣列表爬取，避免反爬。
 *
 * 该值也作为「云端最近一次完整同步」的本地镜像，与云端 sync_meta.json 中的 lastFullSyncAt 对应。
 */
@Singleton
class DoubanSyncMetaStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastFullSyncAtKey = longPreferencesKey("last_full_sync_at")
    private val lastSyncAtKey = longPreferencesKey("last_sync_at")
    private val lastSyncModeKey = stringPreferencesKey("last_sync_mode")
    private val cloudSyncSourceKey = stringPreferencesKey("cloud_sync_source")  // 上次拉取来源（"cloud" / "local"）

    /** 上次完整同步完成时间戳（首次为 0） */
    suspend fun getLastFullSyncAt(): Long =
        context.doubanSyncMetaStore.data.map { it[lastFullSyncAtKey] ?: 0L }.first()

    /** 上次任意同步时间戳（首次为 0） */
    suspend fun getLastSyncAt(): Long =
        context.doubanSyncMetaStore.data.map { it[lastSyncAtKey] ?: 0L }.first()

    /** 上次同步模式（"INCREMENTAL_WITH_CHANGES" / "FULL_REWRITE" / "RESUME" / "RETRY" / "CANCELLED"） */
    suspend fun getLastSyncMode(): String? =
        context.doubanSyncMetaStore.data.map { it[lastSyncModeKey] }.first()

    /**
     * 记录本次同步完成（本地完成）。
     *
     * @param mode 同步模式
     * @param isFullComplete true=完整同步完成（更新 lastFullSyncAt）；false=非完整同步（增量/续传/重试/取消）
     */
    suspend fun recordLocalSync(mode: String, isFullComplete: Boolean) {
        val now = System.currentTimeMillis()
        context.doubanSyncMetaStore.edit { prefs ->
            prefs[lastSyncAtKey] = now
            prefs[lastSyncModeKey] = mode
            prefs[cloudSyncSourceKey] = "local"
            if (isFullComplete) {
                prefs[lastFullSyncAtKey] = now
            }
        }
    }

    /**
     * 从云端拉取后更新本地镜像。
     *
     * @param cloudLastFullSyncAt 云端的 lastFullSyncAt
     * @param cloudLastSyncAt 云端的 lastSyncAt
     * @param cloudLastSyncMode 云端的 lastSyncMode
     */
    suspend fun updateFromCloud(
        cloudLastFullSyncAt: Long,
        cloudLastSyncAt: Long,
        cloudLastSyncMode: String
    ) {
        // 取云端和本地较新的值，避免旧云端覆盖本地新值
        val localLastFull = getLastFullSyncAt()
        val localLast = getLastSyncAt()
        context.doubanSyncMetaStore.edit { prefs ->
            prefs[lastFullSyncAtKey] = maxOf(localLastFull, cloudLastFullSyncAt)
            prefs[lastSyncAtKey] = maxOf(localLast, cloudLastSyncAt)
            prefs[lastSyncModeKey] = if (cloudLastSyncAt > localLast) cloudLastSyncMode else (prefs[lastSyncModeKey] ?: cloudLastSyncMode)
            prefs[cloudSyncSourceKey] = "cloud"
        }
    }

    /**
     * 判断是否可跳过豆瓣列表爬取（核心决策点）。
     *
     * 条件：上次完整同步距今 < [maxAgeMillis]。
     * 调用方还需自行检查 pending items 数量（若有 pending 不应跳过列表爬取）。
     *
     * @param maxAgeMillis 最大允许跳过的时间窗口，默认 7 天
     * @return true=可跳过列表爬取；false=需要爬列表校验
     */
    suspend fun canSkipListCrawl(maxAgeMillis: Long = 7 * 24 * 60 * 60 * 1000L): Boolean {
        val lastFull = getLastFullSyncAt()
        if (lastFull <= 0L) return false  // 从未完整同步过
        val age = System.currentTimeMillis() - lastFull
        return age < maxAgeMillis
    }

    /**
     * 获取冷却期状态(供 UI 显示)。
     *
     * @param maxAgeMillis 冷却窗口,默认 7 天
     * @return [CooldownStatus] neverNull=true 表示从未完整同步过(不显示冷却状态)
     */
    suspend fun getCooldownStatus(maxAgeMillis: Long = 7 * 24 * 60 * 60 * 1000L): CooldownStatus {
        val lastFull = getLastFullSyncAt()
        if (lastFull <= 0L) return CooldownStatus(neverSynced = true)
        val age = System.currentTimeMillis() - lastFull
        val remaining = maxAgeMillis - age
        return if (remaining > 0L) {
            CooldownStatus(isCoolingDown = true, remainingDays = (remaining / (24 * 60 * 60 * 1000L)).toInt() + 1)
        } else {
            CooldownStatus(isCoolingDown = false, remainingDays = 0)
        }
    }

    /** 清除（退出登录时调用） */
    suspend fun clear() {
        context.doubanSyncMetaStore.edit { it.clear() }
    }
}

/**
 * 冷却期状态(供设置页与模式选择对话框显示)。
 *
 * @param neverSynced true=从未完整同步过,不显示冷却状态
 * @param isCoolingDown true=处于 7 天冷却期内
 * @param remainingDays 剩余天数(冷却期内才有意义,向上取整,如剩 1 小时显示 1 天)
 */
data class CooldownStatus(
    val neverSynced: Boolean = false,
    val isCoolingDown: Boolean = false,
    val remainingDays: Int = 0
)
