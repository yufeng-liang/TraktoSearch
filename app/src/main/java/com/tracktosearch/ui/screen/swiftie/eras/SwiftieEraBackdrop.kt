package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import com.tracktosearch.ui.screen.swiftie.unitHeartPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

/**
 * L1 大主体的基准不透明度。
 *
 * 这一层是**底衬**：上面要压半透明白卡片、12 段色带、TS 标签与轴。0.34 是「结构看得清」
 * 与「不把前景糊掉」的交界 —— 实测再高一档，reputation 的王座剪影会从卡片的白纸里透上来。
 * 每个物件在此基础上再乘自己的层次系数（远景 ×0.5、近景 ×1.2 之类）。
 */
private const val PROP_ALPHA = 0.34f

/** 剪影档：城堡、天际线、松林这类「一整块挡住光」的东西，比装饰重一档。 */
private const val SILHOUETTE_ALPHA = 0.52f

/** 远景档：雾、第二层松林、星野边缘。 */
private const val DISTANT_ALPHA = 0.18f

/**
 * 灯火的暖色。
 *
 * 12 张的 `backdropColors` 里没有一个暖光档 —— 门廊漏出的屋内光、evermore 的灯笼芯、
 * folklore 的窗内炉火、Showgirl 的边灯灯泡，用各自专辑的主色画出来读作「一块亮色斑」
 * 而不是「有光源」。光源必须比它照亮的环境更黄更亮，这是唯一不能靠色阶让步的地方。
 */
private val LAMP_CORE = Color(0xFFFFF0C4)
private val LAMP_WARM = Color(0xFFFFC46B)

/** 描金：evermore 的金线、reputation 的王座、Showgirl 的 marquee 边框共用。 */
private val GOLD = Color(0xFFD4AF37)

/**
 * 王座的两档暗金。
 *
 * 有了它们，王座的深浅**全部靠颜色**、一处都不靠 alpha：金属是实心的，而王座背后先压着
 * 整屏网点、后来又要挡住蛇绕过去的那半圈 —— 任何一块半透明的部件都会把身后的东西漏出来
 * （第五轮截图里蛇的远侧半圈整条从座面里透出来）。
 *
 * 上一版拿 alpha 当明暗档（0.52 → 0.95 七个值），台阶越往下越透，正好是最该实心的地方最虚。
 * 换成同一个色相的三档明度：`GOLD` 是受光面（座面、扶手搭手面），[GOLD_MID] 是立面，
 * [GOLD_DEEP] 是背光面与最下那级台阶。
 */
private val GOLD_MID = Color(0xFFA98A2A)
private val GOLD_DEEP = Color(0xFF7C641E)

/** 手稿与宝丽来的纸白。1989 的淡蓝档与 TTPD 的米白档都偏灰，纸压在上面得更白一点。 */
private val PAPER_WHITE = Color(0xFFFBF7F0)

/** 稿纸上的「字迹」与打字机的机身墨色。TTPD 卡片文字用的就是这一个墨。 */
private val INK = Color(0xFF4A453E)

/**
 * 青苔。
 *
 * folklore 那三档是 `#E8E6E2 / #8C8C8C / #4A4844`，**整套没有一点绿**。
 * 「长满青苔的白钢琴」这个意象里，青苔是唯一能证明「这台琴在林子里放了很久」的东西，
 * 用灰色画出来只会读作琴身脏了。
 */
private val MOSS = Color(0xFF6E7A55)
private val MOSS_LIGHT = Color(0xFF8C9968)

/**
 * 每张的随机点位一次性建好，`remember` 住。
 *
 * 固定种子（各专辑发行年份），所以重组、旋转屏、甚至进程重启后位置都一样 ——
 * 背景是「这一张专辑的舞台」，每次进来长得不一样就不是同一个舞台了。
 *
 * 全部存成扁平 `FloatArray`：draw lambda 里遍历不装箱、不分配迭代器。
 */
private class BackdropShapes(lowRam: Boolean) {
    /** Midnights 星野：x, y, r 三元组。低配砍到三分之一。 */
    val stars: FloatArray = buildTriples(if (lowRam) 40 else 120, 2010) { random ->
        floatArrayOf(
            random.nextFloat(),
            random.nextFloat() * 0.72f,
            0.0016f + random.nextFloat() * 0.0042f
        )
    }

    /** 1989 天际线：x, 楼宽, 楼高（占屏高）三元组。刻意不排序，楼群互相遮挡才有纵深。 */
    val towers: FloatArray = buildTriples(if (lowRam) 12 else 22, 1989) { random ->
        floatArrayOf(
            random.nextFloat(),
            0.035f + random.nextFloat() * 0.055f,
            0.10f + random.nextFloat() * 0.26f
        )
    }

    /**
     * 1989 宝丽来：x, y, 旋转（度）三元组。挂在上半屏，不压卡片。
     *
     * y 从 0.085h 起：麻线在 0.058h（状态栏以下），原来 0.04h 起的那一档会让
     * 半数照片吊到线的**上面**去。
     */
    val polaroids: FloatArray = buildTriples(if (lowRam) 2 else 4, 1014) { random ->
        floatArrayOf(
            0.08f + random.nextFloat() * 0.80f,
            0.085f + random.nextFloat() * 0.145f,
            -14f + random.nextFloat() * 28f
        )
    }

    /** reputation 半调网点的行抖动，让点阵不是一张死格子。 */
    val halftoneJitter: FloatArray = buildTriples(24, 2017) { random ->
        floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat())
    }

    private val halftoneColumns = if (lowRam) 14 else 22
    private var halftoneFor = Size.Zero
    private val halftonePath = Path()

    /**
     * 半调网点整块建成一条 Path，**只在尺寸变了才重建**。
     *
     * 八百多个圆点，逐个 `drawCircle` 是八百次原生调用；塞进一条 Path 是一次。
     * 而网点本身不动，连重建都不必每帧做 —— 缓存的判据只有画布尺寸。
     */
    fun halftone(canvas: Size): Path {
        if (canvas == halftoneFor) return halftonePath
        halftoneFor = canvas
        halftonePath.rewind()
        val step = canvas.width / halftoneColumns
        val rows = (canvas.height / step).toInt() + 2
        for (r in 0 until rows) {
            val jitter = halftoneJitter[(r % 8) * 3]
            for (c in 0..halftoneColumns) {
                // 隔行错半格：正交网格一眼就是「屏幕坏点」，错开才是印刷网点
                val cx = (c + if (r % 2 == 0) 0f else 0.5f) * step
                val cy = r * step
                // 点径自上而下涨：上疏下密是报纸从高光过渡到暗部的做法
                val grow = 0.16f + 0.30f * (cy / canvas.height) + jitter * 0.06f
                val radius = step * grow
                halftonePath.addOval(
                    Rect(cx - radius, cy - radius, cx + radius, cy + radius)
                )
            }
        }
        return halftonePath
    }
}

private inline fun buildTriples(count: Int, seed: Int, item: (Random) -> FloatArray): FloatArray {
    val random = Random(seed)
    val out = FloatArray(count * 3)
    for (i in 0 until count) {
        val triple = item(random)
        out[i * 3] = triple[0]
        out[i * 3 + 1] = triple[1]
        out[i * 3 + 2] = triple[2]
    }
    return out
}

/**
 * 页面级背景的 L0 + L1：12 个时代各自的底色渐变与大主体。
 *
 * ## 为什么在页面这一层而不是卡片里
 *
 * 卡片是一张 320dp 宽的半透明白纸，装不下打字机、钢琴、天际线这种尺度的东西 ——
 * 硬塞进去只能缩成图标。而页面背景原本是空的（只有六团水彩），把大主体放这里，
 * 卡片右侧那一列只留小道具（宝丽来、松树），两边各得其所。
 *
 * ## 换张
 *
 * [crossfade] 为 0f 时只画 [outgoing] 一张 —— 画两层会在同一处叠出半透明重影。
 * 大于 0f 时两层按 `1f - crossfade` / `crossfade` 同时画。这 500ms 的窗口刻意等于
 * 「卡片回落 400ms + 段间停顿 100ms」，那时候旧卡片正在收、新卡片还没长出，
 * 背景换色藏在里面看不见接缝，**账本一毫秒不动**。
 *
 * ## 相位
 *
 * [phase] 是 0f..1f 的锯齿波，周期 3.6s（见 `SwiftieEggScreen.BACKDROP_CYCLE_MS`）。
 * 所有由它驱动的往复都是它的**整数倍频**，否则 1f 绕回 0f 那一帧整层会跳一下。
 * 飘落物走的是另一份 12s 的相位，不在本文件里。
 *
 * @param eraElapsedMs 本段已过的毫秒，`-1` 或负数 = 待机态（本段还没开始）。
 *   **三张背景读它**：TTPD 那台打字机（敲字 / 滑架 / 出纸要与卡片对上拍）、reputation
 *   那条蛇（整段走完「进场 → 绕王座 → 立起头 → 出画」一趟），以及 Midnights 面钟上弦的
 *   两根指针（一次性动作，`phase` 那个 3.6s 锯齿问不出「走到第几拍」）。其余 9 张只看 [phase]
 * @param cardBounds TTPD 卡片在根坐标里的边框（px），[Rect.Zero] = 还没量到。
 *   打字机按它把出纸口坐到纸的下缘、把滚筒对齐纸宽 —— 机器在屏幕底下，纸从滚筒
 *   后头升上来，先打的行升得最高
 * @param lowRam 低配降档：远景层与点阵数量减半，但**不许整层静止** ——
 *   定格的背景在用户眼里就是卡死
 */
@Composable
fun SwiftieEraBackdropLayer(
    outgoing: () -> Int,
    incoming: () -> Int,
    crossfade: () -> Float,
    phase: () -> Float,
    eraElapsedMs: () -> Long,
    cardBounds: () -> Rect,
    lowRam: Boolean,
    modifier: Modifier = Modifier
) {
    val shapes = remember(lowRam) { BackdropShapes(lowRam) }
    // 12 条底色渐变一次建完。Brush 内部按尺寸缓存原生 Shader，每帧新建就是每帧一个
    val skies = remember {
        SwiftieErasData.STAGE.map { stage -> Brush.verticalGradient(stage.backdropColors) }
    }
    // 循环里反复 rewind 的那一条 Path，与 SwiftieEraMotifs / SwiftieEraParticles 同一条铁律
    val scratch = remember { Path() }
    val numerals = rememberClockNumeralLayouts()
    // 参考图取墨的枫叶（只有 Red 那张的五片大叶用得上）。两张小 PNG 无条件解码 ——
    // 这一层拿到的号同 `SwiftieEraParticleLayer`：每帧变的时钟派生 lambda，
    // 在组合阶段读一次就把整层拖成逐帧重组
    val maple = rememberMapleBentArt()
    Spacer(
        modifier = modifier.fillMaxSize().drawBehind {
            val from = outgoing().coerceIn(0, skies.lastIndex)
            val to = incoming().coerceIn(0, skies.lastIndex)
            val mix = crossfade().coerceIn(0f, 1f)
            val t = phase()
            val eraMs = eraElapsedMs()
            val card = cardBounds()
            if (mix <= 0f || from == to) {
                drawStage(from, 1f, t, eraMs, card, skies, scratch, shapes, numerals, lowRam, maple)
            } else {
                drawStage(from, 1f - mix, t, eraMs, card, skies, scratch, shapes, numerals, lowRam, maple)
                // 换张那 500ms 里 incoming 的段还没开始：给它 -1 走待机态，否则 TTPD 的纸
                // 会在上一张还没收完时就开始往外吐，reputation 的蛇也会提前从左缘钻出来
                drawStage(to, mix, t, -1L, card, skies, scratch, shapes, numerals, lowRam, maple)
            }
        }
    )
}

/**
 * 一张舞台：底色渐变铺满，再叠这一张的大主体。
 *
 * [alpha] 是换张淡变的权重，**乘进每一笔**而不是靠 `graphicsLayer` 隔离一层 ——
 * 两张舞台各开一次离屏合成，在换张那 500ms 里每帧要多两次全屏 blit。
 */
private fun DrawScope.drawStage(
    index: Int,
    alpha: Float,
    phase: Float,
    eraMs: Long,
    card: Rect,
    skies: List<Brush>,
    path: Path,
    shapes: BackdropShapes,
    numerals: List<TextLayoutResult>,
    lowRam: Boolean,
    maple: MapleArt
) {
    if (alpha <= 0.01f) return
    val stage = SwiftieErasData.STAGE[index]
    drawRect(brush = skies[index], alpha = alpha)
    val colors = stage.backdropColors
    val top = colors[0]
    val mid = colors[1]
    val deep = colors[2]
    when (stage.backdrop) {
        SwiftieEraBackdrop.FIREFLY_PORCH -> drawFireflyPorch(path, top, mid, deep, phase, alpha)
        SwiftieEraBackdrop.GOLDEN_CASTLE -> drawGoldenCastle(path, top, mid, deep, phase, alpha)
        SwiftieEraBackdrop.VEIL_SPOTLIGHT -> drawVeilSpotlight(path, top, mid, deep, phase, alpha)
        SwiftieEraBackdrop.KNIT_AUTUMN -> drawKnitAutumn(path, top, mid, deep, phase, alpha, lowRam, maple)
        SwiftieEraBackdrop.SKYLINE_POLAROIDS ->
            drawSkylinePolaroids(path, top, mid, deep, phase, alpha, shapes)
        SwiftieEraBackdrop.HALFTONE_THRONE ->
            drawHalftoneThrone(path, top, phase, alpha, eraMs, shapes)
        SwiftieEraBackdrop.PASTEL_RAINBOW_HOUSE ->
            drawPastelRainbowHouse(path, top, mid, deep, phase, alpha, eraMs, card)
        SwiftieEraBackdrop.PINE_MOSS_PIANO ->
            drawPineMossPiano(path, top, mid, deep, phase, alpha)
        SwiftieEraBackdrop.BRANCH_LANTERNS ->
            drawBranchLanterns(path, top, mid, deep, phase, alpha, lowRam)
        SwiftieEraBackdrop.MIDNIGHT_CLOCK ->
            drawMidnightClock(path, top, mid, deep, phase, alpha, eraMs, shapes, numerals)
        SwiftieEraBackdrop.TYPEWRITER_DESK ->
            drawTypewriterDesk(path, top, mid, deep, phase, alpha, eraMs, card)
        SwiftieEraBackdrop.THEATRE_STAGE -> drawTheatreStage(path, top, mid, deep, phase, alpha)
    }
}

// ─────────────────────── 共用笔法 ───────────────────────
// 渐变一律建在单位方框里（0,0..1,1），画的时候 withTransform 缩放到实际尺寸。
// 按 px 建的话，凡是尺寸每帧在变的地方（呼吸的光晕、成长的光锥）就是每帧一个原生 Shader。

private val UNIT_SIZE = Size(1f, 1f)
private val UNIT_CENTER = Offset(0.5f, 0.5f)

/**
 * 单位方框里的心形轮廓，全项目共用那一条（见 `SwiftieHeartPath`）。
 *
 * 建成顶层 `val` 而不是每次画的时候 `unitHeartPath()`：它只被读、从不被改，
 * 而 Lover 那张每帧都要画一次霓虹心。
 */
private val UNIT_HEART: Path by lazy(LazyThreadSafetyMode.NONE) { unitHeartPath() }

/** 暖光晕。灯泡、灯笼、窗内火光、台灯共用这一条。 */
private val GLOW_WARM: Brush = Brush.radialGradient(
    0.00f to LAMP_CORE.copy(alpha = 0.85f),
    0.30f to LAMP_WARM.copy(alpha = 0.45f),
    0.70f to LAMP_WARM.copy(alpha = 0.12f),
    1.00f to Color.Transparent,
    center = UNIT_CENTER,
    radius = 0.5f
)

/** 冷白光晕。追光锥的锥顶、霓虹心的外圈、星星的散射。 */
private val GLOW_WHITE: Brush = Brush.radialGradient(
    0.00f to Color.White.copy(alpha = 0.75f),
    0.45f to Color.White.copy(alpha = 0.22f),
    1.00f to Color.Transparent,
    center = UNIT_CENTER,
    radius = 0.5f
)

/** 把单位空间的渐变画成一个直径 [diameter] 的圆斑，圆心 [center]。 */
private fun DrawScope.drawUnitGlow(brush: Brush, center: Offset, diameter: Float, alpha: Float) {
    if (alpha <= 0.01f || diameter <= 0f) return
    withTransform({
        translate(center.x - diameter / 2f, center.y - diameter / 2f)
        scale(diameter, diameter, pivot = Offset.Zero)
    }) {
        drawRect(brush = brush, size = UNIT_SIZE, alpha = alpha.coerceAtMost(1f))
    }
}

/**
 * 一条上下都化开的横雾带。y / height 都是占屏高的比例。
 *
 * 切成 [FOG_SLICES] 条等高实色带、alpha 走正弦，而**不是**建一条 `verticalGradient` ——
 * 雾的颜色随专辑变，每帧新建一条渐变就是每帧一个原生 Shader。14 条实色矩形在
 * GPU 上便宜得多，而雾本来就没有需要被看清的边界。
 *
 * @param overscanX 左右各外扩多少屏宽。**在 `rotate {}` 里调用时必须给** ——
 *   带子只铺 `0..w`，绕屏心转十几度之后两个端头就转进画面里，
 *   屏幕上是一块斜着贴上去的半透明矩形（Midnights 第四轮截图里那块）
 */
private fun DrawScope.drawFogBand(
    y: Float,
    height: Float,
    color: Color,
    alpha: Float,
    overscanX: Float = 0f
) {
    if (alpha <= 0.01f) return
    val h = height * size.height
    val sliceH = h / FOG_SLICES
    val top = y * size.height - h / 2f
    val left = -overscanX * size.width
    val bandW = size.width * (1f + overscanX * 2f)
    for (i in 0 until FOG_SLICES) {
        // 首尾两条本来就该是 0，用 (i + 0.5) 取每条的中点避免整条雾都偏淡
        val profile = sin(PI.toFloat() * (i + 0.5f) / FOG_SLICES)
        drawRect(
            color = color,
            topLeft = Offset(left, top + i * sliceH),
            // +1 抹掉相邻两条之间那道亚像素缝
            size = Size(bandW, sliceH + 1f),
            alpha = alpha * profile
        )
    }
}

/**
 * 14 条。9 条时每条 alpha 之间差 0.11，压在深色天空上那几道台阶看得出来
 * （Midnights 第四轮截图里那块斜雾内部就是一条条横纹）。
 */
private const val FOG_SLICES = 14

/**
 * 追光锥：自 ([apexX], [apexY]) 向下张开到 [bottomY]，底边半宽 [halfWidth]（都按 px）。
 *
 * 用 [CONE_STEPS] 个逐层收窄的同心锥叠出亮度衰减，锥心最亮、边缘最淡。同样是为了
 * 避开每帧新建渐变；而且一圈套一圈画出来的边界本身就是柔的，比一条线性渐变更像光。
 */
private fun DrawScope.drawCone(
    path: Path,
    apexX: Float,
    apexY: Float,
    halfWidth: Float,
    bottomY: Float,
    color: Color,
    alpha: Float
) {
    if (alpha <= 0.01f) return
    for (i in CONE_STEPS downTo 1) {
        val shrink = i / CONE_STEPS.toFloat()
        path.rewind()
        path.moveTo(apexX, apexY)
        path.lineTo(apexX - halfWidth * shrink, bottomY)
        path.lineTo(apexX + halfWidth * shrink, bottomY)
        path.close()
        // 越里层越亮：每层加同样的量，叠出来接近 1/r 的衰减
        drawPath(path = path, color = color, alpha = alpha * 0.22f)
    }
    drawUnitGlow(GLOW_WHITE, Offset(apexX, apexY), halfWidth * 0.9f, alpha * 0.55f)
}

private const val CONE_STEPS = 4

/** 一颗灯泡：暖芯 + 灯丝 + 外圈光晕。[lit] 0f..1f 决定亮到什么程度。 */
private fun DrawScope.drawBulb(center: Offset, radius: Float, lit: Float, alpha: Float) {
    val a = alpha * (0.35f + 0.65f * lit)
    if (a <= 0.01f) return
    drawUnitGlow(GLOW_WARM, center, radius * 7f, a * 0.7f * lit)
    drawCircle(color = LAMP_WARM, radius = radius, center = center, alpha = a)
    drawCircle(color = LAMP_CORE, radius = radius * 0.55f, center = center, alpha = a)
    // 灯丝：一小段亮线。没有它，灯泡就是一个渐变圆点
    drawLine(
        color = Color.White,
        start = Offset(center.x - radius * 0.28f, center.y + radius * 0.1f),
        end = Offset(center.x + radius * 0.28f, center.y - radius * 0.1f),
        strokeWidth = radius * 0.16f,
        alpha = a * lit,
        cap = StrokeCap.Round
    )
}

/**
 * 一格透出暖光的窗：暖底 + 十字窗棂 + 外溢的光晕。
 *
 * 窗棂是「这是一扇窗」的全部证据 —— 少了它就只是墙上一块亮方块。
 */
private fun DrawScope.drawLitWindow(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    frame: Color,
    lit: Float,
    alpha: Float
) {
    if (alpha <= 0.01f) return
    drawUnitGlow(GLOW_WARM, Offset(left + width / 2f, top + height / 2f), width * 3.2f, alpha * 0.5f * lit)
    drawRect(
        color = LAMP_WARM,
        topLeft = Offset(left, top),
        size = Size(width, height),
        alpha = alpha * (0.55f + 0.45f * lit)
    )
    val bar = (width * 0.07f).coerceAtLeast(1f)
    drawLine(
        color = frame,
        start = Offset(left + width / 2f, top),
        end = Offset(left + width / 2f, top + height),
        strokeWidth = bar,
        alpha = alpha * 0.85f
    )
    drawLine(
        color = frame,
        start = Offset(left, top + height * 0.42f),
        end = Offset(left + width, top + height * 0.42f),
        strokeWidth = bar,
        alpha = alpha * 0.85f
    )
    drawRect(
        color = frame,
        topLeft = Offset(left, top),
        size = Size(width, height),
        alpha = alpha * 0.9f,
        style = Stroke(width = bar * 1.4f)
    )
}

/**
 * 一棵针叶树：[tiers] 层逐层收窄的枝盘 + 一段树干。
 *
 * 单个等腰三角形读作「圣诞树图标」；分层、每层下缘往外挑一点，才读作松/云杉。
 */
private fun DrawScope.drawConifer(
    path: Path,
    cx: Float,
    baseY: Float,
    height: Float,
    halfWidth: Float,
    color: Color,
    alpha: Float,
    tiers: Int = 4
) {
    if (alpha <= 0.01f) return
    val trunkW = halfWidth * 0.16f
    drawRect(
        color = color,
        topLeft = Offset(cx - trunkW / 2f, baseY - height * 0.10f),
        size = Size(trunkW, height * 0.10f),
        alpha = alpha
    )
    val crown = height * 0.92f
    val top = baseY - height
    for (i in 0 until tiers) {
        // 自上而下每层更宽、更长，层间重叠 40% 才不露出树干
        val f0 = i / tiers.toFloat()
        val f1 = (i + 1.4f) / tiers
        val tierTop = top + crown * f0 * 0.78f
        val tierBottom = top + crown * f1.coerceAtMost(1f)
        val w = halfWidth * (0.30f + 0.70f * ((i + 1f) / tiers))
        path.rewind()
        path.moveTo(cx, tierTop)
        path.lineTo(cx - w, tierBottom)
        // 下缘中间往上凹一点：枝盘不是一条平的底边
        path.lineTo(cx, tierBottom - crown * 0.045f)
        path.lineTo(cx + w, tierBottom)
        path.close()
        drawPath(path = path, color = color, alpha = alpha)
    }
}

/**
 * 一根树干加进 [path]：底端按 [flare] 放宽（树根的喇叭口），往上按 [taper] 收细。
 *
 * 少了底端那个喇叭口，画面上就是一根等宽的灰条 ——「树」读不出来（第一版如此）。
 */
private fun trunkQuad(
    path: Path,
    w: Float,
    h: Float,
    x: Float,
    halfWidth: Float,
    yBottom: Float,
    yTop: Float,
    lean: Float,
    taper: Float,
    flare: Float,
    leftStrip: Boolean = false
) {
    val cx = x * w
    val hb = halfWidth * (1f + flare) * w
    val ht = halfWidth * taper * w
    if (leftStrip) {
        // 左缘那条受光带：光从左上来，杆的左侧最亮。宽度取底端半宽的 0.5 倍（约整根的 20%）
        val sb = halfWidth * 0.5f * w
        path.moveTo(cx - hb, yBottom * h)
        path.lineTo(cx - hb + sb, yBottom * h)
        path.lineTo(cx + lean * w - ht + sb, yTop * h)
        path.lineTo(cx + lean * w - ht, yTop * h)
        path.close()
        return
    }
    path.moveTo(cx - hb, yBottom * h)
    path.lineTo(cx + hb, yBottom * h)
    path.lineTo(cx + lean * w + ht, yTop * h)
    path.lineTo(cx + lean * w - ht, yTop * h)
    path.close()
}

/**
 * 一组远景/中景的细树干，一次填完；给了 [rimColor] 再补一条左缘的受光带。
 *
 * [trunks] 是五元组 `x, 半宽w, 脚底y, 顶端y, 歪斜w`。同一层共用**一条 Path** ——
 * 填充按非零环绕规则求并集，两根交叠的树干不会像两次 `drawPath` 那样叠出更深的色块。
 *
 * 受光带的颜色**不能**用 [mid]：杆本身已经压到 deep 一档，比它浅一点点的那档再乘个
 * 0.15 只差 2/255，屏上什么都没有（离线复刻器量过）。用的是雾色 [top] 低透明度 ——
 * 雾里透进来的光打在杆的左侧，这个说法和画面对得上。
 */
private fun DrawScope.drawTrunkLayer(
    path: Path,
    trunks: FloatArray,
    color: Color,
    alpha: Float,
    taper: Float,
    flare: Float,
    rimColor: Color? = null,
    rimAlpha: Float = 0f
) {
    if (alpha <= 0.01f) return
    path.rewind()
    for (i in 0 until trunks.size / 5) {
        trunkQuad(
            path = path,
            w = size.width,
            h = size.height,
            x = trunks[i * 5],
            halfWidth = trunks[i * 5 + 1],
            yBottom = trunks[i * 5 + 2],
            yTop = trunks[i * 5 + 3],
            lean = trunks[i * 5 + 4],
            taper = taper,
            flare = flare
        )
    }
    drawPath(path = path, color = color, alpha = alpha)
    if (rimColor != null && rimAlpha > 0.01f) {
        path.rewind()
        for (i in 0 until trunks.size / 5) {
            trunkQuad(
                path = path,
                w = size.width,
                h = size.height,
                x = trunks[i * 5],
                halfWidth = trunks[i * 5 + 1],
                yBottom = trunks[i * 5 + 2],
                yTop = trunks[i * 5 + 3],
                lean = trunks[i * 5 + 4],
                taper = taper,
                flare = flare,
                leftStrip = true
            )
        }
        drawPath(path = path, color = rimColor, alpha = alpha * rimAlpha)
    }
}

/**
 * 一坨叶：**一条闭合路径**，由调用方一次填完。
 *
 * 两件事决定它读作「叶」还是读作「花」，都是前两版在真机上翻车换来的：
 * ① **不能是一圈等幅等距的圆凸**。花簇（绣球、丁香）的轮廓正是「半径相同、间距相同的
 *    一圈小圆」—— 第二版给了 30 段等幅小弧，屏幕上就是一束垂下来的花（需求方这轮原话）。
 *    这里把振幅**调制**起来（`0.030 + 0.060·|sin(1.7θ)|`：有的地方几乎光滑、有的地方一道
 *    深缺口），相位也抖（`15θ + 0.6·sin(2.7θ)`，凸起间距不再均匀），底形再加 2 次/3 次
 *    谐波把它拉歪 —— 整圈没有一处是正圆。
 * ② **下缘不能收成一个尖**。上宽下尖、尖端再挂一小坨，那就是一串垂下来的花。
 *    叶团是**横着铺开**的（[ry] 只给 0.61 倍垂深、[rx] 放到 1.20 倍枝展）。
 * 另外留 4 个**尖叶**（窄高斯凸起）挑出轮廓，叶子才有尖角。
 *
 * [seed] 决定两件事的相位，同一挂里的两坨要传不同的 seed，否则两坨一模一样。
 */
private fun leafBlob(
    path: Path,
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    seed: Int,
    tips: Int = 4
) {
    val rnd = Random(seed * 1000 + 7)
    val ph0 = rnd.nextFloat() * TAU
    val ph1 = rnd.nextFloat() * TAU
    val ph2 = rnd.nextFloat() * TAU
    val tipTh = FloatArray(tips) { rnd.nextFloat() * TAU }
    val tipAmp = FloatArray(tips) { 0.09f + rnd.nextFloat() * 0.10f }
    val steps = 300
    for (i in 0 until steps) {
        val th = TAU * i / steps
        val base = 1f + 0.16f * cos(2f * th + ph0) + 0.11f * cos(3f * th + ph1)
        val taper = 1f - 0.15f * (1f - cos(th)) / 2f
        val amp = 0.030f + 0.060f * abs(sin(1.7f * th + ph2))
        val rip = 1f + amp * sin(15f * th + 0.6f * sin(2.7f * th + ph2))
        var r = base * taper * rip
        for (k in 0 until tips) {
            val d0 = (th - tipTh[k]) % TAU
            val d1 = (th - tipTh[k] + TAU) % TAU
            r += tipAmp[k] * exp(-(d0 * d0) / 0.01125f)
            r += tipAmp[k] * exp(-(d1 * d1) / 0.01125f)
        }
        val px = cx + rx * r * sin(th)
        val py = cy - ry * r * cos(th)
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}

/**
 * 顶上那几丛叶（[FOLK_SPRAYS]）：一丛 = 两坨叶 + 一条把叶连回枝上的细梢。
 *
 * 三条要点：
 * ① **挂点全部钉在树干上**（见 [FOLK_SPRAYS] 的注释）：叶子长在树上。上一版挂点差不多
 *    等距地排开，屏幕上就是一排从天花板垂下来的花簇；
 * ② 叶坨**从画面顶上挂下来**（中心算在 y < 0，只有下半个露出来），不是天上浮着个椭圆；
 * ③ 第二坨要往**侧后方**错（横向 ±0.55 枝展、纵向不齐），**不在正下方再挂一小坨** ——
 *    正下方挂小坨加上下缘收尖，那就是「一簇垂下来的花」。
 *
 * 远一档的三丛用 [mid] 低透明度、还缩了 0.82/0.86 —— 顶上那排叶子要有厚度。
 * 同一批的叶坨并进一条 Path 一次填完（NonZero 取并集），细梢再合并成一条路径一次描边。
 */
private fun DrawScope.drawFolkloreSprays(path: Path, deep: Color, mid: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    val w = size.width
    val h = size.height
    for (pass in 0..1) {
        val far = pass == 0
        path.rewind()
        for (i in 0 until FOLK_SPRAYS.size / 5) {
            if ((i % 2 == 1) != far) continue
            val x0 = FOLK_SPRAYS[i * 5] * w
            val lean = FOLK_SPRAYS[i * 5 + 3]
            val dep = FOLK_SPRAYS[i * 5 + 1] * (if (far) SPR_FAR_DEPTH else 1f)
            val span = FOLK_SPRAYS[i * 5 + 2] * (if (far) SPR_FAR_SPAN else 1f) * w
            val rnd = Random((x0.toInt() % 9973) * 977 + 31)
            for (k in 0 until 2) {
                val ox = rnd.nextInt(-550, 551) / 1000f * span
                val oy = (0.15f + rnd.nextFloat() * 0.47f) * dep * (if (k == 0) 1f else 1.35f)
                val rr = if (k == 0) span else span * (0.42f + rnd.nextFloat() * 0.20f)
                val dd = if (k == 0) dep else dep * (0.55f + rnd.nextFloat() * 0.25f)
                leafBlob(
                    path = path,
                    cx = x0 + ox + lean * span * 0.4f,
                    cy = dd * 0.45f * h + oy * h,
                    rx = rr * 1.20f,
                    ry = dd * 0.61f * h,
                    seed = (x0 * 11.3f).toInt() + k * 17 + i * 101
                )
            }
        }
        drawPath(
            path = path,
            color = if (far) mid else deep,
            alpha = alpha * (if (far) SPR_ALPHA_FAR else SPR_ALPHA_NEAR)
        )
    }
    // 细梢：从画面顶边斜斜地伸进叶坨里。少了它，叶是「挂在空中」的
    path.rewind()
    for (i in 0 until FOLK_SPRAYS.size / 5) {
        val x0 = FOLK_SPRAYS[i * 5] * size.width
        val lean = FOLK_SPRAYS[i * 5 + 3]
        val dep = FOLK_SPRAYS[i * 5 + 1] * size.height
        val span = FOLK_SPRAYS[i * 5 + 2] * size.width
        path.moveTo(x0, -size.height * 0.01f)
        path.lineTo(x0 + lean * span * 0.5f, dep * 0.55f)
        path.lineTo(x0 + lean * span * 0.6f + span * 0.35f, dep * 1.05f)
    }
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * 0.85f,
        style = Stroke(width = size.width * 0.0022f, cap = StrokeCap.Round)
    )
}

/**
 * 一根细梢：从 `(x0, y0)` 到 `(x1, y1)` 的楔形（根粗梢细）。
 *
 * 梢要**细**（根半宽 0.0017–0.0026w，即 2.4–3.7px，和参考照片里那些斜斜的细梢一个量级），
 * 但根必须长在杆上 —— 第一版给了 0.13w 长、根半宽 0.01w 的粗杆，屏幕上是一堆悬在空里的斜棍。
 */
private fun branchWedge(
    path: Path,
    w: Float,
    h: Float,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    w0: Float,
    w1: Float
) {
    val ax = x0 * w
    val ay = y0 * h
    val bx = x1 * w
    val by = y1 * h
    val dx = bx - ax
    val dy = by - ay
    val ln = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val nx = -dy / ln
    val ny = dx / ln
    path.moveTo(ax + nx * w0 * w, ay + ny * w0 * w)
    path.lineTo(ax - nx * w0 * w, ay - ny * w0 * w)
    path.lineTo(bx - nx * w1 * w, by - ny * w1 * w)
    path.lineTo(bx + nx * w1 * w, by + ny * w1 * w)
    path.close()
}

/**
 * 一串圆团**并成一条轮廓**，喂给 [Path] 后由调用方一次填完。
 *
 * 这是本文件的硬规矩：树冠、云、苔藓这类「一坨」的东西**绝不许画成一组半透明圆**。
 * 那样每个圆自己的边都看得见，交叠处还会叠深两次，屏幕上读出来是一串肥皂泡 ——
 * 第一轮截图里首专的树冠和 Lover 的云就是这么废掉的。并进一条 `Path`
 * 之后（`Path.addOval` 默认 NonZero，天然取并集）内部的边全部消失，
 * 一次 `drawPath` 只有外轮廓，那才是叶簇与云的样子。
 *
 * @param spec 每三个数一团：`dx`、`dy`（相对 [scale] 的偏移）、半径系数
 * @param yScale 纵向压扁系数。云要**宽而扁**（0.5 上下）：一团团正圆并起来是灌木丛，
 *   第二轮截图里 Lover 那三排就读成了粉色的树。树冠给 1f
 */
private fun blobInto(
    path: Path,
    spec: FloatArray,
    cx: Float,
    cy: Float,
    scale: Float,
    yScale: Float = 1f
) {
    path.rewind()
    for (i in 0 until spec.size / 3) {
        val r = scale * spec[i * 3 + 2]
        val x = cx + spec[i * 3] * scale
        val y = cy + spec[i * 3 + 1] * scale * yScale
        path.addOval(Rect(x - r, y - r * yScale, x + r, y + r * yScale))
    }
}

/**
 * 门廊那棵阔叶树的树冠：十二团圆的 (dx, dy, 半径系数)，dx/dy 是相对冠幅的偏移。
 *
 * 摊成顶层 `FloatArray` 而不是在 draw 里 `listOf(Offset(...) to ...)` ——
 * 那样每帧要新建一个 List 与十二个 Offset，而这些数是常量。
 * 本文件里所有「一小组固定位置」都按这个规矩写。
 *
 * 半径**故意差得很开**（0.20–0.72），而且小的那几团全压在轮廓边上：
 * 五六团差不多大的圆并起来是一朵云，边上再啃出几个小缺口才有叶簇那种碎边。
 * 这些团必须写在同一个 [FloatArray] 里、由 [blobInto] 并成一条路径一次填完 ——
 * 分两次画同色同 alpha 的团，交叠处会叠深一档，边就又露出来了。
 *
 * 排布是**三簇 + 顶芽 + 底碎**（中簇 / 左簇 / 右簇），而且**竖向比横向长**
 * （竖 2.12r × 横 2.06r）。第三轮截图里这团被读成一朵云，两个原因：
 * 上一版最大的两团压在下缘（底边因此是一条光滑的大弧）、小的碎团全在顶上，
 * 这正是积云的形状 —— 平底、拱顶。阔叶树反过来：顶上是几簇分开的叶团（有凹口），
 * 下缘则被枝叶啃得零碎。而且上一版横 2.32r × 竖 1.92r 是扁的，云才是扁的。
 */
private val PORCH_CROWN = floatArrayOf(
    // 中簇：主体，从冠心往上顶
    0.00f, -0.32f, 0.64f,
    0.00f, 0.14f, 0.70f,
    // 左簇
    -0.52f, -0.04f, 0.52f,
    -0.66f, 0.34f, 0.36f,
    -0.40f, -0.46f, 0.34f,
    // 右簇
    0.54f, -0.08f, 0.50f,
    0.70f, 0.28f, 0.34f,
    0.42f, -0.50f, 0.32f,
    // 顶芽：三团错开，簇间留出凹口
    -0.18f, -0.82f, 0.28f,
    0.22f, -0.74f, 0.26f,
    0.02f, -1.00f, 0.20f,
    // 底碎：下缘的零碎叶团
    -0.30f, 0.58f, 0.28f,
    0.28f, 0.54f, 0.26f,
    -0.02f, 0.70f, 0.22f
)

/**
 * 树冠里侧的暗部叶团。
 *
 * 单色平填的一团轮廓再准也只是一个剪影贴纸 —— 夜里的树冠是有体积的，
 * 背光那半（这一张的光源是右侧那扇窗）要比迎光那半暗一档。这几团**偏左下**，
 * 在 [PORCH_CROWN] 内侧再压一层，冠就有了厚度。
 * 半径都小于 0.5，保证整层不会碰到外轮廓、把边啃掉。
 */
