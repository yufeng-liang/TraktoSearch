package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

/**
 * 白云彩蛋组件 — 替换搜索页的静态白云图标
 *
 * 平时显示天气/节日主题 Lottie 动画，点击触发彩蛋回调。
 */
@Composable
fun CloudEasterEgg(
    themeManager: CloudThemeManager,
    size: Dp = 182.dp,
    modifier: Modifier = Modifier,
    onCloudClicked: () -> Unit = { themeManager.onCloudClicked() }
) {
    val theme by themeManager.currentTheme.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按下缩放反馈
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(100),
        label = "cloud_press_scale"
    )

    // 加载 Lottie 动画
    val composition by rememberLottieComposition(
        LottieCompositionSpec.RawRes(theme.rawRes)
    )
    val progress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever
    )

    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier
            .size(size)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onCloudClicked
            )
    )
}
