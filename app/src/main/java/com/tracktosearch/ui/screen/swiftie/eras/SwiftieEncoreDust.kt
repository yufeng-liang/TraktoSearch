package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.core.content.res.ResourcesCompat
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import kotlin.math.sin

/**
 * 续章开头那 900ms：一撮尘埃从卡片各处聚成 `: THE ENCORE` 这几个字。
 *
 * ## 尘埃往哪儿聚
 *
 * 落点不是「在基线上按间隔采样」，而是**从字形的真实笔画轮廓上取**：
 * `Paint.getTextPath` 给出这几个字的矢量轮廓，`PathMeasure` 按弧长均匀取点。
 * 这样尘埃落定之后的分布与字形本身完全同形 —— 按基线采点的话，`O` 与 `E` 会拿到
 * 一样多的落点，而 `O` 有一圈笔画、`E` 有三条横，疏密一眼就不对。
 *
 * **不要改用 `TextLayoutResult.getPathForRange`**：真机上它给的是**文本选区的路径**
 * （一个圆角矩形），尘埃会聚成一个虚线方框而不是字。这条是踩过的坑，
 * 见 `buildDustTargets` 的说明。
 *
 * ## 尘埃从哪儿来
 *
 * 起点按种子散在**卡片内部**（不是全屏）：这一段演的是「这张卡上的尘埃聚成一行字」，
 * 从屏幕外飞进来就变成另一件事了。
 *
 * ## 时间源
 *
 * 走序列时钟（[SwiftieTimeline.encoreDustProgress]），**不用
 * `rememberInfiniteTransition`** —— 那是一条独立的无限循环（`SwiftieGlitterDust`
 * 用的就是它，40s 一圈），接不进序列，彩蛋被 seek 或倒滑之后两边就对不上了。
 */

/**
 * 尘埃颗粒数。低端机减半。
 *
 * 220 是**真机调出来的**：150 粒摊在整张卡的范围上，落到 `: THE ENCORE` 那九个字形
 * 的轮廓上就只剩零星几粒，读不出「一撮尘埃聚成字」。粒数不够时不是加半径能救的 ——
 * 半径一大就变成「光斑」而不是「尘埃」。
 */
internal const val DUST_COUNT = 220
internal const val DUST_COUNT_LOW_RAM = 110

/**
 * 每颗粒子的过冲：飞到落点后**再往前一点点**再回吸。
 *
 * 纯 `EaseOutCubic` 收敛是「贴上去」，带一点过冲再回吸才是「落定」。0.06 是按
 * 落点到起点的距离算的比例，不是绝对值 —— 远距离飞来的粒子过冲量更大，
 * 读起来是「飞得快的冲得远」，符合直觉。
 */
private const val DUST_OVERSHOOT = 0.06f

/** 过冲发生在进度最后这一段里。 */
private const val DUST_OVERSHOOT_FROM = 0.7f

/**
 * 尘埃颗粒的基准半径，相对**字形包围盒的短边**（即字高）。
 *
 * 0.045 是按真机反推的：21dp 的标题在 3x 屏上字高约 44px，乘出来 2px ——
 * 颗粒要小到读作「尘埃」、又要大到在 1080p 上看得见，2~3.5px 正是这一档。
 * （初版写的 0.0045 乘出来只有 0.2px，在真机上等于不画；0.03 那版仍偏细。）
 */
private const val DUST_RADIUS_RATIO = 0.045f

/** 颗粒半径的随机范围（乘 [DUST_RADIUS_RATIO]）。 */
private const val DUST_RADIUS_MIN = 0.6f
private const val DUST_RADIUS_MAX = 1.7f

/** 尘埃的颜色：金闪粉的暖金，与标题那句 `: THE ENCORE` 同族。 */
private val DUST_COLOR = Color(0xFFE8C15A)

/** 尘埃落定时的高光色（靠近落点的那几粒更亮一点，像颗粒反光）。 */
private val DUST_HIGHLIGHT = Color(0xFFFFF3D0)

/**
 * 一颗粒子的固定量。**只存不随时间变的** —— 位置每帧算。
 */
