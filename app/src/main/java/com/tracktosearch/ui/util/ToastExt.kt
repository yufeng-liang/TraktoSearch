package com.tracktosearch.ui.util

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Toast
import androidx.compose.ui.unit.dp

/**
 * 显示 Toast，并根据底部导航栏/手势条高度抬高位置，避免被系统导航栏遮挡。
 */
fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    val toast = Toast.makeText(this, message, duration)
    val navBarHeight = getNavigationBarHeight()
    val extraOffset = 16.dpToPx()
    toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, navBarHeight + extraOffset)
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
