package com.tracktosearch.ui.screen.swiftie.bracelet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 单颗珠子的**发车间隔基准**：这段时长按珠数均分，就是相邻两颗出发相隔多久。
 *
 * 1100 ÷ 11 = 100ms 一颗，肉眼刚好能跟上「这一颗、然后那一颗」，再快就糊成一条流。
 * 入场整套规则见 [SwiftieBracelet] 的类注释。
 */
internal const val BRACELET_ROLL_SPAN_MS: Long = 1_100L

/** 三条链错开起步：后一条在前一条滚出四颗之后才进画面，眼睛才追得住每一条。 */
internal const val BRACELET_STAGGER_MS: Long = 200L

/**
 * 单颗珠子走完全程用掉几个发车间隔。
 *
 * 3.2 是刻意的重叠：弧线上同时有三颗在滚。收到 1.0 会退化成一颗一颗接力（每颗都得停稳
 * 下一颗才动），再大就整条一起被推进来，「一颗接一颗」的味道全没了。
 */
private const val BRACELET_ROLL_RELEASE_RATIO = 3.2f

/**
 * 三条链从哪一端进场（绘制顺序 = 后 → 前）：左、右、左。
 *
 * 交替不是为了对称好看 —— 方向一致的话三条会读成同一块横向平移，交替之后每条各是各的。
 */
private val BRACELET_ENTRY_FROM_RIGHT = listOf(false, true, false)

/**
 * 珠数为 [beadCount] 的一条链，从第一颗出发到最后一颗停住要多久。
 *
 * 末颗珠子在第 `(珠数 − 1)` 个间隔出发，再走 [BRACELET_ROLL_RELEASE_RATIO] 个间隔到位。
 * 珠数一多间隔就短、珠数一少间隔就长，所以这个时长**不是**单调的：10 颗那条比 11 颗的
 * 还慢（1342ms 对 1320ms）。
 */
internal fun braceletStrandSettleMs(beadCount: Int): Long {
    if (beadCount <= 0) return 0L
    val release = BRACELET_ROLL_SPAN_MS.toFloat() / beadCount
    return (release * (beadCount - 1 + BRACELET_ROLL_RELEASE_RATIO)).roundToLong()
}

/** 昵称补到偶数个字时是 10 颗，间距最大、滚得最久 —— 三条里最慢的一条。 */
private val NICKNAME_SLOWEST_SETTLE_MS: Long = (1..NICKNAME_MAX_BEADS)
    .maxOf { braceletStrandSettleMs(nicknameStrandBeadCount(it)) }

/**
 * 三条都停住的时刻，从第一条出发算起。静态终态喂这个值就是停稳的样子。
 *
 * 按最慢的一条算：昵称那条的珠数随字数在 10 与 11 之间变，取其中更慢的 10 颗，
 * 再算上它是最后出发的（错开 2 档）。所以这是个**任何配置下都不会早于真实收尾**的上界 ——
 * 它只用来「喂一个已经停住的值」与给文案定浮现时刻，宁可晚一点点。
 */
internal val BRACELET_SETTLED_MS: Long =
    (BRACELET_ENTRY_FROM_RIGHT.size - 1) * BRACELET_STAGGER_MS + NICKNAME_SLOWEST_SETTLE_MS

/** 手指拖动的跟随比例。0.65 是「弹性绳」的手感：跟手但拽不走。 */
private const val DRAG_FOLLOW = 0.65f

/** 绳子的颜色。三条共用：手链是一根绳上串出来的，换色就成了三件东西。 */
private val CORD_COLOR = Color(0xFF7A6E66)

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

/** 某条链从这里进场：越界回退到最后一条的方向，宁可方向重复也不要空指针。 */
private fun entryFromRight(strandIndex: Int): Boolean =
    BRACELET_ENTRY_FROM_RIGHT[strandIndex % BRACELET_ENTRY_FROM_RIGHT.size]

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
    /** 停在弧线的哪个参数上，0 = 入场那端。滚入就是从 0 滚到这儿（见 `braceletRollProgress`）。 */
    val finalT: Float,
    /** 终态珠心。入场路上由 [quadraticPointAt] 现算，这里存下来是因为各个缓存都按它建。 */
    val center: Offset,
    val size: Float,
    val rotation: Float,
    /** 投影渐变不吃倾斜，跟着布局缓存一次就够。 */
    val shadowBrush: Brush
)

