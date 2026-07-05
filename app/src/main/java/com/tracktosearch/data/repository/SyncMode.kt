package com.tracktosearch.data.repository

/**
 * 豆瓣重新导入模式。
 *
 * - [INCREMENTAL_ONLY]: 仅同步新增条目,跳过已同步的(模式 A)
 * - [INCREMENTAL_WITH_CHANGES]: 同步新增 + 检测状态变化,撤销旧操作应用新操作(模式 B)
 * - [FULL_REWRITE]: 清空 Trakt 上之前同步的标记后重新应用(模式 C)
 */
enum class SyncMode {
    INCREMENTAL_ONLY,
    INCREMENTAL_WITH_CHANGES,
    FULL_REWRITE
}
