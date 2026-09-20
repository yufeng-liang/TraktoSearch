package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 卡片长出用 400ms，曲目从第 400ms 起逐行点亮。 */
const val TRACK_REVEAL_START_MS: Long = 400L

/** 每行 130ms：读起来像唱针一格一格走过去。再快就成一整块闪现。 */
const val TRACK_STAGGER_MS: Long = 130L

/** 低端机加快到 70ms。**单张卡片总时长不变**，省下的并进停留。 */
const val TRACK_STAGGER_LOW_RAM_MS: Long = 70L

/**
 * 第 [rowIndex] 行的揭示起点（卡内毫秒）。
 *
 * 其余 11 张沿用固定 130ms；TTPD 走账本里放慢后的逐行时间表。
 * [rowIndex] 允许等于曲目数；对 TTPD 来说返回值就是整列打印结束的时刻，
 * 其余 11 张的第 N 行起点正是旧时间表里「最后一行的下一拍」。
 */
internal fun trackRowRevealAtMs(
    eraIndex: Int,
    rowIndex: Int,
    lowRam: Boolean
): Long {
    if (eraIndex == SwiftieTimeline.TTPD_INDEX && !lowRam) {
        // 取整只在这一处发生，UI 与触感拿到的是同一个毫秒数
        return (
            TRACK_REVEAL_START_MS +
                SwiftieTimeline.TTPD_TRACK_REVEAL_MS.toDouble() * rowIndex /
                SwiftieTimeline.ERA_TRACK_COUNTS[SwiftieTimeline.TTPD_INDEX]
            ).roundToLong()
    }
    val stagger = if (lowRam) TRACK_STAGGER_LOW_RAM_MS else TRACK_STAGGER_MS
    return TRACK_REVEAL_START_MS + stagger * rowIndex
}

/** 单行淡入时长。 */
private const val TRACK_FADE_MS: Float = 220f

/** 描金色。金箔的那种黄，不是 Fearless 的主色（数值撞上了，含义无关）。 */
private val GILD_GOLD = Color(0xFFD4AF37)

/** 描金那一行那道金线：该行点亮之后再等 300ms 才扫出来，不跟逐行点亮抢注意力。 */
private const val GILD_RULE_DELAY_MS: Long = 300L

/** 金线扫完 420ms。比一行的淡入（220ms）慢一点：扫得出来才读作一笔写过去。 */
private const val GILD_RULE_MS: Float = 420f

/** 卷收：逐行错开 70ms，**自下而上**。 */
private const val COLLAPSE_ROW_STAGGER_MS: Float = 70f

/** 单行收起 240ms：高度收到 0 + alpha 到 0，读起来像卷纸。 */
private const val COLLAPSE_ROW_MS: Float = 240f

/**
 * 卷收窗口，与 `SwiftieTimeline.REWIND_MS` 同步 —— `collapseProgress` 是按它归一化的。
 *
 * 18 行最长的一张（Lover）用 `17 × 70 + 240 = 1430ms`，正好落在窗口内。
 */
private const val COLLAPSE_WINDOW_MS: Float = 1_500f

/**
 * 这张专辑要描金的那一行；-1 = 这张没有。
 *
 * 只有 Lover 有：**第 7 张专辑的第 3 首**。数字对上了一个私人纪念日，
 * 所以只用一道金线暗示 —— 一旦写成字就不是彩蛋了，
 * 而且任何日期措辞都有时效风险（a11y 描述也刻意不提）。
 */
private fun gildedRowIndex(eraIndex: Int): Int =
    if (eraIndex == SwiftieErasData.LOVER_INDEX) 2 else -1

/** 不参与卷收的那一行用它，省得每行都新建一个返回 0 的 lambda。 */
private val NO_COLLAPSE: () -> Float = { 0f }

/**
 * 一张专辑的完整曲目列：逐行点亮（Spec §6.2），序列末尾自下而上卷收。
 *
 * 曲目名**不借时代字体** —— 那 12 个字库是按专辑名逐个子集化的，只含那几个字，
 * 拿来画曲目全是豆腐块；TTPD 那张用打字机字体（`era_typewriter`，按 31 首的曲名与
 * 数字做的子集），是因为页面上真的有一台打字机把这一列打出来（见 [typingRow]）。
 *
 * @param eraIndex 这是第几张。判「哪一行要描金」（见 [gildedRowIndex]）与
 *   「这一列是不是打出来的」（TTPD）
 * @param elapsedInCard 这张卡片自己的已用毫秒
 * @param textColors 压暗到 AA 的一组文字色，由卡片算好传进来（见 `SwiftieEraContrast`）
 * @param rowHeight 单行行高，由卡片按可用高度与曲目数算好（见 `trackRowHeight`）。
 *   字号跟着它等比缩放，所以 31 首的 TTPD Anthology 在小屏上也排得下
 * @param collapseProgress 0f = 18 行全在；1f = 只剩描金那一行。**在布局阶段读**
 */
