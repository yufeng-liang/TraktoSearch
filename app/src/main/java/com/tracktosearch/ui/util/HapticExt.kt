package com.tracktosearch.ui.util

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 执行线性马达震动反馈（CONFIRM 类型，短促轻触感）。
 * 用于按钮点击等交互场景。
 */
fun Context.performHapticClick() {
    // 使用 View 的 performHapticFeedback 方法
    // 在 Android R+ 使用 CONFIRM（线性马达短震），低版本回退到 CLOCK_TICK
    val view = View(this)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        @Suppress("DEPRECATION")
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
