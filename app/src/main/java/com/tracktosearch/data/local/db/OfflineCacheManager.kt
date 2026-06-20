package com.tracktosearch.data.local.db

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线缓存管理器
 * 在 API 请求成功时写入缓存，失败时从缓存读取
 */
@Singleton
class OfflineCacheManager @Inject constructor(
    private val mediaItemDao: MediaItemDao,
    private val mediaDetailDao: MediaDetailDao,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val TYPE_WATCHLIST_MOVIE = "watchlist_movie"
        const val TYPE_WATCHLIST_SHOW = "watchlist_show"
        const val TYPE_HISTORY_MOVIE = "history_movie"
        const val TYPE_HISTORY_SHOW = "history_show"
    }

    // ========== 想看/已看列表 ==========

    suspend fun saveMediaItems(type: String, items: List<MediaItemEntity>) {
        mediaItemDao.deleteByType(type)
        mediaItemDao.insertAll(items)
    }

    suspend fun getMediaItems(type: String): List<MediaItemEntity> {
        return mediaItemDao.getByTypeList(type)
    }

    suspend fun removeMediaItem(type: String, traktId: Int) {
        mediaItemDao.deleteItem(type, traktId)
    }

    // ========== 详情页 ==========

    suspend fun saveMediaDetail(detail: MediaDetailEntity) {
        mediaDetailDao.insert(detail)
    }

    suspend fun getMediaDetail(traktId: Int): MediaDetailEntity? {
        return mediaDetailDao.getByTraktId(traktId)
    }

    // ========== 缓存清理 ==========

    suspend fun clearAll() {
        mediaDetailDao.clearAll()
        mediaItemDao.deleteByType(TYPE_WATCHLIST_MOVIE)
        mediaItemDao.deleteByType(TYPE_WATCHLIST_SHOW)
        mediaItemDao.deleteByType(TYPE_HISTORY_MOVIE)
        mediaItemDao.deleteByType(TYPE_HISTORY_SHOW)
        // 清理 Coil 图片磁盘缓存
        clearImageCache()
    }

    private fun clearImageCache() {
        try {
            val imageCacheDir = context.cacheDir.resolve("image_cache")
            if (imageCacheDir.exists()) {
                imageCacheDir.deleteRecursively()
            }
        } catch (_: Exception) {
            // 忽略图片缓存清理失败
        }
    }

    // ========== 缓存信息 ==========

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

    /** 计算缓存总占用空间（Room 数据库 + Coil 图片缓存），返回字节数 */
    fun getCacheSizeBytes(): Long {
        var total = 0L
        // Room 数据库文件
        try {
            val dbFile = context.getDatabasePath("tracktosearch.db")
            if (dbFile.exists()) total += dbFile.length()
            // WAL 和 SHM 文件
            val walFile = File(dbFile.parentFile, "tracktosearch.db-wal")
            if (walFile.exists()) total += walFile.length()
            val shmFile = File(dbFile.parentFile, "tracktosearch.db-shm")
            if (shmFile.exists()) total += shmFile.length()
        } catch (_: Exception) {}
        // Coil 图片磁盘缓存
        try {
            val imageCacheDir = context.cacheDir.resolve("image_cache")
            if (imageCacheDir.exists()) {
                total += dirSize(imageCacheDir)
            }
        } catch (_: Exception) {}
        return total
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
