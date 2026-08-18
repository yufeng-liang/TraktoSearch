package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 白色年份角标：无背景填充，纯白文字 + 柔和阴影（悬浮立体感）。
 *
 * 实现：底层为偏移、模糊的暗色文字副本（作为阴影），顶层为纯白文字。
 * 相比逐字描边或 setShadowLayer，两层 Text 最简单稳定，且阴影随字形自然分布。
 *
 * @param year 年份文本（调用方确保已非空）
 * @param modifier 外层修饰符，调用方负责传入对齐（如 align(BottomEnd)）与留白 padding
 * @param fontSize 字号 sp，默认 10
 */
@Composable
fun YearBadge(
    year: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 10
) {
    // 列表卡片高频重组：remember 缓存 TextStyle 与阴影修饰符链，
    // 避免每次组合重复 copy 创建 TextStyle、重复构建 Modifier 链
    // （MaterialTheme.typography 是 @Composable 属性，须在 remember 外读取）
    val labelSmall = MaterialTheme.typography.labelSmall
    val textStyle = remember(fontSize, labelSmall) {
        labelSmall.copy(
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold
        )
    }
    val shadowModifier = remember {
        Modifier
            .offset(x = 1.dp, y = 1.5.dp)
            .blur(3.dp, BlurredEdgeTreatment.Unbounded)
    }
    Box(modifier = modifier) {
        // 阴影层：暗色 + 右下偏移 + 模糊（Unbounded 让阴影不被裁切）
        Text(
            text = year,
            style = textStyle,
            color = Color.Black.copy(alpha = 0.65f),
            modifier = shadowModifier
        )
        // 主层：纯白
        Text(
            text = year,
            style = textStyle,
            color = Color.White
        )
    }
}
