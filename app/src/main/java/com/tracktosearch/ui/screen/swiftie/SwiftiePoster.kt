package com.tracktosearch.ui.screen.swiftie

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.tracktosearch.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 出题态答案槽里的未知数。 */
private const val UNKNOWN = "X"

/**
 * 连错 3 次后浮出的小字。
 *
 * 刻意**不本地化** —— 它是这张印刷品的一部分，不是界面文案；而且 Honey Script 只按
 * `0123456789+=XHerluckynmb. ` 子集化过，换成中日韩会整行豆腐块。视障用户拿不到这条
 * 视觉提示，所以另有一份本地化的 `R.string.swiftie_quiz_a11y_hint`，由海报的
 * `contentDescription` 带出去。
 */
private const val LUCKY_HINT = "Her lucky number."

/** 提示胶囊的底色，与音频提示同一档白纱。 */
private val LUCKY_CAPSULE = Color.White.copy(alpha = 0.62f)

/** 三行手写体合计的书写时长；行与行之间另有 [SCRIPT_LINE_PAUSE_MS]。 */
const val SCRIPT_WRITE_MS: Long = 780L

/** 一行写完到下一行落笔之间的停顿。两个间隙共 120ms。 */
const val SCRIPT_LINE_PAUSE_MS: Long = 60L

/** 手写体从落笔到收笔的总时长，前奏按它排窗口。 */
const val SCRIPT_TOTAL_MS: Long = SCRIPT_WRITE_MS + SCRIPT_LINE_PAUSE_MS * 2

/** 答错摇晃的横向幅度。 */
private val SHAKE_TRAVEL = 10.dp

/** 答错时答案槽的描边宽度。 */
private val WRONG_STROKE = 2.dp

/** 闪粉上的动态高光颗数。低端机减半档（Spec §11.2）。 */
private const val TWINKLE_COUNT = 22
private const val TWINKLE_COUNT_LOW_RAM = 9

/**
 * 出题页整幅海报在屏幕上的几何。
 *
 * @param size 海报的完整尺寸（px）。文字全部按它的比例排 —— 天空可能被裁掉一部分，
 *   但版式要按「整张海报」算，否则裁多少字就跟着挪多少
 * @param offset 海报左上角在屏幕上的位置（px），窄屏上 x 为负（横向被裁）
 * @param quizScale 出题态整条算式的缩放。屏幕够高时是 1，矮屏才压
 * @param quizShiftY 出题态整条算式的竖向位移（px，负值向上）
 */
internal class SwiftiePosterFit(
    val size: Size,
    val offset: Offset,
    val quizScale: Float,
    val quizShiftY: Float
)

/**
 * 按屏幕和键盘的实际占位算出海报几何。
 *
 * 位移量**不是写死的比例**：算式在出题态要停在键盘上沿之上，而键盘高度由键帽的
 * `aspectRatio(1.6f)` 从宽度推出来，屏幕一换就变。写死 0.33H 那种值在 640dp 高的屏上
 * 会把算式顶出画面。
 *
 * @param equationBottomLimit 出题态算式底边不得越过的屏幕纵坐标（px）
 * @param equationTopLimit 出题态算式顶边不得越过的屏幕纵坐标（px），给状态栏与 ✕ 让位
 * @param maxTextWidth 版式宽度上限（px）。平板上天空照样铺满，但字不跟着长
 */
internal fun swiftiePosterFit(
    screen: Size,
    equationBottomLimit: Float,
    equationTopLimit: Float,
    maxTextWidth: Float
): SwiftiePosterFit {
    val cover = maxOf(
        screen.width / SwiftiePosterInk.SOURCE_WIDTH,
        screen.height / SwiftiePosterInk.SOURCE_HEIGHT
    )
    var width = SwiftiePosterInk.SOURCE_WIDTH * cover
    var height = SwiftiePosterInk.SOURCE_HEIGHT * cover
    if (width > maxTextWidth) {
        width = maxTextWidth
        height = width / SwiftiePosterInk.ASPECT
    }
    val offset = Offset((screen.width - width) / 2f, (screen.height - height) / 2f)

    val top = offset.y + SwiftiePosterInk.EQUATION_TOP * height
    val bottom = offset.y + SwiftiePosterInk.EQUATION_BOTTOM * height
    val span = (bottom - top).coerceAtLeast(1f)
    val room = (equationBottomLimit - equationTopLimit).coerceAtLeast(1f)
    // 只在放不下时才压，够高的屏幕上出题态与归位态是同一个字号
    val scale = (room / span).coerceIn(0.55f, 1f)
    return SwiftiePosterFit(
        size = Size(width, height),
        offset = offset,
        quizScale = scale,
        // 缩放以算式顶边为轴心，所以缩完底边在 top + span * scale，再平移到限位上
        quizShiftY = equationBottomLimit - (top + span * scale)
    )
}