@Composable
internal fun SwiftieEraTracklist(
    era: SwiftieEra,
    eraIndex: Int,
    textColors: SwiftieEraTextColors,
    rowHeight: Dp,
    elapsedInCard: () -> Long,
    collapseProgress: () -> Float,
    modifier: Modifier = Modifier
) {
    val lowRam = rememberIsLowRamDevice()
    val gildedRow = gildedRowIndex(eraIndex)
    // TTPD 那一张的曲目列是**打字机打的**：字号换 Special Elite（子集化的打字机字体），
    // 逐行点亮换成「打字头从左往右走过一行」。其余 11 张与压缩前逐像素一致
    val typed = eraIndex == SwiftieTimeline.TTPD_INDEX
    val titleFont = if (typed) {
        FontFamily(Font(R.font.era_typewriter))
    } else {
        FontFamily.Default
    }

    // 字号用 Dp.toSp() 折算，**不跟系统字号走**。
    //
    // 行容器是固定 dp 高（31 首的 TTPD 要靠固定行高才排得进卡片），字号若用裸 .sp
    // 就会被 fontScale 放大而行高不动 —— 字号档位调到 115% 起，行盒就超过行高，
    // 而曲目名是 Ellipsis、序号是默认的 Clip，两者都不是 Visible，
    // Compose 会按布局框裁剪，结果是**字形被纵向切掉**。
    // Dp.toSp() 除掉 fontScale，渲染尺寸正好等于那么多 dp。
    // 代价是这一列不跟随系统字号 —— 它是定时动画里的固定版面，没有别的选择；
    // 需要细看的用户走「拖播放头定格」那条路。
    // 16dp 行高换算出 12sp / 11sp，与压缩前逐像素一致；压到 12dp 就是 9sp / 8.25sp
    val density = LocalDensity.current
    val numberWidth = rowHeight * 1.375f
    val numberStyle = remember(textColors, rowHeight, density, titleFont) {
        TextStyle(
            fontFamily = titleFont,
            fontSize = with(density) { (rowHeight * 0.6875f).toSp() },
            color = textColors.number.copy(alpha = SwiftieEraContrast.NUMBER_ALPHA)
        )
    }
    val titleStyle = remember(textColors, rowHeight, density, titleFont) {
        TextStyle(
            fontFamily = titleFont,
            fontSize = with(density) { (rowHeight * 0.75f).toSp() },
            color = textColors.body
        )
    }
    // derivedStateOf：布尔量不变就不通知读者，所以卷收之前这一列的**布局**一帧都不失效。
    // 直接在 layout 里读 collapseProgress() 会让整段 104 秒每帧重测一遍所有行
    val collapsing = remember(collapseProgress) {
        derivedStateOf { collapseProgress() > 0.0001f }
    }

    Column(
        // 190 首念不完，也会把动画期间的焦点全占住。整块对 TalkBack 隐身，
        // 卡片自己有一条 contentDescription
        modifier = modifier.clearAndSetSemantics { }
    ) {
        era.tracks.forEachIndexed { index, title ->
            val appearAt = trackRowRevealAtMs(eraIndex, index, lowRam)
            val revealSpan = (
                trackRowRevealAtMs(eraIndex, index + 1, lowRam) - appearAt
                ).coerceAtLeast(1L)
            val gilded = index == gildedRow
            // 自下而上：末行先收，首行最后收。描金那一行不收 —— 它是留下来的那一行，
            // 上面几行收干净之后它自然贴到日期底下
            val collapseStart =
                (era.tracks.lastIndex - index) * COLLAPSE_ROW_STAGGER_MS / COLLAPSE_WINDOW_MS
            val rowCollapse: () -> Float = if (gilded) {
                NO_COLLAPSE
            } else {
                {
                    ((collapseProgress() - collapseStart) * COLLAPSE_WINDOW_MS / COLLAPSE_ROW_MS)
                        .coerceIn(0f, 1f)
                }
            }
            // 这一行的两个 Text 各自的排版结果：打字头要**逐字**落在字上，
            // 而按键的落点只有排版结果知道（见 [typingRow]）。
            // 用普通数组而不是 mutableStateOf —— 它在**绘制**阶段被读，不需要触发重组；
            // 每次排版都会刷新，draw 一定晚于 layout，读到的就是这一帧的
            val numberLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
            val titleLayout = remember { arrayOfNulls<TextLayoutResult>(1) }
            // 固定 Locale.US：某些地区会把 %02d 渲染成本地数字
            // Lover 第 3 首（曲名即专辑名）：编号排单字「3」，呼应底座铭牌的 7·3；
            // 固定 Locale.US：某些地区会把 %02d 渲染成本地数字
            val numberText = if (eraIndex == SwiftieErasData.LOVER_INDEX && index == 2) {
                "3"
            } else {
                String.format(Locale.US, "%02d", index + 1)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .collapsingRow(rowHeight, collapsing, rowCollapse)
                    .then(
                        if (typed) {
                            // 打字那一张：**没有淡入也没有位移**，整行靠打字头揭示 ——
                            // alpha 只留给卷收（见下）
                            Modifier
                                .graphicsLayer {
                                    val shrink = (rowCollapse() / 0.7f).coerceIn(0f, 1f)
                                    alpha = 1f - shrink
                                }
                                .typingRow(
                                    progress = {
                                        ((elapsedInCard() - appearAt).toFloat() / revealSpan)
                                            .coerceIn(0f, 1f)
                                    },
                                    numberWidth = numberWidth,
                                    numberChars = numberText.length,
                                    numberLayout = { numberLayout[0] },
                                    titleLayout = { titleLayout[0] },
                                    title = title,
                                    rowHeight = rowHeight,
                                    cursor = textColors.number,
                                    elapsedInCard = elapsedInCard
                                )
                        } else {
                            // 在 graphicsLayer 里读时钟：每帧只失效 draw，不重组
                            Modifier.graphicsLayer {
                                val reveal = ((elapsedInCard() - appearAt) / TRACK_FADE_MS)
                                    .coerceIn(0f, 1f)
                                // alpha 在收起走到 70% 时就归零：高度还在收，字已经看不见了，
                                // 于是永远看不到「字被行高横切一半」那一帧
                                val shrink = (rowCollapse() / 0.7f).coerceIn(0f, 1f)
                                alpha = reveal * (1f - shrink)
                                translationY = (1f - reveal) * 10.dp.toPx()
                            }
                        }
                    )
                    .then(
                        if (gilded) {
                            Modifier.gildedRow(
                                startMs = appearAt + GILD_RULE_DELAY_MS,
                                elapsedInCard = elapsedInCard
                            )
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = numberText,
                    style = numberStyle,
                    // 列宽是定死的 rowHeight×1.375，不禁止折行的话
                    // 「01」会在放大档位折成两行、被行高裁掉下半截
                    maxLines = 1,
                    softWrap = false,
                    onTextLayout = { numberLayout[0] = it },
                    modifier = Modifier.width(numberWidth)
                )
                Text(
                    text = title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleLayout[0] = it }
                )
            }
        }
    }
}

