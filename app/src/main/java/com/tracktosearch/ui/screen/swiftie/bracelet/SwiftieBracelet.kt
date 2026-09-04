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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** 固定内容的字母珠会用到的字，用来预排字形。昵称的字另外并进来。 */
private val BEAD_TEXTS = listOf("1", "3", "8", "7", "S", "W", "I", "F", "T", "E")

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

private fun letterBead(text: String, index: Int) =
    SwiftieBead.Letter(text = text, inkColor = INK_COLORS[index % INK_COLORS.size])

/**
 * 一条手链在画布里的槽位。
 *
 * 宽度和下垂**跟着槽位走，不跟着内容走** —— 越靠前的越宽、垂得越深，这是唯一的纵深线索。
 * 珠径由 [beadSizeFraction] 从宽度和颗数推出来，所以越靠前的珠子也越大，一起把层次做实。
 *
 * @param yFraction 该槽在画布里的纵向位置比例
 * @param widthFraction 宽度比例
 * @param sagFraction 下垂量，相对自身宽度
 */
internal class BraceletSlot(
    val yFraction: Float,
    val widthFraction: Float,
    val sagFraction: Float
)

/** 从后到前三个槽位。只有两条时占**靠前**的两个，插槽底下不留空。 */
internal val BRACELET_SLOTS = listOf(
    BraceletSlot(yFraction = 0.24f, widthFraction = 0.84f, sagFraction = 0.14f),
    BraceletSlot(yFraction = 0.40f, widthFraction = 0.94f, sagFraction = 0.16f),
    BraceletSlot(yFraction = 0.56f, widthFraction = 0.96f, sagFraction = 0.18f)
)

/**
 * 珠径占画布宽度的比例。
 *
 * 1.02：珠子之间留极窄一线，绳子露出来才看得出是穿过去的。
 */
internal fun beadSizeFraction(beadCount: Int, widthFraction: Float): Float =
    widthFraction / (beadCount * 1.02f)

private class BraceletStrand(val beads: List<SwiftieBead>, val slot: BraceletSlot)

/** `13 ♡ 87`：最后那条。11 颗。 */
private fun heartBeads(): List<SwiftieBead> = buildList {
    repeat(3) { add(roundBead(it)) }
    add(letterBead("1", 0))
    add(letterBead("3", 1))
    add(SwiftieBead.Heart)
    add(letterBead("8", 2))
    add(letterBead("7", 3))
    repeat(3) { add(roundBead(it + 2)) }
}

/** `SWIFTIE`：中间那条。11 颗。 */
private fun swiftieBeads(): List<SwiftieBead> = buildList {
    repeat(2) { add(roundBead(it + 1)) }
    "SWIFTIE".forEachIndexed { index, char -> add(letterBead(char.toString(), index)) }
    repeat(2) { add(roundBead(it + 4)) }
}

/**
 * 昵称那条：字母珠居中，两侧补等量圆珠。
 *
 * 补到 11 颗（奇数个字）或 10 颗（偶数个字，两侧要一样多才居中），见
 * [nicknameStrandBeadCount] —— 无论昵称是一个字还是九个字，这条的长度都一样，
 * 堆叠不会一人一形。
 */
private fun nicknameBeads(tokens: List<String>): List<SwiftieBead> = buildList {
    val padding = (nicknameStrandBeadCount(tokens.size) - tokens.size) / 2
    repeat(padding) { add(roundBead(it + 2)) }
    tokens.forEachIndexed { index, token -> add(letterBead(token, index)) }
    repeat(padding) { add(roundBead(padding + it + 2)) }
}

/**
 * 从后到前把内容摆进槽位：`13 ♡ 87` / `SWIFTIE` / 昵称。
 *
 * 昵称一个字都不剩（没登录、或者昵称全是标点）就只有两条，占靠前的两个槽 —— 这条手链
 * 是挂在那儿的，空槽要留在上面，不能让手链在插槽里悬空。
 */
private fun braceletStrands(nickname: List<String>): List<BraceletStrand> {
    val rows = buildList {
        add(heartBeads())
        add(swiftieBeads())
        if (nickname.isNotEmpty()) add(nicknameBeads(nickname))
    }
    return rows.zip(BRACELET_SLOTS.takeLast(rows.size)) { beads, slot -> BraceletStrand(beads, slot) }
}

/**
 * 每颗珠固定的随机旋转，±8°（Spec §8）。固定种子，重组不会让珠子重新乱转。
 *
 * 36 个够三条最长的情形（11 + 11 + 11）各拿一个，不用绕回去复用。
 */
private val BEAD_ROTATIONS: List<Float> = Random(13).let { random ->
    List(36) { (random.nextFloat() * 2f - 1f) * 8f }
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
    val slot = strand.slot
    val width = canvas.width * slot.widthFraction
    val left = (canvas.width - width) / 2f
    val baseY = canvas.height * slot.yFraction
    val beadSize = canvas.width * beadSizeFraction(strand.beads.size, slot.widthFraction)
    // 下垂量按自身宽度算，但不能垂出画布 —— 插槽高度由调用方给（见 braceletHeightFor），
    // 万一将来有人写死一个偏小的高度，这里兜住：最低那颗珠的下缘也要留在画布内。
    // Spacer 不 clip，垂出去的部分不是被裁掉，是画到相邻内容上面去
    val maxSag = (canvas.height - baseY - beadSize / 2f).coerceAtLeast(0f)
    val sag = (width * slot.sagFraction).coerceAtMost(maxSag)
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
 * 按可用宽度算手链插槽该有多高。
 *
 * 下垂量是**宽度**的比例（`sagFraction`），所以插槽高度必须跟着宽度走，不能写死。
 * 反推：最前那条宽 `0.96W`、垂 `0.18 × 0.96W = 0.173W`，基线在 `0.56H`，最低那颗珠还要
 * 再占半径 `0.047W`，于是 `0.56H + 0.220W ≤ H`，得 `H ≥ 0.500W`。取 0.56 留一成余量 ——
 * 360dp 宽的手机算出 202dp。上下限只是防极端窄屏 / 超宽屏；顶到上限之后
 * [planStrand] 的 `maxSag` 会替它收着，垂浅一点而不是垂出插槽。
 */
internal fun braceletHeightFor(width: Dp): Dp = (width * 0.56f).coerceIn(140.dp, 300.dp)

/**
 * 三条堆叠的友谊手链：`13 ♡ 87` / `SWIFTIE` / 用户昵称（Spec §8）。
 *
 * 全部 Canvas 程序化绘制 —— 项目里没有美术资源，也不引入。
 *
 * @param elapsedInBracelet 手链段起点以来的毫秒。喂 [BRACELET_SETTLED_MS] 就是收住的静态样子
 * @param interactive false 时不接拖动、不注册传感器（静态终态与低端机）
 * @param nickname 要串在最前那条上的昵称。null 或者一个可用字符都不剩时只挂两条
 */
@Composable
fun SwiftieBracelet(
    elapsedInBracelet: () -> Long,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    nickname: String? = null
) {
    val lowRam = rememberIsLowRamDevice()
    // 低端机关掉陀螺仪高光（Spec §11.2）
    val highlight = rememberTiltHighlight(enabled = interactive && !lowRam)
    val tokens = remember(nickname) { braceletNicknameTokens(nickname) }
    val strands = remember(tokens) { braceletStrands(tokens) }
    val letterLayouts = rememberBeadLetterLayouts(BEAD_TEXTS + tokens)
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
                val plans = strands.map { strand ->
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
