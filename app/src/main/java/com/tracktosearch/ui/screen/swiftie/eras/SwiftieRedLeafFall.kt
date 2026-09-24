package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

/**
 * Red 那一张的**枫叶剧本**：谁长在枝上、什么时候松手、松手之后怎么落。
 *
 * ## 为什么单独一个文件、还要被两个图层共读
 *
 * 枝上的叶属于页面背景（L1，卡片之下），飘落的叶必须压在卡片之上（L3）。
 * 一片叶从枝上脱手的瞬间正好跨过这两层的边界，所以**落点、尺寸、角度、配色
 * 必须由同一张表算出来**：L1 画还没松手的，L3 接手已经松手的，交接那一帧
 * 两边逐像素相同才看不出「换了个图层」。分头维护的话，这一帧必然跳一下。
 *
 * 这与曲目逐行时刻那本账同一个道理（`SwiftieEraTracklist.trackRowRevealAtMs`
 * 由曲目表和触感谱共用）—— 一个量只在一处定义。
 *
 * ## 时间从哪儿来
 *
 * 只读 `eraElapsedMs`（Red 段内已过的毫秒，负数 = 本段还没开始）。
 * `backdropPhase` 那条 3.6s 循环锯齿问不出「第几拍」，用它落叶会反复脱落重来。
 *
 * 刻意**不给 Red 加前摇**：换张淡入那 500ms 里本段时钟是 -1，枝上五片全在、
 * 屏幕上还没有卡片，「一开始都长在树枝上」这一格由淡变自己让出来，
 * 账本一毫秒不动（`SwiftieTimelineTest` 守着的那两个配乐钉点不受影响）。
 */

/**
 * 真实枫叶的秋色：一档叶身色配一档同色系深墨线。
 *
 * Red 的三档背景色（`#FBE3E3 / #EB3440 / #981A28`）里只有一个红，而深秋枝头
 * 一次落下的叶从来不止一种颜色：同一棵树上有猩红、砖红、橘、琥珀，还有已经发褐的。
 * 五片全用 `mid` 画出来就是五张同一张贴纸。
 *
 * 但**一档绿、一档柠檬黄都没有**。这两色一进来这张就不读作 Red 了。而中档
 * #EB3440 必须留在表里 —— 最大那片用它，时代识别色不能从主体上消失。
 */
internal class MapleHue(
    val fill: Color,
    val ink: Color,
    /** 背面。真叶翻过来偏黄偏钝，正反同色就看不出在翻面 */
    val back: Color
)

internal val MapleHues = arrayOf(
    MapleHue(Color(0xFFEB3440), Color(0xFF8E1420), Color(0xFFE08A3C)), // 猩红（Red 本尊）
    MapleHue(Color(0xFFC21F2C), Color(0xFF6E0F1A), Color(0xFFD9762A)), // 砖红
    MapleHue(Color(0xFFD8541F), Color(0xFF7A2A08), Color(0xFFE0A85A)), // 橘
    MapleHue(Color(0xFFE8912A), Color(0xFF7C4A11), Color(0xFFD9A526)), // 琥珀
    MapleHue(Color(0xFFD9A526), Color(0xFF6E5010), Color(0xFFC9701F)), // 金琥珀
    MapleHue(Color(0xFF8E2028), Color(0xFF47101A), Color(0xFFB8621E)), // 暗紫红
    MapleHue(Color(0xFFB8621E), Color(0xFF5C2E08), Color(0xFFD9A526)), // 焦橙
    MapleHue(Color(0xFF96562A), Color(0xFF4A2812), Color(0xFFB8863F))  // 栗褐
)

/** 一片叶松手之后的落法。 */
internal class RedLeafFall(
    /** 相对 Red 段起点多少毫秒松手 */
    val releaseAtMs: Long,
    /** 从松手到落出画面下缘的时长 */
    val fallMs: Long,
    /** 横向摆动的两组频率。刻意不成整数比，单条 sin 几个个体一起看就是同一条摆线 */
    val freqA: Float,
    val freqB: Float,
    /** 摆幅（占屏宽） */
    val amp: Float,
    /** 净风偏（占屏宽，正数往右） */
    val drift: Float,
    /** 整趟下落在画面内自转几圈，带符号 */
    val twist: Float,
    /** 整趟下落翻几个面 */
    val tumble: Float,
    /** 点头幅度（度）与频率 */
    val nodDeg: Float,
    val nodFreq: Float
)

