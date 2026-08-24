package com.tracktosearch.data.local.db

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线缓存管理器
 * 在 API 请求成功时写入缓存，失败时从缓存读取
 *
 * 缓存类目（用于设置页「缓存管理」分项显示与清除）：
 * 1. 图片缓存（Coil 磁盘缓存） — cacheDir/image_cache/
 * 2. 影视数据缓存（TMDB 详情/演职员 + Trakt 趋势/列表 + 豆瓣热榜） — DataStore 文件
 * 3. ID 映射缓存（TMDB↔Trakt、IMDb↔Trakt、豆瓣→IMDb） — DataStore 文件（按 key 前缀）
 * 4. HTTP 缓存（OkHttp 响应缓存） — cacheDir/http_cache/
 * 5. 离线数据库（Room DB） — databases/tracktosearch.db
 */
@Singleton
class OfflineCacheManager @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val mediaDetailDao: MediaDetailDao,
    private val doubanSyncedItemDao: DoubanSyncedItemDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val TYPE_WATCHLIST_MOVIE = "watchlist_movie"
        const val TYPE_WATCHLIST_SHOW = "watchlist_show"
        const val TYPE_HISTORY_MOVIE = "history_movie"
        const val TYPE_HISTORY_SHOW = "history_show"

        /** DataStore 文件目录（Preferences DataStore 默认路径） */
        private const val DATASTORE_DIR = "datastore"
    }

    // ========== 想看/已看列表 ==========

    suspend fun saveMediaItems(type: String, items: List<MediaItemEntity>) {
        mediaItemDao.replaceByType(type, items)
    }

    suspend fun getMediaItems(type: String): List<MediaItemEntity> {
        return mediaItemDao.getByTypeList(type)
    }

    suspend fun removeMediaItem(type: String, traktId: Int) {
        mediaItemDao.deleteItem(type, traktId)
    }

    /**
     * 清除想看/已看列表快照（切换 Trakt 账号时调用）。
     *
     * 这些行按设备存储、不带账号标识，Watchlist 页会用它们兜首帧渲染，
     * 切号后不清理会在下次冷启动闪出上一个账号的列表。详情缓存与豆瓣本地数据不受影响。
     */
    suspend fun clearWatchlistSnapshots() {
        mediaItemDao.deleteByType(TYPE_WATCHLIST_MOVIE)
        mediaItemDao.deleteByType(TYPE_WATCHLIST_SHOW)
        mediaItemDao.deleteByType(TYPE_HISTORY_MOVIE)
        mediaItemDao.deleteByType(TYPE_HISTORY_SHOW)
    }

    // ========== 详情页 ==========

    suspend fun saveMediaDetail(detail: MediaDetailEntity) {
        mediaDetailDao.insert(detail)
    }

    suspend fun getMediaDetail(traktId: Int): MediaDetailEntity? {
        return mediaDetailDao.getByTraktId(traktId)
    }

    // ========== 按类目清除 ==========

    /** 清除图片缓存（Coil 磁盘缓存） */
    fun clearImageCache() {
        try {
            val imageCacheDir = context.cacheDir.resolve("image_cache")
            if (imageCacheDir.exists()) {
                imageCacheDir.deleteRecursively()
            }
        } catch (_: Exception) {
            // 忽略图片缓存清理失败
        }
    }

    /** 清除 HTTP 缓存（OkHttp 响应缓存） */
    fun clearHttpCache() {
        try {
            val httpCacheDir = context.cacheDir.resolve("http_cache")
            if (httpCacheDir.exists()) {
                httpCacheDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    /** 清除离线数据库（Room DB + WAL/SHM） */
    suspend fun clearDatabase() {
        mediaDetailDao.clearAll()
        mediaItemDao.deleteByType(TYPE_WATCHLIST_MOVIE)
        mediaItemDao.deleteByType(TYPE_WATCHLIST_SHOW)
        mediaItemDao.deleteByType(TYPE_HISTORY_MOVIE)
        mediaItemDao.deleteByType(TYPE_HISTORY_SHOW)
        try {
            doubanSyncedItemDao.clearAll()
        } catch (_: Exception) {}
    }

    /** 清除全部缓存（兼容旧调用，等价于依次清除 5 个类目） */
    suspend fun clearAll() {
        clearDatabase()
        clearImageCache()
        clearHttpCache()
    }

    // ========== 按类目查询大小 ==========

    /** 图片缓存大小（字节） */
    fun getImageCacheSizeBytes(): Long = dirSize(context.cacheDir.resolve("image_cache"))

    /** HTTP 缓存大小（字节） */
    fun getHttpCacheSizeBytes(): Long = dirSize(context.cacheDir.resolve("http_cache"))

    /** 离线数据库大小（字节，含 WAL/SHM） */
    fun getDatabaseSizeBytes(): Long {
        var total = 0L
        try {
            val dbFile = context.getDatabasePath("tracktosearch.db")
            if (dbFile.exists()) total += dbFile.length()
            val walFile = File(dbFile.parentFile, "tracktosearch.db-wal")
            if (walFile.exists()) total += walFile.length()
            val shmFile = File(dbFile.parentFile, "tracktosearch.db-shm")
            if (shmFile.exists()) total += shmFile.length()
        } catch (_: Exception) {}
        return total
    }

    /**
     * 持久化缓存（DataStore）总大小（字节）。
     *
     * 包含影视数据缓存 + ID 映射缓存 + 豆瓣详情缓存，全部存储在
     * context.filesDir/datastore/ 目录下（Preferences DataStore 默认路径）。
     *
     * 注意：DataStore Preferences 把所有 key 序列化到一个文件中，
     * 无法按 key 前缀拆分大小，因此这里返回整个 datastore 目录的大小，
     * 影视数据 + ID 映射两项共用这个值（UI 上分两项展示但合计与实际相符）。
     */
    fun getDataStoreSizeBytes(): Long = dirSize(context.filesDir.resolve(DATASTORE_DIR))

    // ========== 聚合查询 ==========

    /** 全部缓存总大小（字节） */
    fun getCacheSizeBytes(): Long {
        return getImageCacheSizeBytes() +
            getHttpCacheSizeBytes() +
            getDatabaseSizeBytes() +
            getDataStoreSizeBytes()
    }

    /** 各类目缓存明细，用于设置页分项展示 */
    suspend fun getCacheInfo(): CacheInfo {
        val watchlistMovies = mediaItemDao.countByType(TYPE_WATCHLIST_MOVIE)
        val watchlistShows = mediaItemDao.countByType(TYPE_WATCHLIST_SHOW)
        val historyMovies = mediaItemDao.countByType(TYPE_HISTORY_MOVIE)
        val historyShows = mediaItemDao.countByType(TYPE_HISTORY_SHOW)
        val details = mediaItemDao.countDetails()
        return CacheInfo(
            watchlistMovies = watchlistMovies,
            watchlistShows = watchlistShows,
            historyMovies = historyMovies,
            historyShows = historyShows,
            details = details
        )
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        var size = 0L
        val files = dir.listFiles() ?: return 0
        for (file in files) {
            size += if (file.isDirectory) dirSize(file) else file.length()
        }
        return size
    }
}

data class CacheInfo(
    val watchlistMovies: Int,
    val watchlistShows: Int,
    val historyMovies: Int,
    val historyShows: Int,
    val details: Int
) {
    val totalItems: Int get() = watchlistMovies + watchlistShows + historyMovies + historyShows + details
}
