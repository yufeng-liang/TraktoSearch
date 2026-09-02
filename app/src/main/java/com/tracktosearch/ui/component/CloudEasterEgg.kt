package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticCombinedClickable

/**
 * 白云彩蛋组件 — 替换搜索页的静态白云图标
 *
 * 平时显示天气/节日主题 Lottie 动画，点击触发彩蛋回调。
 *
 * 性能优化：通过 [LocalIsCurrentTab] 检测当前是否为用户可见的 Tab。
 * 非当前 Tab 时（如用户在发现页/我的页浏览），停止 Lottie 无限循环动画，
 * 固定显示第一帧，避免后台持续渲染导致 CPU/GPU 高负载发热。
 */
@Composable
fun CloudEasterEgg(
    themeManager: CloudThemeManager,
    size: Dp = 182.dp,
    modifier: Modifier = Modifier,
    // 新手引导未完成时白云点击不做任何分派，计数也不累加（Spec §3.3）
    onboardingCompleted: Boolean = true,
    onCloudClicked: () -> Unit = { themeManager.onCloudClicked(onboardingCompleted) },
    onLongClick: (() -> Unit)? = null
) {
    val theme by themeManager.currentTheme.collectAsStateWithLifecycle()
    val isNightAlternate by themeManager.isNightAlternate.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 当前 Tab 才播放 Lottie 动画，非当前 Tab 静态显示避免后台发热
    val isCurrentTab = LocalIsCurrentTab.current

    // 按下缩放反馈
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(100),
        label = "cloud_press_scale"
    )

    // 获取当前应显示的主题
    val displayTheme = themeManager.getCurrentDisplayTheme()

    // 加载 Lottie 动画（composition 加载本身是异步的，无 CPU 负担）
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(displayTheme.rawRes)
    )
    // 仅当前 Tab 时启用无限循环动画；非当前 Tab 时 progress 固定为 0f（静态第一帧）
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = if (isCurrentTab) LottieConstants.IterateForever else 1,
        isPlaying = isCurrentTab
    )

    // hapticCombinedClickable 是 @Composable，局部 fun 装不下（局部 fun 不能标 @Composable），
    // 所以改成在 composable 体内直接算好的 Modifier 值
    val cloudGesture = Modifier.hapticCombinedClickable(
        interactionSource = interactionSource,
        indication = null,
        semantic = HapticSemantic.LIGHT_TAP,
        onClick = onCloudClicked,
        onLongClick = onLongClick
    )

    LottieAnimation(
        composition = composition,
        progress = { if (isCurrentTab) progress else 0f },
        modifier = modifier
            .size(size)
            .scale(scale)
            .then(cloudGesture)
    )
}
