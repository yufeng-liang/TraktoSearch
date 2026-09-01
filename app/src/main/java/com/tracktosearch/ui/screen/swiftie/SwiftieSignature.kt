package com.tracktosearch.ui.screen.swiftie

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.tracktosearch.R
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** 11 段书写合计占用。 */
const val SIGNATURE_WRITE_MS: Long = 6_800L

/** 段间落笔停顿。10 个间隙共 500ms。 */
const val SIGNATURE_PAUSE_MS: Long = 50L

/** 写完之后整字通体闪一次。 */
const val SIGNATURE_FLASH_MS: Long = 700L

/**
 * 一个字形在签名路径里的位置。
 *
 * @param fromX 该字形左边界（已归一到路径包围盒左上角为原点）
 * @param toX 下一个字形的左边界；末位取路径右边界
 * @param tipY 该字形竖向中心，笔尖亮点跟着它走
 */
internal class SignatureGlyph(val fromX: Float, val toX: Float, val tipY: Float)

/** 一段笔画：[startMs]..[endMs] 之间把揭示前沿从 [fromX] 推到 [toX]。 */
internal class SignatureStroke(
    val fromX: Float,
    val toX: Float,
    val fromTipY: Float,
    val toTipY: Float,
    val startMs: Long,
    val endMs: Long
)

/**
 * 按字形宽度分配书写时间。
 *
 * 宽字形写得久、窄字形写得快 —— 笔速恒定才像手写；平均分配会让 `l` 一闪而过、
 * `T` 慢得像卡住。取整误差全部由最后一段吸收，所以整表总长严格等于
 * `writeMs + pauseMs × (n - 1)`。
 */
internal fun buildSignatureStrokes(
    glyphs: List<SignatureGlyph>,
    writeMs: Long,
    pauseMs: Long
): List<SignatureStroke> {
    if (glyphs.isEmpty()) return emptyList()
    // coerceAtLeast(1f)：零宽字形会让下面除出 NaN
    val widths = glyphs.map { (it.toX - it.fromX).coerceAtLeast(1f) }
    val totalWidth = widths.sum()
    var cursor = 0L
    var spent = 0L
    return glyphs.mapIndexed { index, glyph ->
        val duration = if (index == glyphs.lastIndex) {
            writeMs - spent
        } else {
            (writeMs * widths[index] / totalWidth).toLong()
        }
        spent += duration
        val start = cursor
        val end = start + duration
        cursor = end + pauseMs
        SignatureStroke(
            fromX = glyph.fromX,
            toX = glyph.toX,
            // 笔尖从上一个字形的高度过渡到这一个，段间不会突然跳一格
            fromTipY = glyphs[(index - 1).coerceAtLeast(0)].tipY,
            toTipY = glyph.tipY,
            startMs = start,
            endMs = end
        )
    }
}

/** [elapsedMs] 时刻揭示前沿的横坐标。 */
internal fun List<SignatureStroke>.revealXAt(elapsedMs: Long): Float {
    if (isEmpty()) return 0f
    forEach { stroke ->
        if (elapsedMs < stroke.startMs) return stroke.fromX
        if (elapsedMs <= stroke.endMs) {
            val span = (stroke.endMs - stroke.startMs).coerceAtLeast(1L)
            // 线性推进：书写速度在一个字形内恒定，收尾不减速
            val p = (elapsedMs - stroke.startMs).toFloat() / span
            return stroke.fromX + (stroke.toX - stroke.fromX) * p
        }
    }
    return last().toX
}

/** [elapsedMs] 时刻笔尖亮点的纵坐标。 */
internal fun List<SignatureStroke>.tipYAt(elapsedMs: Long): Float {
    if (isEmpty()) return 0f
    forEach { stroke ->
        if (elapsedMs < stroke.startMs) return stroke.fromTipY
        if (elapsedMs <= stroke.endMs) {
            val span = (stroke.endMs - stroke.startMs).coerceAtLeast(1L)
            val p = (elapsedMs - stroke.startMs).toFloat() / span
            return stroke.fromTipY + (stroke.toTipY - stroke.fromTipY) * p
        }
    }
    return last().toTipY
}