/**
 * 卷收一行：把行高收到 0。
 *
 * 必须走**布局**而不只是 `graphicsLayer` —— 卡片整体高度要跟着缩，
 * 留一片空白就不是「卷起来」而是「擦掉了」。
 *
 * 子内容仍按**原始行高**测量，只把报出去的高度收窄：多出来的部分会被下面
 * 那些已经收完的行位置吃掉，看不见；反过来按矮高度重排的话，Ellipsis 的曲目名
 * 与 Clip 的序号会被布局框横切一半。
 *
 * [collapsing] 是个 `derivedStateOf`：卷收开始前它一直是 false，
 * 所以整段 104 秒里这一层一帧都不失效，真在卷的那 1.5 秒才逐帧重测。
 */
private fun Modifier.collapsingRow(
    rowHeight: Dp,
    collapsing: State<Boolean>,
    rowCollapse: () -> Float
): Modifier = layout { measurable, constraints ->
    val full = rowHeight.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = full, maxHeight = full))
    val height = if (collapsing.value) {
        (full * (1f - rowCollapse())).roundToInt().coerceIn(0, full)
    } else {
        full
    }
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/** 打字头眨眼的周期。与方块游标（`SwiftieEraMotifs` 信纸上那个）同一档速度。 */
private const val TYPING_HEAD_BLINK_MS = 420L