/**
 * 一条链的静态布局 + 绳子那一段。
 *
 * 除了绳子之外全是常量（终态珠心、珠径、旋转、投影），它们只在尺寸变化时重建。
 * 绳子是唯一随时间生长的东西，见 [cordTo]。
 */
private class StrandPlan(
    val cord: Path,
    val cordFrom: Offset,
    val cordControl: Offset,
    val cordTo: Offset,
    val cordWidth: Float,
    val beads: List<PlacedBead>
) {
    /**
     * 绳子已经画到弧线参数 [t] 处的那一段。
     *
     * 跑到 1f 就交回缓存好的整条 —— 停住之后倾斜高光还在 50Hz 地让这一帧失效，
     * 每帧重建一个 Path 没有意义。
     */
    fun cordAt(t: Float): Path =
        if (t >= 1f) cord else quadraticHeadPath(cordFrom, cordControl, cordTo, t)
}

/**
 * 珠体渐变的跨帧缓存。
 *
 * 珠体渐变的圆心跟着倾斜走，所以不能像投影那样只建一次；但也**不能每帧重建** ——
 * `Brush` 按尺寸缓存原生 `Shader`，换实例就等于换 Shader，26 颗珠 × 60fps 一秒
 * 一千五百多个。这里按量化后的倾斜键缓存：真的转到下一档才重建一批。
 *
 * 入场路上珠子在动，渐变却仍按终态珠心建 —— 移动由绘制时的平移承担（见
 * [drawRollingBead]），否则缓存每帧全废，等于白缓存。
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
 * 两种分法的间距差在珠子尺寸之下，看不出来。珠子滚动的路程同样用 t 参数 —— 同一条
 * 曲线上的两个参数化，差的是中途的速度分布，肉眼分不出。
 */
private fun quadraticPointAt(from: Offset, control: Offset, to: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        x = u * u * from.x + 2f * u * t * control.x + t * t * to.x,
        y = u * u * from.y + 2f * u * t * control.y + t * t * to.y
    )
}

/**
 * 二次贝塞尔从起点到参数 [t] 的那一段，仍然是二次贝塞尔（de Casteljau 分割）。
 *
 * 分割点在 [t] 处把曲线切成两半，左半的控制点就是起点与控制点的插值、终点是曲线在 [t]
 * 处的点。用**精确的子曲线**而不是「把整条路径切成虚线只露出一截」：虚线是在描边阶段
 * 做的，`dashPathEffect` 的相位要跟路径总长绑定，而总长得靠 `PathMeasure` 每帧量一次；
 * 子曲线是几何本身，没有相位、没有圆头虚线在切口上留下的半圆。
 */
private fun quadraticHeadPath(from: Offset, control: Offset, to: Offset, t: Float): Path = Path().apply {
    val cx = from.x + (control.x - from.x) * t
    val cy = from.y + (control.y - from.y) * t
    val end = quadraticPointAt(from, control, to, t)
    moveTo(from.x, from.y)
    quadraticTo(cx, cy, end.x, end.y)
}

private fun planStrand(
    canvas: Size,
    strand: BraceletStrand,
    rotationOffset: Int,
    fromRight: Boolean
): StrandPlan {
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
    val leftEnd = Offset(left, baseY)
    val rightEnd = Offset(left + width, baseY)
    // 弧线**从入场那端起画**：右进的那条整条是倒着建的，于是弧线参数 0 就在右端，
    // 滚入与串绳共用同一个参数（见 braceletRollProgress / braceletCordT）
    val from = if (fromRight) rightEnd else leftEnd
    val to = if (fromRight) leftEnd else rightEnd
    // 控制点垂 2×：二次贝塞尔在 t=0.5 处只走到控制点的一半，所以要给两倍才得到 sag
    val control = Offset(left + width / 2f, baseY + sag * 2f)

    val cord = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    val count = strand.beads.size
    val beads = List(count) { index ->
        // 停在哪儿按**路径顺序**（谁先滚进来谁靠入场那端）；印什么字按**屏幕左右**排 ——
        // 右进的那条照搬路径顺序的话，SWIFTIE 会读成 EITFIWS
        val content = strand.beads[if (fromRight) count - 1 - index else index]
        val t = (index + 0.5f) / count
        val center = quadraticPointAt(from, control, to, t)
        PlacedBead(
            bead = content,
            finalT = t,
            center = center,
            size = beadSize,
            rotation = BEAD_ROTATIONS[(rotationOffset + index) % BEAD_ROTATIONS.size],
            shadowBrush = beadShadowBrush(center, beadSize)
        )
    }
    return StrandPlan(
        cord = cord,
        cordFrom = from,
        cordControl = control,
        cordTo = to,
        cordWidth = beadSize * 0.16f,
        beads = beads
    )
}