/** 枝上的一片叶：静态姿态 + 配色，`fall` 为 null 就一直长着。 */
internal class RedBranchLeaf(
    /** 叶柄末端锚点（占屏宽 / 占屏高） */
    val fx: Float,
    val fy: Float,
    /** 叶身半高（占 `minDimension`） */
    val half: Float,
    /** 静态旋转（度） */
    val rot: Float,
    val hue: MapleHue,
    val fall: RedLeafFall?
) {
    /** 这一帧归页面背景（L1）画。待机态一律算「还在枝上」，满枝那一格靠这个。 */
    fun attachedAt(eraMs: Long): Boolean =
        eraMs < 0L || fall == null || eraMs < fall.releaseAtMs

    /**
     * 这一帧归落叶层（L3）画。就是 [attachedAt] 的反面 —— 两层因此不可能同时画
     * 同一片叶（叠出一个深一档的色斑），也不可能同时漏掉它（凭空少一帧）。
     *
     * 「归谁画」不等于「一定画得着」—— 落出画面下缘之后 [fallenOffAt] 为真，
     * L3 自己会剔除，那一刻它哪一层都不在（它在屏幕底下）。
     */
    fun releasedAt(eraMs: Long): Boolean = !attachedAt(eraMs)

    /** 整趟下落走完、这一帧不必再画。`fall == null` 的叶永远不落。 */
    fun fallenOffAt(eraMs: Long): Boolean {
        val fall = this.fall ?: return false
        return eraMs - fall.releaseAtMs >= fall.fallMs
    }
}

/**
 * 枝上那五片。
 *
 * 位置口径是换图前一档一档对过的：旋转都落在 180° 上下（150–214）—— 位图那片叶是
 * 尖瓣朝上、叶柄在下的，转过来叶柄才朝着枝；五片各差二三十度，同一角度摆五片是贴图。
 * 尺寸拉开到 0.062–0.150（2.4 倍差），这是唯一能在一个平面上做出景深的手段。
 * 最大那片钉在 (0.30w, 0.105h)，正是枯枝下垂段（t≈0.41 处约 0.125h）的正下方，
 * 读起来就是从那儿挂下来的。
 *
 * **留在枝上的那几片，叶身下缘必须落在卡片顶边以上。** 锚点钉的是叶柄末端，而旋转
 * 都在 188° 上下 —— 转过来之后叶身从锚点**朝下**长，所以真正要守的不是锚点而是
 * 「锚点 + 叶身下探」。下探量按位图实测（`swiftie_maple_bent_solid` 的不透明包围盒）
 * 依次是 0.086 / 0.054 / 0.056 / 0.036 / 0.030 倍屏高，所以最靠下的锚点只能到 0.17h。
 * 补齐 Taylor's Version 独有曲目后 Red 是 30 首、卡片顶边从 16 首时的 0.455h 抬到
 * 0.20h，原来挂在 0.272h / 0.318h 的两片被整块吞掉（2026-09-20 真机截图确认过），
 * 这一组纵向值因此收进 0.10–0.19h 那一条带。**松手之后不受这条约束**，它本来就要落过卡片。
 *
 * 松手的是中间、左端和枝下那三片，时刻差 650ms 以上（不会看着像同时剪断），
 * 留下最大那片和梢头那片：脱落的不全在两端、锚留着，读起来是「这棵树还在落叶子」
 * 而不是「叶子掉光了」。
 */
