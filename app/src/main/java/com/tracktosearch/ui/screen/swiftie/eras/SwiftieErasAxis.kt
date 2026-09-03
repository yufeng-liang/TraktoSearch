package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline

/**
 * 13 个边界比例：色带 `i` 占 `[SWIFTIE_ERA_EDGES[i], SWIFTIE_ERA_EDGES[i + 1]]`。
 *
 * 宽度**按各段时长成比例**（7.9%–9.4%），这样播放头是匀速的。等宽色带会让播放头在
 * 18 首的 Lover 段明显变慢，看着像卡住了。
 */
val SWIFTIE_ERA_EDGES: List<Float> = List(SwiftieTimeline.ERA_TRACK_COUNTS.size + 1) { index ->
    if (index == SwiftieTimeline.ERA_TRACK_COUNTS.size) {
        1f
    } else {
        (SwiftieTimeline.eraStartMs(index) - SwiftieTimeline.ERAS_CARDS_START).toFloat() /
            SwiftieTimeline.ERAS_CARDS_MS
    }
}

/** 第 [index] 段色带的中心比例。卡片的缩放轴心用它。 */
fun swiftieEraCenterFraction(index: Int): Float {
    val safe = index.coerceIn(0, SWIFTIE_ERA_EDGES.size - 2)
    return (SWIFTIE_ERA_EDGES[safe] + SWIFTIE_ERA_EDGES[safe + 1]) / 2f
}

/**
 * 刻度与年份开始淡入的入场进度（入场第二拍的起点）。
 *
 * **不是私有的**：`SwiftieHapticScore` 按它反推「标尺出现了」那一记触感的时刻
 * （`ERAS_INTRO_START + ERAS_INTRO_MS × 这个值`）。改了这个数，手上那一记跟着挪。
 */
const val AXIS_TICK_FADE_IN_AT = 0.35f

/** 刻度淡入占入场进度的多长。0.35 + 0.35 = 刻度在入场七成处完全显形。 */
const val AXIS_TICK_FADE_IN_SPAN = 0.35f

/**
 * 横向比例落在第几段。拖动吸附用。
 *
 * 走一趟账本换算而不是自己二分 `SWIFTIE_ERA_EDGES`：段落归属只能有一个真相来源。
 */
fun swiftieEraIndexAtFraction(fraction: Float): Int {
    val ms = SwiftieTimeline.ERAS_CARDS_START +
        (fraction.coerceIn(0f, 1f) * SwiftieTimeline.ERAS_CARDS_MS).toLong()
    return SwiftieTimeline.eraIndexAt(ms) ?: SwiftieTimeline.ERA_TRACK_COUNTS.lastIndex
}

/** 播放头在轴上的比例。Eras 卡片段之外自动夹到 0f / 1f。 */
fun swiftiePlayheadFraction(elapsedMs: Long): Float =
    ((elapsedMs - SwiftieTimeline.ERAS_CARDS_START).toFloat() / SwiftieTimeline.ERAS_CARDS_MS)
        .coerceIn(0f, 1f)

/** 色带 12 + 间距 5 + 轴线 + 刻度 6 + 播放头旋钮 = 38dp 的图形高度。 */
private val AXIS_VISUAL_HEIGHT = 38.dp

/**
 * 轴的实际高度 —— 也就是它的**触控目标**。
 *
 * 图形只占 [AXIS_VISUAL_HEIGHT]，但拖播放头是这一段唯一的交互，38dp 低于
 * Material 的 48dp 下限。多出来的 10dp 上下各摊 5dp（见 `drawAxis` 里的 `top`），
 * 图形位置几乎没动，手指却多了 26% 的余量。
 */
private val AXIS_TOUCH_HEIGHT = 48.dp

private val RIBBON_HEIGHT = 12.dp
private val TICK_HEIGHT = 6.dp
private val AXIS_SIDE_PADDING = 20.dp