internal class DustMote(
    val seed: Int,
    /** 起点，卡片内的相对坐标 0f..1f。 */
    val startX: Float,
    val startY: Float,
    /** 落点在 [DustTargets] 里的下标。 */
    val targetIndex: Int,
    /** 起飞的相位偏移 0f..1f：不是所有粒子同时出发，否则读成一块布在平移。 */
    val delay: Float,
    /** 路径的横向摆动幅度与相位。 */
    val sway: Float,
    val swayPhase: Float,
    /** 半径（相对 minDimension）。 */
    val radius: Float,
    /** 亮度档 0f..1f。 */
    val warmth: Float
)

/**
 * 落点集合：字形轮廓上按弧长均匀取的点，归一化到**卡片内容坐标**。
 *
 * 建一次就够（只依赖字形与卡片宽度，不随时间变），所以由调用方 `remember` 住。
 */
internal class DustTargets(
    val points: List<Offset>,
    /** 这行字在卡片内的包围盒，用于把起点限制在合理的区域里。 */
    val bounds: Rect
) {
    val isEmpty: Boolean get() = points.isEmpty()
}

/**
 * 量出 `: THE ENCORE` 的字形轮廓，并在其上取 [count] 个均匀落点。
 *
 * ## 为什么用 `Paint.getTextPath` 而不是 `TextLayoutResult.getPathForRange`
 *
 * 后者是**文本选区**的路径 —— 真机截图里它返回的是一个圆角矩形（选区的包围盒轮廓），
 * 尘埃于是聚成一个虚线方框，而不是聚成字。要拿到**笔画轮廓**只有
 * `android.graphics.Paint.getTextPath` 这一条路，项目里 `SwiftieLetterInk` 与
 * `SwiftiePosterInk` 取字形轮廓用的都是它。
 *
 * 代价是 `Paint` 得自己配：字体（`ResourcesCompat.getFont`）、字号（px）、
 * 以及**每个字的行内偏移**（`measureText` 累加 advance）—— `getTextPath` 只认
 * 「从 x 起画这一段文字」，不会替我们排版。
 *
 * 一切坐标都归一到 0f..1f 的**字形包围盒**（调用方按它还原成像素）。
 *
 * @param context 取字体用（`getTextPath` 只能在组合阶段调，与 `imageResource` 同理）
 * @param fontSizePx 字号（像素），与标题那一行同档
 */
internal fun buildDustTargets(
    context: android.content.Context,
    text: String,
    fontResId: Int,
    fontSizePx: Float,
    count: Int
): DustTargets {
    if (count <= 0 || fontSizePx <= 0f) return DustTargets(emptyList(), Rect.Zero)
    val typeface = ResourcesCompat.getFont(context, fontResId) ?: return DustTargets(emptyList(), Rect.Zero)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.typeface = typeface
        textSize = fontSizePx
    }
    // 逐字取轮廓再并起来：整串一次 getTextPath 会带上 Paint 自己的字距，
    // 而我们要的是「这些字排成一行」的总轮廓，逐字按 measureText 定位最直接
    val outline = Path()
    var penX = 0f
    for (index in text.indices) {
        val glyph = android.graphics.Path()
        paint.getTextPath(text, index, index + 1, penX, 0f, glyph)
        outline.addPath(glyph.asComposePath())
        penX += paint.measureText(text, index, index + 1)
    }
    if (outline.isEmpty) return DustTargets(emptyList(), Rect.Zero)

    // 按弧长在**所有字形**上均匀取点。
    //
    // **必须用 android.graphics.PathMeasure，不能用 compose 那个**：
    // compose 的 `PathMeasure` 只有 `getLength` / `getPosition`，没有 `nextContour`，
    // 而 `getLength` 对多子路径的 Path **只返回第一条 contour** —— 于是所有采样点
    // 全落在第一个字形（这里是冒号）上，尘埃聚成一小团而不是一行字。真机上撞过这个坑。
    // Android 原生那个有 `nextContour()`，可以逐条子路径走完。
    //
    // 分配方式：先量出每条子路径的长度与总长，再按「该条占多少比例」把 count 个
    // 采样点分下去，每条子路径内部按自己的弧长均分。**不是**先算全局弧长再回头找
    // 子路径 —— 那要每个点重走一次 contour，O(n × contours) 在 220 个点上不划算。
    val native = android.graphics.PathMeasure()
    val androidOutline = outline.asAndroidPath()
    native.setPath(androidOutline, false)
    val contourLengths = ArrayList<Float>()
    var total = 0f
    do {
        val length = native.length
        contourLengths += length
        total += length
    } while (native.nextContour())
    if (total <= 0f) return DustTargets(emptyList(), Rect.Zero)

    val bounds = outline.getBounds()
    val points = ArrayList<Offset>(count)
    var remainingPoints = count
    for (contour in contourLengths.indices) {
        val contourLength = contourLengths[contour]
        // 这条子路径分到几个点：按长度比例，最后一条收下所有余数（避免取整丢点）
        val share = if (contour == contourLengths.lastIndex) {
            remainingPoints
        } else {
            (count * (contourLength / total)).toInt().coerceIn(0, remainingPoints)
        }
        if (share > 0 && contourLength > 0f) {
            native.setPath(androidOutline, false)
            repeat(contour) { native.nextContour() }
            val pos = FloatArray(2)
            for (slot in 0 until share) {
                // 0.5 的偏移：不取子路径的起点（那是轮廓的角点，颗粒会挤在角上）
                val distance = contourLength * (slot + 0.5f) / share
                // Android 的 PathMeasure 是 `getPosTan(distance, pos, tan)`，
                // **没有** `getPosition`（那是 compose 接口的方法名）
                if (!native.getPosTan(distance, pos, null)) continue
                val nx = if (bounds.width > 0f) (pos[0] - bounds.left) / bounds.width else 0.5f
                val ny = if (bounds.height > 0f) (pos[1] - bounds.top) / bounds.height else 0.5f
                points += Offset(nx, ny)
            }
        }
        remainingPoints -= share
    }
    if (points.isEmpty()) return DustTargets(emptyList(), Rect.Zero)
    return DustTargets(points, bounds)
}

