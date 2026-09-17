package com.tracktosearch.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.data.util.mcu.hct.Hct
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * FlClash 主题调色板的 Compose 移植（参考其 `lib/widgets/palette.dart`）：HCT 三轴选色
 * —— Hue / Chroma 渐变轨道滑杆 + Tone 离散网格。
 *
 * 轨道外观还原 FlClash：24dp 高超椭圆（n=5 近似）、渐变填充、白色 0.35 高光描边、
 * 底部黑色 0.12 细线，竖条 6×48 thumb。Chroma 上限取 132（Material 常用最大彩度，
 * 修正 FlClash 只到 10 的局限），渐变采样仍按 FlClash 的 49 档。
 */

/** Hue 渐变滑杆：0-360，轨道色相 tone 固定 60。 */
@Composable
fun HctHueSlider(
    hue: Float,
    onHueChanged: (Float) -> Unit,
) {
    val gradientColors = remember {
        (0..360 step 10).map { Color(Hct.from(it.toDouble(), 100.0, 60.0).toInt()) }
    }
    HctGradientSlider(
        value = hue,
        min = 0f,
        max = 360f,
        gradientColors = gradientColors,
        thumbColor = { h -> Color(Hct.from(h.toDouble(), 100.0, 80.0).toInt()) },
        onValueChanged = onHueChanged,
    )
}

/** Chroma 渐变滑杆：0-132，轨道颜色随 hue 实时变化。 */
@Composable
fun HctChromaSlider(
    hue: Float,
    chroma: Float,
    onChromaChanged: (Float) -> Unit,
) {
    val gradientColors = remember(hue) {
        (0..49).map { i ->
            Color(Hct.from(hue.toDouble(), ((i / 49f) * 132f).toDouble(), 60.0).toInt())
        }
    }
    HctGradientSlider(
        value = chroma,
        min = 0f,
        max = 132f,
        gradientColors = gradientColors,
        thumbColor = { c -> Color(Hct.from(hue.toDouble(), c.toDouble(), 80.0).toInt()) },
        onValueChanged = onChromaChanged,
    )
}

/** Tone 离散网格：0-100 共 11 档，选中态外扩主题描边环（FlClash 外扩 4px）。 */
@Composable
fun HctToneGrid(
    hue: Float,
    chroma: Float,
    selectedTone: Int,
    onToneSelected: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (tone in 0..100 step 10) {
            val color = Color(Hct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt())
            val isSelected = tone == selectedTone
            // 网格外框高 44dp 时，选中环比格子大 8dp（上下左右各外扩 4）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    // 负偏移让描边环向外溢出格子 4dp（外扩选中环，父层不裁剪即可显示；
                    // 不能写负 padding，Compose padding 要求非负否则组合期抛异常）
                    Box(
                        modifier = Modifier
                            .offset(x = (-4).dp, y = (-4).dp)
                            .fillMaxSize()
                            .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(11.dp)),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color)
                        .clickable { onToneSelected(tone) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$tone",
                        color = if (tone <= 50) Color.White else Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** 渐变轨道 + 竖条 thumb 的通用滑杆（FlClash _HueSlider/_ChromaSlider 的合成）。 */
@Composable
private fun HctGradientSlider(
    value: Float,
    min: Float,
    max: Float,
    gradientColors: List<Color>,
    thumbColor: (Float) -> Color,
    onValueChanged: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val trackHeight = with(density) { 24.dp.toPx() }
    val trackTopOffset = with(density) { 12.dp.toPx() } // (48-24)/2，thumb 48 高轨道垂直居中
    val thumbSize = with(density) { Size(6.dp.toPx(), 48.dp.toPx()) }
    val thumbCornerRadius = with(density) { 3.dp.toPx() }
    val strokeWidth = with(density) { 1.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(pos: Offset) {
                        val widthPx = size.width
                        if (widthPx <= 0f) return
                        val fraction = (pos.x / widthPx).coerceIn(0f, 1f)
                        onValueChanged(min + fraction * (max - min))
                    }
                    update(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (event.changes.any { it.isConsumed }) break
                        update(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rect = Rect(0f, trackTopOffset, size.width, trackTopOffset + trackHeight)
            drawPath(
                path = superellipsePath(rect),
                brush = Brush.horizontalGradient(
                    colors = gradientColors,
                    startX = rect.left,
                    endX = rect.right,
                ),
            )
            // 白色 0.35 高光描边（内缩 1px）
            drawPath(
                path = superellipsePath(rect.deflate(1f)),
                color = Color.White.copy(alpha = 0.35f),
                style = Stroke(width = strokeWidth),
            )
            // 底部黑色 0.12 细线（左右缩进 12px）
            drawLine(
                color = Color.Black.copy(alpha = 0.12f),
                start = Offset(rect.left + 12f, rect.bottom - 2f),
                end = Offset(rect.right - 12f, rect.bottom - 2f),
                strokeWidth = strokeWidth,
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
            val left = fraction * size.width - thumbSize.width / 2f
            val top = (size.height - thumbSize.height) / 2f
            drawRoundRect(
                color = thumbColor(value),
                topLeft = Offset(left, top),
                size = thumbSize,
                cornerRadius = CornerRadius(thumbCornerRadius),
            )
        }
    }
}

/**
 * 超椭圆路径：|x/a|^n + |y/b|^n = 1 的参数化近似，n=5 接近 FlClash 的 RSuperellipse。
 * 圆角方形（squircle）风格，介于胶囊与直角矩形之间。
 */
internal fun superellipsePath(rect: Rect, n: Float = 5f): Path {
    val a = rect.width / 2f
    val b = rect.height / 2f
    val cx = rect.center.x
    val cy = rect.center.y
    val expFactor = 2f / n
    val steps = 100
    val path = Path()
    path.moveTo(cx + a, cy) // 参数 t=0 起点，保证闭合平滑
    for (i in 1..steps) {
        val t = (i.toFloat() / steps) * 2 * PI.toFloat()
        val px = cx + a * signedAbsPow(cos(t), expFactor)
        val py = cy + b * signedAbsPow(sin(t), expFactor)
        path.lineTo(px, py)
    }
    path.close()
    return path
}

private fun signedAbsPow(value: Float, exp: Float): Float =
    if (value >= 0f) abs(value).pow(exp) else -abs(value).pow(exp)