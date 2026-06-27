package com.tracktosearch.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// TODO: Lottie 依赖未配置，暂时用简单占位替代。添加 lottie-compose 依赖后恢复原实现。

/**
 * 全屏彩蛋 Overlay
 *
 * 点击白云后弹出，显示趣味文案。
 * 点击蒙层 / 返回键 / 超时后自动关闭。
 * TODO: 配置 Lottie 依赖后恢复动画实现。
 */
@Composable
fun CloudOverlay(
    easterEggRes: Int?,
    message: String?,
    onDismiss: () -> Unit
) {
    val visible = easterEggRes != null

    // 返回键关闭
    BackHandler(enabled = visible) { onDismiss() }

    // 使用 Box 包裹 AnimatedVisibility，避免 ColumnScope 上下文问题
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
    ) {
        // 3 秒超时自动关闭
        LaunchedEffect(Unit) {
            delay(3000)
            onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                // Lottie 动画占位
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.extraLarge
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "☁️",
                        style = MaterialTheme.typography.displayLarge
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
