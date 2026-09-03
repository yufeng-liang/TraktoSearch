package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.unitHeartPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

/** 萤火虫虫体。黄绿冷光不在 Taylor Swift 的青绿色阶里 —— 用主色画萤火虫读作一滴水。 */
private val FIREFLY_BODY = Color(0xFF2E3A22)

/** 枫叶背面。Red 那三档里没有橙黄，而正反同色的叶子翻面时根本看不出在翻。 */
private val LEAF_BACK = Color(0xFFE8912A)

/** 松针的苔痕档。folklore 整套全灰，纯灰的细长条读作一根头发。 */
private val NEEDLE_MOSS = Color(0xFF67705C)

/**
 * 轮次 hash：把「这是第几趟」揉进种子，重掷这一趟的起点 / 尺寸 / 摆动相位。
 *
 * `SwiftieGlitterHearts` 那套 `travel % 1f` 会让同一个个体每一趟都从**同一个 x、
 * 同一个尺寸**再来一遍。灯箱只闪几秒看不出来，背景层一段要待十秒以上，
 * 第二趟落回第一趟的轨迹上就被看穿了。
 */
private fun hash01(seed: Int, round: Int, salt: Int): Float {
    var h = (seed * 0x1b873593) xor (round * 0x27d4eb2d) xor (salt * 0x165667b1)
    h = h xor (h ushr 15)
    h *= 0x2545f491
    h = h xor (h ushr 13)
    // 只取低 24 位：带上符号位会有一半结果落进负区
    return (h and 0x00FFFFFF) / 16777215f
}

/** 三角波，-1f..1f，周期 1。羽毛落下是一顿一顿的，正弦太顺。 */
private fun triangle(t: Float): Float = 4f * abs(t - floor(t + 0.5f)) - 1f

/**
 * 两条不成整数比的正弦叠出来的横向位移（Lissajous 的一个轴）。
 *
 * 单条 sin 一趟就闭合一次，几个个体一起看就是同一条摆线；两条叠起来在一趟里
 * 不重复自己，也不会和邻居对齐。
 */
private fun lissajous(t: Float, freqA: Float, freqB: Float, shift: Float): Float =
    sin((t * freqA + shift) * TAU) * 0.66f + sin((t * freqB + shift * 1.7f) * TAU) * 0.34f

/** 进出画面各留一段淡入淡出，不在边缘硬切。 */
private fun edgeFade(t: Float, span: Float): Float =
    (t / span).coerceAtMost((1f - t) / span).coerceIn(0f, 1f)

/**
 * 一个飘落的个体。**只存不随时间变的量** —— 位置、翻面角、亮度都在 draw lambda 里算。
 */
private class Mote(
    /** 与轮次一起喂 [hash01]，重掷这一趟的起点 / 尺寸 / 摆动相位 */
    val seed: Int,
    /**
     * 一个 phase 周期跑几趟。**必须是整数**：phase 从 1f 绕回 0f 时
     * `phase * rounds` 从 rounds 跳回 0，小数部分不变，位置就是连续的。
     */
    val rounds: Int,
    /** 起点相位 0f..1f。按序号均分再抖一点，同一群不会挤成一列 */
    val offset: Float,
    /** 基准尺寸，相对 `minDimension` */
    val size: Float,
    /** 横向摆幅，相对宽度 */
    val amp: Float,
    /** Lissajous 主频 */
    val freqA: Float,
    /** Lissajous 副频。与 [freqA] 不成整数比 */
    val freqB: Float,
    /** 翻面 / 扑翼 / 闪烁的速率，**phase 的整数倍**，绕回周期时不跳 */
    val beat: Int,
    /** 上面那条节拍的相位偏移。同一群里没有两个是同步的 */
    val beatOffset: Float,
    /** 绕 z 的自转圈数，**整数**且带符号 —— 一半个体反着转 */
    val twist: Int,
    /** 路径曲率 / 静态倾角 / 斜落坡度，-1f..1f */
    val bend: Float
)

/**
 * 个体的行程。整数部分是「第几趟」，小数部分是这一趟走了多少。
 *
 * 见 [Mote.rounds]：整数圈数保证位置在 phase 接缝上连续。
 */
private fun travelOf(mote: Mote, phase: Float): Float = phase * mote.rounds + mote.offset

/**
 * 这一趟的轮次编号，用来喂 [hash01]。
 *
 * 取 `mod rounds` 是必须的：phase 绕回 0f 时 `floor(travel)` 会从 rounds 跳到 0，
 * 不取模的话重掷出来的 x 会在周期接缝上把整群瞬移一次。
 */
private fun roundOf(mote: Mote, travel: Float): Int = floor(travel).toInt().mod(mote.rounds)

/**
 * 3–8 个能看清细节的个体，低端机再减半但保底 2 个。
 *
 * 廉价感几乎全部来自「密集小点」—— 宁可只画 4 个，每个都认得出是什么。
 */
private fun swarmSize(base: Int, lowRam: Boolean): Int =
    if (lowRam) (base / 2).coerceAtLeast(2) else base

/** 固定种子建一群。种子用发行年份，与 `SwiftieEraMotifs` 同一套约定，重组不跳位。 */
private fun buildSwarm(
    seed: Int,
    count: Int,
    rounds: IntRange,
    size: ClosedFloatingPointRange<Float>,
    amp: Float,
    beat: IntRange
): List<Mote> {
    val random = Random(seed)
    return List(count) { index ->
        Mote(
            seed = seed * 31 + index * 7,
            rounds = rounds.first + random.nextInt(rounds.last - rounds.first + 1),
            // 均分 + 抖动：纯随机会有两个个体前后脚落在一起，看着像卡了一下
            offset = (index + random.nextFloat() * 0.7f) / count,
            size = size.start + random.nextFloat() * (size.endInclusive - size.start),
            amp = amp * (0.45f + random.nextFloat() * 0.85f),
            freqA = 0.85f + random.nextFloat() * 0.5f,
            freqB = 2.31f + random.nextFloat() * 1.4f,
            beat = beat.first + random.nextInt(beat.last - beat.first + 1),
            beatOffset = random.nextFloat(),
            twist = (1 + random.nextInt(2)) * (if (random.nextBoolean()) 1 else -1),
            bend = random.nextFloat() * 2f - 1f
        )
    }
}