internal val RedBranchLeaves = listOf(
    RedBranchLeaf(0.300f, 0.105f, 0.150f, 188f, MapleHues[0], fall = null),
    RedBranchLeaf(
        0.630f, 0.100f, 0.105f, 205f, MapleHues[1],
        RedLeafFall(
            releaseAtMs = 950L, fallMs = 3900L,
            freqA = 0.9f, freqB = 2.4f, amp = 0.115f, drift = -0.10f,
            twist = -0.85f, tumble = 3f, nodDeg = 17f, nodFreq = 2f
        )
    ),
    RedBranchLeaf(0.815f, 0.125f, 0.098f, 168f, MapleHues[5], fall = null),
    RedBranchLeaf(
        0.125f, 0.105f, 0.070f, 150f, MapleHues[3],
        RedLeafFall(
            releaseAtMs = 260L, fallMs = 3450L,
            freqA = 1.2f, freqB = 3.1f, amp = 0.095f, drift = 0.16f,
            twist = 1.15f, tumble = 4f, nodDeg = 22f, nodFreq = 3f
        )
    ),
    RedBranchLeaf(
        0.485f, 0.150f, 0.062f, 214f, MapleHues[6],
        RedLeafFall(
            releaseAtMs = 1700L, fallMs = 4400L,
            freqA = 0.7f, freqB = 1.9f, amp = 0.135f, drift = 0.06f,
            twist = -1.35f, tumble = 2f, nodDeg = 13f, nodFreq = 1.5f
        )
    )
)

/** 从画面外飘进来的那些。与枝上那三片分开：它们没有「长在枝上」的前史，从头循环。 */
internal class RedDrifter(
    val seed: Int,
    /** 相对 Red 段起点多少毫秒开始进场 */
    val enterAtMs: Long,
    /** 一趟（画外到画外）的时长 */
    val spanMs: Long,
    val hueIndex: Int,
    val half: Float,
    val amp: Float,
    val freqA: Float,
    val freqB: Float,
    val drift: Float,
    val twist: Float,
    val tumble: Float,
    val nodDeg: Float,
    val nodFreq: Float
)

/** 固定种子（发行年份），重组不跳位 —— 与 `buildSwarm` 同一套约定。 */
private fun buildRedDrifters(): List<RedDrifter> {
    val random = Random(2012)
    return List(6) { index ->
        RedDrifter(
            seed = 2012 * 31 + index * 7,
            // 错开进场：段首不会一帧之内凭空满屏叶
            enterAtMs = index * 430L,
            spanMs = 4600L + random.nextInt(2400),
            hueIndex = index,
            half = 0.048f + random.nextFloat() * 0.030f,
            amp = 0.075f + random.nextFloat() * 0.075f,
            freqA = 0.7f + random.nextFloat() * 0.6f,
            freqB = 2.1f + random.nextFloat() * 1.5f,
            drift = (random.nextFloat() * 2f - 1f) * 0.20f,
            twist = (0.5f + random.nextFloat() * 0.9f) * (if (random.nextBoolean()) 1f else -1f),
            tumble = 2f + random.nextInt(3).toFloat(),
            nodDeg = 10f + random.nextFloat() * 16f,
            nodFreq = 1.5f + random.nextFloat() * 1.5f
        )
    }
}

private val RedDrifters = buildRedDrifters()

/** 出画下缘：叶顶边也已经沉到屏幕以外，不需要淡出。 */
private const val FALL_BOTTOM = 1.16f

/** 画外上缘起点。飘进来的叶最大约 0.04h 高，这一档整片都在上缘以外。 */
private const val FALL_TOP = -0.14f

/**
 * 叶身与墨线的不透明度，沿用枝上那五片的 0.92 / 0.45。
 *
 * 这一层现在压在卡片**之上**，半透明会稀释成几团粉影，读不出「有东西挡在字前面」。
 * 墨线仍只给 0.45 —— 叶脉是压出来的暗痕，画实了像铁丝。
 */
private const val FILL_ALPHA = 0.92f
private const val INK_ALPHA = 0.45f

/**
 * 下落的纵向进度。真枫叶不是匀速下沉。
 *
 * 两件事：① 松手那一小段几乎不动，先加速到终端速度；② 之后每翻一次面就兜一下风
 * 慢下来，翻平了又滑下去。所以 smoothstep 只混进 0.35（全混读作「缓动动画」），
 * 再叠一条周期顿挫。
 *
 * 两项在 u=0 处都取 0，所以脱手那一帧的落点与枝上的静态落点严格重合；顿挫取
 * 2.5 个整周期，u=1 处 `sin` 也回到 0，出画时不会自己截断。
 */
private fun fallProgress(u: Float): Float {
    val easeIn = u * u * (3f - 2f * u)
    val glide = sin(u * TAU * 2.5f) * 0.040f * u
    return (u * 0.65f + easeIn * 0.35f + glide).coerceIn(0f, 1f)
}

