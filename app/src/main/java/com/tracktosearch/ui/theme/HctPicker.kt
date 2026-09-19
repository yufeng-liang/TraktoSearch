package com.tracktosearch.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.data.util.mcu.hct.Hct
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.haptic.rememberAppHaptics

/**
 * FlClash 主题调色板的 Compose 移植（参考其 `lib/widgets/palette.dart`）：HCT 三轴选色
 * —— Hue / Chroma 渐变轨道滑杆 + Tone 离散网格。
 *
 * 轨道外观还原 FlClash：24dp 高胶囊、渐变填充、白色 0.35 高光描边，
 * 竖条 6×48 thumb。Chroma 滑杆刻度与 FlClash 原版一致取 0-10，
 * 轨道渐变仍按 49 档采样到 chroma 150。
 */

/** FlClash Chroma 滑杆的可见范围。 */
internal const val HCT_CHROMA_SLIDER_MAX = 10f

/** Chroma 渐变轨道上限：FlClash `_ChromaTrackShape` 用 150 采样。 */
private const val HCT_CHROMA_GRADIENT_MAX = 150f

/** Tone 列表与网格尺寸，对应 FlClash `_ToneGrid` / `_ColorSchemePreview`。 */
private val HCT_TONES = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
private val HCT_GRID_SPACING = 8.dp
private val HCT_TONE_COLUMN_MIN = 40.dp
private val HCT_PREVIEW_COLUMN_MIN = 68.dp
private val HCT_PREVIEW_HEIGHT = 44.dp

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

/**
 * Chroma 渐变滑杆：0-10（FlClash 原版刻度），轨道颜色随 hue 实时变化。
 *
 * 轨道渐变仍按 FlClash `_ChromaTrackShape` 的 49 档采样到 chroma 150：
 * 滑杆只让用户选低彩度区间，但轨道要展示完整过渡，否则颜色会显得发灰。
 */
@Composable
fun HctChromaSlider(
    hue: Float,
    chroma: Float,
    onChromaChanged: (Float) -> Unit,
) {
    val gradientColors = remember(hue) {
        (0..49).map { i ->
            Color(
                Hct.from(
                    hue.toDouble(),
                    ((i / 49f) * HCT_CHROMA_GRADIENT_MAX).toDouble(),
                    60.0
                ).toInt()
            )
        }
    }
    HctGradientSlider(
        value = chroma,
        min = 0f,
        max = HCT_CHROMA_SLIDER_MAX,
        gradientColors = gradientColors,
        thumbColor = { c -> Color(Hct.from(hue.toDouble(), c.toDouble(), 80.0).toInt()) },
        onValueChanged = onChromaChanged,
    )
}

/**
 * Tone 离散网格：0-100 共 11 档，选中态外扩主题描边环（FlClash 外扩 4dp）。
 *
 * 列数与 FlClash 公式一致：`(maxWidth / 40dp).floor()`，再将剩余宽度均分。
 * 手机弹窗宽度下通常是 6 列，第一行 0-50、第二行 60-100；不能用固定 4 列
 * 或单行塞 11 格，否则会分别变成过高和过窄两种畸形布局。
 */
@Composable
fun HctToneGrid(
    hue: Float,
    chroma: Float,
    selectedTone: Int,
    onToneSelected: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = (maxWidth / HCT_TONE_COLUMN_MIN).toInt().coerceIn(1, HCT_TONES.size)
        val itemSize = (maxWidth - HCT_GRID_SPACING * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(HCT_GRID_SPACING)) {
            HCT_TONES.chunked(columns).forEach { rowTones ->
                Row(horizontalArrangement = Arrangement.spacedBy(HCT_GRID_SPACING)) {
                    rowTones.forEach { tone ->
                        val color = Color(
                            Hct.from(
                                hue.toDouble(),
                                chroma.toDouble(),
                                tone.toDouble()
                            ).toInt()
                        )
                        ToneCell(
                            size = itemSize,
                            color = color,
                            tone = tone,
                            isSelected = tone == selectedTone,
                            onToneSelected = onToneSelected,
                        )
                    }
                    // FlClash 的 Wrap 尾行左对齐；这里补齐空位保证行宽稳定。
                    repeat(columns - rowTones.size) {
                        Box(modifier = Modifier.size(itemSize))
                    }
                }
            }
        }
    }
}