/**
 * 排好版的海报墨迹。只在字号（也就是海报尺寸）变化时重建。
 *
 * `13` 那一组不在 [fixed] 里 —— 它是答案槽，跟着输入变。
 */
private class SwiftiePosterArt(context: Context, size: Size) {

    /** `+ 87 = 100`：固定不动的那三组，合成一条路径一次画完。 */
    val fixed: Path = Path().apply {
        addPath(SwiftiePosterInk.uniform(context, "+", SwiftiePosterInk.PLUS, size))
        addPath(SwiftiePosterInk.uniform(context, "87", SwiftiePosterInk.EIGHTY_SEVEN, size))
        addPath(SwiftiePosterInk.stretched(context, "=", SwiftiePosterInk.EQUALS, size))
        addPath(SwiftiePosterInk.uniform(context, "100", SwiftiePosterInk.HUNDRED, size))
    }

    val slot: SwiftiePosterInk.SlotMetrics = SwiftiePosterInk.slotMetrics(context, size)

    val scriptLines: List<Path> =
        SwiftieCongratsPath.LINES.indices.map { SwiftiePosterInk.scriptLine(it, size) }

    private val scriptRanges: List<ClosedFloatingPointRange<Float>> =
        SwiftieCongratsPath.LINES.indices.map { SwiftiePosterInk.scriptLineXRange(it, size) }

    /**
     * 每行的书写窗口。时间按各行的横向跨度分配 —— 笔速恒定才像手写，
     * 平均分配会让只有两个字母的 `on` 写得和 `Congrats` 一样久。
     * 取整误差由最后一行吸收，所以总长严格等于 [SCRIPT_TOTAL_MS]。
     */
    private val scriptWindows: List<LongRange> = buildList {
        val spans = scriptRanges.map { (it.endInclusive - it.start).coerceAtLeast(1f) }
        val total = spans.sum()
        var cursor = 0L
        var spent = 0L
        spans.forEachIndexed { index, span ->
            val duration = if (index == spans.lastIndex) {
                SCRIPT_WRITE_MS - spent
            } else {
                (SCRIPT_WRITE_MS * span / total).toLong()
            }
            spent += duration
            add(cursor..(cursor + duration))
            cursor += duration + SCRIPT_LINE_PAUSE_MS
        }
    }

    /** [index] 行在 [elapsedMs] 时刻的揭示前沿；返回 null 表示这一行还没落笔。 */
    fun scriptRevealX(index: Int, elapsedMs: Long): Float? {
        val window = scriptWindows[index]
        val range = scriptRanges[index]
        if (elapsedMs < window.first) return null
        if (elapsedMs >= window.last) return range.endInclusive
        val span = (window.last - window.first).coerceAtLeast(1L)
        val progress = (elapsedMs - window.first).toFloat() / span
        return range.start + (range.endInclusive - range.start) * progress
    }
}

/**
 * 出题页的整幅海报（Spec §2.3 的「灯箱」，现在是全屏）。
 *
 * 三层：位图天空 → 闪粉浮尘 → 一整个 Canvas 画完算式与手写体。
 *
 * 天空是 `docs/previews/swiftie-poster/sky-clean.png` 的 WebP，**静止不动** ——
 * 它是一张印刷品；而这一屏原先那 8 颗循环上浮的爱心，是唯一不属于原图的东西。
 * 配色一律不随深色模式变化：它是图，不是界面。
 *
 * 算式与手写体**不用离屏图层**：`drawPath(brush)` 直接用平铺的闪粉贴图填字形，
 * 高光用 `clipPath` 收进字形里。这比「烤一张 mask 位图再 DstIn」省掉一次全屏大小的
 * 位图分配 —— 答案槽每次落字都会重建路径，那张位图会跟着每次按键重新分配。
 *
 * @param settle 0 = 出题态（算式抬到键盘上方），1 = 归位到原图的真实位置
 * @param scriptRevealMs 手写体书写已进行的毫秒；负数表示还没落笔
 */