private val PORCH_CROWN_CORE = floatArrayOf(
    -0.30f, 0.10f, 0.42f,
    -0.10f, 0.34f, 0.34f,
    -0.46f, -0.14f, 0.26f,
    0.06f, -0.06f, 0.30f
)

/** 门廊两根立柱的横向位置。 */
private val PORCH_POSTS = floatArrayOf(0.20f, 0.86f)

/**
 * 卡片盖住的横带上缘。
 *
 * 卡片高度按曲目数派生，所以顶边每张不同：首专 11 首在 0.56h、Lover 18 首在 0.41h。
 * **要被看见的大主体必须落在这条线以上。** 第一轮截图里城堡、王座、钢琴、打字机、浴缸
 * 全钉在 0.62–0.80h，结果 12 张里有 7 张的主体整个藏在卡片背后，屏幕上只剩一片渐变。
 *
 * 0.38f 留了 0.03 余量给 Lover 那张（12 张里除 TTPD 外最长的一张）。
 * **TTPD 不在这条线管辖内** —— 31 首（The Anthology 版）把它的顶边压到 0.20h，
 * 那一张的打字机坐到屏幕底下（`drawTypewriterDesk`），与卡片上缘无关。
 */
private const val HERO_BOTTOM = 0.38f

// ─────────────────────── 1 · Taylor Swift ───────────────────────

/**
 * 门廊结构的墨色。
 *
 * **不用 `deep`（#2E6B5E）**：那一档正是这张舞台底部的背景色，画在下半屏等于没画；
 * 上半屏画出来也只是「淡绿上的中绿」。第一轮截图整屏落在同一个绿里，读作一张单色洗底，
 * 屋檐、立柱、栏杆、甲板全都分不出层。夜里的门廊本来就是近黑的剪影 ——
 * 这一档比 `deep` 再暗三档，配合更高的不透明度，结构才立得起来，
 * 而窗里那点暖光也才有东西可以对比。
 */
private val PORCH_INK = Color(0xFF16342C)

/**
 * 夜色门廊：屋檐 + 立柱 + 栏杆 + 地板，右侧一格窗透出屋内暖光，左侧一棵阔叶树剪影。
 *
 * 首专是田纳西的乡村门廊（*Our Song* / *Tim McGraw* 的画面），所以树是阔叶而不是针叶
 * —— 针叶留给 folklore，两张不能撞。窗光按 [phase] 极轻微地明暗，读作屋里有人在动。
 */
private fun DrawScope.drawFireflyPorch(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float
) {
    val w = size.width
    val h = size.height
    // 结构用 PORCH_INK 且给到 0.72：见那个常量的注释，这是这一张能读出层次的前提
    val ink = alpha * 0.72f
    val silhouette = alpha * SILHOUETTE_ALPHA
    // 门廊整体压在 HERO_BOTTOM 以上：屋檐 → 栏杆 → 地板 → 台阶一路收在上缘那 38%，
    // 卡片顶边以下只留台阶的最后一级
    val floorY = h * (HERO_BOTTOM - 0.02f)

    // 屋子的立面。**必须先有墙**：第一轮那扇窗悬在渐变里，读出来是块芥末色的板子。
    // 墙只到 0.34 屏宽的右侧，左边留给树。
    // 这一块仍用 deep：它是「墙」这个大面，压太黑会把窗的暖光吃掉
    drawRect(
        color = deep,
        topLeft = Offset(w * 0.34f, h * 0.06f),
        size = Size(w * 0.66f, floorY - h * 0.06f),
        alpha = silhouette * 0.34f
    )

    // 阔叶树：树干 + 一条并起来的树冠轮廓 + 两根斜枝。
    // 冠心放在 0.17w 而不是更靠边 —— 0.10w 时半个冠幅在屏幕外，读作贴在边上的一团
    val treeX = w * 0.17f
    drawRect(
        color = PORCH_INK,
        topLeft = Offset(treeX - w * 0.011f, h * 0.15f),
        size = Size(w * 0.022f, floorY - h * 0.15f),
        alpha = ink
    )
    // **不画斜枝**：树干直接顶进冠里。前两轮各试过一版斜枝，两次都是同一个结果 ——
    // 冠的下缘是十几个圆团并出来的，边界本身不规则，斜枝的外段总有一截露在冠外，
    // 屏幕上读作两根钉在树上的直棍（第一轮像剪刀、第二轮像钟表指针）。
    // 阔叶树在夜里本来就只看得见「一团冠 + 一根干」，枝是多画的。
    // 冠画两层：外轮廓 + 偏左下的暗部叶团（见 [PORCH_CROWN_CORE]）
    blobInto(path, PORCH_CROWN, treeX, h * 0.135f, w * 0.145f)
    drawPath(path = path, color = PORCH_INK, alpha = ink)
    blobInto(path, PORCH_CROWN_CORE, treeX, h * 0.135f, w * 0.145f)
    drawPath(path = path, color = Color.Black, alpha = ink * 0.30f)

    // 屋檐：一根横梁压住上缘，两根立柱落到地板
    drawRect(color = PORCH_INK, size = Size(w, h * 0.055f), alpha = ink)
    val postW = w * 0.032f
    for (i in PORCH_POSTS.indices) {
        drawRect(
            color = PORCH_INK,
            topLeft = Offset(w * PORCH_POSTS[i] - postW / 2f, h * 0.055f),
            size = Size(postW, floorY - h * 0.055f),
            alpha = ink
        )
    }

    // 栏杆：上下两根横档 + 一排立柱。立柱间距 0.038 屏宽，密到读作栏杆而不是围栏
    val railTop = h * 0.24f
    val railBottom = h * 0.335f
    val barW = (w * 0.007f).coerceAtLeast(1f)
    var bx = w * 0.20f + postW
    while (bx < w * 0.86f - postW) {
        drawRect(
            color = PORCH_INK,
            topLeft = Offset(bx, railTop),
            size = Size(barW, railBottom - railTop),
            alpha = ink * 0.85f
        )
        bx += w * 0.038f
    }
    for (i in 0..1) {
        drawRect(
            color = PORCH_INK,
            topLeft = Offset(w * 0.20f, if (i == 0) railTop else railBottom),
            size = Size(w * 0.66f, h * 0.014f),
            alpha = ink
        )
    }

    // 地板：先铺一整块甲板面，再压横向的木板缝。
    // **面必须有** —— 只画几条缝的话地板在渐变里不成立，
    // 屏幕上读出来是三级台阶悬在半空（第一轮就是这样）
    drawRect(
        color = PORCH_INK,
        topLeft = Offset(0f, floorY),
        size = Size(w, h * 0.52f - floorY),
        alpha = ink * 0.34f
    )
    var boardY = floorY
    var boardGap = h * 0.014f
    while (boardY < h * 0.52f) {
        drawLine(
            color = PORCH_INK,
            start = Offset(0f, boardY),
            end = Offset(w, boardY),
            strokeWidth = 1f,
            alpha = ink * 0.6f
        )
        boardY += boardGap
        boardGap *= 1.18f
    }

    // 门廊台阶：三级，自上而下收窄。没有台阶的门廊看着像悬空的平台
    for (s in 0 until 3) {
        val inset = w * (0.30f + s * 0.045f)
        val y0 = h * (0.52f + s * 0.035f)
        val y1 = y0 + h * 0.028f
        path.rewind()
        path.moveTo(inset, y0)
        path.lineTo(w - inset, y0)
        path.lineTo(w - inset - w * 0.02f, y1)
        path.lineTo(inset + w * 0.02f, y1)
        path.close()
        drawPath(path = path, color = PORCH_INK, alpha = ink * (0.55f - s * 0.12f))
    }

    // 屋内暖光。sin(phase*TAU) 是 phase 的一倍频，绕回 0f 时连续
    val flicker = 0.82f + 0.18f * sin(phase * TAU)
    val winLeft = w * 0.62f
    val winTop = h * 0.11f
    val winW = w * 0.20f
    val winH = h * 0.15f
    // 窗外的光晕先铺：暖光要洒到墙上，否则窗只是一块贴上去的亮片。
    // 0.55 而不是 0.30：结构压到近黑之后，这道光是整张图唯一的亮部，也是「屋里有人」的全部信息
    drawUnitGlow(
        brush = GLOW_WARM,
        center = Offset(winLeft + winW / 2f, winTop + winH / 2f),
        diameter = winW * 3.8f,
        alpha = alpha * 0.55f * flicker
    )
    drawLitWindow(
        left = winLeft,
        top = winTop,
        width = winW,
        height = winH,
        frame = PORCH_INK,
        lit = flicker,
        alpha = alpha * 0.85f
    )
    // 光落在甲板上的那一摊。窗光只糊在墙上、地板却是全黑的话，
    // 这盏灯就成了贴在墙上的贴纸
    drawUnitGlow(
        brush = GLOW_WARM,
        center = Offset(winLeft + winW * 0.42f, floorY + h * 0.035f),
        diameter = winW * 4.4f,
        alpha = alpha * 0.24f * flicker
    )
    // 门缝漏出的一道光带，落在地板上
    drawFogBand(HERO_BOTTOM + 0.02f, 0.06f, mid, alpha * DISTANT_ALPHA)
    drawFogBand(0.30f, 0.30f, top, alpha * DISTANT_ALPHA * 0.6f)
    // 甲板上那只装萤火虫的玻璃罐。0.36h–0.52h 这条甲板原先是整张图唯一的空带
    // （只有几条板缝），而这一张缺的正是光源
    drawFireflyJar(path, w * 0.635f, h * 0.505f, w * 0.088f, h * 0.066f, phase, alpha)
}

/**
 * 甲板上一只装着萤火虫的玻璃罐：罐身 + 颈 + 盖 + 罐里三点冷光 + 洒在板上的一摊。
 *
 * 为什么是玻璃罐：门廊那一带（[HERO_BOTTOM] 到卡片顶边）原先只有几条板缝，是这张图唯一
 * 的空带；而整张图的光只有右上那扇窗一处，甲板左半永远是死黑。罐子同时补上主体与光源，
 * 又跟满屏飘的萤火虫（`drawFireflies`）是同一件事 —— 抓了几只装进罐子，*Our Song* 的画面。
 *
 * 三点光的闪法**各自错相位**（0 / 0.37 / 0.71）：三点同步亮灭读作一个三孔灯泡。
 * 罐身填 [LAMP_CORE] 但只给 0.12 —— 玻璃是透的，填厚了就是一块奶白塑料。
 */
private fun DrawScope.drawFireflyJar(
    path: Path,
    left: Float,
    baseY: Float,
    jw: Float,
    jh: Float,
    phase: Float,
    alpha: Float
) {
    val ink = alpha * 0.72f
    val cx = left + jw / 2f
    // 罐里的总光先铺：光要洒到罐外的板上，罐身才不是一块贴纸
    val breath = 0.72f + 0.28f * sin(phase * 2f * TAU)
    drawUnitGlow(
        brush = GLOW_WARM,
        center = Offset(cx, baseY - jh * 0.45f),
        diameter = jw * 3.4f,
        alpha = alpha * 0.30f * breath
    )
    // 罐身：肩部圆角、下缘方 —— 梅森罐就是这个形
    val body = Rect(left, baseY - jh * 0.82f, left + jw, baseY)
    path.rewind()
    path.addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
            body,
            topLeft = CornerRadius(jw * 0.26f),
            topRight = CornerRadius(jw * 0.26f),
            bottomLeft = CornerRadius(jw * 0.10f),
            bottomRight = CornerRadius(jw * 0.10f)
        )
    )
    drawPath(path = path, color = LAMP_CORE, alpha = alpha * 0.12f)
    drawPath(
        path = path,
        color = PORCH_INK,
        alpha = ink * 0.9f,
        style = Stroke(width = (jw * 0.055f).coerceAtLeast(1f))
    )
    // 颈与盖：盖比颈宽一圈，才读作旋上去的
    drawRect(
        color = PORCH_INK,
        topLeft = Offset(cx - jw * 0.28f, baseY - jh * 0.92f),
        size = Size(jw * 0.56f, jh * 0.12f),
        alpha = ink * 0.8f
    )
    drawRect(
        color = PORCH_INK,
        topLeft = Offset(cx - jw * 0.34f, baseY - jh),
        size = Size(jw * 0.68f, jh * 0.11f),
        alpha = ink
    )
    // 罐里三只。相位各错开，位置也不对称
    val bugs = floatArrayOf(
        0.34f, 0.62f, 0.00f,
        0.62f, 0.40f, 0.37f,
        0.48f, 0.76f, 0.71f
    )
    for (i in 0 until 3) {
        val bx = left + jw * bugs[i * 3]
        val by = baseY - jh * 0.82f * bugs[i * 3 + 1]
        val lit = 0.45f + 0.55f * abs(sin((phase + bugs[i * 3 + 2]) * 2f * TAU))
        drawUnitGlow(GLOW_WARM, Offset(bx, by), jw * 0.62f, alpha * 0.5f * lit)
        drawCircle(color = LAMP_CORE, radius = jw * 0.055f, center = Offset(bx, by), alpha = alpha * lit)
    }
    // 玻璃左侧那道竖高光：一笔就够，玻璃靠它读出弧面
    drawLine(
        color = LAMP_CORE,
        start = Offset(left + jw * 0.20f, baseY - jh * 0.66f),
        end = Offset(left + jw * 0.20f, baseY - jh * 0.14f),
        strokeWidth = (jw * 0.06f).coerceAtLeast(1f),
        cap = StrokeCap.Round,
        alpha = alpha * 0.28f
    )
    // 板上那一摊：压扁的一团，罐子因此站在地板上而不是浮着
    drawUnitGlow(
        brush = GLOW_WARM,
        center = Offset(cx, baseY + jh * 0.10f),
        diameter = jw * 2.6f,
        alpha = alpha * 0.22f * breath
    )
}

// ─────────────────────── 2 · Fearless ───────────────────────

/**
 * 金色城堡：一圈极慢自转的光芒 + 远景城堡剪影 + 一条通向城门的路。
 *
 * *Love Story* 的整支 MV 就是城堡与舞会。光芒用 12 道锥形射线，整段只转过 30°——
 * 而 12 道射线本身每 30° 重复一次，所以 [phase] 从 1f 绕回 0f 那一帧接得上，看不出跳。
 */
private fun DrawScope.drawGoldenCastle(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float
) {
    val w = size.width
    val h = size.height
    // 地面线与城堡整体抬进 HERO_BOTTOM：原来 groundY 在 0.74h，
    // 三座塔加尖顶落在 0.38–0.74h，卡片一盖就只剩塔尖
    val groundY = h * HERO_BOTTOM
    val center = Offset(w * 0.5f, h * 0.24f)

    // 自转光芒。12 道 = 每 30° 一道，转 30° 正好回到自己
    rotate(degrees = phase * (360f / RAY_COUNT), pivot = center) {
        val reach = hypot(w, h) * 0.62f
        for (i in 0 until RAY_COUNT) {
            val a = i / RAY_COUNT.toFloat() * TAU
            val spread = TAU / RAY_COUNT * 0.30f
            path.rewind()
            path.moveTo(center.x, center.y)
            path.lineTo(center.x + cos(a - spread) * reach, center.y + sin(a - spread) * reach)
            path.lineTo(center.x + cos(a + spread) * reach, center.y + sin(a + spread) * reach)
            path.close()
            drawPath(path = path, color = top, alpha = alpha * 0.10f)
        }
    }
    drawUnitGlow(GLOW_WARM, center, w * 0.9f, alpha * 0.30f)

    // 通向城门的路：底边半屏宽，收到城门那点。梯形而不是三角形 —— 路有宽度
    path.rewind()
    path.moveTo(w * 0.20f, h)
    path.lineTo(w * 0.47f, groundY)
    path.lineTo(w * 0.53f, groundY)
    path.lineTo(w * 0.80f, h)
    path.close()
    drawPath(path = path, color = top, alpha = alpha * 0.16f)

    // 城堡：中央主楼 + 左右两座塔 + 三个尖顶 + 旗。都用末档深色，读作逆光剪影
    val silhouette = alpha * SILHOUETTE_ALPHA

    // 石头的不透明度**不能**用 [SILHOUETTE_ALPHA]（0.52）。
    // 这一张背后有 12 道自转射线，0.42–0.52 的墙体等于半透明：第三轮截图里射线的亮带
    // 从主楼身上直穿过去，主楼中间因此有一道竖亮缝，读作「一块贴着渐变的玻璃板」。
    // 石墙是实心的，挡光才对；0.88 之后射线只在城堡轮廓外亮着，城堡本身立起来了
    val stone = (alpha * 0.88f).coerceAtMost(1f)

    // 山丘：城堡得站在什么上面。第二轮三座塔的下缘停在 groundY，
    // 底下直接是渐变，屏幕上读作三支悬空的铅笔。
    // 0.42 而不是 0.55：山要比城墙浅一档，两者同值时墙的下缘就融进山里了
    path.rewind()
    path.moveTo(0f, groundY + h * 0.03f)
    path.cubicTo(
        w * 0.28f, groundY - h * 0.012f,
        w * 0.72f, groundY - h * 0.012f,
        w, groundY + h * 0.03f
    )
    path.lineTo(w, h)
    path.lineTo(0f, h)
    path.close()
    drawPath(path = path, color = deep, alpha = silhouette * 0.42f)

    // 城墙：把三座塔连起来，墙头一排城齿。
    // 少了这道墙，三座塔各自孤立，怎么加尖顶都读不出「一座城堡」。
    // 墙比塔浅一档（0.82 对 1.0）：墙在前、塔在后，同值时整座城堡是一块平板
    val wallTop = groundY - h * 0.072f
    drawRect(
        color = deep,
        topLeft = Offset(w * 0.255f, wallTop),
        size = Size(w * 0.49f, groundY - wallTop),
        alpha = stone * 0.82f
    )
    val crenel = w * 0.022f
    var cx0 = w * 0.255f
    while (cx0 < w * 0.745f) {
        drawRect(
            color = deep,
            topLeft = Offset(cx0, wallTop - crenel * 0.8f),
            size = Size(crenel, crenel * 0.8f),
            alpha = stone * 0.82f
        )
        cx0 += crenel * 2f
    }

    // 塔（x 中心，塔宽，塔高）。左右两座刻意不等高、不等宽 ——
    // 三座一样的塔并排是一排烟囱。塔高按 groundY 上移后重算过，主楼加尖顶收在 0.07h
    val towers = floatArrayOf(
        0.315f, 0.082f, 0.132f,
        0.50f, 0.135f, 0.198f,
        0.685f, 0.070f, 0.156f
    )
    for (i in 0 until 3) {
        val cx = w * towers[i * 3]
        val tw = w * towers[i * 3 + 1]
        val th = h * towers[i * 3 + 2]
        val towerTop = groundY - th
        // 中间那座是主楼（带尖顶），两侧是垛口塔。**同一座塔不能既有城齿又有尖顶** ——
        // 尖顶压在城齿上时，齿与齿之间的空档露出背景，屏幕上是每座塔顶两枚发亮的小方块，
        // 读作「窗」；而真实的城堡里那两样本来就是两种收顶方式。
        // 分开之后侧影也有了变化：一座尖顶主楼 + 两座垛口塔
        val keep = i == 1
        drawRect(
            color = deep,
            topLeft = Offset(cx - tw / 2f, towerTop),
            size = Size(tw, th),
            alpha = stone
        )
        val merlon = tw / 5f
        // 旗杆的落点：主楼在尖顶尖上，垛口塔在齿顶
        val flagY: Float
        if (keep) {
            val spire = th * 0.42f
            flagY = towerTop - spire
            path.rewind()
            path.moveTo(cx, flagY)
            path.lineTo(cx - tw * 0.62f, towerTop)
            path.lineTo(cx + tw * 0.62f, towerTop)
            path.close()
            // 尖顶比塔身再深一档：屋面是背光的那一侧，同一个值会把整座城堡压成一张剪纸。
            // 塔身已经到 0.88，再靠 alpha 加深没有余量了，所以压一层黑
            drawPath(path = path, color = deep, alpha = stone)
            drawPath(path = path, color = Color.Black, alpha = alpha * 0.20f)
        } else {
            for (m in 0 until 3) {
                drawRect(
                    color = deep,
                    topLeft = Offset(cx - tw / 2f + m * merlon * 2f, towerTop - merlon * 0.9f),
                    size = Size(merlon, merlon * 0.9f),
                    alpha = stone
                )
            }
            flagY = towerTop - merlon * 0.9f - th * 0.10f
            // 垛口塔的旗要有杆，否则旗浮在齿顶上方一截
            drawLine(
                color = deep,
                start = Offset(cx, towerTop - merlon * 0.9f),
                end = Offset(cx, flagY),
                strokeWidth = w * 0.004f,
                alpha = stone
            )
        }
        // 旗：随 phase 摆一下（一倍频，绕回时连续）
        val wave = sin(phase * TAU + i) * tw * 0.10f
        path.rewind()
        path.moveTo(cx, flagY)
        path.lineTo(cx + tw * 0.55f, flagY + tw * 0.14f + wave)
        path.lineTo(cx, flagY + tw * 0.30f)
        path.close()
        drawPath(path = path, color = mid, alpha = alpha * PROP_ALPHA * 1.6f)
    }

    // 城门：一个**填实**的圆拱门洞 + 拱石 + 吊闸格 + 门内一盏暖光。
    //
    // 上一版是一段 `drawArc` 加粗的线（宽 0.048w ≈ 69px、线宽 15px），第三轮截图里
    // 那道细弧压在同色的城墙上，读出来是墙面上的一道污渍 —— 门洞的关键是**洞比墙暗**，
    // 一条描边给不出这个。现在门洞按 0.075w 填 [PORCH_INK] 那一档的近黑，
    // 洞口的暖光从底下透出来，才读作「有人在等门」
    val gateW = w * 0.075f
    val gateH = h * 0.050f
    val gateLeft = w * 0.5f - gateW / 2f
    val gateBase = groundY
    val gateSpring = gateBase - gateH * 0.52f
    path.rewind()
    path.moveTo(gateLeft, gateBase)
    path.lineTo(gateLeft, gateSpring)
    // 半圆拱：拱心在 springer 那条线上，所以拱高正好是半径
    path.arcTo(
        rect = Rect(gateLeft, gateSpring - gateW / 2f, gateLeft + gateW, gateSpring + gateW / 2f),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        forceMoveTo = false
    )
    path.lineTo(gateLeft + gateW, gateBase)
    path.close()
    // 门洞先透一层暖光（门内的火把），再把门板压上去，光只从缝里透出来
    drawUnitGlow(GLOW_WARM, Offset(w * 0.5f, gateSpring), gateW * 3.2f, alpha * 0.50f)
    drawPath(path = path, color = Color.Black, alpha = alpha * 0.52f)
    // 吊闸：三横四竖。少了这道格子门洞就是一个黑洞
    for (g in 1..3) {
        val gy = gateSpring + (gateBase - gateSpring) * g / 4f
        drawLine(
            color = deep,
            start = Offset(gateLeft, gy),
            end = Offset(gateLeft + gateW, gy),
            strokeWidth = (gateW * 0.045f).coerceAtLeast(1f),
            alpha = alpha * 0.42f
        )
    }
    for (g in 1..3) {
        val gx = gateLeft + gateW * g / 4f
        drawLine(
            color = deep,
            start = Offset(gx, gateSpring - gateW * 0.30f),
            end = Offset(gx, gateBase),
            strokeWidth = (gateW * 0.045f).coerceAtLeast(1f),
            alpha = alpha * 0.42f
        )
    }
    // 拱石：沿拱线排 7 块楔形石，城门因此是砌出来的而不是挖出来的
    for (v in 0..6) {
        val a = PI.toFloat() + v / 6f * PI.toFloat()
        val rr = gateW * 0.5f
        val vx = w * 0.5f + cos(a) * rr * 1.16f
        val vy = gateSpring + sin(a) * rr * 1.16f
        drawCircle(
            color = deep,
            radius = gateW * 0.085f,
            center = Offset(vx, vy),
            alpha = stone * 0.75f
        )
    }

    // 金雾：城堡脚下一层，把剪影与地面之间的硬边化开
    drawFogBand(HERO_BOTTOM, 0.12f, top, alpha * DISTANT_ALPHA * 1.6f)
    drawFogBand(0.12f, 0.20f, mid, alpha * DISTANT_ALPHA * 0.5f)
}

private const val RAY_COUNT = 12

// ─────────────────────── 3 · Speak Now ───────────────────────

/**
 * 教堂里那扇尖拱花窗 + 从窗里泻下来的一束光 + 两侧石柱 + 三排长椅。
 *
 * *Speak Now* 的同名曲讲的就是闯进婚礼喊「反对」那一幕，所以这一张的场景是**教堂内景**，
 * 主体是那扇尖拱窗。
 *
 * 为什么不画紫裙、不画帷幕：这两样**都已经在卡片上**了 —— 卡片的道具是
 * [drawStageCurtain]（幕褶 + 金绳穗子 + 三层裙摆）。同一件东西在背景与卡片上各画一遍，
 * 就是 Red 那张「背景一条围巾、卡片又一条围巾」的同一个毛病，两个主体还会互相抢。
 *
 * 上一版这里是**三条横贯全屏的正弦带**（薄纱）。第三轮截图里它们读作「三道随手画的波浪」：
 * 没有边界、不附着在任何东西上，而且横穿整屏把画面切成四条。现在只在窗下留一层地面雾。
 *
 * 尖拱用的是**等边拱**：两段圆弧的半径都等于窗宽，圆心各在对侧的起拱点上。
 * 这是哥特窗真实的作法，拱高因此恰好是 `width * √3/2` —— 随手画两段贝塞尔凑出来的
 * 尖拱总是又胖又矮，读作清真寺的葱形拱。
 */
private fun DrawScope.drawVeilSpotlight(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float
) {
    val w = size.width
    val h = size.height

    // 两侧石柱：每侧两根，外侧那根更暗更宽（近），内侧浅（远）
    for (side in 0..1) {
        for (i in 0..1) {
            val colW = w * (if (i == 0) 0.075f else 0.052f)
            val x = if (side == 0) w * (if (i == 0) 0.005f else 0.098f)
            else w - colW - w * (if (i == 0) 0.005f else 0.098f)
            drawRect(
                color = deep,
                topLeft = Offset(x, 0f),
                size = Size(colW, h * 0.62f),
                alpha = alpha * (if (i == 0) 0.62f else 0.44f)
            )
        }
    }
    // 檐口：**一条通宽的横石**，压在四根柱子的顶上。
    //
    // 前两轮这里是每根柱子各自一块柱头（1.32×柱宽、0.020h 高）。两轮截图都是同一个结果：
    // 柱身在浅紫底上几乎看不出来，而四块柱头因为 alpha 更高，读作屏幕上缘四角各一道
    // 孤零零的暗杠 —— 「两条短横线」而不是「柱子」。通宽一条之后它同时是天花线，
    // 四根柱子被它连成一列拱廊，上缘也不再是一片空的渐变。
    //
    // 落在 0.048h–0.078h：0.026h 那档会被状态栏的时间与图标压掉一半
    drawRect(
        color = deep,
        topLeft = Offset(0f, h * 0.048f),
        size = Size(w, h * 0.030f),
        alpha = alpha * 0.52f
    )
    // 檐口下沿的一道线脚：同色更浓的一条窄边。没有它那块横石是一个平贴的矩形，
    // 加了之后下缘有厚度，读作出挑的檐
    drawRect(
        color = deep,
        topLeft = Offset(0f, h * 0.072f),
        size = Size(w, h * 0.006f),
        alpha = alpha * 0.74f
    )

    // 尖拱花窗
    val winW = w * 0.34f
    val winLeft = w * 0.46f - winW / 2f
    val winCx = winLeft + winW / 2f
    // 0.090h 而不是 0.075h：拱尖要落在檐口（收在 0.078h）以下，
    // 否则窗顶插进那条横石里，读作「窗从天花板里长出来」
    val apexY = h * 0.090f
    // 等边拱：拱高 = 窗宽 × √3/2
    val springY = apexY + winW * 0.866f
    val sillY = h * 0.40f
    path.rewind()
    path.moveTo(winLeft, sillY)
    path.lineTo(winLeft, springY)
    // 左弧：圆心在右起拱点，半径 = 窗宽，从起拱点扫到拱尖
    path.arcTo(
        rect = Rect(winLeft + winW - winW, springY - winW, winLeft + winW + winW, springY + winW),
        startAngleDegrees = 180f,
        sweepAngleDegrees = 60f,
        forceMoveTo = false
    )
    // 右弧：圆心在左起拱点，反向扫回起拱点
    path.arcTo(
        rect = Rect(winLeft - winW, springY - winW, winLeft + winW, springY + winW),
        startAngleDegrees = -60f,
        sweepAngleDegrees = 60f,
        forceMoveTo = false
    )
    path.lineTo(winLeft + winW, sillY)
    path.close()
    // 玻璃：整片先铺首档浅色，窗因此是「亮的洞」，其余全是暗的教堂内壁
    drawPath(path = path, color = top, alpha = alpha * 0.62f)
    drawWindowGlass(path, winLeft, winW, springY, sillY, mid, alpha)
    // 石框：最后描，压住所有窗棂的端头
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * 0.86f,
        style = Stroke(width = (winW * 0.055f).coerceAtLeast(2f), join = StrokeJoin.Round)
    )
    // 窗台：一块外挑的横石
    drawRect(
        color = deep,
        topLeft = Offset(winLeft - winW * 0.10f, sillY),
        size = Size(winW * 1.20f, h * 0.016f),
        alpha = alpha * 0.80f
    )

    // 从窗里泻下来的光。锥顶钉在窗心，所以这束光读作**穿窗而入**，
    // 而不是上一版那样从屏幕上缘凭空打下来
    drawCone(
        path = path,
        apexX = winCx,
        apexY = springY,
        halfWidth = w * 0.30f,
        bottomY = h * 0.84f,
        color = top,
        alpha = alpha * 0.30f
    )

    // 长椅：三排，越近越宽越暗。
    //
    // 每排是**椅背 + 座板 + 两侧立柱 + 四条腿**四件套。上一版一排只有一根圆角横木，
    // 截图里三排读作「三道平行的扶手栏杆」—— 一根横木没有坐面，就不是长椅。
    for (row in 0..2) {
        val f = row / 2f
        val pewW = w * (0.44f + f * 0.46f)
        val cx = w * 0.5f
        val backY = h * (0.425f + f * 0.048f)
        val backH = h * (0.010f + f * 0.003f)
        // 座板比椅背略宽：坐面是往外挑的，这一点点差别就把「板」和「背」分开了
        val seatW = pewW * 1.05f
        val seatY = backY + backH * 2.4f
        val seatH = h * (0.013f + f * 0.004f)
        val ink = alpha * (0.42f + f * 0.28f)
        // 椅背与座板之间的两根立柱：先画，被座板压住上端
        for (s in 0..1) {
            val lx = cx + (if (s == 0) -1f else 1f) * pewW * 0.46f
            drawRect(
                color = deep,
                topLeft = Offset(lx - backH * 0.22f, backY),
                size = Size(backH * 0.44f, seatY - backY),
                alpha = ink * 0.86f
            )
        }
        path.rewind()
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                Rect(cx - pewW / 2f, backY, cx + pewW / 2f, backY + backH),
                CornerRadius(backH * 0.45f)
            )
        )
        drawPath(path = path, color = deep, alpha = ink)
        path.rewind()
        path.addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                Rect(cx - seatW / 2f, seatY, cx + seatW / 2f, seatY + seatH),
                CornerRadius(seatH * 0.40f)
            )
        )
        drawPath(path = path, color = deep, alpha = (ink * 1.12f).coerceAtMost(1f))
        // 椅腿：座板下两端各一，腿在座板内侧一点，长椅因此站在地上
        for (s in 0..1) {
            val lx = cx + (if (s == 0) -1f else 1f) * pewW * 0.42f
            drawRect(
                color = deep,
                topLeft = Offset(lx - seatH * 0.20f, seatY + seatH),
                size = Size(seatH * 0.40f, seatH * 1.6f),
                alpha = ink * 0.82f
            )
        }
    }

    // 地面雾：把长椅下缘与卡片顶边之间化开。上一版三条正弦带只剩这一层
    drawFogBand(0.52f, 0.16f, top, alpha * DISTANT_ALPHA * 1.4f)
    drawFogBand(0.86f, 0.20f, deep, alpha * DISTANT_ALPHA)
}

/**
 * 花窗的窗棂与彩玻：竖棂 + 横楣 + 拱心的四叶饰 + 几格上色的玻璃。
 *
 * 全部**裁在窗形之内**（`clipPath`）。不裁的话竖棂会一路捅出拱线以外，
 * 屏幕上是窗顶伸出来两根天线 —— 尖拱的上半是斜的，直线画到 apexY 必然出界。
 *
 * [frame] 是路径本身（调用方刚描完的窗形），这里只读不改；棂与玻璃全部另起路径，
 * 画完不需要还原 —— 调用方随后要用它描石框，会自己 rewind。
 *
 * 上色的格子是**中轴对称的菱形花样**（四处 mid、两处 GOLD）：整窗一个色是磨砂玻璃，
 * 而随机几格又读作「表格里随手填的单元格」—— 铅条彩窗的格子是排过花样的。
 */
private fun DrawScope.drawWindowGlass(
    frame: Path,
    winLeft: Float,
    winW: Float,
    springY: Float,
    sillY: Float,
    mid: Color,
    alpha: Float
) {
    val bar = (winW * 0.026f).coerceAtLeast(1.5f)
    clipPath(frame) {
        // 三道竖棂把窗分成三条 light（哥特窗的标准分法），一路顶到拱里被裁掉
        for (m in 1..2) {
            val mx = winLeft + winW * m / 3f
            drawLine(
                color = INK,
                start = Offset(mx, springY - winW * 0.90f),
                end = Offset(mx, sillY),
                strokeWidth = bar,
                alpha = alpha * 0.72f
            )
        }
        // 横楣：起拱线到窗台**四等分**。
        //
        // 上一版这里是 0.20 起、每道 ×1.10 递增的「透视」间距，但花窗是正对镜头的一个平面，
        // 本来就没有纵深可透视；更要命的是上色的格子按等高排，与递增的楣带对不上，
        // 截图里最下那格正好跨在一道横楣上，读作「贴歪了的色块」。等分之后格与带同一套坐标。
        val rowH = (sillY - springY) / 4f
        for (r in 1..3) {
            val ty = springY + rowH * r
            drawLine(
                color = INK,
                start = Offset(winLeft, ty),
                end = Offset(winLeft + winW, ty),
                strokeWidth = bar * 0.8f,
                alpha = alpha * 0.62f
            )
        }
        // 上色的玻璃格：(列, 行, 用不用金色)。
        //
        // 刻意**左右对称**、按对角错开：上一版是四格随机位置，截图里读作
        // 「表格里随手填了几个单元格」—— 随机不等于手工，铅条彩窗的格子是有花样的。
        // 现在是 mid 一对 → 金一格 → mid 一对 → 金一格 的菱形花样，中轴对称
        val panes = floatArrayOf(
            0f, 0f, 0f, 2f, 0f, 0f,
            1f, 1f, 1f,
            0f, 2f, 0f, 2f, 2f, 0f,
            1f, 3f, 1f
        )
        val lightW = winW / 3f
        for (p in 0 until 6) {
            val col = panes[p * 3]
            val row = panes[p * 3 + 1]
            val gold = panes[p * 3 + 2] > 0.5f
            drawRect(
                color = if (gold) GOLD else mid,
                topLeft = Offset(winLeft + lightW * col + bar, springY + rowH * row + bar),
                size = Size(lightW - bar * 2f, rowH - bar * 2f),
                alpha = alpha * (if (gold) 0.42f else 0.34f)
            )
        }
        // 拱心的四叶饰：四个圆瓣 + 一圈外环。哥特窗拱头里就是这个
        val qy = springY - winW * 0.40f
        val qr = winW * 0.115f
        drawCircle(
            color = INK,
            radius = qr * 2.05f,
            center = Offset(winLeft + winW / 2f, qy),
            alpha = alpha * 0.55f,
            style = Stroke(width = bar)
        )
        for (lobe in 0 until 4) {
            val a = lobe / 4f * TAU
            drawCircle(
                color = mid,
                radius = qr,
                center = Offset(
                    winLeft + winW / 2f + cos(a) * qr,
                    qy + sin(a) * qr
                ),
                alpha = alpha * 0.40f
            )
            drawCircle(
                color = INK,
                radius = qr,
                center = Offset(
                    winLeft + winW / 2f + cos(a) * qr,
                    qy + sin(a) * qr
                ),
                alpha = alpha * 0.50f,
                style = Stroke(width = bar * 0.8f)
            )
        }
    }
}

// ─────────────────────── 4 · Red ───────────────────────

/**
 * 枯枝上挂着的五片大红枫叶 + 铺满下半屏的粗棒针织物。
 *
 * 主体是**枫叶**（见 [KNIT_MAPLES]）：秋天与那件针织是 Red 的两个视觉支柱，而围巾
 * 已经在卡片右上角画成「围起来的一圈」了 —— 背景再挂一条就是同一件道具画两遍，
 * 两条围巾还会互相抢主体。织物本身铺在 [KNIT_TOP] 以下，是这一张的纹理不是主体。
 * 线圈刻意粗（每行 13 针）—— 那件是粗棒针织的，细密的针法读作 T 恤。
 *
 * 前几轮截图的四处返工记在这儿，免得再犯：
 * - 上三分之一原先**是空的**（只有两条雾），12 张里唯一一张卡片以上没有主体的。
 * - 织物上缘原先是一道 `drawRect` 的**直边**，读作「贴上去的一块矩形」。现在上缘走
 *   [knitEdge] 的双频正弦，V 字线圈**逐针**裁在它下面 —— 边缘因此是按针脚锯齿的，
 *   真织物的收边本来就这样，比一条光滑曲线更像。
 * - 麻花辫原先只有左边一条，孤零零一道读作「一根绳子」。改成三条各带罗纹沟槽的绞花柱。
 * - 线圈原先行行等高等距，是一张网格贴图。现在行高、V 的深度与横向错位都按 [jitter01]
 *   抖过 —— 手织物不会那么齐。
 *
 * 整块织物**不随 [phase] 动**：布料该是垂在那儿的，飘起来就成了旗子。动的只有秋雾。
 */