/** 预建好的个体表。全在组合阶段建一次 —— draw lambda 里一个对象都不 new。 */
private class Swarms(
    val motes: Map<SwiftieEraParticle, List<Mote>>,
    /** Lover 那张是心与蝴蝶混在一起，蝴蝶走另一个 mover，单独一张表 */
    val butterflies: List<Mote>
)

/**
 * 10 群一次全建好，不按当前索引懒建。
 *
 * 总共 50 上下个 [Mote]，一次性的开销；懒建反而要在换张那 500ms 的交叉淡变里
 * 掏出新表，正好撞在最忙的那一帧上。
 *
 * `rounds` 的档位就是六组 mover 的速度差：金箔 4..6 最快，枯叶 / 紫闪粉 / 羽毛
 * 2..2 最慢，其余落在中间。**evermore 用 202012 而不是 2020** —— 它和 folklore
 * 同一年，同种子会让枯叶和松针的分布一模一样。
 */
private fun buildSwarms(lowRam: Boolean): Swarms = Swarms(
    motes = mapOf(
        SwiftieEraParticle.FIREFLY to buildSwarm(
            2006, swarmSize(7, lowRam), 3..4, 0.008f..0.013f, 0.10f, 2..7
        ),
        SwiftieEraParticle.GOLD_FLAKE to buildSwarm(
            2008, swarmSize(8, lowRam), 4..6, 0.018f..0.030f, 0.06f, 6..10
        ),
        SwiftieEraParticle.AUTUMN_LEAF to buildSwarm(
            2012, swarmSize(5, lowRam), 2..3, 0.055f..0.085f, 0.09f, 2..3
        ),
        SwiftieEraParticle.SEAGULL to buildSwarm(
            2014, swarmSize(4, lowRam), 2..3, 0.030f..0.050f, 0.05f, 1..2
        ),
        SwiftieEraParticle.HEART_BUTTERFLY to buildSwarm(
            2019, swarmSize(4, lowRam), 3..4, 0.030f..0.048f, 0.05f, 2..4
        ),
        SwiftieEraParticle.PINE_NEEDLE to buildSwarm(
            2020, swarmSize(6, lowRam), 3..4, 0.075f..0.115f, 0.012f, 1..2
        ),
        SwiftieEraParticle.DRY_LEAF to buildSwarm(
            202012, swarmSize(5, lowRam), 2..2, 0.040f..0.060f, 0.07f, 1..1
        ),
        SwiftieEraParticle.PURPLE_GLITTER to buildSwarm(
            2022, swarmSize(7, lowRam), 2..2, 0.014f..0.024f, 0.02f, 3..7
        ),
        SwiftieEraParticle.PAPER_SCRAP to buildSwarm(
            2024, swarmSize(4, lowRam), 2..3, 0.050f..0.075f, 0.08f, 2..4
        ),
        SwiftieEraParticle.FEATHER to buildSwarm(
            2025, swarmSize(3, lowRam), 2..2, 0.110f..0.170f, 0.11f, 2..3
        )
    ),
    // 蝴蝶横穿画面，扑翼 8..12 拍，和上浮的心共用不了一套参数
    butterflies = buildSwarm(20190823, swarmSize(3, lowRam), 3..4, 0.028f..0.042f, 0.05f, 8..12)
)

/**
 * 页面背景的 L2 前景飘落物。按 `SwiftieErasData.STAGE[index].particle` 分发。
 *
 * `particle == null` 的两张（Speak Now 与 reputation）什么都不画 —— 那两张的背景
 * 本体（三层紫纱正弦波、满屏半调网点 + 大幅蛇形）自己就在动。
 *
 * 六组 mover 刻意不共用：上浮、斜落带三轴翻转、打旋慢落、横向滑翔、极慢下沉、
 * 竖直缓落。同一个「小东西从上往下飘」重复 12 次就是屏保。
 *
 * 每帧变的量**只在 draw lambda 里读**：这一层是全屏的，组合阶段读一下时钟
 * 就等于每帧重组整层。
 *
 * @param outgoing 换张时的旧专辑索引；不在换张中时与 [incoming] 相同
 * @param incoming 换张后的新专辑索引
 * @param crossfade 0f = 全 outgoing，1f = 全 incoming
 * @param phase 0f..1f 循环相位。**周期请给 11–14 秒**：一个个体一周期跑 2–6 趟，
 *   给短了枫叶就像被吹风机吹。低端机可以恒喂 0f 把整层定住（同 `SwiftieEraCard`）
 * @param lowRam true 时每群个体数减半，保底 2 个
 */
@Composable
fun SwiftieEraParticleLayer(
    outgoing: () -> Int,
    incoming: () -> Int,
    crossfade: () -> Float,
    phase: () -> Float,
    lowRam: Boolean,
    modifier: Modifier = Modifier
) {
    val swarms = remember(lowRam) { buildSwarms(lowRam) }
    // 循环里反复 rewind 的那一个 Path。每次迭代 new 一个的话，一帧就是几十个
    val scratch = remember { Path() }
    // 心形只有这一条轮廓（见 SwiftieHeartPath），单位方框内，调用方自己 scale
    val heart = remember { unitHeartPath() }
    // 单位空间的光晕：调用方 scale 到目标半径，于是整层每帧一个 Brush 都不 new。
    // 颜色写死是因为萤火虫只出现在第 1 张，没有第二个时代要复用它
    val glow = remember {
        Brush.radialGradient(
            colors = listOf(Color(0xFFFFFBD6), Color(0xFFDCE86A), Color(0x00A8C43A)),
            center = Offset.Zero,
            radius = 1f
        )
    }
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val from = outgoing()
                val to = incoming()
                val fade = crossfade().coerceIn(0f, 1f)
                val t = phase()
                if (from == to) {
                    // 不在换张中：只画一层。同一群画两遍会叠出半透明重影
                    drawEraParticles(to, 1f, t, swarms, scratch, heart, glow)
                    return@drawBehind
                }
                if (fade < 1f) drawEraParticles(from, 1f - fade, t, swarms, scratch, heart, glow)
                if (fade > 0f) drawEraParticles(to, fade, t, swarms, scratch, heart, glow)
            }
    )
}

