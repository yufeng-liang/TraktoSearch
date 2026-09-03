package com.tracktosearch.ui.screen.swiftie.bracelet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

/**
 * 弹性落下。
 *
 * `internal` 而不是 `private`：`SwiftieHapticScore` 要把三记落地触感摊在这段时长上，
 * 而设计文档的硬约束是「彩蛋所有时刻从时间线常量取，不得写死毫秒数」。
 */
internal const val BRACELET_DROP_MS: Long = 900L

/** 左右晃两下收住。同上，触感的余震包络要跟着它走，所以是 `internal`。 */
internal const val BRACELET_SWAY_MS: Long = 1_700L

/** 入场走完的时刻。静态终态直接喂这个值就是收住的样子。 */
internal const val BRACELET_SETTLED_MS: Long = BRACELET_DROP_MS + BRACELET_SWAY_MS

/**
 * 晃动幅度上限。再大就不像手链落下，像钟摆了。
 *
 * `internal`：触感的余震包络拿它把摆角归一到 0f..1f 的振幅。
 */
internal const val BRACELET_SWAY_MAX_DEG = 7f


/** 手指拖动的跟随比例。0.65 是「弹性绳」的手感：跟手但拽不走。 */
private const val DRAG_FOLLOW = 0.65f

/** 字母珠会用到的全部字符，用来预排字形。 */
private const val BEAD_CHARS = "1387SWIFTE"

/** 圆珠配色：Lover 那套 + 一点薄荷，循环取用。 */
private val ROUND_COLORS = listOf(
    SwiftiePalette.SkyBlue,
    SwiftiePalette.CloudPink,
    SwiftiePalette.Lavender,
    SwiftiePalette.PeachYellow,
    Color(0xFF8FD9C0),
    SwiftiePalette.Glitter
)

/** 印刷字配色：亮且互相拉得开。 */
private val INK_COLORS = listOf(
    SwiftiePalette.Glitter,
    SwiftiePalette.RoyalBlue,
    Color(0xFF11837A),
    Color(0xFFE0670F)
)

private fun roundBead(index: Int) = SwiftieBead.Round(ROUND_COLORS[index % ROUND_COLORS.size])

private fun letterBead(char: Char, index: Int) =
    SwiftieBead.Letter(char = char, inkColor = INK_COLORS[index % INK_COLORS.size])

/**
 * 一条手链。
 *
 * @param yFraction 该条在画布里的纵向位置比例
 * @param widthFraction 宽度比例。后面的条略窄，堆叠才有纵深
 * @param sagFraction 下垂量，相对自身宽度
 */
private class BraceletStrand(
    val beads: List<SwiftieBead>,
    val yFraction: Float,
    val widthFraction: Float,
    val sagFraction: Float
)

/** 画的顺序就是从后到前：后面的先画，被前面的压住。 */
private val STRANDS: List<BraceletStrand> = listOf(
    // 最后一条：纯彩珠
    BraceletStrand(
        beads = List(13) { roundBead(it) },
        yFraction = 0.30f,
        widthFraction = 0.72f,
        sagFraction = 0.12f
    ),
    // 中间一条：SWIFTIE
    BraceletStrand(
        beads = buildList {
            repeat(2) { add(roundBead(it + 1)) }
            "SWIFTIE".forEachIndexed { index, char -> add(letterBead(char, index)) }
            repeat(2) { add(roundBead(it + 4)) }
        },
        yFraction = 0.44f,
        widthFraction = 0.84f,
        sagFraction = 0.14f
    ),
    // 最前一条：13 ♡ 87
    BraceletStrand(
        beads = buildList {
            repeat(3) { add(roundBead(it)) }
            add(letterBead('1', 0))
            add(letterBead('3', 1))
            add(SwiftieBead.Heart)
            add(letterBead('8', 2))
            add(letterBead('7', 3))
            repeat(3) { add(roundBead(it + 2)) }
        },
        yFraction = 0.58f,
        widthFraction = 0.94f,
        sagFraction = 0.16f
    )
)

