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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.tracktosearch.R
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 十二笔书写合计占用。
 *
 * 2026-09-13 从 6800 缩短到 4400（快约 1.55 倍）：需求方要把手链的进场叠进签名里、
 * 把省下的时间留给最终合影。**代价是文案上说好的手速对齐没了** —— 原先刻意让这支笔
 * 写得和背景那台打字机一样快（它 86ms 打一个字符、这里 93ms 写一个字符，两个家族长的
 * 名字不会一处像快进、一处像慢放），现在笔是 60ms 一个字符、打字机还是 86ms。
 * 要恢复对齐就得同时改 `TTPD_PREROLL_MS`（打字机那两行的总时长），那是另一件事。
 *
 * 2026-09-25 再提速 20%（4400 ÷ 1.2 = 3667，笔速是原来的 1.2 倍）：省下的 733ms 由弹性段
 * [SwiftieTimeline.FINAL_HOLD_MS] 吸收。同一轮里打字机那两行被**放慢**了（`TTPD_TYPE_MS`
 * 3200 → 4100，一个字符单位约 97ms → 124ms，共 33 个单位），两边的手速差进一步拉开 ——
 * 要恢复对齐是另一件事，得同时动 `TTPD_TYPE_MS`。
 */
const val SIGNATURE_WRITE_MS: Long = 3_667L

/**
 * 抬笔停顿合计占用，按 [SwiftieSignaturePath.PAUSE_WEIGHT] 分给 11 个间隙。
 *
 * 是**总量**而不是每个间隙的时长：补 `i` 上那一点之前要停久一点、换词之前也要停，
 * 每个间隙不一样长，但合计锁死，签名段的账本才不会被笔数一改就崩。
 *
 * 2026-09-13 从 500 压到 300：书写提速之后停顿按原比例就显得拖，收成同一个手感。
 */
const val SIGNATURE_PAUSE_TOTAL_MS: Long = 300L

/** 写完之后整字通体闪一次。 */
const val SIGNATURE_FLASH_MS: Long = 700L

/** 一笔的时间窗口：[startMs] 落笔，[endMs] 收笔，之后是抬笔停顿。 */
internal class SignatureWindow(val startMs: Long, val endMs: Long)

/**
 * 把书写与停顿两份预算按权重分给每一笔。
 *
 * 两份分开算，所以「哪一笔写多久」和「哪个间隙停多久」互不影响。取整误差各自由最后一份
 * 吸收：书写给末笔，停顿给最后一个真有停顿的间隙（末笔之后不停）。于是末笔收笔时刻严格
 * 等于 `writeMs + pauseMs`，加上 [SIGNATURE_FLASH_MS] 正好是账本给签名段的
 * [SwiftieTimeline.SIGNATURE_MS]。
 */
internal fun buildSignatureWindows(
    writeWeights: FloatArray,
    pauseWeights: FloatArray,
    writeMs: Long,
    pauseMs: Long
): List<SignatureWindow> {
    if (writeWeights.isEmpty()) return emptyList()
    val writeTotal = writeWeights.sum().toDouble().coerceAtLeast(1e-6)
    val pauseTotal = pauseWeights.sum().toDouble()
    // 只有一笔时没有间隙，下面的除法也就不会碰到 0
    val lastPause = pauseWeights.indexOfLast { it > 0f }
    var cursor = 0L
    var writeSpent = 0L
    var pauseSpent = 0L
    return List(writeWeights.size) { index ->
        val write = if (index == writeWeights.lastIndex) {
            writeMs - writeSpent
        } else {
            (writeMs * writeWeights[index] / writeTotal).toLong()
        }
        writeSpent += write
        val pause = when {
            lastPause < 0 -> 0L
            index == lastPause -> pauseMs - pauseSpent
            else -> (pauseMs * pauseWeights[index] / pauseTotal).toLong()
        }
        pauseSpent += pause
        val start = cursor
        cursor = start + write + pause
        SignatureWindow(startMs = start, endMs = start + write)
    }
}

/** 书写全部结束的时刻。收尾闪光从这里起算。 */
internal val List<SignatureWindow>.writeEndMs: Long
    get() = if (isEmpty()) 0L else last().endMs