/**
 * 单个 Tone 格子。选中环按 FlClash `_ToneGrid` 几何画在方块外侧：
 *
 * - 色块：44dp、圆角 `AppCorner.sm = 8dp`；
 * - 选中环：外边界比色块四周各外扩 4dp、线宽 4dp、外圆角 12dp；
 *   环向内侧收 4dp 后，内缘圆角正好是 `12 - 4 = 8dp`，与色块圆角重合，
 *   方块四角不会被露在环外。
 *
 * 环用 Canvas 画在同一格内并向外溢出，不参与布局，邻居间距因此保持 FlClash 的 8dp。
 */
@Composable
private fun ToneCell(
    size: androidx.compose.ui.unit.Dp,
    color: Color,
    tone: Int,
    isSelected: Boolean,
    onToneSelected: (Int) -> Unit,
) {
    val haptics = rememberAppHaptics()
    val density = LocalDensity.current
    val cellPx = with(density) { size.toPx() }
    val cornerPx = with(density) { ToneCorner.toPx() }
    val ringInsetPx = with(density) { ToneSelectionInset.toPx() }
    val ringStrokePx = with(density) { ToneSelectionStroke.toPx() }
    // Compose 的 Stroke 以路径为中心向两侧各画一半；想让环完全落在方块外侧，
    // 路径要再内缩半个线宽，这样外边界正好是方块外扩 4dp。
    val ringPathInsetPx = ringInsetPx - ringStrokePx / 2f
    // 外圆角 12dp = 方块圆角 8dp + 外扩 4dp；路径圆角再减半个线宽。
    val ringPathRadiusPx = cornerPx + ringInsetPx - ringStrokePx / 2f
    val ringColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(size)
            .hapticClickable(semantic = HapticSemantic.SEGMENT_TICK) {
                haptics.segmentTick()
                onToneSelected(tone)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = color,
                size = Size(cellPx, cellPx),
                cornerRadius = CornerRadius(cornerPx),
            )
            if (isSelected) {
                drawRoundRect(
                    color = ringColor,
                    topLeft = Offset(-ringPathInsetPx, -ringPathInsetPx),
                    size = Size(
                        cellPx + ringPathInsetPx * 2f,
                        cellPx + ringPathInsetPx * 2f,
                    ),
                    cornerRadius = CornerRadius(ringPathRadiusPx),
                    style = Stroke(width = ringStrokePx),
                )
            }
        }
        Text(
            text = "$tone",
            color = if (tone <= 50) Color.White else Color.Black,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Tone 色块圆角：FlClash `AppCorner.sm`。 */
private val ToneCorner = 8.dp

/** Tone 选中环相对色块的外扩距离：FlClash `_selectionRingInset`。 */
private val ToneSelectionInset = 4.dp

/** Tone 选中环线宽：FlClash `BorderSide(width: _selectionRingInset)`。 */
private val ToneSelectionStroke = 4.dp

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

    // 轨道 Brush 与 thumb 颜色预计算：历史上都在 Canvas 绘制 lambda 内每帧新建
    // Brush（37 色 gradient 对象）并重跑 HCT 色彩数学，拖动掉帧时全压在 Draw 阶段。
    // 宽度经 onSizeChanged 缓存，颜色变化（拖 hue/chroma）只在重组期重算一次。
    var canvasWidthPx by remember { mutableIntStateOf(0) }
    val trackBrush = remember(gradientColors, canvasWidthPx) {
        if (canvasWidthPx <= 0) null
        else Brush.horizontalGradient(gradientColors, 0f, canvasWidthPx.toFloat())
    }
    val resolvedThumbColor = remember(value) { thumbColor(value) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { canvasWidthPx = it.width }
            .height(48.dp)
            .pointerInput(min, max) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(pos: Offset) {
                        val widthPx = size.width
                        if (widthPx <= 0f) return
                        val halfThumb = thumbSize.width / 2f
                        val usableWidth = (widthPx - thumbSize.width).coerceAtLeast(1f)
                        val fraction =
                            ((pos.x - halfThumb) / usableWidth).coerceIn(0f, 1f)
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
            // FlClash `AppCorner.full` 在 24dp 轨道上等价于半高圆角：左右端头是完整
            // 胶囊，不是近似方角的超椭圆。用 roundRect 才能得到和原版一致的圆润端头。
            val trackRadius = CornerRadius(trackHeight / 2f)
            val brush = trackBrush ?: return@Canvas
            drawRoundRect(
                brush = brush,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = trackRadius,
            )
            // 白色 0.35 高光描边（内缩 1px），圆角跟着内缩后的胶囊同步收窄。
            val innerRect = rect.deflate(1f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.35f),
                topLeft = innerRect.topLeft,
                size = innerRect.size,
                cornerRadius = CornerRadius(trackHeight / 2f - 1f),
                style = Stroke(width = strokeWidth),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
            // 与 Slider 的 thumb 几何一致：thumb 中心在两端各内缩半个 thumb 宽，
            // 这样点最左/最右时 thumb 不会被裁，触摸位置和视觉位置也对齐。
            val usableWidth = (size.width - thumbSize.width).coerceAtLeast(0f)
            val left = thumbSize.width / 2f + fraction * usableWidth - thumbSize.width / 2f
            val top = (size.height - thumbSize.height) / 2f
            drawRoundRect(
                color = resolvedThumbColor,
                topLeft = Offset(left, top),
                size = thumbSize,
                cornerRadius = CornerRadius(thumbCornerRadius),
            )
        }
    }
}

