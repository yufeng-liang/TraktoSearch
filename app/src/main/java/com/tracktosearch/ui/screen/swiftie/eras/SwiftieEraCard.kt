package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import com.tracktosearch.ui.screen.swiftie.rememberEncoreGlitterBrush
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice
import com.tracktosearch.ui.screen.swiftie.rememberShowgirlGlitterBrush
import java.util.Locale

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

/**
 * TTPD 卡片给底部那台打字机让出的高度。
 *
 * 机器画在背景层、卡片正下方：出纸口坐在卡片下缘上。这个值必须盖住机器
 * 「滚筒 + 暗腔 + 两肩」那一段（键盘越往下越被时间轴挡住，不要求全露），
 * 布局层用它把卡片从插槽底抬起来（见 `SwiftieErasStage`）。
 */
internal val TTPD_MACHINE_RESERVE = 104.dp

/** 出纸期滚筒暗影的高度。纸刚离开压纸滚筒的那一段是背光的，没有这道影子纸就是「贴」上去的。 */
private val FEED_ROLLER_SHADOW = 14.dp

/**
 * 出纸那张卡片的揭示裁切往右多留的比例（相对卡宽）。
 *
 * 右下角那支羽毛笔**长出卡片右缘**：笔尖行到行尾时墨右缘在 0.94 卡宽，羽尖还要再往外
 * ≈142px（真机上量的），合计伸出卡外约 65px（真机 1440 宽、卡宽 1292、卡缘到屏边 73px）。
 * 裁切卡在 `size.width` 就把伸出去的那截羽面切平了，所以右边界放到卡外。
 * 竖向不动，仍是「纸的前沿」，笔不会比纸先露。
 */
private const val FEED_CLIP_OVERHANG_FRACTION = 0.10f

/**
 * 滚筒暗影与前沿的墨色。
 *
 * 不用纯黑：TTPD 三档底色是米白，纯黑压在米白纸上是一道脏边。这个暖灰是
 * 机身那一档 `INK` 的同族色，读作「机器投在纸上的影子」。
 */
private val FEED_ROLLER_INK = Color(0xFF4A453E)

/** 停留供阅读。Lover 另有 `CARD_ANCHOR_BONUS_MS`，由 `durationMs` 带进来。 */
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
private val CARD_CORNER = 20.dp

/** 卡片左右内边距。标题的可用宽度、续章字号的判据都要从卡宽里扣掉它。 */
private val CARD_HORIZONTAL_PADDING = 18.dp

/**
 * 卡片上下内边距。
 *
 * 提成常量是因为它不只影响观感：Speak Now 的花束领地两头都挂在它上面 —— 上缘就是
 * 内容首行（专辑名那一行）的上缘，下界要从卡片底边往上倒推曲目 Row 的上缘。
 * 写死两处，改内边距就会让花束和字错位。
 */
private val CARD_VERTICAL_PADDING = 16.dp

private val CARD_SHAPE = RoundedCornerShape(CARD_CORNER)

/** 曲目行的理想行高。多数屏幕都用这个值，长专辑按可用高度压行。 */
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
 * [trackCount] 是**较长那一栏的行数**（`SwiftieEra.columnRowCount`），不是曲目总数：
 * 卡片高度由那一栏决定，用总数会把拆了栏的 Speak Now 白压一半。
 *
 * TTPD 用 Anthology 版 31 首，16dp 行高要 496dp —— 大屏放得下，360×640 的小屏放不下。
 * 这里只在放不下时才压，所以其余各张在大屏上与压缩前逐像素一致。
 */
private fun trackRowHeight(slotHeight: Dp, trackCount: Int): Dp {
    if (trackCount <= 0) return TRACK_ROW_HEIGHT_MAX
    return ((slotHeight - CARD_CHROME_HEIGHT) / trackCount)
        .coerceIn(TRACK_ROW_HEIGHT_MIN, TRACK_ROW_HEIGHT_MAX)
}

/**
 * 曲目列拆成哪几段。`internal` 只为让单测能扫一遍 12 张确认没有曲目被拆丢。
 *
 * 没拆栏的 11 张返回**一段完整的 indices** —— 卡片那支 `Row` 里就只有一个 weight(1f)
 * 的子栏，宽度与拆栏前那个 `Modifier.fillMaxWidth()` 相同，一帧都不差。
 */
internal fun trackColumnRanges(era: SwiftieEra): List<IntRange> {
    val split = era.leftColumnRows
    return if (split in 1 until era.tracks.size) {
        listOf(0 until split, split until era.tracks.size)
    } else {
        listOf(era.tracks.indices)
    }
}

/** 两栏之间的栏距。 */
private val TRACK_COLUMN_GUTTER = 12.dp

