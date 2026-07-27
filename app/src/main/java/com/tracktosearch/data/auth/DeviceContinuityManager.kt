package com.tracktosearch.data.auth

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 读取 Android 提供的应用签名/用户/设备范围连续性标识，不持久化原始值。 */
@Singleton
class DeviceContinuityManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun getAndroidId(): String? = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID,
    )?.takeIf { it.isNotBlank() }
}