@Composable
internal fun SwiftiePoster(
    state: SwiftieQuizState,
    fit: SwiftiePosterFit,
    settle: () -> Float,
    scriptRevealMs: () -> Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val emptyLabel = stringResource(R.string.swiftie_quiz_a11y_empty)
    val question = stringResource(R.string.swiftie_quiz_a11y, state.input.ifEmpty { emptyLabel })
    // 连错 3 次浮出的 Her lucky number. 是纯视觉的，这里补一份本地化文案带给 TalkBack
    val hint = stringResource(R.string.swiftie_quiz_a11y_hint)
    val a11y = if (state.showLuckyHint) "$question $hint" else question

    val art = remember(context, fit.size) { SwiftiePosterArt(context, fit.size) }
    val slotPath = remember(art, state.input, state.solved) {
        SwiftiePosterInk.slotPath(context, state.input.ifEmpty { UNKNOWN }, art.slot)
    }
    // 高光要收在闪粉里。答错那 300ms 槽位是描边而不是填充，就不该跟着闪
    val twinkleMask = remember(art, slotPath, state.phase) {
        Path().apply {
            addPath(art.fixed)
            if (state.phase != SwiftieQuizPhase.WRONG) addPath(slotPath)
        }
    }
    val glitter = rememberGlitterBrush(fit.size)
    val time = rememberGlitterTime()
    val twinkleCount = if (rememberIsLowRamDevice()) TWINKLE_COUNT_LOW_RAM else TWINKLE_COUNT
    val twinkles = remember(twinkleCount) { buildSparkles(twinkleCount) }
    val shakeTravel = with(LocalDensity.current) { SHAKE_TRAVEL.toPx() }
    val strokeWidth = with(LocalDensity.current) { WRONG_STROKE.toPx() }

    // 答错摇晃：wrongCount 每 +1 摇一遍，300ms 三个来回后回零。只摇算式，不摇天空 ——
    // 整屏跟着晃会让人以为是页面出错，而不是这个数答错了
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state.wrongCount) {
        if (state.wrongCount == 0) return@LaunchedEffect
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 300
                0f at 0
                -1f at 50
                1f at 110
                -0.7f at 170
                0.5f at 230
                0f at 300
            }
        )
    }

    // 落字弹一下 + 爆一圈亮点。只数「落进来的字」：变长才算，退格与清空不动它
    val pop = remember { Animatable(1f) }
    val typedCount = remember { mutableIntStateOf(0) }
    val previousLength = remember { mutableIntStateOf(0) }
    LaunchedEffect(state.input) {
        val grew = state.input.length > previousLength.intValue
        previousLength.intValue = state.input.length
        if (!grew) {
            if (state.input.isEmpty()) pop.snapTo(1f)
            return@LaunchedEffect
        }
        typedCount.intValue++
        pop.snapTo(1.25f)
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 900f))
    }
    val burst = remember { Animatable(1f) }
    LaunchedEffect(typedCount.intValue, state.phase) {
        if (typedCount.intValue <= 0 || state.phase == SwiftieQuizPhase.WRONG) return@LaunchedEffect
        burst.snapTo(0f)
        burst.animateTo(1f, tween(450))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) { contentDescription = a11y }
    ) {
        Image(
            painter = painterResource(R.drawable.swiftie_poster_sky),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        )
        SwiftieGlitterDust(modifier = Modifier.fillMaxSize())
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                // 逐帧变化的量全在 onDrawBehind 里读，组合期一次都不失效
                .drawWithCache {
                    onDrawBehind {
                        // 天空可能被裁，所以先平移到「整张海报」的左上角，
                        // 之后所有坐标都在海报空间里，与实测的比例一一对应
                        translate(left = fit.offset.x, top = fit.offset.y) {
                            drawScript(art, fit, scriptRevealMs())
                            drawEquation(
                                art = art,
                                fit = fit,
                                slotPath = slotPath,
                                twinkleMask = twinkleMask,
                                wrong = state.phase == SwiftieQuizPhase.WRONG,
                                settle = settle(),
                                shake = shake.value * shakeTravel,
                                pop = pop.value,
                                burst = burst.value,
                                strokeWidth = strokeWidth,
                                glitter = glitter,
                                twinkles = twinkles,
                                phase = time.value
                            )
                        }
                    }
                }
        )
    }
}

/** 三行手写体逐行从左到右写出来。整句做一次揭示会让第 1 行和第 3 行同时长出来。 */
private fun DrawScope.drawScript(
    art: SwiftiePosterArt,
    fit: SwiftiePosterFit,
    elapsedMs: Long
) {
    if (elapsedMs < 0L) return
    art.scriptLines.forEachIndexed { index, path ->
        val revealX = art.scriptRevealX(index, elapsedMs) ?: return@forEachIndexed
        // 竖向给足余量：海报可能比屏幕高（平板上是上下裁），用 DrawScope 的 size 会切掉
        clipRect(
            left = -fit.size.width,
            top = -fit.size.height,
            right = revealX,
            bottom = fit.size.height * 2f
        ) {
            drawPath(path = path, color = SwiftiePalette.RoyalBlue)
        }
    }
}

/** 落字时从答案槽爆开的一圈亮点。 */
private const val BURST_DOTS = 10

/** 亮点飞出的距离，占海报高度。 */
private const val BURST_TRAVEL_FRACTION = 0.075f

