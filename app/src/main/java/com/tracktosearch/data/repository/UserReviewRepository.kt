package com.tracktosearch.data.repository

import com.tracktosearch.data.local.db.UserReviewDao
import com.tracktosearch.data.local.db.UserReviewEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户评分+短评的本地缓存仓储。
 *
 * 详情页优先从本地读取评分/短评，提交/更新时写回本地。
 * 仅作为 Trakt 之上的"优先层"，不替代 Trakt 主流程。
 */
@Singleton
class UserReviewRepository @Inject constructor(
    private val userReviewDao: UserReviewDao,
    private val ratingSnapshot: UserRatingSnapshot
) {
    // mediaType 取 "movie"/"show"（与实体存储值一致）。电影/剧集 traktId 可能同号，
    // 不带 mediaType 的查询会张冠李戴
    suspend fun getReview(traktId: Long, mediaType: String): UserReviewEntity? =
        userReviewDao.getByKey(traktId, mediaType)

    suspend fun saveReview(entity: UserReviewEntity): Unit {
        userReviewDao.upsert(entity.copy(syncedAt = System.currentTimeMillis()))
        // 写穿首帧镜像：下次进同一详情页时 init 阶段就能同步读到，不再先闪「未评分」
        ratingSnapshot.putTrakt(entity.traktId, entity.mediaType, entity.rating?.toInt())
    }

    suspend fun getAllReviews(): List<UserReviewEntity> = userReviewDao.getAll()

    suspend fun getReviewsByType(mediaType: String): List<UserReviewEntity> = userReviewDao.getByMediaType(mediaType)

    suspend fun deleteReview(traktId: Long, mediaType: String) {
        userReviewDao.deleteByKey(traktId, mediaType)
        ratingSnapshot.removeTrakt(traktId, mediaType)
    }
}