/**
 * 分发到 10 个画法。取色一律走 `STAGE[index].backdropColors`、`ALL[index]` 与
 * `SwiftiePalette`；只有三处必须偏离的写成文件顶部的常量（[FIREFLY_BODY]、
 * [LEAF_BACK]、[NEEDLE_MOSS]）。
 *
 * 每一档 alpha 的差是按**面积**定的：整屏铺开的纸片按金箔那个浓度画就压过曲目名了。
 */
private fun DrawScope.drawEraParticles(
    index: Int,
    layerAlpha: Float,
    phase: Float,
    swarms: Swarms,
    scratch: Path,
    heart: Path,
    glow: Brush
) {
    val stage = SwiftieErasData.STAGE.getOrNull(index) ?: return
    val particle = stage.particle ?: return
    val motes = swarms.motes[particle] ?: return
    val era = SwiftieErasData.ALL[index]
    val main = era.mainColor
    val lit = stage.backdropColors.first()
    val deep = stage.backdropColors.last()
    when (particle) {
        SwiftieEraParticle.FIREFLY ->
            drawFireflies(motes, phase, layerAlpha * 0.92f, glow, FIREFLY_BODY)

        SwiftieEraParticle.GOLD_FLAKE ->
            drawGoldFlakes(motes, phase, layerAlpha * 0.78f, scratch, main, lit, deep)

        SwiftieEraParticle.AUTUMN_LEAF ->
            drawAutumnLeaves(motes, phase, layerAlpha * 0.58f, scratch, main, LEAF_BACK, deep)

        SwiftieEraParticle.SEAGULL ->
            drawSeagulls(motes, phase, layerAlpha * 0.68f, scratch, lit, deep)

        SwiftieEraParticle.HEART_BUTTERFLY -> drawHeartsAndButterflies(
            motes, swarms.butterflies, phase, layerAlpha, scratch, heart, main, deep
        )

        SwiftieEraParticle.PINE_NEEDLE ->
            drawPineNeedles(motes, phase, layerAlpha * 0.62f, scratch, deep, NEEDLE_MOSS)

        SwiftieEraParticle.DRY_LEAF ->
            drawDryLeaves(motes, phase, layerAlpha * 0.56f, scratch, deep, main, lit)

        SwiftieEraParticle.PURPLE_GLITTER ->
            drawPurpleGlitter(motes, phase, layerAlpha * 0.80f, SwiftiePalette.Lavender)

        SwiftieEraParticle.PAPER_SCRAP ->
            drawPaperScraps(motes, phase, layerAlpha * 0.54f, scratch, lit, deep, era.textColor)

        // 橙金 + 金高光：主色 #E8620F 打底，PeachYellow 当羽轴上那一道反光
        SwiftieEraParticle.FEATHER ->
            drawFeathers(motes, phase, layerAlpha * 0.64f, scratch, main, deep, SwiftiePalette.PeachYellow)
    }
}

/**
 * 1 · 萤火虫 —— mover：**上浮 + Lissajous 横向游走**。
 *
 * 虫体小椭圆 + 尾部的 radialGradient 光晕，明暗按各自的 [Mote.beat] 呼吸。
 * 呼吸速率是 phase 的整数倍，相位各自错开：一群萤火虫同亮同暗就是一串圣诞灯。
 */
private fun DrawScope.drawFireflies(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    glow: Brush,
    body: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.14f)
        if (a <= 0.01f) return@forEach
        // 1.08 起飞、-0.12 收尾：进出画面都在框外，看不到接缝
        val cy = (1.08f - t * 1.20f) * size.height
        val cx = (
            0.06f + hash01(mote.seed, round, 1) * 0.88f +
                lissajous(t, mote.freqA, mote.freqB, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val r = mote.size * (0.75f + hash01(mote.seed, round, 3) * 0.5f) * size.minDimension
        val breath = 0.18f + 0.82f * (0.5f + 0.5f * sin((phase * mote.beat + mote.beatOffset) * TAU))
        // 光晕圆心压在虫体下方，才读作「屁股在亮」而不是「整只在发光」
        withTransform({
            translate(cx, cy + r * 1.05f)
            scale(r * 3.4f, r * 3.4f, Offset.Zero)
        }) {
            drawCircle(brush = glow, radius = 1f, center = Offset.Zero, alpha = a * 0.62f * breath)
        }
        drawOval(
            color = body,
            topLeft = Offset(cx - r * 0.42f, cy - r * 0.95f),
            size = Size(r * 0.84f, r * 1.9f),
            alpha = a * 0.85f
        )
    }
}

/**
 * 2 · 金箔 —— mover：**斜落带三轴翻转**（与秋叶 / 枯叶 / 纸片同一组）。
 *
 * 极小的不规则四边形，四个角每一趟重掷 —— 12 张里最小的粒子，形状一样就成了
 * 一堆碎点。`scaleX` 随 [Mote.beat] 过零模拟翻面，翻到侧面时压成一条亮线做镜面高光。
 */