/**
 * 横向摆动的入场包络：前 25% 行程从 0 长到满幅。
 *
 * 刚松手的叶没有横向速度，风要等它落起来才兜得住 —— 而且 [lissajous] 在 t=0 处
 * 带着相位偏移**并不为 0**，直接乘上去会让脱手那一帧的 x 凭空偏出一截，
 * 交接那一帧就是肉眼可见的一跳。这一档乘数保证了 u=0 时整项严格为 0。
 */
private fun swayRamp(u: Float): Float = (u * 4f).coerceAtMost(1f)

/**
 * 把一片叶按给定的姿态画出来：平移到锚点、转 [rot]、按 [scaleX] 翻面，再实心 + 墨线两笔。
 *
 * 变换序不能反：先旋转再压扁，反了翻面轴会跟着叶的倾角一起歪。缩放走 canvas 变换
 * 而不是 `dstSize` —— 那条路只吃整数，一直在漂的叶会一格一格跳。
 *
 * 枝上那五片不要直接调它，走 [drawRedBranchLeaf]（两层共用的那一个入口）。
 */
internal fun DrawScope.drawMapleAtPose(
    maple: MapleArt,
    fx: Float,
    fy: Float,
    half: Float,
    rot: Float,
    scaleX: Float,
    hue: MapleHue,
    alpha: Float,
    faceUp: Boolean
) {
    val a = alpha.coerceIn(0f, 1f)
    if (a <= 0.004f) return
    val r = half * size.minDimension
    withTransform({
        translate(fx * size.width, fy * size.height)
        rotate(rot, Offset.Zero)
        scale(scaleX, 1f, Offset.Zero)
    }) {
        val height = MAPLE_SPAN * r
        val stemEnd = Offset(0f, MAPLE_STEM_END_R * r)
        drawMapleSolid(
            maple.solid, height, stemEnd, if (faceUp) hue.fill else hue.back,
            a * FILL_ALPHA, maple.stemEndFrac
        )
        drawMapleInk(maple.ink, height, stemEnd, hue.ink, a * INK_ALPHA, maple.stemEndFrac)
    }
}

/**
 * 枝上那五片之一这一帧怎么画 —— **两层共用这一个函数**。
 *
 * 还没松手的走静态分支，已经松手的走飘落分支，两个分支出的是一组同名局部量，
 * 而静态分支就是飘落分支在 `u = 0` 处的取值。写成两条路径的话，交接那一帧哪怕
 * 只差一个相位常数，肉眼也是一跳，而代码里两处各自都看着没错。
 *
 * 局部量而不是返回值：这一层每帧要过九片叶，按本仓的规矩 draw lambda 里一个对象都不 new。
 */
internal fun DrawScope.drawRedBranchLeaf(
    maple: MapleArt,
    leaf: RedBranchLeaf,
    eraMs: Long,
    alpha: Float
) {
    val fall = leaf.fall
    val fx: Float
    val fy: Float
    val rot: Float
    val scaleX: Float
    val faceUp: Boolean
    if (fall == null || eraMs < fall.releaseAtMs) {
        fx = leaf.fx
        fy = leaf.fy
        rot = leaf.rot
        scaleX = 1f
        faceUp = true
    } else {
        val u = (eraMs - fall.releaseAtMs).toFloat() / fall.fallMs
        val flip = cos(u * TAU * fall.tumble)
        val fold = abs(flip).coerceAtLeast(0.10f)
        fx = leaf.fx +
            lissajous(u, fall.freqA, fall.freqB, leaf.rot / 360f) * fall.amp * swayRamp(u) +
            fall.drift * u * u
        fy = leaf.fy + (FALL_BOTTOM - leaf.fy) * fallProgress(u)
        rot = leaf.rot + u * fall.twist * 360f + sin(u * TAU * fall.nodFreq) * fall.nodDeg
        // 负号把叶镜像过去，配上背面那档色才读得出「翻了个面」；只压扁不镜像
        // 就只是鼓回去一下，正反同色更是完全看不出在翻
        scaleX = if (flip >= 0f) fold else -fold
        faceUp = flip >= 0f
    }
    drawMapleAtPose(maple, fx, fy, leaf.half, rot, scaleX, leaf.hue, alpha, faceUp)
}