/** 探路字号。100px 只是个够大的整数，用来量出「一个 em 值多少像素」。 */
private const val PROBE_TEXT_SIZE = 100f

/**
 * 签名宽度上限。
 *
 * 300dp 是设计值，但**只能当上限用** —— 签名的高度由字形包围盒等比推出来，
 * 写死 300dp 在 320dp 宽的小屏（去掉两侧 20dp 内边距只剩 280dp）会横向溢出，
 * 而外层是 `Column` 不是 `clip`，溢出的那截要么被父级裁掉要么把下面的东西挤歪。
 */
private val SIGNATURE_MAX_WIDTH = 300.dp

/** 定格闪粉用的相位。挑 0.35 是箔面渐变正好偏亮的一档，静止看着不发灰。 */
private const val SIGNATURE_STILL_PHASE = 0.35f

/**
 * 一笔的笔心中线，已经按字号缩放、按笔位平移到画布坐标。
 *
 * **签名与题词（`SwiftieLetterInk`）共用这一个类**：两者都是「沿中线铺圆头圆接的变宽粗线」，
 * 差别只在签名铺完要被字形 mask 裁、题词的墨就是字形本身。
 *
 * @param halfWidth 每个点上的半宽
 * @param t 每个点的累计时间比例，离线烤好（曲率大处慢、回描段快）
 */
internal class SignatureStroke(
    val x: FloatArray,
    val y: FloatArray,
    val halfWidth: FloatArray,
    val t: FloatArray,
    val window: SignatureWindow
) {

    /** 沿线走到 [progress]（0f..1f）时笔尖在哪。题词那边用它把羽毛笔钉在笔迹的前沿上。 */
    fun pointAt(progress: Float): Offset {
        var head = 0
        while (head + 1 < t.size && t[head + 1] <= progress) head++
        if (head + 1 >= t.size) return Offset(x[head], y[head])
        val span = t[head + 1] - t[head]
        val fraction = if (span > 1e-6f) ((progress - t[head]) / span).coerceIn(0f, 1f) else 0f
        return Offset(
            x[head] + (x[head + 1] - x[head]) * fraction,
            y[head] + (y[head + 1] - y[head]) * fraction
        )
    }

    /**
     * 把「写到 [progress] 为止」的墨迹追加到 [into]。
     *
     * 一段一段铺矩形（两端各按自己的半宽），再在每个点上盖一个圆盘。圆盘就是圆角接头：
     * 少了它，急转弯的外侧会缺一个楔形。铺出字形之外没关系，字形 mask 会裁掉。
     */
    fun appendTo(into: Path, progress: Float) {
        var head = 0
        while (head + 1 < t.size && t[head + 1] <= progress) head++
        for (index in 0 until head) {
            quad(
                into,
                x[index], y[index], halfWidth[index],
                x[index + 1], y[index + 1], halfWidth[index + 1]
            )
            disc(into, x[index], y[index], halfWidth[index])
        }
        disc(into, x[head], y[head], halfWidth[head])
        if (head + 1 >= t.size) return
        // 段内插值出笔尖：只按整点走的话，笔迹会一格一格跳
        val span = t[head + 1] - t[head]
        val fraction = if (span > 1e-6f) ((progress - t[head]) / span).coerceIn(0f, 1f) else 0f
        if (fraction <= 0f) return
        val tipX = x[head] + (x[head + 1] - x[head]) * fraction
        val tipY = y[head] + (y[head + 1] - y[head]) * fraction
        val tipWidth = halfWidth[head] + (halfWidth[head + 1] - halfWidth[head]) * fraction
        quad(into, x[head], y[head], halfWidth[head], tipX, tipY, tipWidth)
        disc(into, tipX, tipY, tipWidth)
    }
}

/**
 * 一段变宽的矩形。
 *
 * 法向量取这一段自己的方向 —— 用相邻两段的平均法向在急转处会退化。拐角由 [disc] 补。
 *
 * **internal 是为了信纸（`SwiftieLetterInk`）：那一句 `All’s fair in love / and poetry.`
 * 的墨用的是同一套铺法**，两处的笔性必须逐像素一致。
 */