private fun DrawScope.drawGoldFlakes(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    face: Color,
    highlight: Color,
    edge: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.10f)
        if (a <= 0.01f) return@forEach
        val cy = (-0.12f + t * 1.24f) * size.height
        // 整段行程横向也在走，才是「斜落」而不是「直落 + 左右摇摆」
        val cx = (
            -0.10f + hash01(mote.seed, round, 1) * 1.05f + mote.bend * 0.22f * t +
                lissajous(t, mote.freqA, mote.freqB, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val side = mote.size * (0.7f + hash01(mote.seed, round, 3) * 0.6f) * size.minDimension
        val flip = cos((phase * mote.beat + mote.beatOffset) * TAU)
        val spin = (phase * mote.twist + mote.bend) * 360f
        path.rewind()
        path.moveTo(-side * (0.42f + hash01(mote.seed, round, 4) * 0.16f), -side * 0.50f)
        path.lineTo(side * 0.50f, -side * (0.26f + hash01(mote.seed, round, 5) * 0.24f))
        path.lineTo(side * (0.34f + hash01(mote.seed, round, 6) * 0.16f), side * 0.50f)
        path.lineTo(-side * 0.50f, side * (0.20f + hash01(mote.seed, round, 7) * 0.26f))
        path.close()
        withTransform({
            translate(cx, cy)
            rotate(spin, Offset.Zero)
            scale(abs(flip).coerceAtLeast(0.05f), 1f, Offset.Zero)
        }) {
            // 翻到背面换暗档：金箔正反两面亮度差很大，同色就看不出在翻
            drawPath(path = path, color = if (flip >= 0f) face else edge, alpha = a)
        }
        // 翻到侧面压成一条亮线，就是那一下镜面反光。跟着箔片一起转
        if (abs(flip) < 0.30f) {
            withTransform({
                translate(cx, cy)
                rotate(spin, Offset.Zero)
            }) {
                drawLine(
                    highlight,
                    Offset(0f, -side * 0.5f),
                    Offset(0f, side * 0.5f),
                    size.minDimension * 0.0035f,
                    StrokeCap.Round,
                    alpha = a * (1f - abs(flip) / 0.30f)
                )
            }
        }
    }
}

/** 枫叶第 `lobe` 瓣（0..4）的方向，弧度，y 向下。相邻两瓣差 35°。 */
private fun mapleTipAngle(lobe: Int): Float = (-160f + lobe * 35f) * PI.toFloat() / 180f

/** 中间那瓣最长、两侧渐短。五瓣等长就成了海星。 */
private fun mapleTipRadius(half: Float, lobe: Int): Float =
    half * (0.62f + 0.38f * sin(lobe / 4f * PI.toFloat()))

/**
 * 叶柄根在 `(0, half * 0.55f)`，五个尖瓣从这里散开，瓣间四个凹口。
 *
 * 每一瓣的长度**各自**抖一次（不是整片一起缩放）：完全对称的枫叶一看就是画出来的，
 * 而且这样每一趟的轮廓都不一样。
 */
private fun mapleInto(path: Path, half: Float, seed: Int, round: Int) {
    val baseY = half * 0.55f
    path.rewind()
    path.moveTo(0f, baseY)
    for (lobe in 0..4) {
        val tipR = mapleTipRadius(half, lobe) * (0.90f + hash01(seed, round, 20 + lobe) * 0.20f)
        val tipA = mapleTipAngle(lobe)
        path.lineTo(cos(tipA) * tipR, baseY + sin(tipA) * tipR)
        if (lobe == 4) continue
        // 凹口落在两瓣正中间，半径压到 0.34，尖瓣才分得开
        val notchA = (mapleTipAngle(lobe) + mapleTipAngle(lobe + 1)) * 0.5f
        path.lineTo(cos(notchA) * half * 0.34f, baseY + sin(notchA) * half * 0.34f)
    }
    path.close()
}

/**
 * 3 · 秋叶 —— mover：**斜落带三轴翻转**，翻转频率压到金箔的 1/3。
 *
 * 大片枫叶轮廓 + 主叶脉与四条支脉，正反两面异色（正面主色红、背面 [LEAF_BACK] 橙黄），
 * 翻面时换色。叶脉是这片叶子唯一的「结构」，少了它放大看就是个红色多边形。
 */
private fun DrawScope.drawAutumnLeaves(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    front: Color,
    back: Color,
    vein: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.12f)
        if (a <= 0.01f) return@forEach
        val cy = (-0.18f + t * 1.34f) * size.height
        val cx = (
            -0.08f + hash01(mote.seed, round, 1) * 1.02f + mote.bend * 0.26f * t +
                lissajous(t, mote.freqA, mote.freqB, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val half = mote.size * (0.78f + hash01(mote.seed, round, 3) * 0.44f) * size.minDimension
        val flip = cos((phase * mote.beat + mote.beatOffset) * TAU)
        val faceUp = flip >= 0f
        withTransform({
            translate(cx, cy)
            rotate((phase * mote.twist + mote.bend) * 360f, Offset.Zero)
            scale(abs(flip).coerceAtLeast(0.08f), 1f, Offset.Zero)
        }) {
            mapleInto(path, half, mote.seed, round)
            drawPath(path = path, color = if (faceUp) front else back, alpha = a)
            // 叶柄
            drawLine(
                vein,
                Offset(0f, half * 0.55f),
                Offset(-half * 0.04f, half * 0.98f),
                half * 0.055f,
                StrokeCap.Round,
                alpha = a * 0.9f
            )
            for (lobe in 0..4) {
                val tipA = mapleTipAngle(lobe)
                val tipR = mapleTipRadius(half, lobe) * 0.86f
                drawLine(
                    vein,
                    Offset(0f, half * 0.55f),
                    Offset(cos(tipA) * tipR, half * 0.55f + sin(tipA) * tipR),
                    // 正中那条是主脉，比支脉粗一倍
                    if (lobe == 2) half * 0.045f else half * 0.026f,
                    StrokeCap.Round,
                    // 背面看到的是叶脉凸起，比正面淡
                    alpha = a * (if (faceUp) 0.55f else 0.34f)
                )
            }
        }
    }
}

