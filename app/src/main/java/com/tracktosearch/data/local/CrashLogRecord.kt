package com.tracktosearch.data.local

import kotlinx.serialization.Serializable

/** 崩溃日志上传记录（每份日志文件一条，状态可更新，详情页直接展示快照与文件生命周期解耦） */
@Serializable
data class CrashLogRecord(
    val id: String,               // 日志文件名（crash_2026-08-12_10-32-00.log）
    val crashTime: Long,          // 崩溃时间戳（由文件名解析）
    val status: Status = Status.PENDING,
    val uploadTime: Long = 0L,    // 上传完成时间戳（0 = 未完成）
    val error: String = "",       // 失败原因（英文，非 UI 展示文案）
    val logContent: String,       // 日志全文快照
    val appVersion: String = "",
    val device: String = "",
) {
    @Serializable
    enum class Status { PENDING, UPLOADING, SUCCESS, FAILED }
}
