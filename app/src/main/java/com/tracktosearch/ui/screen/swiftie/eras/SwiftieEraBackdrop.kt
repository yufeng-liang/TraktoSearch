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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.unitHeartPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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

    /** folklore 松林：x, 树高（占屏高）, 树宽 三元组。 */
    val pines: FloatArray = buildTriples(if (lowRam) 9 else 17, 2020) { random ->
        floatArrayOf(
            random.nextFloat(),
            0.16f + random.nextFloat() * 0.30f,
            0.05f + random.nextFloat() * 0.05f
        )
    }

    /** TTPD 手稿页：x, y, 旋转（度）三元组。 */
    val pages: FloatArray = buildTriples(if (lowRam) 3 else 6, 2024) { random ->
        floatArrayOf(
            0.04f + random.nextFloat() * 0.42f,
            0.52f + random.nextFloat() * 0.40f,
            -22f + random.nextFloat() * 44f
        )
    }

    /** 1989 宝丽来：x, y, 旋转（度）三元组。挂在上半屏，不压卡片。 */
    val polaroids: FloatArray = buildTriples(if (lowRam) 2 else 4, 1014) { random ->
        floatArrayOf(
            0.08f + random.nextFloat() * 0.80f,
            0.04f + random.nextFloat() * 0.16f,
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
 * @param lowRam 低配降档：远景层与点阵数量减半，但**不许整层静止** ——
 *   定格的背景在用户眼里就是卡死
 */
@Composable
fun SwiftieEraBackdropLayer(
    outgoing: () -> Int,
    incoming: () -> Int,
    crossfade: () -> Float,
    phase: () -> Float,
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
    Spacer(
        modifier = modifier.fillMaxSize().drawBehind {
            val from = outgoing().coerceIn(0, skies.lastIndex)
            val to = incoming().coerceIn(0, skies.lastIndex)
            val mix = crossfade().coerceIn(0f, 1f)
            val t = phase()
            if (mix <= 0f || from == to) {
                drawStage(from, 1f, t, skies, scratch, shapes, lowRam)
            } else {
                drawStage(from, 1f - mix, t, skies, scratch, shapes, lowRam)
                drawStage(to, mix, t, skies, scratch, shapes, lowRam)
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
    skies: List<Brush>,
    path: Path,
    shapes: BackdropShapes,
    lowRam: Boolean
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
        SwiftieEraBackdrop.KNIT_AUTUMN -> drawKnitAutumn(path, top, mid, deep, phase, alpha, lowRam)
        SwiftieEraBackdrop.SKYLINE_POLAROIDS ->
            drawSkylinePolaroids(path, top, mid, deep, phase, alpha, shapes)
        SwiftieEraBackdrop.HALFTONE_THRONE ->
            drawHalftoneThrone(path, top, mid, phase, alpha, shapes)
        SwiftieEraBackdrop.PASTEL_RAINBOW_HOUSE ->
            drawPastelRainbowHouse(path, top, mid, deep, phase, alpha)
        SwiftieEraBackdrop.PINE_MOSS_PIANO ->
            drawPineMossPiano(path, top, mid, deep, phase, alpha, shapes)
        SwiftieEraBackdrop.BRANCH_LANTERNS ->
            drawBranchLanterns(path, top, mid, deep, phase, alpha, lowRam)
        SwiftieEraBackdrop.MIDNIGHT_CLOCK ->
            drawMidnightClock(path, top, mid, deep, phase, alpha, shapes)
        SwiftieEraBackdrop.TYPEWRITER_DESK ->
            drawTypewriterDesk(path, top, mid, deep, phase, alpha, shapes)
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
private val UNIT_HEART: Path = unitHeartPath()

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
 * 雾的颜色随专辑变，每帧新建一条渐变就是每帧一个原生 Shader。9 条实色矩形在
 * GPU 上便宜得多，而雾本来就没有需要被看清的边界。
 */
private fun DrawScope.drawFogBand(y: Float, height: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    val h = height * size.height
    val sliceH = h / FOG_SLICES
    val top = y * size.height - h / 2f
    for (i in 0 until FOG_SLICES) {
        // 首尾两条本来就该是 0，用 (i + 0.5) 取每条的中点避免整条雾都偏淡
        val profile = sin(PI.toFloat() * (i + 0.5f) / FOG_SLICES)
        drawRect(
            color = color,
            topLeft = Offset(0f, top + i * sliceH),
            // +1 抹掉相邻两条之间那道亚像素缝
            size = Size(size.width, sliceH + 1f),
            alpha = alpha * profile
        )
    }
}

private const val FOG_SLICES = 9

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
 * 门廊那棵阔叶树的树冠：五团圆的 (dx, dy, 半径系数)，dx/dy 是相对冠幅的偏移。
 *
 * 摊成顶层 `FloatArray` 而不是在 draw 里 `listOf(Offset(...) to ...)` ——
 * 那样每帧要新建一个 List、五个 Pair 与五个 Offset，而这些数是常量。
 * 本文件里所有「一小组固定位置」都按这个规矩写。
 */
private val PORCH_CROWN = floatArrayOf(
    0.00f, 0.00f, 1.00f,
    -0.62f, 0.08f, 0.74f,
    0.66f, 0.06f, 0.80f,
    -0.30f, -0.08f, 0.62f,
    0.34f, -0.07f, 0.58f
)

/** 门廊两根立柱的横向位置。 */
private val PORCH_POSTS = floatArrayOf(0.20f, 0.86f)

// ─────────────────────── 1 · Taylor Swift ───────────────────────

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
    val silhouette = alpha * SILHOUETTE_ALPHA

    // 阔叶树：树干 + 五团交叠的树冠。单团圆形读作气球，交叠才有枝叶的轮廓
    val treeX = w * 0.10f
    drawRect(
        color = deep,
        topLeft = Offset(treeX - w * 0.012f, h * 0.30f),
        size = Size(w * 0.024f, h * 0.44f),
        alpha = silhouette
    )
    val crown = w * 0.13f
    for (i in 0 until PORCH_CROWN.size / 3) {
        drawCircle(
            color = deep,
            radius = crown * PORCH_CROWN[i * 3 + 2],
            center = Offset(
                treeX + PORCH_CROWN[i * 3] * crown,
                h * 0.22f + PORCH_CROWN[i * 3 + 1] * h
            ),
            alpha = silhouette
        )
    }

    // 屋檐：一根横梁压住上缘，两根立柱落到地板
    drawRect(color = deep, size = Size(w, h * 0.055f), alpha = silhouette)
    val postW = w * 0.032f
    for (i in PORCH_POSTS.indices) {
        drawRect(
            color = deep,
            topLeft = Offset(w * PORCH_POSTS[i] - postW / 2f, h * 0.055f),
            size = Size(postW, h * 0.70f),
            alpha = silhouette
        )
    }

    // 栏杆：上下两根横档 + 一排立柱。立柱间距 0.038 屏宽，密到读作栏杆而不是围栏
    val railTop = h * 0.60f
    val railBottom = h * 0.735f
    val barW = (w * 0.007f).coerceAtLeast(1f)
    var bx = w * 0.20f + postW
    while (bx < w * 0.86f - postW) {
        drawRect(
            color = deep,
            topLeft = Offset(bx, railTop),
            size = Size(barW, railBottom - railTop),
            alpha = silhouette * 0.85f
        )
        bx += w * 0.038f
    }
    for (i in 0..1) {
        drawRect(
            color = deep,
            topLeft = Offset(w * 0.20f, if (i == 0) railTop else railBottom),
            size = Size(w * 0.66f, h * 0.016f),
            alpha = silhouette
        )
    }

    // 地板：横向木板缝，越远越密（简单的线性收窄就够，门廊只有几步深）
    var boardY = h * 0.755f
    var boardGap = h * 0.030f
    while (boardY < h) {
        drawLine(
            color = deep,
            start = Offset(0f, boardY),
            end = Offset(w, boardY),
            strokeWidth = 1f,
            alpha = silhouette * 0.5f
        )
        boardY += boardGap
        boardGap *= 1.16f
    }

    // 门廊台阶：三级，自下而上收窄。没有台阶的门廊看着像悬空的平台
    for (s in 0 until 3) {
        val inset = w * (0.30f + s * 0.045f)
        path.rewind()
        path.moveTo(inset, h * (0.86f + s * 0.045f))
        path.lineTo(w - inset, h * (0.86f + s * 0.045f))
        path.lineTo(w - inset - w * 0.02f, h * (0.90f + s * 0.045f))
        path.lineTo(inset + w * 0.02f, h * (0.90f + s * 0.045f))
        path.close()
        drawPath(path = path, color = deep, alpha = silhouette * (0.45f + s * 0.12f))
    }

    // 屋内暖光。sin(phase*TAU) 是 phase 的一倍频，绕回 0f 时连续
    val flicker = 0.82f + 0.18f * sin(phase * TAU)
    drawLitWindow(
        left = w * 0.62f,
        top = h * 0.16f,
        width = w * 0.16f,
        height = h * 0.20f,
        frame = deep,
        lit = flicker,
        alpha = alpha * 0.75f
    )
    // 门缝漏出的一道光带，落在地板上
    drawFogBand(0.80f, 0.06f, mid, alpha * DISTANT_ALPHA)
    drawFogBand(0.30f, 0.30f, top, alpha * DISTANT_ALPHA * 0.6f)
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
    val groundY = h * 0.74f
    val center = Offset(w * 0.5f, h * 0.40f)

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
    drawPath(path = path, color = top, alpha = alpha * 0.22f)

    // 城堡：中央主楼 + 左右两座塔 + 三个尖顶 + 旗。都用末档深色，读作逆光剪影
    val silhouette = alpha * SILHOUETTE_ALPHA
    // 塔（x 中心，塔宽，塔高）
    val towers = floatArrayOf(
        0.315f, 0.075f, 0.26f,
        0.50f, 0.115f, 0.36f,
        0.685f, 0.075f, 0.26f
    )
    for (i in 0 until 3) {
        val cx = w * towers[i * 3]
        val tw = w * towers[i * 3 + 1]
        val th = h * towers[i * 3 + 2]
        val towerTop = groundY - th
        drawRect(
            color = deep,
            topLeft = Offset(cx - tw / 2f, towerTop),
            size = Size(tw, th),
            alpha = silhouette
        )
        // 城齿：三个小方块压在塔顶，是「城堡」而不是「烟囱」的关键
        val merlon = tw / 5f
        for (m in 0 until 3) {
            drawRect(
                color = deep,
                topLeft = Offset(cx - tw / 2f + m * merlon * 2f, towerTop - merlon * 0.9f),
                size = Size(merlon, merlon * 0.9f),
                alpha = silhouette
            )
        }
        // 尖顶
        val spire = th * 0.42f
        path.rewind()
        path.moveTo(cx, towerTop - merlon * 0.9f - spire)
        path.lineTo(cx - tw * 0.62f, towerTop - merlon * 0.9f)
        path.lineTo(cx + tw * 0.62f, towerTop - merlon * 0.9f)
        path.close()
        drawPath(path = path, color = deep, alpha = silhouette)
        // 旗：随 phase 摆一下（一倍频，绕回时连续）
        val flagY = towerTop - merlon * 0.9f - spire
        val wave = sin(phase * TAU + i) * tw * 0.10f
        path.rewind()
        path.moveTo(cx, flagY)
        path.lineTo(cx + tw * 0.55f, flagY + tw * 0.14f + wave)
        path.lineTo(cx, flagY + tw * 0.30f)
        path.close()
        drawPath(path = path, color = mid, alpha = alpha * PROP_ALPHA * 1.6f)
    }

    // 城门：拱形。用一段加粗的圆弧当门洞，门内点一盏暖光
    val gateW = w * 0.048f
    drawArc(
        color = deep,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(w * 0.5f - gateW / 2f, groundY - h * 0.075f),
        size = Size(gateW, gateW),
        alpha = silhouette,
        style = Stroke(width = gateW * 0.22f)
    )
    drawUnitGlow(GLOW_WARM, Offset(w * 0.5f, groundY - h * 0.045f), gateW * 2.4f, alpha * 0.45f)

    // 金雾：城堡脚下一层，把剪影与地面之间的硬边化开
    drawFogBand(0.74f, 0.12f, top, alpha * DISTANT_ALPHA * 1.6f)
    drawFogBand(0.20f, 0.26f, mid, alpha * DISTANT_ALPHA * 0.5f)
}

private const val RAY_COUNT = 12

// ─────────────────────── 3 · Speak Now ───────────────────────

/**
 * 紫色薄纱与追光：三层正弦薄纱横过画面 + 两侧垂落的帷幕 + 中央一束追光。
 *
 * *Speak Now* 的封面是那条旋转起来的紫色长裙，巡演也是紫幕 + 追光。薄纱用三条不同
 * 频率与相位的正弦带，**频率都是整数**，所以 [phase] 绕回时三条同时接上。
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

    // 追光：从上缘正中偏左打下来，落在下三分之一
    drawCone(
        path = path,
        apexX = w * 0.44f,
        apexY = -h * 0.04f,
        halfWidth = w * 0.34f,
        bottomY = h * 0.82f,
        color = top,
        alpha = alpha * 0.5f
    )

    // 三层薄纱。每层是一条上下缘都是正弦的带子，靠 lineTo 采样画出来
    val veils = floatArrayOf(
        // y 中心, 带高, 频率(整数), 相位偏移, 振幅
        0.30f, 0.10f, 2f, 0.00f, 0.045f,
        0.52f, 0.14f, 1f, 0.35f, 0.060f,
        0.74f, 0.11f, 3f, 0.65f, 0.030f
    )
    for (v in 0 until 3) {
        val cy = veils[v * 5]
        val bandH = veils[v * 5 + 1]
        val freq = veils[v * 5 + 2]
        val shift = veils[v * 5 + 3]
        val amp = veils[v * 5 + 4]
        path.rewind()
        var first = true
        // 32 段足够：薄纱的边是柔的，多采样只是白烧 CPU
        for (i in 0..VEIL_SAMPLES) {
            val fx = i / VEIL_SAMPLES.toFloat()
            val y = (cy + sin((fx * freq + phase + shift) * TAU) * amp) * h
            if (first) {
                path.moveTo(fx * w, y)
                first = false
            } else {
                path.lineTo(fx * w, y)
            }
        }
        for (i in VEIL_SAMPLES downTo 0) {
            val fx = i / VEIL_SAMPLES.toFloat()
            // 下缘的相位再错开一点，带子才会有宽窄变化，不是一条等宽的面条
            val y = (cy + bandH + sin((fx * freq + phase + shift + 0.18f) * TAU) * amp) * h
            path.lineTo(fx * w, y)
        }
        path.close()
        drawPath(path = path, color = if (v == 1) mid else top, alpha = alpha * 0.16f)
    }

    // 两侧帷幕：各 6 道褶皱，越靠外越暗
    val curtainW = w * 0.17f
    for (side in 0..1) {
        for (i in 0 until 6) {
            val f = i / 5f
            val x = if (side == 0) f * curtainW else w - f * curtainW
            drawLine(
                color = deep,
                start = Offset(x, 0f),
                end = Offset(x + (if (side == 0) 1f else -1f) * w * 0.02f, h),
                strokeWidth = curtainW * 0.34f,
                alpha = alpha * SILHOUETTE_ALPHA * (0.85f - 0.5f * f)
            )
        }
    }
    drawFogBand(0.86f, 0.20f, deep, alpha * DISTANT_ALPHA)
}

private const val VEIL_SAMPLES = 32

// ─────────────────────── 4 · Red ───────────────────────

/**
 * 大幅粗针织 + 秋日暖雾：下三分之二铺满平针的 V 字线圈，中间一道绞花麻花辫。
 *
 * 那条针织围巾是 *All Too Well* 的核心意象，卡片右侧留的是围巾一角，这里铺的是
 * **织物本身**。线圈刻意粗（每行 13 针）—— 围巾是粗棒针织的，细密的针法读作 T 恤。
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
    lowRam: Boolean
) {
    val w = size.width
    val h = size.height
    val fabricTop = h * 0.34f
    val stitchW = w / (if (lowRam) 9f else 13f)
    val stitchH = stitchW * 0.72f
    val stroke = stitchW * 0.17f

    // 全部 V 字塞进同一条 Path 一次画完：几百次 drawLine 会把绘制预算吃光
    path.rewind()
    var row = 0
    var y = fabricTop
    while (y < h + stitchH) {
        // 隔行错半针，这是平针织物的样子；不错行就成了菱形网格
        val offsetX = if (row % 2 == 0) 0f else stitchW / 2f
        var x = -stitchW + offsetX
        while (x < w + stitchW) {
            path.moveTo(x, y)
            path.lineTo(x + stitchW / 2f, y + stitchH * 0.62f)
            path.lineTo(x + stitchW, y)
            x += stitchW
        }
        y += stitchH
        row++
    }
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * PROP_ALPHA * 0.75f,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 绞花麻花辫：两条相位相反的正弦股绞在一起，是粗棒针织最好认的那道花样
    val braidX = w * 0.16f
    val braidAmp = w * 0.045f
    for (strand in 0..1) {
        path.rewind()
        var first = true
        var by = fabricTop
        while (by < h) {
            val f = (by - fabricTop) / (h - fabricTop)
            // 频率 6 是整数，绞花在上下缘都收得住；strand 差半个周期即互绞
            val x = braidX + sin((f * 6f + strand * 0.5f) * TAU) * braidAmp
            if (first) {
                path.moveTo(x, by)
                first = false
            } else {
                path.lineTo(x, by)
            }
            by += h * 0.012f
        }
        drawPath(
            path = path,
            color = deep,
            alpha = alpha * PROP_ALPHA * (if (strand == 0) 1.5f else 1.1f),
            style = Stroke(width = stitchW * 0.42f, cap = StrokeCap.Round)
        )
    }

    // 织物上缘：一道罗纹收边，让织物有「边」而不是渐隐掉
    drawRect(
        color = deep,
        topLeft = Offset(0f, fabricTop - stitchH * 0.5f),
        size = Size(w, stitchH * 0.5f),
        alpha = alpha * PROP_ALPHA * 0.9f
    )

    // 秋雾：上半屏两条暖雾，按 phase 上下轻推（一倍频）
    val drift = sin(phase * TAU) * 0.02f
    drawFogBand(0.14f + drift, 0.22f, top, alpha * DISTANT_ALPHA * 1.4f)
    drawFogBand(0.30f - drift, 0.14f, mid, alpha * DISTANT_ALPHA)
}

// ─────────────────────── 5 · 1989 ───────────────────────

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
    val skylineBase = h * 0.68f

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

    // 宝丽来：白边框 + 灰底 + 下缘那道宽白边。挂在上半屏，压不到卡片
    val polaroids = shapes.polaroids
    val frameW = w * 0.14f
    for (i in 0 until polaroids.size / 3) {
        val px = polaroids[i * 3] * w
        val py = polaroids[i * 3 + 1] * h
        // 挂着的东西会晃：±1.5° 的摆动，相位按序号错开
        val sway = sin((phase + i * 0.31f) * TAU) * 1.5f
        rotate(degrees = polaroids[i * 3 + 2] + sway, pivot = Offset(px, py)) {
            // 图钉与一小段线：宝丽来不会自己浮在空中
            drawLine(
                color = deep,
                start = Offset(px, py - h * 0.035f),
                end = Offset(px, py),
                strokeWidth = 1.5f,
                alpha = alpha * 0.5f
            )
            drawCircle(color = deep, radius = frameW * 0.045f, center = Offset(px, py - h * 0.035f), alpha = alpha * 0.6f)
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
    drawFogBand(0.66f, 0.10f, top, alpha * DISTANT_ALPHA * 1.5f)
}

private const val WAVE_SAMPLES = 28

// ─────────────────────── 6 · reputation ───────────────────────

/**
 * 报纸半调网点 + 金色王座 + 一条大蛇。
 *
 * reputation 的整套视觉就是报纸拼贴与蛇。这一张的三档底色是 `#3A3A3A / #111111 / #000000`
 * ——**唯一一张纯黑白的舞台**，所以王座那点金（[GOLD]）是全屏唯一的彩色，
 * 也就是全屏唯一的视觉焦点。
 */
private fun DrawScope.drawHalftoneThrone(
    path: Path,
    top: Color,
    mid: Color,
    phase: Float,
    alpha: Float,
    shapes: BackdropShapes
) {
    val w = size.width
    val h = size.height

    // 网点：整块一次画完，缓存在 shapes 里（见 BackdropShapes.halftone）
    drawPath(path = shapes.halftone(size), color = top, alpha = alpha * 0.30f)

    // 王座：座面 + 高背 + 两侧扶手 + 三级台阶。全屏唯一的金
    val gold = alpha * SILHOUETTE_ALPHA
    val seatY = h * 0.72f
    val seatW = w * 0.30f
    val seatX = w * 0.50f
    // 台阶：三级，越下越宽
    for (s in 0 until 3) {
        val stepW = seatW * (1.15f + s * 0.28f)
        drawRect(
            color = GOLD,
            topLeft = Offset(seatX - stepW / 2f, seatY + h * (0.055f + s * 0.045f)),
            size = Size(stepW, h * 0.045f),
            alpha = gold * (0.5f - s * 0.11f)
        )
    }
    // 高背：上缘做成三个尖，是「王座」而不是「椅子」
    path.rewind()
    path.moveTo(seatX - seatW * 0.42f, seatY)
    path.lineTo(seatX - seatW * 0.42f, h * 0.40f)
    path.lineTo(seatX - seatW * 0.21f, h * 0.34f)
    path.lineTo(seatX, h * 0.28f)
    path.lineTo(seatX + seatW * 0.21f, h * 0.34f)
    path.lineTo(seatX + seatW * 0.42f, h * 0.40f)
    path.lineTo(seatX + seatW * 0.42f, seatY)
    path.close()
    drawPath(path = path, color = GOLD, alpha = gold * 0.62f)
    // 座面与扶手
    drawRect(
        color = GOLD,
        topLeft = Offset(seatX - seatW / 2f, seatY),
        size = Size(seatW, h * 0.055f),
        alpha = gold * 0.8f
    )
    for (i in 0..1) {
        val side = if (i == 0) -1f else 1f
        drawRect(
            color = GOLD,
            topLeft = Offset(seatX + side * seatW * 0.52f - seatW * 0.04f, h * 0.60f),
            size = Size(seatW * 0.08f, h * 0.12f),
            alpha = gold * 0.7f
        )
    }
    drawUnitGlow(GLOW_WARM, Offset(seatX, h * 0.55f), w * 0.7f, alpha * 0.16f)

    // 大蛇：自左下游到右上，蛇身有粗细变化 + 一片片鳞。
    // 用 top（#3A3A3A）而不是 mid（#111111）—— 纯黑底上画近黑等于没画
    val spineY = 0.46f
    val spineAmp = 0.13f
    // 摆动：整条蛇的相位随 phase 平移一整个周期，绕回 0f 时形状完全重合
    val sway = phase * TAU
    path.rewind()
    // 上缘（顺着走）
    for (i in 0..SNAKE_SAMPLES) {
        val f = i / SNAKE_SAMPLES.toFloat()
        val bx = f * w
        val by = (spineY + sin(f * 2f * TAU + sway) * spineAmp) * h
        val thick = h * 0.030f * (1f - f * 0.72f)
        if (i == 0) path.moveTo(bx, by - thick) else path.lineTo(bx, by - thick)
    }
    // 下缘（倒着回来）
    for (i in SNAKE_SAMPLES downTo 0) {
        val f = i / SNAKE_SAMPLES.toFloat()
        val bx = f * w
        val by = (spineY + sin(f * 2f * TAU + sway) * spineAmp) * h
        val thick = h * 0.030f * (1f - f * 0.72f)
        path.lineTo(bx, by + thick)
    }
    path.close()
    drawPath(path = path, color = top, alpha = alpha * 0.58f)

    // 鳞：沿蛇身每隔一段画一道朝后开口的弧。没有鳞的蛇身就是一条光滑的带子
    for (i in 0 until SNAKE_SCALES) {
        val f = i / SNAKE_SCALES.toFloat()
        val bx = f * w
        val by = (spineY + sin(f * 2f * TAU + sway) * spineAmp) * h
        val thick = h * 0.030f * (1f - f * 0.72f)
        drawArc(
            color = mid,
            startAngle = 120f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(bx - thick * 0.72f, by - thick * 0.72f),
            size = Size(thick * 1.44f, thick * 1.44f),
            alpha = alpha * 0.42f,
            style = Stroke(width = (thick * 0.16f).coerceAtLeast(1f))
        )
    }
    // 蛇头：一个略尖的椭圆 + 一只竖瞳
    val headY = (spineY + sin(sway) * spineAmp) * h
    drawOval(
        color = top,
        topLeft = Offset(-w * 0.02f, headY - h * 0.040f),
        size = Size(w * 0.13f, h * 0.080f),
        alpha = alpha * 0.62f
    )
    drawOval(
        color = GOLD,
        topLeft = Offset(w * 0.055f, headY - h * 0.012f),
        size = Size(w * 0.012f, h * 0.024f),
        alpha = alpha * 0.75f
    )
}

private const val SNAKE_SAMPLES = 36
private const val SNAKE_SCALES = 26

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

/** 一朵云的四团：(dx 相对冠幅, 半径系数)。见 [PORCH_CROWN] 的注释，不在 draw 里建 List。 */
private val CLOUD_PUFFS = floatArrayOf(
    0.00f, 1.00f,
    0.55f, 0.72f,
    -0.50f, 0.66f,
    0.24f, 0.84f
)

/** Lover House 两扇窗的横向位置（相对屋宽）。 */
private val HOUSE_WINDOWS = floatArrayOf(-0.30f, 0.30f)

/**
 * pastel 云海 + 一道彩虹 + 远景 Lover House + 屋顶霓虹心。
 *
 * *Lover* MV 整支设定在那栋粉蓝小屋里。这里的房子是**远景**、只有轮廓与窗光 ——
 * 精细的那一栋在 `SwiftieSnowGlobe` 的球内，收尾那 6.5 秒才登场，两处不能长一样。
 * 霓虹心按 [phase] 呼吸，是这一张唯一在动的东西。
 */
private fun DrawScope.drawPastelRainbowHouse(
    path: Path,
    top: Color,
    mid: Color,
    deep: Color,
    phase: Float,
    alpha: Float
) {
    val w = size.width
    val h = size.height

    // 彩虹：六道同心弧，画在云层之下，所以云能压住虹脚
    val arcR = w * 0.62f
    val arcCenter = Offset(w * 0.52f, h * 0.62f)
    RAINBOW.forEachIndexed { i, band ->
        val r = arcR - i * w * 0.026f
        drawArc(
            color = band,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(arcCenter.x - r, arcCenter.y - r),
            size = Size(r * 2f, r * 2f),
            alpha = alpha * 0.24f,
            style = Stroke(width = w * 0.028f)
        )
    }

    // 云海：三排交叠的圆。单排会读作泡泡，三排错位叠起来才是云
    val cloudRows = floatArrayOf(0.16f, 0.34f, 0.50f)
    cloudRows.forEachIndexed { row, cy ->
        val puffR = w * (0.10f - row * 0.018f)
        // 云整体随 phase 横向平移一个完整的间距，绕回时正好错开一朵，接得上
        val shift = phase * w * 0.26f
        var cx = -puffR * 2f + (row * w * 0.09f) + shift % (w * 0.26f)
        while (cx < w + puffR * 2f) {
            for (p in 0 until CLOUD_PUFFS.size / 2) {
                val dx = CLOUD_PUFFS[p * 2]
                val s = CLOUD_PUFFS[p * 2 + 1]
                drawCircle(
                    color = if (row == 0) top else mid,
                    radius = puffR * s,
                    center = Offset(cx + dx * puffR * 1.6f, cy * h + (1f - s) * puffR * 0.5f),
                    alpha = alpha * (0.26f - row * 0.05f)
                )
            }
            cx += w * 0.26f
        }
    }

    // Lover House（远景）：墙 + 尖顶 + 门 + 两扇亮着的窗
    val houseW = w * 0.30f
    val houseX = w * 0.50f
    val eaveY = h * 0.70f
    val wallH = h * 0.14f
    drawRect(
        color = deep,
        topLeft = Offset(houseX - houseW / 2f, eaveY),
        size = Size(houseW, wallH),
        alpha = alpha * 0.42f
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
            top = eaveY + wallH * 0.22f,
            width = houseW * 0.15f,
            height = wallH * 0.34f,
            frame = deep,
            lit = 1f,
            alpha = alpha * 0.8f
        )
    }

    // 屋顶霓虹心：描边而不是实心 —— 霓虹是一根弯成形状的灯管。
    // 呼吸用 sin(phase*TAU)，一倍频，绕回时连续
    val pulse = 0.68f + 0.32f * sin(phase * TAU)
    val heartSide = houseW * 0.30f
    val heartCx = houseX
    val heartCy = eaveY - houseW * 0.42f - heartSide * 0.72f
    drawUnitGlow(
        GLOW_WHITE,
        Offset(heartCx, heartCy + heartSide * 0.1f),
        heartSide * 3.4f,
        alpha * 0.34f * pulse
    )
    withTransform({
        translate(heartCx - heartSide / 2f, heartCy - heartSide / 2f)
        scale(heartSide, heartSide, pivot = Offset.Zero)
    }) {
        drawPath(
            path = UNIT_HEART,
            color = SwiftiePalette.Glitter,
            alpha = alpha * (0.55f + 0.45f * pulse),
            // 线宽在单位空间里，所以它跟着心一起缩放，改 heartSide 不用重算
            style = Stroke(width = 0.10f, cap = StrokeCap.Round)
        )
    }
    drawFogBand(0.80f, 0.16f, top, alpha * DISTANT_ALPHA * 1.8f)
}

// ─────────────────────── 8 · folklore ───────────────────────

/**
 * 松林 + 灰雾 + 长满青苔的白色三角钢琴 + 一格窗透出的炉火。
 *
 * folklore 是「林子里的小屋」，官方影像 *folklore: the long pond studio sessions* 就是
 * 林中木屋加一台钢琴。钢琴是这一张的记忆点，所以给它完整的结构：琴身、掀开的琴盖、
 * 一排琴键、三条腿，再让青苔从琴身下缘长上来。
 */
private fun DrawScope.drawPineMossPiano(
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
    val groundY = h * 0.80f

    // 松林分两层：远层淡且矮（画在雾之下），近层深且高
    val pines = shapes.pines
    val count = pines.size / 3
    for (layer in 0..1) {
        for (i in 0 until count) {
            if (i % 2 != layer) continue
            val cx = pines[i * 3] * w
            val ph = pines[i * 3 + 1] * h * (if (layer == 0) 0.62f else 1f)
            val pw = pines[i * 3 + 2] * w * (if (layer == 0) 0.72f else 1f)
            drawConifer(
                path = path,
                cx = cx,
                baseY = groundY - (if (layer == 0) h * 0.06f else 0f),
                height = ph,
                halfWidth = pw,
                color = if (layer == 0) mid else deep,
                alpha = alpha * (if (layer == 0) DISTANT_ALPHA * 1.5f else SILHOUETTE_ALPHA)
            )
        }
        // 远层画完压一层雾，近层就自然站到雾前面
        if (layer == 0) drawFogBand(0.66f, 0.16f, top, alpha * DISTANT_ALPHA * 2.2f)
    }

    // 木屋一角：只露右侧一片墙与一扇亮着的窗，整栋画出来会跟 Lover 那张的房子撞
    val cabinX = w * 0.80f
    drawRect(
        color = deep,
        topLeft = Offset(cabinX, h * 0.44f),
        size = Size(w - cabinX, groundY - h * 0.44f),
        alpha = alpha * SILHOUETTE_ALPHA * 0.9f
    )
    path.rewind()
    path.moveTo(cabinX - w * 0.04f, h * 0.44f)
    path.lineTo(cabinX + w * 0.10f, h * 0.36f)
    path.lineTo(w, h * 0.40f)
    path.lineTo(w, h * 0.44f)
    path.close()
    drawPath(path = path, color = deep, alpha = alpha * SILHOUETTE_ALPHA)
    // 炉火：比窗光更闪。两个不同倍频叠起来，读作火苗而不是灯泡
    val fire = 0.72f + 0.18f * sin(phase * TAU) + 0.10f * sin(phase * 3f * TAU)
    drawLitWindow(
        left = cabinX + w * 0.03f,
        top = h * 0.52f,
        width = w * 0.10f,
        height = h * 0.10f,
        frame = deep,
        lit = fire,
        alpha = alpha * 0.85f
    )

    // 三角钢琴：琴身（一侧直、一侧圆的「翼」形）+ 掀开的琴盖 + 琴键 + 三条腿
    val pianoLeft = w * 0.06f
    val pianoRight = w * 0.62f
    val bodyTop = groundY - h * 0.15f
    val bodyBottom = groundY - h * 0.04f
    path.rewind()
    path.moveTo(pianoLeft, bodyTop)
    path.lineTo(pianoLeft, bodyBottom)
    // 圆的那一侧：两段三次曲线绕出琴身的弧
    path.cubicTo(
        pianoLeft + (pianoRight - pianoLeft) * 0.45f, bodyBottom + h * 0.045f,
        pianoRight, bodyBottom - h * 0.01f,
        pianoRight, bodyTop + h * 0.02f
    )
    path.cubicTo(
        pianoRight - (pianoRight - pianoLeft) * 0.18f, bodyTop - h * 0.012f,
        pianoLeft + (pianoRight - pianoLeft) * 0.30f, bodyTop,
        pianoLeft, bodyTop
    )
    path.close()
    drawPath(path = path, color = PAPER_WHITE, alpha = alpha * 0.62f)
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * 0.5f,
        style = Stroke(width = (w * 0.004f).coerceAtLeast(1f))
    )

    // 琴盖：从直边那侧掀起，一块斜的四边形。支杆一根斜撑
    path.rewind()
    path.moveTo(pianoLeft, bodyTop)
    path.lineTo(pianoLeft + (pianoRight - pianoLeft) * 0.16f, bodyTop - h * 0.13f)
    path.lineTo(pianoRight * 0.94f, bodyTop - h * 0.10f)
    path.lineTo(pianoRight, bodyTop + h * 0.02f)
    path.close()
    drawPath(path = path, color = PAPER_WHITE, alpha = alpha * 0.5f)
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * 0.45f,
        style = Stroke(width = (w * 0.003f).coerceAtLeast(1f))
    )
    drawLine(
        color = deep,
        start = Offset(pianoLeft + (pianoRight - pianoLeft) * 0.52f, bodyTop - h * 0.105f),
        end = Offset(pianoLeft + (pianoRight - pianoLeft) * 0.46f, bodyTop),
        strokeWidth = (w * 0.006f).coerceAtLeast(1f),
        alpha = alpha * 0.5f
    )

    // 琴键：白键一条长带，黑键按「2-3」的分组画。少了分组就是一把梳子
    val keyLeft = pianoLeft + (pianoRight - pianoLeft) * 0.05f
    val keyRight = pianoLeft + (pianoRight - pianoLeft) * 0.55f
    val keyTop = bodyBottom - h * 0.012f
    val keyH = h * 0.026f
    drawRect(
        color = PAPER_WHITE,
        topLeft = Offset(keyLeft, keyTop),
        size = Size(keyRight - keyLeft, keyH),
        alpha = alpha * 0.7f
    )
    val octave = (keyRight - keyLeft) / 3f
    for (o in 0 until 3) {
        // 一个八度里 5 个黑键，位置是 2-3 分组：这是钢琴键盘唯一的辨识特征
        floatArrayOf(0.09f, 0.22f, 0.48f, 0.61f, 0.74f).forEach { f ->
            drawRect(
                color = deep,
                topLeft = Offset(keyLeft + octave * (o + f), keyTop),
                size = Size(octave * 0.075f, keyH * 0.62f),
                alpha = alpha * 0.72f
            )
        }
    }

    // 三条腿
    floatArrayOf(0.10f, 0.52f, 0.90f).forEach { f ->
        val lx = pianoLeft + (pianoRight - pianoLeft) * f
        drawRect(
            color = deep,
            topLeft = Offset(lx - w * 0.008f, bodyBottom),
            size = Size(w * 0.016f, groundY - bodyBottom + h * 0.02f),
            alpha = alpha * SILHOUETTE_ALPHA * 0.8f
        )
    }

    // 青苔：沿琴身下缘与琴盖上缘长一排不规则的团。大小按位置的正弦变化，不用再抽随机
    for (i in 0 until 14) {
        val f = i / 13f
        val mx = pianoLeft + (pianoRight - pianoLeft) * f
        val blob = w * (0.012f + 0.014f * abs(sin(f * 5.3f)))
        drawCircle(
            color = if (i % 3 == 0) MOSS_LIGHT else MOSS,
            radius = blob,
            center = Offset(mx, bodyBottom - h * 0.004f + blob * 0.3f),
            alpha = alpha * 0.34f
        )
        if (i % 2 == 0) {
            drawCircle(
                color = MOSS,
                radius = blob * 0.7f,
                center = Offset(mx + blob, bodyTop - h * 0.10f * f),
                alpha = alpha * 0.26f
            )
        }
    }
    drawFogBand(0.84f, 0.14f, top, alpha * DISTANT_ALPHA * 1.6f)
}

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

/**
 * 枝条 + 金线 + 挂在枝上的灯笼。
 *
 * evermore 是 folklore 的冬季姊妹作，官方视觉是枯枝、格纹与那件橙棕大衣。金线来自
 * *willow* 的 MV（一根金线牵着人走过整支片子），所以它必须**贯穿整个画面**，
 * 而不是缩在角落里当装饰。
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
    val depth = if (lowRam) 4 else 5

    // 三丛枝：自上缘垂下来，角度与分叉扰动各不相同
    val roots = floatArrayOf(
        // x, 起始角度, 长度比例, bend
        0.14f, 74f, 0.17f, 0.28f,
        0.52f, 96f, 0.20f, -0.34f,
        0.86f, 104f, 0.15f, 0.18f
    )
    for (i in 0 until 3) {
        drawBranch(
            x = w * roots[i * 4],
            y = -h * 0.02f,
            length = h * roots[i * 4 + 2],
            angleDeg = roots[i * 4 + 1],
            width = w * 0.016f,
            depth = depth,
            bend = roots[i * 4 + 3],
            color = deep,
            alpha = alpha * SILHOUETTE_ALPHA
        )
    }

    // 金线：一条贯穿左右的正弦，频率 2（整数），随 phase 平移一整个周期
    path.rewind()
    for (i in 0..THREAD_SAMPLES) {
        val f = i / THREAD_SAMPLES.toFloat()
        val y = (0.56f + sin((f * 2f + phase) * TAU) * 0.10f) * h
        if (i == 0) path.moveTo(0f, y) else path.lineTo(f * w, y)
    }
    drawPath(
        path = path,
        color = GOLD,
        alpha = alpha * 0.55f,
        style = Stroke(width = (w * 0.0055f).coerceAtLeast(1f), cap = StrokeCap.Round)
    )

    // 灯笼：吊线 + 顶盖 + 上下收窄的灯身 + 竖骨 + 底坠。三盏各自摆动、各自呼吸
    val lanterns = floatArrayOf(
        // x, 吊线长度比例, 尺寸比例, 相位偏移
        0.24f, 0.30f, 1.00f, 0.00f,
        0.60f, 0.42f, 0.82f, 0.37f,
        0.80f, 0.24f, 0.68f, 0.68f
    )
    for (i in 0 until 3) {
        val hangX = w * lanterns[i * 4]
        val cordLen = h * lanterns[i * 4 + 1]
        val scale = lanterns[i * 4 + 2]
        val shift = lanterns[i * 4 + 3]
        // 摆动：吊线像单摆一样绕挂点转 ±2.4°，相位错开所以三盏不同步
        val swing = sin((phase + shift) * TAU) * 2.4f
        rotate(degrees = swing, pivot = Offset(hangX, h * 0.04f)) {
            val bodyW = w * 0.062f * scale
            val bodyH = bodyW * 1.35f
            val cy = h * 0.04f + cordLen
            drawLine(
                color = deep,
                start = Offset(hangX, h * 0.04f),
                end = Offset(hangX, cy - bodyH / 2f),
                strokeWidth = 1.5f,
                alpha = alpha * 0.55f
            )
            // 顶盖与底座：两条短横，灯笼的上下沿
            for (e in 0..1) {
                val edgeY = if (e == 0) cy - bodyH / 2f else cy + bodyH / 2f
                drawRect(
                    color = deep,
                    topLeft = Offset(hangX - bodyW * 0.58f, edgeY - bodyH * 0.05f),
                    size = Size(bodyW * 1.16f, bodyH * 0.10f),
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
    drawFogBand(0.10f, 0.18f, mid, alpha * DISTANT_ALPHA * 0.9f)
    drawFogBand(0.88f, 0.16f, top, alpha * DISTANT_ALPHA * 1.5f)
}

private const val THREAD_SAMPLES = 40

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

/**
 * 午夜星空 + 一面星形指针的钟 + 薰衣草雾。
 *
 * Midnights 的封面是那只手举着的打火机与深蓝，而「午夜」这个词本身需要一面钟才落得实。
 * 两根指针都指向 12 —— 那是这张专辑的书名。星星的明灭是 [phase] 的**二倍频**，
 * 相位按序号错开，所以不会整片一起闪。
 */
private fun DrawScope.drawMidnightClock(
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

    // 钟：表圈 + 12 个刻度（3/6/9/12 加粗）+ 两根指向 12 的星形指针
    val clockCenter = Offset(w * 0.50f, h * 0.44f)
    val clockR = w * 0.26f
    drawCircle(
        color = top,
        radius = clockR,
        center = clockCenter,
        alpha = alpha * 0.40f,
        style = Stroke(width = w * 0.010f)
    )
    drawCircle(color = deep, radius = clockR * 0.96f, center = clockCenter, alpha = alpha * 0.22f)
    for (i in 0 until 12) {
        val a = i / 12f * TAU - PI.toFloat() / 2f
        val major = i % 3 == 0
        val r0 = clockR * (if (major) 0.80f else 0.87f)
        drawLine(
            color = top,
            start = Offset(clockCenter.x + cos(a) * r0, clockCenter.y + sin(a) * r0),
            end = Offset(clockCenter.x + cos(a) * clockR * 0.94f, clockCenter.y + sin(a) * clockR * 0.94f),
            strokeWidth = if (major) w * 0.008f else w * 0.004f,
            alpha = alpha * (if (major) 0.55f else 0.35f),
            cap = StrokeCap.Round
        )
    }
    // 两根指针都朝上 = 午夜。时针短而粗，分针长而细，星尖收在指针末端
    for (i in 0..1) {
        val len = if (i == 0) 0.46f else 0.78f
        val thick = if (i == 0) 0.016f else 0.010f
        drawLine(
            color = SwiftiePalette.Lavender,
            start = clockCenter,
            end = Offset(clockCenter.x, clockCenter.y - clockR * len),
            strokeWidth = w * thick,
            alpha = alpha * 0.62f,
            cap = StrokeCap.Round
        )
        starInto(path, clockCenter.x, clockCenter.y - clockR * len, w * 0.026f, phase * 360f / 5f)
        drawPath(path = path, color = Color.White, alpha = alpha * 0.68f)
    }
    drawCircle(color = SwiftiePalette.Lavender, radius = w * 0.012f, center = clockCenter, alpha = alpha * 0.7f)
    drawUnitGlow(GLOW_WHITE, clockCenter, clockR * 2.6f, alpha * 0.14f)

    // 薰衣草雾：两条斜过画面。旋转一个固定角度再铺横带，比写一条斜向渐变省事
    rotate(degrees = -14f, pivot = Offset(w / 2f, h / 2f)) {
        drawFogBand(0.30f + sin(phase * TAU) * 0.015f, 0.20f, SwiftiePalette.Lavender, alpha * DISTANT_ALPHA)
        drawFogBand(0.78f - sin(phase * TAU) * 0.015f, 0.26f, mid, alpha * DISTANT_ALPHA * 1.3f)
    }
}

// ─────────────────────── 11 · The Tortured Poets Department ───────────────────────

/**
 * 打字机 + 散落的手稿页 + 台灯光锥。
 *
 * TTPD 的整套视觉是打字机、稿纸与誓约（*The Manuscript* 就是最后一首）。打字机占
 * **右下约 40% 画面**，是这一张唯一的主体，所以它必须有真结构：压纸滚筒、卷进去的纸、
 * 两个色带盘、四排弧形键盘、一束从键盘扫向滚筒的字锤，以及左侧那根回车杆。
 *
 * 卡片那一侧配的是信纸与羽毛笔（见 `SwiftieEraMotifs` 的 `LETTER_QUILL`）——
 * 一张写信、一台打字，两件事不重复。
 */
private fun DrawScope.drawTypewriterDesk(
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

    // 台灯光锥：自左上打向右下，照在打字机上。锥体本身是竖直的，绕锥顶转 34°
    val apex = Offset(w * 0.06f, -h * 0.03f)
    rotate(degrees = 34f, pivot = apex) {
        drawCone(
            path = path,
            apexX = apex.x,
            apexY = apex.y,
            halfWidth = w * 0.42f,
            bottomY = h * 1.05f,
            color = LAMP_CORE,
            alpha = alpha * 0.30f
        )
    }

    // 手稿页：白纸 + 几条代表字迹的短横线 + 一角折起
    val pages = shapes.pages
    val pageW = w * 0.19f
    for (i in 0 until pages.size / 3) {
        val px = pages[i * 3] * w
        val py = pages[i * 3 + 1] * h
        rotate(degrees = pages[i * 3 + 2], pivot = Offset(px, py)) {
            val pageH = pageW * 1.32f
            drawRect(
                color = PAPER_WHITE,
                topLeft = Offset(px - pageW / 2f, py - pageH / 2f),
                size = Size(pageW, pageH),
                alpha = alpha * 0.55f
            )

            // 字迹：8 行，行长按正弦变化（段落不会每行一样长）。最后一行刻意很短，
            // 那是「写到这里停下了」
            for (line in 0 until 8) {
                val lineLen = if (line == 7) 0.34f else 0.58f + 0.32f * abs(sin(line * 1.7f + i))
                drawLine(
                    color = INK,
                    start = Offset(px - pageW * 0.36f, py - pageH * 0.34f + pageH * 0.088f * line),
                    end = Offset(px - pageW * 0.36f + pageW * 0.72f * lineLen, py - pageH * 0.34f + pageH * 0.088f * line),
                    strokeWidth = (pageH * 0.012f).coerceAtLeast(1f),
                    alpha = alpha * 0.30f
                )
            }
            // 折角：右下一小块翻起来，纸才有厚度
            path.rewind()
            path.moveTo(px + pageW / 2f, py + pageH / 2f - pageW * 0.16f)
            path.lineTo(px + pageW / 2f, py + pageH / 2f)
            path.lineTo(px + pageW / 2f - pageW * 0.16f, py + pageH / 2f)
            path.close()
            drawPath(path = path, color = mid, alpha = alpha * 0.5f)
        }
    }

    // ── 打字机本体 ──
    val tLeft = w * 0.50f
    val tRight = w * 1.02f
    val tW = tRight - tLeft
    val baseY = h * 0.97f
    val bodyTop = h * 0.70f

    // 机身：前低后高的梯形，前缘略外扩
    path.rewind()
    path.moveTo(tLeft + tW * 0.06f, bodyTop)
    path.lineTo(tRight - tW * 0.02f, bodyTop)
    path.lineTo(tRight, baseY)
    path.lineTo(tLeft, baseY)
    path.close()
    drawPath(path = path, color = INK, alpha = alpha * SILHOUETTE_ALPHA * 1.25f)
    // 底座那道亮边，把机身与桌面分开
    drawLine(
        color = mid,
        start = Offset(tLeft, baseY),
        end = Offset(tRight, baseY),
        strokeWidth = (h * 0.005f).coerceAtLeast(1f),
        alpha = alpha * 0.5f
    )

    // 卷进滚筒的那张纸：从滚筒后面升起，微微后倾。上面已经打了几行字
    val paperW = tW * 0.46f
    val paperH = h * 0.30f
    val paperX = tLeft + tW * 0.30f
    val paperTop = bodyTop - paperH
    rotate(degrees = -4f, pivot = Offset(paperX + paperW / 2f, bodyTop)) {
        drawRect(
            color = PAPER_WHITE,
            topLeft = Offset(paperX, paperTop),
            size = Size(paperW, paperH),
            alpha = alpha * 0.66f
        )
        for (line in 0 until 6) {
            val len = 0.52f + 0.40f * abs(sin(line * 2.3f))
            drawLine(
                color = INK,
                start = Offset(paperX + paperW * 0.10f, paperTop + paperH * (0.16f + line * 0.11f)),
                end = Offset(paperX + paperW * (0.10f + 0.78f * len), paperTop + paperH * (0.16f + line * 0.11f)),
                strokeWidth = (paperH * 0.014f).coerceAtLeast(1f),
                alpha = alpha * 0.34f
            )
        }
        // 光标：正在打的那一行末尾，按 phase 闪。二倍频 = 一个周期闪两次
        val caretOn = if (sin(phase * 2f * TAU) > 0f) 1f else 0.15f
        drawRect(
            color = INK,
            topLeft = Offset(paperX + paperW * 0.66f, paperTop + paperH * 0.70f),
            size = Size(paperW * 0.022f, paperH * 0.075f),
            alpha = alpha * 0.62f * caretOn
        )
    }

    // 压纸滚筒：一根横着的圆柱。两端的旋钮 + 表面一道高光是它的全部证据
    val platenY = bodyTop
    val platenH = h * 0.042f
    drawRoundRect(
        color = mid,
        topLeft = Offset(tLeft + tW * 0.10f, platenY - platenH / 2f),
        size = Size(tW * 0.84f, platenH),
        cornerRadius = CornerRadius(platenH / 2f),
        alpha = alpha * 0.62f
    )
    drawLine(
        color = PAPER_WHITE,
        start = Offset(tLeft + tW * 0.14f, platenY - platenH * 0.22f),
        end = Offset(tLeft + tW * 0.90f, platenY - platenH * 0.22f),
        strokeWidth = platenH * 0.16f,
        alpha = alpha * 0.5f,
        cap = StrokeCap.Round
    )
    for (i in 0..1) {
        val f = if (i == 0) 0.10f else 0.94f
        drawCircle(
            color = INK,
            radius = platenH * 0.78f,
            center = Offset(tLeft + tW * f, platenY),
            alpha = alpha * SILHOUETTE_ALPHA * 1.3f
        )
        drawCircle(
            color = mid,
            radius = platenH * 0.34f,
            center = Offset(tLeft + tW * f, platenY),
            alpha = alpha * 0.5f
        )
    }

    // 两个色带盘：机身上方左右各一，盘心一个轴
    for (i in 0..1) {
        val cx = tLeft + tW * (if (i == 0) 0.24f else 0.72f)
        val cy = bodyTop + h * 0.035f
        drawCircle(color = mid, radius = tW * 0.055f, center = Offset(cx, cy), alpha = alpha * 0.45f)
        drawCircle(
            color = INK,
            radius = tW * 0.055f,
            center = Offset(cx, cy),
            alpha = alpha * 0.5f,
            style = Stroke(width = (tW * 0.008f).coerceAtLeast(1f))
        )
        drawCircle(color = INK, radius = tW * 0.014f, center = Offset(cx, cy), alpha = alpha * 0.6f)
    }

    // 字锤：一束从键盘扫向滚筒的细杆，正在打的那一根抬起来
    val strikeIndex = (phase * TYPE_BARS).toInt() % TYPE_BARS
    for (i in 0 until TYPE_BARS) {
        val f = i / (TYPE_BARS - 1f)
        val fromX = tLeft + tW * (0.24f + 0.52f * f)
        val lift = if (i == strikeIndex) 1f else 0f
        drawLine(
            color = INK,
            start = Offset(fromX, bodyTop + h * 0.075f),
            end = Offset(
                tLeft + tW * 0.50f + (fromX - (tLeft + tW * 0.50f)) * 0.25f,
                bodyTop + h * (0.012f - 0.012f * lift)
            ),
            strokeWidth = (tW * 0.006f).coerceAtLeast(1f),
            alpha = alpha * (0.30f + 0.45f * lift)
        )
    }

    // 键盘：四排弧形排列的圆键。每排比上一排更宽、更靠前，这是打字机键盘的阶梯
    for (row in 0 until 4) {
        val rowF = row / 3f
        val keys = 10 - row % 2
        val rowY = bodyTop + h * (0.115f + rowF * 0.055f)
        val rowHalf = tW * (0.30f + rowF * 0.055f)
        val keyR = tW * 0.026f
        for (k in 0 until keys) {
            val kf = if (keys == 1) 0.5f else k / (keys - 1f)
            val kx = tLeft + tW * 0.50f + (kf - 0.5f) * rowHalf * 2f
            // 弧：中间的键比两端低一点，手指才落得舒服
            val arc = (kf - 0.5f) * (kf - 0.5f) * h * 0.030f
            drawCircle(color = mid, radius = keyR, center = Offset(kx, rowY - arc), alpha = alpha * 0.42f)
            drawCircle(
                color = PAPER_WHITE,
                radius = keyR * 0.62f,
                center = Offset(kx, rowY - arc - keyR * 0.12f),
                alpha = alpha * 0.30f
            )
        }
    }

    // 回车杆：左端一根往左上翘出去的杆，末端一个握把。少了它一眼就知道是台假打字机
    drawLine(
        color = INK,
        start = Offset(tLeft + tW * 0.10f, platenY - platenH * 0.2f),
        end = Offset(tLeft - tW * 0.10f, platenY - h * 0.045f),
        strokeWidth = (tW * 0.014f).coerceAtLeast(1f),
        alpha = alpha * SILHOUETTE_ALPHA * 1.2f,
        cap = StrokeCap.Round
    )
    drawCircle(
        color = mid,
        radius = tW * 0.022f,
        center = Offset(tLeft - tW * 0.10f, platenY - h * 0.045f),
        alpha = alpha * 0.5f
    )
    // 桌面：一条压在打字机脚下的暗带，机器才是「放在桌上」而不是浮着
    drawFogBand(0.965f, 0.075f, deep, alpha * DISTANT_ALPHA * 2.4f)
    drawFogBand(0.14f, 0.22f, top, alpha * DISTANT_ALPHA)
}

private const val TYPE_BARS = 9

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
        bottomY = h * 0.86f,
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

    // marquee 灯牌：只画灯泡围成的矩形边框，**不写字** —— 写了就是在画 logo。
    // 跑马灯：亮点沿边框跑一圈正好一个相位周期
    val frameLeft = w * 0.30f
    val frameRight = w * 0.70f
    val frameTop = h * 0.10f
    val frameBottom = h * 0.24f
    val perimeter = 2f * ((frameRight - frameLeft) + (frameBottom - frameTop))
    val bulbR = w * 0.009f
    val bulbStep = perimeter / MARQUEE_BULBS
    for (i in 0 until MARQUEE_BULBS) {
        var d = i * bulbStep
        val bx: Float
        val by: Float
        val topW = frameRight - frameLeft
        val sideH = frameBottom - frameTop
        when {
            d < topW -> { bx = frameLeft + d; by = frameTop }
            d < topW + sideH -> { bx = frameRight; by = frameTop + (d - topW) }
            d < topW * 2f + sideH -> { bx = frameRight - (d - topW - sideH); by = frameBottom }
            else -> { d -= topW * 2f + sideH; bx = frameLeft; by = frameBottom - d }
        }
        // 亮点是一段沿周长移动的窗口，窗口内渐亮渐暗
        val head = phase * MARQUEE_BULBS
        val dist = abs(((i - head + MARQUEE_BULBS) % MARQUEE_BULBS))
        val near = minOf(dist, MARQUEE_BULBS - dist)
        val lit = (1f - near / 3f).coerceIn(0f, 1f)
        drawBulb(Offset(bx, by), bulbR, lit, alpha * 0.9f)
    }
    // 灯牌底板：灯泡围出来的那块要有面，不然灯泡像浮在空中
    drawRect(
        color = deep,
        topLeft = Offset(frameLeft, frameTop),
        size = Size(frameRight - frameLeft, frameBottom - frameTop),
        alpha = alpha * 0.22f
    )

    // 空浴缸：外沿一圈厚唇 + 缸体 + 四只爪脚 + 水面高光。**缸里没有人**
    val tubLeft = w * 0.30f
    val tubRight = w * 0.74f
    val tubTop = h * 0.62f
    val tubBottom = h * 0.84f
    val tubW = tubRight - tubLeft
    // 缸体：上宽下窄的圆角形
    path.rewind()
    path.moveTo(tubLeft, tubTop)
    path.lineTo(tubRight, tubTop)
    path.lineTo(tubRight - tubW * 0.07f, tubBottom - tubW * 0.05f)
    path.quadraticTo(
        (tubLeft + tubRight) / 2f, tubBottom + tubW * 0.04f,
        tubLeft + tubW * 0.07f, tubBottom - tubW * 0.05f
    )
    path.close()
    drawPath(path = path, color = PAPER_WHITE, alpha = alpha * 0.42f)
    drawPath(
        path = path,
        color = deep,
        alpha = alpha * 0.45f,
        style = Stroke(width = (w * 0.004f).coerceAtLeast(1f))
    )
    // 厚唇：一条压在缸口的椭圆环，浴缸的边是有厚度的
    drawOval(
        color = PAPER_WHITE,
        topLeft = Offset(tubLeft - tubW * 0.02f, tubTop - tubW * 0.055f),
        size = Size(tubW * 1.04f, tubW * 0.115f),
        alpha = alpha * 0.55f
    )
    drawOval(
        color = deep,
        topLeft = Offset(tubLeft + tubW * 0.03f, tubTop - tubW * 0.030f),
        size = Size(tubW * 0.94f, tubW * 0.075f),
        alpha = alpha * 0.30f
    )
    // 爪脚：前两只看得见，后两只只露一点
    for (i in 0 until TUB_FEET.size / 2) {
        val f = TUB_FEET[i * 2]
        val vis = TUB_FEET[i * 2 + 1]
        val fx = tubLeft + tubW * f
        drawRoundRect(
            color = mid,
            topLeft = Offset(fx - tubW * 0.022f, tubBottom - tubW * 0.03f),
            size = Size(tubW * 0.044f, tubW * 0.075f * vis),
            cornerRadius = CornerRadius(tubW * 0.02f),
            alpha = alpha * 0.45f * vis
        )
    }
    // 龙头：右端一根弯管
    drawArc(
        color = GOLD,
        startAngle = 180f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = Offset(tubRight - tubW * 0.02f, tubTop - tubW * 0.16f),
        size = Size(tubW * 0.10f, tubW * 0.13f),
        alpha = alpha * 0.55f,
        style = Stroke(width = (tubW * 0.018f).coerceAtLeast(1f), cap = StrokeCap.Round)
    )
    // 舞台地板：一条横向的暗带 + 一道被追光照亮的椭圆光斑，再压一层暖雾当空气中的尘
    drawFogBand(0.90f, 0.14f, deep, alpha * DISTANT_ALPHA * 2f)
    drawUnitGlow(GLOW_WHITE, Offset(w * 0.52f, h * 0.86f), w * 0.66f, alpha * 0.20f)
    drawFogBand(0.42f, 0.34f, top, alpha * DISTANT_ALPHA * 0.7f)
}

private const val MARQUEE_BULBS = 26

/** 浴缸四只爪脚：(横向位置, 可见度)。后两只被缸体挡住，只露一点。 */
private val TUB_FEET = floatArrayOf(
    0.12f, 1.0f,
    0.86f, 1.0f,
    0.30f, 0.6f,
    0.68f, 0.6f
)







































