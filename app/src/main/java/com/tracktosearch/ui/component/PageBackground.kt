package com.tracktosearch.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 跨页面共享背景：渐变底色 + 彩色漂浮光晕。
 *
 * 光晕分布在一个宽为 pageCount * 屏幕宽度的画布上，并随当前页水平平移，
 * 从而在不同 Tab 页之间形成“同一水平画布连续运动”的视觉效果。
 */
@Composable
fun PageBackground(
    currentPage: Int,
    pageCount: Int,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val canvasWidthDp = screenWidthDp * pageCount

    val anim1 = remember { Animatable(0f) }
    val anim2 = remember { Animatable(0f) }
    val anim3 = remember { Animatable(0f) }
    val anim4 = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            while (true) {
                anim1.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 20000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        }
        launch {
            while (true) {
                anim2.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 25000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        }
        launch {
            while (true) {
                anim3.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 22000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        }
        launch {
            while (true) {
                anim4.animateTo(
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 26000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )
            }
        }
    }

    val t1 = anim1.value
    val t2 = anim2.value
    val t3 = anim3.value
    val t4 = anim4.value

    val bgGradient = if (isDark) {
        Brush.linearGradient(
            0f to Color(0xFF13132A),
            0.5f to Color(0xFF181835),
            1f to Color(0xFF1A1530)
        )
    } else {
        Brush.linearGradient(
            0f to Color(0xFFF0F4FF),
            0.4f to Color(0xFFE8F0FF),
            1f to Color(0xFFF5E8FF)
        )
    }

    val orb1Colors = if (isDark) {
        listOf(Color(0xFF5C6BC0).copy(alpha = 0.42f), Color(0xFF5C6BC0).copy(alpha = 0f))
    } else {
        listOf(Color(0xFF7986CB).copy(alpha = 0.48f), Color(0xFF7986CB).copy(alpha = 0f))
    }
    val orb2Colors = if (isDark) {
        listOf(Color(0xFFEC407A).copy(alpha = 0.35f), Color(0xFFEC407A).copy(alpha = 0f))
    } else {
        listOf(Color(0xFFF48FB1).copy(alpha = 0.42f), Color(0xFFF48FB1).copy(alpha = 0f))
    }
    val orb3Colors = if (isDark) {
        listOf(Color(0xFF26A69A).copy(alpha = 0.32f), Color(0xFF26A69A).copy(alpha = 0f))
    } else {
        listOf(Color(0xFF80CBC4).copy(alpha = 0.40f), Color(0xFF80CBC4).copy(alpha = 0f))
    }
    val orb4Colors = if (isDark) {
        listOf(Color(0xFFFFB74D).copy(alpha = 0.28f), Color(0xFFFFB74D).copy(alpha = 0f))
    } else {
        listOf(Color(0xFFFFE0B2).copy(alpha = 0.35f), Color(0xFFFFE0B2).copy(alpha = 0f))
    }
    val orb5Colors = if (isDark) {
        listOf(Color(0xFFBA68C8).copy(alpha = 0.35f), Color(0xFFBA68C8).copy(alpha = 0f))
    } else {
        listOf(Color(0xFFCE93D8).copy(alpha = 0.40f), Color(0xFFCE93D8).copy(alpha = 0f))
    }
    val orb6Colors = if (isDark) {
        listOf(Color(0xFF64B5F6).copy(alpha = 0.32f), Color(0xFF64B5F6).copy(alpha = 0f))
    } else {
        listOf(Color(0xFF90CAF9).copy(alpha = 0.38f), Color(0xFF90CAF9).copy(alpha = 0f))
    }

    // 背景画布随当前页水平平移，形成连续画布效果
    val targetOffsetX = (-currentPage * screenWidthDp).dp
    val offsetX by animateDpAsState(
        targetValue = targetOffsetX,
        animationSpec = tween(durationMillis = 400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "page_background_offset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgGradient)
    ) {
        Box(
            modifier = Modifier
                .width(canvasWidthDp.dp)
                .fillMaxHeight()
                .offset(x = offsetX)
        ) {
            // 蓝紫色光晕（第 0 页右侧 / 第 1 页左侧）
            Orb(
                size = 300.dp,
                x = (screenWidthDp * 0.45f).dp,
                y = (-60 + kotlin.math.sin(t1 * Math.PI * 2).toFloat() * 50).dp,
                colors = orb1Colors
            )
            // 粉色光晕（第 0 页左下）
            Orb(
                size = 260.dp,
                x = (-110 + kotlin.math.cos(t2 * Math.PI * 2).toFloat() * 70).dp,
                y = (290 + kotlin.math.sin(t2 * Math.PI * 2).toFloat() * 100).dp,
                colors = orb2Colors
            )
            // 青色光晕（第 0 页中下 / 第 1 页左上）
            Orb(
                size = 240.dp,
                x = (screenWidthDp * 0.15f + kotlin.math.sin(t3 * Math.PI * 2 + 1).toFloat() * 80).dp,
                y = (150 + kotlin.math.cos(t3 * Math.PI * 2 + 1).toFloat() * 80).dp,
                colors = orb3Colors
            )
            // 橙色光晕（第 1 页右下）
            Orb(
                size = 220.dp,
                x = (screenWidthDp * 1.3f + kotlin.math.cos(t1 * Math.PI * 2 + 2).toFloat() * 60).dp,
                y = (420 + kotlin.math.sin(t1 * Math.PI * 2 + 2).toFloat() * 70).dp,
                colors = orb4Colors
            )
            // 紫色光晕（第 1 页上 / 第 2 页左上）
            Orb(
                size = 280.dp,
                x = (screenWidthDp * 1.0f + kotlin.math.sin(t4 * Math.PI * 2).toFloat() * 70).dp,
                y = (60 + kotlin.math.cos(t4 * Math.PI * 2).toFloat() * 60).dp,
                colors = orb5Colors
            )
            // 浅蓝光晕（第 2 页右 / 第 3 页左）
            Orb(
                size = 240.dp,
                x = (screenWidthDp * 2.2f + kotlin.math.cos(t3 * Math.PI * 2 + 1.5f).toFloat() * 80).dp,
                y = (180 + kotlin.math.sin(t3 * Math.PI * 2 + 1.5f).toFloat() * 90).dp,
                colors = orb6Colors
            )
            // 绿色光晕（第 3 页左上）
            Orb(
                size = 220.dp,
                x = (screenWidthDp * 2.85f + kotlin.math.sin(t2 * Math.PI * 2 + 0.5f).toFloat() * 60).dp,
                y = (-40 + kotlin.math.cos(t2 * Math.PI * 2 + 0.5f).toFloat() * 50).dp,
                colors = if (isDark) {
                    listOf(Color(0xFF66BB6A).copy(alpha = 0.30f), Color(0xFF66BB6A).copy(alpha = 0f))
                } else {
                    listOf(Color(0xFFA5D6A7).copy(alpha = 0.36f), Color(0xFFA5D6A7).copy(alpha = 0f))
                }
            )
            // 琥珀色光晕（第 3 页右下）
            Orb(
                size = 200.dp,
                x = (screenWidthDp * 3.3f + kotlin.math.cos(t4 * Math.PI * 2 + 2.5f).toFloat() * 50).dp,
                y = (520 + kotlin.math.sin(t4 * Math.PI * 2 + 2.5f).toFloat() * 70).dp,
                colors = if (isDark) {
                    listOf(Color(0xFFFFCA28).copy(alpha = 0.28f), Color(0xFFFFCA28).copy(alpha = 0f))
                } else {
                    listOf(Color(0xFFFFE082).copy(alpha = 0.34f), Color(0xFFFFE082).copy(alpha = 0f))
                }
            )
        }
    }
}

@Composable
private fun Orb(
    size: Dp,
    x: Dp,
    y: Dp,
    colors: List<Color>
) {
    Box(
        modifier = Modifier
            .size(size)
            .offset(x = x, y = y)
            .background(Brush.radialGradient(colors), RoundedCornerShape(50))
    )
}