private fun DrawScope.drawKnitAutumn(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float,
    lowRam: Boolean,
    maple: MapleArt
) {
    val w = size.width
    val h = size.height
    val stitchW = w / (if (lowRam) 9f else 13f)
    val stitchH = stitchW * 0.72f
    val stroke = stitchW * 0.17f

    // 远景暖雾先铺满上半屏：枯枝与围巾压在它上面才分得出前后
    val drift = sin(phase * TAU) * 0.02f
    drawFogBand(0.16f + drift, 0.24f, top, alpha * DISTANT_ALPHA * 1.4f)

    // 织物本体：先垫一层布身，织物才不是「浮在渐变上的一堆线」。
    // 上缘走 knitEdge 的波浪，下面三边直接贴到画布边
    path.rewind()
    path.moveTo(0f, knitEdge(0f) * h)
    for (i in 1..KNIT_EDGE_SAMPLES) {
        val fx = i / KNIT_EDGE_SAMPLES.toFloat()
        path.lineTo(fx * w, knitEdge(fx) * h)
    }
    path.lineTo(w, h)
    path.lineTo(0f, h)
    path.close()
    drawPath(path = path, color = mid, alpha = alpha * 0.17f)

    // 收边罗纹：沿着同一条波浪描一道粗边。原先这里是一道贴满屏宽的 drawRect，
    // 那条水平直边是第一轮最刺眼的缺陷
    path.rewind()
    path.moveTo(0f, knitEdge(0f) * h)
    for (i in 1..KNIT_EDGE_SAMPLES) {
        val fx = i / KNIT_EDGE_SAMPLES.toFloat()
        path.lineTo(fx * w, knitEdge(fx) * h)
    }
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * PROP_ALPHA * 0.55f,
        style = Stroke(width = stitchH * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 全部 V 字塞进同一条 Path 一次画完：几百次 drawLine 会把绘制预算吃光
    path.rewind()
    var row = 0
    // 从上缘波谷再往上起一行：这样最上面那几行是**部分**留下的，
    // 织物的边界因此是按针脚锯齿的
    var y = (KNIT_TOP - 0.034f) * h
    while (y < h + stitchH) {
        val j = jitter01(row * 7 + 3)
        val rowH = stitchH * (0.90f + j * 0.20f)
        val vDepth = stitchH * (0.52f + jitter01(row * 13 + 5) * 0.22f)
        // 隔行错半针，这是平针织物的样子；不错行就成了菱形网格。
        // 再叠一点行级抖动，免得整片是一张规整贴图
        val offsetX = (if (row % 2 == 0) 0f else stitchW / 2f) + (j - 0.5f) * stitchW * 0.22f
        var x = -stitchW + offsetX
        while (x < w + stitchW) {
            // 逐针裁：这一针整个在上缘以上就不下笔
            if (y >= knitEdge(x / w) * h) {
                path.moveTo(x, y)
                path.lineTo(x + stitchW / 2f, y + vDepth)
                path.lineTo(x + stitchW, y)
            }
            x += stitchW
        }
        y += rowH
        row++
    }
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * PROP_ALPHA * 0.75f,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 绞花麻花柱：两条相位相反的正弦股绞在一起，是粗棒针织最好认的那道花样。
    // **三条**而不是一条 —— 左边孤零零一道读作「一根掉在布上的绳子」；
    // 粗棒针织的绞花本来是成列排的，两侧还各有一道罗纹沟槽把它嵌在平针里。
    //
    // 绞距（freq 9–11）比第一轮的 5–7 密一倍、股也细了：绞得太松、股太粗的时候
    // 三条柱子读作「三根挂在布上的粗麻绳」，而不是织在布里的花样
    val braidAmp = stitchW * 0.45f
    val gutter = braidAmp + stitchW * 0.40f
    for (b in 0 until 3) {
        val bx = w * (0.12f + b * 0.38f)
        val bTop = knitEdge(bx / w) * h + stitchH * 0.4f
        // 三条柱子的绞距各不相同：同一个频率排三列又回到「规整贴图」
        val freq = 9f + b
        val skew = jitter01(b * 31 + 11) * 0.2f
        for (s in -1..1 step 2) {
            drawLine(
                color = deep,
                start = Offset(bx + s * gutter, bTop),
                end = Offset(bx + s * gutter, h),
                strokeWidth = stitchW * 0.10f,
                alpha = alpha * PROP_ALPHA * 0.5f
            )
        }
        for (strand in 0..1) {
            path.rewind()
            var first = true
            var by = bTop
            while (by < h) {
                val f = (by - bTop) / (h - bTop)
                // 频率取整数，绞花在上下缘都收得住；strand 差半个周期即互绞
                val x = bx + sin((f * freq + strand * 0.5f + skew) * TAU) * braidAmp
                if (first) {
                    path.moveTo(x, by)
                    first = false
                } else {
                    path.lineTo(x, by)
                }
                by += h * 0.008f
            }
            drawPath(
                path = path,
                color = deep,
                alpha = alpha * PROP_ALPHA * (if (strand == 0) 1.15f else 0.8f),
                style = Stroke(width = stitchW * 0.30f, cap = StrokeCap.Round)
            )
        }
    }

    // 压在织物上缘那条波浪上的一层薄雾：让「布」与「天」之间是渐变而不是一条线。
    // 上缘的锯齿已经不是直边了，再糊一层雾，那道过渡就彻底看不出是两个图层
    drawFogBand(KNIT_TOP - 0.01f - drift, 0.13f, mid, alpha * DISTANT_ALPHA)

    // ── 枯枝：枫叶得长在什么东西上，凭空飘着的五片就只是贴纸 ──
    // 12 段逐段变细拼一条弯枝。等宽的一条读作电线
    var px = knitBoughX(0f) * w
    var py = knitBoughY(0f) * h
    for (i in 1..KNIT_BOUGH_SEGMENTS) {
        val t = i / KNIT_BOUGH_SEGMENTS.toFloat()
        val nx = knitBoughX(t) * w
        val ny = knitBoughY(t) * h
        drawLine(
            color = deep,
            start = Offset(px, py),
            end = Offset(nx, ny),
            strokeWidth = w * (0.019f - 0.013f * t),
            // **Butt 而不是 Round**：圆头会在每个接点多画半个圆，
            // 半透明叠两遍就是一颗深色圆点 —— 第一轮那条枝读作「串了珠子的绳」。
            // 只有梢头那一段收圆
            cap = if (i == KNIT_BOUGH_SEGMENTS) StrokeCap.Round else StrokeCap.Butt,
            alpha = alpha * SILHOUETTE_ALPHA
        )
        px = nx
        py = ny
    }
    // 三根上翘的小枝。Red 是深秋，枝上不留叶 —— 叶子都在 L2 层飘着
    for (i in 0 until 3) {
        val t = 0.22f + i * 0.24f
        drawBranch(
            x = knitBoughX(t) * w,
            y = knitBoughY(t) * h,
            length = h * 0.045f,
            angleDeg = -74f + i * 16f,
            width = w * 0.006f,
            depth = 3,
            bend = 0.3f - i * 0.2f,
            color = deep,
            alpha = alpha * SILHOUETTE_ALPHA
        )
    }

    drawKnitMaples(maple, mid, deep, alpha)
}

/** 织物上缘的基准高度。波浪在它上下各 0.027 屏高内摆。 */
private const val KNIT_TOP = 0.335f

/** 上缘波浪的采样段数。织物的边是柔的，多采样只是白烧 CPU。 */
private const val KNIT_EDGE_SAMPLES = 26

/**
 * 织物上缘在横向比例 [fx] 处的高度（占屏高）。
 *
 * **两个不成整数比的频率叠加**（1.6 与 3.7）：单频正弦一眼就看出是数学曲线，
 * 叠一个错拍的高频之后，波峰波谷的间距不再规律，读起来才是一条搭下来的布边。
 * 振幅合起来 0.027 屏高，最深的波谷仍在 Red 卡片顶边（0.455h）以上。
 */
private fun knitEdge(fx: Float): Float =
    KNIT_TOP + sin((fx * 1.6f + 0.12f) * TAU) * 0.020f + sin(fx * 3.7f * TAU) * 0.007f

private const val KNIT_BOUGH_SEGMENTS = 12

/** 枯枝的二次贝塞尔：P0 (-0.02, 0.068) → P1 (0.36, 0.175) → P2 (0.82, 0.100)，单位是屏宽 / 屏高。 */
private fun knitBoughX(t: Float): Float =
    (1f - t) * (1f - t) * -0.02f + 2f * t * (1f - t) * 0.36f + t * t * 0.82f

private fun knitBoughY(t: Float): Float =
    (1f - t) * (1f - t) * 0.068f + 2f * t * (1f - t) * 0.175f + t * t * 0.100f

/**
 * Red 的主体：搭在枯枝上的五片大红枫叶。
 *
 * x（占屏宽）, y（占屏高）, 半径（占最小边）, 旋转（度）四元组。
 *
 * 旋转都落在 180° 附近（150–214）：位图那片叶子是**尖瓣朝上、叶柄在下**的，
 * 转过来叶柄才朝着枝。五片的角度各差二三十度 —— 同一个角度摆五片是贴图。
 *
 * 尺寸拉开到 0.062–0.150（2.4 倍差）：这是唯一能在一层平面上做出景深的手段，
 * 等大的五片会平铺成一张壁纸。最大那片钉在 (0.30w, 0.205h)，
 * 正是枯枝下垂最低那一段的下方（枝在 t=0.5 处约 0.135h），读起来就是从那儿挂下来的。
 *
 * 五片都在 0.32h 以上，压不到 Red 卡片的顶边（16 首 → 0.455h）。
 */
private val KNIT_MAPLES = floatArrayOf(
    0.300f, 0.205f, 0.150f, 188f,
    0.630f, 0.130f, 0.105f, 205f,
    0.815f, 0.272f, 0.098f, 168f,
    0.125f, 0.135f, 0.070f, 150f,
    0.485f, 0.318f, 0.062f, 214f
)

/**
 * 五片大枫叶：实心填色 + 墨线（叶缘、叶脉、叶柄全在墨线里）。
 *
 * 叶形来自参考图取墨的两张位图（[MapleArt]）—— 与 L2 层飘落的秋叶、杯套上那片刻线
 * 是**同一片真叶**（同一份 PNG），这里只是换填色（[mid]）与墨线（[deep]）两个颜色，
 * 不再是为这一处另画一套叶子。换图前这里是「填色 + 描边 + 掌状脉」三笔，
 * 现在是「实心图 + 墨线图」两笔：轮廓与叶脉在一张图里，天然重合。
 *
 * 近乎不透明（0.92）：它们是这一张的主体，压在淡粉的上半屏上，
 * 半透明会稀释成几团粉影。墨线只给 0.45 —— 叶脉是压出来的暗痕，画实了像铁丝。
 */
private fun DrawScope.drawKnitMaples(maple: MapleArt, mid: Color, deep: Color, alpha: Float) {
    val w = size.width
    val h = size.height
    val u = size.minDimension
    for (i in 0 until KNIT_MAPLES.size / 4) {
        val half = KNIT_MAPLES[i * 4 + 2] * u
        withTransform({
            translate(KNIT_MAPLES[i * 4] * w, KNIT_MAPLES[i * 4 + 1] * h)
            rotate(KNIT_MAPLES[i * 4 + 3], Offset.Zero)
        }) {
            // 叶柄末端钉在局部原点下方 `MAPLE_STEM_END_R` half（换图前的落点口径）、
            // 总高 `MAPLE_SPAN` half —— 五片的落点、角度、大小一处都不用重调。
            // stemEndFrac：这几片用的是**弯柄**那套（参考图的叶柄本来就往左弯），
            // 锚点跟着叶柄末端走，五片才不会整体横移
            val height = MAPLE_SPAN * half
            val stemEnd = Offset(0f, MAPLE_STEM_END_R * half)
            drawMapleSolid(maple.solid, height, stemEnd, mid, alpha * 0.92f, maple.stemEndFrac)
            drawMapleInk(maple.ink, height, stemEnd, deep, alpha * 0.45f, maple.stemEndFrac)
        }
    }
}

/**
 * 纽约天际线 + 海岸线 + 挂着的宝丽来。
 *
 * *Welcome To New York* 与那组宝丽来是 1989 的全部视觉。天际线楼群不排序，
 * 互相遮挡才有纵深；宝丽来只画白边与灰底，**不画内容** —— 画了就是在画封面。
 */
private fun DrawScope.drawSkylinePolaroids(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float,
    shapes: BackdropShapes
) {
    val w = size.width
    val h = size.height
    // 0.60h 而不是 0.68h：楼高最高 0.36h，基线抬这一档之后最高的几栋顶到 0.24h，
    // 整片天际线的上三分之二都在卡片顶边以上。天际线是**这一张的纹理**而不是单个主体，
    // 所以不必像王座、钢琴那样整个收进 HERO_BOTTOM
    val skylineBase = h * 0.60f

    // 天际线。窗灯用固定的取模规则而不是再抽一次随机 —— 每帧抽随机会让整片楼闪成噪点
    val towers = shapes.towers
    val count = towers.size / 3
    for (i in 0 until count) {
        val cx = towers[i * 3] * w
        val tw = towers[i * 3 + 1] * w
        val th = towers[i * 3 + 2] * h
        drawRect(
            color = deep,
            topLeft = Offset(cx - tw / 2f, skylineBase - th),
            size = Size(tw, th),
            alpha = alpha * SILHOUETTE_ALPHA
        )
        // 楼顶水塔 / 天线：纽约天际线的识别点，光是方块只会读作条形图
        if (i % 3 == 0) {
            drawRect(
                color = deep,
                topLeft = Offset(cx - tw * 0.10f, skylineBase - th - th * 0.10f),
                size = Size(tw * 0.20f, th * 0.10f),
                alpha = alpha * SILHOUETTE_ALPHA
            )
        }
        // 窗灯：3 列 × 若干行的小点阵，隔一个点亮一个
        val cols = 3
        val cellW = tw / (cols * 2f + 1f)
        val rows = (th / (cellW * 2.4f)).toInt().coerceAtMost(9)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if ((r * 7 + c * 3 + i) % 4 != 0) continue
                drawRect(
                    color = LAMP_WARM,
                    topLeft = Offset(
                        cx - tw / 2f + cellW * (1f + c * 2f),
                        skylineBase - th + cellW * (1.2f + r * 2.4f)
                    ),
                    size = Size(cellW, cellW * 1.3f),
                    alpha = alpha * 0.55f
                )
            }
        }
    }

    // 海岸线：三道水平的浪，按 phase 横向推移（整数倍频，绕回连续）
    for (i in 0 until 3) {
        val y = skylineBase + h * (0.06f + i * 0.075f)
        path.rewind()
        var first = true
        for (s in 0..WAVE_SAMPLES) {
            val fx = s / WAVE_SAMPLES.toFloat()
            val yy = y + sin((fx * (2f + i) + phase * (1f + i)) * TAU) * h * 0.012f
            if (first) {
                path.moveTo(fx * w, yy)
                first = false
            } else {
                path.lineTo(fx * w, yy)
            }
        }
        drawPath(
            path = path,
            color = if (i == 1) mid else top,
            alpha = alpha * PROP_ALPHA * (1.2f - i * 0.25f),
            style = Stroke(width = h * 0.006f, cap = StrokeCap.Round)
        )
    }

    // 宝丽来：白边框 + 灰底 + 下缘那道宽白边。挂在上半屏，压不到卡片。
    //
    // 先横过整屏拉一根麻线，四张都吊在这根线上。第一轮每张只有一小截往上戳的短线加个点，
    // 屏幕上读出来是四根天线 —— 图钉钉在空气里比不画图钉更假。
    //
    // 0.058h 而不是 0.024h：那一档压在状态栏里，整根线连同夹子被时钟与信号图标切成几段，
    // 照片看着还是吊在空气上。这一档落在状态栏下缘以下。
    // 线本身走一道浅悬链（中点垂 0.012h）—— 绷成一条直线的是钢丝，不是麻绳
    val stringY = h * 0.058f
    val sag = h * 0.012f
    path.rewind()
    path.moveTo(0f, stringY)
    path.quadraticTo(w * 0.5f, stringY + sag * 2f, w, stringY)
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * 0.40f,
        style = Stroke(width = (h * 0.0012f).coerceAtLeast(1f))
    )
    val polaroids = shapes.polaroids
    val frameW = w * 0.14f
    for (i in 0 until polaroids.size / 3) {
        val px = polaroids[i * 3] * w
        val py = polaroids[i * 3 + 1] * h
        // 麻线在这一点的实际高度。二次贝塞尔那条线上 x = t·w，所以
        // y = stringY + 4·sag·t(1−t) —— 夹子必须夹在垂下来的那条线上，不是在基准高度上
        val fx = polaroids[i * 3]
        val hangY = stringY + 4f * sag * fx * (1f - fx)
        // 吊线与夹子画在 rotate 之外：线是垂的，只有照片会歪。
        // 跟着一起转的话线会斜着从麻线上飘出来
        drawLine(
            color = deep,
            start = Offset(px, hangY),
            end = Offset(px, py),
            strokeWidth = 1.5f,
            alpha = alpha * 0.5f
        )
        drawRect(
            color = deep,
            topLeft = Offset(px - frameW * 0.035f, hangY - h * 0.004f),
            size = Size(frameW * 0.07f, h * 0.010f),
            alpha = alpha * 0.62f
        )
        // 挂着的东西会晃：±1.5° 的摆动，相位按序号错开
        val sway = sin((phase + i * 0.31f) * TAU) * 1.5f
        rotate(degrees = polaroids[i * 3 + 2] + sway, pivot = Offset(px, py)) {
            val frameH = frameW * 1.20f
            drawRect(
                color = PAPER_WHITE,
                topLeft = Offset(px - frameW / 2f, py),
                size = Size(frameW, frameH),
                alpha = alpha * 0.72f
            )
            // 相纸的成像区：上下左右不等宽，下边最宽 —— 这是宝丽来最好认的比例
            drawRect(
                color = deep,
                topLeft = Offset(px - frameW / 2f + frameW * 0.075f, py + frameW * 0.075f),
                size = Size(frameW * 0.85f, frameW * 0.85f),
                alpha = alpha * 0.30f
            )
        }
    }
    drawFogBand(0.58f, 0.10f, top, alpha * DISTANT_ALPHA * 1.5f)
}

private const val WAVE_SAMPLES = 28

// ─────────────────────── 6 · reputation ───────────────────────

/**
 * 网点底 + 金色王座 + 一条**绕着王座游过去**的大蛇。
 *
 * ## 蛇的行程
 *
 * 整段 reputation（[REP_ERA_MS] 毫秒）里蛇走完一趟：从屏幕左缘游进来 → 绕王座一圈半
 * （远侧在王座背后、近侧在王座前面）→ 在右上方立起头 → 从右侧甩出去、消失。
 *
 * 做法是**一条固定的曲线 + 一个滑动的窗口**：[snakePointX] / [snakePointY] 把 `s ∈ 0..1`
 * 映射成屏幕上的一点（进场段 / 绕圈段 / 抬头出画段三截拼起来），蛇身占 `s ∈
 * [sHead - SNAKE_LEN, sHead]` 这一段，头位置 `sHead` 随本段进度从 0 走到 `1 + SNAKE_LEN`。
 * 落在 `[0,1]` 之外的采样点直接不画 —— 于是「从左缘冒出来」和「从右缘拖走」都是免费的，
 * 不用另写出入场动画。时间上四段各一个速度（见 [SNAKE_KEY_S]）：进场快、绕圈稳、
 * 抬头时几乎停住、末段甩出去 —— 那个「停住」就是攻击姿态的定格。
 *
 * ## 两处接缝必须是切线连续的
 *
 * 三截曲线在段界处只对上**位置**是不够的：切线一拐折，蛇身立刻读作一条被掰过的管子
 * ——「转弯时身体变成了折线」说的就是这个，而出圈那一刻正是蛇头抬起、最该好看的一刻。
 * 所以进场段与出画段都是三次贝塞尔，它们的末端/首端控制点分别落在**椭圆在两个端点上的
 * 切线**里：
 *
 * - 进场段从椭圆最左点的**下方**接上来（末端控制点在正下方），末端切线竖直向上；
 * - 出画段从最右点**竖直向下**出发（首端控制点在正下方），再往右上兜出去。
 *
 * 椭圆在两个端点的切线恰好是竖直的，这条约束与屏幕宽高比无关 —— 换一台比例不同的机器
 * 也不会重新长出折角。「抬头」因此不再是原地硬折一下，而是顺着绕圈的下半程兜上来：
 * 颈先沉一下、再抬起来，就是蛇发力前那个 S。
 *
 * 段界上的**曲率**仍有跳变（三次贝塞尔对不上椭圆的曲率），但蛇身管径只有 0.017h，
 * 这点偏差在屏幕上读不出来；切线不一致却是必现的一道折痕。
 *
 * ## 为什么要分两趟画
 *
 * 「绕」和「压在上面」的差别只有一件事：远侧那半圈必须被王座挡住。所以蛇身按深度切段，
 * **远侧先画 → 王座 → 近侧后画**（`sin θ < 0` 的那半圈在后）。上一版是一条正弦从王座上
 * 横穿过去，压在金色上，屏幕上读作「一根波浪管子搭在王座前面」，而且王座从半透明的蛇身里
 * 透出来 —— 那不是绕。
 *
 * @param eraMs 本段已过的毫秒；负数 = 本段还没开始（换张淡变期），蛇整条在屏外
 */
private fun DrawScope.drawHalftoneThrone(
    path: Path,
    top: Color,
    phase: Float,
    alpha: Float,
    eraMs: Long,
    shapes: BackdropShapes
) {
    val w = size.width
    val h = size.height

    // 网点：整块一次画完，缓存在 shapes 里（见 BackdropShapes.halftone）。
    // 0.22 而不是 0.30：网点在卡片那半屏会透过半透明白纸显出来，
    // 0.30 时整张卡片读作一张打孔纸，压在曲目名底下抢注意力
    drawPath(path = shapes.halftone(size), color = top, alpha = alpha * 0.22f)

    // ── 蛇的窗口 ──
    val progress = if (eraMs < 0L) 0f else eraMs / REP_ERA_MS
    val sHead = snakeHeadAt(progress)
    val sTail = (sHead - SNAKE_LEN).coerceAtLeast(0f)
    val sTip = sHead.coerceAtMost(1f)
    val span = sTip - sTail
    val maxThick = h * SNAKE_THICK

    fun sAt(i: Int) = sTail + span * i / SNAKE_SAMPLES
    fun px(s: Float) = snakePointX(s) * w
    fun py(s: Float) = snakePointY(s) * h

    // 法向（像素空间里算，w != h 时按比例算出来的方向是歪的）
    fun nx(s: Float): Float {
        val d = py(s + SNAKE_DS) - py(s - SNAKE_DS)
        val e = px(s + SNAKE_DS) - px(s - SNAKE_DS)
        val len = hypot(e, d)
        return if (len < 0.001f) 0f else -d / len
    }

    fun ny(s: Float): Float {
        val d = py(s + SNAKE_DS) - py(s - SNAKE_DS)
        val e = px(s + SNAKE_DS) - px(s - SNAKE_DS)
        val len = hypot(e, d)
        return if (len < 0.001f) 1f else e / len
    }

    // 粗细：颈就是最粗处 → 前 30% 一路等粗 → 之后单调收到尾尖。数见 [SNAKE_NECK] 那一段
    fun thick(s: Float): Float {
        val u = ((sHead - s) / SNAKE_LEN).coerceIn(0f, 1f)
        val shape = when {
            u < SNAKE_KNEE -> SNAKE_NECK
            u < SNAKE_MID_KNEE ->
                SNAKE_MID + (SNAKE_NECK - SNAKE_MID) *
                    ((SNAKE_MID_KNEE - u) / (SNAKE_MID_KNEE - SNAKE_KNEE))
            u < SNAKE_TAIL_KNEE ->
                SNAKE_TAIL + (SNAKE_MID - SNAKE_TAIL) *
                    ((SNAKE_TAIL_KNEE - u) / (SNAKE_TAIL_KNEE - SNAKE_MID_KNEE))
            else -> SNAKE_TIP + (SNAKE_TAIL - SNAKE_TIP) * ((1f - u) / (1f - SNAKE_TAIL_KNEE))
        }
        return maxThick * shape
    }

    // 一条边：side = -1 是背侧、+1 是腹侧；scale 收窄用来画背脊高光与腹侧暗带
    fun edgeX(s: Float, side: Float, scale: Float) = px(s) + nx(s) * thick(s) * side * scale
    fun edgeY(s: Float, side: Float, scale: Float) = py(s) + ny(s) * thick(s) * side * scale

    // 腾背两档明暗要看**屏幕上下**而不是行进方向。
    //
    // 法向 `(-dy, dx)` 的 y 分量就是 `dx`，而绕王座那一圈里**走在前面的那半程**
    // （`sin θ > 0`）正是往左走的（`dx < 0`）—— 按行进方向取侧别，整条腹侧暗带会翻到
    // 背上去，圆柱的受光面上下颠倒，而那正是看得最清楚的半程。
    // 绕着柱子的身体，朝上的那一面永远朝上。
    //
    // 宽度再按 `|ny|` 收：身子走成竖直的那一段两侧一样朝上，本就不该有腾背之分；
    // 收到 0 也同时把 `sd` 翻符号那一处的跳变抹平了 —— 两条带子都改成**多边形**
    // 而不是描边，就是为了宽度能逐点变（描边整条只能一个线宽，侧别一翻就是一道斜跳）
    fun sd(s: Float) = if (ny(s) >= 0f) 1f else -1f

    fun fade(s: Float) = abs(ny(s))

    // 一段连续同深度的蛇身
    fun drawRun(i0: Int, i1: Int) {
        // 身：上缘顺着走、下缘倒着回来
        path.rewind()
        for (i in i0..i1) {
            val s = sAt(i)
            if (i == i0) path.moveTo(edgeX(s, -1f, 1f), edgeY(s, -1f, 1f))
            else path.lineTo(edgeX(s, -1f, 1f), edgeY(s, -1f, 1f))
        }
        for (i in i1 downTo i0) {
            val s = sAt(i)
            path.lineTo(edgeX(s, 1f, 1f), edgeY(s, 1f, 1f))
        }
        path.close()
        drawPath(path = path, color = SNAKE_BODY, alpha = alpha * 0.94f)
        // 腹侧那一档暗：圆柱背光的下半边。少了它蛇身是一条等亮的带子
        path.rewind()
        for (i in i0..i1) {
            val s = sAt(i)
            val side = sd(s)
            val inner = 1f - 0.70f * fade(s)
            if (i == i0) path.moveTo(edgeX(s, side, inner), edgeY(s, side, inner))
            else path.lineTo(edgeX(s, side, inner), edgeY(s, side, inner))
        }
        for (i in i1 downTo i0) {
            val s = sAt(i)
            val side = sd(s)
            path.lineTo(edgeX(s, side, 1f), edgeY(s, side, 1f))
        }
        path.close()
        drawPath(path = path, color = Color.Black, alpha = alpha * 0.30f)
        // 背脊高光：偏背侧一条细带。蛇是有光泽的，这一条是它唯一的高光
        path.rewind()
        for (i in i0..i1) {
            val s = sAt(i)
            val side = -sd(s)
            val outer = 0.52f + 0.18f * fade(s)
            if (i == i0) path.moveTo(edgeX(s, side, outer), edgeY(s, side, outer))
            else path.lineTo(edgeX(s, side, outer), edgeY(s, side, outer))
        }
        for (i in i1 downTo i0) {
            val s = sAt(i)
            val side = -sd(s)
            val inner = 0.52f - 0.18f * fade(s)
            path.lineTo(edgeX(s, side, inner), edgeY(s, side, inner))
        }
        path.close()
        drawPath(path = path, color = SNAKE_SCALE, alpha = alpha * 0.44f)
        // 鳞：每隔 6 个采样（= 0.016 的 s，间距与 96 采样时的 3 个相同）横跨蛇身一道向后
        // 开口的弧。**不许画成整圈的圆** —— 第一轮那 26 个 300° 的环读作一串链节或气泡，
        // 而蛇鳞是一排横跨身体的弯边。
        //
        // 只跨**背侧那一半**（从 -0.92 到 +0.15，不再到 +0.92）：背鳞与下面那排腹鳞
        // 原来各占满整个管径、在腹侧叠成一片「K / V」字样的乱纹（第五轮截图里像一行手写字母）。
        // 真蛇也是这样分的 —— 背上是小菱鳞，腹面是一块块横贯的大鳞板，两者在体侧交界
        path.rewind()
        var i = i0
        while (i <= i1) {
            val s = sAt(i)
            val t = thick(s)
            val cx = px(s)
            val cy = py(s)
            val ax = nx(s) * t
            val ay = ny(s) * t
            val back = -sd(s)
            // 沿身方向的后退量：弧的开口朝尾
            val bx = -ay * 0.60f
            val by = ax * 0.60f
            path.moveTo(cx + ax * back * 0.92f, cy + ay * back * 0.92f)
            path.quadraticTo(cx + bx, cy + by, cx - ax * back * 0.15f, cy - ay * back * 0.15f)
            i += 6
        }
        drawPath(
            path = path,
            color = SNAKE_SCALE,
            alpha = alpha * 0.34f,
            style = Stroke(width = (maxThick * 0.13f).coerceAtLeast(1f))
        )
        // 腹鳞：腹侧一排横杠，比背鳞亮。蛇的腹面是一块块横向的大鳞片，
        // 这一排是「这一侧是肚子」的唯一说明。侧别与长度跟着 sd / fade 走，
        // 才不会和上面那条暗带各在一边（那样蛇就有两个肚子了）
        path.rewind()
        i = i0
        while (i <= i1) {
            val s = sAt(i)
            val side = sd(s)
            val from = 0.94f - 0.52f * fade(s)
            path.moveTo(edgeX(s, side, from), edgeY(s, side, from))
            path.lineTo(edgeX(s, side, 0.94f), edgeY(s, side, 0.94f))
            i += 4
        }
        drawPath(
            path = path,
            color = SNAKE_SCALE,
            alpha = alpha * 0.26f,
            style = Stroke(width = (maxThick * 0.11f).coerceAtLeast(1f))
        )
    }

    // 一趟：只画深度对得上的那些段
    fun snakePass(wantBehind: Boolean) {
        if (span <= 0.002f) return
        var i = 0
        while (i <= SNAKE_SAMPLES) {
            if (snakeBehind(sAt(i)) != wantBehind) {
                i++
                continue
            }
            var j = i
            while (j + 1 <= SNAKE_SAMPLES && snakeBehind(sAt(j + 1)) == wantBehind) j++
            // 两头**各多画一个采样**：段与段之间的那一节（`j` 到 `j+1`）深度正好在翻面上，
            // 两趟都不认它，屏幕上就是一道横穿蛇身的背景色缺口
            // （第五轮截图里蛇绕到王座前沿那一处豁了一个黑楔子，约 30px 宽）。
            // 多出来的那一节被另一趟盖住，而盖它的正是更靠前的那一趟 —— 遮挡顺序仍然是对的
            if (j > i) drawRun((i - 1).coerceAtLeast(0), (j + 1).coerceAtMost(SNAKE_SAMPLES))
            i = j + 1
        }
    }

    // 头：颈根在蛇身末端，朝向就是那一点的切线。张口只在「立起来定住」那一段
    val headVisible = span > 0.002f && sHead <= 1f + SNAKE_LEN * 0.55f
    val headBehind = headVisible && snakeBehind(sTip)
    val gape = ((sHead - 0.80f) / 0.10f).coerceIn(0f, 1f) * ((0.94f - sHead) / 0.10f).coerceIn(0f, 1f)
    fun drawHead() {
        val ux = px(sTip + SNAKE_DS) - px(sTip - SNAKE_DS)
        val uy = py(sTip + SNAKE_DS) - py(sTip - SNAKE_DS)
        val len = hypot(ux, uy)
        if (len < 0.001f) return
        drawSnakeSkull(
            path = path,
            hx = px(sTip),
            hy = py(sTip),
            ux = ux / len,
            uy = uy / len,
            hl = h * 0.052f,
            // 头的半高按**最粗处**折算，不跟着 `thick(sTip)` 走 —— 身细了一档之后
            // 若还按颈的口径算，头会跟着缩成管子上一个鼓包（[SNAKE_HEAD_HALF] 有推导）
            hh = maxThick * SNAKE_HEAD_HALF,
            gape = gape,
            phase = phase,
            alpha = alpha
        )
    }

    // 远侧那半圈 → 王座 → 近侧那半圈。顺序就是「绕」的全部实现
    snakePass(wantBehind = true)
    if (headVisible && headBehind) drawHead()
    drawThroneSeat(path, phase, alpha)
    snakePass(wantBehind = false)
    if (headVisible && !headBehind) drawHead()
}

/**
 * 王座：三级台阶 + 高背 + 座面 + 两侧扶手。全屏唯一的金。
 *
 * 整座抬进 [HERO_BOTTOM] 以内 —— 第一轮座面在 0.72h、台阶在 0.87h，
 * 卡片背后只露出高背的上半段，屏幕上读出来是一根金色的方尖碑。
 *
 * 不透明度**不能**用 [SILHOUETTE_ALPHA]（0.52）：再乘上各部件的 0.6–0.8 之后整座王座落在
 * 0.32–0.44，而它背后正压着整屏网点 —— 第四轮截图里网点从座面、背板、台阶里一路透出来，
 * 王座读作一块印着圆点的玻璃。金属是实心的。这一条对**蛇**同样成立：远侧那半圈要被王座
 * 挡住，王座就必须不透明。
 *
 * 所以这一版**一处 alpha 都不用来调明暗**，全部交给 [GOLD] / [GOLD_MID] / [GOLD_DEEP] 三档
 * 颜色 —— 只留 [alpha]（交叉渐变的权重）一个乘数。
 */
private fun DrawScope.drawThroneSeat(path: Path, phase: Float, alpha: Float) {
    val w = size.width
    val h = size.height
    // 不再乘 0.95：[alpha] 本身就是两张背景交叉渐变的权重（它不是
    // [SILHOUETTE_ALPHA] 那类道具系数），完全显示时就是 1f。明暗全交给三档颜色
    val gold = alpha.coerceAtMost(1f)
    val seatY = h * 0.30f
    val seatW = w * 0.30f
    val seatX = w * 0.50f
    // 台阶：三级，越下越宽。越下越深（背光）但一样实心。
    // 三级只有两档颜色（第二三级同色），所以每级上沿再压一条亮金 —— 台阶的前棱受光，
    // 少了它下面两级糊成一块
    val stepColors = arrayOf(GOLD_MID, GOLD_DEEP, GOLD_DEEP)
    for (s in 0 until 3) {
        val stepW = seatW * (1.15f + s * 0.28f)
        val stepY = seatY + h * (0.030f + s * 0.026f)
        drawRect(
            color = stepColors[s],
            topLeft = Offset(seatX - stepW / 2f, stepY),
            size = Size(stepW, h * 0.026f),
            alpha = gold
        )
        drawRect(
            color = GOLD,
            topLeft = Offset(seatX - stepW / 2f, stepY),
            size = Size(stepW, (h * 0.0026f).coerceAtLeast(1f)),
            alpha = gold
        )
    }
    // 高背：窄而高，上缘收成一个尖顶 + 两侧各一个矮尖，是「王座」而不是「墓碑」。
    // 第二轮那块背板宽到 0.84×座宽、上缘三个尖差不多高，屏幕上读作一块五边形石板
    path.rewind()
    path.moveTo(seatX - seatW * 0.32f, seatY)
    path.lineTo(seatX - seatW * 0.32f, h * 0.186f)
    path.lineTo(seatX - seatW * 0.20f, h * 0.166f)
    path.lineTo(seatX - seatW * 0.12f, h * 0.182f)
    path.lineTo(seatX, h * 0.104f)
    path.lineTo(seatX + seatW * 0.12f, h * 0.182f)
    path.lineTo(seatX + seatW * 0.20f, h * 0.166f)
    path.lineTo(seatX + seatW * 0.32f, h * 0.186f)
    path.lineTo(seatX + seatW * 0.32f, seatY)
    path.close()
    drawPath(path = path, color = GOLD_MID, alpha = gold)
    // 背板上两道竖肋：一块纯色的板子没有雕饰，加两道就有「靠背」的分格
    for (i in 0..1) {
        val side = if (i == 0) -1f else 1f
        drawLine(
            color = GOLD_DEEP,
            start = Offset(seatX + side * seatW * 0.14f, h * 0.196f),
            end = Offset(seatX + side * seatW * 0.14f, seatY - h * 0.006f),
            strokeWidth = (w * 0.005f).coerceAtLeast(1f),
            alpha = gold
        )
    }
    // 座面与扶手。扶手要有横向的搭手面，只画一根竖条会读作两根柱子
    drawRect(
        color = GOLD,
        topLeft = Offset(seatX - seatW / 2f, seatY),
        size = Size(seatW, h * 0.030f),
        alpha = gold
    )
    for (i in 0..1) {
        val side = if (i == 0) -1f else 1f
        drawRect(
            color = GOLD_DEEP,
            topLeft = Offset(seatX + side * seatW * 0.52f - seatW * 0.04f, h * 0.235f),
            size = Size(seatW * 0.08f, h * 0.065f),
            alpha = gold
        )
        drawRect(
            color = GOLD,
            topLeft = Offset(
                if (side < 0f) seatX - seatW * 0.56f else seatX + seatW * 0.32f,
                h * 0.235f
            ),
            size = Size(seatW * 0.24f, h * 0.012f),
            alpha = gold
        )
    }
    drawUnitGlow(GLOW_WARM, Offset(seatX, h * 0.21f), w * 0.7f, alpha * 0.16f * (0.94f + 0.06f * sin(phase * TAU)))
}

/**
 * 蛇头：颅 + 眉脊 + 金色竖瞳 + 上下颌 + 分叉舌，张口时露两根毒牙。
 *
 * 全部画在**头自己的坐标系**里：`a` 沿朝向、`b` 横向（`+b` 恒是「屏幕下方」，见 `sgn`）。
 * 所以头永远顺着蛇身的切线长出来，一次都不用手调角度 —— 上一版写死朝左，蛇一转向头就横在身上。
 *
 * 蛇头的辨识特征按重要性排：**竖瞳**（哺乳动物是圆瞳）、**吻端收成铲形**、**眉脊压在眼上**
 * （这一条是「凶」的全部来源）、**分叉的舌**。四样都得有，缺一样就退回「一个灰色的头形」。
 *
 * 下颌是**单独一块**，绕颈根转 [gape] × 0.38 弧度 —— 闭口时两块正好贴上，张口时中间露出
 * 一道黑口腔与两根牙。上一版整个头是一块，张不开嘴，攻击姿态就只剩「头抬起来」。
 */
