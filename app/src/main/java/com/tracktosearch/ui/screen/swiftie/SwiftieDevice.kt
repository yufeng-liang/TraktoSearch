package com.tracktosearch.ui.screen.swiftie

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 低端机判定。彩蛋里多处按此降级（sparkle 数量、Eras 卡片时长、手链陀螺仪），
 * 因此抽成一处，Phase D / E 直接复用。
 */
@Composable
internal fun rememberIsLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice ?: false
    }
}
