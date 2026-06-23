package com.tracktosearch.push

import android.content.Intent
import android.util.Log
import cn.jpush.android.api.NotificationMessage
import cn.jpush.android.service.JPushMessageReceiver
import org.json.JSONObject

/**
 * 极光推送自定义 Receiver
 * 处理通知点击、别名/标签操作回调等
 */
class PushReceiver : JPushMessageReceiver() {

    companion object {
        private const val TAG = "JPushReceiver"
    }

    override fun onNotifyMessageOpened(context: android.content.Context, message: NotificationMessage) {
        super.onNotifyMessageOpened(context, message)
        Log.d(TAG, "Notification opened: ${message.notificationTitle} - ${message.notificationContent}")
        // 点击通知跳转到详情页
        val extrasStr = message.notificationExtras
        if (!extrasStr.isNullOrEmpty()) {
            try {
                val json = JSONObject(extrasStr)
                val traktId = json.optInt("traktId", -1)
                val tmdbId = json.optInt("tmdbId", -1)
                val type = json.optString("type", "movie")
                val title = json.optString("title", "")
                if (traktId > 0) {
                    val intent = Intent(context, com.tracktosearch.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("navigate_to", "detail")
                        putExtra("type", type)
                        putExtra("traktId", traktId)
                        putExtra("tmdbId", tmdbId)
                        putExtra("title", title)
                    }
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse notification extras", e)
            }
        }
    }

    override fun onConnected(context: android.content.Context, isConnected: Boolean) {
        super.onConnected(context, isConnected)
        Log.d(TAG, "JPush connected: $isConnected")
    }

    override fun onRegister(context: android.content.Context, registrationId: String) {
        super.onRegister(context, registrationId)
        Log.d(TAG, "Registration ID: $registrationId")
    }
}
