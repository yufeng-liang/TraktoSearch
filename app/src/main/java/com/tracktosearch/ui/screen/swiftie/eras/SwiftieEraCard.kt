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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
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

/**
 * TTPD 那一张从滚筒里出纸的时长。
 *
 * 比 [CARD_GROW_MS] 长得多是故意的：400ms 揭完 31 行高的一张纸，读作「闪出来」而不是
 * 「卷出来」。1100ms 走完时行揭示正好点到第 8 行（`400 + 130 × 8 ≈ 1440`），
 * 出纸前沿一路跑在打字前面，看上去就是纸先出来、字随后落上去。
 */
const val CARD_FEED_MS: Long = 1_100L

/** 出纸期滚筒暗影的高度。纸刚离开压纸滚筒的那一段是背光的，没有这道影子纸就是「贴」上去的。 */
private val FEED_ROLLER_SHADOW = 14.dp

/**
 * 滚筒暗影与前沿的墨色。
 *
 * 不用纯黑：TTPD 三档底色是米白，纯黑压在米白纸上是一道脏边。这个暖灰是
 * 机身那一档 `INK` 的同族色，读作「机器投在纸上的影子」。
 */
private val FEED_ROLLER_INK = Color(0xFF4A453E)

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

/** 曲目行的理想行高。18 行只有 288dp，绝大多数屏幕都用这个值。 */
private val TRACK_ROW_HEIGHT_MAX = 16.dp

/**
 * 行高下限。
 *
 * 这里**不能**用「宁可让卡片顶出插槽也不再压」的策略：卡片带 `.clip(CARD_SHAPE)`，
 * 顶出去的行不是露在外面，是被剪掉、静默消失。所以下限压到 9dp ——
 * 31 首的 TTPD 至少要 `118 + 31×9 = 397dp` 插槽，比任何在售机型的可用高度都低。
 * 代价是小屏上这张卡的字确实小（约 6.8dp），但总比丢掉最后几首好。
 */
private val TRACK_ROW_HEIGHT_MIN = 9.dp

/**
 * 卡片除曲目列以外占的高度：标题（最多两行）+ 日期 + 两处间距 + 上下内边距。
 *
 * 这是个 dp 常量，所以标题与日期的字号也必须是 dp 折算的（见 [titleFontSizeFor]
 * 与调用处的 `toSp()`）—— 否则系统字号一放大，实际 chrome 就超过这个预算，
 * 曲目列被挤出卡片、被 clip 静默吃掉尾部几行。
 */
private val CARD_CHROME_HEIGHT = 118.dp

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
 * 一个时代的卡片：从轴上长出 → 停留（曲目逐行点亮）→ 回落。
 *
 * 卡片**不随深色模式反色** —— 它是「图」，配色由 [SwiftieEra] 固定（Spec §2.3）。
 *
 * @param eraIndex 这是第几张。只往下传给曲目列判「哪一行要描金」，卡片自己不用
 * @param elapsedInCard 这张卡片起点以来的毫秒
 * @param durationMs 这张卡片分到的总时长，取自 `SwiftieTimeline.cardDurationMs`
 * @param originFractionX 对应色带在轴上的中心比例，用作缩放轴心
 * @param slotHeight 卡片可用的最大高度，由 `SwiftieErasStage` 量出来。曲目行高按它折算
 * @param collapseProgress 序列末尾的卷收：0f = 完整卡片；1f = 只剩专辑名 + 日期 +
 *   描金那一行。**在布局 / 绘制阶段读**，不进组合
 * @param feedProgress 只有 TTPD 传：0f..1f 的出纸进度。非 null 时这张卡片**不从轴上长出**，
 *   改成上缘钉死、下缘自上而下揭示 —— 它是背景那台打字机吐出来的那张纸。
 *   段末的回落仍走统一的 scaleY 收起，纸不卷回机器
 */
