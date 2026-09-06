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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.screen.swiftie.rememberIsLowRamDevice
import java.util.Locale
import kotlin.math.roundToInt

/** 卡片长出用 400ms，曲目从第 400ms 起逐行点亮。 */
const val TRACK_REVEAL_START_MS: Long = 400L

/** 每行 130ms：读起来像唱针一格一格走过去。再快就成一整块闪现。 */
const val TRACK_STAGGER_MS: Long = 130L

/** 低端机加快到 70ms。**单张卡片总时长不变**，省下的并进停留。 */
const val TRACK_STAGGER_LOW_RAM_MS: Long = 70L

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
 * 曲目名**不用**时代字体 —— 那些字体按专辑名逐个子集化，拿来画曲目全是豆腐块。
 * 只借该时代的主色。
 *
 * @param eraIndex 这是第几张，只用来判「哪一行要描金」（见 [gildedRowIndex]）
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
    val stagger = if (lowRam) TRACK_STAGGER_LOW_RAM_MS else TRACK_STAGGER_MS
    val gildedRow = gildedRowIndex(eraIndex)

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
    val numberStyle = remember(textColors, rowHeight, density) {
        TextStyle(
            fontSize = with(density) { (rowHeight * 0.6875f).toSp() },
            color = textColors.number.copy(alpha = SwiftieEraContrast.NUMBER_ALPHA)
        )
    }
    val titleStyle = remember(textColors, rowHeight, density) {
        TextStyle(fontSize = with(density) { (rowHeight * 0.75f).toSp() }, color = textColors.body)
    }
    // derivedStateOf：布尔量不变就不通知读者，所以卷收之前这一列的**布局**一帧都不失效。
    // 直接在 layout 里读 collapseProgress() 会让整段 96 秒每帧重测一遍所有行
    val collapsing = remember(collapseProgress) {
        derivedStateOf { collapseProgress() > 0.0001f }
    }

    Column(
        // 187 首念不完，也会把动画期间的焦点全占住。整块对 TalkBack 隐身，
        // 卡片自己有一条 contentDescription
        modifier = modifier.clearAndSetSemantics { }
    ) {
        era.tracks.forEachIndexed { index, title ->
            val appearAt = TRACK_REVEAL_START_MS + stagger * index
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .collapsingRow(rowHeight, collapsing, rowCollapse)
                    // 在 graphicsLayer 里读时钟：每帧只失效 draw，不重组
                    .graphicsLayer {
                        val reveal = ((elapsedInCard() - appearAt) / TRACK_FADE_MS)
                            .coerceIn(0f, 1f)
                        // alpha 在收起走到 70% 时就归零：高度还在收，字已经看不见了，
                        // 于是永远看不到「字被行高横切一半」那一帧
                        val shrink = (rowCollapse() / 0.7f).coerceIn(0f, 1f)
                        alpha = reveal * (1f - shrink)
                        translationY = (1f - reveal) * 10.dp.toPx()
                    }
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
                    // 固定 Locale.US：某些地区会把 %02d 渲染成本地数字
                    text = String.format(Locale.US, "%02d", index + 1),
                    style = numberStyle,
                    // 列宽是定死的 rowHeight×1.375，不禁止折行的话
                    // 「01」会在放大档位折成两行、被行高裁掉下半截
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(numberWidth)
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
 * 所以整段 96 秒里这一层一帧都不失效，真在卷的那 1.5 秒才逐帧重测。
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
