package com.tracktosearch.ui.screen.swiftie.bracelet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer

/** 一颗珠子。 */
internal sealed interface SwiftieBead {
    /** 白色方形字母珠，正面印彩色字。 */
    class Letter(val char: Char, val inkColor: Color) : SwiftieBead

    /** 半透明彩色圆珠。 */
    class Round(val color: Color) : SwiftieBead

    /** 红色心形珠。整条手链只有一颗。 */
    object Heart : SwiftieBead
}

/**
 * 字母珠印字的参考字号（px）。绘制时按珠子实际尺寸缩放。
 *
 * 印字尺寸与珠子尺寸解耦：珠子尺寸要等到 draw 阶段才知道，而 `TextMeasurer`
 * 只能在组合阶段用。
 */
internal const val BEAD_LETTER_REFERENCE_PX: Float = 100f

/** 单位空间（0..1）里的心形轮廓。绘制时缩放，避免每帧重建 `Path`。 */
private val UNIT_HEART: Path = Path().apply {
    moveTo(0.5f, 0.92f)
    cubicTo(-0.18f, 0.52f, 0.16f, 0.02f, 0.5f, 0.30f)
    cubicTo(1.18f, 0.02f, 0.84f, 0.52f, 0.5f, 0.92f)
    close()
}

/** 把要用到的字符预排一遍。字母珠只有 `13 87 SWIFTIE` 这些字符，一次量完够用整条序列。 */
@Composable
internal fun rememberBeadLetterLayouts(chars: String): Map<Char, TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(chars, measurer, density) {
        val style = TextStyle(
            fontSize = with(density) { BEAD_LETTER_REFERENCE_PX.toSp() },
            fontWeight = FontWeight.Bold
        )
        chars.toSet().associateWith { char -> measurer.measure(char.toString(), style) }
    }
}

/**
 * 画一颗珠子。
 *
 * @param center 珠心（px）
 * @param size 珠子边长 / 直径（px）
 * @param rotationDeg 该珠固定的随机旋转，±8°（Spec §8）
 * @param highlight 光源偏移，各轴 -1f..1f，由加速度计给。全 0 就是正对着看
 */
internal fun DrawScope.drawSwiftieBead(
    bead: SwiftieBead,
    center: Offset,
    size: Float,
    rotationDeg: Float,
    highlight: Offset,
    letterLayouts: Map<Char, TextLayoutResult>
) {
    drawBeadShadow(center, size)
    rotate(degrees = rotationDeg, pivot = center) {
        when (bead) {
            is SwiftieBead.Letter -> drawLetterBead(bead, center, size, highlight, letterLayouts[bead.char])
            is SwiftieBead.Round -> drawRoundBead(bead.color, center, size, highlight)
            SwiftieBead.Heart -> drawHeartBead(center, size, highlight)
        }
    }
}

/**
 * 向下的柔和投影。
 *
 * 用径向渐变而不是 `Modifier.blur` —— 后者在 API 31 以下**静默失效**，
 * 那批机器上会变成一块硬边黑影。
 */
private fun DrawScope.drawBeadShadow(center: Offset, size: Float) {
    val at = Offset(center.x, center.y + size * 0.22f)
    val radius = size * 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
            center = at,
            radius = radius
        ),
        radius = radius,
        center = at
    )
}

private fun DrawScope.drawLetterBead(
    bead: SwiftieBead.Letter,
    center: Offset,
    size: Float,
    highlight: Offset,
    layout: TextLayoutResult?
) {
    val half = size / 2f
    // 0.24：四角明显圆润，但还看得出是方珠。再大就成圆角骰子了
    val corner = CornerRadius(size * 0.24f)
    // 正面微凸：径向渐变的中心朝光源偏，边缘落到浅灰，读起来就是个鼓面
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, Color(0xFFF2EFEC), Color(0xFFD8D2CC)),
            center = Offset(
                x = center.x - highlight.x * size * 0.22f,
                y = center.y - highlight.y * size * 0.22f
            ),
            radius = size * 0.85f
        ),
        topLeft = Offset(center.x - half, center.y - half),
        size = Size(size, size),
        cornerRadius = corner
    )

    // 彩色印刷字。0.62：字占珠面约六成，四周留出白边，跟真字母珠一样
    layout?.let { measured ->
        val scale = size * 0.62f / BEAD_LETTER_REFERENCE_PX
        val glyphWidth = measured.size.width * scale
        val glyphHeight = measured.size.height * scale
        withTransform({
            translate(center.x - glyphWidth / 2f, center.y - glyphHeight / 2f)
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
        }) {
            drawText(textLayoutResult = measured, color = bead.inkColor)
        }
    }

    // 左上高光点：判断「这是真塑料」的核心线索，位置随倾斜走
    drawOval(
        color = Color.White,
        topLeft = Offset(
            x = center.x - size * (0.34f + highlight.x * 0.10f),
            y = center.y - size * (0.36f + highlight.y * 0.10f)
        ),
        size = Size(size * 0.30f, size * 0.20f),
        alpha = 0.85f
    )

    // 底部环境反射：真塑料底下总有一道从桌面弹回来的淡光
    drawArc(
        color = Color.White,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(center.x - half * 0.78f, center.y - half * 0.78f),
        size = Size(size * 0.78f, size * 0.78f),
        alpha = 0.22f,
        style = Stroke(width = size * 0.07f)
    )
}

private fun DrawScope.drawRoundBead(
    color: Color,
    center: Offset,
    size: Float,
    highlight: Offset
) {
    val radius = size / 2f
    // 半透明：光源侧透光偏淡、中段最浓、背光侧回落。三段渐变就够读出「不是实心」
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = 0.55f),
                color.copy(alpha = 0.92f),
                color.copy(alpha = 0.70f)
            ),
            center = Offset(
                x = center.x - highlight.x * radius * 0.40f,
                y = center.y - highlight.y * radius * 0.40f
            ),
            radius = radius * 1.15f
        ),
        radius = radius,
        center = center
    )
    // 内反光：背光侧内壁被照亮的一小弧。这一笔是「半透明」最关键的线索
    drawArc(
        color = Color.White,
        startAngle = 10f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(center.x - radius * 0.62f, center.y - radius * 0.62f),
        size = Size(radius * 1.24f, radius * 1.24f),
        alpha = 0.30f,
        style = Stroke(width = radius * 0.16f)
    )
    // 左上高光点
    drawCircle(
        color = Color.White,
        radius = radius * 0.20f,
        center = Offset(
            x = center.x - (0.36f + highlight.x * 0.12f) * radius,
            y = center.y - (0.38f + highlight.y * 0.12f) * radius
        ),
        alpha = 0.88f
    )
}

private fun DrawScope.drawHeartBead(center: Offset, size: Float, highlight: Offset) {
    // 心形珠比同排方珠略大一点点，视觉重量才配得上
    val side = size * 1.06f
    withTransform({
        translate(center.x - side / 2f, center.y - side / 2f)
        scale(scaleX = side, scaleY = side, pivot = Offset.Zero)
    }) {
        drawPath(
            path = UNIT_HEART,
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFFF7A90), Color(0xFFD81B3E)),
                center = Offset(0.36f, 0.30f),
                radius = 0.85f
            )
        )
    }
    // 心形也有塑料高光，位置在左上鼓包上
    drawOval(
        color = Color.White,
        topLeft = Offset(
            x = center.x - side * (0.26f + highlight.x * 0.08f),
            y = center.y - side * (0.24f + highlight.y * 0.08f)
        ),
        size = Size(side * 0.22f, side * 0.15f),
        alpha = 0.75f
    )
}
