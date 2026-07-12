package com.tracktosearch.data.repository

/**
 * 豆瓣重新导入模式。
 *
 * - [INCREMENTAL_WITH_CHANGES]: 增量同步 + 检测状态变化,同步完成后自动进行状态一致性检查(模式 A)
 * - [FULL_REWRITE]: 清空 Trakt 上之前同步的标记后重新应用(模式 B)
 */
enum class SyncMode {
    INCREMENTAL_WITH_CHANGES,
    FULL_REWRITE
}
