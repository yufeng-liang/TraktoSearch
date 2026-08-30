package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice
import java.util.Locale

/** 卡片长出用 400ms，曲目从第 400ms 起逐行点亮。 */
const val TRACK_REVEAL_START_MS: Long = 400L

/** 每行 130ms：读起来像唱针一格一格走过去。再快就成一整块闪现。 */
const val TRACK_STAGGER_MS: Long = 130L

/** 低端机加快到 70ms 并关扫光。**单张卡片总时长不变**，省下的并进停留。 */
const val TRACK_STAGGER_LOW_RAM_MS: Long = 70L

/** 单行淡入时长。 */
private const val TRACK_FADE_MS: Float = 220f

/** 全部点亮之后再等 600ms 才开始扫光。 */
private const val SWEEP_DELAY_MS: Long = 600L

/** 扫光走完一趟。 */
private const val SWEEP_MS: Float = 2_200f

/** 单行行高，固定值 —— 18 行也只有 288dp。 */
private val ROW_HEIGHT = 16.dp

/**
 * 一张专辑的完整曲目列：逐行点亮 + 一趟扫光（Spec §6.2）。
 *
 * 曲目名**不用**时代字体 —— 那些字体按专辑名逐个子集化，拿来画曲目全是豆腐块。
 * 只借该时代的主色。
 *
 * @param elapsedInCard 这张卡片自己的已用毫秒
 */
@Composable
fun SwiftieEraTracklist(
    era: SwiftieEra,
    elapsedInCard: () -> Long,
    modifier: Modifier = Modifier
) {
    val lowRam = rememberIsLowRamDevice()
    val stagger = if (lowRam) TRACK_STAGGER_LOW_RAM_MS else TRACK_STAGGER_MS
    val revealDoneAt = TRACK_REVEAL_START_MS + stagger * era.tracks.size

    val numberStyle = remember(era) {
        TextStyle(fontSize = 11.sp, color = era.mainColor.copy(alpha = 0.55f))
    }
    val titleStyle = remember(era) {
        TextStyle(fontSize = 12.sp, color = era.textColor)
    }

    Box(
        // 172 首念不完，也会把动画期间的焦点全占住。整块对 TalkBack 隐身，
        // 卡片自己有一条 contentDescription
        modifier = modifier.clearAndSetSemantics { }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            era.tracks.forEachIndexed { index, title ->
                val appearAt = TRACK_REVEAL_START_MS + stagger * index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        // 在 graphicsLayer 里读时钟：每帧只失效 draw，不重组
                        .graphicsLayer {
                            val p = ((elapsedInCard() - appearAt) / TRACK_FADE_MS)
                                .coerceIn(0f, 1f)
                            alpha = p
                            translationY = (1f - p) * 10.dp.toPx()
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // 固定 Locale.US：某些地区会把 %02d 渲染成本地数字
                        text = String.format(Locale.US, "%02d", index + 1),
                        style = numberStyle,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(
                        text = title,
                        style = titleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (!lowRam) {
            TrackSweep(
                color = era.mainColor,
                elapsedInCard = elapsedInCard,
                sweepStartMs = revealDoneAt + SWEEP_DELAY_MS,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@Composable
private fun TrackSweep(
    color: Color,
    elapsedInCard: () -> Long,
    sweepStartMs: Long,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.drawWithCache {
            // 光带高度取 22% 曲目列高，短了看不出在扫、长了整列一起发光
            val bandHeight = size.height * 0.22f
            val brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = 0.22f),
                    Color.Transparent
                )
            )
            onDrawBehind {
                val p = ((elapsedInCard() - sweepStartMs) / SWEEP_MS)
                if (p < 0f || p > 1f) return@onDrawBehind
                val top = -bandHeight + p * (size.height + bandHeight)
                translate(top = top) {
                    drawRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(size.width, bandHeight)
                    )
                }
            }
        }
    )
}