/**
 * 枝上**还留着**的那几片。已经松手的这一帧整片交给 L3 —— 同一片叶两层叠画会深一档，
 * 交接处就是一个明显的色斑。
 *
 * [eraMs] 为负（换张淡入中）时五片全画：那是「满枝、还没有卡片」的那一格。
 */
internal fun DrawScope.drawRedAttachedMaples(maple: MapleArt, eraMs: Long, alpha: Float) {
    RedBranchLeaves.forEach { leaf ->
        if (leaf.attachedAt(eraMs)) drawRedBranchLeaf(maple, leaf, eraMs, alpha)
    }
}

/**
 * Red 的落叶层 —— **挂在卡片之上**。
 *
 * 与 L2 那层飘落物的区别不只是 z 序：L2 按 30s 行程无限循环，这一层的枝上那三片
 * 是**一次性**的（跟着 `eraElapsedMs` 走，落完就不再出现），因为「从这根枝上脱落」
 * 这件事只能演一遍。飘进来的那几片仍走循环，否则 Red 段后半程（约 7s 之后）整层会
 * 空掉，落叶这个母题就只剩一个开头。
 *
 * 低端机只把飘进来的隔一个画一个，枝上那三片不动 —— 那是这一张的叙事本体，
 * 而且整层必须一直在动，定格的背景在用户眼里就是卡死。
 */
@Composable
fun SwiftieRedLeafFallLayer(
    eraElapsedMs: () -> Long,
    lowRam: Boolean,
    modifier: Modifier = Modifier
) {
    val maple = rememberMapleBentArt()
    val drifterStep = if (lowRam) 2 else 1
    Spacer(
        modifier = modifier.fillMaxSize().drawBehind {
            val eraMs = eraElapsedMs()
            if (eraMs < 0L) return@drawBehind
            var i = RedDrifters.lastIndex
            while (i >= 0) {
                drawRedDrifter(RedDrifters[i], maple, eraMs)
                i -= drifterStep
            }
            // 枝上那三片最后画：它们离镜头最近，落过卡片时要压在别的叶上面。
            // 已经落出下缘的剔除掉 —— 它归这一层画，但这一帧屏幕底下没有它的位置
            RedBranchLeaves.forEach { leaf ->
                if (leaf.releasedAt(eraMs) && !leaf.fallenOffAt(eraMs)) {
                    drawRedBranchLeaf(maple, leaf, eraMs, 1f)
                }
            }
        }
    )
}

/** 飘进来的一片：走 [FALL_TOP] 到 [FALL_BOTTOM] 的整趟，每趟重掷起点、大小与色相。 */
private fun DrawScope.drawRedDrifter(
    drifter: RedDrifter,
    maple: MapleArt,
    eraMs: Long
) {
    val since = eraMs - drifter.enterAtMs
    if (since < 0L) return
    val trips = since.toFloat() / drifter.spanMs
    val round = floor(trips).toInt()
    val u = trips - round
    val prog = fallProgress(u)
    // 每趟重掷：同一趟落回上一趟的轨迹上，第二遍就被看穿了（见 hash01）
    val fx = (hash01(drifter.seed, round, 1) * 1.16f - 0.08f) +
        lissajous(u, drifter.freqA, drifter.freqB, hash01(drifter.seed, round, 2)) *
            drifter.amp * swayRamp(u) +
        drifter.drift * u * u
    val fy = FALL_TOP + (FALL_BOTTOM - FALL_TOP) * prog
    val rot = u * drifter.twist * 360f + sin(u * TAU * drifter.nodFreq) * drifter.nodDeg
    val flip = cos(u * TAU * drifter.tumble)
    val half = drifter.half + hash01(drifter.seed, round, 3) * 0.014f
    val hue = MapleHues[(drifter.hueIndex + round) % MapleHues.size]
    val fold = abs(flip).coerceAtLeast(0.10f)
    drawMapleAtPose(
        maple, fx, fy, half, rot,
        if (flip >= 0f) fold else -fold, hue, 1f, flip >= 0f
    )
}

/** 本段是否正在落叶。只有 Red 那 9.4s 需要这一层全屏节点，其余时间它不该存在。 */
internal fun redLeafFallActiveAt(elapsedMs: Long): Boolean =
    SwiftieTimeline.eraIndexAt(elapsedMs) == SwiftieErasData.RED_INDEX