/**
 * 4 · 海鸥 —— mover：**横向滑翔**（与蝴蝶同一组）。
 *
 * 两段弧线组成的翼展剪影，翼展宽度与浓淡随「透视」一起缓慢变化（模拟远近），
 * 一半个体从右往左 —— 天上不会所有鸟一个方向。翼尖那一撮深色是远处海鸥的辨识点。
 */
private fun DrawScope.drawSeagulls(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    plumage: Color,
    wingTip: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.10f)
        if (a <= 0.01f) return@forEach
        val across = if (mote.twist > 0) t else 1f - t
        val cx = (-0.14f + across * 1.28f) * size.width
        // 偶尔轻微升降：两条低频正弦叠出来的，不是规律的上下波
        val cy = (
            0.08f + hash01(mote.seed, round, 1) * 0.34f +
                lissajous(t, mote.freqA * 0.55f, mote.freqB * 0.4f, mote.beatOffset) * 0.045f
            ) * size.height
        // near：0.55 远、1.0 近。翼展和浓度一起变，才读作在往远处飞
        val near = 0.55f + 0.45f * (0.5f + 0.5f * sin((phase * mote.beat + mote.beatOffset) * TAU))
        val span = mote.size * (0.72f + hash01(mote.seed, round, 2) * 0.56f) * near * size.minDimension
        path.rewind()
        path.moveTo(-span, -span * 0.34f)
        path.quadraticTo(-span * 0.44f, span * 0.18f, 0f, 0f)
        path.quadraticTo(span * 0.44f, span * 0.18f, span, -span * 0.34f)
        withTransform({
            translate(cx, cy)
            // 压翼：滑翔的鸟会侧一点身，正对屏幕的剪影是死的
            rotate(mote.bend * 9f + sin(t * TAU * 2f) * 4f, Offset.Zero)
        }) {
            drawPath(
                path = path,
                color = plumage,
                alpha = a * near,
                style = Stroke(width = span * 0.13f, cap = StrokeCap.Round)
            )
            drawCircle(wingTip, span * 0.072f, Offset(-span, -span * 0.34f), alpha = a * near * 0.8f)
            drawCircle(wingTip, span * 0.072f, Offset(span, -span * 0.34f), alpha = a * near * 0.8f)
        }
    }
}

/**
 * 一对翼瓣，左右镜像塞进**同一个** Path 一次画完 —— 分两次画就要多一次
 * `withTransform`，而扑翼时上下两对翼各自还要一次。
 *
 * @param up true 画上面那对（大），false 画下面那对（小）
 */
private fun wingPairInto(path: Path, halfSpan: Float, halfBody: Float, up: Boolean) {
    val s = if (up) -1f else 1f
    path.rewind()
    path.moveTo(0f, s * halfBody * 0.12f)
    path.cubicTo(
        halfSpan * 0.52f, s * halfBody * 1.42f,
        halfSpan * 1.10f, s * halfBody * 0.34f,
        halfSpan * 0.24f, s * halfBody * 0.02f
    )
    path.close()
    path.moveTo(0f, s * halfBody * 0.12f)
    path.cubicTo(
        -halfSpan * 0.52f, s * halfBody * 1.42f,
        -halfSpan * 1.10f, s * halfBody * 0.34f,
        -halfSpan * 0.24f, s * halfBody * 0.02f
    )
    path.close()
}

/**
 * 5 · 上浮的亮粉心 + 横穿的蝴蝶 —— **两个 mover 混在一张里**。
 *
 * 心走上浮组（升到顶淡出），蝴蝶走横向滑翔组。蝴蝶是两对翼瓣（上翼大、下翼小）
 * + 细身体 + 触角，扑翼靠上下翼的 `scaleX` **反相**收放：一对张开时另一对正收，
 * 才是「扑」而不是整体缩放。
 */
