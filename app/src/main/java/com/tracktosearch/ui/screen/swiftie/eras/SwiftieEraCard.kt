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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/** 曲目行的理想行高。18 行只有 324dp，绝大多数屏幕都用这个值。 */
private val TRACK_ROW_HEIGHT_MAX = 18.dp

/**
 * 行高下限。
 *
 * 这里**不能**用「宁可让卡片顶出插槽也不再压」的策略：卡片带 `.clip(CARD_SHAPE)`，
 * 顶出去的行不是露在外面，是被剪掉、静默消失。所以下限压到 9dp ——
 * 31 首的 TTPD 至少要 `136 + 31×9 = 415dp` 插槽，比任何在售机型的可用高度都低。
 * 代价是小屏上这张卡的字确实小（约 6.8dp），但总比丢掉最后几首好。
 */
private val TRACK_ROW_HEIGHT_MIN = 9.dp

/**
 * 卡片除曲目列以外占的高度：标题（最多两行）+ 日期 + 两处间距 + 上下内边距。
 *
 * 这是个 dp 常量，所以标题与日期的字号也必须是 dp 折算的（见 [titleFontSizeFor]
 * 与调用处的 `toSp()`）—— 否则系统字号一放大，实际 chrome 就超过这个预算，
 * 曲目列被挤出卡片、被 clip 静默吃掉尾部几行。
 *
 * 136dp = 上下内边距 32 + 两处间距 12 + 日期一行 16 + 标题最多 76。标题那一档按
 * 「32dp 字号折两行」算，是 12 个专辑名里最坏的情况（40dp 那一档全是 8 个字符以内的
 * 短名，在任何在售机型上都排得下一行）。字号调大就要跟着改这里 —— 估小了曲目列会被
 * 挤出卡片，而卡片带 clip，挤出去的行是**静默消失**，不是露在外面。
 */
private val CARD_CHROME_HEIGHT = 136.dp


/**
 * 按插槽剩余高度定行高。
 *
 * TTPD 用 Anthology 版 31 首，16dp 行高要 496dp —— 大屏放得下，360×640 的小屏放不下。
 * 这里只在放不下时才压，所以其余 11 张卡片与压缩前逐像素一致。
 */
private fun trackRowHeight(slotHeight: Dp, trackCount: Int): Dp {
    if (trackCount <= 0) return TRACK_ROW_HEIGHT_MAX
    return ((slotHeight - CARD_CHROME_HEIGHT) / trackCount)
        .coerceIn(TRACK_ROW_HEIGHT_MIN, TRACK_ROW_HEIGHT_MAX)
}

/**
 * 一个时代的卡片：从轴上长出 → 停留（曲目逐行点亮 + 扫光）→ 回落。
 *
 * 卡片**不随深色模式反色** —— 它是「图」，配色由 [SwiftieEra] 固定（Spec §2.3）。
 *
 * @param elapsedInCard 这张卡片起点以来的毫秒
 * @param durationMs 这张卡片分到的总时长，取自 `SwiftieTimeline.cardDurationMs`
 * @param originFractionX 对应色带在轴上的中心比例，用作缩放轴心
 * @param slotHeight 卡片可用的最大高度，由 `SwiftieErasStage` 量出来。曲目行高按它折算
 */
@Composable
fun SwiftieEraCard(
    era: SwiftieEra,
    elapsedInCard: () -> Long,
    durationMs: Long,
    originFractionX: Float,
    slotHeight: Dp,
    modifier: Modifier = Modifier
) {
    val lowRam = rememberIsLowRamDevice()
    val density = LocalDensity.current
    val titleFont = remember(era.fontResId) { FontFamily(Font(era.fontResId)) }
    // 浅色时代主色印在白卡上读不出来，这里取压暗到 AA 的那一组（见 SwiftieEraContrast）
    val textColors = remember(era) { SwiftieEraTextColors(era) }
    val rowHeight = trackRowHeight(slotHeight, era.tracks.size)
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
            .shadow(elevation = 14.dp, shape = CARD_SHAPE, clip = false)
            .clip(CARD_SHAPE)
            // 半透明白纸压在水彩天空上；主色只染一层薄底
            .background(Color.White.copy(alpha = 0.86f))
            .drawBehind {
                drawRect(color = era.mainColor, alpha = 0.10f)
                // 低端机整块母题定格（Spec §11.2）。关键是这条分支**根本不读时钟** ——
                // 只要读了 elapsedInCard()，这个 drawBehind 就会每帧失效，
                // 母题里那一堆 Path / Brush 也就每帧重建一次
                val phase = if (lowRam) {
                    0f
                } else {
                    (elapsedInCard().mod(MOTIF_CYCLE_MS)).toFloat() / MOTIF_CYCLE_MS
                }
                drawEraMotif(
                    motif = era.motif,
                    color = era.mainColor,
                    phase = phase,
                    lowRam = lowRam
                )
                // 母题之上再压一层光与颗粒。放在母题后面是因为它要的是「打在场景上的光」，
                // 画在母题下面就只是又一层底色。**只用白 / 亮色**：卡片上的文字是深色，
                // 提亮底色只会让对比度更好，压暗才会把 SwiftieEraContrast 那套模型顶穿
                drawEraAtmosphere(phase = phase, lowRam = lowRam)
            }
            // 卡片整体一条 contentDescription；曲目列自己对 TalkBack 隐身（Spec §6.2）。
            // mergeDescendants 是必须的 —— 少了它，下面的专辑名与日期两个 Text
            // 仍是各自可停靠的叶子，contentDescription 里的曲目数反而无处可去
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = era.name,
                style = TextStyle(
                    fontFamily = titleFont,
                    // toSp() 除掉 fontScale：CARD_CHROME_HEIGHT 是 dp 预算，
                    // 标题跟着系统字号长就会把曲目列挤出卡片
                    fontSize = with(density) { titleFontSizeFor(era.name).toSp() },
                    color = textColors.body
                ),
                // 允许折两行：13 个字体的字宽差得很远，按字数估的字号可能还是偏大，
                // 折行总比裁掉专辑名好；真的还放不下就省略号，别硬切字形
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = era.releaseDate,
                style = TextStyle(
                    fontSize = with(density) { 12.5.dp.toSp() },
                    color = textColors.date.copy(alpha = SwiftieEraContrast.DATE_ALPHA)
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            SwiftieEraTracklist(
                era = era,
                textColors = textColors,
                rowHeight = rowHeight,
                elapsedInCard = elapsedInCard,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 按专辑名长度分档取字号。
 *
 * 返回 **Dp**，由调用处 `toSp()` 折算 —— [CARD_CHROME_HEIGHT] 是 dp 预算，
 * 标题不能跟着系统字号长。
 *
 * 没用 `BasicText` 的 `autoSize`：那要在测量阶段反复试排，12 张卡片各一次不值得，
 * 而且这 12 个名字是固定的常量，档位一次调好就永远对。
 */
private fun titleFontSizeFor(name: String) = when {
    name.length <= 8 -> 40.dp     // Red · 1989 · Lover · folklore · evermore · Fearless
    name.length <= 14 -> 32.dp    // Speak Now · Midnights · reputation · Taylor Swift
    else -> 22.dp                 // The Tortured Poets Department · The Life of a Showgirl
}