/** 两端年份的字号。跟随系统字号 —— 这一行外层没有定高容器，长大不会被裁。 */
private val YEAR_FONT_SIZE = 11.sp

/** 未走过的段落：灰化。走过的换成该时代主色。 */
private val DESATURATED = Color(0xFFB6AFAB)

/**
 * 横向时间轴：轴线 + 12 段色带 + 刻度 + 播放头。
 *
 * 入场分三拍（Spec §5 的 T1100–3100）：轴线自中点向两端铺开 → 刻度与年份淡入 →
 * 12 段色带以低饱和度升上来。
 *
 * @param introProgress 0f..1f，T1100–3100 的进度
 * @param playheadFraction 0f..1f，播放头位置。倒滑段由调用方给回退中的值
 * @param interactive false 时整块不收触摸（倒滑与绽放期间）
 * @param onSeekToEra 按下 / 拖动时吸附到的段落索引。`gestureStart` 只在一次手势的
 *   第一个事件为 true —— 「首次拖动给宽限」这类判断必须只在那一下做，
 *   拖动过程中每个移动事件都会回调
 */
@Composable
fun SwiftieErasAxis(
    introProgress: () -> Float,
    playheadFraction: () -> Float,
    interactive: Boolean,
    onSeekToEra: (index: Int, gestureStart: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val axisLabel = stringResource(R.string.swiftie_eras_axis_a11y)
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(AXIS_TOUCH_HEIGHT)
                .padding(horizontal = AXIS_SIDE_PADDING)
                // 倒滑与绽放期间必须整块停掉：alpha 只改绘制、不改命中区域，
                // 那时候碰一下就会把时钟倒拨回卡片段，配乐钉死的两个点当场失效
                // （同一个理由让「跳过」也在那之后 enabled = false）
                .then(
                    if (!interactive) {
                        // 不可交互时连语义也清掉：那几段轴的 alpha 已经是 0，
                        // 留着 contentDescription 只会让 TalkBack 停在一个看不见、
                        // 也点不动的东西上
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                            // 只给一条静态说明。**刻意不做 slider 语义** ——
                            // ProgressBarRangeInfo 要读逐帧的播放头位置，而语义 lambda
                            // 里的 state 读会让整棵语义树每帧失效重建；而且 12 段拖动
                            // 对读屏用户本来也没有可用性，卡片自己有完整的播报
                            .semantics { contentDescription = axisLabel }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    // 按下即吸附，不等滑动阈值 —— 点一下色带就跳过去是最自然的期待
                                    val down = awaitFirstDown()
                                    onSeekToEra(
                                        swiftieEraIndexAtFraction(down.position.x / size.width),
                                        true
                                    )
                                    // 只消费 Main pass；外层按住暂停走 Initial pass，照样收得到
                                    down.consume()
                                    drag(down.id) { change ->
                                        onSeekToEra(
                                            swiftieEraIndexAtFraction(change.position.x / size.width),
                                            false
                                        )
                                        change.consume()
                                    }
                                }
                            }
                    }
                )
        ) {
            val intro = introProgress().coerceIn(0f, 1f)
            val linePhase = (intro / 0.45f).coerceIn(0f, 1f)
            val tickPhase =
                ((intro - AXIS_TICK_FADE_IN_AT) / AXIS_TICK_FADE_IN_SPAN).coerceIn(0f, 1f)
            val ribbonPhase = ((intro - 0.55f) / 0.45f).coerceIn(0f, 1f)

            // 图形在 48dp 的触控高度里垂直居中，上下各留 5dp 只做触控余量
            val top = ((size.height - AXIS_VISUAL_HEIGHT.toPx()) / 2f).coerceAtLeast(0f)
            val ribbonHeight = RIBBON_HEIGHT.toPx()
            val ribbonTop = top
            val lineY = top + ribbonHeight + 5.dp.toPx()
            val tickBottom = lineY + TICK_HEIGHT.toPx()
            val knobY = tickBottom + 7.dp.toPx()
            val gap = 2.dp.toPx()
            val corner = CornerRadius(ribbonHeight / 2f)

            // 第一拍：轴线自中点向两端铺开
            val half = size.width / 2f * linePhase
            drawLine(
                color = SwiftiePalette.RoyalBlue,
                start = Offset(size.width / 2f - half, lineY),
                end = Offset(size.width / 2f + half, lineY),
                strokeWidth = 1.5.dp.toPx(),
                alpha = 0.55f
            )

            // 第二拍：刻度淡入（13 条，含两端）
            if (tickPhase > 0f) {
                SWIFTIE_ERA_EDGES.forEach { edge ->
                    val x = edge * size.width
                    drawLine(
                        color = SwiftiePalette.RoyalBlue,
                        start = Offset(x, lineY),
                        end = Offset(x, tickBottom),
                        strokeWidth = 1.dp.toPx(),
                        alpha = 0.40f * tickPhase
                    )
                }
            }

            // 第三拍：色带升上来。先整条灰化，再把走过的部分覆一层饱和色
            if (ribbonPhase > 0f) {
                val rise = ribbonTop + (1f - ribbonPhase) * 4.dp.toPx()
                SWIFTIE_ERA_EDGES.zipWithNext().forEach { (from, to) ->
                    drawRoundRect(
                        color = DESATURATED,
                        topLeft = Offset(from * size.width + gap / 2f, rise),
                        size = Size((to - from) * size.width - gap, ribbonHeight),
                        cornerRadius = corner,
                        alpha = 0.55f * ribbonPhase
                    )
                }
                // 一个 clipRect 就够：当前段会被切成半亮，读起来正是「正在放这一段」
                val head = playheadFraction().coerceIn(0f, 1f) * size.width
                clipRect(left = 0f, top = 0f, right = head, bottom = size.height) {
                    SWIFTIE_ERA_EDGES.zipWithNext().forEachIndexed { index, (from, to) ->
                        drawRoundRect(
                            color = SwiftieErasData.ALL[index].mainColor,
                            topLeft = Offset(from * size.width + gap / 2f, rise),
                            size = Size((to - from) * size.width - gap, ribbonHeight),
                            cornerRadius = corner,
                            alpha = ribbonPhase
                        )
                    }
                }

                // 播放头：竖线穿过色带与刻度，下面挂一个白心蓝环的旋钮
                val headX = playheadFraction().coerceIn(0f, 1f) * size.width
                drawLine(
                    color = SwiftiePalette.RoyalBlue,
                    start = Offset(headX, ribbonTop),
                    end = Offset(headX, knobY),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    alpha = ribbonPhase
                )
                drawCircle(
                    color = Color.White,
                    radius = 5.dp.toPx(),
                    center = Offset(headX, knobY),
                    alpha = ribbonPhase
                )
                drawCircle(
                    color = SwiftiePalette.RoyalBlue,
                    radius = 5.dp.toPx(),
                    center = Offset(headX, knobY),
                    alpha = ribbonPhase,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // 年份跟着刻度那一拍淡入。**不显示 `3/12` 这类进度数字**（Spec §6.1）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AXIS_SIDE_PADDING)
                .graphicsLayer {
                    alpha = ((introProgress() - AXIS_TICK_FADE_IN_AT) / AXIS_TICK_FADE_IN_SPAN)
                        .coerceIn(0f, 1f)
                },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val yearStyle = TextStyle(
                fontSize = YEAR_FONT_SIZE,
                color = SwiftiePalette.RoyalBlue.copy(alpha = 0.70f)
            )
            // 年份是装饰性重复信息（轴本身与 12 张卡片都带日期），对读屏隐身
            Text(text = "2006", style = yearStyle, modifier = Modifier.clearAndSetSemantics { })
            Text(text = "2025", style = yearStyle, modifier = Modifier.clearAndSetSemantics { })
        }
    }
}