/**
 * 尘埃云的外扩范围上限，单位是字高 / 字宽。
 *
 * 起点**不是**摊满整张卡：那样粒子大半时间在卡的另一头飞，落到字形上的那几粒
 * 读不出「云聚成字」，只像几粒浮尘。真机上试下来 2 个字高的范围是一团「云」的样子 ——
 * 再大就散，再小就挤成描边。
 */
private const val DUST_SPREAD_CAP = 2.0f

/**
 * 建一撮尘埃。
 *
 * ## 坐标系
 *
 * 起点与落点**都**存在「字形包围盒相对坐标」里：0f..1f 正好是 `: THE ENCORE`
 * 那几个字的包围盒，1 个字高 = 1.0。往外可以超出这个范围。
 *
 * 之所以不各用各的坐标系再换算，是因为插值是 `start + (target - start) * eased` ——
 * 两边不同源就会在落定那一刻突然跳一下（落点在字形坐标、起点在卡片坐标）。
 *
 * @param count 颗粒数
 * @param targets 落点集合（字形相对坐标）
 * @param spreadAbove 字形包围盒**上方**还有多少空间（以字高为单位，恒 >= 0）
 * @param spreadBelow 字形包围盒**下方**还有多少空间（同上）
 * @param spreadLeft 字形包围盒**左侧**还有多少空间（以**字宽**为单位，恒 >= 0）
 * @param spreadRight 字形包围盒**右侧**还有多少空间（同上）
 */