/**
 * 一整条算式：`13 + 87 = 100`。
 *
 * 出题态与归位态之间只差一个仿射变换 —— 同一套路径、同一个字号，所以答对那一刻不是
 * 「换一张图」，是这几个数自己走回原图里的位置。
 *
 * @param settle 0 = 出题态，1 = 归位
 * @param shake 答错摇晃的横向位移（px）
 * @param pop 答案槽的落字回弹倍率
 * @param burst 落字亮点的进度，1 = 已散完
 * @param phase 闪粉高光的共用相位
 */
private fun DrawScope.drawEquation(
    art: SwiftiePosterArt,
    fit: SwiftiePosterFit,
    slotPath: Path,
    twinkleMask: Path,
    wrong: Boolean,
    settle: Float,
    shake: Float,
    pop: Float,
    burst: Float,
    strokeWidth: Float,
    glitter: Brush,
    twinkles: List<Sparkle>,
    phase: Float
) {
    val poster = fit.size
    val tau = 2f * PI.toFloat()
    val scale = lerp(fit.quizScale, 1f, settle)
    val shift = lerp(fit.quizShiftY, 0f, settle)
    // 缩放轴心：横向取海报中线（算式实测中心在 0.489W，偏差不到 0.5%），竖向取算式顶边
    // —— swiftiePosterFit 的位移量就是按这个轴心解出来的，换轴心那个值就不对了
    val pivot = Offset(poster.width / 2f, SwiftiePosterInk.EQUATION_TOP * poster.height)
    val slotCenter = Offset(
        art.slot.centerX,
        art.slot.baselineY - SwiftiePosterInk.ANSWER.height * poster.height / 2f
    )
    val box = SwiftiePosterInk.EQUATION
    val left = box.left * poster.width
    val top = box.top * poster.height
    val width = box.width * poster.width
    val height = box.height * poster.height

    withTransform({
        translate(left = shake, top = shift)
        scale(scale, scale, pivot)
    }) {
        drawPath(path = art.fixed, brush = glitter)
        if (wrong) {
            // 答错那 300ms 只描边：填成闪粉会读成「这个数收下了」，而它没有
            drawPath(path = slotPath, color = SwiftiePalette.Glitter, style = Stroke(strokeWidth))
        } else {
            withTransform({ scale(pop, pop, slotCenter) }) {
                drawPath(path = slotPath, brush = glitter)
            }
        }
        // 高光与归位闪光都收进字形里，一点都不溢到天空上
        clipPath(twinkleMask) {
            twinkles.forEach { sparkle ->
                val pulse = sin((phase + sparkle.phase) * tau) * 0.5f + 0.5f
                if (pulse <= 0.08f) return@forEach
                drawCircle(
                    color = Color.White,
                    radius = sparkle.radius * (0.5f + 0.5f * pulse),
                    center = Offset(left + sparkle.x * width, top + sparkle.y * height),
                    alpha = pulse * 0.75f
                )
            }
            // 归位途中整条算式亮一下。正弦包络，所以停在出题态和归位态时都是 0
            val flash = sin(settle * PI.toFloat()) * 0.3f
            if (flash > 0.01f) {
                drawRect(
                    color = Color.White,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    alpha = flash
                )
            }
        }
        // 亮点在字形外面，所以不能进 clipPath；但要跟着算式一起动，所以在变换里
        if (burst < 1f && !wrong) {
            val distance = BURST_TRAVEL_FRACTION * poster.height * (0.35f + 0.65f * burst)
            val alpha = (1f - burst) * 0.9f
            repeat(BURST_DOTS) { index ->
                val angle = index.toFloat() / BURST_DOTS * tau
                drawCircle(
                    color = Color.White,
                    radius = 0.8f + 3f * (1f - burst),
                    // 竖向压扁一点：正圆爆开像加载动画，扁的才像溅出来的闪粉
                    center = slotCenter + Offset(cos(angle) * distance, sin(angle) * distance * 0.8f),
                    alpha = alpha
                )
            }
        }
    }
}

/**
 * 连错 3 次后浮出的 `Her lucky number.`，一枚浅色胶囊压在键盘上方。
 *
 * 只动 alpha、位子常驻，所以它出现时不会把键盘顶下去。答对之后跟着淡出。
 */
@Composable
fun SwiftieLuckyHint(visible: Boolean, modifier: Modifier = Modifier) {
    val hintAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "luckyHintAlpha"
    )
    Text(
        text = LUCKY_HINT,
        style = TextStyle(
            fontFamily = SwiftieFonts.Marker,
            fontSize = 18.sp,
            color = SwiftiePalette.RoyalBlue,
            textAlign = TextAlign.Center
        ),
        maxLines = 1,
        // graphicsLayer 排在 background 之前，胶囊与字一起淡 —— 反过来只淡字、底还在
        modifier = modifier
            .graphicsLayer { alpha = hintAlpha }
            .background(LUCKY_CAPSULE, CircleShape)
            .padding(horizontal = 14.dp, vertical = 4.dp)
    )
}
