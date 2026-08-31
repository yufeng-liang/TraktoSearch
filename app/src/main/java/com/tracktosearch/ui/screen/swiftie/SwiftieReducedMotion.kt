package com.tracktosearch.ui.screen.swiftie

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 非公开的无障碍开关键名。读不到就按未开启处理。 */
private const val KEY_A11Y_ANIMATION_DISABLED = "accessibility_display_animation_disabled"

/**
 * 系统是否要求「减少动效」。
 *
 * 只在进入彩蛋时读一次：序列跑到一半用户去改系统设置属于极端情况，中途切换路径
 * 会比不切换更难看。
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { readReducedMotion(context) }
}

private fun readReducedMotion(context: Context): Boolean {
    val resolver = context.contentResolver
    val animatorScale = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    // 这个键不是公开 API，getInt 读不到会直接返回默认值，不抛异常
    val a11yDisabled = runCatching {
        Settings.Global.getInt(resolver, KEY_A11Y_ANIMATION_DISABLED, 0)
    }.getOrDefault(0)
    // 刻意**不看** TRANSITION_ANIMATION_SCALE：那个滑块只管窗口切换动画，
    // 不少人把它调到 0 只为界面跳转更快，并不代表不想看应用内动画。
    // 把它算进来会让这批用户永远拿到静态终态、连配乐都听不到（Spec §11.1 只列了这两个条件）
    return animatorScale == 0f || a11yDisabled == 1
}
