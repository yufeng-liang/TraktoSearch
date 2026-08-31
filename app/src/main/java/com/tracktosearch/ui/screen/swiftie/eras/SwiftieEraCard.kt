package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice

/** 卡片自轴上长出。 */
const val CARD_GROW_MS: Long = 400L

/** 停留供阅读。第 1、7 张另有 `CARD_ANCHOR_BONUS_MS`，由 `durationMs` 带进来。 */
const val CARD_HOLD_MS: Long = 5_000L

/** 卡片回落。 */
const val CARD_RECEDE_MS: Long = 400L

/** 段间停顿：回落完到下一张开始长出之间的空白。 */
const val CARD_GAP_MS: Long = 100L

/**
 * 母题相位循环一圈的时长。3.6s 让闪烁 / 呼吸在单张停留里走完约两圈 ——
 * 一圈太慢看不出在动，四圈以上又抢注意力。
 */
private const val MOTIF_CYCLE_MS: Long = 3_600L

/** 卡片圆角。 */
private val CARD_SHAPE = RoundedCornerShape(20.dp)

/**
 * 一个时代的卡片：从轴上长出 → 停留（曲目逐行点亮 + 扫光）→ 回落。
 *
 * 卡片**不随深色模式反色** —— 它是「图」，配色由 [SwiftieEra] 固定（Spec §2.3）。
 *
 * @param elapsedInCard 这张卡片起点以来的毫秒
 * @param durationMs 这张卡片分到的总时长，取自 `SwiftieTimeline.cardDurationMs`
 * @param originFractionX 对应色带在轴上的中心比例，用作缩放轴心
 */
@Composable
fun SwiftieEraCard(
    era: SwiftieEra,
    elapsedInCard: () -> Long,
    durationMs: Long,
    originFractionX: Float,
    modifier: Modifier = Modifier
) {
    val lowRam = rememberIsLowRamDevice()
    val titleFont = remember(era.fontResId) { FontFamily(Font(era.fontResId)) }
    // 浅色时代主色印在白卡上读不出来，这里取压暗到 AA 的那一组（见 SwiftieEraContrast）
    val textColors = remember(era) { SwiftieEraTextColors(era) }
    val description = stringResource(
        R.string.swiftie_era_card_a11y,
        era.name,
        era.releaseDate,
        era.tracks.size
    )
    // 回落起点由总时长倒推：durationMs - 400 - 100
    val recedeStartMs = durationMs - CARD_RECEDE_MS - CARD_GAP_MS

    Box(
        modifier = modifier
            .graphicsLayer {
                val elapsed = elapsedInCard()
                // EaseOutBack 会冲过 1 再收回来，读起来像琴键被按下又弹起。
                // 峰值约 1.1，只持续几帧，12sp 文字的形变看不出来
                val grow = EaseOutBack.transform(
                    (elapsed.toFloat() / CARD_GROW_MS).coerceIn(0f, 1f)
                )
                val recede = EaseInCubic.transform(
                    ((elapsed - recedeStartMs).toFloat() / CARD_RECEDE_MS).coerceIn(0f, 1f)
                )
                scaleY = grow * (1f - recede)
                alpha = (elapsed.toFloat() / (CARD_GROW_MS * 0.6f)).coerceIn(0f, 1f) * (1f - recede)
                // 轴心落在色带上、贴着轴线（y = 1f），才像从那一段长出来
                transformOrigin = TransformOrigin(
                    pivotFractionX = originFractionX.coerceIn(0f, 1f),
                    pivotFractionY = 1f
                )
            }
            .shadow(elevation = 10.dp, shape = CARD_SHAPE, clip = false)
            .clip(CARD_SHAPE)
            // 半透明白纸压在水彩天空上；主色只染一层薄底
            .background(Color.White.copy(alpha = 0.86f))
            .drawBehind {
                drawRect(color = era.mainColor, alpha = 0.10f)
                drawEraMotif(
                    motif = era.motif,
                    color = era.mainColor,
                    phase = (elapsedInCard().mod(MOTIF_CYCLE_MS)).toFloat() / MOTIF_CYCLE_MS,
                    lowRam = lowRam
                )
            }
            // 卡片整体一条 contentDescription；曲目列自己对 TalkBack 隐身（Spec §6.2）
            .semantics { contentDescription = description }
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = era.name,
                style = TextStyle(
                    fontFamily = titleFont,
                    fontSize = titleFontSizeFor(era.name),
                    color = textColors.body
                ),
                // 允许折两行：13 个字体的字宽差得很远，按字数估的字号可能还是偏大，
                // 折行总比裁掉专辑名好
                maxLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = era.releaseDate,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = textColors.date.copy(alpha = SwiftieEraContrast.DATE_ALPHA)
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            SwiftieEraTracklist(
                era = era,
                textColors = textColors,
                elapsedInCard = elapsedInCard,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 按专辑名长度分档取字号。
 *
 * 没用 `BasicText` 的 `autoSize`：那要在测量阶段反复试排，12 张卡片各一次不值得，
 * 而且这 12 个名字是固定的常量，档位一次调好就永远对。
 */
private fun titleFontSizeFor(name: String) = when {
    name.length <= 8 -> 34.sp     // Red · 1989 · Lover · folklore · evermore · Fearless
    name.length <= 14 -> 27.sp    // Speak Now · Midnights · reputation · Taylor Swift
    else -> 18.sp                 // The Tortured Poets Department · The Life of a Showgirl
}