private fun DrawScope.drawHeartsAndButterflies(
    hearts: List<Mote>,
    butterflies: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    heart: Path,
    pink: Color,
    sky: Color
) {
    hearts.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        // 上浮到顶一路淡出，不是在上缘硬切
        val a = alpha * 0.66f * (1f - t) * edgeFade(t, 0.08f)
        if (a <= 0.01f) return@forEach
        val cy = (1.06f - t * 1.22f) * size.height
        val cx = (
            0.05f + hash01(mote.seed, round, 1) * 0.90f +
                lissajous(t, mote.freqA, mote.freqB, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val side = mote.size * (0.72f + hash01(mote.seed, round, 3) * 0.56f) * size.minDimension
        withTransform({
            translate(cx - side * 0.5f, cy - side * 0.5f)
            // unitHeartPath 是 0..1 单位方框，按边长展开
            scale(side, side, Offset.Zero)
        }) {
            drawPath(path = heart, color = SwiftiePalette.Glitter, alpha = a)
        }
    }
    drawButterflies(butterflies, phase, alpha * 0.62f, path, pink, sky)
}

/** 蝴蝶本体。拆出来只是因为它和心共用不了任何一行 mover。 */
private fun DrawScope.drawButterflies(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    pink: Color,
    sky: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.10f)
        if (a <= 0.01f) return@forEach
        val across = if (mote.twist > 0) t else 1f - t
        val cx = (-0.12f + across * 1.24f) * size.width
        // 蝴蝶不走直线，横穿时上下起伏比海鸥大得多
        val cy = (
            0.14f + hash01(mote.seed, round, 1) * 0.56f +
                lissajous(t, mote.freqA * 1.6f, mote.freqB, mote.beatOffset) * 0.09f
            ) * size.height
        val span = mote.size * (0.76f + hash01(mote.seed, round, 2) * 0.48f) * size.minDimension
        val body = span * 0.92f
        val flap = (phase * mote.beat + mote.beatOffset) * TAU
        // 翅面在粉与天蓝之间各取一档，四只蝴蝶不同色
        val wing = lerp(pink, sky, hash01(mote.seed, round, 3))
        withTransform({
            translate(cx, cy)
            rotate(mote.bend * 16f + sin(flap) * 5f, Offset.Zero)
        }) {
            // 上翼大、下翼小；两者 scaleX 反相（一个用 sin 一个用 cos）
            val upper = 0.30f + 0.70f * abs(sin(flap))
            val lower = 0.30f + 0.70f * abs(cos(flap))
            wingPairInto(path, span, body, up = true)
            withTransform({ scale(upper, 1f, Offset.Zero) }) {
                drawPath(path = path, color = wing, alpha = a)
            }
            wingPairInto(path, span * 0.62f, body * 0.66f, up = false)
            withTransform({ scale(lower, 1f, Offset.Zero) }) {
                drawPath(path = path, color = wing, alpha = a * 0.86f)
            }
            // 细身体 + 两根触角。少了这两笔，两对翼瓣读作一朵花。
            // 用 GlitterDeep 而不是背景那两档 pastel —— Lover 的三档都是浅色，
            // 身体拿其中任一档画都会糊进翅面里
            drawLine(
                SwiftiePalette.GlitterDeep, Offset(0f, -body * 0.52f), Offset(0f, body * 0.72f),
                span * 0.10f, StrokeCap.Round, alpha = a * 0.9f
            )
            drawLine(
                SwiftiePalette.GlitterDeep, Offset(0f, -body * 0.52f), Offset(-span * 0.26f, -body * 0.98f),
                span * 0.035f, StrokeCap.Round, alpha = a * 0.7f
            )
            drawLine(
                SwiftiePalette.GlitterDeep, Offset(0f, -body * 0.52f), Offset(span * 0.26f, -body * 0.98f),
                span * 0.035f, StrokeCap.Round, alpha = a * 0.7f
            )
        }
    }
}

/**
 * 6 · 松针 —— mover：**竖直缓落**，独占一组。
 *
 * 一根两端收尖的细纺锤 + 中脊高光。摆幅压到 0.012 量级（别的粒子是 0.06–0.11）：
 * 松针是有重量的直落物，横着飘就成了羽毛。
 */
private fun DrawScope.drawPineNeedles(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    dark: Color,
    moss: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.12f)
        if (a <= 0.01f) return@forEach
        val cy = (-0.14f + t * 1.28f) * size.height
        val cx = (
            0.04f + hash01(mote.seed, round, 1) * 0.92f +
                lissajous(t, mote.freqA, mote.freqB, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val len = mote.size * (0.78f + hash01(mote.seed, round, 3) * 0.5f) * size.minDimension
        val halfW = len * 0.045f
        // 每一趟换一档苔色，六根针不是同一根复制六遍
        val tint = lerp(dark, moss, hash01(mote.seed, round, 4))
        path.rewind()
        path.moveTo(0f, -len * 0.5f)
        path.quadraticTo(halfW, 0f, 0f, len * 0.5f)
        path.quadraticTo(-halfW, 0f, 0f, -len * 0.5f)
        path.close()
        withTransform({
            translate(cx, cy)
            // 静态倾角 + 极缓的摇摆：真的针叶落下来是斜着的，且慢慢晃。
            // 摇摆写成 sin 而不是累加角度 —— 累加的角度在 phase 绕回 0f 时会跳一下
            rotate(mote.bend * 34f + sin(phase * mote.twist * TAU) * 22f, Offset.Zero)
        }) {
            drawPath(path = path, color = tint, alpha = a)
            // 中脊：这根针唯一的结构，少了它就是一条细黑杠
            drawLine(
                moss, Offset(0f, -len * 0.34f), Offset(0f, len * 0.34f),
                halfW * 0.5f, StrokeCap.Round, alpha = a * 0.55f
            )
        }
    }
}

/**
 * 卷边枯叶轮廓。右缘舒展，**左缘的控制点回勾进叶片内部** —— 那就是卷边。
 *
 * @param curl 0f..1f，卷的深浅，每一趟重掷
 */
private fun dryLeafInto(path: Path, half: Float, curl: Float) {
    path.rewind()
    path.moveTo(0f, -half)
    path.cubicTo(half * 0.80f, -half * 0.50f, half * 0.86f, half * 0.42f, half * 0.10f, half)
    path.cubicTo(
        -half * (0.16f + curl * 0.30f), half * 0.52f,
        -half * (0.70f - curl * 0.34f), half * 0.30f,
        -half * 0.52f, -half * 0.06f
    )
    path.cubicTo(-half * 0.44f, -half * 0.46f, -half * 0.22f, -half * 0.78f, 0f, -half)
    path.close()
}

/**
 * 7 · 枯叶 —— mover：**斜落带三轴翻转**，但 [Mote.beat] 恒为 1，比枫叶慢一半，
 * `rounds` 也钉在 2（枫叶是 2..3），落得更沉。
 *
 * 深棕叶身 + 卷边高光 + 一条弓形主脉。三笔缺一笔就读成一块棕色色块。
 */
