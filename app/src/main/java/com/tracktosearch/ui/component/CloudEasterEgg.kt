package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// TODO: Lottie 依赖未配置，暂时用简单占位替代。添加 lottie-compose 依赖后恢复原实现。

/**
 * 白云彩蛋组件 — 替换搜索页的静态白云图标
 *
 * 平时显示天气/节日主题图标，点击触发彩蛋回调。
 * TODO: 配置 Lottie 依赖后恢复动画实现。
 */
@Composable
fun CloudEasterEgg(
    themeManager: CloudThemeManager,
    size: Dp = 182.dp,
    modifier: Modifier = Modifier,
    onCloudClicked: () -> Unit = { themeManager.onCloudClicked() }
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 按下缩放反馈
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = tween(100),
        label = "cloud_press_scale"
    )

    // 简单的云朵占位
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.6f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onCloudClicked
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = "☁️", color = Color.Gray)
    }
}
