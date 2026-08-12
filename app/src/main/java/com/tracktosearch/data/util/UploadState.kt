package com.tracktosearch.data.util

/** 崩溃日志上传状态（状态机终态为 Success / Failed，下次上传开始时回到 Uploading） */
sealed interface UploadState {
    data object Idle : UploadState
    data object Uploading : UploadState
    data object Success : UploadState
    data class Failed(val error: String) : UploadState
}