private fun DrawScope.drawSnakeSkull(
    path: Path,
    hx: Float,
    hy: Float,
    ux: Float,
    uy: Float,
    hl: Float,
    hh: Float,
    gape: Float,
    phase: Float,
    alpha: Float
) {
    // `+b` 必须恒指向**屏幕下方**。横向基是 `(-uy, ux)`，y 分量就是 `ux` —— 蛇绕王座时
    // 有整整半程在往左走（`ux < 0`），那一程「下颌」会画到头顶上去，整个头是倒的。
    // 乘一个符号 = 把头沿自身长轴镜像，左向蛇本来就该是右向蛇的镜像
    val sgn = if (ux >= 0f) 1f else -1f
    val ox = -uy * sgn
    val oy = ux * sgn
    fun fx(a: Float, b: Float) = hx + ux * a * hl + ox * b * hh
    fun fy(a: Float, b: Float) = hy + uy * a * hl + oy * b * hh

    // 下颌的坐标系：绕颈根转出去。转角同号于 sgn，`+a` 才是朝着（镜像后的）`+b` 转
    val jr = gape * 0.38f * sgn
    val jc = cos(jr)
    val js = sin(jr)
    val jux = ux * jc - uy * js
    val juy = uy * jc + ux * js
    val hingeX = fx(-0.10f, 0.12f)
    val hingeY = fy(-0.10f, 0.12f)
    val jox = -juy * sgn
    val joy = jux * sgn
    fun gx(a: Float, b: Float) = hingeX + jux * a * hl + jox * b * hh
    fun gy(a: Float, b: Float) = hingeY + juy * a * hl + joy * b * hh

    // 口腔：张口时上下颌之间那一块黑。先画，两块颌压在它上面
    if (gape > 0.02f) {
        path.rewind()
        path.moveTo(hingeX, hingeY)
        path.lineTo(fx(1.02f, 0.04f), fy(1.02f, 0.04f))
        path.lineTo(gx(0.98f, 0.10f), gy(0.98f, 0.10f))
        path.close()
        drawPath(path = path, color = Color.Black, alpha = alpha * 0.62f * gape)
    }

    // 下颌：颌线在上、颌底在下。
    //
    // 后缘收成一个**尖**（`-0.30, 0.10`），不是一道横切口。两件事一起解决：
    //  1. 描边是沿整条闭合路径走的，横切口会被描出一条亮线横在颈上 ——
    //     屏幕上就是「头接在身子上」的那道缝。收成尖之后闭合边退化成零长，缝没了。
    //  2. 尖落在**蛇身里面**，头根因此整块坐在身子的末端里 —— 颈到头是一路胀开的，
    //     不是「接上去一块」。上一版后缘在 +0.66（横切口），根部 1.87 × maxThick，
    //     比颈（1.24）粗一半，就是「蛇头尾部厚度厚于蛇身」。
    path.rewind()
    path.moveTo(gx(-0.30f, 0.26f), gy(-0.30f, 0.26f))
    path.cubicTo(
        gx(0.24f, 0.10f), gy(0.24f, 0.10f),
        gx(0.72f, 0.12f), gy(0.72f, 0.12f),
        gx(0.98f, 0.06f), gy(0.98f, 0.06f)
    )
    path.cubicTo(
        gx(0.84f, 0.46f), gy(0.84f, 0.46f),
        gx(0.40f, 0.62f), gy(0.40f, 0.62f),
        gx(-0.30f, 0.26f), gy(-0.30f, 0.26f)
    )
    path.close()
    // 0.94 与蛇身**同一档**：头根压在身子上，只要亮一档，那道头形的浅色印子
    // 就比轮廓差出来的那两三像素显眼得多
    drawPath(path = path, color = SNAKE_BODY, alpha = alpha * 0.94f)
    drawPath(path = path, color = Color.Black, alpha = alpha * 0.22f)

    // 上颅：颈背 → 眉脊隆起 → 吻背下坡 → 吻端 → 上唇线收回颈背。
    // 起点与终点是同一个点（-0.34, -0.44），同 [下颌] 的收尖，后缘那道边为零长
    path.rewind()
    path.moveTo(fx(-0.34f, -0.44f), fy(-0.34f, -0.44f))
    path.cubicTo(
        fx(0.06f, -0.90f), fy(0.06f, -0.90f),
        fx(0.50f, -1.04f), fy(0.50f, -1.04f),
        fx(0.80f, -0.58f), fy(0.80f, -0.58f)
    )
    path.cubicTo(
        fx(0.96f, -0.40f), fy(0.96f, -0.40f),
        fx(1.04f, -0.16f), fy(1.04f, -0.16f),
        fx(1.02f, 0.06f), fy(1.02f, 0.06f)
    )
    path.cubicTo(
        fx(0.84f, 0.22f), fy(0.84f, 0.22f),
        fx(0.46f, 0.26f), fy(0.46f, 0.26f),
        fx(-0.34f, -0.44f), fy(-0.34f, -0.44f)
    )
    path.close()
    drawPath(path = path, color = SNAKE_BODY, alpha = alpha * 0.94f)
    drawPath(
        path = path,
        color = SNAKE_SCALE,
        alpha = alpha * 0.34f,
        style = Stroke(width = (hh * 0.10f).coerceAtLeast(1f))
    )

    // 上唇线：沿上颅下缘单独描一道深线。闭口时上下颌两块正好重合，
    // 少了这一条整个头是一块无缝的楔子 —— 嘴在哪里看不出来，头就只是个头形。
    // 起笔从 0.20 起（不是后缘）：后缘那一段已经在蛇身里了，画出来是一道悬在身上的线
    path.rewind()
    path.moveTo(fx(0.20f, 0.24f), fy(0.20f, 0.24f))
    path.cubicTo(
        fx(0.46f, 0.28f), fy(0.46f, 0.28f),
        fx(0.84f, 0.22f), fy(0.84f, 0.22f),
        fx(1.02f, 0.06f), fy(1.02f, 0.06f)
    )
    drawPath(
        path = path,
        color = Color.Black,
        alpha = alpha * 0.34f,
        style = Stroke(width = (hh * 0.09f).coerceAtLeast(1f))
    )

    // 毒牙：张口时从上颌垂下来两根。只在张口时画 —— 闭口的蛇看不见牙
    if (gape > 0.06f) {
        path.rewind()
        for (k in 0..1) {
            val a = 0.80f - k * 0.13f
            path.moveTo(fx(a, 0.10f), fy(a, 0.10f))
            path.lineTo(fx(a - 0.06f, 0.10f + 0.46f * gape), fy(a - 0.06f, 0.10f + 0.46f * gape))
        }
        drawPath(
            path = path,
            color = PAPER_WHITE,
            alpha = alpha * 0.72f * gape,
            style = Stroke(width = (hh * 0.13f).coerceAtLeast(1f), cap = StrokeCap.Round)
        )
    }

    // 眉脊：压在眼睛上方的一道骨棱。蝮蛇的「凶」全在这一条，少了它就是一条温和的水蛇。
    // 三个数跟着上颅那道眉峰走（峰在 a ≈ 0.4、b ≈ -0.85），高出去两三像素才是「棱」
    path.rewind()
    path.moveTo(fx(0.16f, -0.80f), fy(0.16f, -0.80f))
    path.quadraticTo(
        fx(0.44f, -0.98f), fy(0.44f, -0.98f),
        fx(0.66f, -0.64f), fy(0.66f, -0.64f)
    )
    drawPath(
        path = path,
        color = Color.Black,
        alpha = alpha * 0.36f,
        style = Stroke(width = (hh * 0.16f).coerceAtLeast(1f), cap = StrokeCap.Round)
    )

    // 眼：金色眼白 + 竖瞳。竖瞳是蛇眼与所有哺乳动物眼睛唯一的分野。
    // 瞳孔用两段二次曲线拼成一枚梭形，跟着头的朝向一起转 —— 画椭圆的话它永远是正的
    val eyeA = 0.40f
    val eyeB = -0.34f
    val eyeR = 0.30f
    drawCircle(
        color = GOLD,
        radius = hh * eyeR,
        center = Offset(fx(eyeA, eyeB), fy(eyeA, eyeB)),
        alpha = alpha * 0.94f
    )
    path.rewind()
    path.moveTo(fx(eyeA, eyeB - eyeR * 0.86f), fy(eyeA, eyeB - eyeR * 0.86f))
    path.quadraticTo(
        fx(eyeA + eyeR * 0.30f, eyeB), fy(eyeA + eyeR * 0.30f, eyeB),
        fx(eyeA, eyeB + eyeR * 0.86f), fy(eyeA, eyeB + eyeR * 0.86f)
    )
    path.quadraticTo(
        fx(eyeA - eyeR * 0.30f, eyeB), fy(eyeA - eyeR * 0.30f, eyeB),
        fx(eyeA, eyeB - eyeR * 0.86f), fy(eyeA, eyeB - eyeR * 0.86f)
    )
    path.close()
    drawPath(path = path, color = Color.Black, alpha = alpha * 0.90f)

    // 鼻孔：吻背上一个小点。这个尺寸下一个点就够，但少了它吻端是一块光板
    drawCircle(
        color = Color.Black,
        radius = (hh * 0.09f).coerceAtLeast(1f),
        center = Offset(fx(0.88f, -0.24f), fy(0.88f, -0.24f)),
        alpha = alpha * 0.52f
    )

    // 颅顶三片鳞：向后开口的短弧，跟蛇身的鳞是同一种画法（起点跟着上颅的轮廓收进 0.76）
    path.rewind()
    for (s in 0..2) {
        val a = 0.20f + s * 0.19f
        path.moveTo(fx(a, -0.76f), fy(a, -0.76f))
        path.quadraticTo(
            fx(a - 0.13f, -0.42f), fy(a - 0.13f, -0.42f),
            fx(a, -0.04f), fy(a, -0.04f)
        )
    }
    drawPath(
        path = path,
        color = SNAKE_SCALE,
        alpha = alpha * 0.30f,
        style = Stroke(width = (hh * 0.09f).coerceAtLeast(1f))
    )

    // 舌：闭口时才吐。张着嘴还吐舌头是卡通蛇
    if (gape < 0.5f) {
        val flick = 0.34f + 0.34f * sin(phase * TAU * 3f)
        path.rewind()
        path.moveTo(fx(1.00f, 0.10f), fy(1.00f, 0.10f))
        path.lineTo(fx(1.00f + flick * 0.60f, 0.16f), fy(1.00f + flick * 0.60f, 0.16f))
        path.moveTo(fx(1.00f + flick * 0.60f, 0.16f), fy(1.00f + flick * 0.60f, 0.16f))
        path.lineTo(fx(1.00f + flick * 1.05f, -0.10f), fy(1.00f + flick * 1.05f, -0.10f))
        path.moveTo(fx(1.00f + flick * 0.60f, 0.16f), fy(1.00f + flick * 0.60f, 0.16f))
        path.lineTo(fx(1.00f + flick * 1.05f, 0.42f), fy(1.00f + flick * 1.05f, 0.42f))
        drawPath(
            path = path,
            color = SNAKE_TONGUE,
            alpha = alpha * 0.82f * (1f - gape * 2f).coerceIn(0f, 1f),
            style = Stroke(width = (hh * 0.13f).coerceAtLeast(1f), cap = StrokeCap.Round)
        )
    }
}

/** 蛇舌。reputation 全屏只有近黑与灰，这一点暗红与那只金瞳是仅有的两处色。 */
private val SNAKE_TONGUE = Color(0xFF8E2B33)

/**
 * 蛇身与蛇鳞的两档灰。
 *
 * **不能复用 reputation 那三档底色**（`#3A3A3A / #111111 / #000000`）：那张舞台整屏近黑，
 * 拿 `#3A3A3A` 当蛇身画在 `#111111` 上，合出来大约 `#2A2A2A` —— 与背景差不到一档，
 * 第二轮截图里那条蛇只剩鳞的刻痕，身子整条看不见。
 * 这两个值是为「压在近黑上仍读得出体积」挑的：身对底约 3.2:1，鳞对身再亮一档。
 */
private val SNAKE_BODY = Color(0xFF57534E)
private val SNAKE_SCALE = Color(0xFF8A857F)

/**
 * 蛇身在行程曲线上占的长度（`s` 的单位）。
 *
 * 0.65 而不是 0.52：这个数同时决定「屏幕上那条蛇有多长」，因为 `s` 是按弧长分的，
 * 身的可见弧长就是 `0.65 × 全曲线弧长 ≈ 2400px`（1440×3200 上，占屏高四分之三）。
 * 0.52 那一版只有约 1800px，配上 0.0168 的粗，读出来是一条短粗的绳。
 * 绕圈段本身是 0.540，所以 0.65 仍然满足「定格时一圈半缠在座上、尾巴还拖着」。
 */
private const val SNAKE_LEN = 0.65f

/**
 * 蛇身最粗处的**半**管径，按屏高取。
 *
 * 0.0168 是第一版那个「短粗」的口径；0.0110 把身细了三分之一。
 * 细下来还有一层：王座两侧的扶手只有 0.30w 宽，粗管径贴上去会把扶手盖掉。
 */
private const val SNAKE_THICK = 0.0110f

/**
 * 蛇头半高 = 最粗处的半管径 × 这个系数。
 *
 * **头不跟身体的比例走**。头的最大高（颅顶 -0.86 到下颌底 +0.70）约 `1.56 × hh`：
 * 1.18 时是 `1.84 × maxThick` ≈ 65px，比颈（47.9）宽三成半 —— 与真蛇的 1.3–1.8
 * 同量级。头认得出靠的是**特征**（竖瞳、眉脊、毒牙、分叉舌），不是宽度：
 * 这一档再往上加，头会肥成一只鞋。
 *
 * 头的根部不参与接缝的计算：上颅与下颌的后缘都收成一个尖，尖落在蛇身**里面**
 * （见 [drawSnakeSkull]），根部没有可量的宽度，颈到头就是蛇身那道锥度一路胀开。
 * 上一版两条后缘都是横切口、根部合起来 1.87 × maxThick（比颈粗一半），
 * 屏幕上读作颈上接了块比身还粗的疙瘩，就是「蛇头尾部厚度厚于蛇身」。
 *
 * **改这个数要连着 [SNAKE_NECK] 一起改**：头在 `a = 0`（蛇身末端那一点）的外轮廓
 * 是 `1.16 × hh`，必须与颈一样宽，接缝才看不出来。
 */
private const val SNAKE_HEAD_HALF = 1.18f

/**
 * 粗细沿身的四折线：颈 0.68（**最粗处就是颈**，等粗到 u = 0.30）→ 0.56（u = 0.62）
 * → 0.40（u = 0.88）→ 尾尖 0.26。
 *
 * 被点名过四次，三次都是**前后粗细不对**。
 *
 * 第一次是「1.0 一条直线收到 0.16」：可见的那截尾巴正好落在最细处，一路收到 11px，
 * 读作一根线。第二次是「缓收到 0.30、最后 6% 收成尖 0.08」：尾**尖**反而更细了 ——
 * 而屏幕上看得到的尾，恰恰就是尾尖本身（它在 s 上是滑动窗口的末端，永远跟着蛇走）。
 * 第三次是「颈细一档 0.62 → 颈后最粗 1.0」：最粗处落在 u = 0.16，于是
 * **靠头那截反而比后面细**，整条蛇读作一片叶子 —— 两头尖、中间鼓。
 *
 * 所以最粗处放在**颈上**（u = 0）并让前 30% 等粗，之后单调收到尾尖：
 * 47.9 → 47.9px（前 30%）→ 39 → 28 → 18（尾尖）。真蛇就是这个形状，
 * 蛇头后面那一截本来就是全身最粗的地方。
 *
 * 0.68 同时是**接缝值**：蛇身末端要与蛇头的根部齐平。头根整块收尖、落在蛇身
 * 里面，头在 `a = 0`（正是蛇身末端那一点）的外轮廓是 `1.16 × hh` ≈ 48px，
 * 与颈的 47.9px 齐平，屏幕上找不到接缝（见 [SNAKE_HEAD_HALF] 那条约束）。
 */
private const val SNAKE_NECK = 0.68f

/** 等粗段一直到这里。真蛇最粗的一截在头后，不在再往后 16% 的地方。 */
private const val SNAKE_KNEE = 0.30f

private const val SNAKE_MID = 0.56f
private const val SNAKE_MID_KNEE = 0.62f
private const val SNAKE_TAIL = 0.40f
private const val SNAKE_TAIL_KNEE = 0.88f
private const val SNAKE_TIP = 0.26f

/**
 * 蛇身采样点数。窗口是滑动的，所以这是「当前可见那一段」的分段数，不是整条曲线的。
 *
 * 192 而不是 96：蛇身的折线感有两个来源，除了段界上的切线拐折，还有一个是**采样太稀**。
 * 第一版那个椭圆极扁（0.176w × 0.032h），两端的曲率半径只有约 41px（1440×3200 上），
 * 而可见那一段约 1800px 弧长 —— 96 个采样在最弯处一段要转 21°，圈的两端就成了一小段
 * 一小段的折线。现在圈上最紧处约 110px、可见弧长约 2500px（`SNAKE_LEN` = 0.65），
 * 192 个采样在最弯处每段转 6.7°，肉眼已经读作圆弧。代价只是每帧多几百个多边形顶点，
 * 与鳞、腹鳞的绘制调用数无关（那两个按采样**步长**走）。
 */
private const val SNAKE_SAMPLES = 192

/** 求切线用的差分步长。太小会在段界处放大浮点误差，太大则弯处的法向偏出去。 */
private const val SNAKE_DS = 0.0035f

/**
 * 行程曲线三段的 `s` 分界。
 *
 * 按各段的**实际弧长**分配，蛇的速度才是均匀的：进场约 845px、绕圈（约 1.43 圈）2171px、
 * 抬头出画约 892px，合 3908px（1440×3200 上按 4 万个采样点累加相邻点距离量的）。
 * 改椭圆半径、收尾角、进场/出画曲线的控制量或出画点，都要跟着重算这两个数，
 * 不然蛇会在某一段忽然加速 —— 绕圈那段尤其非线性：半轴动一点，它的弧长就变一截。
 */
private const val SNAKE_S_ENTER = 0.216f
private const val SNAKE_S_LOOP = 0.772f

/**
 * 绕王座那个椭圆：中心、两半轴、起始高度、扫过的角（1.5 圈减去 [SNAKE_LOOP_TRIM]）。
 *
 * **这一版的两半轴是「按弯得动」挑的，不是按好看挑的。** 蛇身有半个管径的厚度，
 * 轨迹的曲率半径只要掉到半管径量级，内侧边缘就会自己叠起来 —— 屏幕上读作一个折角，
 * 而不是一条急弯。所以先量曲率半径，再挑半径：老椭圆（0.176w × 0.032h，且 1.5 圈
 * 硬拧到最右点）在两端只有 38px 半径，而那时半管径 27px —— 正好卡在折叠的边缘上。
 * 0.070 的高半轴 + 提前收尾后，绕圈段最紧处约 110px，是半管径的三倍。
 *
 * 高度也受了卡片的约束：这条螺纹最低的那半圈（`b = 1/6`，θ = 1.5π）落在
 * `CY − RISE/6 + RY`，要压在卡片上沿（0.40h）之上 —— 半轴一大就会钻到卡片背后去。
 */
private const val SNAKE_LOOP_CX = 0.500f
private const val SNAKE_LOOP_RX = 0.182f
private const val SNAKE_LOOP_RY = 0.070f
private const val SNAKE_LOOP_CY = 0.320f
private const val SNAKE_LOOP_RISE = 0.140f

/**
 * 绕几圈。1.5 而不是整数：从最左（θ = π）起转 1.5 圈正好停在**最右**，
 * 接下来往右、斜着出画一路顺着走。整数圈会停回最左边，出画得先横穿王座。
 * 实际只转 `1.5 圈 − [SNAKE_LOOP_TRIM]`（见那条注释：停最右点的切线出画段接不住）。
 */
private const val SNAKE_TURNS = 1.5f

/**
 * 线圈**提前**收尾的角（弧度）。0 就是「正好停在最右点」。
 *
 * 停在最右点看着最整齐，但那一点的切线是**竖直向下**的：出画段要从这里接到「往右上走」，
 * 得原地反折约 163°，贝塞尔在这种反折上会塌成一个半径 10px 的尖 —— 就是上一版颈部
 * 那道折。提前 0.80rad（约 46°）收尾，蛇身是在椭圆右上角、**斜向下右**离开线圈的，
 * 出画段顺着这个方向续上去，整条颈部只有一个方向的转向，最紧处从 10px 变成约 150px。
 *
 * 收尾点因此落在 `x = CX + RX·cos(46°) ≈ 0.627w`、`y ≈ 0.130h` —— 王座的背板轮廓
 * 在那个高度只到 0.51w（上缘收成尖顶），所以「远侧 → 近侧」的切换点仍在轮廓之外，
 * 不会出现身体从背板面上穿出来。
 */
private const val SNAKE_LOOP_TRIM = 0.80f

/** `[SNAKE_LOOP_TRIM]` 之后绕圈段实际扫过的角。三个三角函数共用。 */
private val SNAKE_LOOP_SPAN: Float = SNAKE_TURNS * TAU - SNAKE_LOOP_TRIM

/** reputation 是第 6 张。写成常量是为了让下面那行现算的段长有个名字。 */
private const val REP_INDEX = 5

/**
 * reputation 这一段的总时长。
 *
 * **现算，不抄常量**：卡片时长是按曲目数派生的（[SwiftieTimeline.cardDurationMs]），
 * 哪天 reputation 的曲目表改一首，这条行程的四个节拍就全要跟着挪。
 */
private val REP_ERA_MS: Float =
    SwiftieTimeline.cardDurationMs(REP_INDEX, SwiftieTimeline.ERA_TRACK_COUNTS[REP_INDEX]).toFloat()

/**
 * 头位置随本段进度的四段速度（[SNAKE_KEY_P] 是进度、[SNAKE_KEY_S] 是曲线参数）。
 *
 * 四段的速度分别是 0.98 / 1.54 / 0.43 / 3.33（s 每单位进度）—— 故意不平滑：
 * 「游进来 → 缠上去 → **几乎停住立起头** → 一甩出画」，那个停顿就是攻击姿态的定格，
 * 也是这段唯一让人看清蛇头的时刻。匀速走完全程只会读作一条传送带上的绳子。
 */
private val SNAKE_KEY_P = floatArrayOf(0f, 0.22f, 0.58f, 0.76f, 1f)
private val SNAKE_KEY_S = floatArrayOf(0f, SNAKE_S_ENTER, SNAKE_S_LOOP, 0.850f, 1f + SNAKE_LEN)

/** 分段线性查表：把 0f..1f 的段内进度换成蛇头在曲线上的位置。 */
private fun snakeHeadAt(p: Float): Float {
    if (p <= 0f) return 0f
    if (p >= 1f) return SNAKE_KEY_S[SNAKE_KEY_S.size - 1]
    for (i in 1 until SNAKE_KEY_P.size) {
        if (p <= SNAKE_KEY_P[i]) {
            val t = (p - SNAKE_KEY_P[i - 1]) / (SNAKE_KEY_P[i] - SNAKE_KEY_P[i - 1])
            return SNAKE_KEY_S[i - 1] + (SNAKE_KEY_S[i] - SNAKE_KEY_S[i - 1]) * t
        }
    }
    return SNAKE_KEY_S[SNAKE_KEY_S.size - 1]
}

/** 绕圈段的角度。从最左（π）起按 [SNAKE_LOOP_SPAN] 转（不足 1.5 圈，见 [SNAKE_LOOP_TRIM]）。 */
private fun snakeLoopAngle(b: Float): Float = PI.toFloat() + b * SNAKE_LOOP_SPAN

/**
 * 线圈末端那一点，也是出画段的**起点**。
 *
 * 提前收尾之后它不再等于椭圆最右点（`CX + RX`）—— 两段必须共用这一个值，
 * 否则接缝上蛇身会断开（`snakePointX/Y` 的出画分支以前直接写 `CX + RX`）。
 */
private val SNAKE_LOOP_END_X: Float = SNAKE_LOOP_CX + cos(snakeLoopAngle(1f)) * SNAKE_LOOP_RX
private val SNAKE_LOOP_END_Y: Float =
    SNAKE_LOOP_CY - SNAKE_LOOP_RISE + sin(snakeLoopAngle(1f)) * SNAKE_LOOP_RY

/**
 * 一段三次贝塞尔的取值。进场段与抬头出画那两截都用它：两端的控制点落在椭圆端点的
 * 切线上，接缝才不拐折（见 [snakePointX] 的说明）。
 */
private fun cubicAt(t: Float, p0: Float, p1: Float, p2: Float, p3: Float): Float {
    val inv = 1f - t
    return inv * inv * inv * p0 + 3f * inv * inv * t * p1 + 3f * inv * t * t * p2 + t * t * t * p3
}

/**
 * 行程曲线的横坐标（屏宽的比例）。
 *
 * 三段拼起来，**段界处两段的值必须相等**，不然蛇身会在接缝上断开：
 * 进场段末端 = 椭圆最左点，绕圈段末端 = 出画段起点 = [SNAKE_LOOP_END_X]。
 * 除了位置，**切线也要接上**：进场段末端的控制点压在最左点下方、出画段首端的控制点
 * 沿线圈末端切线方向拉出去（[SNAKE_EXIT_KICK_X]）—— 见 [drawHalftoneThrone] 的说明。
 * `s` 允许略微超出 `0..1`（求切线要取 `s ± SNAKE_DS`），两头都按同一个式子外推。
 */
private fun snakePointX(s: Float): Float = when {
    s <= SNAKE_S_ENTER -> cubicAt(
        s / SNAKE_S_ENTER,
        SNAKE_ENTER_X,
        SNAKE_ENTER_X + SNAKE_ENTER_PULL_X,
        SNAKE_LOOP_CX - SNAKE_LOOP_RX,
        SNAKE_LOOP_CX - SNAKE_LOOP_RX
    )
    s <= SNAKE_S_LOOP -> {
        val b = (s - SNAKE_S_ENTER) / (SNAKE_S_LOOP - SNAKE_S_ENTER)
        SNAKE_LOOP_CX + cos(snakeLoopAngle(b)) * SNAKE_LOOP_RX
    }
    else -> cubicAt(
        (s - SNAKE_S_LOOP) / (1f - SNAKE_S_LOOP),
        SNAKE_LOOP_END_X,
        SNAKE_LOOP_END_X + SNAKE_EXIT_KICK_X,
        SNAKE_EXIT_X - SNAKE_EXIT_PULL,
        SNAKE_EXIT_X
    )
}

/** 行程曲线的纵坐标（屏高的比例）。见 [snakePointX] 的段界要求。 */
private fun snakePointY(s: Float): Float = when {
    s <= SNAKE_S_ENTER -> cubicAt(
        s / SNAKE_S_ENTER,
        SNAKE_ENTER_Y,
        SNAKE_ENTER_Y + SNAKE_ENTER_PULL_Y,
        SNAKE_LOOP_CY + SNAKE_ENTER_ARRIVE,
        SNAKE_LOOP_CY
    )
    s <= SNAKE_S_LOOP -> {
        val b = (s - SNAKE_S_ENTER) / (SNAKE_S_LOOP - SNAKE_S_ENTER)
        SNAKE_LOOP_CY - b * SNAKE_LOOP_RISE + sin(snakeLoopAngle(b)) * SNAKE_LOOP_RY
    }
    else -> cubicAt(
        (s - SNAKE_S_LOOP) / (1f - SNAKE_S_LOOP),
        SNAKE_LOOP_END_Y,
        SNAKE_LOOP_END_Y + SNAKE_EXIT_KICK_Y,
        SNAKE_EXIT_Y - SNAKE_EXIT_PULL * SNAKE_EXIT_SLOPE,
        SNAKE_EXIT_Y
    )
}

/**
 * 这一点是不是在王座**背后**。
 *
 * 只有绕圈段的远半圈（`sin θ < 0`，椭圆的上半边）在后面，其余一律在前。
 * 「绕」与「压在上面」的差别只在这一个判断上。
 */
private fun snakeBehind(s: Float): Boolean {
    if (s <= SNAKE_S_ENTER || s > SNAKE_S_LOOP) return false
    val b = (s - SNAKE_S_ENTER) / (SNAKE_S_LOOP - SNAKE_S_ENTER)
    return sin(snakeLoopAngle(b)) < 0f
}

/** 进场点：屏外左侧 0.22w、台阶那个高度。出画点：屏外右侧 1.24w。 */
private const val SNAKE_ENTER_X = -0.22f
private const val SNAKE_ENTER_Y = 0.360f
private const val SNAKE_EXIT_X = 1.240f

/**
 * 出画点的高度。0.150 而不是上一版的 0.232：线圈末端抬到 0.130h 之后，出画点再压到
 * 0.23h 的话整条颈一路往下沉、头几乎贴着王座扶手；抬高到 0.150h，颈才是「从线圈上
 * 探出去、末了再往上抬一点」，在屏上的位置与上一版那个立起头的定格几乎重合
 * （实测定格时刻头在 0.76w、0.19h，上一版约 0.75w、0.19h）。
 */
private const val SNAKE_EXIT_Y = 0.150f

/**
 * 进场段（三次贝塞尔）的三个控制量。
 *
 * [SNAKE_ENTER_PULL_X] 是首端控制点的水平牵引：0.30w 让这一截大体是平推进来的，
 * 只在最后压出一个「沉一下再上来」的弧。[SNAKE_ENTER_ARRIVE] 是末端控制点相对椭圆
 * 最左点**向下**的距离 —— 就靠它在末端把切线掰成竖直，接上椭圆在那一点的切线。
 * 少了它（或改小）接缝上就是一道折角：进场是横着来的，绕圈却一上来就往上走。
 */
private const val SNAKE_ENTER_PULL_X = 0.300f
private const val SNAKE_ENTER_PULL_Y = 0.020f
private const val SNAKE_ENTER_ARRIVE = 0.050f

/**
 * 出画段（三次贝塞尔）的三个控制量。
 *
 * 首端控制点**落在绕圈段末端的切线上**（[SNAKE_EXIT_KICK_X] / [SNAKE_EXIT_KICK_Y]），
 * 这是这一版的全部要害：提前收尾之后蛇身是斜向下右离开线圈的（约 34°），出画段顺着
 * 这个方向拉出去，整条颈部只有一个方向的转向。上一版把首端控制点放在「竖直向下」，
 * 而末端在 0.42w 外、比出圈点还高 0.126h —— 曲线得先下、再上、最后又往下，
 * 两次反折把贝塞尔压成半径 10px 的尖，就是颈部那道折。
 *
 * **两个 kick 常量必须一起改**：它们不是「长度」而是「位移分量」，比例等于线圈末端
 * 切线的方向（按 1440×3200 折算；与 [SNAKE_S_ENTER] 一样是随半轴/收尾角重算的常数）。
 * 改 [SNAKE_LOOP_TRIM] 之后要重新算这两个数，否则端点切线又会不一致。
 */
private const val SNAKE_EXIT_KICK_X = 0.0875f
private const val SNAKE_EXIT_KICK_Y = 0.0218f

/**
 * 末端：从出画点往回 0.42w、向上 0.042h，于是末端切线是「右略偏下 5.7°」——
 * 头和颈的收尾基本是平着探出去，不再像上一版那样一路往右上抬到 55°。
 *
 * 斜率是量出来的，不是挑出来的：0.10 时出画段最紧处约 150px（半管径在颈部约 25px，
 * 六倍余量）；斜率再往负走（末端越抬越高），曲率会迅速收紧 —— −0.20 时只剩 91px、
 * −0.30 时 63px，重新回到会看出折角的量级。
 */
private const val SNAKE_EXIT_PULL = 0.420f
private const val SNAKE_EXIT_SLOPE = 0.100f

// ─────────────────────── 7 · Lover ───────────────────────

/** 彩虹的六道色。Lover 的 pastel 版本，饱和度压得很低，不然一道彩虹会抢掉整屏。 */
private val RAINBOW = listOf(
    Color(0xFFF3A8B8),
    Color(0xFFF6C79A),
    Color(0xFFF3E7A0),
    Color(0xFFAEDCB4),
    Color(0xFFA8C8E8),
    Color(0xFFC9AEDE)
)

/** 一朵云的五团：(dx 相对冠幅, dy, 半径系数)。见 [PORCH_CROWN] 的注释，不在 draw 里建 List。 */
private val CLOUD_PUFFS = floatArrayOf(
    0.00f, 0.00f, 1.00f,
    0.62f, 0.16f, 0.68f,
    -0.58f, 0.18f, 0.62f,
    0.28f, -0.22f, 0.72f,
    -0.24f, -0.16f, 0.60f
)

/** Lover House 两扇窗的横向位置（相对屋宽）。 */
private val HOUSE_WINDOWS = floatArrayOf(-0.30f, 0.30f)

/** 烟囱单独用冷灰蓝，不再和屋顶共用半透明颜色叠成一块脏色。 */
private val HOUSE_CHIMNEY = Color(0xFF8FA7C7)

/** Lover House 的屋宽（占屏宽）与檐口高度（占屏高）。屋顶那颗心的位置从这两个数推。 */
private const val LOVER_HOUSE_W = 0.36f

private const val LOVER_EAVE_Y = 0.30f

/**
 * 屋顶那颗心的方框。
 *
 * 单独抽出来是因为**三层都要它**：背景这一层画心，页面最上层那 900ms 的飞行段要拿它当
 * 落点（[SwiftieLoverArrowFlight]），插在心上那支箭的位置又要从它推。各写一份必然错开，
 * 而错开一点在屏幕上就是「箭插在心旁边的空气里」。
 */