/** 每颗珠固定的随机旋转，±8°（Spec §8）。固定种子，重组不会让珠子重新乱转。 */
private val BEAD_ROTATIONS: List<Float> = Random(13).let { random ->
    List(24) { (random.nextFloat() * 2f - 1f) * 8f }
}

private class PlacedBead(
    val bead: SwiftieBead,
    val center: Offset,
    val size: Float,
    val rotation: Float,
    /** 投影渐变不吃倾斜，跟着布局缓存一次就够。 */
    val shadowBrush: Brush
)

private class StrandPlan(val cord: Path, val cordWidth: Float, val beads: List<PlacedBead>)

/**
 * 珠体渐变的跨帧缓存。
 *
 * 珠体渐变的圆心跟着倾斜走，所以不能像投影那样只建一次；但也**不能每帧重建** ——
 * `Brush` 按尺寸缓存原生 `Shader`，换实例就等于换 Shader，26 颗珠 × 60fps 一秒
 * 一千五百多个。这里按量化后的倾斜键缓存：真的转到下一档才重建一批。
 */
private class BeadBodyBrushes(private val plans: List<StrandPlan>) {
    private var key: Int = Int.MIN_VALUE
    private var brushes: List<Brush> = emptyList()

    fun forHighlight(highlight: Offset): List<Brush> {
        val nextKey = highlightKey(highlight)
        if (nextKey != key || brushes.isEmpty()) {
            key = nextKey
            val quantized = quantizeHighlight(highlight)
            brushes = plans.flatMap { plan ->
                plan.beads.map { beadBodyBrush(it.bead, it.center, it.size, quantized) }
            }
        }
        return brushes
    }
}

/**
 * 二次贝塞尔在 [t] 处的点。
 *
 * 按 t 等分取珠位而不是按弧长等分：这条弧很浅（下垂只有宽度的 12–16%），
 * 两种分法的间距差在珠子尺寸之下，看不出来。
 */
private fun quadraticPointAt(from: Offset, control: Offset, to: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        x = u * u * from.x + 2f * u * t * control.x + t * t * to.x,
        y = u * u * from.y + 2f * u * t * control.y + t * t * to.y
    )
}

private fun planStrand(canvas: Size, strand: BraceletStrand, rotationOffset: Int): StrandPlan {
    val width = canvas.width * strand.widthFraction
    val left = (canvas.width - width) / 2f
    val baseY = canvas.height * strand.yFraction
    // 1.02：珠子之间留极窄一线，绳子露出来才看得出是穿过去的
    val beadSize = width / (strand.beads.size * 1.02f)
    // 下垂量按自身宽度算，但不能垂出画布 —— 插槽高度由调用方给（见 braceletHeightFor），
    // 万一将来有人写死一个偏小的高度，这里兜住：最低那颗珠的下缘也要留在画布内。
    // Spacer 不 clip，垂出去的部分不是被裁掉，是画到相邻内容上面去
    val maxSag = (canvas.height - baseY - beadSize / 2f).coerceAtLeast(0f)
    val sag = (width * strand.sagFraction).coerceAtMost(maxSag)
    val from = Offset(left, baseY)
    val to = Offset(left + width, baseY)
    // 控制点垂 2×：二次贝塞尔在 t=0.5 处只走到控制点的一半，所以要给两倍才得到 sag
    val control = Offset(left + width / 2f, baseY + sag * 2f)

    val cord = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    val beads = strand.beads.mapIndexed { index, bead ->
        val center = quadraticPointAt(from, control, to, (index + 0.5f) / strand.beads.size)
        PlacedBead(
            bead = bead,
            center = center,
            size = beadSize,
            rotation = BEAD_ROTATIONS[(rotationOffset + index) % BEAD_ROTATIONS.size],
            shadowBrush = beadShadowBrush(center, beadSize)
        )
    }
    return StrandPlan(cord = cord, cordWidth = beadSize * 0.16f, beads = beads)
}

