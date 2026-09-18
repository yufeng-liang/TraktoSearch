package com.tracktosearch.ui.util

import android.annotation.SuppressLint
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.Flow

/**
 * 显示 Toast。
 *
 * 历史上这里用 setGravity 把 Toast 抬到悬浮导航栏之上，但 Android 11（API 30）起平台对
 * 文本 Toast 的 setGravity/setMargin 是空操作，本应用 targetSdk 远高于 30，
 * 偏移整段从未生效过——已删除，避免误以为位置计算在起作用。
 */
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

/**
 * 在 Composable 中订阅 Int 资源 ID 的 Flow 并显示 Toast。
 * 使用 SuppressLint 允许在副作用中根据运行时资源 ID 解析字符串（Toast 事件模式）。
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun ToastEffect(toastEvent: Flow<Int>) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        toastEvent.collect { resId ->
            context.showToast(context.getString(resId))
        }
    }
}