internal fun quad(
    into: Path,
    fromX: Float, fromY: Float, fromWidth: Float,
    toX: Float, toY: Float, toWidth: Float
) {
    val dx = toX - fromX
    val dy = toY - fromY
    val length = hypot(dx, dy)
    if (length < 1e-4f) return
    val nx = -dy / length
    val ny = dx / length
    into.moveTo(fromX - nx * fromWidth, fromY - ny * fromWidth)
    into.lineTo(toX - nx * toWidth, toY - ny * toWidth)
    into.lineTo(toX + nx * toWidth, toY + ny * toWidth)
    into.lineTo(fromX + nx * fromWidth, fromY + ny * fromWidth)
    into.close()
}

/**
 * 一个圆盘。
 *
 * 用两段正角弧拼，绕向（顺时针）与 [quad] 一致：默认的 NonZero 填充下，反绕向的形状会把
 * 重叠处抵消成空洞，而这条带子处处重叠 —— `addOval` 是逆时针的，不能用。
 */
internal fun disc(into: Path, centerX: Float, centerY: Float, radius: Float) {
    if (radius <= 0f) return
    val box = Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    into.arcTo(box, 0f, 180f, forceMoveTo = true)
    into.arcTo(box, 180f, 180f, forceMoveTo = false)
    into.close()
}

/**
 * 构建好的签名图形：字形轮廓（当 mask）+ 尺寸 + 十二笔的中线与时间窗口。
 *
 * 揭示区域分**两块**给出来（[settledInk] / [freshInk]），要分别裁：字形轮廓的绕向由字体
 * 决定，与 [quad]/[disc] 那套顺时针铺片相反，合进同一条路径之后 NonZero 填充会把两者
 * 重叠的地方抵消成空洞 —— 花体的字母是连笔的，真的会重叠，接笔处就会缺一块。各自绕向
 * 一致，各自裁一次，就没有这个问题。
 *
 * @param finishedGlyph 每一笔写完时该并入的**整字轮廓**；该字形还有笔没写完就是 null
 *   （`i` 的主体等着补点）
 */
private class SignatureArt(
    val outline: Path,
    val width: Float,
    val height: Float,
    private val strokes: List<SignatureStroke>,
    private val finishedGlyph: List<Path?>
) {

    val writeEndMs: Long = strokes.map { it.window }.writeEndMs

    /** 已经整字写完的部分。跨帧留着，只在写完一个字时追加。 */
    private val done = Path()
    private var cached = 0

    /** [freshInk] 每帧复用，省掉一次分配。调用方只读，不留存。 */
    private val scratch = Path()

    /**
     * 已经**整字**写完的墨；null 表示一个字都还没写完。
     *
     * 整字写完就换成字形轮廓：几条曲线代替上百个铺片，每帧的裁剪路径小一个数量级。
     * 覆盖率是 100%（生成脚本自检不过就不出表），两者画出来是同一块墨。
     */
    fun settledInk(elapsedMs: Long): Path? {
        val whole = wholeGlyphStrokes(elapsedMs)
        // 时间倒回（静态终态传的是常量，重组也可能换时钟）：缓存作废重建
        if (cached > whole) {
            done.reset()
            cached = 0
        }
        while (cached < whole) {
            finishedGlyph[cached]?.let { done.addPath(it) }
            cached++
        }
        return if (cached == 0) null else done
    }

    /** 正在写的那笔，外加写完了但整字还没写完的那几笔。null 表示还没落笔。 */
    fun freshInk(elapsedMs: Long): Path? {
        val finished = finishedStrokes(elapsedMs)
        var any = false
        scratch.reset()
        for (index in wholeGlyphStrokes(elapsedMs) until finished) {
            strokes[index].appendTo(scratch, 1f)
            any = true
        }
        val active = strokes.getOrNull(finished)
        if (active != null && elapsedMs >= active.window.startMs) {
            val span = (active.window.endMs - active.window.startMs).coerceAtLeast(1L)
            active.appendTo(scratch, (elapsedMs - active.window.startMs).toFloat() / span)
            any = true
        }
        return if (any) scratch else null
    }

    private fun finishedStrokes(elapsedMs: Long): Int {
        var count = 0
        while (count < strokes.size && elapsedMs >= strokes[count].window.endMs) count++
        return count
    }

    /** 前多少笔属于「已经整字写完」的字形。 */
    private fun wholeGlyphStrokes(elapsedMs: Long): Int {
        var whole = 0
        for (index in 0 until finishedStrokes(elapsedMs)) {
            if (finishedGlyph[index] != null) whole = index + 1
        }
        return whole
    }
}