/** 书写全部结束的时刻。收尾闪光从这里起算。 */
internal val List<SignatureStroke>.writeEndMs: Long
    get() = if (isEmpty()) 0L else last().endMs

/** 签名文字。**永不翻译**，也不做任何本地化替换。 */
private const val SIGNATURE_TEXT = "Taylor Swift"

/** 笔尖亮点半径。 */
private val TIP_RADIUS = 3.5.dp

/**
 * 签名宽度上限。
 *
 * 300dp 是设计值，但**只能当上限用** —— 签名的高度由字形包围盒等比推出来，
 * 写死 300dp 在 320dp 宽的小屏（去掉两侧 20dp 内边距只剩 280dp）会横向溢出，
 * 而外层是 `Column` 不是 `clip`，溢出的那截要么被父级裁掉要么把落款挤歪。
 */
private val SIGNATURE_MAX_WIDTH = 300.dp

/** 定格闪粉用的相位。挑 0.35 是箔面渐变正好偏亮的一档，静止看着不发灰。 */
private const val SIGNATURE_STILL_PHASE = 0.35f

/** 构建好的签名图形：归一到左上角为原点的轮廓 + 尺寸 + 笔画时间表。 */
private class SignatureArt(
    val path: Path,
    val width: Float,
    val height: Float,
    val strokes: List<SignatureStroke>
)

/**
 * 量字形、取轮廓、算时间表。**只在字号变化时跑一次**（`remember` 缓存）。
 *
 * 两次 `getTextPath`：第一次按 100px 探路以求出达到 [targetWidthPx] 需要的字号，
 * 第二次才是真正要用的轮廓。`Pacifico` 的花体会伸出字符宽度之外，所以宽高一律取
 * **路径包围盒**而不是 `measureText`。
 */
private fun buildSignatureArt(context: Context, targetWidthPx: Float): SignatureArt {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = ResourcesCompat.getFont(context, R.font.swiftie_script)
        textSize = 100f
    }
    val probe = android.graphics.Path()
    paint.getTextPath(SIGNATURE_TEXT, 0, SIGNATURE_TEXT.length, 0f, 0f, probe)
    val probeBounds = android.graphics.RectF()
    probe.computeBounds(probeBounds, true)
    paint.textSize = 100f * targetWidthPx / probeBounds.width().coerceAtLeast(1f)

    val full = android.graphics.Path()
    paint.getTextPath(SIGNATURE_TEXT, 0, SIGNATURE_TEXT.length, 0f, 0f, full)
    val bounds = android.graphics.RectF()
    full.computeBounds(bounds, true)
    // 平移到包围盒左上角为原点；下面所有坐标都在这个空间里
    full.offset(-bounds.left, -bounds.top)

    val glyphPath = android.graphics.Path()
    val glyphBounds = android.graphics.RectF()
    val glyphs = SIGNATURE_TEXT.indices
        // 空格不是一段笔画。11 段：Taylor 六段 + Swift 五段
        .filter { !SIGNATURE_TEXT[it].isWhitespace() }
        .map { index ->
            glyphPath.reset()
            paint.getTextPath(SIGNATURE_TEXT, index, index + 1, 0f, 0f, glyphPath)
            glyphPath.computeBounds(glyphBounds, true)
            SignatureGlyph(
                fromX = paint.measureText(SIGNATURE_TEXT, 0, index) - bounds.left,
                toX = paint.measureText(SIGNATURE_TEXT, 0, index + 1) - bounds.left,
                tipY = (glyphBounds.top + glyphBounds.bottom) / 2f - bounds.top
            )
        }
        .toMutableList()

    // 末笔的字符前进量可能短于花体实际伸出的右边界，不补齐会留一小截永远不揭示
    val last = glyphs.last()
    glyphs[glyphs.lastIndex] = SignatureGlyph(
        fromX = last.fromX,
        toX = max(last.toX, bounds.width()),
        tipY = last.tipY
    )

    return SignatureArt(
        path = full.asComposePath(),
        width = bounds.width(),
        height = bounds.height(),
        strokes = buildSignatureStrokes(
            glyphs = glyphs,
            writeMs = SIGNATURE_WRITE_MS,
            pauseMs = SIGNATURE_PAUSE_MS
        )
    )
}