/**
 * 拆栏时左栏占曲目列宽度的比例。
 *
 * 不是 0.5：Speak Now 左栏最长的是 "Better than Revenge"（19 字），右栏最长的
 * "When Emma Falls in Love" 后面还要再挂一枚 TV 标签。对半分会把右栏那一行打上省略号，
 * 而专辑目录上出现省略号比两栏不等宽难看得多。
 */
private const val TRACK_LEFT_COLUMN_FRACTION = 0.44f

/**
 * 一个时代的卡片：从轴上长出 → 停留（曲目随卡片一次性出现，TTPD 那台打字机逐行打）→ 回落。
 *
 * 卡片**不随深色模式反色** —— 它是「图」，配色由 [SwiftieEra] 固定（Spec §2.3）。
 *
 * @param eraIndex 这是第几张。只往下传给曲目列判「哪一行要描金」，卡片自己不用
 * @param elapsedInCard 这张卡片起点以来的毫秒
 * @param durationMs 这张卡片分到的总时长，取自 `SwiftieTimeline.cardDurationMs`
 * @param originFractionX 对应色带在轴上的中心比例，用作缩放轴心
 * @param slotHeight 卡片可用的最大高度，由 `SwiftieErasStage` 量出来。曲目行高按它折算
 * @param slotWidth 卡片可用的最大宽度，由 `SwiftieErasStage` 量出来（已扣掉页面左右内边距）。
 *   续章标题的字号按它分档 —— 全串 `THE LIFE OF A SHOWGIRL: THE ENCORE` 在窄屏上
 *   22dp 放不下会折行，而折行会吃掉 [CARD_CHROME_HEIGHT] 的预算把曲目列挤扁
 * @param collapseProgress 序列末尾的卷收：0f = 完整卡片；1f = 只剩专辑名 + 日期 +
 *   描金那一行。**在布局 / 绘制阶段读**，不进组合
 * @param feedProgress 只有 TTPD 传：0f..1f 的出纸进度。非 null 时这张卡片**不从轴上长出**，
 *   改成下缘钉死在机器出纸口上、上缘自下而上揭示 —— 它是屏幕底下那台打字机吐上来的
 *   那张纸，先打的那行（第 1 首）在纸的最上头，歌名从上到下读下去正是打字的顺序。
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
    slotWidth: Dp = 320.dp,
    collapseProgress: () -> Float = { 0f },
    feedProgress: (() -> Float)? = null,
    loverAimAngle: () -> Float = { LOVER_FALLBACK_AIM_ANGLE },
    modifier: Modifier = Modifier
) {
    val lowRam = rememberIsLowRamDevice()
    val density = LocalDensity.current
    val titleFont = remember(era.fontResId) { FontFamily(Font(era.fontResId)) }
    // 浅色时代主色印在白卡上读不出来，这里取压暗到 AA 的那一组（见 SwiftieEraContrast）
    val textColors = remember(era) { SwiftieEraTextColors(era) }
    // 只在序列末尾开始回退时切换 Lover 的彩蛋日期；用布尔 derivedState 避免回退期间逐帧重组卡片。
    val rewinding = remember(collapseProgress) {
        derivedStateOf { collapseProgress() > 0.0001f }
    }
    val cardFill = remember(era.mainColor) { SwiftieEraContrast.cardFill(era.mainColor) }
    // 行高按**较长那一栏**的行数折算，不是曲目总数：拆了两栏的 Speak Now 因此
    // 能保住 16dp 的行高，而卡片矮到露出背景的教堂长椅（见 `SwiftieEra.columnRowCount`）
    val rowHeight = trackRowHeight(slotHeight, era.columnRowCount)
    // 让位窗口只服务 TTPD：其余 11 张曲目随卡片一次性出现，没有「某一行正在点亮」
    // 这件事，道具列不该再让位（columnFade 恒 1f）。
    val stagger = if (eraIndex == SwiftieTimeline.TTPD_INDEX && !lowRam) {
        SwiftieTimeline.TTPD_TRACK_REVEAL_MS.toFloat() / era.tracks.size
    } else {
        (if (lowRam) TRACK_STAGGER_LOW_RAM_MS else TRACK_STAGGER_MS).toFloat()
    }
    // era.tracks 是常量列表，「哪几行算长歌名」在组合阶段算一次，draw 阶段只查表
    val longTitles = remember(era) {
        BooleanArray(era.tracks.size) { era.tracks[it].length > LONG_TITLE_CHARS }
    }
    // 母题的照片素材（Red 的围巾、evermore 的背影、Speak Now 的花束、1989 的拍立得照，
    // 其余母题仍是线画）。位图只能在组合阶段读，draw 阶段（drawBehind）拿不到 resources；
    // 别的时代这里恒为 null，那 8 张卡片因此连一次解码都不做
    val photoRes = when (era.motif) {
        SwiftieEraMotif.SPEAK_NOW_BOUQUET -> R.drawable.era_speak_now_bouquet
        SwiftieEraMotif.RED_SCARF -> R.drawable.era_red_scarf
        SwiftieEraMotif.BRAID_PLAID -> R.drawable.era_evermore_back
        SwiftieEraMotif.POLAROID -> R.drawable.era_1989_polaroid
        else -> null
    }
    val propPhoto = photoRes?.let { ImageBitmap.imageResource(it) }
    // 杯套上那片刻线枫叶（参考图取墨，见 [MapleArt]）。与 propPhoto 同一条门控：
    // 只有 Red 解这一张 14.6KB 的墨线图，另外 11 张卡片连这次解码都不做
    val propMapleInk = if (era.motif == SwiftieEraMotif.RED_SCARF) rememberMapleInkArt() else null
    // 画布上排字用的测量器：只有 Red 的纸套要印一行 MAPLE LATTE。与照片同理，
    // 文字只能在组合阶段量；其余 11 张卡拿到这个对象也不会去量
    val textMeasurer = rememberTextMeasurer()
    val description = stringResource(
        R.string.swiftie_era_card_a11y,
        era.name,
        era.releaseDate,
        era.tracks.size
    )
    // 回落起点由总时长倒推：durationMs - 400 - 100
    val recedeStartMs = durationMs - CARD_RECEDE_MS - CARD_GAP_MS

    // 正文那一层的裁切：出纸那张（TTPD）在节点上不裁形状（好让羽毛笔长出卡片右缘，
    // 见下面 `.then(...)` 那一段），圆角就由这里补上 —— 正文自己不会伸到圆角外，
    // 这一刀只是把「文字/高亮贴到圆角上」那点毛边留住。其余 11 张节点已裁，这里是本体
    val cardContentClip = if (feedProgress == null) Modifier else Modifier.clip(CARD_SHAPE)

    // 出纸那一层。11 张卡片这里是 Modifier 本体，一笔都不多画
    val feedModifier = if (feedProgress == null) {
        Modifier
    } else {
        val shadowPx = with(density) { FEED_ROLLER_SHADOW.toPx() }
        // Brush 在组合阶段建一次。draw lambda 里 new 一个 Brush = 每帧一个原生 Shader。
        // 渐变方向反过来：出口在**下缘**（机器在底下），影子越往下越深
        val roller = remember(shadowPx) {
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, FEED_ROLLER_INK),
                startY = 0f,
                endY = shadowPx
            )
        }
        Modifier.drawWithContent {
            val p = feedProgress().coerceIn(0f, 1f)
            // 打字机前摇里 p 恒为 0：一笔不画，卡片连阴影都不存在
            if (p <= 0f) return@drawWithContent
            // 纸从底下那台机器里**往上**出来：下缘钉死在出口上，前沿（纸的上边）上移
            val frontier = size.height * (1f - p)
            // 右边界放到卡片外：这一张的羽毛笔要**长出卡片右缘**（见 `drawLetterQuill`），
            // 裁在 `size.width` 就把伸出去的那截羽面切平了。竖向仍是纸的前沿 ——
            // 截出来是一条横带，笔在纸没到它那一行之前照样不露
            clipRect(top = frontier, right = size.width * (1f + FEED_CLIP_OVERHANG_FRACTION)) {
                this@drawWithContent.drawContent()
                if (p >= 1f) return@clipRect
                // 滚筒暗影钉在纸的下缘（出口不动），不跟着前沿走。
                // 前 75% 恒定、末段淡掉 —— 走完那一帧直接抹掉会「啪」一下
                val ink = ((1f - p) * 4f).coerceAtMost(1f)
                drawRect(
                    brush = roller,
                    topLeft = Offset(0f, size.height - shadowPx),
                    size = Size(size.width, shadowPx),
                    alpha = ink
                )
                // 前沿：纸的裁切边。一条实线 + 下方一小片压暗，读作纸有厚度
                drawRect(
                    color = FEED_ROLLER_INK,
                    topLeft = Offset(0f, frontier),
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
                // 两侧进纸导轨：下缘各一个小缺口，纸是从两片导片之间挤出来的
                for (side in 0..1) {
                    val cx = size.width * (if (side == 0) 0.17f else 0.83f)
                    val guideW = shadowPx * 0.30f
                    drawRect(
                        color = FEED_ROLLER_INK,
                        topLeft = Offset(cx - guideW / 2f, size.height - shadowPx * 0.44f),
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
                    // 2026-09-18：去掉 EaseOutBack 的过冲。卡片一次长大到位，
                    // 不再冲过 1 再收回来的回弹。EaseOutCubic 单调收敛，仍保留起步快、
                    // 收尾稳的手感
                    val grow = EaseOutCubic.transform(
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
            // **出纸那一张不投影**。10dp 的环境投影绕着圆角有一圈，被不透明的纸盖住之后
            // 只剩四个圆角外面露着 —— 四块三角暗边，读作脏（半透明白纸时它把整张纸一起
            // 染灰，谁也没比谁干净，所以看不出来）。这一张也不需要它：纸的下缘压在滚筒上，
            // 接触暗影由 [feedModifier] 画，其余三边是真纸压在真机器上，没有离地
            .then(
                if (feedProgress == null) {
                    Modifier.shadow(elevation = 10.dp, shape = CARD_SHAPE, clip = false)
                } else {
                    Modifier
                }
            )
            // **出纸那一张（TTPD）不裁卡片形状**：它右下角那支羽毛笔要长出卡片右缘
            // （笔尖行到行尾时羽面越过卡缘，见 `drawLetterQuill`），裁一刀就把羽面切平了。
            // 圆角白底改由 `background(shape)` 自己画，正文那一层另外裁（[cardContentClip]）。
            // 其余 11 张照旧 clip + 矩形白底，一笔都不变
            .then(
                if (feedProgress == null) {
                    Modifier
                        .clip(CARD_SHAPE)
                        // 先合成时代主色薄染，整张卡片使用同一实色，避免圆角边缘透出更浅颜色。
                        .background(cardFill)
                } else {
                    // 出纸卡片也使用同一实色；滚筒与机器阴影仍由 feedModifier 单独绘制。
                    Modifier.background(cardFill, CARD_SHAPE)
                }
            )
            .drawBehind {
                // 曲目 Row 的上缘。卡片内容是「上内边距 + 标题那一截 + 曲目 Row + 下内边距」，
                // 而 Row 高 = columnRowCount × 行高，所以 Row 的上缘能从卡片底边**倒推**出来 ——
                // 不需要 onLocationChanged 量一次再回灌，那正是要避免的自锁。
                val rowTop = (
                    size.height - CARD_VERTICAL_PADDING.toPx() -
                        era.columnRowCount * rowHeight.toPx()
                    ).coerceAtLeast(0f)
                // 带的左右就是右栏那一栏的左右：花束和 16–22 行是同栏的上下两截，
                // 领地必须共用同一个横向区间，不然花束会贴到文字列之外去。
                // Row 是 fillMaxWidth + 等宽间隙，两栏按 0.44/0.56 分掉
                // 「内容宽 − 间隙」，所以右栏右缘恒等于「卡宽 − 左右内边距」
                val rightColWidth =
                    (size.width - CARD_HORIZONTAL_PADDING.toPx() * 2f - TRACK_COLUMN_GUTTER.toPx()) *
                        (1f - TRACK_LEFT_COLUMN_FRACTION)
                val bandRight = size.width - CARD_HORIZONTAL_PADDING.toPx()
                // 花束的领地：横向 = 右栏；纵向 = **从专辑名那一行的上缘**一直到
                // 「Row 上缘 + propRowBand 行」。
                // 布局那边只留 propRowBand 行（右栏 16–22 因此与左栏 09–15 齐底），
                // 但标题与日期只吃卡片左半边，右半边这一整条空档借给花束长高 ——
                // 于是花束尺寸不再由「两栏要留几行」这一格钳死，两件事各自成立。
                // 顶部就压在标题那一行的上缘：这是需求方指定的上限（花束顶到专辑名这一行），
                // 再往上就是卡片的圆角与内边距了
                val propBand = Rect(
                    left = bandRight - rightColWidth,
                    top = CARD_VERTICAL_PADDING.toPx(),
                    right = bandRight,
                    bottom = rowTop + era.propRowBand * rowHeight.toPx()
                )
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
                        columnFade = 1f,
                        // -1 = 定格档。Lover 那把弓因此永远停在松弦上着箭的静态，
                        // 读一下真时钟就把整块定格的意义抹掉了
                        eraElapsedMs = -1L,
                        loverAimAngle = loverAimAngle(),
                        propPhoto = propPhoto,
                        textMeasurer = textMeasurer,
                        propMapleInk = propMapleInk,
                        propBand = propBand
                    )
                } else {
                    val elapsed = elapsedInCard()
                    drawEraMotif(
                        motif = era.motif,
                        color = era.mainColor,
                        phase = (elapsed.mod(MOTIF_CYCLE_MS)).toFloat() / MOTIF_CYCLE_MS,
                        lowRam = false,
                        // 只有 TTPD 逐行打字需要长歌名让位；其余 11 张恒 1f，
                        // 曲目一次性显示，长名照常 Ellipsis，道具不让位
                        columnFade = if (eraIndex == SwiftieTimeline.TTPD_INDEX) {
                            columnFadeAt(elapsed, longTitles, stagger)
                        } else {
                            1f
                        },
                        eraElapsedMs = elapsed,
                        loverAimAngle = loverAimAngle(),
                        propPhoto = propPhoto,
                        textMeasurer = textMeasurer,
                        propMapleInk = propMapleInk,
                        propBand = propBand
                    )
                }
            }
            // 卡片整体一条 contentDescription；曲目列自己对 TalkBack 隐身（Spec §6.2）。
            // mergeDescendants 是必须的 —— 少了它，下面的专辑名与日期两个 Text
            // 仍是各自可停靠的叶子，contentDescription 里的曲目数反而无处可去
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Column(modifier = cardContentClip.padding(horizontal = CARD_HORIZONTAL_PADDING, vertical = CARD_VERTICAL_PADDING)) {
            // 1989 的标题不走字体：封面上那四个数字是马克笔直接写的，
            // 辨识特征是干笔的空隙，而字体的笔画是实心的（见 SwiftieMarker1989）
            if (era.name == "1989") {
                SwiftieMarker1989(
                    color = textColors.body,
                    capHeight = titleFontSizeFor(era),
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (eraIndex == SwiftieErasData.SHOWGIRL_INDEX) {
                // Showgirl 的标题是**两段不同材质的闪粉**拼在同一行：专辑名是官方封面
                // 那圈橙红闪粉，续章那句 `: THE ENCORE` 是金闪粉（参考图里就是金色）。
                // 走 AnnotatedString + SpanStyle(brush)：ui-text 的 SpanStyle 有 Brush
                // 重载，所以同一段文字的两个 range 能各绑一块贴板；用两个并排的 Text 拼
                // 会让基线、字距、折行各自为政，而这是斜体压缩字，接缝一眼看得出。
                SwiftieShowgirlTitle(
                    eraName = era.name,
                    encoreReveal = { SwiftieTimeline.encoreInkProgress(elapsedInCard()) },
                    innerWidth = slotWidth - CARD_HORIZONTAL_PADDING * 2,
                    density = density,
                    elapsedInCard = elapsedInCard,
                    lowRam = lowRam,
                    // 尘埃的起点要摊在**整张卡**上：卡片内容高 = chrome 预算 + 行数 × 行高
                    // （与 `trackRowHeight` 同一个口径）
                    cardContentHeight = CARD_CHROME_HEIGHT + rowHeight * era.columnRowCount,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // 字号用 toSp() 除掉 fontScale：CARD_CHROME_HEIGHT 是 dp 预算，
                // 标题跟着系统字号长就会把曲目列挤出卡片
                val titleSize = titleFontSizeFor(era)
                val titleSp = with(density) { titleSize.toSp() }
                // 闪粉只铺在上面那一层。底下那圈实色描边一手管加粗、一手把字缘钉在
                // textColors.body 上 —— 标题读不读得清不该去赌闪粉颗粒的疏密分布
                val titleBrush = if (era.titleGlitter) rememberShowgirlGlitterBrush() else null
                // brush 与 color 是 TextStyle 两个**互斥**的构造重载，不能同时传（传了就
                // 两个候选都不匹配，编译直接失败）：有贴板时颜色归贴板，没有才走实色
                val titleStyle = if (titleBrush == null) {
                    TextStyle(
                        fontFamily = titleFont,
                        fontSize = titleSp,
                        color = textColors.body
                    )
                } else {
                    TextStyle(
                        brush = titleBrush,
                        fontFamily = titleFont,
                        fontSize = titleSp
                    )
                }
                if (era.titleStrokeEm > 0f) {
                    // 合成加粗：同一段文字画两遍，底下那一层只描边、上面那一层实心。
                    // 描边加在**细笔画上的比例比粗笔画大**，所以出来是「更实」而不是整体放大
                    // —— 这是没有粗体字重时唯一不动字形轮廓的加法（12 套字库多数只有 Regular）。
                    // 两层同字号、同换行规则、同宽约束，量出来的行盒一致，Box 只是把它们叠起来
                    //
                    // 描边那一层另建一个实色 style，不从 titleStyle 上 copy：这个版本的
                    // copy() 没有 brush 参数，而 copy 过来的 brush 会让垫在下面的也变成闪粉，
                    // 字缘就没有实色撑着
                    val boldStyle = TextStyle(
                        fontFamily = titleFont,
                        fontSize = titleSp,
                        color = textColors.body,
                        drawStyle = Stroke(
                            width = with(density) { (titleSize * era.titleStrokeEm).toPx() },
                            join = StrokeJoin.Round,
                            cap = StrokeCap.Round
                        )
                    )
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = era.name,
                            style = boldStyle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = era.name,
                            style = titleStyle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Text(
                        text = era.name,
                        style = titleStyle,
                        // 允许折两行：13 个字体的字宽差得很远，按字数估的字号可能还是偏大，
                        // 折行总比裁掉专辑名好；真的还放不下就省略号，别硬切字形
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            // 日期的字面逐张定：TTPD 那一张跟着曲目列用打字机字体（见 `dateFontResId`），
            // 其余 11 张是系统默认。null 时不传 fontFamily，与改动前逐像素一致
            val dateFamily = era.dateFontResId?.let { res ->
                remember(res) { FontFamily(Font(res)) }
            }
            Text(
                text = swiftieEncoreDateLabel(
                    eraIndex = eraIndex,
                    releaseDate = swiftieEraDateLabel(
                        eraIndex = eraIndex,
                        releaseDate = era.releaseDate,
                        rewinding = rewinding.value
                    ),
                    encoreDateProgress = SwiftieTimeline.encoreDateProgress(elapsedInCard())
                ),
                style = TextStyle(
                    fontFamily = dateFamily,
                    fontSize = with(density) { 11.dp.toSp() },
                    color = textColors.date.copy(alpha = SwiftieEraContrast.DATE_ALPHA)
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            // 曲目列。11 张是一栏通铺（ranges 只有一段、weight 恒 1f，与拆栏前逐像素
            // 一致）；Speak Now 拆两栏，为的是让卡片矮到露出背景教堂的第一排长椅
            val ranges = trackColumnRanges(era)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TRACK_COLUMN_GUTTER)
            ) {
                ranges.forEachIndexed { slot, range ->
                    val weight = when {
                        ranges.size < 2 -> 1f
                        slot == 0 -> TRACK_LEFT_COLUMN_FRACTION
                        else -> 1f - TRACK_LEFT_COLUMN_FRACTION
                    }
                    // 带道具的那一栏顶部先空出 [SwiftieEra.propRowBand] 行给花束。
                    // 这一格只管**排布**（它决定两栏底边齐不齐），花束画多大另有来源：
                    // 它的领地往上一直借到专辑名那一行的上缘（见 drawBehind 里那个 `propBand`），
                    // 所以放大花束不必把两栏重新挤开 —— 也不需要把绘制结果回灌给布局
                    val carriesProp = slot == ranges.lastIndex && era.propRowBand > 0
                    Column(modifier = Modifier.weight(weight)) {
                        if (carriesProp) {
                            Spacer(modifier = Modifier.height(rowHeight * era.propRowBand))
                        }
                        SwiftieEraTracklist(
                            era = era,
                            eraIndex = eraIndex,
                            textColors = textColors,
                            rowHeight = rowHeight,
                            elapsedInCard = elapsedInCard,
                            collapseProgress = collapseProgress,
                            range = range,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/** Lover 初次展示真实发行日期，序列末尾回退时才显示 TS7。 */
