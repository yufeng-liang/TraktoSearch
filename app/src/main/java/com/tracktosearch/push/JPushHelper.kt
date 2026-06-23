package com.tracktosearch.push

import android.content.Context
import android.util.Log
import cn.jpush.android.api.JPushInterface

/**
 * 极光推送初始化与工具类
 */
object JPushHelper {

    private const val TAG = "JPushHelper"

    fun init(context: Context) {
        val appKey = com.tracktosearch.BuildConfig.JPUSH_APPKEY
        if (appKey.isBlank()) {
            Log.w(TAG, "JPush AppKey is empty, skip initialization")
            return
        }
        JPushInterface.setDebugMode(false)
        JPushInterface.init(context)
        Log.d(TAG, "JPush initialized with AppKey: ${appKey.take(8)}...")
    }

    fun getRegistrationId(context: Context): String {
        return JPushInterface.getRegistrationID(context)
    }

    fun onResume(context: Context) {
        JPushInterface.onResume(context)
    }

    fun onPause(context: Context) {
        JPushInterface.onPause(context)
    }
}