internal fun swiftieLoverHeartBox(size: Size): Rect {
    val houseW = size.width * LOVER_HOUSE_W
    val side = houseW * 0.34f
    val cx = size.width * 0.50f
    // 檐口往上：屋顶尖 0.42 个屋宽，再往上留 0.78 个心高
    val cy = size.height * LOVER_EAVE_Y - houseW * 0.42f - side * 0.78f
    return Rect(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
}

/**
 * 那颗心的体积色：左上受光的浅粉 → 右下背光的深玫红。
 *
 * 单位方框里建一次（见本节开头那条铁律）。渐变的圆心偏在 0.36/0.30 而不是正中 ——
 * 光从左上来，高光就该偏在左上；居中的径向渐变读作一个发光的贴纸，没有体积。
 */
private val HEART_BODY: Brush = Brush.radialGradient(
    0.00f to Color(0xFFFFD3E2),
    0.42f to Color(0xFFF57FA6),
    1.00f to Color(0xFFC93C68),
    center = Offset(0.36f, 0.30f),
    radius = 0.82f
)

/**
 * 一个 0f..1f 的确定性抖动，只用来给「同一个模板重复很多次」的东西错开尺寸。
 *
 * 不用 [Random]：这是 draw 阶段每帧都走的路，`Random(seed)` 每次都是一次分配。
 * 这个式子是图形学里那条老掉牙的 `fract(sin(x) * 43758.5453)`，无分配、纯算术，
 * 同一个 [i] 永远给同一个数 —— 云不会每帧变大小。
 */
private fun jitter01(i: Int): Float {
    val v = sin(i * 12.9898f) * 43758.547f
    return v - kotlin.math.floor(v)
}

/**
 * pastel 云海 + 一道彩虹 + 远景 Lover House + 屋顶那颗心。
 *
 * *Lover* MV 整支设定在那栋粉蓝小屋里。这里的房子是**远景**、只有轮廓与窗光 ——
 * 精细的那一栋在 `SwiftieSnowGlobe` 的球内，收尾那 6.5 秒才登场，两处不能长一样。
 * 屋顶那颗心按 [phase] 呼吸，命中之后带一支箭（见 [drawLoverHeart]）。
 */
private fun DrawScope.drawPastelRainbowHouse(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float,
    eraMs: Long,
    card: Rect
) {
    val w = size.width
    val h = size.height

    // 彩虹：六道同心弧，画在云层之下，所以云能压住虹脚。
    // 圆心抬到 0.46h：第一轮钉在 0.62h，虹顶正好落在卡片顶边下面，屏幕上只剩一道糊边
    val arcR = w * 0.62f
    val arcCenter = Offset(w * 0.52f, h * 0.46f)
    RAINBOW.forEachIndexed { i, band ->
        val r = arcR - i * w * 0.026f
        drawArc(
            color = band,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(arcCenter.x - r, arcCenter.y - r),
            size = Size(r * 2f, r * 2f),
            alpha = alpha * 0.44f,
            style = Stroke(width = w * 0.028f)
        )
    }

    // 云海：三排，每朵是五团**并成一条轮廓**再一次填完（见 blobInto —— 分开画会读作肥皂泡）。
    // 每朵的冠幅按 jitter01 错开 ±18%，否则一排等大的云就是一条花边。
    // yScale 0.52：并起来的正圆团是灌木，云必须宽而扁
    val cloudRows = floatArrayOf(0.07f, 0.15f, 0.235f)
    var cloudSeed = 0
    cloudRows.forEachIndexed { row, cy ->
        val puffR = w * (0.125f - row * 0.018f)
        // 云整体随 phase 横向平移一个完整的间距，绕回时正好错开一朵，接得上
        val stride = w * 0.30f
        var cx = -puffR * 2f + (row * w * 0.10f) + (phase * stride) % stride
        while (cx < w + puffR * 2f) {
            val wobble = 0.82f + 0.36f * jitter01(cloudSeed++)
            blobInto(path, CLOUD_PUFFS, cx, cy * h, puffR * wobble, yScale = 0.52f)
            drawPath(
                path = path,
                color = if (row == 0) top else mid,
                alpha = alpha * (0.50f - row * 0.10f)
            )
            cx += stride
        }
    }

    // Lover House（远景）：墙 + 尖顶 + 门 + 两扇亮着的窗。
    // 抬到 HERO_BOTTOM 以内 —— 原来在 0.70h，整栋压在卡片背后，等于没画。
    // 屋宽与檐口走 LOVER_HOUSE_W / LOVER_EAVE_Y：屋顶那颗心的位置由 swiftieLoverHeartBox
    // 从同两个数推，而那个函数飞行段与卡片也在调 —— 这里写死一个 0.36f 就会两边错开
    val houseW = w * LOVER_HOUSE_W
    val houseX = w * 0.50f
    val eaveY = h * LOVER_EAVE_Y
    val wallH = h * 0.075f
    drawRect(
        color = deep,
        topLeft = Offset(houseX - houseW / 2f, eaveY),
        size = Size(houseW, wallH),
        alpha = alpha * 0.42f
    )
    // 烟囱先画在屋顶后面；底边改成屋顶右斜边的同一条线，避免水平矩形压进屋面。
    val chimneyLeftX = houseX + houseW * 0.255f
    val chimneyRightX = houseX + houseW * 0.345f
    val chimneyBottomLeftY = eaveY - houseW * 0.42f * (1f - 0.255f / 0.62f)
    val chimneyBottomRightY = eaveY - houseW * 0.42f * (1f - 0.345f / 0.62f)
    val chimneyTopY = chimneyBottomLeftY - houseW * 0.12f
    path.rewind()
    path.moveTo(chimneyLeftX, chimneyTopY)
    path.lineTo(chimneyRightX, chimneyTopY)
    path.lineTo(chimneyRightX, chimneyBottomRightY)
    path.lineTo(chimneyLeftX, chimneyBottomLeftY)
    path.close()
    drawPath(
        path = path,
        color = HOUSE_CHIMNEY,
        alpha = alpha * 0.72f
    )
    path.rewind()
    path.moveTo(houseX, eaveY - houseW * 0.42f)
    path.lineTo(houseX - houseW * 0.62f, eaveY)
    path.lineTo(houseX + houseW * 0.62f, eaveY)
    path.close()
    drawPath(path = path, color = deep, alpha = alpha * 0.52f)
    // 门
    drawRect(
        color = deep,
        topLeft = Offset(houseX - houseW * 0.07f, eaveY + wallH * 0.42f),
        size = Size(houseW * 0.14f, wallH * 0.58f),
        alpha = alpha * 0.7f
    )
    for (i in HOUSE_WINDOWS.indices) {
        drawLitWindow(
            left = houseX + houseW * HOUSE_WINDOWS[i] - houseW * 0.075f,
            top = eaveY + wallH * 0.18f,
            width = houseW * 0.15f,
            height = wallH * 0.30f,
            frame = deep,
            lit = 1f,
            alpha = alpha * 0.9f
        )
    }
    // 门上只留一条窄檐线和门把手，不再画宽大的梯形雨篷；
    // 那个形状会在远景低透明度下读成房子里面摆了一张桌子。
    drawLine(
        color = deep,
        start = Offset(houseX - houseW * 0.105f, eaveY + wallH * 0.36f),
        end = Offset(houseX + houseW * 0.105f, eaveY + wallH * 0.36f),
        strokeWidth = (h * 0.0028f).coerceAtLeast(1f),
        alpha = alpha * 0.48f,
        cap = StrokeCap.Round
    )
    drawCircle(
        color = HOUSE_CHIMNEY,
        radius = (h * 0.0035f).coerceAtLeast(1f),
        center = Offset(houseX + houseW * 0.035f, eaveY + wallH * 0.68f),
        alpha = alpha * 0.82f
    )
    // 栅栏：两道横杆 + 疏一点的立柱。第二轮立柱间距 0.075×屋宽、宽 0.016，
    // 屏幕上是一条拉链
    val fenceY = eaveY + wallH
    val fenceLeft = houseX - houseW * 0.95f
    val fenceRight = houseX + houseW * 0.95f
    for (rail in 0..1) {
        drawLine(
            color = deep,
            start = Offset(fenceLeft, fenceY + wallH * (0.10f + rail * 0.11f)),
            end = Offset(fenceRight, fenceY + wallH * (0.10f + rail * 0.11f)),
            strokeWidth = (h * 0.0022f).coerceAtLeast(1f),
            alpha = alpha * 0.40f
        )
    }
    var picket = fenceLeft
    while (picket <= fenceRight) {
        drawRect(
            color = deep,
            topLeft = Offset(picket, fenceY + wallH * 0.04f),
            size = Size(houseW * 0.030f, wallH * 0.24f),
            alpha = alpha * 0.40f
        )
        picket += houseW * 0.13f
    }

    // 屋顶那颗心。呼吸用 sin(phase*TAU)，一倍频，绕回时连续
    val pulse = 0.68f + 0.32f * sin(phase * TAU)
    drawLoverHeart(path, swiftieLoverHeartBox(size), pulse, alpha, eraMs, card)
    drawFogBand(HERO_BOTTOM, 0.16f, top, alpha * DISTANT_ALPHA * 1.8f)
}

/**
 * 屋顶那颗心 + 插在它上面的那支箭。
 *
 * ## 为什么不再是一根霓虹灯管
 *
 * 上一版是 `Stroke(width = 0.10f)` 描的一颗心 —— 霓虹灯管的读法没错，但需求方要的是
 * 「立体感」和「箭穿过的层级感」：**一条描边没有体积可穿**，箭压上去只是两根线交叉。
 * 所以改成有厚度的实心心：[HEART_BODY] 给左上受光 / 右下背光的体积，左上一团圆高光
 * 交代釉面，右下一道反光边交代背光那一侧的转折，外圈光晕与灯管亮边都留着 ——
 * 远景里它仍要读作屋顶上一块**发光的**招牌，而不是一颗贴纸。
 *
 * ## 层级
 *
 * 光晕 → 整支箭 → 心 → 箭的后半段（`0.49..1`）再来一遍。中段被心挡住、尾巴那一截又压
 * 回心的上面，于是读作「从这一面插进去、从那一面穿出来」。少了最后一步箭就是躺在心
 * 背后的一根棍子。0.49 是心的后缘落在箭身上的位置（心的半宽约 0.20 个箭长、
 * 箭尖停在中心前 0.10 个箭长处）。
 *
 * 命中那一下心整体抖 [LOVER_RECOIL_MS]：`(1-t)·sin(2.2 圈)` 的衰减摆，同时带一下缩放与
 * 旋转（箭一起转，它插在心上）。没有这一下，箭是「出现」在心上而不是「射」进去的。
 *
 * @param eraMs Lover 这一段已过的毫秒。小于 [LOVER_HIT_MS] = 还没命中，心是完整的
 * @param card Lover 卡片在根坐标里的边框，只用来推箭长（三层同一个箭长）。
 *   [Rect.Zero] = 还没量到，退回按心的尺寸估，宁可短一点也不要这一帧没有箭
 */
private fun DrawScope.drawLoverHeart(
    path: Path,
    heart: Rect,
    pulse: Float,
    alpha: Float,
    eraMs: Long,
    card: Rect
) {
    val side = heart.width
    val stuck = eraMs >= LOVER_HIT_MS
    // 命中后的衰减摆：0 → 1 走完 420ms。摆 2.2 圈，幅度线性收干
    val recoil = if (!stuck) {
        0f
    } else {
        val t = ((eraMs - LOVER_HIT_MS) / LOVER_RECOIL_MS).coerceIn(0f, 1f)
        (1f - t) * sin(t * TAU * 2.2f)
    }
    val length = if (card.width > 0f) {
        swiftieLoverStuckLength(swiftiePropBox(card.size))
    } else {
        side * 2.52f
    }
    val aim = swiftieLoverAim(card, size)
    val tip = swiftieLoverStuckTip(heart, length, aim.unit)

    withTransform({
        if (recoil != 0f) {
            rotate(degrees = recoil * 5.0f, pivot = heart.center)
            val s = 1f + recoil * 0.085f
            scale(s, s, pivot = heart.center)
        }
    }) {
        // 光晕：命中那一下再亮一档（flash 随 recoil 的绝对值走，不跟着摆的正负闪）
        val flash = if (stuck) abs(recoil) else 0f
        drawUnitGlow(
            GLOW_WHITE,
            Offset(heart.center.x, heart.center.y + side * 0.1f),
            side * (3.4f + flash * 1.1f),
            alpha * (0.40f + flash * 0.30f) * pulse
        )
        if (stuck) drawArcheryArrow(tip, aim.angle, length, alpha * 0.95f)
        withTransform({
            translate(heart.left, heart.top)
            scale(side, side, pivot = Offset.Zero)
        }) {
            // 体积：单位空间里的径向渐变，光心偏左上
            drawPath(path = UNIT_HEART, brush = HEART_BODY, alpha = alpha * 0.94f)
            // 灯管亮边：原来那一版的全部内容，现在降级成心的一圈边光。
            // 线宽在单位空间里，所以它跟着心一起缩放，改 side 不用重算
            drawPath(
                path = UNIT_HEART,
                color = SwiftiePalette.Glitter,
                alpha = alpha * (0.42f + 0.34f * pulse),
                style = Stroke(width = 0.055f, cap = StrokeCap.Round)
            )
        }
        // 釉面高光：左上那一团。圆的 —— 高光是光源在曲面上的像，跟心的轮廓无关
        drawUnitGlow(
            GLOW_WHITE,
            Offset(heart.left + side * 0.33f, heart.top + side * 0.30f),
            side * 0.46f,
            alpha * 0.62f
        )
        // 不再画右半边的白色反光弧。那条弧会在粉色釉面里读成一块突兀的白色月牙，
        // 还会和穿心箭杆争夺视觉中心；保留左上高光与外轮廓，瓷釉体积已经足够。
        // 穿透：靠近尾巴那一截压回心的上面。压之前先在心面上落一道**箭的影子** ——
        // 箭杆浮在心的曲面之上，没有这道影子它只是画在心上的一条白线
        if (stuck) {
            val shade = Offset(side * 0.038f, side * 0.050f)
            drawLine(
                color = HEART_SHADOW,
                start = heart.center + shade,
                end = heart.center - aim.unit * (side * 0.46f) + shade,
                strokeWidth = side * 0.055f,
                alpha = alpha * 0.22f
            )
            // 箭尾重新浮到心面上：杆从心的入射口（中央凹口那一带）往下穿过心体，出心之后
            // 继续露出尾羽。**只补杆尾这一截，不再补箭镞**。
            //
            // 上一版还在前景补了一遍箭镞（`from = 0f, to = 0.17f`），理由是「否则镞嵌进心里
            // 会被釉面盖住」—— 那是把结果当成了前提：真机截图里它读作**一颗小粉心贴在
            // 大粉心的正面**，正是「没有穿刺」的来源。现在箭镞故意留在心后：尖端埋在
            // 心体里看不见，镞的下半从心的下缘探出来，穿心由「杆入心、镞出心」成立。
            drawArcheryArrow(tip, aim.angle, length, alpha * 0.95f, from = 0.60f)
        }
    }
    // 迸光在回弹的变换之外画：它是空气里的光，不跟着心一起晃
    if (stuck) {
        drawHeartImpact(
            path = path,
            // 入射点落在心的边上（0.42 个心宽），不是心里 —— 光要从「扎进去的那个口」冒出来
            entry = heart.center - aim.unit * (side * 0.42f),
            side = side,
            sinceHit = (eraMs - LOVER_HIT_MS).toFloat(),
            angle = aim.angle,
            alpha = alpha
        )
    }
}

/** 箭在心面上那道影子的颜色。比心的暗部再深一档的玫红，不是灰 —— 灰影子读作脏。 */
private val HEART_SHADOW = Color(0xFF8E2A4C)

/** 命中反馈的完整时长：接触、折射、碎光三段收在同一拍里。 */
private const val HEART_IMPACT_MS = 560f

/** 玻璃釉面的玫粉边，不用金色星芒抢走 Lover 的粉。 */
private val IMPACT_ROSE = Color(0xFFFF9EBD)

/** 玻璃折射出来的奶白边。 */
private val IMPACT_PEARL = Color(0xFFFFF2F6)

/** 入射缝的深玫色。 */
private val IMPACT_DEEP = Color(0xFFD75C86)

/** 玻璃碎片的粉白亮面。 */
private val IMPACT_SHARD = Color(0xFFFFC9DB)

/** 只在命中反馈里使用：先快后慢，避免机械匀速。 */
private fun impactEaseOut(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    val inverse = 1f - t
    return 1f - inverse * inverse * inverse
}

/**
 * 箭扎进糖釉爱心的反馈：**入射缝 + 折射弧 + 少量玻璃碎片**。
 *
 * 不再画平均分布的四芒星，也不做一圈廉价爆闪。命中点先出现一道与箭身垂直的细缝，
 * 两组不闭合的弧线像玻璃受力后的折射边从缝旁展开，最后只留四枚短短的圆头碎片。
 * 所有亮部都围绕入射点，不覆盖箭头主体；箭本身的尺寸、角度、层次完全不被动效改变。
 */
private fun DrawScope.drawHeartImpact(
    path: Path,
    entry: Offset,
    side: Float,
    sinceHit: Float,
    angle: Float,
    alpha: Float
) {
    val t = sinceHit / HEART_IMPACT_MS
    if (t < 0f || t >= 1f) return

    val fade = (1f - t) * (1f - t)
    val ringT = impactEaseOut(((t - 0.035f) / 0.72f).coerceAtLeast(0f))
    val shardT = impactEaseOut(((t - 0.10f) / 0.56f).coerceAtLeast(0f))
    val seamT = (1f - t / 0.24f).coerceIn(0f, 1f)
    val axis = Offset(cos(angle), sin(angle))
    val tangent = Offset(-axis.y, axis.x)
    val center = entry - axis * (side * 0.012f)
    val axisDegrees = angle * 180f / PI.toFloat()

    // 接触瞬间是一团很克制的釉面亮，不再用实心白点制造廉价闪烁。
    drawUnitGlow(
        GLOW_WHITE,
        center,
        side * (0.22f + 0.34f * ringT),
        alpha * 0.20f * fade
    )

    // 入射缝：它是箭穿入心面的证据，方向始终垂直于箭身。
    if (seamT > 0f) {
        val seamHalf = side * (0.065f + 0.025f * seamT)
        drawLine(
            IMPACT_DEEP,
            center - tangent * seamHalf,
            center + tangent * seamHalf,
            strokeWidth = side * 0.020f,
            cap = StrokeCap.Round,
            alpha = alpha * 0.62f * seamT
        )
        drawLine(
            IMPACT_PEARL,
            center - tangent * (seamHalf * 0.64f) - axis * (side * 0.014f),
            center + tangent * (seamHalf * 0.64f) - axis * (side * 0.014f),
            strokeWidth = side * 0.007f,
            cap = StrokeCap.Round,
            alpha = alpha * 0.78f * seamT
        )
    }

    // 两组不闭合的弧：像糖釉表面反射出的弯月，不会变成完整圆环或齿轮。
    val outerRadius = side * (0.12f + 0.34f * ringT)
    val outerStroke = side * (0.025f - 0.010f * ringT).coerceAtLeast(0.010f)
    drawArc(
        color = IMPACT_ROSE,
        startAngle = axisDegrees + 102f,
        sweepAngle = 72f,
        useCenter = false,
        topLeft = center - Offset(outerRadius, outerRadius),
        size = Size(outerRadius * 2f, outerRadius * 2f),
        alpha = alpha * 0.42f * fade,
        style = Stroke(width = outerStroke, cap = StrokeCap.Round)
    )
    drawArc(
        color = IMPACT_ROSE,
        startAngle = axisDegrees + 284f,
        sweepAngle = 58f,
        useCenter = false,
        topLeft = center - Offset(outerRadius, outerRadius),
        size = Size(outerRadius * 2f, outerRadius * 2f),
        alpha = alpha * 0.34f * fade,
        style = Stroke(width = outerStroke, cap = StrokeCap.Round)
    )

    val innerRadius = side * (0.075f + 0.21f * ringT)
    drawArc(
        color = IMPACT_PEARL,
        startAngle = axisDegrees + 112f,
        sweepAngle = 52f,
        useCenter = false,
        topLeft = center - Offset(innerRadius, innerRadius),
        size = Size(innerRadius * 2f, innerRadius * 2f),
        alpha = alpha * 0.58f * fade,
        style = Stroke(width = side * 0.010f, cap = StrokeCap.Round)
    )
    drawArc(
        color = IMPACT_DEEP,
        startAngle = axisDegrees + 294f,
        sweepAngle = 42f,
        useCenter = false,
        topLeft = center - Offset(innerRadius, innerRadius),
        size = Size(innerRadius * 2f, innerRadius * 2f),
        alpha = alpha * 0.25f * fade,
        style = Stroke(width = side * 0.008f, cap = StrokeCap.Round)
    )

    // 一道偏移的曲线高光，模拟釉面折射而不是 UI 光效。
    val ribbon = side * (0.09f + 0.18f * ringT)
    path.rewind()
    path.moveTo(
        center.x - tangent.x * ribbon - axis.x * side * 0.08f,
        center.y - tangent.y * ribbon - axis.y * side * 0.08f
    )
    path.cubicTo(
        center.x - tangent.x * ribbon * 0.78f + axis.x * side * 0.10f,
        center.y - tangent.y * ribbon * 0.78f + axis.y * side * 0.10f,
        center.x + tangent.x * ribbon * 0.54f + axis.x * side * 0.16f,
        center.y + tangent.y * ribbon * 0.54f + axis.y * side * 0.16f,
        center.x + tangent.x * ribbon * 0.82f + axis.x * side * 0.23f,
        center.y + tangent.y * ribbon * 0.82f + axis.y * side * 0.23f
    )
    drawPath(
        path,
        IMPACT_PEARL,
        alpha = alpha * 0.34f * fade,
        style = Stroke(width = side * 0.011f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 仅四枚短碎片：向外脱离，长度不同，保留空气感但不抢主体。
    val shardAngles = floatArrayOf(
        axisDegrees - 142f,
        axisDegrees - 64f,
        axisDegrees + 34f,
        axisDegrees + 124f
    )
    val shardReach = floatArrayOf(0.17f, 0.12f, 0.20f, 0.14f)
    for (i in shardAngles.indices) {
        val rad = shardAngles[i] * PI.toFloat() / 180f
        val direction = Offset(cos(rad), sin(rad))
        val startRadius = side * (0.16f + 0.03f * shardT)
        val endRadius = side * (0.16f + shardReach[i] * shardT)
        val start = center + direction * startRadius
        val end = center + direction * endRadius
        val lift = tangent * (side * 0.006f)
        drawLine(
            IMPACT_DEEP,
            start,
            end,
            strokeWidth = side * 0.014f,
            cap = StrokeCap.Round,
            alpha = alpha * 0.30f * fade * shardT
        )
        drawLine(
            IMPACT_SHARD,
            start - lift,
            end - lift,
            strokeWidth = side * 0.006f,
            cap = StrokeCap.Round,
            alpha = alpha * 0.62f * fade * shardT
        )
    }
}

// ─────────────────────── 8 · folklore ───────────────────────

/**
 * 苔藓那几块斑的 x 区间（成对，占琴身宽的比例）。顶面与前沿共用同一组 ——
 * 一丛苔藓是翻过棱线连着长的，两个面各排一套会读成两种贴纸。
 * 缝隙宽窄不等（0.08 / 0.07 / 0.08 而中段区间也不等宽）：等宽的缝隙又变回花边
 */
/** 琴盖自由边上那三丛苔藓的 t 区间（沿自由边的比例，成对）。宽窄与缝隙都不等 */
private val LID_MOSS_SPANS = floatArrayOf(
    0.05f, 0.33f,
    0.42f, 0.60f,
    0.71f, 0.98f
)

private val MOSS_PATCHES = floatArrayOf(
    0.00f, 0.22f,
    0.30f, 0.46f,
    0.53f, 0.70f,
    0.78f, 1.00f
)

/**
 * 琴盖撑起后与水平面的夹角。
 *
 * 拿离线复刻器（`build/egg-shots/fo_piano.py`）比过 35° / 50° / 65° / 78° 四档：
 * 再小那块板像没掀开，再大它就高过树线、把整个上半屏压住。
 */
private const val LID_OPEN_DEGREES = 50f

/**
 * 琴身的**深度向量**（占屏宽 / 屏高的比例）。只有这一份，琴身、顶面、琴盖、键盘托全按它推。
 *
 * 上一版是 `0.100w / -0.042h`：右侧板 144px 宽、后下角比前沿高出 0.042h，那一大块
 * 平行四边形在屏幕上读作「另贴上来的一块板」，是全图最显眼的「飘」（需求方点名）。
 * 收到 0.078w / -0.033h 之后侧板 112px 宽、后下角只高 0.033h，琴也从一个「斜四分之三」
 * 变回参考图里那种近正面的角度 —— 参考剧照里琴是几乎正对着看的人。
 */
private const val PIANO_DX_FRACTION = 0.078f
private const val PIANO_DY_FRACTION = -0.0328f

/**
 * 琴身整体下沉的屏高比例。
 *
 * 原来琴脚压在林地线上（`HERO_BOTTOM - 0.002 = 0.378h`），而卡片顶边在 0.458h ——
 * 中间那 0.08h 空地上什么都没有，屏幕上是「琴飘在半山腰」。沉 0.045h 之后：琴脚落进
 * 苔原带里、琴脚到卡片只剩 0.035h，后下角（`bodyB + dy`）也在林地线**以下**。
 */
private const val PIANO_SINK = 0.045f

/** 立式琴深约是琴高的 0.6/1.2，再乘上收窄后的深度比例 —— 琴盖抬起量按它折真机像素。 */
private const val LID_DEPTH_OF_HEIGHT = 0.5f * 0.78f

/**
 * 一片雾里的松林 + 一台长满苔藓的**立式**钢琴，琴顶架起一块斜板。
 *
 * folklore 的封面就是霉霉一个人置身松林之中 —— 所以这一张**没有房子**。
 * 上一版右边那栋亮着窗的木屋被删了：它把视线全吸过去，而且 Lover 那张已经有一栋屋，
 * 同一件道具两张背景各一次，正是「重复」。腾出来的右半屏全部还给松林。
 *
 * ## 松林
 *
 * 参考 folklore 专辑封面那张雾林（Beth Garrabrant 拍的）与 cardigan MV 的林子 ——
 * 那张照片的语法就三条：**竖直的细树干**排成节奏；深度全靠**雾**（远处的树干洗完就没了、
 * 树脚在雾里断掉，不是淡着拖到地上）；树冠是上方的**软叶顶棚**，糊成一片、不勾边。
 *
 * 所以这一版把「三角松」整个换掉了。上一版 27 棵山毛榉式三角松（17 棵随机分三层 +
 * 6 棵塞缝 + 4 棵近景），全是同一个 [drawConifer] 换个尺寸 —— 乱不在多，在**同一个形状
 * 反复出现**：随机只挪位置与大小，形状一模一样，屏幕上是一片锯齿。现在 16 根，全部手放：
 * 8 根远景细杆（[FOLK_FAR_TRUNKS]，脚停在雾里）、2 棵雾里的杉（[FOLK_MIST_FIRS]，
 * 「这是松林」靠它们说但只到 0.17–0.20 档）、4 根中景（[FOLK_MID_TRUNKS]）、
 * 2 根近景粗杆（[FOLK_NEAR_TRUNKS]，底端的喇叭口是「树」的关键）与
 * 画框边上的两个杉木楔子（[FOLK_NEAR_PINES]）。
 *
 * 顺序：顶棚 → 远层 → 雾 → 雾里的杉 → 中层 → 雾 → 近层。顶棚**最先**画：
 * 树干要从叶子里穿出来，反过来树干顶在叶子上就成贴纸。
 *
 * ## 钢琴为什么改成立式、为什么要斜着摆
 *
 * 上一版是**正视的三角钢琴**：一个圆头的琴身加一块平铺的大琴盖，屏幕上读作一只浴缸
 * 加一块板（宽 0.45w、高只有 0.085h，2.4:1）。这一版按**近正面的四分之三视角**重画， * 三个面都露出来：正脸（背光，最暗）、右侧面（更暗，往后收）、顶面（朝上受光，最亮）。
 * 深度向量 [PIANO_DX_FRACTION] / [PIANO_DY_FRACTION] 只有一份，琴身、顶面、琴盖、键盘托
 * 全按它推 —— 换角度只动这两个数。
 *
 * 立式而不是三角：立柜的高宽比接近 1:1，在 0.38h 的英雄区里立得起来；三角钢琴是趴着的，
 * 在这块横长的区域里只会更扁。而「林子里被遗弃的钢琴」这个意象本身就是立式琴。
 *
 * 琴顶那块板是**掀开撑住的琴盖**：立式琴的顶板铰在**后沿**，掀开时是前边抬起来往后退，
 * 所以自由边（前边）落在后沿的**左上**（前移量 `cosθ·dx`、抬起量 `sinθ·琴深`）。
 * 这一点对着参考剧照核过：铰在后沿、自由边在琴的上方偏左，撑杆支在自由边那一侧。
 * 板是硬的，所以自由边在投影里仍然平行于铰线、两端抬一样高 —— 一个平行四边形。
 * **不许让两端抬不一样高**：那读作坡顶，而坡顶把需求方点名删掉的那栋木屋又读回来了。
 * 撑杆立在顶面上，板在顶面上还投一道影。
 *
 * ## 落地
 *
 * 「琴像浮在画面上」被需求方点了两轮，三处一起改才压住：
 * 琴身整体下沉（[PIANO_SINK]，后下角落到林地线**以下**）；右侧板不能是一整片均匀的死黑
 * （读作另贴上来的一块板）；琴脚要有一整片**苔岸**埋住底边那条笔直的棱。
 *
 * ## 苔藓
 *
 * 苔藓是这一张的主角，所以它不是「沿边排一串小圆」（第一版如此，屏幕上读作一行绿豆），
 * 也不是沿着顶沿通铺的一条边 —— 连续的一条无论下缘画成圆弧还是折线，都读作人做的花边
 * （第二版是流苏帷幔、第三版是锯齿花边，都被截图否掉了）。关键分界不在弧还是角，
 * 在**连续还是断开**：断口处露出来的那截木头才是「长出来的」证据。
 *
 * 所以定死四块斑的 x 区间 [MOSS_PATCHES]，顶面与前沿**共用同一组区间**（一丛苔藓是翻过
 * 棱线连着长的），块内两端收到贴边、块中最厚。顶面另外通铺一层 0.16 的淡绿 —— 那是阴湿
 * 木头本身的颜色，不是苔藓本体。正脸的斑只落在三条积水的横棱上，垂下来的缕只从斑的中段吊。
 */
private fun DrawScope.drawPineMossPiano(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float
) {
    val w = size.width
    val h = size.height
    // 林地线 = HERO_BOTTOM。琴身再往下沉（[PIANO_SINK]）也不会被卡片吃掉：
    // 卡片顶边在 0.458h，琴脚沉到 0.423h 时还剩 0.035h 的余量
    val groundY = h * HERO_BOTTOM

    // 林地：一层苔原。folklore 三档底色全是灰（#E8E6E2 / #8C8C8C / #4A4844），
    // 整屏唯一的绿就是苔藓这一档
    drawRect(
        color = MOSS,
        topLeft = Offset(0f, groundY - h * 0.012f),
        size = Size(w, h * 0.16f),
        alpha = alpha * 0.30f
    )

    // ── 松林 ──
    // 参考 folklore 专辑封面那张雾林（Beth Garrabrant 拍的）+ cardigan MV 的林子，语法三条：
    // ①**树干是主体**：十几到二十几根竖直的杆、粗细差三档、一直长出画面顶 ——
    //    林子不是「一层层的树形」，是一排**杆**；树形（三角松）只在雾里点两下；
    // ②**深度全靠雾**：远处的杆洗完就没了，脚都落进同一条地平雾里（0.40–0.412h），
    //    不在半空里齐齐断掉；
    // ③顶上**不是一条树冠带**（那是云的读法）：顶上是亮雾 + 六挂**疏叶**，
    //    挂与挂之间露着天，叶子由 40–60px 波长的小弧接成。
    //
    // 上一版是 17 棵随机三角松分三层 + 6 棵更小的塞缝 + 4 棵近景大三角，一共 27 棵，
    // 全是同一个 `drawConifer` 换个尺寸 —— 乱不在多，在**同一个形状反复出现**：
    // 随机只挪位置和大小，形状一模一样，屏幕上是一片锯齿（需求方原话「松林画的有点乱」）。
    // 第二版把树形换成了杆 + 软椭圆顶棚，顶棚读成了云、杆淡到看不见（「树看不清，顶部像云朵一样」）。
    //
    // 顺序：叶 → 远层 → 雾 → 雾里的杉 → 中层 → 雾 → 近层 → 细梢。
    // 叶必须**最先**画：树干从叶子里穿出来，反过来树干顶在叶子上就成贴纸
    drawFolkloreSprays(path, deep, mid, alpha)
    // 远层的透明度是「树看不清」的主因：原来 0.20 的白杆落在 195 的天空上只差 18/255，
    // 屏幕上就是没有。0.55 之后远处那排才数得出来，同时仍比中层浅一档
    drawTrunkLayer(path, FOLK_FAR_TRUNKS, mid, alpha * 0.55f, taper = 0.72f, flare = 0.18f)
    // 雾做得**宽而软**：窄条带会在屏幕上留下两道水平的白边 ——
    // 一条 0.24h 高的带子在浅色天空里比树还显眼（离线复刻器第一版就栽在这）
    drawFogBand(0.24f + 0.006f * sin(phase * TAU), 0.36f, top, alpha * 0.16f)
    // 雾里的两棵杉：只留剪影的形、不压暗。「这是松林」这句话靠它们说，
    // 但它们是远景（0.17–0.20 档），不是原来那几棵挡在琴前面的深色大三角
    for (i in 0 until FOLK_MIST_FIRS.size / 4) {
        drawConifer(
            path = path,
            cx = FOLK_MIST_FIRS[i * 4] * w,
            baseY = groundY + h * 0.010f,
            height = FOLK_MIST_FIRS[i * 4 + 1] * h,
            halfWidth = FOLK_MIST_FIRS[i * 4 + 2] * w,
            color = mid,
            alpha = alpha * FOLK_MIST_FIRS[i * 4 + 3]
        )
    }
    drawTrunkLayer(
        path, FOLK_MID_TRUNKS, deep, alpha * 0.40f, taper = 0.62f, flare = 0.34f,
        rimColor = top, rimAlpha = 0.09f
    )
    drawFogBand(0.33f, 0.22f, top, alpha * 0.13f)
    // 近景两棵杉木剪影：只露出画框边上的一个楔子（x = -0.062w / 1.062w），
    // 满了就是原来那三棵挤在右边一团的样子。**在钢琴之前画** ——
    // 钢琴是全图最靠前的东西，与它重叠的树该被它挡住
    for (i in 0 until FOLK_NEAR_PINES.size / 3) {
        drawConifer(
            path = path,
            cx = FOLK_NEAR_PINES[i * 3] * w,
            baseY = groundY + h * 0.008f,
            height = FOLK_NEAR_PINES[i * 3 + 1] * h,
            halfWidth = FOLK_NEAR_PINES[i * 3 + 2] * w,
            color = deep,
            alpha = alpha * SILHOUETTE_ALPHA * 1.24f
        )
    }
    drawTrunkLayer(
        path, FOLK_NEAR_TRUNKS, deep, alpha * 0.58f, taper = 0.58f, flare = 0.50f,
        rimColor = top, rimAlpha = 0.11f
    )
    // 细梢：**在树干之后**画（梢是从杆上长出来的），长在近景杆上那几根压深一档
    for (pass in 0..1) {
        val nearPass = pass == 0
        path.rewind()
        for (i in 0 until FOLK_BRANCHES.size / 6) {
            var near = false
            for (t in 0 until FOLK_NEAR_TRUNKS.size / 5) {
                if (abs(FOLK_BRANCHES[i * 6] - FOLK_NEAR_TRUNKS[t * 5]) < 0.02f) near = true
            }
            if (near != nearPass) continue
            branchWedge(
                path = path,
                w = w,
                h = h,
                x0 = FOLK_BRANCHES[i * 6],
                y0 = FOLK_BRANCHES[i * 6 + 1],
                x1 = FOLK_BRANCHES[i * 6 + 2],
                y1 = FOLK_BRANCHES[i * 6 + 3],
                w0 = FOLK_BRANCHES[i * 6 + 4],
                w1 = FOLK_BRANCHES[i * 6 + 5]
            )
        }
        drawPath(path = path, color = deep, alpha = alpha * (if (nearPass) 0.44f else 0.24f))
    }

    // ── 立式钢琴（斜四分之三视角）──
    // 一份深度向量推出所有的面：往后一格 = 往右 dx、往上 dy（俯视）。
    // 反过来「朝着看的人」就是往左往下 —— 键盘托、踏板都按 -dx / -dy 推出来
    //
    // 高 0.158h 而不是 0.208h：`0.395w × 0.208h` 在 1440×3200 上是 569×666 像素，
    // **比自己还高**，而真的立式琴宽约 150cm、高约 120cm（宽:高 ≈ 1.25）。
    // 更要紧的是早期那版琴盖自由边的板厚带落在 `0.007h`，整条压进状态栏里；
    // 现在琴身矮 0.05h、`dy` 收到 0.033h、抬起量按 0.39×琴高折，板上苔簇最高到 0.175h，
    // 离系统栏还差着半个屏
    val bodyW = w * 0.395f
    val bodyH = h * 0.158f
    val bodyL = w * 0.075f
    val bodyR = bodyL + bodyW
    // 琴身整体下沉（见 [PIANO_SINK]）：琴脚落进苔原带、离卡片更近，后下角也不再露在天上
    val bodyB = groundY + h * PIANO_SINK
    val bodyT = bodyB - bodyH
    val dx = w * PIANO_DX_FRACTION
    val dy = h * PIANO_DY_FRACTION

    // 落地影：顺着**整个可见底轮廓**（前棱 + 右侧棱）往**右后**摊。
    //
    // 方向是要害。等距投影里「往后」就是深度向量 (dx, dy) 本身，光从左前上来 ⇒ 影子往右后走，
    // 于是影子两条边与箱体两条底边**平行**，接得严丝合缝。上一版只从**前棱**往**右下**摊
    // （`(dx*reach, +drop)`）：右侧棱底下那一块地反倒是**亮**的，那块平行四边形就浮在亮地上
    // —— 需求方两轮都点这一处「平行四边形像悬浮一样」。
    //
    // 长度要按「地平线以下还剩多少地」砍：箱高 506px、光 45° 时物理上该拖 500px 长，
    // 但每 1 个深度单位只往上 105px（|dy|），拖满就爬到地平线**以上的天**里去了。
    // 只摊到 0.95 个深度单位（≈100px，末端停在地平线下 49px），五档嵌套、越远越淡：
    // 贴着底轮廓那四档叠出 ≈0.38 的暗，往右后散开
    for (step in 0 until 5) {
        val reach = floatArrayOf(0.18f, 0.36f, 0.55f, 0.75f, 0.95f)[step]
        path.rewind()
        path.moveTo(bodyL, bodyB)
        path.lineTo(bodyR + dx * reach, bodyB + dy * reach)
        path.lineTo(bodyR + dx * (reach + 1f), bodyB + dy * (reach + 1f))
        path.lineTo(bodyL + dx * (reach + 1f), bodyB + dy * (reach + 1f))
        path.close()
        drawPath(
            path = path,
            color = Color.Black,
            alpha = alpha * floatArrayOf(0.16f, 0.12f, 0.10f, 0.08f, 0.06f)[step]
        )
    }

    // 右侧板：往后收的那个面，背光又侧对着光，全琴最暗。
    // **但不能是一整片均匀的死黑** —— 那样屏幕上读作「另贴上来的一块板」，而不是箱体的
    // 侧面（需求方这轮点名了这块平行四边形）。面本身提亮一档，再沿上→下分三段渐暗
    // （和落地影同一手法）：面里有了「离光越远越暗」的走向，它才是一个受光的立面。
    // 后棱再描一道极淡的亮线，箱体那根竖转角才交代得出来
    path.rewind()
    path.moveTo(bodyR, bodyT)
    path.lineTo(bodyR + dx, bodyT + dy)
    path.lineTo(bodyR + dx, bodyB + dy)
    path.lineTo(bodyR, bodyB)
    path.close()
    drawPath(path = path, color = deep, alpha = alpha * 0.94f)
    for (band in 0 until 3) {
        val t0 = floatArrayOf(0f, 0.40f, 0.72f)[band]
        val t1 = floatArrayOf(0.40f, 0.72f, 1f)[band]
        val strength = floatArrayOf(0.05f, 0.10f, 0.16f)[band]
        path.rewind()
        path.moveTo(bodyR, bodyT + bodyH * t0)
        path.lineTo(bodyR + dx, bodyT + bodyH * t0 + dy)
        path.lineTo(bodyR + dx, bodyT + bodyH * t1 + dy)
        path.lineTo(bodyR, bodyT + bodyH * t1)
        path.close()
        drawPath(path = path, color = Color.Black, alpha = alpha * strength)
    }
    drawLine(
        color = mid,
        start = Offset(bodyR + dx, bodyT + dy),
        end = Offset(bodyR + dx, bodyB + dy),
        strokeWidth = (w * 0.0030f).coerceAtLeast(1f),
        alpha = alpha * 0.14f
    )

    // 顶面：唯一朝上的面，最亮。苔藓整块铺在它上面
    path.rewind()
    path.moveTo(bodyL, bodyT)
    path.lineTo(bodyR, bodyT)
    path.lineTo(bodyR + dx, bodyT + dy)
    path.lineTo(bodyL + dx, bodyT + dy)
    path.close()
    drawPath(path = path, color = mid, alpha = alpha * 0.95f)
    // 通铺一层很淡的绿：常年阴湿的木头本身就发绿，但这不是苔藓本体
    drawPath(path = path, color = MOSS, alpha = alpha * 0.16f)
    // 苔藓本体在顶面上也是**那几块**，而且和前沿的斑**共用同一组 x 区间** ——
    // 一丛苔藓是翻过棱线连着长的，顶面一块、前沿另一块错开，会读成两种贴纸
    for (k in 0 until MOSS_PATCHES.size / 2) {
        val x0 = MOSS_PATCHES[k * 2]
        val x1 = MOSS_PATCHES[k * 2 + 1]
        path.rewind()
        // 前沿一侧（贴 bodyT）为直边，往深处一侧鼓成弧：只吃掉顶面的前半截
        path.moveTo(bodyL + bodyW * x0 + dx * 0.04f, bodyT + dy * 0.04f)
        path.lineTo(bodyL + bodyW * x1 + dx * 0.04f, bodyT + dy * 0.04f)
        for (i in 6 downTo 0) {
            val t = i / 6f
            val f = x0 + (x1 - x0) * t
            val bell = sin(t * PI.toFloat())
            val depth = 0.10f + 0.62f * bell * (0.62f + 0.38f * sin(f * 19f + 0.4f))
            path.lineTo(bodyL + bodyW * f + dx * depth, bodyT + dy * depth)
        }
        path.close()
        drawPath(path = path, color = MOSS, alpha = alpha * 0.52f)
        drawPath(path = path, color = MOSS_LIGHT, alpha = alpha * 0.18f)
    }
    // 每丛里再压两块深的：一丛苔藓自己的厚薄也不匀
    for (i in 0 until 5) {
        val f = (i + 0.5f) / 5f
        val bx = bodyL + dx * 0.30f + bodyW * f
        val by = bodyT + dy * 0.30f
        val r = w * (0.016f + 0.015f * abs(sin(f * 7.3f + 1.1f)))
        drawOval(
            color = MOSS,
            topLeft = Offset(bx - r, by - r * 0.42f),
            size = Size(r * 2f, r * 0.84f),
            alpha = alpha * 0.34f
        )
    }

    // 琴盖投在顶面上的影：占后 70%，往右偏。板与顶面之间有这道影才是「架起来」的
    path.rewind()
    path.moveTo(bodyL + dx * 0.28f, bodyT + dy * 0.28f)
    path.lineTo(bodyR + dx * 0.28f, bodyT + dy * 0.28f)
    path.lineTo(bodyR + dx, bodyT + dy)
    path.lineTo(bodyL + dx, bodyT + dy)
    path.close()
    drawPath(path = path, color = Color.Black, alpha = alpha * 0.28f)

    // ── 掀开撑住的琴盖 ──
    // 铰在顶面后沿，往后上方倾。一块硬板绕一根铰转过去，自由边在投影里
    // **仍然平行于铰线**，两端抬一样高 —— 所以这是一个干净的**平行四边形**，
    // “3D” 全靠位移同时带 x 与 y 分量（和其余所有面用的是同一份深度向量）。
    //
    // 更早的一版故意让右端比左端多抬一截、右端又往右多伸出 0.046w，想要“不平行的立体感”，
    // 结果那正是一块**坡顶**：下沿水平、上沿斜着、右侧还撑出一截检口。
    // 需求方让把房子去掉，而这块“屋顶”把房子又读回来了。硬板不会扭，这条也本就是错的
    val hingeLX = bodyL + dx
    val hingeRX = bodyR + dx
    val hingeY = bodyT + dy
    // 自由边往**左上**抬，不是右上。铰线在顶面**后沿**，掀开就是「把前边抬起来」——
    // 而“往前”在投影里是 `(-dx, -dy)` 的反向（左下）：抬起量叠上去之后，自由边必然
    // 落在铰线的左上方。上一版写的是 `+0.062w`（往右上）＝ 把自由边摆到铰线**后面**去了，
    // 那块板在几何上是在琴背后悬着的一块板 —— 需求方原话「撑起的琴盖角度画错了」，
    // 而且它跟琴身只共一条线，整台琴也就跟着「像悬浮在画面上一样」。
    //
    // 抬起量用**琴深**折算：立式琴深约是琴高的 0.6/1.2 = 一半，`bodyH × 0.5` 像素。
    // 角度 50° 是拿离线复刻器（`build/egg-shots/fo_piano.py`）比 35/50/65/78 四档定的：
    // 再小像没掀开，再大那块板就高过树线、把整个上半屏压住
    val lidRad = LID_OPEN_DEGREES * PI.toFloat() / 180f
    val lidShiftX = -cos(lidRad) * dx
    val lidShiftY = cos(lidRad) * abs(dy) - sin(lidRad) * (bodyH * LID_DEPTH_OF_HEIGHT)
    val lidLX = hingeLX + lidShiftX
    val lidLY = hingeY + lidShiftY
    val lidRX = hingeRX + lidShiftX
    val lidRY = hingeY + lidShiftY
    path.rewind()
    path.moveTo(hingeLX, hingeY)
    path.lineTo(hingeRX, hingeY)
    path.lineTo(lidRX, lidRY)
    path.lineTo(lidLX, lidLY)
    path.close()
    // 看到的是板的**底面**（板往后倾），朝下的面比朝前的正脸再暗一档
    drawPath(path = path, color = deep, alpha = alpha)
    drawPath(path = path, color = Color.Black, alpha = alpha * 0.10f)
    // 底面上**一道**横向的暗线。两道等距的缝加上倾斜的大面，读作瓦楞屋面板；
    // 一道就够交代「板是拼的」，再淡一档
    run {
        val f = 0.46f
        drawLine(
            color = Color.Black,
            start = Offset(hingeLX + (lidLX - hingeLX) * f, hingeY + (lidLY - hingeY) * f),
            end = Offset(hingeRX + (lidRX - hingeRX) * f, hingeY + (lidRY - hingeY) * f),
            strokeWidth = (h * 0.0022f).coerceAtLeast(1f),
            alpha = alpha * 0.13f
        )
    }
    // 板厚：沿自由边再描一条窄带，且这一条是全板唯一受光的面 —— 板才不是一张纸
    path.rewind()
    path.moveTo(lidLX, lidLY)
    path.lineTo(lidRX, lidRY)
    path.lineTo(lidRX + w * 0.005f, lidRY - h * 0.012f)
    path.lineTo(lidLX + w * 0.005f, lidLY - h * 0.012f)
    path.close()
    drawPath(path = path, color = mid, alpha = alpha * 0.92f)
    // 板沿上的苔藓：**三丛跨在自由边上的斑**，不是沿边排的六个等距椭圆 ——
    // 那一版屏幕上是一行绿豆（本函数开头就点过这个错，结果自己在板沿上又犯一次），
    // 六颗均匀的绿点压在一块斜面的顶边，整体读作屋脊上的瓦
    val edgeX = { t: Float -> lidLX + (lidRX - lidLX) * t }
    val edgeY = { t: Float -> lidLY + (lidRY - lidLY) * t }
    for (k in 0 until LID_MOSS_SPANS.size / 2) {
        val t0 = LID_MOSS_SPANS[k * 2]
        val t1 = LID_MOSS_SPANS[k * 2 + 1]
        path.rewind()
        path.moveTo(edgeX(t0), edgeY(t0) + h * 0.005f)
        path.lineTo(edgeX(t1), edgeY(t1) + h * 0.005f)
        for (i in 6 downTo 0) {
            val f = i / 6f
            val t = t0 + (t1 - t0) * f
            val bell = sin(f * PI.toFloat())
            val rise = h * (0.002f + 0.019f * bell * (0.60f + 0.40f * sin(t * 21f + 0.9f)))
            path.lineTo(edgeX(t), edgeY(t) - rise)
        }
        path.close()
        drawPath(path = path, color = if (k == 1) MOSS_LIGHT else MOSS, alpha = alpha * 0.56f)
    }
    // 撑杆：从顶面立到板底。少了它那块板是浮着的
    drawLine(
        color = deep,
        start = Offset(bodyL + dx * 0.66f + bodyW * 0.62f, bodyT + dy * 0.66f),
        end = Offset(lidLX + (lidRX - lidLX) * 0.62f, lidLY + (lidRY - lidLY) * 0.62f),
        strokeWidth = (w * 0.0085f).coerceAtLeast(1f),
        alpha = alpha
    )

    // ── 正脸 ──
    // 背光的那个面，比顶面暗、比侧板亮。上门板 / 谱架 / 键盘托 / 下门板 / 底座，
    // 五段从上到下排下来，立式琴的辨识度全在这个排布上
    path.rewind()
    path.moveTo(bodyL, bodyT)
    path.lineTo(bodyR, bodyT)
    path.lineTo(bodyR, bodyB)
    path.lineTo(bodyL, bodyB)
    path.close()
    drawPath(path = path, color = deep, alpha = alpha * 0.92f)
    // 左棱受光：光从左上来，正脸最左那一条比其余亮一档
    drawRect(
        color = mid,
        topLeft = Offset(bodyL, bodyT),
        size = Size(bodyW * 0.045f, bodyH),
        alpha = alpha * 0.28f
    )
    val keyY = bodyT + bodyH * 0.455f
    // 上门板：一块内凹的方板，四周留边。一块纯色的正脸没有雕饰。
    // 0.12 而不是 0.20：`deep` 正脸本身已经压到 0.92，再叠一层两成的黑，
    // 门板暗得比右侧板还狠 —— 那不是内凹，是一个洞
    drawRect(
        color = Color.Black,
        topLeft = Offset(bodyL + bodyW * 0.10f, bodyT + bodyH * 0.10f),
        size = Size(bodyW * 0.80f, keyY - bodyT - bodyH * 0.20f),
        alpha = alpha * 0.12f
    )
    drawRect(
        color = mid,
        topLeft = Offset(bodyL + bodyW * 0.10f, bodyT + bodyH * 0.10f),
        size = Size(bodyW * 0.80f, keyY - bodyT - bodyH * 0.20f),
        alpha = alpha * 0.18f,
        style = Stroke(width = (w * 0.0030f).coerceAtLeast(1f))
    )
    // 两侧立柱：立式琴正脸两边各一根，比门板凸出来一点
    for (side in 0..1) {
        drawRect(
            color = mid,
            topLeft = Offset(
                if (side == 0) bodyL + bodyW * 0.045f else bodyR - bodyW * 0.085f,
                bodyT + bodyH * 0.06f
            ),
            size = Size(bodyW * 0.040f, bodyH * 0.90f),
            alpha = alpha * (if (side == 0) 0.22f else 0.10f)
        )
    }

    // ── 键盘托 ──
    // 朝着看的人探出来：往后是 (dx, dy)，那么朝前就是 (-dx, -dy) 的一小截。
    // 这一块是全图唯一的亮白（琴键），所以它同时也是钢琴的视觉落点。
    //
    // 琴键**两端各缩进 0.075×琴宽**，缩出来的位置放两块颊木（cheek block）。
    // 上一版琴键从左边一直铺到右边，截出来是一根白尺贴在正脸上；
    // 真的立式琴键床两头必定堵着一块比键高的颊木，那两块才是「键是嵌在里面」的交代
    val outX = -w * 0.026f
    val outY = h * 0.013f
    val cheek = bodyW * 0.105f
    val keyL = bodyL + cheek
    val keyW = bodyW - cheek * 2f
    path.rewind()
    path.moveTo(bodyL, keyY)
    path.lineTo(bodyR, keyY)
    path.lineTo(bodyR + outX, keyY + outY)
    path.lineTo(bodyL + outX, keyY + outY)
    path.close()
    drawPath(path = path, color = deep, alpha = alpha)
    path.rewind()
    path.moveTo(keyL, keyY)
    path.lineTo(keyL + keyW, keyY)
    path.lineTo(keyL + keyW + outX, keyY + outY)
    path.lineTo(keyL + outX, keyY + outY)
    path.close()
    drawPath(path = path, color = PAPER_WHITE, alpha = alpha * 0.90f)
    // 白键缝：从后沿拉到前沿，26 道
    for (i in 1 until 26) {
        val f = i / 26f
        drawLine(
            color = Color.Black,
            start = Offset(keyL + keyW * f, keyY),
            end = Offset(keyL + keyW * f + outX, keyY + outY),
            strokeWidth = (w * 0.0016f).coerceAtLeast(1f),
            alpha = alpha * 0.32f
        )
    }
    // 黑键：一个八度 5 根，位置按 2-3 分组。这是「这是一台钢琴」的唯一标识
    val blackW = keyW * 0.017f
    for (o in 0 until 4) {
        floatArrayOf(0.085f, 0.205f, 0.470f, 0.600f, 0.730f).forEach { f ->
            val bx = keyL + keyW * ((o + f) / 4f)
            path.rewind()
            path.moveTo(bx, keyY)
            path.lineTo(bx + blackW, keyY)
            path.lineTo(bx + blackW + outX * 0.60f, keyY + outY * 0.60f)
            path.lineTo(bx + outX * 0.60f, keyY + outY * 0.60f)
            path.close()
            drawPath(path = path, color = Color.Black, alpha = alpha * 0.82f)
        }
    }
    // 两块颊木：比键面高一截，受光侧提一档
    for (side in 0..1) {
        val bx = if (side == 0) bodyL else bodyR - cheek
        path.rewind()
        path.moveTo(bx, keyY - h * 0.009f)
        path.lineTo(bx + cheek, keyY - h * 0.009f)
        path.lineTo(bx + cheek + outX, keyY + outY)
        path.lineTo(bx + outX, keyY + outY)
        path.close()
        drawPath(path = path, color = mid, alpha = alpha * (if (side == 0) 0.72f else 0.50f))
    }
    // 键盘托的前脸：托是有厚度的一块板，少了这一条琴键像是印在正脸上的一道贴纸
    path.rewind()
    path.moveTo(bodyL + outX, keyY + outY)
    path.lineTo(bodyR + outX, keyY + outY)
    path.lineTo(bodyR + outX, keyY + outY + h * 0.011f)
    path.lineTo(bodyL + outX, keyY + outY + h * 0.011f)
    path.close()
    drawPath(path = path, color = deep, alpha = alpha)
    drawLine(
        color = mid,
        start = Offset(bodyL + outX, keyY + outY),
        end = Offset(bodyR + outX, keyY + outY),
        strokeWidth = (h * 0.0022f).coerceAtLeast(1f),
        alpha = alpha * 0.30f
    )

    // 下门板 + 底座 + 踏板。底座往前探一小截，琴才是站在地上而不是插进地里
    drawRect(
        color = Color.Black,
        topLeft = Offset(bodyL + bodyW * 0.12f, keyY + outY + h * 0.020f),
        size = Size(bodyW * 0.76f, bodyB - keyY - outY - h * 0.040f),
        alpha = alpha * 0.11f
    )
    path.rewind()
    path.moveTo(bodyL + outX * 0.6f, bodyB - h * 0.014f)
    path.lineTo(bodyR + outX * 0.6f, bodyB - h * 0.014f)
    path.lineTo(bodyR, bodyB)
    path.lineTo(bodyL, bodyB)
    path.close()
    drawPath(path = path, color = deep, alpha = alpha)
    drawLine(
        color = mid,
        start = Offset(bodyL + outX * 0.6f, bodyB - h * 0.014f),
        end = Offset(bodyR + outX * 0.6f, bodyB - h * 0.014f),
        strokeWidth = (h * 0.0020f).coerceAtLeast(1f),
        alpha = alpha * 0.24f
    )
    // 踏板：两片薄金属，挂在底座上方
    for (i in 0..1) {
        drawRect(
            color = mid,
            topLeft = Offset(bodyL + bodyW * (0.42f + i * 0.10f), bodyB - h * 0.030f),
            size = Size(bodyW * 0.055f, h * 0.005f),
            alpha = alpha * 0.42f
        )
    }

    // ── 苔藓：顶沿那几块斑 ──
    // **不是一条通栏的边**。连续的一条无论下缘怎么画都读成人做的花边：
    // 等间距的圆弧是流苏帷幔（第二版），等间距的折线是锯齿花边（第三版），
    // 两版都被自己截图否掉了。真正的分界不在弧还是角，在**连续还是断开** ——
    // 断口处露出来的那截木头才是「长出来的」证据。
    //
    // 四块斑，块内两端收到贴边（`bell`）、块中最厚，厚度再叠一层高频扰动（`lobe`）。
    // 缝隙定在 0.22–0.30 / 0.46–0.53 / 0.70–0.78，宽窄不等 —— 等宽的缝隙又是花边
    for (k in 0 until MOSS_PATCHES.size / 2) {
        val x0 = MOSS_PATCHES[k * 2]
        val x1 = MOSS_PATCHES[k * 2 + 1]
        path.rewind()
        path.moveTo(bodyL + bodyW * x0, bodyT - h * 0.004f)
        path.lineTo(bodyL + bodyW * x1, bodyT - h * 0.004f)
        for (i in 6 downTo 0) {
            val t = i / 6f
            val f = x0 + (x1 - x0) * t
            val bell = sin(t * PI.toFloat())
            val lobe = 0.55f + 0.45f * sin(f * 23f + 1.7f)
            path.lineTo(bodyL + bodyW * f, bodyT + h * (0.003f + 0.026f * bell * lobe))
        }
        path.close()
        drawPath(path = path, color = MOSS, alpha = alpha * 0.60f)
        drawPath(path = path, color = MOSS_LIGHT, alpha = alpha * 0.22f)
        // 垂下来的一缕：只从斑的中段吊，吊在断口上等于凭空长出一根。
        // 画成**上宽下尖的三角**而不是等宽的圆头线段：等宽的一条 0.028h 长线
        // 挂在正脸上读作一道绿色的漆滴（四条并排更像刚刷过的墙）。长度也砍到一半
        val mid0 = (x0 + x1) * 0.5f
        val len = h * (0.006f + 0.011f * abs(sin(mid0 * 7.9f)))
        val hangX = bodyL + bodyW * mid0
        val hangY = bodyT + h * 0.016f
        path.rewind()
        path.moveTo(hangX - w * 0.0072f, hangY)
        path.lineTo(hangX + w * 0.0072f, hangY)
        path.lineTo(hangX + w * 0.0016f, hangY + len)
        path.close()
        drawPath(path = path, color = MOSS, alpha = alpha * 0.40f)
    }
    // 正脸上的斑：大小差三倍、不排队。**用两个互质的频率取位置与半径** ——
    // 同一个频率既定位置又定大小，屏幕上会出现一行「大小大小」的珠链。
    //
    // 纵向只落在三条**横棱**上（上门板上沿 / 键盘托下沿 / 底座），不再满脸撒：
    // 上一版按 `0.18 + 0.78·|sin|` 铺满整个正脸、半径又到 0.032w，
    // 截出来是一把撒在门板中央的绿硬币。苔藓靠水，长的是积水的平台与缝，不是垂直的板面。
    //
    // 中间那条棱从 0.56 挪到 0.63：0.56 正好压在键盘托的下唇上，几块绿斑骑在白键边缘，
    // 读作掉在琴键上的青豆。压扁到 0.52（原来 0.72）也是为了不读作硬币
    val ledges = floatArrayOf(0.10f, 0.63f, 0.94f)
    for (pass in 0..1) {
        path.rewind()
        for (i in 0 until 13) {
            if ((i % 3 == 0) != (pass == 1)) continue
            val f = i / 12f
            val bx = bodyL + bodyW * (0.04f + 0.94f * f) + w * 0.014f * sin(f * 11.7f)
            val blob = w * (0.006f + 0.013f * abs(sin(f * 5.3f + 0.7f)))
            val by = bodyT + bodyH * (ledges[i % 3] + 0.030f * sin(f * 13.1f))
            path.addOval(Rect(bx - blob * 1.22f, by - blob * 0.52f, bx + blob * 1.22f, by + blob * 0.52f))
        }
        drawPath(path = path, color = if (pass == 1) MOSS_LIGHT else MOSS, alpha = alpha * (if (pass == 1) 0.34f else 0.44f))
    }
    // 琴脚下的**苔岸**：苔藓从地上爬上来，琴与苔原才是长在一起的。
    //
    // 一排跨在底边上的小椭圆（半径 0.010–0.022w）不够 —— 底边那条笔直的棱还在，屏幕上
    // 只是「脚边有几块苔」，琴依旧像摆上去的（需求方两轮都说还在飘）。要的是**一整片岸**：
    // 从琴脚爬上来、往两边散成一丛丛，前沿与后沿（比前沿高 |dy|）一起埋掉。
    // 轮廓收成**枣核**（两端上下两条边收到同一点）—— 两端留竖直切口就是一块绿补丁
    run {
        val steps = 40
        val bankL = bodyL - w * 0.030f
        val bankSpan = bodyW + dx + w * 0.060f
        path.rewind()
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val bell = sin(t * PI.toFloat()).coerceAtLeast(0f)
            val rise = h * (0.002f + bell.pow(0.6f) *
                (0.012f + 0.010f * abs(sin(t * 7.3f + 0.4f))))
            if (i == 0) path.moveTo(bankL, bodyB - rise) else path.lineTo(bankL + bankSpan * t, bodyB - rise)
        }
        for (i in steps downTo 0) {
            val t = i / steps.toFloat()
            val drop = h * (0.001f + 0.013f * sqrt(sin(t * PI.toFloat()).coerceAtLeast(0f)))
            path.lineTo(bankL + bankSpan * t, bodyB + drop)
        }
        path.close()
        drawPath(path = path, color = MOSS, alpha = alpha * 0.72f)
        // 岸边一丛丛：整片单色的岸读作一块绿布。两端也要靠这些丛收掉硬切口
        for (i in 0 until 17) {
            val t = (i + 0.5f) / 17f
            val dome = sin(t * PI.toFloat()).coerceAtLeast(0f).pow(0.45f)
            val bx = bodyL - w * 0.034f + (bodyW + dx + w * 0.068f) * t
            val r = w * (0.009f + 0.014f * abs(sin(t * 5.9f + 1.3f))) * (0.40f + 0.60f * dome)
            val cy = bodyB - h * (0.001f + 0.021f * dome * (0.6f + 0.4f * abs(sin(t * 9.7f))))
            drawOval(
                color = if (i % 3 == 1) MOSS_LIGHT else MOSS,
                topLeft = Offset(bx - r, cy - r * 0.60f),
                size = Size(r * 2f, r * 1.26f),
                alpha = alpha * 0.55f
            )
        }
        // 苔岸只埋住**前棱**。可见的底轮廓有**两条**棱，右侧棱那条底下（|dy| = 0.033h ≈ 105px）
        // 还是光的 —— 那块平行四边形就悬在这一条光带上（需求方这轮的原话）。
        // 沿侧棱再走一条**苔领**：苔要**跨在棱上**长（圆心落在棱上或往外一点，一半在琴身上、
        // 一半在外面），齐着棱码一排绿球会读成「一串贴在边上的珠子」；宽度往后收成零
        // （`1 - t^1.6`），到后下角自然没了，不留一道直切口
        run {
            val sideLen = hypot(dx, dy).coerceAtLeast(1f)
            val nx = abs(dy) / sideLen
            val ny = abs(dx) / sideLen
            val n = 26
            path.rewind()
            // **第一条必须是 moveTo**：空路径上直接 lineTo，这条子路径从 (0,0) 起头 ——
            // 屏幕上就是一条从屏幕左上角斜穿到琴脚的绿线（MOSS × 0.62，斜率 0.5）。
            // 真机截图上量到的线正好过 (0,0) 与 (bodyR, bodyB)，就是这一处
            path.moveTo(bodyR, bodyB)
            for (i in 1..n) {
                val t = i / n.toFloat()
                path.lineTo(bodyR + dx * t, bodyB + dy * t)
            }
            for (i in n downTo 0) {
                val t = i / n.toFloat()
                val width = w * (0.013f + 0.014f * abs(sin(t * 5.3f + 0.9f))) *
                    (1f - t.pow(1.6f)).coerceAtLeast(0f)
                path.lineTo(bodyR + dx * t + nx * width, bodyB + dy * t + ny * width)
            }
            path.close()
            drawPath(path = path, color = MOSS, alpha = alpha * 0.62f)
            for (i in 0 until 11) {
                val t = (i + 0.5f) / 11f
                val r = w * (0.007f + 0.017f * abs(sin(t * 6.3f + 2.1f))) * (1f - 0.55f * t)
                val off = r * (0.25f + 0.85f * abs(sin(t * 9.7f + 0.4f)))
                val cx = bodyR + dx * t + nx * off
                val cy = bodyB + dy * t + ny * off
                drawOval(
                    color = if (i % 3 == 1) MOSS_LIGHT else MOSS,
                    topLeft = Offset(cx - r, cy - r * 0.62f),
                    size = Size(r * 2f, r * 1.32f),
                    alpha = alpha * 0.58f
                )
            }
            // 接触线：整条可见底轮廓（前棱 → 右侧棱）压一道极窄的暗线。
            // 有这条线，箱体才是**坐在**地上；没有，苔只是散在脚边
            path.rewind()
            path.moveTo(bodyL, bodyB)
            path.lineTo(bodyR, bodyB)
            path.lineTo(bodyR + dx, bodyB + dy)
            drawPath(
                path = path,
                color = Color.Black,
                alpha = alpha * 0.30f,
                style = Stroke(width = w * 0.0040f)
            )
        }
    }
    drawFogBand(HERO_BOTTOM + 0.09f, 0.13f, top, alpha * DISTANT_ALPHA * 1.6f)
}

/**
 * folklore 近景那几棵松：(x 比例, 树高比例, 半宽比例)。
 *
 * 手放而不是随机：它们要正好填住钢琴右边那一片空（原来是那栋木屋），
 * 又不能站到钢琴前面去。树高 0.34–0.44h 而林地线在 0.38h —— 树尖顶出屏幕，
 * 「近」就是这么来的。
 */
/**
 * folklore 松林：**手放**的树干，不用随机。
 *
 * 随机只挪位置和大小、形状一模一样，屏幕上就是一片锯齿（上一版 27 棵三角松，需求方
 * 原话「画的有点乱」）。这里全部手放，间距刻意不等 —— 有两根挨着成丛，也有一大段空。
 *
 * 远层 12 根（[FOLK_FAR_TRUNKS]），脚底一律落进地平雾里（0.40–0.412h），不到地面 ——
 * 第一版让它们停在 0.29–0.32h，屏幕上就是十几根顶着天的淡竖线、脚在半空里齐齐断掉，
 * 「树看不清」有一半是这么来的。中层 6 根（[FOLK_MID_TRUNKS]）、近景粗杆 2 根
 * （[FOLK_NEAR_TRUNKS]）、雾里的杉 2 棵（[FOLK_MIST_FIRS]）、画框边上的两个杉木楔子
 * （[FOLK_NEAR_PINES]）、细梢 8 根（[FOLK_BRANCHES]）。
 */
private val FOLK_FAR_TRUNKS = floatArrayOf(
    0.028f, 0.0022f, 0.406f, -0.05f, 0.010f,
    0.072f, 0.0034f, 0.402f, -0.04f, -0.008f,
    0.128f, 0.0026f, 0.410f, -0.06f, 0.014f,
    0.196f, 0.0040f, 0.404f, -0.03f, -0.011f,
    0.252f, 0.0024f, 0.408f, -0.05f, 0.008f,
    0.318f, 0.0038f, 0.400f, -0.04f, -0.013f,
    0.396f, 0.0028f, 0.412f, -0.05f, 0.011f,
    0.468f, 0.0044f, 0.402f, -0.03f, -0.009f,
    0.560f, 0.0026f, 0.408f, -0.06f, 0.013f,
    0.660f, 0.0036f, 0.404f, -0.04f, -0.010f,
    0.858f, 0.0024f, 0.410f, -0.05f, 0.009f,
    0.928f, 0.0042f, 0.402f, -0.03f, -0.012f
)

private val FOLK_MID_TRUNKS = floatArrayOf(
    0.158f, 0.0062f, 0.408f, -0.04f, 0.014f,
    0.352f, 0.0084f, 0.404f, -0.04f, -0.011f,
    0.512f, 0.0056f, 0.410f, -0.04f, 0.016f,
    0.700f, 0.0090f, 0.402f, -0.04f, -0.012f,
    0.824f, 0.0068f, 0.406f, -0.04f, 0.010f,
    0.612f, 0.0048f, 0.412f, -0.04f, -0.009f
)

private val FOLK_NEAR_TRUNKS = floatArrayOf(
    0.104f, 0.0192f, 0.412f, -0.06f, 0.020f,
    0.736f, 0.0140f, 0.408f, -0.06f, -0.015f
)

/**
 * 雾里那两棵杉：`(x, 树高, 半宽, 强度)`。**只留剪影的形、不压暗** ——
 * 「这是松林」这句话靠它们说，但它们是远景，不是原来那几棵挡在琴前面的深色大三角。
 */
private val FOLK_MIST_FIRS = floatArrayOf(
    0.285f, 0.30f, 0.070f, 0.20f,
    0.868f, 0.28f, 0.065f, 0.17f
)

/**
 * 顶上的叶丛：`(挂点x, 垂到y, 枝展w, 茎的倾向, 强度)`。
 *
 * **挂点全部钉在树干上**（0.104 / 0.252 / 0.318 / 0.468 / 0.700 / 0.858 都在
 * [FOLK_FAR_TRUNKS] / [FOLK_MID_TRUNKS] / [FOLK_NEAR_TRUNKS] 里）—— 叶子长在树上。
 * 上一版挂点按 0.03 / 0.23 / 0.42… 差不多等距地排开，屏幕上就是一排从天花板垂下来的
 * 花簇（需求方这轮原话「树冠画的像垂下来的花簇一样」）。
 */
private val FOLK_SPRAYS = floatArrayOf(
    0.104f, 0.152f, 0.112f, -0.30f, 0.30f,
    0.252f, 0.086f, 0.076f, 0.24f, 0.23f,
    0.318f, 0.112f, 0.086f, -0.18f, 0.25f,
    0.468f, 0.130f, 0.096f, 0.18f, 0.26f,
    0.700f, 0.146f, 0.106f, 0.28f, 0.28f,
    0.858f, 0.090f, 0.078f, -0.22f, 0.22f
)

/** 近一档那三挂（0/2/4 号）的叶团不透明度 */
private const val SPR_ALPHA_NEAR = 0.30f

/** 远一档那三挂（1/3/5 号）的叶团不透明度与缩放：顶上那排叶子要有厚度 */
private const val SPR_ALPHA_FAR = 0.185f
private const val SPR_FAR_DEPTH = 0.82f
private const val SPR_FAR_SPAN = 0.86f

/** 细梢：`(x, 根y, 末端x, 末端y, 根半宽w, 梢半宽w)`。根都长在杆上 */
private val FOLK_BRANCHES = floatArrayOf(
    0.104f, 0.130f, 0.196f, 0.096f, 0.0026f, 0.0011f,
    0.104f, 0.176f, 0.038f, 0.140f, 0.0022f, 0.0010f,
    0.104f, 0.232f, 0.182f, 0.198f, 0.0018f, 0.0008f,
    0.736f, 0.150f, 0.820f, 0.116f, 0.0020f, 0.0009f,
    0.736f, 0.196f, 0.662f, 0.164f, 0.0017f, 0.0008f,
    0.352f, 0.156f, 0.284f, 0.128f, 0.0019f, 0.0008f,
    0.700f, 0.118f, 0.770f, 0.090f, 0.0022f, 0.0009f,
    0.512f, 0.140f, 0.578f, 0.114f, 0.0018f, 0.0008f
)

/** 近景杉木剪影：`(x, 树高, 半宽w)`。x 落在画框外 —— 只露出两个楔子当画框 */
private val FOLK_NEAR_PINES = floatArrayOf(
    -0.062f, 0.44f, 0.108f,
    1.062f, 0.46f, 0.112f
)

// ─────────────────────── 9 · evermore ───────────────────────

/**
 * 一段递归分叉的枯枝。
 *
 * [depth] 每降一级，长度 ×0.72、线宽 ×0.66、左右各偏 [SPREAD_DEG] 度。三级以上才读得出
 * 「枝」；两级只是一个 Y。分叉角度用 [bend] 做非对称扰动 —— 完全对称的二叉树是数学图形，
 * 不是树。
 */
private fun DrawScope.drawBranch(
    x: Float,
    y: Float,
    length: Float,
    angleDeg: Float,
    width: Float,
    depth: Int,
    bend: Float,
    color: Color,
    alpha: Float
) {
    if (depth <= 0 || length < 2f) return
    val rad = angleDeg / 180f * PI.toFloat()
    val ex = x + cos(rad) * length
    val ey = y + sin(rad) * length
    drawLine(
        color = color,
        start = Offset(x, y),
        end = Offset(ex, ey),
        strokeWidth = width.coerceAtLeast(1f),
        alpha = alpha,
        cap = StrokeCap.Round
    )
    val next = length * 0.72f
    val nextW = width * 0.66f
    // bend 让两侧的偏角不等，且随深度变号，长出来才不像剪纸
    drawBranch(ex, ey, next, angleDeg - SPREAD_DEG * (1f + bend), nextW, depth - 1, -bend * 0.8f, color, alpha)
    drawBranch(ex, ey, next, angleDeg + SPREAD_DEG * (1f - bend), nextW, depth - 1, -bend * 0.6f, color, alpha)
    // 每隔一级多抽一根短枝：只有二叉的树太规整
    if (depth >= 3) {
        drawBranch(ex, ey, next * 0.6f, angleDeg + SPREAD_DEG * bend * 2.4f, nextW * 0.7f, depth - 2, bend, color, alpha)
    }
}

private const val SPREAD_DEG = 26f

/** 上缘那根主枝分几段画。段数决定粗细过渡的平滑度，14 段在 1440px 宽上看不出折角。 */
private const val BOUGH_SEGMENTS = 14

/** 主枝中点相对两端的下垂量。枝是被自己的重量压弯的，一条水平直线读作钢管。 */
private const val BOUGH_SAG = 0.030f

/** 主枝上某个横向位置的高度。灯笼的吊线与柳条的起点都得挂在这条曲线上。 */
private fun boughY(h: Float, top: Float, fx: Float): Float =
    top + 4f * h * BOUGH_SAG * fx * (1f - fx)

/**
 * 垂柳的柳条：(起点 x, 长度[h 比], 末梢横向漂移[w 比], 摆动相位偏移)。
 *
 * 前四条已经铺满左中右，低内存机只画前四条（见 [drawBranchLanterns] 的 witheCount）。
 * 相位互不相同，六条因此不会同步摆动 —— 同步的一排读作一块布。
 */
private val WILLOW_WITHES = floatArrayOf(
    0.08f, 0.30f, -0.048f, 0.00f,
    0.55f, 0.25f, 0.052f, 0.63f,
    0.92f, 0.21f, -0.030f, 0.27f,
    0.30f, 0.34f, 0.036f, 0.44f,
    0.72f, 0.31f, -0.040f, 0.12f,
    0.19f, 0.22f, 0.028f, 0.81f
)

/** 一条柳条分几段画。段数同时决定挂几片叶（第 3、5、7、9 段各一片）。 */
private const val WITHE_SEGMENTS = 9

/**
 * 主枝 + 垂柳 + 金线 + 挂在枝上的灯笼。
 *
 * evermore 是 folklore 的冬季姊妹作，官方视觉是枯枝、格纹与那件橙棕大衣。金线来自
 * *willow* 的 MV（一根金线牵着人走过整支片子），所以它必须**贯穿整个画面**，
 * 而不是缩在角落里当装饰；垂柳与它同源。
 */
private fun DrawScope.drawBranchLanterns(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float,
    lowRam: Boolean
) {
    val w = size.width
    val h = size.height
    // 低内存机少画两条柳条。数组的前四条已经铺满左中右，砍掉的是补密度的那两条
    val witheCount = if (lowRam) 4 else 6

    // 横过上缘的那根主枝。**必须有**：第四轮截图里三丛枝直接从屏幕上缘垂下来，
    // 上端没有任何依附，整屏读作「一堆悬空的树杈」甚至倒吊的根系。
    // 有了这根粗枝，三丛才成为「从枝上垂下来的细枝」，灯笼也才有东西可挂。
    // 走一条轻微下垂的二次曲线并且**由粗到细**（左端 0.030w 收到右端 0.012w）——
    // 等宽的一条横杠是脚手架钢管
    // 0.058h 而不是 0.022h：主枝的两端要落在状态栏以下。第六轮截图里那两端
    // 正好穿过时间与信号图标，读作「一根横在时钟后面的棍子」
    val boughLeft = h * 0.058f
    for (seg in 0 until BOUGH_SEGMENTS) {
        val t0 = seg / BOUGH_SEGMENTS.toFloat()
        val t1 = (seg + 1) / BOUGH_SEGMENTS.toFloat()
        // 二次曲线：两端在 boughLeft，中点下垂 BOUGH_SAG
        val y0 = boughLeft + 4f * h * BOUGH_SAG * t0 * (1f - t0)
        val y1 = boughLeft + 4f * h * BOUGH_SAG * t1 * (1f - t1)
        drawLine(
            color = deep,
            start = Offset(t0 * w, y0),
            end = Offset(t1 * w, y1),
            strokeWidth = w * (0.030f - 0.018f * t0),
            alpha = alpha * SILHOUETTE_ALPHA * 1.15f
        )
    }

    // 垂下来的柳条：六条，各自的横向漂移与摆动相位都不同。
    //
    // 上一版这里是三丛 [drawBranch] 的递归枯枝，从主枝**朝下**长。递归枝的形状是
    // 「根部粗、越分越细、末端散开成一把」，倒过来朝下长满上半屏之后，第七轮截图整屏
    // 读作一团**倒吊的根系**，而不是树。柳条的形状恰好相反：通身都细、一根单线不分叉、
    // 只在末梢卷出去 —— 没有分叉就不会被读成根。willow 又正是 evermore 的第一首，
    // 那根贯穿画面的金线也来自同一支 MV，垂柳与它是一套东西。
    //
    // 干叶全部攒进 [path] 最后一次填完：逐片 drawPath 会让同色交叠处叠深一档。
    path.rewind()
    for (i in 0 until witheCount) {
        val x0 = w * WILLOW_WITHES[i * 4]
        val len = h * WILLOW_WITHES[i * 4 + 1]
        val drift = w * WILLOW_WITHES[i * 4 + 2]
        val sway = sin((phase + WILLOW_WITHES[i * 4 + 3]) * TAU) * w * 0.010f
        val y0 = boughY(h, boughLeft, WILLOW_WITHES[i * 4])
        var px = x0
        var py = y0
        for (seg in 1..WITHE_SEGMENTS) {
            val t = seg / WITHE_SEGMENTS.toFloat()
            // 横向偏移走 t²：柳条上半段几乎垂直，末梢才卷出去。风也只吹得动末梢
            val nx = x0 + (drift + sway) * t * t
            val ny = y0 + len * t
            drawLine(
                color = deep,
                start = Offset(px, py),
                end = Offset(nx, ny),
                strokeWidth = (w * (0.0075f - 0.0050f * t)).coerceAtLeast(1f),
                alpha = alpha * SILHOUETTE_ALPHA * 1.1f,
                cap = StrokeCap.Round
            )
            // 干叶：中段以下每隔一段挂一片，左右交替。叶形按**当前段的切线**定向，
            // 所以每片都顺着柳条长；轴对齐的椭圆在这个尺寸上只读作一个点
            if (seg >= 3 && seg % 2 == 1) {
                val dl = hypot(nx - px, ny - py)
                if (dl > 0.5f) {
                    val ux = (nx - px) / dl
                    val uy = (ny - py) / dl
                    val side = if (((i + seg) % 4) < 2) 1f else -1f
                    val ll = h * 0.020f
                    val lw = ll * 0.30f
                    // 叶尖在切线上再偏 34°，叶子因此是斜挂着的，不是柳条的延长线
                    val ax = ux * 0.83f - uy * side * 0.56f
                    val ay = uy * 0.83f + ux * side * 0.56f
                    val mx = nx + ax * ll * 0.5f
                    val my = ny + ay * ll * 0.5f
                    path.moveTo(nx, ny)
                    path.quadraticTo(mx - ay * lw, my + ax * lw, nx + ax * ll, ny + ay * ll)
                    path.quadraticTo(mx + ay * lw, my - ax * lw, nx, ny)
                }
            }
            px = nx
            py = ny
        }
    }
    drawPath(path = path, color = mid, alpha = alpha * 0.52f)

    // 金线：*willow* MV 里那根牵着人走的金线，所以它得贯穿左右。
    //
    // 两轮都画错在同一件事上：**当成波形函数在画，而不是当成一根挂着的线**。
    // 第一版 freq 2、振幅 0.055h、alpha 0.62 —— 一道饱和的黄色大波浪，读作荧光笔划痕；
    // 第二版叠了 3 与 7 两个频率想让它「松」一点，结果是锯齿，读作心率线。
    // 两版都还压了一层更宽的同色微光，那层把它糊成一条黄雾带，更像笔触。
    //
    // 线的真实形状是**悬链**：两端高、中间垂，一条曲线，没有周期。所以现在是一段二次
    // 贝塞尔，两端挂在两侧的枝上（[THREAD_ANCHOR_Y]），中点垂到 0.40h，
    // 只随 [phase] 极轻地上下荡 —— 风吹一根线只会让它整体晃，不会让它变出波形。
    val threadSag = h * (0.40f + sin(phase * TAU) * 0.006f)
    path.rewind()
    path.moveTo(-w * 0.02f, h * THREAD_ANCHOR_Y)
    path.quadraticTo(w * 0.5f, threadSag, w * 1.02f, h * (THREAD_ANCHOR_Y - 0.020f))
    drawPath(
        path = path,
        color = GOLD,
        alpha = alpha * 0.46f,
        style = Stroke(width = (w * 0.0035f).coerceAtLeast(1f), cap = StrokeCap.Round)
    )
    // 两端各一小段绕在枝上的圈：线得**系**在什么上面，否则两头是凭空断掉的
    for (side in 0..1) {
        val ax = if (side == 0) w * 0.055f else w * 0.945f
        val ay = h * (THREAD_ANCHOR_Y + 0.012f * (1f - side))
        drawCircle(
            color = GOLD,
            radius = w * 0.011f,
            center = Offset(ax, ay),
            alpha = alpha * 0.40f,
            style = Stroke(width = (w * 0.0030f).coerceAtLeast(1f))
        )
    }

    // 灯笼：吊线 + 顶盖 + 上下收窄的灯身 + 竖骨 + 底坠。三盏各自摆动、各自呼吸。
    // 吊线长度收到 0.14–0.26h，三盏灯身全落在 HERO_BOTTOM 以内
    val lanterns = floatArrayOf(
        // x, 吊线长度比例, 尺寸比例, 相位偏移
        0.24f, 0.20f, 1.00f, 0.00f,
        0.60f, 0.27f, 0.82f, 0.37f,
        0.80f, 0.14f, 0.68f, 0.68f
    )
    for (i in 0 until 3) {
        val hangX = w * lanterns[i * 4]
        val cordLen = h * lanterns[i * 4 + 1]
        val scale = lanterns[i * 4 + 2]
        val shift = lanterns[i * 4 + 3]
        // 挂点落在主枝的曲线上。写死 h*0.04 的话吊线从屏幕上缘出发，
        // 会从主枝里穿过去 —— 灯笼看着不是挂在枝上而是吊在天花板上
        val hangY = boughY(h, boughLeft, lanterns[i * 4])
        // 摆动：吊线像单摆一样绕挂点转 ±2.4°，相位错开所以三盏不同步
        val swing = sin((phase + shift) * TAU) * 2.4f
        rotate(degrees = swing, pivot = Offset(hangX, hangY)) {
            // 0.088w 而不是 0.062w：第四轮那三盏灯在 1440px 宽的屏上只有 89px，
            // 顶盖与底座两条横边占掉一半高度，读出来像三块寿司
            val bodyW = w * 0.088f * scale
            val bodyH = bodyW * 1.35f
            val cy = hangY + cordLen
            drawLine(
                color = deep,
                start = Offset(hangX, hangY),
                end = Offset(hangX, cy - bodyH / 2f),
                strokeWidth = 1.5f,
                alpha = alpha * 0.55f
            )
            // 顶盖与底座：两条短横，灯笼的上下沿。0.06 高而不是 0.10 ——
            // 灯身只有 0.12w 高，两条 0.10 的横边会各吃掉一成
            for (e in 0..1) {
                val edgeY = if (e == 0) cy - bodyH / 2f else cy + bodyH / 2f
                drawRect(
                    color = deep,
                    topLeft = Offset(hangX - bodyW * 0.58f, edgeY - bodyH * 0.03f),
                    size = Size(bodyW * 1.16f, bodyH * 0.06f),
                    alpha = alpha * 0.6f
                )
            }
            // 灯身：上下窄、中间宽的六边形
            path.rewind()
            path.moveTo(hangX - bodyW * 0.34f, cy - bodyH / 2f)
            path.lineTo(hangX + bodyW * 0.34f, cy - bodyH / 2f)
            path.lineTo(hangX + bodyW * 0.5f, cy)
            path.lineTo(hangX + bodyW * 0.34f, cy + bodyH / 2f)
            path.lineTo(hangX - bodyW * 0.34f, cy + bodyH / 2f)
            path.lineTo(hangX - bodyW * 0.5f, cy)
            path.close()
            val breath = 0.72f + 0.28f * sin((phase * 2f + shift) * TAU)
            drawPath(path = path, color = LAMP_WARM, alpha = alpha * 0.5f * breath)
            drawUnitGlow(GLOW_WARM, Offset(hangX, cy), bodyW * 4.2f, alpha * 0.42f * breath)
            // 竖骨：三条，把灯身分成纸糊的几面
            for (r in 0 until 3) {
                val f = -0.22f + r * 0.22f
                drawLine(
                    color = deep,
                    start = Offset(hangX + bodyW * f, cy - bodyH * 0.45f),
                    end = Offset(hangX + bodyW * f, cy + bodyH * 0.45f),
                    strokeWidth = 1f,
                    alpha = alpha * 0.42f
                )
            }
            drawCircle(color = deep, radius = bodyW * 0.07f, center = Offset(hangX, cy + bodyH * 0.62f), alpha = alpha * 0.5f)
        }
    }
    // 两条雾：上缘一条冷的把枝梢化开，下缘一条暖的托住灯笼
    drawFogBand(0.08f, 0.14f, mid, alpha * DISTANT_ALPHA * 0.9f)
    drawFogBand(HERO_BOTTOM, 0.16f, top, alpha * DISTANT_ALPHA * 1.5f)
}

/**
 * 金线两端挂在枝上的高度。
 *
 * 0.255h：三丛枝的二级分叉大致落在 0.24–0.28h，线系在那一带才像是**挂在枝上**的。
 * 采样常量已经不需要了 —— 悬链是一段二次贝塞尔，交给 `quadraticTo` 一次画完，
 * 用不着自己打点。
 */
private const val THREAD_ANCHOR_Y = 0.255f

// ─────────────────────── 10 · Midnights ───────────────────────

/** 五角星，[rotationDeg] 是自转。写成把点塞进给定 Path，好让调用方复用同一条。 */
private fun starInto(path: Path, cx: Float, cy: Float, outer: Float, rotationDeg: Float) {
    val inner = outer * 0.42f
    val base = rotationDeg / 180f * PI.toFloat() - PI.toFloat() / 2f
    path.rewind()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outer else inner
        val a = base + i * PI.toFloat() / 5f
        val x = cx + cos(a) * r
        val y = cy + sin(a) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

/** 罗马数字预排的参考字号（px）。真实大小在 draw 阶段按表盘半径缩放。 */
private const val NUMERAL_REFERENCE_PX = 100f

/**
 * 表盘角度基准：**0° = 12 点，顺时针为正**。
 *
 * 与整点刻度同一套（刻度那两处的 `i / 12f * TAU - PI/2` 就是这条基准）——
 * 换成「3 点方向为 0°」会让刻度、罗马数字与指针各自算一套，早晚错位。
 */
internal const val MIDNIGHT_CLOCK_ANGLE_BASE_DEG: Float = 0f

/** 卡出现后多久起转。卡片前 400ms 在长出来，眼神还在入场动画上。 */
internal const val MIDNIGHT_WIND_START_MS: Long = 800L

/** 主程时长：分针走两整圈、时针从 2 点推到 3 点。 */
internal const val MIDNIGHT_WIND_MS: Float = 1_800f

/**
 * 落位后的回吸时长。
 *
 * 和主程分开画两段：主程是一次**单调**的走针，过冲只是末尾那一小下。
 * 揉进同一条曲线里（比如 `easeOutBack`）会让分针在最后小半圈里明显倒着走 ——
 * 钟的指针没有倒转的道理，读出来是素材卡帧。
 */
internal const val MIDNIGHT_SETTLE_MS: Float = 260f

/**
 * 回吸的峰值角度（度）。**负** = 往回转。
 *
 * 3.6° 是掐着表盘刻度定的：分针 60 格、每格 6°，回吸约占 0.6 格 ——
 * 看得见「顿了一下」，但不足以让分针明显离开 12 点那一格。
 */
internal const val MIDNIGHT_SETTLE_DEG: Float = 3.6f

/** 满盘的度数。 */
private const val MIDNIGHT_FULL_TURN_DEG: Float = 360f

/**
 * 时针与分针的角速度比：分针一圈 = 时针一格，1:12。
 *
 * **这不是配上去的，是一条约束**：两根针由同一组齿轮驱动，各自走多少只能差这个倍数。
 * 起手 2:00、落位 3:00 时两针各差 30°，分针因此**恰好走一整圈** ——
 * 想让它多转几圈就得把落位改成别的时刻，见 [midnightHandAngles]。
 */
private const val MIDNIGHT_HAND_GEAR_RATIO: Float = 12f

/** 起手姿态（时针在 2 点）。落位是 3:00，分针两整圈回到 12 —— 两个都是整点。 */
private const val MIDNIGHT_HOUR_START_DEG: Float = 60f

/** 落位姿态：严格的 3:00:00。 */
private const val MIDNIGHT_HOUR_END_DEG: Float = 90f

/** 0° 在 12 点、顺时针为正的表盘上，这根指针此时指向几点（单位：度，0..360）。 */
internal fun midnightClockAngleOf(angleDeg: Float): Float =
    (angleDeg - MIDNIGHT_CLOCK_ANGLE_BASE_DEG).mod(MIDNIGHT_FULL_TURN_DEG)

/**
 * 这一毫秒两根指针各自的角度（0° = 12 点，顺时针为正）。
 *
 * ## 为什么分针只走一圈（以及为什么不能是三圈）
 *
 * 两根针是一组齿轮上的刚性件：**分针走多少，时针只能按 1:12 跟多少**
 * （[MIDNIGHT_HAND_GEAR_RATIO]）。落位钉死 3:00、起手又必须是个整点，两个端点就此
 * 反推出唯一的解 —— 两针各差 30°（2:00 → 3:00），分针**恰好一整圈**。
 *
 * 曾经按「分针转两圈更热闹」写过一版：两圈意味着时针要走 60°，落位就变成 4:00。
 * 想保留 3:00 的落位又想多转几圈，只能让两针脱开、各转各的 —— 屏幕上立刻读成
 * 一根被单独拧过的针。**要改圈数就改落位时刻，两者只能一起动**。
 *
 * ## 过冲为什么是加在「回吸窗口里的一记负冲量」
 *
 * 主程用 `1 - cos` 收在零速上，过冲是在那之后再叠一个负向的 `sin` 包络
 * （[MIDNIGHT_SETTLE_DEG]）：t=0 与 t=1 都严格为 0，所以「落位严格 3:00」
 * 不受它影响，而中段那 3.6° 的往回一顿就是真钟走到整点的那一下。
 *
 * 两根针用的是**同一个角度冲量**而不是同一个比例：分针在它自己的位置上多转 3.6°、
 * 时针在原处多退 3.6°，读起来是「各自顿了一下」。按比例给（时针 0.3°）时针就完全看不出来。
 *
 * @param eraMs 本段已过的毫秒。负数或超出主程 = 停在对应的端点姿态
 * @return 时针角度 to 分针角度（分针可超过 360°，那表示它已经转过整圈）
 */
internal fun midnightHandAngles(eraMs: Long): Pair<Float, Float> {
    val hourStart = MIDNIGHT_HOUR_START_DEG
    // 分针的行程**从时针的行程推出来**，不是另写一个常量：写两个独立常量就会允许
    // 「两针各走各的」这种组合存在，而那是这面钟物理上做不到的事
    val hourSpan = MIDNIGHT_HOUR_END_DEG - MIDNIGHT_HOUR_START_DEG
    val minuteSpan = hourSpan * MIDNIGHT_HAND_GEAR_RATIO

    val since = eraMs - MIDNIGHT_WIND_START_MS
    // 主程之前（含换张淡变期传来的 -1）：停在起手姿态。
    // 起手是个真姿态而不是「还没开始画」—— 换张那 500ms 里这张舞台以 0.5 的 alpha 露着，
    // 指针不能凭空长出来
    if (since <= 0L) return hourStart to 0f
    if (since >= MIDNIGHT_WIND_MS) {
        if (since >= MIDNIGHT_WIND_MS + MIDNIGHT_SETTLE_MS) {
            return MIDNIGHT_HOUR_END_DEG to minuteSpan
        }
        val t = (since - MIDNIGHT_WIND_MS) / MIDNIGHT_SETTLE_MS
        val back = sin(PI.toFloat() * t) * MIDNIGHT_SETTLE_DEG
        return (MIDNIGHT_HOUR_END_DEG - back) to (minuteSpan - back)
    }

    // 分针倒着算时针：`minute / 12` 就是时针这一程走过的角度，传动比只有这一个来源
    val minute = minuteSpan * easeOutWind(since / MIDNIGHT_WIND_MS)
    return (hourStart + minute / MIDNIGHT_HAND_GEAR_RATIO) to minute
}

/**
 * 走针的缓动：起手立刻就有速度、落到终点时速度为零。
 *
 * `1 - cos` 而不是三次 ease-out：后者的起步速度是 0，指针从完全静止里「想」一下才走 ——
 * 上弦是被人推了一把，`sin` 在 t=0 处的非零斜率才对。
 */
private fun easeOutWind(t: Float): Float = 1f - cos(PI.toFloat() / 2f * t.coerceIn(0f, 1f))

/** XII 在 12 点位，顺时针排到 XI —— 与 drawMidnightClock 的整点刻度同序。 */
private val ROMAN_NUMERALS =
    listOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")

/**
 * 表盘的 12 个罗马数字预排一次。
 *
 * `TextMeasurer` 只能在组合阶段用（与手链字母珠 `rememberBeadLetterLayouts` 同一套路）：
 * 这里按参考字号排好，draw 阶段再缩放到表盘半径。衬线体加粗 ——
 * 无衬线的罗马数字读作一排代码。
 */
@Composable
private fun rememberClockNumeralLayouts(): List<TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val style = TextStyle(
            fontSize = with(density) { NUMERAL_REFERENCE_PX.toSp() },
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold
        )
        ROMAN_NUMERALS.map { measurer.measure(it, style) }
    }
}

/**
 * 午夜星空 + 一面星形指针的钟 + 薰衣草雾。
 *
 * Midnights 的封面是那只手举着的打火机与深蓝，而「午夜」这个词本身需要一面钟才落得实。
 * 指针**落位在 3:00**（凌晨三点），但它是从上弦的位置转过去的：卡出现 800ms 后
 * 分针走一整圈、时针按 1:12 跟着从 2 点推到 3 点，末尾带一下回吸
 * （行程为什么是一圈、不是几圈，见 [midnightHandAngles]）。
 * 星星的明灭是 [phase] 的**二倍频**，相位按序号错开，所以不会整片一起闪。
 *
 * @param eraMs 本段已过的毫秒，指针只吃这一个量（负数 = 停在起手姿态，见 [midnightHandAngles]）
 */
private fun DrawScope.drawMidnightClock(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float,
    eraMs: Long,
    shapes: BackdropShapes,
    numerals: List<TextLayoutResult>
) {
    val w = size.width
    val h = size.height

    // 地平线那一点余光：这张舞台三档全是深蓝，首档 top（#2A3A6B）是其中最亮的一档，
    // 铺在下缘当「城市把云底染亮的那一点」，星空才有上下
    drawFogBand(0.94f, 0.16f, top, alpha * DISTANT_ALPHA * 1.4f)

    // 星野
    val stars = shapes.stars
    for (i in 0 until stars.size / 3) {
        val sx = stars[i * 3] * w
        val sy = stars[i * 3 + 1] * h
        val r = stars[i * 3 + 2] * w
        val twinkle = 0.55f + 0.45f * sin((phase * 2f + i * 0.137f) * TAU)
        drawCircle(color = Color.White, radius = r, center = Offset(sx, sy), alpha = alpha * 0.75f * twinkle)
        // 每第七颗给一点散射，星空才有大小层次
        if (i % 7 == 0) {
            drawUnitGlow(GLOW_WHITE, Offset(sx, sy), r * 9f, alpha * 0.3f * twinkle)
        }
    }

    // 钟：表盘 + 表圈 + 60 格分刻度（整点加粗）+ 罗马数字 + 指向 3 点的星形指针。
    // 钟心抬到 0.24h：半径 0.26w 时表盘占 0.12–0.36h，整面都在 HERO_BOTTOM 以内
    val clockCenter = Offset(w * 0.50f, h * 0.24f)
    val clockR = w * 0.26f
    // 盘面先压暗一档，再用薰衣草描圈与刻度。
    // 表圈与刻度**不能用 top**（#2A3A6B）：那是这张舞台的首档底色，
    // 画在同一片深蓝上等于没画，第四轮截图里整面钟只剩两根指针，读作一支温度计。
    //
    // 盘面从 0.30 提到 0.78：0.30 时星野与那两条薰衣草斜雾**从表盘里穿过去**，
    // 第五轮截图上整面钟是一圈套在星空上的铁环，不是一块表盘。表盘是实心的珐琅面，
    // 它该把背后的星星挡住
    drawCircle(color = deep, radius = clockR * 0.96f, center = clockCenter, alpha = alpha * 0.78f)
    // 内圈：一道比表圈细的同心线，珐琅面与表圈之间的那道装饰线
    drawCircle(
        color = SwiftiePalette.Lavender,
        radius = clockR * 0.86f,
        center = clockCenter,
        alpha = alpha * 0.22f,
        style = Stroke(width = (w * 0.0025f).coerceAtLeast(1f))
    )
    drawCircle(
        color = SwiftiePalette.Lavender,
        radius = clockR,
        center = clockCenter,
        alpha = alpha * 0.55f,
        style = Stroke(width = w * 0.010f)
    )
    // 60 格分刻度。**这是「表盘」与「圆环加十二道杠」的分界** ——
    // 只有 12 道刻度的圆盘读作罗盘或方向舵，钟面的特征是那一圈细密的分格
    for (i in 0 until 60) {
        if (i % 5 == 0) continue
        val a = i / 60f * TAU - PI.toFloat() / 2f
        drawLine(
            color = SwiftiePalette.Lavender,
            start = Offset(clockCenter.x + cos(a) * clockR * 0.90f, clockCenter.y + sin(a) * clockR * 0.90f),
            end = Offset(clockCenter.x + cos(a) * clockR * 0.94f, clockCenter.y + sin(a) * clockR * 0.94f),
            strokeWidth = (w * 0.002f).coerceAtLeast(1f),
            alpha = alpha * 0.34f
        )
    }
    for (i in 0 until 12) {
        val a = i / 12f * TAU - PI.toFloat() / 2f
        val major = i % 3 == 0
        val r0 = clockR * (if (major) 0.80f else 0.87f)
        drawLine(
            color = SwiftiePalette.Lavender,
            start = Offset(clockCenter.x + cos(a) * r0, clockCenter.y + sin(a) * r0),
            end = Offset(clockCenter.x + cos(a) * clockR * 0.94f, clockCenter.y + sin(a) * clockR * 0.94f),
            strokeWidth = if (major) w * 0.008f else w * 0.004f,
            alpha = alpha * (if (major) 0.75f else 0.5f),
            cap = StrokeCap.Round
        )
    }
    // 罗马数字压在刻度内侧、指针下：预排在组合阶段（见 rememberClockNumeralLayouts），
    // 这里只按表盘半径缩放平移。3 点的 III 正好迎着时针，12 点的 XII 压在分针根上 ——
    // 指针画在数字之上，和真表一样
    val numeralScale = clockR * 0.145f / NUMERAL_REFERENCE_PX
    numerals.forEachIndexed { i, layout ->
        val a = i / 12f * TAU - PI.toFloat() / 2f
        val nx = clockCenter.x + cos(a) * clockR * 0.65f
        val ny = clockCenter.y + sin(a) * clockR * 0.65f
        val nw = layout.size.width * numeralScale
        val nh = layout.size.height * numeralScale
        withTransform({
            translate(nx - nw / 2f, ny - nh / 2f)
            scale(scaleX = numeralScale, scaleY = numeralScale, pivot = Offset.Zero)
        }) {
            drawText(
                textLayoutResult = layout,
                color = SwiftiePalette.Lavender,
                alpha = alpha * 0.72f
            )
        }
    }
    // 表蒙子的反光：左上（9 点到 12 点之间）**两道细亮条**。玻璃靠它读出来，
    // 少了它表盘只是一块涂黑的圆板。
    //
    // 第六轮量过像素：那一版（宽 0.20R、alpha 0.10）在左上采到 (46,52,80)，
    // 而下半盘因为压着薰衣草斜雾是 (57,56,91) —— **反光比盘面还暗**，读出来是一团脏。
    // 第七轮收到 0.10R / 0.26 仍然不对：白色叠在深靛底上必然去饱和成灰，一道 0.10R 宽
    // 的灰弧在盘面上读作一条虫或一片贴歪的胶带。
    //
    // 玻璃反光的辨识特征不是「一块亮区」而是**两道平行的细高光**（长的贴着蒙子边缘、
    // 短的在它内侧），所以关键是「细 + 亮」而不是「宽 + 淡」：同样的白，
    // 铺开就是雾，收窄就是镜面。
    for (g in 0..1) {
        val gr = clockR * (if (g == 0) 0.90f else 0.70f)
        drawArc(
            color = Color.White,
            startAngle = if (g == 0) 196f else 209f,
            sweepAngle = if (g == 0) 58f else 21f,
            useCenter = false,
            topLeft = Offset(clockCenter.x - gr, clockCenter.y - gr),
            size = Size(gr * 2f, gr * 2f),
            alpha = alpha * (if (g == 0) 0.55f else 0.38f),
            style = Stroke(
                width = clockR * (if (g == 0) 0.024f else 0.016f),
                cap = StrokeCap.Round
            )
        )
    }
    // 指针：时针短而粗、分针长而细，角度从上弦进度来（落位 3:00）。星尖收在指针末端
    val (hourAngle, minuteAngle) = midnightHandAngles(eraMs)
    for (i in 0..1) {
        val len = if (i == 0) 0.46f else 0.78f
        val thick = if (i == 0) 0.016f else 0.010f
        // i = 0 时针短而粗，i = 1 分针长而细。角度约定见 midnightHandAngles：
        // 0° 在 12 点、顺时针为正，所以终点要先把角度扳 -90° 才是屏幕坐标里的正弦余弦
        val a = ((if (i == 0) hourAngle else minuteAngle) - 90f) / 180f * PI.toFloat()
        val end = Offset(
            clockCenter.x + cos(a) * clockR * len,
            clockCenter.y + sin(a) * clockR * len
        )
        drawLine(
            color = SwiftiePalette.Lavender,
            start = clockCenter,
            end = end,
            strokeWidth = w * thick,
            alpha = alpha * 0.62f,
            cap = StrokeCap.Round
        )
        starInto(path, end.x, end.y, w * 0.026f, phase * 360f / 5f)
        drawPath(path = path, color = Color.White, alpha = alpha * 0.68f)
    }
    drawCircle(color = SwiftiePalette.Lavender, radius = w * 0.012f, center = clockCenter, alpha = alpha * 0.7f)
    drawUnitGlow(GLOW_WHITE, clockCenter, clockR * 2.6f, alpha * 0.14f)

    // 薰衣草雾：两条斜过画面。旋转一个固定角度再铺横带，比写一条斜向渐变省事 ——
    // 但必须给 overscanX，否则转过 -14° 之后带子的两个端头会转进画面
    rotate(degrees = -14f, pivot = Offset(w / 2f, h / 2f)) {
        drawFogBand(
            0.30f + sin(phase * TAU) * 0.015f, 0.20f,
            SwiftiePalette.Lavender, alpha * DISTANT_ALPHA, overscanX = 0.35f
        )
        drawFogBand(
            0.78f - sin(phase * TAU) * 0.015f, 0.26f,
            mid, alpha * DISTANT_ALPHA * 1.3f, overscanX = 0.35f
        )
    }
}

// ─────────────────────── 11 · The Tortured Poets Department ───────────────────────

/**
 * 台灯光锥 + 一台**正对观众**的打字机，坐在屏幕底下。
 *
 * ## 卡片就是这台机器吐上来的那张纸
 *
 * TTPD 段开头 `SwiftieTimeline.TTPD_PREROLL_MS` = 1400ms 里屏幕上没有卡片：机器自己
 * 敲字、滑架逐格左移，1250ms 一次回车横扫回位，字打在滚筒上那截露头的纸上；然后纸
 * 开始往上走 —— 那张纸就是曲目卡片（揭示由 `SwiftieEraCard` 的 `feedProgress` 做，
 * 下缘钉死在出纸口上、上缘上移）。真机正是这个方向：印字点在滚筒上不动，纸往上卷，
 * 越早打的行越靠上 —— 第 1 首在纸的最顶上，歌名从上到下读下去就是打字的顺序。
 *
 * 出纸口 [card] 的下缘必须坐在卡片下缘上、滚筒必须与卡片同宽：31 首的卡片高度按屏高
 * 派生、宽度在平板上封顶 480dp，写死比例换台设备纸就会比机器宽出一截。卡片在布局层
 * 被抬高了 `TTPD_MACHINE_RESERVE`（见 `SwiftieErasStage`），机器的滚筒、暗腔与两肩
 * 全落在那段里；键盘越往下越被时间轴挡住，属预期。
 *
 * ## 为什么换成正视
 *
 * 机器原先坐在卡片**上缘**（俯视、纸往下挂）。挪到底下之后纵向预算不再只有上缘那
 * 0.137h —— 从卡片下缘到屏幕底整段都是机器的，于是换回最经典的正对视角：滚筒横在
 * 顶上、字锤从暗腔里朝滚筒抬、四排键从后往前渐大、回车杆在左端翘起。纸从滚筒后头
 * 升上来，机器的每一层都在纸的下沿之下，不会被纸挡。
 */
private fun DrawScope.drawTypewriterDesk(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float,
    eraMs: Long,
    card: Rect
) {
    val w = size.width
    val h = size.height

    // 台灯光锥：自左上打向右下，落在底部那台机器上。锥体本身是竖直的，绕锥顶转 34°。
    // 亮度随相位极轻地呼吸（白炽灯的电流声），幅度只有 ±0.02
    val apex = Offset(w * 0.06f, -h * 0.03f)
    rotate(degrees = 34f, pivot = apex) {
        drawCone(
            path = path,
            apexX = apex.x,
            apexY = apex.y,
            halfWidth = w * 0.46f,
            bottomY = h * 1.05f,
            color = LAMP_CORE,
            alpha = alpha * (0.30f + 0.02f * sin(phase * TAU))
        )
    }

    // 两层雾给卡片与机器分层：中带那条压在卡片后面的水彩上（TTPD 三档底色是米白 →
    // 米白 → 灰米，卡片也是米白，不压它卡片边界在屏幕上找不到）；顶上那条收远山。
    // 机器那一段不铺 —— 前摇里字全打在滚筒上那截纸上，糊一层灰就把字洗没了
    drawFogBand(0.34f, 0.30f, deep, alpha * DISTANT_ALPHA * 1.5f)
    drawFogBand(0.06f, 0.14f, top, alpha * DISTANT_ALPHA)

    drawTypewriter(path, mid, deep, alpha, eraMs, card)
}

/**
 * 打字机本体（正视：滚筒在顶、暗腔居中、四排键在前）。
 *
 * 纵向尺寸**全部**写成「离出纸口 [Rect.bottom] 多远往下」：出纸口坐在卡片下缘上，而卡片
 * 位置随曲目数与屏高变，写成屏高的比例每换台设备就得重算滚筒、四排键、前脸的相对位置。
 *
 * ## 正视的层次
 *
 * 1. **滚筒横在机器顶上**：纸从它后头升上来，压纸架横在纸前。按圆柱上色 —— 暗—亮—暗
 *    三条横带 + 两端轴套与刻纹旋钮（旋钮每打完一行转一格，是「纸在往上走」的证据）。
 * 2. **滑架下是一条暗腔**：全图最暗的一块，字锤从腔里朝滚筒抬。一道真正的暗缝比任何
 *    描边都更像「后面还有东西」。
 * 3. **两肩高出键盘区**：色带盘盖坐在肩顶，轮廓「高—低—高」，机器有了肩宽。
 * 4. **四排键近大远小**（见 [TYPE_KEY_TIERS]）：越靠前的一排键帽更大、整排更宽，每颗键
 *    下面还露出一小截键杆。
 *
 * ## 滑架不横移
 *
 * 真机是纸跟着滑架往左走、字锤原地敲。这里**反过来**：纸不动，打字点沿滚筒左→右走，
 * 每 [TYPE_LINE_MS] 一行、末 12% 是回车横扫飞回行首。原因是升上来的那张纸就是曲目卡片，
 * 卡片在布局里钉死不动 —— 滑架带着纸横移，纸和卡片当场错开一截。
 * 代价是机械原理不对，但屏幕上「打字机正在打字」这件事读得出来，而错位读得出来。
 */
private fun DrawScope.drawTypewriter(
    path: Path,
    mid: Color,
    deep: Color,
    alpha: Float,
    eraMs: Long,
    card: Rect
) {
    val w = size.width
    val h = size.height

    // 出纸口坐在卡片下缘。量不到就用 0.74h 兜底（那是布局层 TTPD_MACHINE_RESERVE
    // 抬高后的典型位置），并钳在合理带内 —— 卡片下缘不该低于 0.84h（机器只剩一条缝）
    val exitY = (if (card.width > 1f) card.bottom else h * 0.74f)
        .coerceIn(h * 0.55f, h * 0.84f)
    val paperL = if (card.width > 1f) card.left else w * 0.055f
    val paperR = if (card.width > 1f) card.right else w * 0.945f
    val paperW = paperR - paperL
    val paperCx = (paperL + paperR) / 2f

    // 机身左右各出画 0.02w
    val mL = -w * 0.02f
    val mW = w * 1.04f
    val mR = mL + mW

    // ── 纵向分层（一律「出口往下多远」，改总高只动这十来个数）──
    val platenCy = exitY + h * 0.030f
    val platenRy = h * 0.017f
    val recessTop = exitY + h * 0.050f
    val recessBottom = exitY + h * 0.080f
    val shoulderTop = exitY + h * 0.082f
    val lipTop = exitY + h * 0.112f
    // 横向：两肩各占 0.185w，中间 0.60w 是键盘区
    val shoulderR = w * 0.185f
    val shoulderSpan = shoulderR - mL
    val midL = shoulderR
    val midR = mR - shoulderSpan
    val bankCx = w * 0.5f
    val bankWidth = w * 0.60f

    // ── 节拍 ──
    // 待机（换张淡变期，本段还没开始）：不敲、不打、滚筒上那截纸是打了三行的一页
    val idle = eraMs < 0L
    val lineT = if (idle) 0.62f else (eraMs % TYPE_LINE_MS).toFloat() / TYPE_LINE_MS
    val sweeping = lineT > TYPE_LINE_SPAN
    // 打字点：行内左→右，末 12% 飞回行首
    val typed = if (sweeping) 0f else lineT / TYPE_LINE_SPAN
    val carriageT = if (sweeping) 1f - (lineT - TYPE_LINE_SPAN) / (1f - TYPE_LINE_SPAN) else typed
    val printX = paperL + paperW * (0.07f + 0.84f * carriageT)
    // 字锤：95ms 一击，前 35% 抬起、后 65% 落回。横扫途中不敲
    val strikeIndex = if (idle || sweeping) -1 else ((eraMs / TYPE_STRIKE_MS) % TYPE_BARS).toInt()
    val lift = if (strikeIndex < 0) {
        0f
    } else {
        val t = (eraMs % TYPE_STRIKE_MS).toFloat() / TYPE_STRIKE_MS
        (1f - abs(t - 0.35f) / 0.65f).coerceIn(0f, 1f)
    }
    // 滚筒上那截露头的纸累积的行数。第 3 行之后不再涨 —— 出纸一启动卡片就从这里
    // 接管，露头那截被卡片盖住
    val stubLines = if (idle) 3 else (1L + eraMs / TYPE_LINE_MS).coerceIn(1L, 3L).toInt()
    // 露头纸的可见度：前摇里机器自己敲给谁看全靠它；卡片开始升上来（前摇结束）后
    // 300ms 淡掉 —— 卡片是不透明的，硬留着只会从纸边上漏出几道旧线
    val stubAlpha = if (eraMs < SwiftieTimeline.TTPD_PREROLL_MS) {
        1f
    } else {
        (1f - (eraMs - SwiftieTimeline.TTPD_PREROLL_MS) / 300f).coerceIn(0f, 1f)
    }

    // ── 滚筒后露头的那截纸（前摇期的「字打在哪」）──
    // 印字行钉死在滚筒上沿之上：真机是印字点不动、纸往上卷，所以越早打的行越靠上，
    // 第 0 行（最新那行）贴着滚筒
    if (stubAlpha > 0.01f) {
        val stubTop = exitY - h * 0.036f
        path.rewind()
        path.moveTo(paperCx - paperW * 0.470f, stubTop)
        path.lineTo(paperCx + paperW * 0.470f, stubTop)
        path.lineTo(paperCx + paperW * 0.492f, exitY - h * 0.002f)
        path.lineTo(paperCx - paperW * 0.492f, exitY - h * 0.002f)
        path.close()
        // 纸色不能用 PAPER_WHITE：米白纸压在米白桌面上分不开。露头这截背着台灯，
        // 压到灰米（deep 那一档）既分得开又是对的；顶边再提两条亮带表现「上亮下暗」
        drawPath(path = path, color = deep, alpha = alpha * 0.92f * stubAlpha)
        val stubH = h * 0.034f
        drawRect(
            color = PAPER_WHITE,
            topLeft = Offset(paperCx - paperW * 0.470f, stubTop),
            size = Size(paperW * 0.940f, stubH * 0.42f),
            alpha = alpha * 0.30f * stubAlpha
        )
        drawRect(
            color = PAPER_WHITE,
            topLeft = Offset(paperCx - paperW * 0.478f, stubTop + stubH * 0.42f),
            size = Size(paperW * 0.956f, stubH * 0.30f),
            alpha = alpha * 0.14f * stubAlpha
        )
        drawPath(
            path = path,
            color = INK,
            alpha = alpha * 0.42f * stubAlpha,
            style = Stroke(width = (w * 0.0018f).coerceAtLeast(1f))
        )
        for (line in 0 until stubLines) {
            val len = if (line == 0) typed else 0.70f + 0.26f * abs(sin(line * 2.7f))
            if (len <= 0.01f) continue
            val lineY = exitY - h * (0.006f + 0.0070f * line)
            drawLine(
                color = INK,
                start = Offset(paperL + paperW * 0.07f, lineY),
                end = Offset(paperL + paperW * (0.07f + 0.84f * len), lineY),
                strokeWidth = (h * 0.0024f).coerceAtLeast(1f),
                alpha = alpha * 0.68f * stubAlpha
            )
        }
    }

    // ── 压纸滚筒 ──
    // 按圆柱上色：整体一档深，靠上一条宽亮带、再往下一条窄亮带、最下压黑 ——
    // 三档一叠就是一根横着的橡胶辊。上一轮是「圆角矩形 + 一道高光」，读作一根扁条。
    // 滚筒只比纸宽 4%：真机就是这样，而且这样两端的旋钮才整只落在屏内
    // （上一轮按机身宽算，机身出画，左右旋钮各被屏幕裁掉一半）
    val platenL = paperL - paperW * 0.02f
    val platenR = paperR + paperW * 0.02f
    val platenW = platenR - platenL
    drawRoundRect(
        color = INK,
        topLeft = Offset(platenL, platenCy - platenRy),
        size = Size(platenW, platenRy * 2f),
        cornerRadius = CornerRadius(platenRy * 0.5f),
        alpha = alpha * 0.94f
    )
    drawRect(
        color = PAPER_WHITE,
        topLeft = Offset(platenL + platenW * 0.010f, platenCy - platenRy * 0.66f),
        size = Size(platenW * 0.980f, platenRy * 0.42f),
        alpha = alpha * 0.21f
    )
    drawRect(
        color = PAPER_WHITE,
        topLeft = Offset(platenL + platenW * 0.010f, platenCy - platenRy * 0.20f),
        size = Size(platenW * 0.980f, platenRy * 0.22f),
        alpha = alpha * 0.09f
    )
    drawRect(
        color = Color.Black,
        topLeft = Offset(platenL, platenCy + platenRy * 0.40f),
        size = Size(platenW, platenRy * 0.60f),
        alpha = alpha * 0.22f
    )
    // 印字点：字锤落下那一瞬间在滚筒前壁上闪一下
    if (lift > 0.55f) {
        drawOval(
            color = PAPER_WHITE,
            topLeft = Offset(printX - w * 0.008f, platenCy + platenRy * 0.22f),
            size = Size(w * 0.016f, platenRy * 0.50f),
            alpha = alpha * 0.44f * lift
        )
    }
    // 压纸架：横在纸前面把纸压向滚筒的那根杆 + 两只小胶轮。
    // 没有它，纸和滚筒是两个不相干的形状
    val bailY = platenCy + platenRy * 0.60f
    drawRect(
        color = Color.Black,
        topLeft = Offset(paperL - paperW * 0.03f, bailY),
        size = Size(paperW * 1.06f, h * 0.0034f),
        alpha = alpha * 0.52f
    )
    for (side in 0..1) {
        val rx = paperCx + paperW * (if (side == 0) -0.27f else 0.27f)
        drawOval(
            color = Color.Black,
            topLeft = Offset(rx - w * 0.013f, bailY - h * 0.0044f),
            size = Size(w * 0.026f, h * 0.0106f),
            alpha = alpha * 0.62f
        )
        drawOval(
            color = PAPER_WHITE,
            topLeft = Offset(rx - w * 0.004f, bailY - h * 0.0026f),
            size = Size(w * 0.006f, h * 0.0038f),
            alpha = alpha * 0.30f
        )
    }
    // 两端：先一小段轴套（端面与滚筒之间那截），再是刻纹旋钮。
    // 少了轴套，旋钮像是直接贴在滚筒端头上的一枚圆片
    val knobAngle = (stubLines + typed) * 14f
    for (side in 0..1) {
        val kx = if (side == 0) platenL else platenR
        val inward = if (side == 0) 1f else -1f
        drawRect(
            color = INK,
            topLeft = Offset(if (inward > 0f) kx else kx - w * 0.020f, platenCy - platenRy * 0.70f),
            size = Size(w * 0.020f, platenRy * 1.40f),
            alpha = alpha * 0.99f
        )
        knurledKnob(kx, platenCy, w * 0.038f, platenRy * 1.52f, knobAngle * inward, mid, alpha)
    }
    // 回车杆：左端那根往左上翘出去的杆 + 末端握把。少了它一眼就知道是台假打字机。
    // **在旋钮之后画**：真机上这根杆就是横在左旋钮前面的，压过去才对
    val leverX = platenL - mW * 0.022f
    val leverY = platenCy - h * 0.042f
    drawLine(
        color = INK,
        start = Offset(platenL + mW * 0.050f, platenCy - platenRy * 0.30f),
        end = Offset(leverX, leverY),
        strokeWidth = (mW * 0.010f).coerceAtLeast(1f),
        alpha = alpha * 0.92f,
        cap = StrokeCap.Round
    )
    drawCircle(color = mid, radius = mW * 0.016f, center = Offset(leverX, leverY), alpha = alpha * 0.45f)
    drawCircle(
        color = INK,
        radius = mW * 0.016f,
        center = Offset(leverX, leverY),
        alpha = alpha * 0.84f,
        style = Stroke(width = (mW * 0.0035f).coerceAtLeast(1f))
    )

    // ── 滑架下面那条暗腔 ──
    // 全图最暗的一块，也是这一版唯一新增的结构。纵向只有 0.150h，四条同亮度的横带叠
    // 起来读不出前后；而一条比任何部件都暗的腔，一眼就是「这里是空的、后面还有东西」。
    // 真机上这里确实是空的：纸从这条缝绕下去，字锤从这条缝抬起来。
    //
    // 0.93 而不是 0.66：**背景层的 alpha 是 1.0**（它是两张背景交叉渐变的权重，
    // 不是 `PROP_ALPHA` 那类道具系数）。上一版按 0.34 估的，实测这条腔的合成值是 82、
    // 而机身 `INK × 0.88` 是 72 —— 该最暗的地方比它前面的机身还亮，整台机器的前后关系是反的。
    // 0.93 让底色只剩七个百分点（≈ 17），比机身深一大截
    drawRect(
        color = Color.Black,
        topLeft = Offset(mL, recessTop),
        size = Size(mW, recessBottom - recessTop),
        alpha = alpha * 0.93f
    )
    // 字锤：11 根从腔底立起来，正在打的那一根抬到印字点。
    // 用 mid（TTPD 的 mid 近白）而不是 INK —— 暗腔里的金属靠反光被看见，
    // 深色的杆画在黑腔里等于没画
    val hitY = platenCy + platenRy * 0.58f
    for (i in 0 until TYPE_BARS) {
        val f = i / (TYPE_BARS - 1f)
        val fromX = w * (0.25f + 0.50f * f)
        val fromY = recessBottom - h * 0.001f
        // 扇形排布：两侧的杆更斜，中间的近乎直立
        val restX = fromX + (bankCx - fromX) * 0.34f
        val restY = recessTop + h * 0.004f
        val p = if (i == strikeIndex) lift else 0f
        drawLine(
            color = mid,
            start = Offset(fromX, fromY),
            end = Offset(restX + (printX - restX) * p, restY + (hitY - restY) * p),
            strokeWidth = (w * 0.0034f).coerceAtLeast(1f),
            alpha = alpha * (0.26f + 0.50f * p),
            cap = StrokeCap.Round
        )
    }
    // 色带导子：立在印字点前的那只小叉子，随字锤一起抬。少了它印字点上什么都没有
    drawRect(
        color = mid,
        topLeft = Offset(printX - w * 0.006f, recessTop + h * 0.004f - h * 0.008f * lift),
        size = Size(w * 0.012f, h * 0.011f),
        alpha = alpha * (0.28f + 0.34f * lift)
    )

    // ── 两肩 ──
    // 肩比中段高 0.010h，色带盘盖坐在肩顶。轮廓因此是「高—低—高」，机器有了肩宽；
    // 上一轮整台机器一条等高的直边，屏幕上就是一块板
    for (side in 0..1) {
        val sL = if (side == 0) mL else midR
        val sR = sL + shoulderSpan
        val inset = shoulderSpan * 0.05f
        val faceTop = shoulderTop + h * 0.008f
        // 肩顶面：后缘略窄 = 俯视的透视
        path.rewind()
        path.moveTo(sL + inset, shoulderTop)
        path.lineTo(sR - inset, shoulderTop)
        path.lineTo(sR, faceTop)
        path.lineTo(sL, faceTop)
        path.close()
        drawPath(path = path, color = INK, alpha = alpha * 0.80f)
        drawLine(
            color = PAPER_WHITE,
            start = Offset(sL + inset, shoulderTop),
            end = Offset(sR - inset, shoulderTop),
            strokeWidth = (h * 0.0024f).coerceAtLeast(1f),
            alpha = alpha * 0.20f
        )
        // 肩的前脸：一路落到前沿，比顶面暗一档 —— 朝上的面受光、朝前的面背光
        drawRect(
            color = INK,
            topLeft = Offset(sL, faceTop),
            size = Size(shoulderSpan, lipTop - faceTop),
            alpha = alpha * 0.93f
        )
        // 台灯在左上：左肩外棱受光、右肩外棱压暗
        drawRect(
            color = if (side == 0) PAPER_WHITE else Color.Black,
            topLeft = Offset(if (side == 0) sL else sR - mW * 0.030f, faceTop),
            size = Size(mW * 0.030f, lipTop - faceTop),
            alpha = alpha * (if (side == 0) 0.10f else 0.17f)
        )
        // 折角：顶面到前脸那条亮线
        drawLine(
            color = PAPER_WHITE,
            start = Offset(sL, faceTop),
            end = Offset(sR, faceTop),
            strokeWidth = (h * 0.0018f).coerceAtLeast(1f),
            alpha = alpha * 0.15f
        )
        // 色带盘盖：沉进肩顶的一只扁椭圆。盘里绕的色带是黑的，盘心比机身还深，
        // 亮的只有金属外圈那一道与中央的轴
        val cx = (sL + sR) * 0.5f
        val cy = shoulderTop + h * 0.0044f
        val rx = shoulderSpan * 0.32f
        val ry = h * 0.0068f
        drawOval(
            color = Color.Black,
            topLeft = Offset(cx - rx * 1.16f, cy - ry * 1.22f),
            size = Size(rx * 2.32f, ry * 2.44f),
            alpha = alpha * 0.55f
        )
        drawOval(color = INK, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2f, ry * 2f), alpha = alpha)
        drawOval(
            color = mid,
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f),
            alpha = alpha * 0.46f,
            style = Stroke(width = (w * 0.0022f).coerceAtLeast(1f))
        )
        drawOval(
            color = mid,
            topLeft = Offset(cx - rx * 0.20f, cy - ry * 0.22f),
            size = Size(rx * 0.40f, ry * 0.44f),
            alpha = alpha * 0.58f
        )
        // 色带：从盘的内侧拉进腔里的导子。一左一右两条，色带走向能看见
        drawLine(
            color = mid,
            start = Offset(cx + (if (side == 0) rx else -rx), cy + ry * 0.5f),
            end = Offset(printX + (if (side == 0) -w * 0.018f else w * 0.018f), recessTop + h * 0.007f),
            strokeWidth = (h * 0.0020f).coerceAtLeast(1f),
            alpha = alpha * 0.30f
        )
    }

    // ── 中段机身：键盘坐的那块斜坡 ──
    // 三个层次：腔（最暗）< 中段 < 肩。中段更靠里、受光比肩少，但比腔亮得多
    drawRect(
        color = INK,
        topLeft = Offset(midL, recessBottom),
        size = Size(midR - midL, lipTop - recessBottom),
        alpha = alpha * 0.88f
    )
    drawRect(
        color = PAPER_WHITE,
        topLeft = Offset(midL, recessBottom),
        size = Size(midR - midL, h * 0.0034f),
        alpha = alpha * 0.14f
    )

    // ── 四排键 ──
    // 键帽是**横扁的椭圆**，不是圆：键面朝上，投影在竖直方向被压掉六成。
    //
    // 键帽**必须比机身暗**。上一轮用 `mid`（TTPD 的 mid 是 #F5F1EA，近白）画帽面，
    // 实测键帽 (206,203,198) 压在 (99,95,89) 的机身上，亮度差一倍 —— 屏幕上就是
    // 「一盘珍珠」，正是被点名过的那种廉价感。真机是深色胶木键帽 + 一圈镀铬边 + 白字母：
    // 亮的只有那圈边和那个字母，两者都只有一两个像素宽。
    //
    // 四排**近大远小**（见 [TYPE_KEY_TIERS]）：越靠前的一排键帽更大、整排更宽，
    // 每颗键下面再露一小截键杆。上一轮四排等大等宽，读作一块机械键盘；
    // 逐排放大 5% 之后同一块斜坡上就有了纵深，键也成了「架在杆上」而不是「印在板上」
    val pressedRow = if (strikeIndex < 0) -1 else strikeIndex % TYPE_KEY_ROWS.size
    for (row in TYPE_KEY_ROWS.indices.reversed()) {
        val keys = TYPE_KEY_ROWS[row]
        val scale = TYPE_KEY_TIERS[row]
        // 第 0 排最靠里（贴着两肩下沿）、最小；越靠前越大越低。低到出屏的那几排
        // 被时间轴挡住，属预期 —— 机器本来就坐在屏幕底
        val rowY = exitY + h * (0.120f + 0.0112f * row)
        val keyRx = mW * 0.0250f * scale
        val keyRy = h * 0.0047f * scale
        // 每排往右错三分之一个键，和真机一样
        val rowCx = bankCx + keyRx * 0.33f * (row - 1.5f)
        val pitch = bankWidth * scale / keys
        val pressedCol = if (row == pressedRow) (strikeIndex * 7) % keys else -1
        for (k in 0 until keys) {
            val down = if (k == pressedCol) lift else 0f
            val cx = rowCx + (k - (keys - 1) / 2f) * pitch
            val cy = rowY + keyRy * 0.34f * down
            val rx = keyRx * (1f - 0.05f * down)
            val ry = keyRy * (1f - 0.05f * down)
            // 键杆：帽下面露出的一小截。按下时缩短，那就是「被按下去了」
            drawRect(
                color = Color.Black,
                topLeft = Offset(cx - rx * 0.30f, cy),
                size = Size(rx * 0.60f, ry * (2.2f - 1.2f * down)),
                alpha = alpha * 0.44f
            )
            // 键座：比键帽大一圈的暗环，同时是键杆投在斜坡上的影
            drawOval(
                color = Color.Black,
                topLeft = Offset(cx - rx * 1.16f, cy - ry * 1.20f),
                size = Size(rx * 2.32f, ry * 2.56f),
                alpha = alpha * 0.34f
            )
            drawOval(
                color = INK,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2f, ry * 2f),
                alpha = alpha * (0.90f + 0.10f * down)
            )
            // 帽面上半提亮：键面是凹的，靠上那一半朝着台灯
            drawOval(
                color = PAPER_WHITE,
                topLeft = Offset(cx - rx * 0.84f, cy - ry * 0.88f),
                size = Size(rx * 1.68f, ry * 0.84f),
                alpha = alpha * (0.14f - 0.07f * down)
            )
            // 镀铬边：一圈细白线。全机最亮的东西就该是这种一像素宽的高光
            drawOval(
                color = PAPER_WHITE,
                topLeft = Offset(cx - rx, cy - ry),
                size = Size(rx * 2f, ry * 2f),
                alpha = alpha * (0.42f - 0.20f * down),
                style = Stroke(width = (h * 0.0010f).coerceAtLeast(1f))
            )
            // 字母：一小道白。画不出字形就不要假装 —— 一道横杠在这个尺寸下正是字的样子
            drawRect(
                color = PAPER_WHITE,
                topLeft = Offset(cx - rx * 0.28f, cy - ry * 0.08f),
                size = Size(rx * 0.56f, (h * 0.0012f).coerceAtLeast(1f)),
                alpha = alpha * (0.60f - 0.26f * down)
            )
        }
    }

    // 空格键：最前那一条，也是唯一一根横杆。先垫一层影，它才是「架起来」的
    val spaceTop = exitY + h * 0.1720f
    val spaceW = w * 0.360f
    drawRoundRect(
        color = Color.Black,
        topLeft = Offset(paperCx - spaceW * 0.5f, spaceTop + h * 0.0022f),
        size = Size(spaceW, h * 0.0105f),
        cornerRadius = CornerRadius(h * 0.0048f),
        alpha = alpha * 0.38f
    )
    // 同样不能用 mid 铺面：实测那一条是 (235,233,227)，比键帽还白，像贴了张纸条
    drawRoundRect(
        color = INK,
        topLeft = Offset(paperCx - spaceW * 0.5f, spaceTop),
        size = Size(spaceW, h * 0.0105f),
        cornerRadius = CornerRadius(h * 0.0048f),
        alpha = alpha * 0.95f
    )
    drawRoundRect(
        color = PAPER_WHITE,
        topLeft = Offset(paperCx - spaceW * 0.5f, spaceTop),
        size = Size(spaceW, h * 0.0105f),
        cornerRadius = CornerRadius(h * 0.0048f),
        alpha = alpha * 0.30f,
        style = Stroke(width = (w * 0.0020f).coerceAtLeast(1f))
    )

    // ── 前脸 ──
    // 三个调子里最暗的一块（背光）。从键区下沿一路落到屏幕底：机器「坐在屏幕底下」，
    // 底边出画才对；落进时间轴后面的那截被轴盖住，属预期
    drawRect(
        color = INK,
        topLeft = Offset(mL, lipTop),
        size = Size(mW, h - lipTop),
        alpha = alpha * 0.97f
    )
    drawLine(
        color = PAPER_WHITE,
        start = Offset(mL, lipTop),
        end = Offset(mR, lipTop),
        strokeWidth = (h * 0.0018f).coerceAtLeast(1f),
        alpha = alpha * 0.15f
    )
    // 铭牌：前脸正中一道镀铬窄条。老机器这个位置就是厂牌，一道亮线就够
    drawRect(
        color = PAPER_WHITE,
        topLeft = Offset(paperCx - w * 0.062f, lipTop + h * 0.0022f),
        size = Size(w * 0.124f, (h * 0.0016f).coerceAtLeast(1f)),
        alpha = alpha * 0.26f
    )
    // 出纸缝 + 两只送纸胶辊。机器投在纸上的那道影子由卡片自己画（见 SwiftieEraCard
    // 的 feedProgress）—— 背景层在卡片**下面**，画在这里会被纸整块盖掉
    val seamY = exitY - h * 0.005f
    drawRect(
        color = Color.Black,
        topLeft = Offset(paperL - paperW * 0.012f, seamY - h * 0.002f),
        size = Size(paperW * 1.024f, h * 0.004f),
        alpha = alpha * 0.70f
    )
    for (side in 0..1) {
        val rx = paperCx + paperW * (if (side == 0) -0.34f else 0.34f)
        drawOval(
            color = INK,
            topLeft = Offset(rx - w * 0.018f, seamY - h * 0.007f),
            size = Size(w * 0.036f, h * 0.014f),
            alpha = alpha
        )
        drawOval(
            color = PAPER_WHITE,
            topLeft = Offset(rx - w * 0.006f, seamY - h * 0.0046f),
            size = Size(w * 0.008f, h * 0.005f),
            alpha = alpha * 0.26f
        )
    }
}

