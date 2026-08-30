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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

/** 弹性落下。 */
private const val DROP_MS: Long = 900L

/** 左右晃两下收住。 */
private const val SWAY_MS: Long = 1_700L

/** 入场走完的时刻。静态终态直接喂这个值就是收住的样子。 */
internal const val BRACELET_SETTLED_MS: Long = DROP_MS + SWAY_MS

/** 晃动幅度上限。再大就不像手链落下，像钟摆了。 */
private const val SWAY_MAX_DEG = 7f

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
    val rotation: Float
)

private class StrandPlan(val cord: Path, val cordWidth: Float, val beads: List<PlacedBead>)

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
    val sag = width * strand.sagFraction
    val from = Offset(left, baseY)
    val to = Offset(left + width, baseY)
    // 控制点垂 2×：二次贝塞尔在 t=0.5 处只走到控制点的一半，所以要给两倍才得到 sag
    val control = Offset(left + width / 2f, baseY + sag * 2f)

    val cord = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    // 1.02：珠子之间留极窄一线，绳子露出来才看得出是穿过去的
    val beadSize = width / (strand.beads.size * 1.02f)
    val beads = strand.beads.mapIndexed { index, bead ->
        PlacedBead(
            bead = bead,
            center = quadraticPointAt(from, control, to, (index + 0.5f) / strand.beads.size),
            size = beadSize,
            rotation = BEAD_ROTATIONS[(rotationOffset + index) % BEAD_ROTATIONS.size]
        )
    }
    return StrandPlan(cord = cord, cordWidth = beadSize * 0.16f, beads = beads)
}

/** 落下进度：1f 在画面之上，0f 落到位。`EaseOutBack` 会冲过 0 再收回，就是弹性绳的软着陆。 */
private fun dropFraction(elapsedMs: Long): Float {
    val p = (elapsedMs.toFloat() / DROP_MS).coerceIn(0f, 1f)
    return 1f - EaseOutBack.transform(p)
}

/** 落地后左右晃两下：指数衰减 × 两个周期，t=1 时正好归零，接得上后面的静止。 */
private fun swayDegrees(elapsedMs: Long): Float {
    val t = (elapsedMs - DROP_MS).coerceAtLeast(0L).toFloat() / SWAY_MS
    if (t >= 1f) return 0f
    return exp(-2.4f * t) * sin(t * TAU * 2f) * SWAY_MAX_DEG
}

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

    val dragModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = { scope.launch { dragOffset.animateTo(Offset.Zero, REBOUND) } },
                onDragCancel = { scope.launch { dragOffset.animateTo(Offset.Zero, REBOUND) } }
            ) { change, dragAmount ->
                change.consume()
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
                rotationZ = swayDegrees(elapsed)
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
                onDrawBehind {
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
                                highlight = highlight.value,
                                letterLayouts = letterLayouts
                            )
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
