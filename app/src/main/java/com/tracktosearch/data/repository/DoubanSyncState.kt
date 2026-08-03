package com.tracktosearch.data.repository

import androidx.annotation.StringRes
import com.tracktosearch.R
import com.tracktosearch.data.remote.douban.DoubanMarkStatus

/** 豆瓣同步的稳定主阶段，不承载数量、标题或错误文本。 */
enum class DoubanSyncStage(val isTerminal: Boolean = false) {
    IDLE,
    PREPARING,
    FETCHING_LIST,
    PARSING_DATA,
    UPDATING_LIST,
    COMPLETED(isTerminal = true),
    LOGIN_REQUIRED(isTerminal = true),
    CANCELLING(isTerminal = true),
    FAILED(isTerminal = true)
}

/** 登录引导目标，用于区分豆瓣 Cookie 过期和 Trakt 会话缺失。 */
enum class DoubanSyncLoginTarget {
    DOUBAN,
    TRAKT
}

/** 主阶段内的具体工作，不重复主阶段文案。 */
enum class DoubanSyncSubStage {
    NONE,
    CONNECTING,
    PULLING_CLOUD,
    FETCHING_WISH_LIST,
    FETCHING_COLLECT_LIST,
    FETCHING_DETAIL,
    LOOKING_UP_TRAKT,
    WRITING_TARGET,
    WRITING_LOCAL,
    STATUS_CHANGES,
    RETRYING_FAILURES,
    WAITING_DELAY
}

/** 对话框中展示的最近一条豆瓣列表数据。 */
data class DoubanSyncPreviewItem(
    val doubanId: String,
    val title: String,
    val status: DoubanMarkStatus,
    val rating: Int?,
    val markedAt: String
)

/** 有界的最新条目缓冲，按 status + doubanId 去重。 */
class DoubanSyncPreviewBuffer(private val maxSize: Int = 5) {
    init {
        require(maxSize > 0) { "maxSize must be greater than zero" }
    }

    private var entries: List<DoubanSyncPreviewItem> = emptyList()

    @Synchronized
    fun addAll(items: List<DoubanSyncPreviewItem>) {
        if (items.isEmpty()) return
        val incomingKeys = items.map { it.status to it.doubanId }.toSet()
        entries = (items.asReversed() + entries.filterNot { it.status to it.doubanId in incomingKeys })
            .distinctBy { it.status to it.doubanId }
            .take(maxSize)
    }

    @Synchronized
    fun snapshot(): List<DoubanSyncPreviewItem> = entries.toList()

    @Synchronized
    fun clear() {
        entries = emptyList()
    }
}

/** 将批处理内部的旧子阶段名称收敛为稳定 ID。 */
internal fun subStageFromLegacy(value: String): DoubanSyncSubStage = when (value) {
    "详情页" -> DoubanSyncSubStage.FETCHING_DETAIL
    "Trakt 查询" -> DoubanSyncSubStage.LOOKING_UP_TRAKT
    "写入 Trakt" -> DoubanSyncSubStage.WRITING_TARGET
    "写入本地" -> DoubanSyncSubStage.WRITING_LOCAL
    "状态变化" -> DoubanSyncSubStage.STATUS_CHANGES
    "重试失败项" -> DoubanSyncSubStage.RETRYING_FAILURES
    "等待延迟" -> DoubanSyncSubStage.WAITING_DELAY
    else -> DoubanSyncSubStage.NONE
}

internal fun stageFromSubStage(subStage: DoubanSyncSubStage): DoubanSyncStage = when (subStage) {
    DoubanSyncSubStage.FETCHING_WISH_LIST,
    DoubanSyncSubStage.FETCHING_COLLECT_LIST -> DoubanSyncStage.FETCHING_LIST
    DoubanSyncSubStage.FETCHING_DETAIL,
    DoubanSyncSubStage.LOOKING_UP_TRAKT,
    DoubanSyncSubStage.RETRYING_FAILURES -> DoubanSyncStage.PARSING_DATA
    DoubanSyncSubStage.WRITING_TARGET,
    DoubanSyncSubStage.WRITING_LOCAL,
    DoubanSyncSubStage.STATUS_CHANGES -> DoubanSyncStage.UPDATING_LIST
    else -> DoubanSyncStage.PREPARING
}