internal fun buildDustMotes(
    count: Int,
    targets: DustTargets,
    spreadAbove: Float,
    spreadBelow: Float,
    spreadLeft: Float,
    spreadRight: Float
): List<DustMote> {
    if (targets.isEmpty) return emptyList()
    // 外扩范围封顶（见 DUST_SPREAD_CAP）：四边都用同一个上限，云才是圆的
    val up = spreadAbove.coerceAtMost(DUST_SPREAD_CAP)
    val down = spreadBelow.coerceAtMost(DUST_SPREAD_CAP)
    val left = spreadLeft.coerceAtMost(DUST_SPREAD_CAP)
    val right = spreadRight.coerceAtMost(DUST_SPREAD_CAP)
    return List(count) { index ->
        // 用 hash 而不是 Random：同一份种子每次重建都一样，seek 回去画面不跳
        val horizontal = hash01(index, 0, 11)
        val vertical = hash01(index, 0, 23)
        // 上下的粒子各半。某一侧没有空间就全给另一侧 —— 否则那半粒子会挤在
        // 字形的边缘上，落定前一刻读成「一圈描边」
        val fromAbove = when {
            up <= 0f -> false
            down <= 0f -> true
            else -> hash01(index, 0, 29) < 0.5f
        }
        // 上方：从字形顶（0f）往上到 -up；下方：从字形底（1f）往下到 1+down
        val sy = if (fromAbove) {
            -up * vertical
        } else {
            1f + down * vertical
        }
        // 横向同理：字形左边从 0f 往左到 -left，右边从 1f 往右到 1+right
        val sx = -left + (1f + left + right) * horizontal
        DustMote(
            seed = index,
            startX = sx,
            startY = sy,
            targetIndex = (hash01(index, 0, 31) * targets.points.size).toInt()
                .coerceIn(0, targets.points.size - 1),
            // 起飞错开：前 35% 的进度里陆续出发
            delay = hash01(index, 0, 41) * 0.35f,
            sway = (hash01(index, 0, 53) - 0.5f) * 0.35f,
            swayPhase = hash01(index, 0, 67),
            radius = DUST_RADIUS_MIN +
                hash01(index, 0, 79) * (DUST_RADIUS_MAX - DUST_RADIUS_MIN),
            warmth = hash01(index, 0, 97)
        )
    }
}

/**
 * 字形包围盒四周各有几个字高 / 字宽的空间可用。
 *
 * 一切都在**字形坐标**里算：字形自己恒占 0f..1f（那是定义），卡片内容区从
 * `-titleBox.top / titleBox.height` 到 `(cardHeight - titleBox.top) / titleBox.height`。
 * 于是「上方空间」= 卡顶到字形顶的距离，以此类推。
 *
 * 四个值都取非负：某侧真的没空间时（标题贴着卡边）给 0，
 * [buildDustMotes] 会把粒子全给对面那侧。
 *
 * @param titleBox 字形包围盒在**卡片内容区**里的位置与尺寸（px）
 * @param cardWidth 卡片内容区的宽（px）
 * @param cardHeight 卡片内容区的高（px）
 */
internal fun dustSpreadOf(
    titleBox: Rect,
    cardWidth: Float,
    cardHeight: Float
): DustSpread {
    if (titleBox.width <= 0f || titleBox.height <= 0f) return DustSpread(0f, 0f, 0f, 0f)
    return DustSpread(
        above = (titleBox.top / titleBox.height).coerceAtLeast(0f),
        below = ((cardHeight - titleBox.bottom) / titleBox.height).coerceAtLeast(0f),
        left = (titleBox.left / titleBox.width).coerceAtLeast(0f),
        right = ((cardWidth - titleBox.right) / titleBox.width).coerceAtLeast(0f)
    )
}

/** [dustSpreadOf] 的四个方向余量，单位分别是字高（上下）与字宽（左右）。 */
internal class DustSpread(
    val above: Float,
    val below: Float,
    val left: Float,
    val right: Float
)

/**
 * 一颗尘埃在 [progress]（0f..1f）时的位置与亮度。
 *
 * 归一化坐标（相对字形包围盒）。返回 null = 这一颗还没出发。
 *
 * 缓动用 `EaseOutCubic` 的等价多项式（`1-(1-t)^3`）：起步快、收尾稳，
 * 与卡片自己的长出用的是同一条曲线，整段的手感一致。
 */