@Composable
fun SwiftieEraCard(
    era: SwiftieEra,
    eraIndex: Int,
    elapsedInCard: () -> Long,
    durationMs: Long,
    originFractionX: Float,
    slotHeight: Dp,
    collapseProgress: () -> Float = { 0f },
    feedProgress: (() -> Float)? = null,
    modifier: Modifier = Modifier
) {
    val lowRam = rememberIsLowRamDevice()
    val density = LocalDensity.current
    val titleFont = remember(era.fontResId) { FontFamily(Font(era.fontResId)) }
    // 浅色时代主色印在白卡上读不出来，这里取压暗到 AA 的那一组（见 SwiftieEraContrast）
    val textColors = remember(era) { SwiftieEraTextColors(era) }
    val rowHeight = trackRowHeight(slotHeight, era.tracks.size)
    // 曲目列的 stagger 必须与 `SwiftieEraTracklist` 用的同一个值 —— 让位窗口是按
    // 「第几行正在点亮」算的，两边错开一档，压下去的就是隔壁那一行
    val stagger = if (lowRam) TRACK_STAGGER_LOW_RAM_MS else TRACK_STAGGER_MS
    // era.tracks 是常量列表，「哪几行算长歌名」在组合阶段算一次，draw 阶段只查表
    val longTitles = remember(era) {
        BooleanArray(era.tracks.size) { era.tracks[it].length > LONG_TITLE_CHARS }
    }
    val description = stringResource(
        R.string.swiftie_era_card_a11y,
        era.name,
        era.releaseDate,
        era.tracks.size
    )
    // 回落起点由总时长倒推：durationMs - 400 - 100
    val recedeStartMs = durationMs - CARD_RECEDE_MS - CARD_GAP_MS

    // 出纸那一层。11 张卡片这里是 Modifier 本体，一笔都不多画
    val feedModifier = if (feedProgress == null) {
        Modifier
    } else {
        val shadowPx = with(density) { FEED_ROLLER_SHADOW.toPx() }
        // Brush 在组合阶段建一次。draw lambda 里 new 一个 Brush = 每帧一个原生 Shader
        val roller = remember(shadowPx) {
            Brush.verticalGradient(
                colors = listOf(FEED_ROLLER_INK, Color.Transparent),
                startY = 0f,
                endY = shadowPx
            )
        }
        Modifier.drawWithContent {
            val p = feedProgress().coerceIn(0f, 1f)
            // 前摇那 1400ms 里 p 恒为 0：一笔不画，卡片连阴影都不存在
            if (p <= 0f) return@drawWithContent
            val frontier = size.height * p
            clipRect(bottom = frontier) {
                this@drawWithContent.drawContent()
                if (p >= 1f) return@clipRect
                // 滚筒暗影钉在纸的上缘（出口不动），不跟着前沿走。
                // 前 75% 恒定、末段淡掉 —— 走完那一帧直接抹掉会「啪」一下
                val ink = ((1f - p) * 4f).coerceAtMost(1f)
                drawRect(brush = roller, size = Size(size.width, shadowPx), alpha = ink)
                // 前沿：纸的裁切边。一条实线 + 上方一小片压暗，读作纸有厚度
                drawRect(
                    color = FEED_ROLLER_INK,
                    topLeft = Offset(0f, frontier - shadowPx * 0.34f),
                    size = Size(size.width, shadowPx * 0.34f),
                    alpha = 0.10f
                )
                drawLine(
                    color = FEED_ROLLER_INK,
                    start = Offset(0f, frontier),
                    end = Offset(size.width, frontier),
                    strokeWidth = 1f.coerceAtLeast(size.height * 0.0008f),
                    alpha = 0.30f
                )
                // 两侧进纸导轨：上缘各一个小缺口，纸是从两片导片之间挤出来的
                for (side in 0..1) {
                    val cx = size.width * (if (side == 0) 0.17f else 0.83f)
                    val guideW = shadowPx * 0.30f
                    drawRect(
                        color = FEED_ROLLER_INK,
                        topLeft = Offset(cx - guideW / 2f, 0f),
                        size = Size(guideW, shadowPx * 0.44f),
                        alpha = ink * 0.55f
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                val elapsed = elapsedInCard()
                val recede = EaseInCubic.transform(
                    ((elapsed - recedeStartMs).toFloat() / CARD_RECEDE_MS).coerceIn(0f, 1f)
                )
                if (feedProgress != null) {
                    // 出纸那一张不缩放不淡入 —— 纸是不透明的，揭示交给 [feedModifier] 的 clip。
                    // 段末仍走同一套 scaleY 收起
                    scaleY = 1f - recede
                    alpha = 1f - recede
                } else {
                    // EaseOutBack 会冲过 1 再收回来，读起来像琴键被按下又弹起。
                    // 峰值约 1.1，只持续几帧，12sp 文字的形变看不出来
                    val grow = EaseOutBack.transform(
                        (elapsed.toFloat() / CARD_GROW_MS).coerceIn(0f, 1f)
                    )
                    scaleY = grow * (1f - recede)
                    alpha = (elapsed.toFloat() / (CARD_GROW_MS * 0.6f)).coerceIn(0f, 1f) *
                        (1f - recede)
                }
                // 轴心落在色带上、贴着轴线（y = 1f），才像从那一段长出来
                transformOrigin = TransformOrigin(
                    pivotFractionX = originFractionX.coerceIn(0f, 1f),
                    pivotFractionY = 1f
                )
            }
            // clip 必须夹在 graphicsLayer 与 shadow 之间：放到 shadow 之后，
            // 那圈 10dp 的投影会整张浮在纸还没出来的地方
            .then(feedModifier)
            .shadow(elevation = 10.dp, shape = CARD_SHAPE, clip = false)
            .clip(CARD_SHAPE)
            // 半透明白纸压在水彩天空上；主色只染一层薄底
            .background(Color.White.copy(alpha = 0.86f))
            .drawBehind {
                drawRect(color = era.mainColor, alpha = 0.10f)
                // 低端机整块母题定格（Spec §11.2）。关键是这条分支**根本不读时钟** ——
                // 只要读了 elapsedInCard()，这个 drawBehind 就会每帧失效，
                // 母题里那一堆 Path / Brush 也就每帧重建一次。让位系数同理定在 1f，
                // 读一下就把整块定格的意义抹掉了
                if (lowRam) {
                    drawEraMotif(
                        motif = era.motif,
                        color = era.mainColor,
                        phase = 0f,
                        lowRam = true,
                        columnFade = 1f
                    )
                } else {
                    val elapsed = elapsedInCard()
                    drawEraMotif(
                        motif = era.motif,
                        color = era.mainColor,
                        phase = (elapsed.mod(MOTIF_CYCLE_MS)).toFloat() / MOTIF_CYCLE_MS,
                        lowRam = false,
                        // 长歌名会横穿右侧那一列，那几行点亮时道具让位（见 columnFadeAt）
                        columnFade = columnFadeAt(elapsed, longTitles, stagger)
                    )
                }
            }
            // 卡片整体一条 contentDescription；曲目列自己对 TalkBack 隐身（Spec §6.2）。
            // mergeDescendants 是必须的 —— 少了它，下面的专辑名与日期两个 Text
            // 仍是各自可停靠的叶子，contentDescription 里的曲目数反而无处可去
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            // 1989 的标题不走字体：封面上那四个数字是马克笔直接写的，
            // 辨识特征是干笔的空隙，而字体的笔画是实心的（见 SwiftieMarker1989）
            if (era.name == "1989") {
                SwiftieMarker1989(
                    color = textColors.body,
                    capHeight = titleFontSizeFor(era.name),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
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
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = era.releaseDate,
                style = TextStyle(
                    fontSize = with(density) { 11.dp.toSp() },
                    color = textColors.date.copy(alpha = SwiftieEraContrast.DATE_ALPHA)
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            SwiftieEraTracklist(
                era = era,
                eraIndex = eraIndex,
                textColors = textColors,
                rowHeight = rowHeight,
                elapsedInCard = elapsedInCard,
                collapseProgress = collapseProgress,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 算「长歌名」的字符数门槛。
 *
 * 28 字以上的曲目名在这个字号下会一路排到卡片右缘，压在道具那一列上。
 * 最长的几个：`Miss Americana & The Heartbreak Prince`（Lover 第 7 首）、
 * `We Are Never Ever Getting Back Together`（Red）、
 * `Chloe or Sam or Sophia or Marcus`（TTPD）。
 */
private const val LONG_TITLE_CHARS = 28

/** 让位的进出斜坡。 */
private const val COLUMN_YIELD_RAMP_MS = 150f

/** 让位按住的时长。 */
private const val COLUMN_YIELD_HOLD_MS = 420f

/**
 * 右侧道具列的让位系数：长歌名那一行点亮前后把道具压到 [COLUMN_FADE_MIN]。
 *
 * 窗口是「该行点亮时刻 − 150ms 起、按住 420ms、再 150ms 抬回来」，
 * 而不是只在那一行的 130ms 行距里压一下 —— 那样读起来是道具闪了一下。
 *
 * 只回看 5 行：整个窗口 720ms，130ms 一行，`720 ÷ 130 ≈ 5.5`，
 * 所以**定长循环**就够，不用每帧扫 31 行。低端机不走这里（母题整块定格）。
 */
private fun columnFadeAt(elapsed: Long, longTitles: BooleanArray, stagger: Long): Float {
    val cursor = ((elapsed - TRACK_REVEAL_START_MS) / stagger).toInt()
    var strongest = 0f
    for (index in (cursor - 5)..(cursor + 1)) {
        if (index < 0 || index >= longTitles.size || !longTitles[index]) continue
        val since = (elapsed - (TRACK_REVEAL_START_MS + stagger * index)).toFloat()
        val envelope = when {
            since < -COLUMN_YIELD_RAMP_MS -> 0f
            since < 0f -> 1f + since / COLUMN_YIELD_RAMP_MS
            since < COLUMN_YIELD_HOLD_MS -> 1f
            since < COLUMN_YIELD_HOLD_MS + COLUMN_YIELD_RAMP_MS ->
                1f - (since - COLUMN_YIELD_HOLD_MS) / COLUMN_YIELD_RAMP_MS
            else -> 0f
        }
        if (envelope > strongest) strongest = envelope
    }
    return 1f - (1f - COLUMN_FADE_MIN) * strongest
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
    name.length <= 8 -> 34.dp     // Red · 1989 · Lover · folklore · evermore · Fearless
    name.length <= 14 -> 27.dp    // Speak Now · Midnights · reputation · Taylor Swift
    else -> 18.dp                 // The Tortured Poets Department · The Life of a Showgirl
}
