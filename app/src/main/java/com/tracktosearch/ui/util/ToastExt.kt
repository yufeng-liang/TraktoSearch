package com.tracktosearch.ui.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Toast
import androidx.compose.ui.unit.dp

/**
 * 显示 Toast，抬高位置到悬浮导航栏之上，避免被遮挡。
 * 悬浮导航栏约 64dp + 底部间距 8dp，加上额外偏移。
 */
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    val toast = Toast.makeText(this, message, duration)
    // 悬浮导航栏高度(64dp) + 间距(8dp) + 额外偏移
    val navBarHeight = getNavigationBarHeight()
    val floatingNavOffset = (64 + 8 + 16).dpToPx()
    toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, maxOf(navBarHeight, floatingNavOffset))
    toast.show()
}

private fun Context.getNavigationBarHeight(): Int {
    val window = (this as? Activity)?.window ?: return 0
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.decorView.rootWindowInsets
            ?.getInsets(WindowInsets.Type.navigationBars())
            ?.bottom ?: 0
    } else {
        @Suppress("DEPRECATION")
        window.decorView.rootWindowInsets?.stableInsetBottom ?: 0
    }
}

private fun Int.dpToPx(): Int {
    return (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
