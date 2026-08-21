package com.tracktosearch.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统计页快照专用 DataStore。
 *
 * 单独一个文件而不是并入 `trakt_persistent_cache`：Preferences DataStore 每次写入都会重写整个文件，
 * 热力图 + 词云的快照有几十 KB，混进 ID 映射缓存会让每次映射写入都付出这份开销。
 */
private val Context.statisticsSnapshotStore: DataStore<Preferences> by preferencesDataStore(name = "statistics_snapshot")

/**
 * 统计页计算结果快照。
 *
 * 存的是**算完的结果**而不是原始 history：`extended=full` 的上千条观看历史序列化后有 1-2MB，
 * 不适合放进 Preferences DataStore；而结果只有计数 + 几个分布 Map，几十 KB 即可，
 * 让进页先秒出上次结果、再后台刷新。
 */
@Serializable
data class StatisticsSnapshot(
    val totalMovieCount: Int = 0,
    /** 有观看记录的剧数（含未看完） */
    val showsWatchedCount: Int = 0,
    /** 已全部看完的剧数 */
    val showsCompletedCount: Int = 0,
    val totalEpisodeCount: Int = 0,
    val thisMonthWatched: Int = 0,
    val thisYearWatched: Int = 0,
    val genreDistribution: Map<String, Int> = emptyMap(),
    val heatmapData: Map<String, Int> = emptyMap(),
    val totalWatchMinutes: Long = 0,
    val totalRatings: Int = 0,
    val averageRating: Double = 0.0,
    val ratingDistribution: Map<Int, Int> = emptyMap(),
    val wordCloud: List<Word> = emptyList(),
    /** 写入时间，仅用于调试与后续可能的过期策略 */
    val savedAt: Long = 0L
) {
    @Serializable
    data class Word(val word: String, val weight: Int)
}

/**
 * 统计页快照存储。
 *
 * 快照永不按时间过期：只要有就先显示，同时总是后台刷新（stale-while-revalidate），
 * 符合「一切缓存策略以尽量减少网络请求为第一目标」，且进页永远不会出现空骨架。
 * 账号切换/登出由 [com.tracktosearch.data.session.SessionCacheRegistry] 调用 [clear] 清除，
 * 避免下一个账号看到上一个账号的统计。
 */
@Singleton
class StatisticsSnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    /** 进程内缓存，避免同一次会话反复读盘反序列化 */
    @Volatile
    private var cached: StatisticsSnapshot? = null

    @Volatile
    private var loaded = false

    /** 读取快照；无快照或数据损坏返回 null。 */
    suspend fun load(): StatisticsSnapshot? {
        cached?.let { return it }
        if (loaded) return null
        val saved = runCatching { context.statisticsSnapshotStore.data.first()[KEY_SNAPSHOT] }
            .getOrElse {
                Log.w(TAG, "统计快照读取失败", it)
                null
            }
        loaded = true
        if (saved.isNullOrBlank()) return null
        val restored = runCatching { json.decodeFromString(StatisticsSnapshot.serializer(), saved) }
            .getOrElse {
                // 旧格式或损坏数据：忽略而不是抛出，下一次成功刷新会覆盖
                Log.w(TAG, "统计快照解析失败，忽略损坏数据", it)
                null
            }
        cached = restored
        return restored
    }

    /** 写入快照（覆盖）。失败只记日志，不影响正在展示的数据。 */
    suspend fun save(snapshot: StatisticsSnapshot) {
        cached = snapshot
        loaded = true
        runCatching {
            context.statisticsSnapshotStore.edit { prefs ->
                prefs[KEY_SNAPSHOT] = json.encodeToString(StatisticsSnapshot.serializer(), snapshot)
            }
        }.onFailure { Log.w(TAG, "统计快照写入失败", it) }
    }

    /** 清除快照（登出、切换账号、设置页清理缓存时调用）。 */
    suspend fun clear() {
        cached = null
        loaded = true
        runCatching {
            context.statisticsSnapshotStore.edit { it.remove(KEY_SNAPSHOT) }
        }.onFailure { Log.w(TAG, "统计快照清除失败", it) }
    }

    /** 快照在磁盘上的近似字节数，供设置页「缓存管理」展示。 */
    suspend fun sizeBytes(): Long = runCatching {
        (context.statisticsSnapshotStore.data.first()[KEY_SNAPSHOT]?.length ?: 0).toLong()
    }.getOrDefault(0L)

    private companion object {
        const val TAG = "StatsSnapshotStore"

        /** key 带版本号：快照结构变化时用新 key 隔离旧格式，避免旧数据污染新模型 */
        val KEY_SNAPSHOT = stringPreferencesKey("statistics_snapshot_v1")
    }
}
