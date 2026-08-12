package com.tracktosearch.data.util

/** 崩溃上传完成 toast 触发判定（纯函数）：状态转移（Uploading → 终态）或首次挂载即终态且本次会话有崩溃 */
object UploadToastPolicy {
    fun shouldNotify(prev: UploadState?, current: UploadState, crashCount: Int): Boolean = when (current) {
        is UploadState.Failed, UploadState.Success ->
            prev is UploadState.Uploading || (prev == null && crashCount > 0)
        UploadState.Idle, UploadState.Uploading -> false
    }
}