private fun DrawScope.drawDryLeaves(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    body: Color,
    curlLit: Color,
    vein: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.12f)
        if (a <= 0.01f) return@forEach
        // 后半程加速一点，枯叶是「沉」下去而不是匀速飘
        val fall = t * t * 0.35f + t * 0.65f
        val cy = (-0.16f + fall * 1.32f) * size.height
        val cx = (
            -0.06f + hash01(mote.seed, round, 1) * 1.0f + mote.bend * 0.18f * t +
                lissajous(t, mote.freqA * 0.8f, mote.freqB * 0.7f, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val half = mote.size * (0.80f + hash01(mote.seed, round, 3) * 0.44f) * size.minDimension
        val flip = cos((phase * mote.beat + mote.beatOffset) * TAU)
        val curl = 0.25f + hash01(mote.seed, round, 4) * 0.7f
        withTransform({
            translate(cx, cy)
            // 不整圈转，只在 ±150° 内来回翻 —— 枯叶比枫叶重，转不起来。
            // 写成 sin 而不是累加角度，phase 绕回 0f 时角度不跳
            rotate(mote.bend * 40f + sin(phase * mote.twist * TAU) * 150f, Offset.Zero)
            scale(abs(flip).coerceAtLeast(0.10f), 1f, Offset.Zero)
        }) {
            dryLeafInto(path, half, curl)
            drawPath(path = path, color = body, alpha = a)
            // 卷起来的那一侧翻上来一条窄面，受光比叶背亮
            path.rewind()
            path.moveTo(half * 0.06f, half * 0.86f)
            path.cubicTo(
                -half * 0.18f, half * 0.44f,
                -half * (0.50f - curl * 0.16f), half * 0.16f,
                -half * 0.42f, -half * 0.10f
            )
            drawPath(
                path = path,
                color = curlLit,
                alpha = a * 0.85f,
                style = Stroke(width = half * 0.11f, cap = StrokeCap.Round)
            )
            path.rewind()
            path.moveTo(0f, -half * 0.86f)
            path.quadraticTo(half * 0.18f, 0f, half * 0.08f, half * 0.84f)
            drawPath(
                path = path,
                color = vein,
                alpha = a * 0.42f,
                style = Stroke(width = half * 0.05f, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 8 · 紫色闪粉 —— mover：**极慢下沉**，独占一组（`rounds` 钉在 2，全场最慢档）。
 *
 * 小四芒星片：两条主轴 + 两条短斜轴 + 一点亮核，整片缓慢自转 —— 全部轴对齐屏幕
 * 就成了一片十字。闪烁按各自的 [Mote.beat]，不同步。
 *
 * 刻意**不是圆点**：无形状的光尘正是廉价感的来源。
 */
private fun DrawScope.drawPurpleGlitter(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    color: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val twinkle = 0.30f + 0.70f * (0.5f + 0.5f * sin((phase * mote.beat + mote.beatOffset) * TAU))
        val a = alpha * edgeFade(t, 0.14f) * twinkle
        if (a <= 0.01f) return@forEach
        val cy = (-0.08f + t * 1.18f) * size.height
        val cx = (
            0.04f + hash01(mote.seed, round, 1) * 0.92f +
                lissajous(t, mote.freqA, mote.freqB, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val arm = mote.size * (0.72f + hash01(mote.seed, round, 3) * 0.56f) * size.minDimension
        val stroke = arm * 0.14f
        withTransform({
            translate(cx, cy)
            rotate((phase * mote.twist + mote.bend) * 360f, Offset.Zero)
        }) {
            drawLine(color, Offset(-arm, 0f), Offset(arm, 0f), stroke, StrokeCap.Round, alpha = a)
            drawLine(color, Offset(0f, -arm), Offset(0f, arm), stroke, StrokeCap.Round, alpha = a)
            // 短斜轴只有主轴的 0.42 长：四芒星的「芒」要分主次，等长就是八角雪花
            val diag = arm * 0.42f
            drawLine(
                color, Offset(-diag, -diag), Offset(diag, diag),
                stroke * 0.68f, StrokeCap.Round, alpha = a * 0.7f
            )
            drawLine(
                color, Offset(-diag, diag), Offset(diag, -diag),
                stroke * 0.68f, StrokeCap.Round, alpha = a * 0.7f
            )
            drawCircle(Color.White, stroke * 0.85f, Offset.Zero, alpha = a * 0.8f)
        }
    }
}

/**
 * 纸片轮廓：上下缘**同向**拱起 [bow]，纸厚才一致。平的矩形读作一张色卡。
 */
private fun paperInto(path: Path, halfW: Float, halfH: Float, bow: Float) {
    path.rewind()
    path.moveTo(-halfW, -halfH)
    path.quadraticTo(0f, -halfH - bow, halfW, -halfH)
    path.lineTo(halfW, halfH)
    path.quadraticTo(0f, halfH - bow, -halfW, halfH)
    path.close()
}

/**
 * 9 · 纸片 —— mover：**斜落带三轴翻转**。
 *
 * 大而薄、带弧度的纸片。纸面留 2–3 条长短不一的短横线当字迹，画在同一个变换里，
 * 翻面时和纸一起压扁；翻到侧面再补一道薄边，纸才有厚度。
 */
private fun DrawScope.drawPaperScraps(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    face: Color,
    edge: Color,
    ink: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.12f)
        if (a <= 0.01f) return@forEach
        val cy = (-0.20f + t * 1.38f) * size.height
        val cx = (
            -0.08f + hash01(mote.seed, round, 1) * 1.05f + mote.bend * 0.24f * t +
                lissajous(t, mote.freqA, mote.freqB, hash01(mote.seed, round, 2)) * mote.amp
            ) * size.width
        val halfH = mote.size * (0.80f + hash01(mote.seed, round, 3) * 0.44f) * size.minDimension
        val halfW = halfH * (0.62f + hash01(mote.seed, round, 4) * 0.26f)
        val flip = cos((phase * mote.beat + mote.beatOffset) * TAU)
        val squash = abs(flip)
        val spin = (phase * mote.twist + mote.bend) * 360f
        withTransform({
            translate(cx, cy)
            rotate(spin, Offset.Zero)
            scale(squash.coerceAtLeast(0.02f), 1f, Offset.Zero)
        }) {
            paperInto(path, halfW, halfH, halfH * 0.26f)
            drawPath(path = path, color = face, alpha = a)
            // 描一道边。TTPD 三档底色（#FBF8F2 / #F5F1EA / #B8B0A2）几乎同色，
            // 只填米白的纸片在上半屏根本看不出轮廓
            drawPath(
                path = path,
                color = edge,
                alpha = a * 0.9f,
                style = Stroke(width = halfH * 0.030f)
            )
            // 2 或 3 条字迹，长短各自随机：等长的三条读作三根横线而不是一段字
            val lines = 2 + (hash01(mote.seed, round, 5) * 1.99f).toInt()
            for (line in 0 until lines) {
                val ratio = 0.34f + hash01(mote.seed, round, 6 + line) * 0.52f
                val y = halfH * (-0.42f + line * 0.36f)
                drawLine(
                    ink,
                    Offset(-halfW * 0.62f, y),
                    Offset(-halfW * 0.62f + halfW * 1.24f * ratio, y),
                    halfH * 0.045f,
                    StrokeCap.Round,
                    alpha = a * 0.55f
                )
            }
        }
        // 翻到侧面：纸压成一条薄边，这一下才交代出「纸是有厚度的一张」。
        // 跟着纸一起转，不能画成屏幕对齐的竖条
        if (squash < 0.16f) {
            withTransform({
                translate(cx, cy)
                rotate(spin, Offset.Zero)
            }) {
                drawRect(
                    color = edge,
                    topLeft = Offset(-halfH * 0.022f, -halfH),
                    size = Size(halfH * 0.044f, halfH * 2f),
                    alpha = a * (1f - squash / 0.16f) * 0.9f
                )
            }
        }
    }
}

/**
 * 一排斜向羽枝，两侧一次塞进同一个 Path。长度**中段最长、两端渐短**。
 *
 * 羽枝一律朝根部方向斜（`+y`），这是羽毛和「一片叶子」在剪影上唯一的区别。
 */
private fun barbsInto(path: Path, len: Float, count: Int, sweep: Float) {
    path.rewind()
    for (index in 0 until count) {
        val u = index / (count - 1f)
        val y = -len * 0.46f + u * len * 0.86f
        val barb = len * 0.30f * (0.25f + 0.75f * sin(u * PI.toFloat()))
        path.moveTo(0f, y)
        path.quadraticTo(barb * 0.55f, y + barb * sweep * 0.35f, barb, y + barb * sweep)
        path.moveTo(0f, y)
        path.quadraticTo(-barb * 0.55f, y + barb * sweep * 0.35f, -barb, y + barb * sweep)
    }
}

/**
 * 10 · 羽毛 —— mover：**打旋慢落**，独占一组。
 *
 * 摆动是**三角波**不是正弦：羽毛落下来是一顿一顿的，摆到端点顿一下再换向，
 * 正弦太顺。羽毛整体倒向自己的摆动方向＝打旋；`scaleX` 过零＝绕羽轴缓慢自转。
 *
 * 中央羽轴（根粗尖细的锥体）+ 两侧斜向羽枝 + 根部几缕绒毛 + 羽轴上一道金高光，
 * 四笔齐了才认得出是羽毛而不是一根橙色羽毛掸子。
 */
private fun DrawScope.drawFeathers(
    motes: List<Mote>,
    phase: Float,
    alpha: Float,
    path: Path,
    barb: Color,
    shaft: Color,
    sheen: Color
) {
    motes.forEach { mote ->
        val travel = travelOf(mote, phase)
        val round = roundOf(mote, travel)
        val t = travel - floor(travel)
        val a = alpha * edgeFade(t, 0.14f)
        if (a <= 0.01f) return@forEach
        val cy = (-0.20f + t * 1.40f) * size.height
        // 摆动的频率与相位都随这一趟重掷 —— 同一根羽毛不会沿同一条锯齿线落两趟。
        // 这里喂的是 t（本趟进度）而不是 phase：t 在周期接缝上连续，频率就能取任意值
        val sway = triangle(t * (2.2f + hash01(mote.seed, round, 2) * 1.7f) + mote.beatOffset)
        val cx = (0.06f + hash01(mote.seed, round, 1) * 0.88f + sway * mote.amp) * size.width
        val len = mote.size * (0.80f + hash01(mote.seed, round, 3) * 0.44f) * size.minDimension
        val axis = cos((phase * mote.beat + mote.beatOffset) * TAU)
        withTransform({
            translate(cx, cy)
            rotate(sway * 34f + mote.bend * 14f, Offset.Zero)
            scale((0.30f + 0.70f * abs(axis)), 1f, Offset.Zero)
        }) {
            barbsInto(path, len, count = 13, sweep = 0.52f)
            drawPath(
                path = path,
                color = barb,
                alpha = a * 0.92f,
                style = Stroke(width = len * 0.017f, cap = StrokeCap.Round)
            )
            // 根部三缕绒毛：更散、更细、更淡，羽毛的根部不是齐口切断的
            path.rewind()
            for (k in 0..2) {
                val dir = k - 1f
                val fy = len * (0.30f + k * 0.05f)
                path.moveTo(0f, fy)
                path.quadraticTo(dir * len * 0.13f, fy + len * 0.06f, dir * len * 0.21f, fy + len * 0.17f)
            }
            drawPath(
                path = path,
                color = barb,
                alpha = a * 0.42f,
                style = Stroke(width = len * 0.010f, cap = StrokeCap.Round)
            )
            // 羽轴：根粗尖细的窄锥，压在羽枝上面
            path.rewind()
            path.moveTo(-len * 0.017f, len * 0.46f)
            path.lineTo(0f, -len * 0.50f)
            path.lineTo(len * 0.017f, len * 0.46f)
            path.close()
            drawPath(path = path, color = shaft, alpha = a)
            drawLine(
                sheen, Offset(0f, -len * 0.44f), Offset(0f, -len * 0.02f),
                len * 0.013f, StrokeCap.Round, alpha = a * 0.75f
            )
        }
    }
}