@StringRes
fun DoubanSyncStage.compactLabelRes(): Int = when (this) {
    DoubanSyncStage.IDLE -> R.string.douban_sync_compact_idle
    DoubanSyncStage.PREPARING -> R.string.douban_sync_compact_preparing
    DoubanSyncStage.FETCHING_LIST -> R.string.douban_sync_compact_fetching_list
    DoubanSyncStage.PARSING_DATA -> R.string.douban_sync_compact_parsing_data
    DoubanSyncStage.UPDATING_LIST -> R.string.douban_sync_compact_updating_list
    DoubanSyncStage.COMPLETED -> R.string.douban_sync_compact_completed
    DoubanSyncStage.LOGIN_REQUIRED -> R.string.douban_sync_compact_login_required
    DoubanSyncStage.CANCELLING -> R.string.douban_sync_compact_cancelling
    DoubanSyncStage.FAILED -> R.string.douban_sync_compact_failed
}

@StringRes
fun DoubanSyncStage.labelRes(): Int = when (this) {
    DoubanSyncStage.IDLE -> R.string.douban_sync_stage_idle
    DoubanSyncStage.PREPARING -> R.string.douban_sync_stage_preparing
    DoubanSyncStage.FETCHING_LIST -> R.string.douban_sync_stage_fetching_list
    DoubanSyncStage.PARSING_DATA -> R.string.douban_sync_stage_parsing_data
    DoubanSyncStage.UPDATING_LIST -> R.string.douban_sync_stage_updating_list
    DoubanSyncStage.COMPLETED -> R.string.douban_sync_stage_completed
    DoubanSyncStage.LOGIN_REQUIRED -> R.string.douban_sync_stage_login_required
    DoubanSyncStage.CANCELLING -> R.string.douban_sync_stage_cancelling
    DoubanSyncStage.FAILED -> R.string.douban_sync_stage_failed
}

@StringRes
fun DoubanSyncSubStage.labelRes(): Int? = when (this) {
    DoubanSyncSubStage.NONE -> null
    DoubanSyncSubStage.CONNECTING -> R.string.douban_sync_substage_connecting
    DoubanSyncSubStage.PULLING_CLOUD -> R.string.douban_sync_substage_pulling_cloud
    DoubanSyncSubStage.FETCHING_WISH_LIST -> R.string.douban_sync_substage_fetching_wish_list
    DoubanSyncSubStage.FETCHING_COLLECT_LIST -> R.string.douban_sync_substage_fetching_collect_list
    DoubanSyncSubStage.FETCHING_DETAIL -> R.string.douban_sync_substage_fetching_detail
    DoubanSyncSubStage.LOOKING_UP_TRAKT -> R.string.douban_sync_substage_looking_up_trakt
    DoubanSyncSubStage.WRITING_TARGET -> R.string.douban_sync_substage_writing_target
    DoubanSyncSubStage.WRITING_LOCAL -> R.string.douban_sync_substage_writing_local
    DoubanSyncSubStage.STATUS_CHANGES -> R.string.douban_sync_substage_status_changes
    DoubanSyncSubStage.RETRYING_FAILURES -> R.string.douban_sync_substage_retrying_failures
    DoubanSyncSubStage.WAITING_DELAY -> R.string.douban_sync_substage_waiting_delay
}

@StringRes
fun DoubanMarkStatus.labelRes(): Int = when (this) {
    DoubanMarkStatus.WISH -> R.string.douban_sync_preview_status_wish
    DoubanMarkStatus.COLLECT -> R.string.douban_sync_preview_status_collect
}