/** 落下进度：1f 在画面之上，0f 落到位。`EaseOutBack` 会冲过 0 再收回，就是弹性绳的软着陆。 */
private fun dropFraction(elapsedMs: Long): Float {
    val p = (elapsedMs.toFloat() / BRACELET_DROP_MS).coerceIn(0f, 1f)
    return 1f - EaseOutBack.transform(p)
}

/**
 * 落地后左右晃两下：指数衰减 × 两个周期，t=1 时正好归零，接得上后面的静止。
 *
 * `internal` 且带 `bracelet` 前缀：`SwiftieHapticScore` 的余震包络直接量这条曲线，
 * 不另抄一份 —— 两条曲线一旦分家，手上的余震就会和眼里的摆动错开。
 */
internal fun braceletSwayDegrees(elapsedMs: Long): Float {
    val t = (elapsedMs - BRACELET_DROP_MS).coerceAtLeast(0L).toFloat() / BRACELET_SWAY_MS
    if (t >= 1f) return 0f
    return exp(-2.4f * t) * sin(t * TAU * 2f) * BRACELET_SWAY_MAX_DEG
}


/**
 * 按可用宽度算手链插槽该有多高。
 *
 * 下垂量是**宽度**的比例（`sagFraction`），所以插槽高度必须跟着宽度走，不能写死。
 * 反推：最前那条宽 `0.94W`、垂 `0.16 × 0.94W = 0.150W`，基线在 `0.58H`，
 * 最低那颗珠还要再占半径 `0.042W`，于是 `0.58H + 0.192W ≤ H`，得 `H ≥ 0.458W`。
 * 取 0.46 —— 360dp 宽的手机算出 166dp，与原来写死的 170dp 基本一致；
 * 平板上跟着长，不会像原来那样让最前那条垂到插槽之外、画到相邻内容上。
 * 上下限只是防极端窄屏 / 超宽屏。
 */
internal fun braceletHeightFor(width: Dp): Dp = (width * 0.46f).coerceIn(140.dp, 260.dp)

/**
 * 三条堆叠的友谊手链：`13 ♡ 87` / `SWIFTIE` / 一条纯彩珠（Spec §8）。
 *
 * 全部 Canvas 程序化绘制 —— 项目里没有美术资源，也不引入。
 *
 * @param elapsedInBracelet 手链段起点以来的毫秒。喂 [BRACELET_SETTLED_MS] 就是收住的静态样子
 * @param interactive false 时不接拖动、不注册传感器（静态终态与低端机）
 */