internal fun swiftieEraDateLabel(
    eraIndex: Int,
    releaseDate: String,
    rewinding: Boolean
): String = if (eraIndex == SwiftieErasData.LOVER_INDEX && rewinding) "TS7" else releaseDate

/**
 * Showgirl 续章的日期：**翻页那一刻**才从原版发行日跳到 The Encore 的发行日。
 *
 * 与 Lover 那处（回退时显示 TS7）同一个套路：`releaseDate` 字段始终是**原版**发行日，
 * 新日期是「续章状态下」的显示值。这样 `SwiftieErasDataTest` 的发行顺序断言、
 * 以及卡片外的任何消费方都不受续章影响。
 *
 * 翻页在 [SwiftieTimeline.encoreDateProgress] 过半时发生 —— 那一拍是 400ms 的淡换，
 * 硬切会读成闪一下。
 */
internal fun swiftieEncoreDateLabel(
    eraIndex: Int,
    releaseDate: String,
    encoreDateProgress: Float
): String = if (eraIndex == SwiftieErasData.SHOWGIRL_INDEX && encoreDateProgress >= 0.5f) {
    SwiftieErasData.ENCORE_RELEASE_DATE
} else {
    releaseDate
}

/**
 * 续章标题的候选字号，从大到小。取**第一个固有宽度放得下**的那档。
 *
 * 上限 23dp 是**真机实测反推**的，不是算出来的：需求方要「专辑名字号稍微大一点」，
 * 而 22dp 在真机（480dp 屏、卡片内宽 404dp）上已经占掉 **88%** 的内宽 ——
 * 离线用 fontTools 的 advance 算只得到 72%，真机的字距与 hinting 让实际排版宽了
 * 一截。按实测比例外推：23dp 占 92%、24dp 占 96%、26dp 会超出内宽被省略号截断
 * （26dp 那版真机出来是 `: THE E...`）。需求方看过 24dp 之后定在 **23dp**：
 * 24dp 那 96% 太贴边，窄一点的机器上就没有余量了。
 *
 * 为什么不按屏宽分档：全串 `THE LIFE OF A SHOWGIRL: THE ENCORE` 在窄屏
 * （360dp、内宽 284dp）上连 22dp 都放不下，会折行；而折行会吃掉
 * [CARD_CHROME_HEIGHT] 的预算把曲目列挤扁。所以**在运行时量真实排版结果**、
 * 逐档往下试才是权威判据 —— 窄屏自动落到 20/21dp、宽屏用满 23dp，两端都不折行。
 */
