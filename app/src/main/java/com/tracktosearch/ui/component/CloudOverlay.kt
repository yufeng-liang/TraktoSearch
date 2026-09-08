package com.tracktosearch.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tracktosearch.ui.haptic.hapticClickable
import kotlinx.coroutines.delay

/**
 * 全屏彩蛋 Overlay
 *
 * 点击白云后弹出，播放治愈 Lottie 动画 + 趣味文案。
 * 点击蒙层 / 返回键 / 动画播完自动关闭。
 */
@Composable
fun CloudOverlay(
    easterEggRes: Int?,
    messageRes: Int?,
    onDismiss: () -> Unit
) {
    val visible = easterEggRes != null

    // 返回键关闭
    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(200)),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
    ) {
        val res = easterEggRes ?: return@AnimatedVisibility
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
        val progress by animateLottieCompositionAsState(
            composition = composition,
            iterations = 1
        )

        // 动画播完自动关闭
        LaunchedEffect(progress) {
            if (progress >= 1f) {
                delay(300)
                onDismiss()
            }
        }

        // 3 秒超时自动关闭
        LaunchedEffect(Unit) {
            delay(3000)
            onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                // 纯展示层的背景点击关闭：不是操作面，不震（3 秒也会自动关）
                .hapticClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    semantic = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                LottieAnimation(
                    composition = composition,
                    progress = { progress },
                    modifier = Modifier.size(300.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (messageRes != null) {
                    Text(
                        text = stringResource(messageRes),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
