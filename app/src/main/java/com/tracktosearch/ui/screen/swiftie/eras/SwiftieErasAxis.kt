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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** 色带 12 + 间距 5 + 轴线 + 刻度 6 + 播放头旋钮 = 38dp。 */
private val AXIS_HEIGHT = 38.dp
private val RIBBON_HEIGHT = 12.dp
private val TICK_HEIGHT = 6.dp
private val AXIS_SIDE_PADDING = 20.dp

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
 * @param onSeekToEra 按下 / 拖动时吸附到的段落索引
 */
@Composable
fun SwiftieErasAxis(
    introProgress: () -> Float,
    playheadFraction: () -> Float,
    onSeekToEra: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(AXIS_HEIGHT)
                .padding(horizontal = AXIS_SIDE_PADDING)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // 按下即吸附，不等滑动阈值 —— 点一下色带就跳过去是最自然的期待
                        val down = awaitFirstDown()
                        onSeekToEra(swiftieEraIndexAtFraction(down.position.x / size.width))
                        // 只消费 Main pass；外层按住暂停走 Initial pass，照样收得到
                        down.consume()
                        drag(down.id) { change ->
                            onSeekToEra(swiftieEraIndexAtFraction(change.position.x / size.width))
                            change.consume()
                        }
                    }
                }
        ) {
            val intro = introProgress().coerceIn(0f, 1f)
            val linePhase = (intro / 0.45f).coerceIn(0f, 1f)
            val tickPhase = ((intro - 0.35f) / 0.35f).coerceIn(0f, 1f)
            val ribbonPhase = ((intro - 0.55f) / 0.45f).coerceIn(0f, 1f)

            val ribbonHeight = RIBBON_HEIGHT.toPx()
            val lineY = ribbonHeight + 5.dp.toPx()
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
                val rise = (1f - ribbonPhase) * 4.dp.toPx()
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
                    start = Offset(headX, 0f),
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
                    alpha = ((introProgress() - 0.35f) / 0.35f).coerceIn(0f, 1f)
                },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val yearStyle = TextStyle(
                fontSize = 10.sp,
                color = SwiftiePalette.RoyalBlue.copy(alpha = 0.70f)
            )
            Text(text = "2006", style = yearStyle)
            Text(text = "2025", style = yearStyle)
        }
    }
}