/**
 * 滚筒两端的旋钮：椭圆盘 + 一圈刻纹 + 中央轴。
 *
 * 刻纹按 [degrees] 转 —— 每打完一行转一格，这是「纸在往上走」的唯一证据。
 * 少了刻纹它就是一个深色椭圆，和滚筒本体分不开。
 */
private fun DrawScope.knurledKnob(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    degrees: Float,
    mid: Color,
    alpha: Float
) {
    drawOval(
        color = INK,
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2f, ry * 2f),
        alpha = alpha * 0.94f
    )
    val step = TAU / KNOB_KNURLS
    val base = degrees / 360f * TAU
    for (k in 0 until KNOB_KNURLS) {
        val a = base + step * k
        val ca = cos(a)
        val sa = sin(a)
        drawLine(
            color = PAPER_WHITE,
            start = Offset(cx + ca * rx * 0.66f, cy + sa * ry * 0.66f),
            end = Offset(cx + ca * rx * 0.94f, cy + sa * ry * 0.94f),
            strokeWidth = (rx * 0.055f).coerceAtLeast(1f),
            alpha = alpha * 0.20f
        )
    }
    drawOval(
        color = mid,
        topLeft = Offset(cx - rx * 0.30f, cy - ry * 0.30f),
        size = Size(rx * 0.60f, ry * 0.60f),
        alpha = alpha * 0.60f
    )
}