internal fun dustPositionOf(
    mote: DustMote,
    targets: DustTargets,
    progress: Float
): DustSample? {
    if (targets.isEmpty) return null
    // 每颗自己的窗口：delay 之后起飞，1f 时全部落定
    val span = (1f - mote.delay).coerceAtLeast(0.0001f)
    val local = ((progress - mote.delay) / span).coerceIn(0f, 1f)
    if (local <= 0f) return null

    val eased = 1f - (1f - local) * (1f - local) * (1f - local)
    val target = targets.points[mote.targetIndex]
    val start = Offset(mote.startX, mote.startY)

    // 过冲：末段再往前一点点，然后回吸。位移方向 = 起点指向落点
    val overshoot = if (local > DUST_OVERSHOOT_FROM) {
        val tail = (local - DUST_OVERSHOOT_FROM) / (1f - DUST_OVERSHOOT_FROM)
        // sin 一个完整的半波：0 → 1 → 0，所以「冲出去再回来」是连续的
        sin(tail * Math.PI.toFloat()) * DUST_OVERSHOOT
    } else {
        0f
    }

    val dx = target.x - start.x
    val dy = target.y - start.y
    // 横向摆动：飞行途中偏一点，落定时必须归零（否则颗粒落不在字形上）
    val sway = mote.sway * sin((mote.swayPhase + local * 2f) * 2f * Math.PI.toFloat()) *
        (1f - eased)
    val x = start.x + dx * (eased + overshoot) + sway
    val y = start.y + dy * (eased + overshoot)
    // 亮度：起飞时淡入，落定时最亮
    val alpha = (local * 3f).coerceAtMost(1f)
    return DustSample(Offset(x, y), alpha, mote.radius, mote.warmth)
}

/** 一颗尘埃这一帧的取样结果。 */
internal class DustSample(
    val position: Offset,
    val alpha: Float,
    val radius: Float,
    val warmth: Float
)

/**
 * 尘埃层的整体不透明度：聚字窗口结束后跟着 [SwiftieTimeline.encoreInkProgress] 退场。
 *
 * 尘埃不是「聚完就消失」—— 真字淡入的同时它还在，两三层重叠一小段才读作
 * 「尘埃变成了字」而不是「尘埃被抹掉、字另起一层」。
 */
internal fun dustLayerAlpha(elapsedInCard: Long): Float {
    val ink = SwiftieTimeline.encoreInkProgress(elapsedInCard)
    // 聚字窗口内恒 1，之后随真字淡入线性退到 0
    return (1f - ink).coerceIn(0f, 1f)
}

/**
 * 这撮尘埃的落点颜色：按 [DustSample.warmth] 在暖金与高光之间混。
 *
 * 不走 `Color.lerp`（那是 Oklab 插值，在这个跨度上会把中间调拉灰），
 * 直接通道线性插。
 */
internal fun dustColorOf(warmth: Float): Color {
    val t = warmth.coerceIn(0f, 1f)
    return Color(
        red = DUST_COLOR.red + (DUST_HIGHLIGHT.red - DUST_COLOR.red) * t,
        green = DUST_COLOR.green + (DUST_HIGHLIGHT.green - DUST_COLOR.green) * t,
        blue = DUST_COLOR.blue + (DUST_HIGHLIGHT.blue - DUST_COLOR.blue) * t
    )
}

/**
 * 把这一撮尘埃画在卡片上。
 *
 * 挂在卡片**内部**（标题那一行的位置），所以坐标用 [targets] 的包围盒对齐到
 * 标题那一行 —— 尘埃落定的地方就是真字将要出现的地方，两层的字面才对得上。
 *
 * @param elapsedInCard 卡片自己的已用毫秒
 * @param targets 字形落点（由 [buildDustTargets] 建，调用方 `remember` 住）
 * @param motes 这一撮粒子（由 [buildDustMotes] 建，同上）
 * @param titleBox 标题那一行在卡片内的位置与尺寸（px）。尘埃按它定位
 */
internal fun DrawScope.drawEncoreDust(
    elapsedInCard: Long,
    targets: DustTargets,
    motes: List<DustMote>,
    titleBox: Rect
) {
    if (targets.isEmpty || motes.isEmpty()) return
    val alpha = dustLayerAlpha(elapsedInCard)
    if (alpha <= 0.01f) return
    val progress = SwiftieTimeline.encoreDustProgress(elapsedInCard)
    // 聚字还没开始：整层不画（省掉每帧 150 次位置计算）
    if (progress <= 0f) return

    val minDimension = minOf(titleBox.width, titleBox.height)
    val radiusBase = minDimension * DUST_RADIUS_RATIO
    for (mote in motes) {
        val sample = dustPositionOf(mote, targets, progress) ?: continue
        val x = titleBox.left + sample.position.x * titleBox.width
        val y = titleBox.top + sample.position.y * titleBox.height
        drawCircle(
            color = dustColorOf(sample.warmth),
            radius = radiusBase * sample.radius,
            center = Offset(x, y),
            alpha = sample.alpha * alpha
        )
    }
}
