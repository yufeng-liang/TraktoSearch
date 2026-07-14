package com.tracktosearch.ui.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 线性马达震动反馈工具。
 *
 * 优先使用 View.performHapticFeedback()，原因：
 * 1. 走系统内部通道，国内厂商 ROM（小米/OPPO/vivo/魅族）对其有专门优化
 * 2. 不需要 VIBRATE 权限
 * 3. 魅族 mEngine 等厂商专属线性马达通过此通道触发
 *
 * 降级到 Vibrator API 作为兜底（Compose 中无 View 引用时）
 *
 * 三种效果对应不同交互场景：
 * - TICK：极轻触感，用于导航切换、Tab 切换等高频轻量操作
 * - CLICK：标准触感，用于按钮点击、卡片点击、展开/折叠
 * - HEAVY_CLICK：重触感，用于重要确认操作（标记已看、评分提交等）
 */

/** 震动反馈类型 */
enum class HapticType {
    /** 极轻触感 — 导航切换、Tab 切换、滑动选择 */
    TICK,
    /** 标准触感 — 按钮点击、卡片点击、展开/折叠 */
    CLICK,
    /** 重触感 — 重要确认操作（标记已看、评分、删除） */
    HEAVY_CLICK
}

/** 指定类型的震动反馈（优先 View.performHapticFeedback） */
fun View.performHaptic(type: HapticType) {
    val feedbackConstant = when (type) {
        HapticType.TICK -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CLOCK_TICK
            } else {
                HapticFeedbackConstants.VIRTUAL_KEY
            }
        }
        HapticType.CLICK -> HapticFeedbackConstants.VIRTUAL_KEY
        HapticType.HEAVY_CLICK -> HapticFeedbackConstants.LONG_PRESS
    }

    val flags = if (type == HapticType.HEAVY_CLICK) {
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
    } else {
        0
    }

    try {
        performHapticFeedback(feedbackConstant, flags)
        return
    } catch (_: Exception) {
        // 某些 ROM 可能对常量值报错，降级处理
    }

    // 魅族 mEngine 专属常量尝试
    if (type == HapticType.HEAVY_CLICK) {
        try {
            performHapticFeedback(31011)
            return
        } catch (_: Exception) {
            // 非魅族设备忽略
        }
    }

    // 最终降级到 Vibrator API
    context.performHapticFallback(type)
}

/** Vibrator API 降级方案 */
private fun Context.performHapticFallback(type: HapticType) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    if (!vibrator.hasVibrator()) return

    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            val effect = when (type) {
                HapticType.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                HapticType.CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                HapticType.HEAVY_CLICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            }
            try {
                vibrator.vibrate(effect)
            } catch (_: Exception) {
                vibrator.vibrate(VibrationEffect.createOneShot(type.fallbackDuration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            vibrator.vibrate(VibrationEffect.createOneShot(type.fallbackDuration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        else -> {
            @Suppress("DEPRECATION")
            vibrator.vibrate(type.fallbackDuration / 2)
        }
    }
}

private val HapticType.fallbackDuration: Long
    get() = when (this) {
        HapticType.TICK -> 10L
        HapticType.CLICK -> 20L
        HapticType.HEAVY_CLICK -> 30L
    }