/**
 * 第 [beadIndex] 颗珠子此刻的入场进度 0f..1f：还没出发返回 null，滚到头返回 1f。
 *
 * 乘上它在弧线上的终态参数（[PlacedBead.finalT]）才是此刻的位置 —— 这里只管时间，
 * 位置是几何，两件事分开算，几何那半在 `planStrand` 里。
 *
 * 出发时刻 = `该条起步 + 序号 × 发车间隔`；路程按 `EaseOutCubic` 收在终态参数上：
 * 起步慢、后段快，滚到头那一下最利落，接到静止也不会「啪」一下停住。
 */
internal fun braceletRollProgress(
    elapsedMs: Long,
    strandIndex: Int,
    beadIndex: Int,
    beadCount: Int
): Float? {
    if (beadCount <= 0) return null
    val releaseMs = BRACELET_ROLL_SPAN_MS.toFloat() / beadCount
    val launchMs = strandIndex * BRACELET_STAGGER_MS + beadIndex * releaseMs
    val raw = (elapsedMs - launchMs) / (releaseMs * BRACELET_ROLL_RELEASE_RATIO)
    if (raw <= 0f) return null
    return EaseOutCubic.transform(raw.coerceAtMost(1f))
}

/**
 * 绳子此刻该画到哪：追上最后那颗已经出发的珠子，再多探半颗。
 *
 * 多探那半颗是必须的 —— 珠子串在绳上，绳的末端本来就该隐在最后那颗珠子底下
 * （珠子半径约等于半个间距）。不探的话整条链停住时绳头会缺一小截，看起来像断在珠子中间。
 *
 * 一颗都还没出发就返回 0：那时绳子的起点还没进画面，画出来会是一个孤零零的圆点。
 */
internal fun braceletCordT(beadT: List<Float?>, beadCount: Int): Float {
    if (beadCount <= 0) return 0f
    var head = -1f
    beadT.forEach { t -> if (t != null && t > head) head = t }
    if (head < 0f) return 0f
    return (head + 0.5f / beadCount).coerceAtMost(1f)
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
 * ## 入场：珠子沿终态那条弧线滚进来，绳子跟在后面串上
 *
 * （2026-09-13 需求方在可视化伴侣里选的方案 C。四条规则构成「手链的来处」：）
 *
 * - 珠子走的就是终态那条弧线，不是另画一条路径，也不再从上往下掉。所以入场不占额外场地，
 *   珠子不会压到上面的签名 —— 三条各自在自己的高度上从侧面滚进来，方向 左 / 右 / 左。
 * - 出发间隔 = [BRACELET_ROLL_SPAN_MS] ÷ 珠数；单颗走完全程用掉
 *   [BRACELET_ROLL_RELEASE_RATIO] 个间隔，于是弧线上同时有三颗在滚。「一颗接一颗」是
 *   流速而不是接力 —— 接力要一颗停稳下一颗才动，整条会读成被推着走的绳子。
 * - 珠子一出发就是全不透明：它出现在绳头，本来就是个实体，淡入反而像凭空冒出来。
 * - 到位即停，**不摆**。上一版落地后 ±7° 晃两下（连同回弹）已经删掉，
 *   需求方同日定案：「手链不要抖动晃来晃去」。
 *
 * 于是账本上也没有「手链段」这个边界：整段入场都发生在签名段里面
 * （起点见 `SwiftieTimeline.BRACELET_ENTRY_MS`），`SwiftieSequencePhase` 少一个相位。
 *
 * @param elapsedInBracelet 手链入场的毫秒数（第一条链起步那一刻算 0）。喂
 *   [BRACELET_SETTLED_MS] 就是停住不动的样子
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
                val pitchPx = braceletBeadPitchPx(size.width.toFloat(), strands)
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
            // 拖动只做整层平移：珠子、绳子一起跟着手走
            .graphicsLayer {
                translationX = dragOffset.value.x
                translationY = dragOffset.value.y
            }
            .drawWithCache {
                var rotationOffset = 0
                val plans = strands.mapIndexed { index, strand ->
                    planStrand(size, strand, rotationOffset, entryFromRight(index)).also {
                        rotationOffset += strand.beads.size
                    }
                }
                // 珠体渐变按量化倾斜缓存；尺寸变了整块 cache 会重建，缓存跟着新建
                val bodyBrushes = BeadBodyBrushes(plans)
                onDrawBehind {
                    // 逐帧量全在这个 lambda 里读：只失效绘制，不重组
                    val elapsed = elapsedInBracelet()
                    val tilt = highlight.value
                    val bodies = bodyBrushes.forHighlight(tilt)
                    var beadIndex = 0
                    plans.forEachIndexed { strandIndex, plan ->
                        val beadT = plan.beads.mapIndexed { index, bead ->
                            braceletRollProgress(elapsed, strandIndex, index, plan.beads.size)
                                ?.let { bead.finalT * it }
                        }
                        val cordT = braceletCordT(beadT, plan.beads.size)
                        // 先绳后珠：珠子压住绳子，珠间露出的那截就是穿过珠孔的绳
                        if (cordT > 0f) {
                            drawPath(
                                path = plan.cordAt(cordT),
                                color = CORD_COLOR,
                                alpha = 0.85f,
                                style = Stroke(width = plan.cordWidth, cap = StrokeCap.Round)
                            )
                        }
                        plan.beads.forEachIndexed { index, placed ->
                            // 渐变是按终态珠心建的，滚入路上的珠子靠平移把渐变一起带走。
                            // 不这么做就得每帧重建 33 个 Shader（见 BeadBodyBrushes）
                            val brush = bodies[beadIndex]
                            beadIndex++
                            val t = beadT[index] ?: return@forEachIndexed
                            drawRollingBead(
                                placed = placed,
                                center = quadraticPointAt(
                                    plan.cordFrom, plan.cordControl, plan.cordTo, t
                                ),
                                highlight = tilt,
                                letterLayouts = letterLayouts,
                                bodyBrush = brush
                            )
                        }
                    }
                }
            }
    )
}