/**
 * 自适应预览网格：8 个 M3 role（含 on* 前景），列数与 FlClash `_ColorSchemePreview`
 * 一致：`(maxWidth / 68dp).floor()`，保证每格至少 68dp 宽，label 才读得清。
 *
 * 不能用固定 4 列 + weight：窄屏弹窗里会把 label 压成 9sp 都读不清的碎片，
 * 换行时还会出现「一半换行、一半留空」的乱序。
 */
@Composable
fun HctColorSchemePreviewGrid(
    scheme: androidx.compose.material3.ColorScheme,
) {
    val roles = remember(scheme) {
        listOf(
            Triple(scheme.primary, scheme.onPrimary, "Primary"),
            Triple(scheme.secondary, scheme.onSecondary, "Secondary"),
            Triple(scheme.tertiary, scheme.onTertiary, "Tertiary"),
            Triple(scheme.error, scheme.onError, "Error"),
            Triple(scheme.surface, scheme.onSurface, "Surface"),
            Triple(scheme.primaryContainer, scheme.onPrimaryContainer, "Primary\nCont."),
            Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, "Secondary\nCont."),
            Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, "Tertiary\nCont."),
        )
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = (maxWidth / HCT_PREVIEW_COLUMN_MIN).toInt().coerceIn(1, roles.size)
        val itemWidth = (maxWidth - HCT_GRID_SPACING * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(HCT_GRID_SPACING)) {
            roles.chunked(columns).forEach { rowRoles ->
                Row(horizontalArrangement = Arrangement.spacedBy(HCT_GRID_SPACING)) {
                    rowRoles.forEach { (background, foreground, label) ->
                        Box(
                            modifier = Modifier
                                .width(itemWidth)
                                .height(HCT_PREVIEW_HEIGHT)
                                // FlClash `_ColorSchemePreview` 同样用 AppShape.sm = 8dp。
                                .clip(RoundedCornerShape(8.dp))
                                .background(background),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = foreground,
                                fontSize = 9.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
