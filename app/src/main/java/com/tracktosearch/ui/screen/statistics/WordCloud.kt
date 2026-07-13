package com.tracktosearch.ui.screen.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 词云单项：词语及其权重（出现频次）。
 */
data class WordCloudItem(val word: String, val weight: Int)

/**
 * 经过布局计算后的单词摆放信息。
 *
 * @param word 词语
 * @param rect 在画布坐标中的摆放矩形（含内边距，已绝对定位）
 * @param color 绘制颜色（取自主题调色板）
 */
private data class PlacedWord(
    val word: String,
    val rect: Rect,
    val color: Color,
    val style: TextStyle
)

/**
 * 词云组件。
 *
 * 按权重降序取前 [maxWords] 个高频词，字号随权重在 12sp~36sp 间归一化，
 * 颜色循环取自 [androidx.compose.material3.MaterialTheme] 调色板（兼容深色模式），
 * 布局使用固定种子（Random(42)）的螺旋搜索，尽量不与已放置矩形重叠；
 * 放不下则缩小重试，仍失败则跳过（保证不崩、不重叠堆叠）。同一输入永远得到相同布局。
 *
 * 空列表时不绘制任何内容（空态由调用方处理）。
 *
 * @param words 词频列表（无需预先排序）
 * @param modifier 修饰符，建议至少提供宽度（如 fillMaxWidth），高度由组件按内容估算
 * @param maxWords 仅取前 N 个高频词，避免过密
 */
@Composable
fun WordCloud(
    words: List<WordCloudItem>,
    modifier: Modifier = Modifier,
    maxWords: Int = 60
) {
    if (words.isEmpty()) return

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val colorScheme = androidx.compose.material3.MaterialTheme.colorScheme

    // 主题协调调色板（均在 surface 背景上可读）
    val palette = remember(colorScheme) {
        listOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
            colorScheme.onSurfaceVariant,
            colorScheme.primary.copy(alpha = 0.85f),
            colorScheme.secondary.copy(alpha = 0.85f),
            colorScheme.tertiary.copy(alpha = 0.85f)
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val canvasWidthPx = with(density) { maxWidth.toPx() }

        // 布局计算放入 remember，仅在 words / 宽度 / 调色板变化时重算，onDraw 只负责绘制
        val layout = remember(words, canvasWidthPx, palette, maxWords) {
            computeLayout(
                words = words,
                canvasWidthPx = canvasWidthPx,
                palette = palette,
                textMeasurer = textMeasurer,
                density = density,
                maxWords = maxWords
            )
        }

        val canvasHeightDp = with(density) { layout.heightPx.toDp() }

        Canvas(modifier = Modifier.fillMaxWidth().height(canvasHeightDp)) {
            for (placed in layout.placed) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = placed.word,
                    topLeft = Offset(placed.rect.left, placed.rect.top),
                    style = placed.style,
                    color = placed.color,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Visible
                )
            }
        }
    }
}

/**
 * 计算词云布局：归一化字号 -> 测量文字尺寸 -> 螺旋搜索无重叠摆放 -> 计算整体包围盒。
 */