/**
 * 打字头那一格的高度，按行高算。
 *
 * 曲名字号是行高的 0.75（见调用处的 `titleStyle`），Special Elite 的大写字高约 0.67 个字号
 * —— 相乘 ≈ 行高的 0.50。真机上量过：行高 57.5px 时大写顶到基线正好 29px（0.504）。
 * 这个比值不随密度与系统字号变（字号本身是用 `Dp.toSp()` 折过的），所以能写死。
 */
private const val TYPING_HEAD_SPAN = 0.50f

/** 首帧还没有排版结果时，基线按行高估的位置（真机量到的 0.651）。 */
private const val TYPING_HEAD_FALLBACK_BASELINE = 0.651f

/**
 * 打字头走过一行。
 *
 * ## 为什么不是淡入
 *
 * TTPD 这一列是那台打字机打出来的：字从左往右**一个字一个字落上去**，落下的地方压着
 * 一块方块游标（就是机器上的印字点）。淡入读作「点亮」，与屏幕上正在发生的事对不上。
 *
 * 行距 [stagger]（130ms）正好是打字头走到下一行的时间，所以任何一帧**只有一行**
 * 正在被打 —— 真机也只有一根字锤。揭示就是裁一刀，字形被拦腰截断的那半个字由游标
 * 压着，看不见切口。
 *
 * ## 落点按**字**取，不按行宽均分
 *
 * 行是 `fillMaxWidth`，曲名却大多只占半行。按「行宽 × 进度」推游标，短曲名（`loml`）
 * 打完之后游标还在一路往右滑，滑过的是一块空白 —— 屏幕上读作游标跑丢了。
 * 所以落点从两个 Text **自己的排版结果**里逐字取（`getHorizontalPosition`）：
 * 序号那两位取序号的，曲名取曲名的，游标永远停在**下一个字的字格**上。
 *
 * 进度也按字取整（`(p * 总字数).toInt()`）：真机一格一格走，被揭示的字因此总是完整的
 * 一个 —— 切口落在字格边界上，不需要靠游标去盖半个字形。
 *
 * 长曲名被 Ellipsis 截断时，只数到**省略号之前**那些字（`getLineEnd(visibleEnd = true)`），
 * 游标走到省略号就停住，不会继续往行尾滑。
 *
 * 游标的高度也一样要**贴着字**：字是垂直居中排的，按行高切一段固定比例会整体栽到基线
 * 下面去。这里按「大写顶 → 基线」画，基线从排版结果取（见 [TYPING_HEAD_SPAN]）。
 *
 * clip 而不是 `graphicsLayer`：`graphicsLayer` 只能整体设 alpha 或做仿射变换，
 * 而揭示边要停在行里任意位置，只能在绘制时裁。
 *
 * @param progress 0f..1f 的打字进度。**在绘制阶段读**（读的是时钟），不引起重组
 * @param numberWidth 序号列的宽度（定宽格子，见 `numberWidth` 那个局部量）
 * @param numberChars 序号有几位
 * @param numberLayout 序号自己的排版结果。序号列是定宽格子而数字只占左边一小截，
 *   按列宽均分着走会让游标在数字打完之后空滑一段才进曲名
 * @param titleLayout 曲名的排版结果，给出每个字的落点。首帧可能还没有（见调用处的数组）
 * @param title 曲目名，排版结果还没到时按它算个大概
 * @param cursor 游标色。取序号那一档 —— 比正文浅，压在前沿上不抢字
 */