/** 字锤根数。 */
private const val TYPE_BARS = 11

/**
 * 四排键各自的键数。10/10/10/9 —— 真机上排最长、下排最短。
 *
 * 从 11/11/10/10 收到这里：键要够大才看得出「一圈镀铬边 + 一个字母」，
 * 而键盘区的宽度（0.60w）是定的，减一颗键每颗就宽 10%。
 */
private val TYPE_KEY_ROWS = intArrayOf(10, 10, 10, 9)

/**
 * 四排键的**近大远小**系数（后 → 前）。
 *
 * 键盘是一道朝着看的人倾下来的斜坡，所以越靠前的一排离眼睛越近：键帽更大、整排更宽。
 * 上一轮四排等大等宽地码在同一个 y 间距上，屏幕上读作一块机械键盘 —— 那是「被压缩了」
 * 这条反馈里最主要的一处。每排放大 5%、整排也跟着宽 5%，同一块斜坡上就有了纵深。
 *
 * 幅度不能再大：0.93→1.09 已经是 17% 的差，再拉开前排键就宽到与后排对不上列，
 * 读成两台不同的机器。
 */
private val TYPE_KEY_TIERS = floatArrayOf(0.93f, 0.98f, 1.03f, 1.09f)

/** 旋钮上的刻纹数。 */
private const val KNOB_KNURLS = 11