private val SHOWGIRL_TITLE_SIZES = listOf(23.dp, 22.dp, 21.dp, 20.dp, 19.dp, 18.dp)

/** 续章那半句的字面。全大写，与参考图一致。 */
internal const val SHOWGIRL_ENCORE_TITLE: String = ": THE ENCORE"

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
 * 而不是只在那一行的行距里压一下 —— 那样读起来是道具闪了一下。
 *
 * 只回看 5 行：整个窗口 720ms，一行约 227ms（TTPD 7030 / 31），`720 ÷ 227 ≈ 3.2`，
 * 所以**定长循环**就够，不用每帧扫 31 行。低端机不走这里（母题整块定格）。
 */
private fun columnFadeAt(elapsed: Long, longTitles: BooleanArray, stagger: Float): Float {
    val cursor = ((elapsed - TRACK_REVEAL_START_MS) / stagger).toInt()
    var strongest = 0f
    for (index in (cursor - 5)..(cursor + 1)) {
        if (index < 0 || index >= longTitles.size || !longTitles[index]) continue
        val since = elapsed - (TRACK_REVEAL_START_MS + stagger * index)
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
 *
 * 档位按**字符数**分，所以它量不到「字面的实际大小」：全小写的词（reputation）
 * x-height 比同档的大写标题矮一截，排出来看着小一号。那种情况由
 * [SwiftieEra.titleSizeOverride] 逐张补，不在这里为某一张改档位。
 */
private fun titleFontSizeFor(era: SwiftieEra) = era.titleSizeOverride ?: when {
    era.name.length <= 8 -> 34.dp     // Red · 1989 · Lover · folklore · evermore · Fearless
    era.name.length <= 14 -> 27.dp    // Speak Now · Midnights · reputation · Taylor Swift
    else -> 18.dp                     // The Tortured Poets Department · The Life of a Showgirl
}

/**
 * Showgirl 的标题：**两段不同材质的真闪粉**拼在同一行。
 *
 * 专辑名是官方封面那圈橙红闪粉，续章那句 `: THE ENCORE` 是金闪粉（参考图上就是金色）。
 * 两段都用 `SpanStyle(brush = …)` 各绑一块 320px 真颗粒贴板 —— ui-text 的 `SpanStyle`
 * 有 `Brush` 重载，所以同一段文字的两个 range 可以各走一个 `ShaderBrush`。
 *
 * ## 为什么不用两个并排的 `Text`
 *
 * 那是更直观的写法，但两段会各自排版：基线、字距、折行规则全部分家，而这是**斜体压缩**
 * 字（Barlow Condensed Bold Italic），斜体的字面本身在行内是斜的，两段拼接处的缝隙
 * 与基线错位一眼就能看出。走 `AnnotatedString` 则整串一次排版，接缝由排版引擎负责。
 *
 * ## 续章那半句为什么用 alpha 而不是「晚点再画」
 *
 * 它**从一开始就占着版面**，只是 alpha 随续章推进从 0 涨到 1。这样标题的折行位置在
 * 整段续章里一次都不变 —— 换成「等续章开始再追加」，标题会在尘埃聚字的瞬间重新折行，
 * 而那时屏幕上正有上百粒尘埃在往字形的落点上飞，整体跳一下极难看。
 *
 * @param encoreReveal 续章那半句的不透明度 0f..1f。原版展示期恒 0f
 * @param innerWidth 卡片内宽（已扣左右内边距），字号按它选
 */
@Composable
private fun SwiftieShowgirlTitle(
    eraName: String,
    encoreReveal: () -> Float,
    innerWidth: Dp,
    density: Density,
    elapsedInCard: () -> Long,
    lowRam: Boolean,
    cardContentHeight: Dp,
    modifier: Modifier = Modifier
) {
    val titleFont = remember { FontFamily(Font(R.font.era_showgirl)) }
    val orangeBrush = rememberShowgirlGlitterBrush()
    val goldBrush = rememberEncoreGlitterBrush()
    val measurer = rememberTextMeasurer()
    // 官方写法是全大写，而 eraName 保留官方大小写（a11y 与数据都用它），
    // 所以大写只在这一层做
    val upperName = remember(eraName) { eraName.uppercase(Locale.US) }
    val fullText = upperName + SHOWGIRL_ENCORE_TITLE
    val maxWidthPx = with(density) { innerWidth.roundToPx() }.coerceAtLeast(1)

    // 字号**量出来**：取第一个排得下一行的档。
    //
    // **量的时候不能给 maxWidth 约束**：`TextMeasurer.measure` 会把结果尺寸钳到约束上，
    // 于是 `layout.size.width <= maxWidthPx` 恒真、永远选中最上一档，标题就被
    // 省略号截断（真机上撞到过：26dp 那版出来是 `: THE E...`）。
    // 要的是**固有宽度**，所以用无约束的 Constraints，再拿它跟可用宽度比。
    val chosen = remember(measurer, maxWidthPx, fullText, titleFont, density) {
        fun measureAt(size: Dp) = measurer.measure(
            text = AnnotatedString(fullText),
            style = TextStyle(
                fontFamily = titleFont,
                fontSize = with(density) { size.toSp() }
            ),
            maxLines = 1,
            softWrap = false,
            constraints = Constraints()
        )
        SHOWGIRL_TITLE_SIZES.firstNotNullOfOrNull { candidate ->
            val layout = measureAt(candidate)
            if (layout.size.width <= maxWidthPx) candidate to layout else null
        } ?: run {
            val fallback = SHOWGIRL_TITLE_SIZES.last()
            fallback to measureAt(fallback)
        }
    }
    val titleSize = chosen.first
    val layout = chosen.second
    val titleSp = with(density) { titleSize.toSp() }
    val titleStyle = remember(titleFont, titleSp) {
        TextStyle(fontFamily = titleFont, fontSize = titleSp)
    }
    val reveal = encoreReveal()

    // 尘埃的落点与粒子：只依赖字形与字号，不随时间变，所以 remember 住。
    // 低端机减半 —— 与卡片母题的降档同策略。
    //
    // 落点取的是**真字形轮廓**（`Paint.getTextPath`），不是 TextLayoutResult 的
    // 选区路径 —— 后者在真机上返回的是圆角矩形，尘埃会聚成一个虚线方框
    val context = LocalContext.current
    val targets = remember(context, titleSize, lowRam) {
        buildDustTargets(
            context = context,
            text = SHOWGIRL_ENCORE_TITLE,
            fontResId = R.font.era_showgirl,
            fontSizePx = with(density) { titleSize.toPx() },
            count = if (lowRam) DUST_COUNT_LOW_RAM else DUST_COUNT
        )
    }
    // 尘埃落在哪：把字形轮廓自己的包围盒映射进**这个 Box 的坐标**。
    //
    // 轮廓是 `Paint.getTextPath` 从 `penX = 0, baseline = 0` 起画的，而 `Text` 从
    // Box 的左上角开始排整串。所以要把这一段在行内的起点（`getHorizontalPosition`）
    // 与行的基线（`getLineBaseline`）补上，两个坐标系才对得上 ——
    // 漏掉这一步尘埃会聚在 Box 左上角，而字在右边。
    val dustBox = remember(layout, targets, upperName) {
        if (targets.isEmpty) {
            Rect.Zero
        } else {
            val runStart = layout.getHorizontalPosition(upperName.length, usePrimaryDirection = false)
            val baseline = layout.getLineBaseline(0)
            val b = targets.bounds
            Rect(
                left = runStart + b.left,
                top = baseline + b.top,
                right = runStart + b.right,
                bottom = baseline + b.bottom
            )
        }
    }
    val motes = remember(targets, lowRam, dustBox, cardContentHeight, innerWidth, density) {
        // 尘埃从**整张卡**上聚来：四边各还有几个字高 / 字宽的空间。
        // 标题上方只有内边距那一点，下方是日期加整列曲目 —— 所以粒子大半从下面来
        val spread = dustSpreadOf(
            titleBox = dustBox,
            cardWidth = with(density) { innerWidth.toPx() },
            cardHeight = with(density) { cardContentHeight.toPx() }
        )
        buildDustMotes(
            count = if (lowRam) DUST_COUNT_LOW_RAM else DUST_COUNT,
            targets = targets,
            spreadAbove = spread.above,
            spreadBelow = spread.below,
            spreadLeft = spread.left,
            spreadRight = spread.right
        )
    }

    Box(
        modifier = modifier.drawBehind {
            // 只有续章那张、且窗口内才画（progress 0 时 drawEncoreDust 自己早退）
            if (!dustBox.isEmpty) {
                drawEncoreDust(
                    elapsedInCard = elapsedInCard(),
                    targets = targets,
                    motes = motes,
                    titleBox = dustBox
                )
            }
        }
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(brush = orangeBrush)) { append(upperName) }
                // 金闪粉那一段：alpha 随续章推进，位置与宽度一毫秒都不变
                withStyle(SpanStyle(brush = goldBrush, alpha = reveal)) {
                    append(SHOWGIRL_ENCORE_TITLE)
                }
            },
            style = titleStyle,
            // 一行硬约束：字号已经按「排得下」选过，这里再折行就说明选档出了问题，
            // 宁可省略号也不要折行把曲目列挤扁
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