private fun Modifier.typingRow(
    progress: () -> Float,
    numberWidth: Dp,
    numberChars: Int,
    numberLayout: () -> TextLayoutResult?,
    titleLayout: () -> TextLayoutResult?,
    title: String,
    rowHeight: Dp,
    cursor: Color,
    elapsedInCard: () -> Long
): Modifier = drawWithContent {
    // clipRect 的 block 换过接收者（DrawScope），`drawContent()` 要用显式接收者才调得到
    val content = this
    val p = progress()
    if (p <= 0f) return@drawWithContent
    if (p >= 1f) {
        content.drawContent()
        return@drawWithContent
    }
    val numberPx = numberWidth.toPx()
    val num = numberLayout()
    val titleResult = titleLayout()
    // 省略号之前真正排出来的字数。没截断时就是曲目名的全长
    val titleChars = titleResult?.getLineEnd(0, visibleEnd = true) ?: title.length
    val total = (numberChars + titleChars).coerceAtLeast(1)
    val typed = (p * total).toInt().coerceIn(0, total)
    // 已经落上去 k 个字，裁剪边就停在**第 k 个字的左沿**上（= 游标要落的那一格）。
    // usePrimaryDirection = false：这一列全是拉丁字母与数字，字格边界不需要按双向文本
    // 的书写方向去分辨（那一位在这个 Compose 版本上没有默认值，必须显式给）
    val head = when {
        typed < numberChars -> num?.getHorizontalPosition(typed, usePrimaryDirection = false)
            ?: (numberPx * typed / numberChars)
        else -> numberPx + (
            titleResult?.getHorizontalPosition(
                (typed - numberChars).coerceIn(0, titleChars),
                usePrimaryDirection = false
            ) ?: ((size.width - numberPx) * ((typed - numberChars).toFloat() / titleChars))
            )
    }
    clipRect(right = head) { content.drawContent() }
    // 游标：只占行高的一半多一点。齐行高的竖条读起来是「文本插入符」而不是字锤，
    // 而这台机器上落下来的是一小块方形印字头。
    //
    // **高度带是大写顶到基线**，不是行高的一段固定比例：字是垂直居中排的，按行高
    // 切一段会整体栽到基线下面去（上一版占 0.56、底边压到基线下 7px）。
    // 基线从排版结果里取（`getLineBaseline`）—— 字号跟着行高走，它也跟着走
    if ((elapsedInCard() / TYPING_HEAD_BLINK_MS) % 2L != 0L) return@drawWithContent
    val headW = (rowHeight.toPx() * 0.17f).coerceAtLeast(1f)
    val headH = rowHeight.toPx() * TYPING_HEAD_SPAN
    val baseline = titleResult?.let {
        (size.height - it.size.height) * 0.5f + it.getLineBaseline(0)
    } ?: (size.height * TYPING_HEAD_FALLBACK_BASELINE)
    drawRect(
        color = cursor,
        topLeft = Offset(head - headW * 0.5f, baseline - headH),
        size = Size(headW, headH)
    )
}

/**
 * 描金那一行：一条自左往右扫出来的细金线。
 *
 * 最早这一行铺的是一条暖黄横条（`#FFE7B0`，两端淡出）。被点名「太丑了」—— 一条通栏的
 * 黄底在浅色卡片上读作「这一行被选中 / 被高亮了」，是控件状态而不是题字，而且那个黄与
 * 卡片主色系没有关系。换成**一条 0.7dp 的细金线**：从左往右扫出来，两端用渐变淡掉。
 * 金线是题字的笔迹，横条是控件背景 —— 差别全在这里。
 *
 * 行尾原先还有一颗被箭钉住的金心、`The Archer` 那一行还有一把小弓，两样都撤了：
 * 12dp 的行高画不出一把像样的弓，而这一箭真正要射中的是**页面背景彩虹上那颗心**
 * （见 `SwiftieLoverArrowFlight`）—— 弓因此搬到卡片右侧的道具位，那里有一百多 dp 见方。
 * 序号也不再描金：金字压在白卡上只有 1.9:1，要靠一圈深金描边才够对比，
 * 而那圈描边在 11sp 上糊成一小块黄底。
 *
 * 这一行为什么被挑出来，见 [gildedRowIndex]。
 */
private fun Modifier.gildedRow(
    startMs: Long,
    elapsedInCard: () -> Long
): Modifier = drawWithCache {
    val ruleTop = size.height - 1.6.dp.toPx()
    val ruleHeight = 0.7.dp.toPx()
    val ruleWidth = (size.width - 2.dp.toPx()).coerceAtLeast(1f)
    // Brush 在 drawWithCache 里建一次。draw lambda 里 new 一个就是每帧一个原生 Shader
    val rule = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            GILD_GOLD.copy(alpha = 0.62f),
            GILD_GOLD.copy(alpha = 0.62f),
            Color.Transparent
        ),
        startX = 0f,
        endX = ruleWidth
    )
    onDrawBehind {
        val sweep = ((elapsedInCard() - startMs) / GILD_RULE_MS).coerceIn(0f, 1f)
        if (sweep <= 0f) return@onDrawBehind
        drawRect(
            brush = rule,
            topLeft = Offset(0f, ruleTop),
            size = Size(ruleWidth * sweep, ruleHeight)
        )
    }
}