/** 一击的时长。95ms ≈ 每秒 10 击，是「打得很顺」的手速。 */
private const val TYPE_STRIKE_MS = 95L

/**
 * 一行的时长（含回车横扫）。
 *
 * 刻意等于 `TTPD_PREROLL_MS - 150`：前摇那 1400ms 正好是**第一行打完 + 一次回车**，
 * 纸紧接着出来。之后这个周期照常走下去 —— 机器在整段 11.3s 里一直在打，
 * 打的就是屏幕上正在逐行点亮的那 31 首。
 */
private const val TYPE_LINE_MS = 1_250L

/** 一行里用于打字的比例，余下的 12%（150ms）是回车横扫。 */
private const val TYPE_LINE_SPAN = 0.88f

// ─────────────────────── 12 · The Life of a Showgirl ───────────────────────

/**
 * 剧场舞台：大幕 + 一排化妆镜边灯 + 追光锥 + 空浴缸剪影 + marquee 灯牌边框。
 *
 * 原先这一张画的是羽毛扇，被否掉了 —— 九条粗线扫过右下角读作一把扫帚。改成剧场本身：
 * 「Showgirl 的一生」讲的是幕后而不是道具，浴缸（官方主视觉里那只）比扇子更是它。
 * **不画人形** —— 一旦有人，这就从「舞台」变成「某个人的画像」。
 *
 * 边灯按 [phase] 依次点亮（跑马灯），跑一圈正好是一个相位周期，所以绕回时接得上。
 */
private fun DrawScope.drawTheatreStage(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float
) {
    val w = size.width
    val h = size.height

    // 追光锥：自上缘正中打到舞台地板，落在浴缸上
    drawCone(
        path = path,
        apexX = w * 0.52f,
        apexY = -h * 0.02f,
        halfWidth = w * 0.30f,
        bottomY = h * 0.44f,
        color = LAMP_CORE,
        alpha = alpha * 0.42f
    )

    // 大幕：两侧各收拢成一束，中间留出开口。每侧 7 道褶皱，宽窄不等
    val curtainW = w * 0.24f
    for (side in 0..1) {
        for (i in 0 until 7) {
            val f = i / 6f
            // 褶皱宽度按正弦变化：等宽的竖条读作百叶窗
            val fold = curtainW * (0.10f + 0.06f * abs(sin(f * 4.1f)))
            val x = if (side == 0) f * curtainW else w - f * curtainW
            drawRect(
                color = deep,
                topLeft = Offset(x - fold / 2f, 0f),
                size = Size(fold, h * (0.92f - 0.10f * f)),
                alpha = alpha * SILHOUETTE_ALPHA * (0.9f - 0.42f * f)
            )
        }
        // 幕布下缘收成弧形：布是软的，落地不会是一条直线
        path.rewind()
        val edgeX = if (side == 0) curtainW else w - curtainW
        path.moveTo(if (side == 0) 0f else w, h * 0.92f)
        path.quadraticTo(
            (if (side == 0) 0f else w) * 0.5f + edgeX * 0.5f,
            h * 0.99f,
            edgeX,
            h * 0.82f
        )
        path.lineTo(if (side == 0) 0f else w, h * 0.82f)
        path.close()
        drawPath(path = path, color = deep, alpha = alpha * SILHOUETTE_ALPHA * 0.7f)
    }
    // 幕顶横楣
    drawRect(color = deep, size = Size(w, h * 0.075f), alpha = alpha * SILHOUETTE_ALPHA)

    // 灯架：一道横贯全屏的桁架 + 五盏吊在下面的舞台灯，每盏各投一束光锥。
    //
    // 这里原先是**一整块灯泡围边的 marquee 灯牌**。第五轮截图暴露了一个硬伤：
    // 卡片右侧的道具正是「一面镶灯泡的化妆镜」（`SwiftieEraMotifs` 的 `VANITY_MIRROR`），
    // 背景那块灯牌与它是同一件东西 —— 一圈灯泡围一块板 —— 一屏之内画了两遍，
    // 而且两者体量相近，互相抢主体。这跟 Red 那张「背景一条围巾、卡片又一条围巾」
    // 是同一个毛病。
    //
    // 换成桁架灯：同样是「剧场」的信息，但形不撞（横杆 + 吊灯 vs 镶灯边框），
    // 而且它给了浴缸一个**光源的来处** —— 原来那束追光是从屏幕上缘凭空打下来的。
    val trussY = h * 0.082f
    val trussH = h * 0.026f
    for (chord in 0..1) {
        drawRect(
            color = deep,
            topLeft = Offset(0f, trussY + chord * trussH),
            size = Size(w, h * 0.0055f),
            alpha = alpha * 0.62f
        )
    }
    // 斜撑：上下弦之间来回的锯齿。少了它两条横杆只是两条线，不是桁架
    var braceX = 0f
    var braceUp = true
    while (braceX < w) {
        val nextX = braceX + w * 0.055f
        drawLine(
            color = deep,
            start = Offset(braceX, if (braceUp) trussY else trussY + trussH),
            end = Offset(nextX.coerceAtMost(w), if (braceUp) trussY + trussH else trussY),
            strokeWidth = (h * 0.0035f).coerceAtLeast(1f),
            alpha = alpha * 0.46f
        )
        braceX = nextX
        braceUp = !braceUp
    }
    // 五盏灯。位置不等距（0.18 / 0.34 / 0.52 / 0.68 / 0.84）：等距一排读作路灯
    val lamps = floatArrayOf(0.18f, 0.34f, 0.52f, 0.68f, 0.84f)
    for (i in lamps.indices) {
        val lx = w * lamps[i]
        val yokeTop = trussY + trussH + h * 0.0055f
        val bodyTop = yokeTop + h * 0.012f
        val bodyH = h * 0.030f
        // 两侧的灯朝中间偏，中间那盏正打 —— 真的舞台灯是聚向表演区的
        val tilt = (lamps[i] - 0.52f) * -34f
        rotate(degrees = tilt, pivot = Offset(lx, yokeTop)) {
            drawLine(
                color = deep,
                start = Offset(lx, yokeTop),
                end = Offset(lx, bodyTop),
                strokeWidth = (w * 0.006f).coerceAtLeast(1f),
                alpha = alpha * 0.6f
            )
            // 灯体：上窄下宽的桶。par 灯就是这个形
            path.rewind()
            path.moveTo(lx - w * 0.015f, bodyTop)
            path.lineTo(lx + w * 0.015f, bodyTop)
            path.lineTo(lx + w * 0.023f, bodyTop + bodyH)
            path.lineTo(lx - w * 0.023f, bodyTop + bodyH)
            path.close()
            drawPath(path = path, color = deep, alpha = alpha * 0.66f)
            // 镜面：闪一下。五盏各自错相位，整排才不是同步的一串
            val lit = 0.62f + 0.38f * sin((phase * 2f + i * 0.21f) * TAU)
            drawBulb(Offset(lx, bodyTop + bodyH), w * 0.016f, lit, alpha * 0.9f)
            // 光锥：从镜面往下，落到浴缸那一带
            drawCone(
                path = path,
                apexX = lx,
                apexY = bodyTop + bodyH,
                halfWidth = w * 0.085f,
                bottomY = h * 0.42f,
                color = LAMP_CORE,
                alpha = alpha * 0.13f * lit
            )
        }
    }

    // 空浴缸：外沿一圈厚唇 + 缸体 + 四只爪脚 + 水面与泡沫。**缸里没有人**。
    // 抬进 HERO_BOTTOM：原来在 0.62–0.84h，整只缸压在卡片背后，
    // 只剩一团发白的圆角矩形透上来，把卡片左三分之二洗成一片脏白。
    //
    // 第五轮把缸放大了（0.10h 高 → 0.15h）：灯牌撤掉之后 0.14–0.38h 全空了出来，
    // 而这只缸是官方主视觉里的那件东西，本来就该是这一张的主体。
    // 缸体也从 0.42 提到 0.86 —— 0.42 的搪瓷缸背后透着幕布的竖褶，读作一只塑料桶。
    val tubLeft = w * 0.275f
    val tubRight = w * 0.765f
    // 0.250h 而不是 0.225h：上一版 0.15h 高、下缘又收掉 7%，比例是**桶**不是缸。
    // 独脚浴缸横向比竖向长得多，缸壁近乎直，只在贴近底部才收 —— 收窄量也从 7% 降到 3.5%
    val tubTop = h * 0.250f
    val tubBottom = h * 0.378f
    val tubW = tubRight - tubLeft
    // 缸体：上宽下略窄的圆角形
    path.rewind()
    path.moveTo(tubLeft, tubTop)
    path.lineTo(tubRight, tubTop)
    path.lineTo(tubRight - tubW * 0.035f, tubBottom - tubW * 0.04f)
    path.quadraticTo(
        (tubLeft + tubRight) / 2f, tubBottom + tubW * 0.035f,
        tubLeft + tubW * 0.035f, tubBottom - tubW * 0.04f
    )
    path.close()
    // 0.96 而不是 0.86：第七轮截图里幕布的竖褶从缸壁里透上来，横过缸体读作一道道水平
    // 条纹（缸体自己是纯白，透上来的褶被缸口的椭圆切成了横的），一只搪瓷缸读作条纹布桶。
    // 搪瓷是不透光的
    drawPath(path = path, color = PAPER_WHITE, alpha = alpha * 0.96f)
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * 0.70f,
        style = Stroke(width = (w * 0.004f).coerceAtLeast(1f))
    )
    // 缸体左侧一道竖高光 + 右侧一道暗部：搪瓷是弧面，平填一块白是一张纸板
    drawLine(
        color = Color.White,
        start = Offset(tubLeft + tubW * 0.10f, tubTop + tubW * 0.06f),
        end = Offset(tubLeft + tubW * 0.13f, tubBottom - tubW * 0.10f),
        strokeWidth = (tubW * 0.030f).coerceAtLeast(2f),
        alpha = alpha * 0.34f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = deep,
        start = Offset(tubRight - tubW * 0.09f, tubTop + tubW * 0.08f),
        end = Offset(tubRight - tubW * 0.13f, tubBottom - tubW * 0.10f),
        strokeWidth = (tubW * 0.040f).coerceAtLeast(2f),
        alpha = alpha * 0.16f,
        cap = StrokeCap.Round
    )
    // 厚唇：一条压在缸口的椭圆环，浴缸的边是有厚度的
    drawOval(
        color = PAPER_WHITE,
        topLeft = Offset(tubLeft - tubW * 0.02f, tubTop - tubW * 0.055f),
        size = Size(tubW * 1.04f, tubW * 0.115f),
        alpha = alpha * 0.55f
    )
    // 缸口的内影：唇的里侧，缸因此是「有开口的」而不是一块实心白
    drawOval(
        color = deep,
        topLeft = Offset(tubLeft + tubW * 0.03f, tubTop - tubW * 0.030f),
        size = Size(tubW * 0.94f, tubW * 0.075f),
        alpha = alpha * 0.45f
    )
    // 水面：缸口内的一片浅椭圆 + 一排泡沫。
    // 一只**空**缸只是一件家具；有水才是官方主视觉里那一幕。
    // 泡沫用一条路径里并起来的圆团一次填完 —— 逐个 drawCircle 的话交叠处会叠深一档，
    // 一排大小相近的圆珠子（Red 与 folklore 两张都在这上面翻过车）
    drawOval(
        color = top,
        topLeft = Offset(tubLeft + tubW * 0.055f, tubTop - tubW * 0.020f),
        size = Size(tubW * 0.89f, tubW * 0.062f),
        alpha = alpha * 0.62f
    )
    // 泡沫排两层、半径差到 2.6 倍、横向间距也按频率抖开。
    // 上一版 13 团半径在 0.018–0.032 之间、y 只抖 ±0.012，等间距摆一排 ——
    // 第六轮截图里那是**一串珍珠项链**贴在缸口上。真的泡沫是大小相差极大的团挤在一起，
    // 所以半径 0.012–0.044（3.6 倍差），横向位置再叠一个频率错开
    path.rewind()
    for (layer in 0..1) {
        val count = if (layer == 0) 11 else 9
        for (b in 0 until count) {
            val f = (b + 0.5f) / count
            val jitterX = sin(f * (if (layer == 0) 9.1f else 6.7f) + layer * 2.3f) * 0.030f
            val bx = tubLeft + tubW * (0.06f + f * 0.88f + jitterX)
            val br = tubW * (0.012f + 0.032f * abs(sin(f * (if (layer == 0) 7.3f else 11.9f) + 1.1f)))
            val by = tubTop - tubW * (if (layer == 0) 0.002f else 0.016f) +
                sin(f * 4.7f + layer * 1.7f) * tubW * 0.020f
            path.addOval(Rect(bx - br, by - br, bx + br, by + br))
        }
    }
    drawPath(path = path, color = Color.White, alpha = alpha * 0.58f)
    // 爪脚：踝 + 外撇的爪垫两段。前两只整只看得见，后两只**按比例缩小**（不是变淡）。
    //
    // 上一版是一个圆角矩形，圆角给到 0.030 而框只有 0.068×0.063，等于画了个椭圆；
    // 后两只又叠了 vis 的半透明，第七轮截图里那两只读作缸底下方两颗**脱开的灰珠子**。
    // 远近该用**大小**表示，不该用透明度：半透明的实心木件只会读作幽灵。
    // 爪脚缸的辨识特征就是踝细、脚爪往外撇的那一块，所以这里必须是两段
    for (i in 0 until TUB_FEET.size / 2) {
        val f = TUB_FEET[i * 2]
        val vis = TUB_FEET[i * 2 + 1]
        val fx = tubLeft + tubW * f
        val ankleH = tubW * 0.072f * vis
        val padH = tubW * 0.044f * vis
        val topY = tubBottom - tubW * 0.028f * vis
        // 踝：上宽下窄的一小段梯形，接在缸底
        path.rewind()
        path.moveTo(fx - tubW * 0.030f * vis, topY)
        path.lineTo(fx + tubW * 0.030f * vis, topY)
        path.lineTo(fx + tubW * 0.019f * vis, topY + ankleH)
        path.lineTo(fx - tubW * 0.019f * vis, topY + ankleH)
        path.close()
        drawPath(path = path, color = deep, alpha = alpha * 0.84f)
        drawRoundRect(
            color = deep,
            topLeft = Offset(fx - tubW * 0.042f * vis, topY + ankleH - padH * 0.30f),
            size = Size(tubW * 0.084f * vis, padH),
            cornerRadius = CornerRadius(tubW * 0.012f * vis),
            alpha = alpha * 0.90f
        )
    }
    // 龙头：右端一根立柱 + 一段弯管，管口朝缸内。
    // 第四轮只有那一段 150° 的弧、又细又小，压在橙底上读作一枚墨绿色的挂钩。
    //
    // 第五轮换掉了颜色。原来整支龙头用 [GOLD]（#D4AF37）—— 而这一张的中档底色是
    // #E8620F 的橙：金画在橙上混出来是**黄绿**，第五轮截图里那支龙头读作一株
    // 从缸沿长出来的嫩芽（当场没认出是龙头）。镀铬件本来就该是近白加深色描边，
    // 白在橙底上永远不会串色
    val tapX = tubRight - tubW * 0.13f
    // 底座压在缸沿**之内**（+0.03 而不是 -0.02）。上一版整支龙头连底座都在缸口以上，
    // 与缸沿之间还留着一道缝，第六轮截图里读作「浮在缸上方的一副自行车车把」。
    // 龙头是从缸沿上装出来的，底座必须踩在沿上
    val tapBase = tubTop + tubW * 0.030f
    // 0.13 而不是 0.17：出水口只需要探进缸口一点，高过缸沿半个缸宽的龙头是消防栓
    val tapH = tubW * 0.130f
    // 管身：先描一道深色当轮廓，再压一道更细的近白当镀铬面。
    // 两道叠出来才是「管」；单描一道白线在浅橙上几乎看不见
    val pipeW = (tubW * 0.036f).coerceAtLeast(3f)
    drawLine(
        color = deep,
        start = Offset(tapX, tapBase),
        end = Offset(tapX, tapBase - tapH),
        strokeWidth = pipeW,
        alpha = alpha * 0.72f,
        cap = StrokeCap.Round
    )
    drawLine(
        color = PAPER_WHITE,
        start = Offset(tapX, tapBase),
        end = Offset(tapX, tapBase - tapH),
        strokeWidth = pipeW * 0.58f,
        alpha = alpha * 0.92f,
        cap = StrokeCap.Round
    )
    // 鹅颈弯管：立柱顶端往**左**（缸内一侧）绕过去，末端挂一段朝下的出水口。
    //
    // 上一版这段弧的圆心就落在立柱顶端，于是立柱插在弧的**正中间**，两端等长地朝下 ——
    // 第七轮截图里那是一副对称的 ∩ 形车把，不是龙头。真龙头是不对称的：一头是立柱，
    // 另一头是朝下的水口。所以弧的**右端**接立柱，左端接出水口
    val neckRx = tubW * 0.088f
    val neckRy = tubW * 0.058f
    val neckCx = tapX - neckRx
    val neckCy = tapBase - tapH
    val nozzleX = neckCx - neckRx
    val nozzleBottom = neckCy + tubW * 0.048f
    for (pass in 0..1) {
        val col = if (pass == 0) deep else PAPER_WHITE
        val wid = if (pass == 0) pipeW else pipeW * 0.58f
        val a = alpha * (if (pass == 0) 0.72f else 0.92f)
        drawArc(
            color = col,
            startAngle = 0f,
            sweepAngle = -180f,
            useCenter = false,
            topLeft = Offset(neckCx - neckRx, neckCy - neckRy),
            size = Size(neckRx * 2f, neckRy * 2f),
            alpha = a,
            style = Stroke(width = wid, cap = StrokeCap.Round)
        )
        // 出水口：弯管左端往下一小段
        drawLine(
            color = col,
            start = Offset(nozzleX, neckCy),
            end = Offset(nozzleX, nozzleBottom),
            strokeWidth = wid,
            alpha = a,
            cap = StrokeCap.Round
        )
    }
    // 水柱：出水口到水面那一小段。有它这支才是**开着的**龙头，
    // 而不是缸沿上装着的一件金属摆件
    drawLine(
        color = Color.White,
        start = Offset(nozzleX, nozzleBottom),
        end = Offset(nozzleX, tubTop - tubW * 0.020f),
        strokeWidth = pipeW * 0.50f,
        alpha = alpha * 0.42f,
        cap = StrokeCap.Round
    )
    // 阀门：两枚十字把手（冷热各一），龙头才不是一根光管子
    for (v in 0..1) {
        val vx = tapX + (if (v == 0) -1f else 1f) * tubW * 0.058f
        // 把手落在底座那一档高度上，跟着底座一起踩在缸沿上
        val vy = tapBase - tapH * 0.12f
        drawCircle(color = deep, radius = tubW * 0.024f, center = Offset(vx, vy), alpha = alpha * 0.72f)
        drawCircle(color = PAPER_WHITE, radius = tubW * 0.017f, center = Offset(vx, vy), alpha = alpha * 0.90f)
        for (arm in 0..1) {
            val ax = if (arm == 0) tubW * 0.024f else 0f
            val ay = if (arm == 0) 0f else tubW * 0.024f
            drawLine(
                color = deep,
                start = Offset(vx - ax, vy - ay),
                end = Offset(vx + ax, vy + ay),
                strokeWidth = (tubW * 0.008f).coerceAtLeast(1f),
                alpha = alpha * 0.55f
            )
        }
        // 把手到管身的短颈
        drawLine(
            color = PAPER_WHITE,
            start = Offset(tapX, vy),
            end = Offset(vx, vy),
            strokeWidth = pipeW * 0.42f,
            alpha = alpha * 0.85f
        )
    }

    // 舞台地板：一条横向暗带 + 追光落地的椭圆光斑，再压一层暖雾表现空气中的尘。
    drawFogBand(HERO_BOTTOM, 0.14f, deep, alpha * DISTANT_ALPHA * 2f)
    drawUnitGlow(GLOW_WHITE, Offset(w * 0.52f, h * 0.375f), w * 0.66f, alpha * 0.20f)
    drawFogBand(0.14f, 0.24f, top, alpha * DISTANT_ALPHA * 0.7f)
}

/**
 * 浴缸四只爪脚：(横向位置, 尺寸比例)。
 *
 * 后两只是缸另一侧的脚，按 0.6 **整体缩小**（不是调透明度）—— 远近用大小表示。
 */
private val TUB_FEET = floatArrayOf(
    0.12f, 1.0f,
    0.86f, 1.0f,
    0.30f, 0.6f,
    0.68f, 0.6f
)