/**
 * 逐字形取轮廓，按笔位摆好。返回值按 [text] 的下标索引，空格是 null。
 *
 * **不用 `getTextPath(text, 0, length, …)` 一次取整串**：[SwiftieSignaturePath] 的点表是
 * 逐字形量出来的，而这个字体带 GPOS `kern`，整串取到的轮廓与「逐字形 + `measureText`
 * 笔位」摆出来的会差出零点几个像素。差这么一点，mask 边上就会留一条永远揭示不到的细线。
 * 让 mask 和笔迹用同一套笔位，两边就严格对齐。
 */
private fun glyphOutlines(
    paint: android.graphics.Paint,
    text: String
): List<android.graphics.Path?> = text.mapIndexed { index, char ->
    if (char.isWhitespace()) {
        null
    } else {
        android.graphics.Path().also {
            paint.getTextPath(text, index, index + 1, paint.measureText(text, 0, index), 0f, it)
        }
    }
}

private fun union(paths: List<android.graphics.Path?>): android.graphics.Path =
    android.graphics.Path().apply { paths.forEach { it?.let(::addPath) } }

/**
 * 量字形、摆中线、算时间表。**只在字号变化时跑一次**（`remember` 缓存）。
 *
 * 两轮 [glyphOutlines]：第一轮按 [PROBE_TEXT_SIZE] 探路，求出达到 [targetWidthPx] 需要的
 * 字号；第二轮才是真正要用的轮廓。`Pacifico` 的花体会伸出字符宽度之外，所以宽高一律取
 * **路径包围盒**而不是 `measureText`。
 */
private fun buildSignatureArt(context: Context, targetWidthPx: Float): SignatureArt {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        typeface = ResourcesCompat.getFont(context, R.font.swiftie_script)
        textSize = PROBE_TEXT_SIZE
    }
    val text = SwiftieSignaturePath.TEXT
    val probeBounds = android.graphics.RectF()
    union(glyphOutlines(paint, text)).computeBounds(probeBounds, true)
    paint.textSize = PROBE_TEXT_SIZE * targetWidthPx / probeBounds.width().coerceAtLeast(1f)

    val glyphs = glyphOutlines(paint, text)
    val outline = union(glyphs)
    val bounds = android.graphics.RectF()
    outline.computeBounds(bounds, true)
    // 平移到包围盒左上角为原点；下面所有坐标都在这个空间里
    outline.offset(-bounds.left, -bounds.top)
    glyphs.forEach { it?.offset(-bounds.left, -bounds.top) }

    val size = paint.textSize
    val pens = FloatArray(text.length) { paint.measureText(text, 0, it) - bounds.left }
    val windows = buildSignatureWindows(
        writeWeights = SwiftieSignaturePath.WRITE_WEIGHT,
        pauseWeights = SwiftieSignaturePath.PAUSE_WEIGHT,
        writeMs = SIGNATURE_WRITE_MS,
        pauseMs = SIGNATURE_PAUSE_TOTAL_MS
    )
    val stride = SwiftieSignaturePath.STRIDE
    val index = SwiftieSignaturePath.GLYPH_INDEX
    val strokes = SwiftieSignaturePath.POINTS.mapIndexed { order, points ->
        val count = points.size / stride
        val penX = pens[index[order]]
        SignatureStroke(
            // 点表是 em 单位、y 向下、基线为 0、原点在该字形的落笔处
            x = FloatArray(count) { penX + points[it * stride] * size },
            y = FloatArray(count) { -bounds.top + points[it * stride + 1] * size },
            halfWidth = FloatArray(count) { points[it * stride + 2] * size },
            t = FloatArray(count) { points[it * stride + 3] },
            window = windows[order]
        )
    }
    // 同一个字形的最后一笔才认「整字写完」
    val finishedGlyph = index.mapIndexed { order, glyph ->
        if (order + 1 < index.size && index[order + 1] == glyph) {
            null
        } else {
            glyphs[glyph]?.asComposePath()
        }
    }

    return SignatureArt(
        outline = outline.asComposePath(),
        width = bounds.width(),
        height = bounds.height(),
        strokes = strokes,
        finishedGlyph = finishedGlyph
    )
}