@Composable
fun SwiftieBracelet(
    elapsedInBracelet: () -> Long,
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
    val lowRam = rememberIsLowRamDevice()
    // 低端机关掉陀螺仪高光（Spec §11.2）
    val highlight = rememberTiltHighlight(enabled = interactive && !lowRam)
    val letterLayouts = rememberBeadLetterLayouts(BEAD_CHARS)
    val dragOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    // 手链是彩蛋里唯一一处该走 ComposeHaptics 的地方：`frequentTick()` 自带的 40ms 闸门
    // 正是设计文档给「逐珠划过」这一行标的「节流 ≥40 ms」。序列上那些按乐句排好的
    // 密集 tick 走的是另一条路（Provider<AppHaptics>，见 SwiftieHapticConductor），
    // 被这道闸吞掉就不成谱子了
    val haptics = rememberAppHaptics()

    val dragModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            // 手指自上一记以来走过的距离。够一颗珠子的间距就再来一记
            var travelPx = 0f
            // 松手回弹**落定**才一记 gestureEnd()：animateTo 挂到弹簧收住才返回。
            // 期间再次拖动会让 snapTo 掐掉这次动画，协程带着 CancellationException 结束，
            // 那一记自然不发 —— 手还在屏幕上，谈不上「落定」
            val settle: () -> Unit = {
                scope.launch {
                    dragOffset.animateTo(Offset.Zero, REBOUND)
                    haptics.gestureEnd()
                }
            }
            detectDragGestures(
                onDragStart = { travelPx = 0f },
                onDragEnd = settle,
                onDragCancel = settle
            ) { change, dragAmount ->
                change.consume()
                val pitchPx = braceletBeadPitchPx(size.width.toFloat())
                travelPx += dragAmount.getDistance()
                if (travelPx >= pitchPx) {
                    // 取余而不是减一个间距：一帧里走过三颗珠子只该响一记，
                    // 剩下两颗的欠账不许攒到下一帧去补
                    travelPx %= pitchPx
                    haptics.frequentTick()
                }
                scope.launch { dragOffset.snapTo(dragOffset.value + dragAmount * DRAG_FOLLOW) }
            }
        }
    } else {
        Modifier
    }


    Spacer(
        modifier = modifier
            .fillMaxSize()
            .then(dragModifier)
            // 逐帧量全在这个 lambda 里读：只失效绘制，不重组
            .graphicsLayer {
                val elapsed = elapsedInBracelet()
                // 1.6×自身高度：起点在画面之外，落下来才有下坠感
                translationY = dropFraction(elapsed) * size.height * -1.6f + dragOffset.value.y
                translationX = dragOffset.value.x
                rotationZ = braceletSwayDegrees(elapsed)
                // 吊在上缘晃，不是绕自己中心转
                transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 0f)
                alpha = (elapsed / 200f).coerceIn(0f, 1f)
            }
            .drawWithCache {
                var rotationOffset = 0
                val plans = STRANDS.map { strand ->
                    planStrand(size, strand, rotationOffset).also {
                        rotationOffset += strand.beads.size
                    }
                }
                // 珠体渐变按量化倾斜缓存；尺寸变了整块 cache 会重建，缓存跟着新建
                val bodyBrushes = BeadBodyBrushes(plans)
                onDrawBehind {
                    val tilt = highlight.value
                    val bodies = bodyBrushes.forHighlight(tilt)
                    var beadIndex = 0
                    plans.forEach { plan ->
                        // 先绳后珠：珠子压住绳子，珠间露出的那截就是穿过珠孔的绳
                        drawPath(
                            path = plan.cord,
                            color = Color(0xFF7A6E66),
                            alpha = 0.85f,
                            style = Stroke(width = plan.cordWidth, cap = StrokeCap.Round)
                        )
                        plan.beads.forEach { placed ->
                            drawSwiftieBead(
                                bead = placed.bead,
                                center = placed.center,
                                size = placed.size,
                                rotationDeg = placed.rotation,
                                highlight = tilt,
                                letterLayouts = letterLayouts,
                                shadowBrush = placed.shadowBrush,
                                bodyBrush = bodies[beadIndex]
                            )
                            beadIndex++
                        }
                    }
                }
            }
    )
}

/** 松手回弹。`dampingRatio 0.35` 会过冲一两下，正是弹性绳该有的样子。 */
private val REBOUND = spring<Offset>(
    dampingRatio = 0.35f,
    stiffness = Spring.StiffnessLow
)

/**
 * 最前那条手链上相邻两颗珠子的中心间距，像素。「逐珠划过」的触感按它计数。
 *
 * 取最前那条（`STRANDS` 的末位，也就是画得最后、压在最上面的 `13 ♡ 87`）：
 * 它最宽、珠子最大，手指真正划到的就是它。间距 = 该条宽度 ÷ 珠数，与 `planStrand`
 * 里珠心落在 `(index + 0.5) / size` 的排法一致。
 *
 * 从画布宽度算而不是写死 dp：平板上珠子更大，一记之间本来就该走更远。
 * 夹到至少 1px 防零宽画布把它除成 0 或无穷。
 */
private fun braceletBeadPitchPx(canvasWidthPx: Float): Float {
    val strand = STRANDS.last()
    return (canvasWidthPx * strand.widthFraction / strand.beads.size).coerceAtLeast(1f)
}

