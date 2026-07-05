package com.tracktosearch.data.repository

import com.tracktosearch.data.local.db.DoubanSyncFailureEntity
import com.tracktosearch.data.remote.douban.DoubanMarkItem
import com.tracktosearch.data.remote.douban.DoubanMarkStatus

/**
 * 豆瓣同步失败项数据类。
 *
 * 包含豆瓣条目的全部信息,用于:
 * - 在 UI 中展示失败项明细
 * - 持久化到 douban_sync_failures 表(Room)
 * - 导出/导入 JSON(跨设备重试)
 * - 重试时直接走详情页→Trakt→写入流程,无需重新爬取列表
 */
data class DoubanSyncFailure(
    val doubanId: String,
    val title: String,
    val posterUrl: String?,
    val rating: Int?,
    val comment: String?,
    val markedAt: String,
    val doubanUrl: String,
    val status: DoubanMarkStatus,
    val failureReason: FailureReason,
    val failedAt: Long,
    val attemptCount: Int = 0,
    val mediaType: String? = null,   // "movie" / "show" / null(未分类),用户手动标注
    val subtitle: String? = null     // 用户手动编辑的子标题/外文标题/别名,用于资源搜索
) {
    /** 转换为 Room Entity 用于持久化 */
    fun toEntity(): DoubanSyncFailureEntity = DoubanSyncFailureEntity(
        doubanId = doubanId,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        comment = comment,
        markedAt = markedAt,
        doubanUrl = doubanUrl,
        status = status.path,
        failureReason = failureReason.name,
        failedAt = failedAt,
        attemptCount = attemptCount,
        mediaType = mediaType,
        subtitle = subtitle
    )

    /** 转换为 DoubanMarkItem,用于重试时复用 syncBatchToTrakt 流程 */
    fun toMarkItem(): DoubanMarkItem = DoubanMarkItem(
        doubanId = doubanId,
        title = title,
        rating = rating,
        comment = comment,
        markedAt = markedAt,
        doubanUrl = doubanUrl,
        posterUrl = posterUrl
    )

    companion object {
        /** 从 Room Entity 反序列化 */
        fun fromEntity(e: DoubanSyncFailureEntity): DoubanSyncFailure = DoubanSyncFailure(
            doubanId = e.doubanId,
            title = e.title,
            posterUrl = e.posterUrl,
            rating = e.rating,
            comment = e.comment,
            markedAt = e.markedAt,
            doubanUrl = e.doubanUrl,
            status = DoubanMarkStatus.fromString(e.status),
            failureReason = FailureReason.fromString(e.failureReason),
            failedAt = e.failedAt,
            attemptCount = e.attemptCount,
            mediaType = e.mediaType,
            subtitle = e.subtitle
        )

        /** 从豆瓣条目构造失败项 */
        fun fromMarkItem(
            item: DoubanMarkItem,
            status: DoubanMarkStatus,
            reason: FailureReason
        ): DoubanSyncFailure = DoubanSyncFailure(
            doubanId = item.doubanId,
            title = item.title,
            posterUrl = item.posterUrl,
            rating = item.rating,
            comment = item.comment,
            markedAt = item.markedAt,
            doubanUrl = item.doubanUrl,
            status = status,
            failureReason = reason,
            failedAt = System.currentTimeMillis()
        )
    }
}

/**
 * 失败原因枚举。
 *
 * @property displayKey 用于本地化和持久化的 key
 * @property recoverable 是否可恢复(可重试成功)。
 *   - true:  详情页访问失败、Trakt 写入超时/失败 → 默认重试
 *   - false: 无 IMDb ID、Trakt 未找到此条目 → 默认跳过(用户可手动勾选强制重试)
 */
enum class FailureReason(val displayKey: String, val recoverable: Boolean) {
    NO_IMDB_ID("no_imdb_id", recoverable = false),
    DETAIL_FETCH_FAILED("detail_fetch_failed", recoverable = true),
    TRAKT_NOT_FOUND("trakt_not_found", recoverable = false),
    TRAKT_WRITE_TIMEOUT("trakt_write_timeout", recoverable = true),
    TRAKT_WRITE_FAILED("trakt_write_failed", recoverable = true);

    companion object {
        /** 从字符串反序列化(容错:未知值降级为 DETAIL_FETCH_FAILED) */
        fun fromString(value: String?): FailureReason =
            entries.firstOrNull { it.name == value || it.displayKey == value }
                ?: DETAIL_FETCH_FAILED
    }
}