/**
 * 画一颗珠子，位置可能与它的终态珠心不同（入场路上）。
 *
 * 平移画布而不是重建渐变：渐变、投影、随机旋转都留在终态坐标系里，整块挪过去就行。
 * 已经在位上的珠子直接画，省掉一次 save / restore。
 */
private fun DrawScope.drawRollingBead(
    placed: PlacedBead,
    center: Offset,
    highlight: Offset,
    letterLayouts: Map<String, TextLayoutResult>,
    bodyBrush: Brush
) {
    val dx = center.x - placed.center.x
    val dy = center.y - placed.center.y
    if (dx == 0f && dy == 0f) {
        drawSwiftieBead(
            bead = placed.bead,
            center = placed.center,
            size = placed.size,
            rotationDeg = placed.rotation,
            highlight = highlight,
            letterLayouts = letterLayouts,
            shadowBrush = placed.shadowBrush,
            bodyBrush = bodyBrush
        )
        return
    }
    withTransform({ translate(dx, dy) }) {
        drawSwiftieBead(
            bead = placed.bead,
            center = placed.center,
            size = placed.size,
            rotationDeg = placed.rotation,
            highlight = highlight,
            letterLayouts = letterLayouts,
            shadowBrush = placed.shadowBrush,
            bodyBrush = bodyBrush
        )
    }
}

/** 松手回弹。`dampingRatio 0.35` 会过冲一两下，正是弹性绳该有的样子。 */
private val REBOUND = spring<Offset>(
    dampingRatio = 0.35f,
    stiffness = Spring.StiffnessLow
)

/**
 * 最前那条手链上相邻两颗珠子的中心间距，像素。「逐珠划过」的触感按它计数。
 *
 * 取 [braceletStrands] 结果的末位，也就是画得最后、压在最上面那条：它挂在
 * [BRACELET_SLOTS] 最宽的那个插槽上、珠子最大，手指真正划到的就是它。有昵称时
 * 它就是昵称那条，珠数随昵称长短变，所以不能预先算成常量。
 * 间距 = 该条宽度 ÷ 珠数，与 [planStrand] 里珠心落在 `(index + 0.5) / size` 的排法一致。
 *
 * 从画布宽度算而不是写死 dp：平板上珠子更大，一记之间本来就该走更远。
 * 夹到至少 1px 防零宽画布把它除成 0 或无穷；[strands] 空了也回退到 1px，
 * 那时手链本来就没画出来，除以 0 会让这一格触感变成每像素一记。
 */
private fun braceletBeadPitchPx(canvasWidthPx: Float, strands: List<BraceletStrand>): Float {
    val strand = strands.lastOrNull() ?: return 1f
    return (canvasWidthPx * strand.slot.widthFraction / strand.beads.size).coerceAtLeast(1f)
}
