package com.tracktosearch.data.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏「取消下载」动作的入口。只做转发：真正终止读流的是 ApkDownloader.cancelActiveDownload()，
 * 弹窗收到 ApkDownloadCancelledException 后自己把状态收回 Idle，这里不碰 UI 状态。
 */
class ApkDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CANCEL) {
            ApkDownloader.cancelActiveDownload()
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.tracktosearch.CANCEL_APK_DOWNLOAD"
    }
}
