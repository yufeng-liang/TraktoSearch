package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.theme.LightBackground

/**
 * 跨页面共享背景：渐变底色 + 彩色光晕。
 *
 * 光晕分布在一个宽为 pageCount * 屏幕宽度的画布上，并随当前页水平平移，
 * 从而在不同 Tab 页之间形成“同一水平画布”的视觉效果。
 *
 * 注意：光晕位置为固定值（不再使用无限动画驱动 sin/cos 漂浮）。
 * 原因：PageBackground 位于 hazeSource 采样层内，无限动画会每帧驱动
 * HazeSourceNode.draw 重新捕获（实测静态下 252fps 无效重绘）。
 * 移除无限动画后 Haze 仅在 Tab 切换平移期间短暂重绘，性能大幅提升，
 * 视觉上光晕装饰保留、Tab 切换平移保留，仅丢失极缓慢的漂浮感。
 */
@Composable
fun PageBackground(
    currentPage: Int,
    pageCount: Int,
    isDark: Boolean,
    showColorGlow: Boolean = true,
    modifier: Modifier = Modifier
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val canvasWidthDp = screenWidthDp * pageCount

    val bgGradient = if (isDark) {
        Brush.linearGradient(
            0f to Color(0xFF13132A),
            0.5f to Color(0xFF181835),
            1f to Color(0xFF1A1530)
        )
    } else {
        Brush.linearGradient(
            0f to LightBackground,
            0.4f to Color(0xFFECEDEF),
            1f to Color(0xFFF3F4F5)
        )
    }

    val orb1Colors = if (isDark) {
        listOf(Color(0xFF5C6BC0).copy(alpha = 0.42f), Color(0xFF5C6BC0).copy(alpha = 0f))
    } else {
        listOf(Color(0xFF7986CB).copy(alpha = 0.18f), Color(0xFF7986CB).copy(alpha = 0f))
    }
    val orb2Colors = if (isDark) {
        listOf(Color(0xFFEC407A).copy(alpha = 0.35f), Color(0xFFEC407A).copy(alpha = 0f))
    } else {
        listOf(Color(0xFFF48FB1).copy(alpha = 0.16f), Color(0xFFF48FB1).copy(alpha = 0f))
    }
    val orb3Colors = if (isDark) {
        listOf(Color(0xFF26A69A).copy(alpha = 0.32f), Color(0xFF26A69A).copy(alpha = 0f))
    } else {
        listOf(Color(0xFF80CBC4).copy(alpha = 0.14f), Color(0xFF80CBC4).copy(alpha = 0f))
    }
    val orb4Colors = if (isDark) {
        listOf(Color(0xFFFFB74D).copy(alpha = 0.28f), Color(0xFFFFB74D).copy(alpha = 0f))
    } else {
        listOf(Color(0xFFFFE0B2).copy(alpha = 0.13f), Color(0xFFFFE0B2).copy(alpha = 0f))
    }
    val orb5Colors = if (isDark) {
        listOf(Color(0xFFBA68C8).copy(alpha = 0.35f), Color(0xFFBA68C8).copy(alpha = 0f))
    } else {
        listOf(Color(0xFFCE93D8).copy(alpha = 0.14f), Color(0xFFCE93D8).copy(alpha = 0f))
    }
    val orb6Colors = if (isDark) {
        listOf(Color(0xFF64B5F6).copy(alpha = 0.32f), Color(0xFF64B5F6).copy(alpha = 0f))
    } else {
        listOf(Color(0xFF90CAF9).copy(alpha = 0.14f), Color(0xFF90CAF9).copy(alpha = 0f))
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
        if (showColorGlow) {
            Box(
                modifier = Modifier
                    .width(canvasWidthDp.dp)
                    .fillMaxHeight()
                    .offset(x = offsetX)
            ) {
            // 光晕位置取原 sin/cos 动画的中点值（t=0.5），保持视觉分布一致
            // 蓝紫色光晕（第 0 页右侧 / 第 1 页左侧）
            Orb(
                size = 300.dp,
                x = (screenWidthDp * 0.45f).dp,
                y = (-10).dp,
                colors = orb1Colors
            )
            // 粉色光晕（第 0 页左下）
            Orb(
                size = 260.dp,
                x = (-40).dp,
                y = (390).dp,
                colors = orb2Colors
            )
            // 青色光晕（第 0 页中下 / 第 1 页左上）
            Orb(
                size = 240.dp,
                x = (screenWidthDp * 0.15f).dp,
                y = (230).dp,
                colors = orb3Colors
            )
            // 橙色光晕（第 1 页右下）
            Orb(
                size = 220.dp,
                x = (screenWidthDp * 1.3f).dp,
                y = (420).dp,
                colors = orb4Colors
            )
            // 紫色光晕（第 1 页上 / 第 2 页左上）
            Orb(
                size = 280.dp,
                x = (screenWidthDp * 1.0f).dp,
                y = (60).dp,
                colors = orb5Colors
            )
            // 浅蓝光晕（第 2 页右 / 第 3 页左）
            Orb(
                size = 240.dp,
                x = (screenWidthDp * 2.2f).dp,
                y = (180).dp,
                colors = orb6Colors
            )
            // 绿色光晕（第 3 页左上）
            Orb(
                size = 220.dp,
                x = (screenWidthDp * 2.85f).dp,
                y = (-15).dp,
                colors = if (isDark) {
                    listOf(Color(0xFF66BB6A).copy(alpha = 0.30f), Color(0xFF66BB6A).copy(alpha = 0f))
                } else {
                    listOf(Color(0xFFA5D6A7).copy(alpha = 0.14f), Color(0xFFA5D6A7).copy(alpha = 0f))
                }
            )
            // 琥珀色光晕（第 3 页右下）
            Orb(
                size = 200.dp,
                x = (screenWidthDp * 3.3f).dp,
                y = (520).dp,
                colors = if (isDark) {
                    listOf(Color(0xFFFFCA28).copy(alpha = 0.28f), Color(0xFFFFCA28).copy(alpha = 0f))
                } else {
                    listOf(Color(0xFFFFE082).copy(alpha = 0.12f), Color(0xFFFFE082).copy(alpha = 0f))
                }
            )
        }
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