/** 收尾闪光的强度。一进一出的正弦包络，不会停在过曝。 */
private fun flashAlpha(elapsedMs: Long, writeEndMs: Long): Float {
    if (elapsedMs <= writeEndMs) return 0f
    val p = ((elapsedMs - writeEndMs).toFloat() / SIGNATURE_FLASH_MS).coerceIn(0f, 1f)
    return sin(p * PI.toFloat()) * 0.55f
}

/**
 * 一笔一笔写出来的闪粉签名（Spec §7）。
 *
 * 笔顺、笔速、每一点的笔宽都在 [SwiftieSignaturePath] 里，由离线脚本从字形骨架生成。
 * 这里只负责把那些中线摆到画布上、按时间铺开。
 *
 * @param elapsedInSignature 签名段起点以来的毫秒。喂 [SwiftieTimeline.SIGNATURE_MS]（写完 + 闪过）
 *   或更大的常量就是写完的样子，「减少动效」的静态终态正是这么用的
 * @param animated 闪粉是否要一直闪。静态终态传 false —— 那是一张停着的画面，
 *   挂一条无限动画会把帧时钟永久唤着，用户忘了退出就一直在耗电。
 *   传 false 之后 `time` 恒定，`drawBehind` 里不再有变化的 state 读，只画一次
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
    val layoutDirection = LocalLayoutDirection.current
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
                .drawWithCache {
                    // 字形先烤进一张位图当 mask，只有尺寸变化才重建。
                    //
                    // **不能用 `drawPath(blendMode = DstIn)` 抠形**：DstIn 要「src 没盖到
                    // 的地方把 dst 清掉」，而 Skia 画一条路径只在路径的覆盖率范围内混合 ——
                    // 路径外的像素覆盖率是 0，根本不参与运算，箔面于是整块留下，
                    // 屏幕上就是一个玫红矩形而不是签名。位图 src 铺满整层、
                    // 字形外 alpha = 0，DstIn 才真的擦得掉（与灯箱的
                    // `SwiftieGlitterText` 同一套做法）。
                    val mask = ImageBitmap(
                        width = size.width.toInt().coerceAtLeast(1),
                        height = size.height.toInt().coerceAtLeast(1)
                    )
                    CanvasDrawScope().draw(this, layoutDirection, Canvas(mask), size) {
                        drawPath(path = art.outline, color = Color.White)
                    }
                    onDrawBehind {
                        val elapsed = elapsedInSignature()
                        if (elapsed >= art.writeEndMs) {
                            // 写完了：整字都是墨，形状交给 mask。这时再拿几百个铺片
                            // 裁一遍，画出来是同一块东西
                            drawGlitterBody(time, sparkles)
                            val flash = flashAlpha(elapsed, art.writeEndMs)
                            if (flash > 0f) drawRect(color = Color.White, alpha = flash)
                        } else {
                            // 箔面只铺在已经写出来的墨上。两块分别裁（见 SignatureArt）：
                            // 重叠处画了两遍，而箔面底色不透明，画两遍是同一块 ——
                            // 只有闪点会在那条极窄的接笔带上稍亮一点
                            art.settledInk(elapsed)?.let {
                                clipPath(it) { drawGlitterBody(time, sparkles) }
                            }
                            art.freshInk(elapsed)?.let {
                                clipPath(it) { drawGlitterBody(time, sparkles) }
                            }
                        }
                        drawImage(image = mask, blendMode = BlendMode.DstIn)
                    }
                }
        )
    }
}