private fun computeLayout(
    words: List<WordCloudItem>,
    canvasWidthPx: Float,
    palette: List<Color>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    maxWords: Int
): WordCloudLayout {
    val minFontPx = with(density) { 12.sp.toPx() }
    val maxFontPx = with(density) { 36.sp.toPx() }
    val rand = Random(42)

    // 1. 按权重降序取前 N
    val top = words.sortedByDescending { it.weight }
        .take(maxWords.coerceAtLeast(1).coerceAtMost(words.size))

    if (top.isEmpty()) return WordCloudLayout(emptyList(), 0f)

    val maxWeight = top.first().weight.toFloat()
    val minWeight = top.last().weight.toFloat()
    val weightSpan = (maxWeight - minWeight).coerceAtLeast(1f)

    // 2. 测量每个词在归一化字号下的尺寸
    data class Measured(val item: WordCloudItem, val fontSizePx: Float, val width: Float, val height: Float)

    val measured = top.mapIndexed { index, item ->
        val t = (item.weight.toFloat() - minWeight) / weightSpan
        val fontSizePx = lerp(minFontPx, maxFontPx, t)
        val style = TextStyle(fontSize = with(density) { fontSizePx.toSp() })
        val result = textMeasurer.measure(item.word, style)
        val padX = fontSizePx * 0.25f
        val padY = fontSizePx * 0.15f
        Measured(
            item = item,
            fontSizePx = fontSizePx,
            width = result.size.width + padX * 2f,
            height = result.size.height + padY * 2f
        )
    }

    val placed = mutableListOf<PlacedWord>()
    val padding = maxFontPx * 0.4f
    val areaSize = max(canvasWidthPx, maxFontPx * 4f)
    val center = Offset(areaSize / 2f, areaSize / 2f)

    // 3. 逐个螺旋放置，最大词先放中心
    for ((i, m) in measured.withIndex()) {
        val color = palette[i % palette.size]
        val style = TextStyle(
            fontSize = with(density) { m.fontSizePx.toSp() },
            color = color
        )
        var rect = spiralPlace(
            width = m.width,
            height = m.height,
            center = center,
            placed = placed,
            rand = rand,
            areaSize = areaSize
        )
        if (rect == null) {
            // 缩小到最小字号重试一次
            val minStyle = TextStyle(
                fontSize = with(density) { minFontPx.toSp() },
                color = color
            )
            val minResult = textMeasurer.measure(m.item.word, minStyle)
            val minW = minResult.size.width + m.width * 0.1f
            val minH = minResult.size.height + m.height * 0.1f
            rect = spiralPlace(
                width = minW,
                height = minH,
                center = center,
                placed = placed,
                rand = rand,
                areaSize = areaSize
            ) ?: continue // 仍放不下则跳过，保证不崩、不堆叠
        }
        placed.add(PlacedWord(m.item.word, rect, color, style))
    }

    // 4. 计算包围盒并居中（水平）平移到画布宽度内
    if (placed.isEmpty()) return WordCloudLayout(emptyList(), 0f)

    val minX = placed.minOf { it.rect.left }
    val maxX = placed.maxOf { it.rect.right }
    val minY = placed.minOf { it.rect.top }
    val maxY = placed.maxOf { it.rect.bottom }

    val contentWidth = maxX - minX
    val offsetX = padding + (canvasWidthPx - contentWidth) / 2f - minX
    val offsetY = padding - minY

    val shifted = placed.map { p ->
        p.copy(rect = p.rect.translate(Offset(offsetX, offsetY)))
    }

    val heightPx = (maxY - minY) + padding * 2f
    return WordCloudLayout(shifted, heightPx)
}

/**
 * 阿基米德螺旋 + 黄金角搜索无重叠位置。
 * 返回以左上角定位的矩形；找不到则返回 null。
 */
private fun spiralPlace(
    width: Float,
    height: Float,
    center: Offset,
    placed: List<PlacedWord>,
    rand: Random,
    areaSize: Float
): Rect? {
    val goldenAngle = 2.399963f
    val maxAttempts = 400
    val step = (width.coerceAtLeast(height)) * 0.25f + 2f
    var radius = 0f
    var angle = rand.nextFloat() * (Math.PI * 2f).toFloat()

    for (attempt in 0 until maxAttempts) {
        val cx = center.x + radius * cos(angle)
        val cy = center.y + radius * sin(angle)
        val rect = Rect(
            left = cx - width / 2f,
            top = cy - height / 2f,
            right = cx + width / 2f,
            bottom = cy + height / 2f
        )
        // 超出虚拟区域则放弃（半径过大，避免越界重叠）
        if (rect.left < 0f || rect.top < 0f || rect.right > areaSize || rect.bottom > areaSize) {
            return null
        }
        if (!overlaps(placed, rect)) {
            return rect
        }
        radius += step
        angle += goldenAngle
    }
    return null
}

/**
 * 是否与已放置矩形（带内边距）重叠。
 */
private fun overlaps(placed: List<PlacedWord>, rect: Rect): Boolean {
    for (p in placed) {
        if (Rect.overlaps(rect, p.rect)) return true
    }
    return false
}

/**
 * 布局结果：已摆放词列表 + 画布高度（px）。
 */
private data class WordCloudLayout(
    val placed: List<PlacedWord>,
    val heightPx: Float
)
