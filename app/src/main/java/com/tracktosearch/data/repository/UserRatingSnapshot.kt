package com.tracktosearch.data.repository

import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.local.db.UserReviewDao
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户评分的进程内内存镜像，只服务详情页首帧。
 *
 * 背景：评分本身早已落盘（Trakt 条目在 `user_review`，豆瓣条目在 `douban_synced_items.rating`），
 * 但详情页的读取都是异步的，而 ViewModel 每次导航都是新建的、初始 `userRating = null`，
 * 于是每次进入都先画一帧「未评分」再跳成「已评分」。这里提供一份同步可读的镜像，
 * 让 ViewModel 在 init 阶段就能把评分播种进初始状态。
 *
 * 定位是**首帧提示**，不是数据源：所有既有异步读取照常执行并覆盖镜像值，
 * 因此镜像即使短暂过时也只影响首帧，不会产生新的错误状态。
 *
 * 量纲统一为 Trakt 的 1-10（豆瓣 1-5 ×2），与 `DetailUiState.userRating` 一致。
 */
@Singleton
class UserRatingSnapshot @Inject constructor(
    private val userReviewDao: UserReviewDao,
    private val doubanSyncedItemDao: DoubanSyncedItemDao
) {
    // key 带 mediaType：电影/剧集 traktId 分属不同命名空间可能同号，裸 id 会互相覆盖
    private val traktRatings = ConcurrentHashMap<String, Int>()
    private val doubanRatings = ConcurrentHashMap<String, Int>()

    private fun traktKey(traktId: Long, mediaType: String) = "$traktId:$mediaType"

    /**
     * 全量重建镜像。启动预热调用一次；豆瓣批量同步改表后重新调用以对齐。
     * 重建而非增量合并：取消评分会删行，增量合并会残留已被删除的旧值。
     */
    suspend fun warmUp() {
        val trakt = runCatching { userReviewDao.getAll() }.getOrNull().orEmpty()
        val douban = runCatching { doubanSyncedItemDao.getAllSyncedItems() }.getOrNull().orEmpty()

        traktRatings.clear()
        doubanRatings.clear()
        trakt.forEach { entity ->
            entity.rating?.toInt()?.let { traktRatings[traktKey(entity.traktId, entity.mediaType)] = it }
        }
        douban.forEach { item ->
            item.rating?.let { doubanRatings[item.doubanId] = it * DOUBAN_TO_TEN_SCALE }
        }
    }

    /** 仅查内存，不触发任何 I/O。未评分或镜像未预热时返回 null。 */
    fun peekTrakt(traktId: Long, mediaType: String): Int? = traktRatings[traktKey(traktId, mediaType)]

    /** 仅查内存，不触发任何 I/O。未评分或镜像未预热时返回 null。 */
    fun peekDouban(doubanId: String): Int? = doubanRatings[doubanId]

    /** 写穿：评分 1-10，null 表示取消评分。 */
    fun putTrakt(traktId: Long, mediaType: String, rating: Int?) {
        val key = traktKey(traktId, mediaType)
        if (rating == null) traktRatings.remove(key) else traktRatings[key] = rating
    }

    /** 写穿：豆瓣 1-5 星，null 表示取消评分。 */
    fun putDouban(doubanId: String, rating: Int?) {
        if (rating == null) doubanRatings.remove(doubanId)
        else doubanRatings[doubanId] = rating * DOUBAN_TO_TEN_SCALE
    }

    fun removeTrakt(traktId: Long, mediaType: String) {
        traktRatings.remove(traktKey(traktId, mediaType))
    }

    fun removeDouban(doubanId: String) {
        doubanRatings.remove(doubanId)
    }

    private companion object {
        const val DOUBAN_TO_TEN_SCALE = 2
    }
}