/** 收尾闪光的强度。一进一出的正弦包络，不会停在过曝。 */
private fun flashAlpha(elapsedMs: Long, writeEndMs: Long): Float {
    if (elapsedMs <= writeEndMs) return 0f
    val p = ((elapsedMs - writeEndMs).toFloat() / SIGNATURE_FLASH_MS).coerceIn(0f, 1f)
    return sin(p * PI.toFloat()) * 0.55f
}

/**
 * 逐段揭示的闪粉签名（Spec §7）。
 *
 * @param elapsedInSignature 签名段起点以来的毫秒。给一个 ≥ 8000 的常量就是写完的样子，
 *   「减少动效」的静态终态正是这么用的
 * @param animated 闪粉是否要一直闪。静态终态传 false —— 那是一张停着的画面，
 *   挂一条无限动画会把帧时钟永久唤着，用户忘了退出就一直在耗电。
 *   传 false 之后 `time` 恒定，两个 `drawBehind` 都不再有变化的 state 读，只画一次
 * @param widthLimit 宽度上限，默认 [SIGNATURE_MAX_WIDTH]。实际取它与可用宽度的较小值
 */
@Composable
fun SwiftieSignature(
    elapsedInSignature: () -> Long,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    widthLimit: Dp = SIGNATURE_MAX_WIDTH
) {
    BoxWithConstraints(modifier = modifier) {
        SignatureArtwork(
            elapsedInSignature = elapsedInSignature,
            animated = animated,
            // maxWidth 在宽度无约束时是 Dp.Infinity，minOf 会落到 widthLimit
            targetWidth = minOf(maxWidth, widthLimit)
        )
    }
}

@Composable
private fun SignatureArtwork(
    elapsedInSignature: () -> Long,
    animated: Boolean,
    targetWidth: Dp
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val targetWidthPx = with(density) { targetWidth.toPx() }
    val art = remember(context, targetWidthPx) { buildSignatureArt(context, targetWidthPx) }
    val sparkleCount = if (rememberIsLowRamDevice()) 24 else 60
    val sparkles = remember(sparkleCount) { buildSparkles(sparkleCount) }
    val time = if (animated) {
        rememberGlitterTime()
    } else {
        remember { mutableFloatStateOf(SIGNATURE_STILL_PHASE) }
    }

    Box(
        modifier = Modifier.size(
            width = with(density) { art.width.toDp() },
            height = with(density) { art.height.toDp() }
        )
    ) {
        Spacer(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    val elapsed = elapsedInSignature()
                    val revealX = art.strokes.revealXAt(elapsed)
                    if (revealX <= 0f) return@drawBehind
                    val flash = flashAlpha(elapsed, art.strokes.writeEndMs)
                    // 箔面只铺到揭示前沿；前沿右边还没「写」到
                    clipRect(left = 0f, top = 0f, right = revealX, bottom = size.height) {
                        drawGlitterBody(time, sparkles)
                        if (flash > 0f) drawRect(color = Color.White, alpha = flash)
                    }
                    // 最后用字形轮廓抠形。DstIn 只保留 mask 覆盖到的像素，
                    // 边缘比 clipPath 干净（与 Phase B 的灯箱同一套做法）
                    drawPath(
                        path = art.path,
                        color = Color.White,
                        blendMode = BlendMode.DstIn
                    )
                }
        )

        Spacer(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val elapsed = elapsedInSignature()
                    if (elapsed > art.strokes.writeEndMs) return@drawBehind
                    val center = Offset(
                        x = art.strokes.revealXAt(elapsed),
                        y = art.strokes.tipYAt(elapsed)
                    )
                    val radius = TIP_RADIUS.toPx()
                    // 外圈柔光 + 内圈实心：一颗看得出在走的笔尖
                    drawCircle(color = Color.White, radius = radius * 2.6f, center = center, alpha = 0.28f)
                    drawCircle(color = Color.White, radius = radius, center = center, alpha = 0.95f)
                }
        )
    }
}
