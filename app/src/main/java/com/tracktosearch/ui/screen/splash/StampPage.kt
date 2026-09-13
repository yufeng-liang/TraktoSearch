package com.tracktosearch.ui.screen.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import java.time.LocalDate
import java.util.Locale

/**
 * 「日签页」这一版的版面：开屏那一整屏和日签卡片用的是同一种纸。
 *
 * 开屏（[SplashQuoteOverlay]）和日签卡片（DailyStampCardOverlay）本来是两份排版：
 * 一份铺满屏幕、一份装在一张票根卡里，纸色、海报尺寸、字号、撕口位置各写一套。
 * 现在合成一份——卡片就是把这一页按 9:16 缩进轮播槽里（见 DailyStampCardOverlay 的
 * 密度缩放），于是屏幕上看到的、导出分享出去的、以及开屏那一屏，是同一页的三种呈现。
 *
 * 拆开的依据是「动还是不动」：这一层只管**静止的版面**（位置、留白、字号、墨色），
 * 开屏那套浮现动画留在 SplashQuoteOverlay，通过 [StampReveal] 施加在每个元素上。
 * 末态（alpha 1、无位移、无缩放）不挂 graphicsLayer——图层录不进 Picture，
 * 而日签卡要旁路录一份给导出，页内任何一处图层都会让导出的那张图缺一块。
 *
 * 底部那一条 [StampStripHeight] 上的内容是三种呈现唯一的差别：开屏放「轻触跳过」，
 * 卡片与导出图上放落款那一行（图标 + 应用名，见 DailyStampExport 的 StampBrand），
 * 未来那一页放「那天见」。三处共用这一个高度，日期块和撕口虚线才落在同一个位置——
 * 「只把那一行换掉」说的就是这件事。
 */

// ---------------------------------------------------------------------------
// 版面常量
// ---------------------------------------------------------------------------

/** 页面左右页边距。台词那一列比它窄不了多少，改小一行字就会顶到齿孔轨上 */
internal val StampSidePadding: Dp = 34.dp

/** 顶部那道安全边。状态栏压在这一层上面，少了它矮屏上海报上沿会钻到时钟底下 */
internal val StampTopPadding: Dp = 12.dp

/** 海报到底下那几行台词之间 */
internal val StampPosterToLines: Dp = 20.dp

/** 台词到出处之间 */
internal val StampLinesToSource: Dp = 18.dp

/** 出处到印章之间 */
internal val StampSourceToSeal: Dp = 20.dp

/**
 * 底部那一条的高度：开屏是「轻触跳过」，卡片与导出图上是落款那一行（图标 + 名字）。
 *
 * 撕口虚线和日期块都由它推出来（见下面三个），所以这一条改高，那两样跟着上移——
 * 三处一起动，不会出现「开屏的日期在这儿、导出的日期在那儿」。
 */
internal val StampStripHeight: Dp = 44.dp

/** 撕口虚线离页底多远。虚线要压在底部那一条的上沿之外，见 [StampStripHeight] */
internal val StampTearBottom: Dp = StampStripHeight + 36.dp

/** 日期块离页底多远：贴在虚线上方 6dp。太贴着虚线会把日期读成存根上的字 */
internal val StampDateBottom: Dp = StampTearBottom + 6.dp

/**
 * 日期块有多高：月日一行（38sp）+ 3dp + 年份一行（11sp）。
 *
 * 这个高度只用来推出日期块的顶边（见 [StampSealBandBottom] 与 [StampContentBottomReserve]），
 * 所以取的是设计值而不是量出来的行盒高度——差一两个 dp 只让印章在那一格里挪一个像素。
 */
internal val StampDateBlockHeight: Dp = 64.dp

/**
 * 中间那一列要在底部让出的高度。
 *
 * 日期块自己那一块（见 [StampDateBlockHeight]）加离印章至少 8dp 的空。让出这一块之后，
 * 四行台词加上印章也不会顶到日期上；多出来的余量落在印章与日期之间，那里本来就是留白。
 */
internal val StampContentBottomReserve: Dp = StampDateBottom + StampDateBlockHeight + 8.dp

/** 撕口虚线左右各内收多少，让虚线不顶到齿孔轨 */
internal val StampTearInset: Dp = 26.dp

/**
 * 印章那一段的下沿：日期块顶边离页底 [StampDateBottom]，再往上就是日期块本身。
 *
 * 印章在「出处行底边 → 这里」之间垂直居中（见 [stampSealBand]）。不拿页底当边界：
 * 那样印章会压到日期上；也不拿撕口虚线（80dp）当边界：那是纸的撕口，不是版面的下边界。
 *
 * 这个值是**从页底量**的，而中间那一列的下边离页底还差一道 [StampContentBottomReserve]；
 * 调用处要把它换算到那一列的坐标系里去（见 StampPage 里那段注释）。
 */
private val StampSealBandBottom: Dp = StampDateBottom + StampDateBlockHeight

/**
 * 设计高度：这一页按「853dp 高的屏」来排。
 *
 * 日签卡把它等比缩进轮播槽（9:16 的槽高通常五六百 dp），开屏则是真的铺满屏幕。
 * 取 853 是因为最高的那几档手机（iPhone 15 的 852、Pixel 8 的 915 折下来）在这一档上下，
 * 而页内内容按这一档排完还剩得下印章到日期之间那段留白。
 */
internal val StampDesignHeight: Dp = 853.dp

/** 日签卡片的宽高比：9:16，和手机屏幕同形，分享出去是通用的竖图 */
internal const val StampPageAspect: Float = 9f / 16f

/** 海报的圆角与卡纸边：三面 7dp，底边 15dp 是留给手拿的地方 */
private val PosterCorner: Dp = 9.dp
private val PosterWindowCorner: Dp = 5.dp
private val PosterMat: Dp = 7.dp
private val PosterMatBottom: Dp = 15.dp

/** 高屏 238×356；矮屏回落到 176×264，见 [stampPosterSize] */
private val PosterWidth: Dp = 238.dp
private val PosterHeight: Dp = 356.dp
private val PosterCompactWidth: Dp = 176.dp
private val PosterCompactHeight: Dp = 264.dp

/** 屏幕矮过这一档就用小海报，见 [stampPosterSize] */
internal const val PosterCompactHeightDp = 760

/**
 * 字号放大超过这一档也按矮屏算：四行台词的行高整段拉长，占的还是纵向那点余量
 */
internal const val PosterCompactFontScale = 1.15f

/** 四行台词比三行多吃三十来 dp，海报跟着收一档（收海报而不是收字号：字号是内容） */
private const val PosterSqueezeLines = 4
private const val PosterSqueeze = 0.92f

/** 投影：九层同心圆角矩形叠出来的软影，见 [stampPosterShadow] */
private val PosterShadowSpread: Dp = 9.dp
private val PosterShadowDrop: Dp = 3.dp
private const val PosterShadowSteps = 9
private const val PosterShadowInkLight = 0.12f
private const val PosterShadowInkDark = 0.40f

private val TearDash: Dp = 3.dp

private const val DateSlash = " ⁄ "
private const val SlashInk = 0.5f
private const val EM_DASH = "—"

/**
 * 日期那一行的淡墨：印章底下那行年份。
 *
 * 日期是装饰性刻度，按 WCAG 只需要 3:1，可以比正文淡；但不能淡到看不见——
 * [SplashPalette.inkFaint]（0.32 倍墨）在纸上只有 1.60:1（暗色 2.23:1），等于没印上去。
 * 这里按正文墨另兑一档：明色 0.82 在纸上是 4.00:1，暗色 0.44 是 3.64:1，两套的淡法看上去一致。
 *
 * 不去改 inkFaint 本身，是因为日签页拿它画撕口虚线和日历格线：
 * 为了这一行日期把它压深，那一屏的细线会立刻变成描边。
 */
internal fun stampDateInk(palette: SplashPalette): Color =
    palette.ink.copy(alpha = if (palette.isDark) 0.44f else 0.82f)

/**
 * 底部那一行提示的墨色（开屏的「轻触跳过」）。
 *
 * 这行是操作提示不是装饰，得按正文的 4.5:1 要求给色。明色索引到实心墨（纸上 5.87:1，
 * 把四角压暗和颗粒算进去 4.60:1）；暗色的浅墨本来就富裕，0.60 实测 5.45:1，
 * 再往上加只会让一行小字比台词还抢眼。
 */
internal fun stampHintInk(palette: SplashPalette): Color =
    palette.ink.copy(alpha = if (palette.isDark) 0.60f else 1f)

// ---------------------------------------------------------------------------
// 浮现
// ---------------------------------------------------------------------------

/**
 * 一个元素怎么浮现：淡入、上浮、压下来。
 *
 * 三样都是同一个元素上的图层变换，合在一个 graphicsLayer 里做——分成两层不会更清楚，
 * 只多一张图层。
 */
internal data class StampReveal(
    val alpha: Float = 1f,
    val rise: Dp = 0.dp,
    val scale: Float = 1f,
)

/**
 * 把 [reveal] 施加到元素上。
 *
 * 末态直接返回原修饰符、不挂图层：日签卡要用 Picture 录下这一页再重放成导出图，
 * 而 Picture 里遇到图层会整块漏掉（GraphicsLayer 快照那条路更早还栽过——部分 Android 9+
 * 设备返回尺寸正常、内容全白的位图）。于是页内元素在末态必须是裸绘制，
 * 只有开屏那几帧浮现才真的用得上图层。
 */
internal fun Modifier.stampReveal(reveal: StampReveal): Modifier =
    if (reveal.alpha >= 1f && reveal.rise == 0.dp && reveal.scale == 1f) {
        this
    } else {
        this.graphicsLayer {
            alpha = reveal.alpha
            translationY = reveal.rise.toPx()
            if (reveal.scale != 1f) {
                scaleX = reveal.scale
                scaleY = reveal.scale
            }
        }
    }

// ---------------------------------------------------------------------------
// 页
// ---------------------------------------------------------------------------

/**
 * 把这一页裁成圆角矩形。[corner] 是 0 时原样返回，不挂任何东西。
 *
 * 走 `drawWithContent` + `clipPath` 而不是 [clip]：Compose 的 `Modifier.clip` 是一层图层
 * （graphicsLayer(shape, clip = true)），而这一页要被日签卡录进 Picture 再重放成导出图，
 * 图层进不了 Picture。画布上的 clipPath 只是一条绘制指令，录制、重放都在，屏幕上也是同一条。
 */
internal fun Modifier.stampCornerClip(corner: Dp): Modifier =
    if (corner <= 0.dp) {
        this
    } else {
        this.drawWithContent {
            val radius = corner.toPx()
            // 裁的是这一页的整块区域；圆角之外保持没画过（导出图上就是透明的四角）
            clipPath(
                path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(Offset.Zero, size),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    )
                }
            ) { this@drawWithContent.drawContent() }
        }
    }

/**
 * 中间那一列的落位，以及印章在「出处 → 日期」那一段里的位置，单位都是像素。
 *
 * [topBlockHeight] 是海报 + 台词 + 出处那一块的高度，[sealHeight] 是印章（含印下那行英文）。
 * [bandBottom] 是那一段下沿在**布局坐标系**里的 y（见 [StampSealBandBottom]）。
 *
 * [topMargin] 把印章那一份（[gap] + 印章）也一起算进这一列的居中：印章还在列里时，这一列
 * 就是按这个高度居中的。于是海报、台词、出处的位置和从前一模一样，动过的只有印章。
 *
 * [sealTop] 让印章（含下方英文那一整块）在「出处行的底边 → [bandBottom]」的正中：台词行数、
 * 印章是方印还是长方印、有没有那行英文，都只改变那一段的长度——居中关系不变。开屏、日签卡、
 * 导出图三处共用这一条规矩（用户 2026-09-13 定）。
 */
internal fun stampSealBand(
    available: Int,
    topBlockHeight: Int,
    sealHeight: Int,
    gap: Int,
    bandBottom: Int,
): StampSealBand {
    val slack = (available - topBlockHeight - gap - sealHeight).coerceAtLeast(0)
    val topMargin = slack / 2
    val bandTop = topMargin + topBlockHeight
    val center = (bandTop + bandBottom) / 2
    // 那一段比印章还短时（超矮窗口、超大字号）贴着出处行排，别翻到上面去
    val sealTop = (center - sealHeight / 2).coerceAtLeast(bandTop)
    return StampSealBand(topMargin = topMargin, sealTop = sealTop)
}

/** 见 [stampSealBand] */
internal class StampSealBand(
    val topMargin: Int,
    val sealTop: Int,
)

/**
 * 静止的一页日签：底色与背景、居中那一列（海报 → 台词 → 出处 → 印）、钉在底部的日期块与
 * 撕口虚线、以及底部那一条的插槽。
 *
 * [base] 是这张纸的底色，也是这一页唯一不透明的底：[StampBackdrop] 画的光锥、齿孔轨、
 * 颗粒和四角压暗全是半透明的装饰，压在什么上面由调用方决定。开屏传给它的是一档会变的
 * 纸色（进场从浅纸淡到当前主题的纸），日签卡用默认值。它必须画在页内——导出那张图是把
 * 这一页录成 Picture 再重放的，底色落在页外（比如挂在调用方的外层修饰符上），重放出来
 * 就是一张透明底：相册和聊天窗口都按自己的底显示它，纸上那份暖白全丢了。
 *
 * [columnModifier] 给的是顶部的安全边：开屏要躲状态栏（[StampTopPadding] 之外再加一道
 * statusBarsPadding），卡片不用——它不在状态栏底下。背景层不吃这道边：
 * 光锥和齿孔轨要铺满整页，那才是这张纸的边界。
 *
 * [corner] 是这一页四个角切掉的半径：开屏铺满整屏，切了也看不见（还给状态栏那一带留缺口），
 * 所以是 0；日签卡与导出图是浮在别的底色上的一张卡，24dp 那个圆角才读得出来。裁切走
 * [stampCornerClip]（画布上的 clipPath，不是图层）——图层录不进 Picture，导出会缺角。
 *
 * 各元素的浮现由调用方自理（槽位里包一层 [stampReveal]，或把修饰符交给下面的静态件）：
 * 开屏每个元素各有一档 alpha，卡片全都是末态。
 *
 * 三个槽位对应纸上的三层：[poster] / [quoteBlock] / [seal] 是中间那一列（印章自己在那一段里
 * 居中，见 [stampSealBand]），[strip] 是日期块下面那一条，[footnote] 是撕口虚线以下那一段的
 * 正中。
 */
@Composable
internal fun StampPage(
    palette: SplashPalette,
    date: LocalDate,
    modifier: Modifier = Modifier,
    columnModifier: Modifier = Modifier,
    base: Color = palette.paper,
    corner: Dp = 0.dp,
    tearAlpha: Float = 1f,
    dateReveal: StampReveal = StampReveal(),
    backdrop: @Composable () -> Unit = {},
    poster: @Composable () -> Unit = {},
    quoteBlock: @Composable ColumnScope.() -> Unit = {},
    seal: @Composable () -> Unit = {},
    strip: @Composable BoxScope.() -> Unit = {},
    footnote: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier.stampCornerClip(corner).background(base)) {
        backdrop()

        // 中间那一列（海报 → 台词 → 出处）与印章分开排：印章不在这一列里，它在「出处」
        // 与日期之间那一段里居中（见 stampSealBand / StampSealBandBottom）。这一列的高度
        // 仍然把印章那一份算进去，于是海报与台词的位置和印章还在列里时一模一样——
        // 用户要的只是把印挪下去，不是把整页重排。
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .then(columnModifier)
                .padding(horizontal = StampSidePadding)
                .padding(top = StampTopPadding, bottom = StampContentBottomReserve),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    poster()
                    Spacer(Modifier.height(StampPosterToLines))
                    quoteBlock()
                }
                Box { seal() }
            },
        ) { measurables, constraints ->
            val topBlock = measurables[0].measure(constraints.copy(minHeight = 0))
            val sealBox = measurables[1].measure(Constraints())
            val gap = StampSourceToSeal.roundToPx()
            // 那一段的下沿＝日期块顶边。它比这一列的底边还低一点（这一列底部为日期块留了
            // StampContentBottomReserve，比日期块自己的高度多出几个 dp），所以从这一列底边
            // 往页面底部数是「负的一段」——印章那一段本来就要伸到这一列的外面去。
            val bandBottom =
                constraints.maxHeight + (StampContentBottomReserve - StampSealBandBottom).roundToPx()
            val column = stampSealBand(
                available = constraints.maxHeight,
                topBlockHeight = topBlock.height,
                sealHeight = sealBox.height,
                gap = gap,
                bandBottom = bandBottom,
            )
            layout(constraints.maxWidth, constraints.maxHeight) {
                topBlock.place(
                    x = (constraints.maxWidth - topBlock.width) / 2,
                    y = column.topMargin,
                )
                sealBox.place(
                    x = (constraints.maxWidth - sealBox.width) / 2,
                    y = column.sealTop,
                )
            }
        }

        StampDateBlock(
            date = date,
            palette = palette,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = StampDateBottom)
                .stampReveal(dateReveal),
        )

        StampTearLine(
            palette = palette,
            alpha = tearAlpha,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = StampTearBottom, start = StampTearInset, end = StampTearInset),
        )

        // 底部那一条。开屏在这儿放「轻触跳过」，别的呈现空着——撕口以下那一段才是
        // 落款的地方，见下面那个 footnote 槽。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = StampStripHeight),
        ) {
            strip()
        }

        // 撕口虚线以下那一段的正中。那一段是存根：导出图在那儿落款（图标 + 应用名），
        // 未来那一页在那儿写「那天见」，开屏在那儿放「轻触跳过」——三者同高。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(StampTearBottom),
            contentAlignment = Alignment.Center,
        ) {
            footnote()
        }
    }
}

/** 开屏的静止背景：光锥停在张开态、尘埃不飘、颗粒与四角压暗都到位 */
@Composable
internal fun StampBackdrop(palette: SplashPalette) {
    SplashBackdrop(
        palette = palette,
        beamScale = 1f,
        beamAlpha = palette.beamAlpha,
        grainAlpha = palette.grainAlpha,
        vignetteAlpha = 1f,
        drift = false,
    )
}

// ---------------------------------------------------------------------------
// 页内元素
// ---------------------------------------------------------------------------

/**
 * 一行台词的字号、行高、字距。
 *
 * 字号随行数递减，保证 4 行也不触发自动折行——自动折行会把断句断在错误的地方。
 * 英文原文一行放得下更多字，于是整体小一档、走斜体。
 */
internal class StampLineSpec(
    val fontSize: Float,
    val lineHeight: Float,
    val letterSpacing: Float,
    val italic: Boolean,
)

/**
 * 台词那一行的字号档。
 *
 * 开屏是铺满整屏的一页，日签卡是缩进槽里的那一张：同一页版式，卡上的字要大两档才看得清
 * （用户 2026-09-13 两次定：只放卡片与导出，开屏不动）。差值是逐项量出来的两档，不是同一个
 * 倍率——中文行 23/21/19 → 27/25/23，英文 20/19/17.5 → 24/23/21.5，出处 13 → 17，
 * 印文 17 → 21（印面跟着 46 → 56，见 QuoteSeal），出处里那个年份小字 11 → 13。
 * 日期那一块（月日 38sp、年份 11sp）两档相同：它不是「别的部分」，是这一页的落款。
 */
internal enum class StampTextSize {
    /** 开屏 */
    Full,

    /** 日签卡与导出图 */
    Card,
}

/** 见 [StampLineSpec] */
internal fun stampLineSpec(
    isEnglish: Boolean,
    lineCount: Int,
    size: StampTextSize = StampTextSize.Full,
): StampLineSpec {
    // 卡片那一档就是在开屏那一档上加 4sp：行数与语种的分档规则两处共用
    val step = if (size == StampTextSize.Card) 4f else 0f
    return when {
        isEnglish && lineCount >= 4 -> StampLineSpec(17.5f + step, 1.58f, 0.006f, italic = true)
        isEnglish && lineCount == 3 -> StampLineSpec(19f + step, 1.58f, 0.006f, italic = true)
        isEnglish -> StampLineSpec(20f + step, 1.58f, 0.006f, italic = true)
        lineCount >= 4 -> StampLineSpec(19f + step, 1.60f, 0.012f, italic = false)
        lineCount == 3 -> StampLineSpec(21f + step, 1.64f, 0.012f, italic = false)
        else -> StampLineSpec(23f + step, 1.64f, 0.012f, italic = false)
    }
}

/** 单行台词 */
@Composable
internal fun StampQuoteLine(
    text: String,
    palette: SplashPalette,
    isEnglish: Boolean,
    lineCount: Int,
    modifier: Modifier = Modifier,
    size: StampTextSize = StampTextSize.Full,
) {
    val spec = remember(isEnglish, lineCount, size) { stampLineSpec(isEnglish, lineCount, size) }
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        color = palette.ink,
        fontSize = spec.fontSize.sp,
        lineHeight = (spec.fontSize * spec.lineHeight).sp,
        fontFamily = FontFamily.Serif,
        // 600 而不是 500：衬线族在多数机器上只装了 400 和 700 两个字重，中间值按最近的一档取，
        // 500 会被取回 400——写着 Medium，画出来是常规体，也就是「太细」的由来。600 落到 700
        // 那一侧，装了可变字体的机器上还能拿到真正的 600。台词是这一页的主体，该比正文重一档。
        fontWeight = FontWeight.SemiBold,
        fontStyle = if (spec.italic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = spec.letterSpacing.em,
        textAlign = TextAlign.Center,
    )
}

/**
 * 出处行：破折号 + 书名号包起的片名 + 小一号的年份。
 *
 * 年份靠字号退一档，不靠透明度：它是注解不是标题，同号同重会和片名抢注意力；
 * 而按透明度再兑一档必然掉到 4.5:1 以下（见 SplashPalette.inkSoft 的注释）。
 *
 * [onClick] 不为空时整行可点（日签卡用它进影片详情），开屏那一屏不可点——
 * 那里点哪儿都是跳过。
 *
 * [size] 与台词同一档：卡片上的出处比开屏大一档，见 [StampTextSize]。
 */
@Composable
internal fun StampSourceLine(
    title: String,
    titleWrap: Pair<String, String>,
    year: Int,
    palette: SplashPalette,
    modifier: Modifier = Modifier,
    size: StampTextSize = StampTextSize.Full,
    onClick: (() -> Unit)? = null,
    clickSemantic: HapticSemantic? = HapticSemantic.LIGHT_TAP,
) {
    // 片名跟着台词一起放大；年份是注解，只跟着往上抬两档，别抢片名的位置
    val titleStep = if (size == StampTextSize.Card) 4f else 0f
    val yearStep = if (size == StampTextSize.Card) 2f else 0f
    val text = remember(title, titleWrap, year, titleStep) {
        buildAnnotatedString {
            append(EM_DASH)
            append(' ')
            append(titleWrap.first)
            append(title)
            append(titleWrap.second)
            append(' ')
            withStyle(SpanStyle(fontSize = (11f + yearStep).sp)) {
                append("($year)")
            }
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val clickable = if (onClick == null) {
        modifier
    } else {
        modifier.hapticClickable(
            interactionSource = interactionSource,
            indication = null,
            semantic = clickSemantic,
            onClick = onClick,
        )
    }
    Text(
        text = text,
        modifier = clickable.fillMaxWidth(),
        color = palette.inkSoft,
        fontSize = (13f + titleStep).sp,
        letterSpacing = 0.1.em,
        textAlign = TextAlign.Center,
    )
}

/**
 * 台词区：几行台词 + 出处行。行间的那点空由字号自带的行高给，这里只补台词到出处那一段。
 *
 * 台词和出处一起给，是因为它们之间的间距属于版面（[StampLinesToSource]），
 * 拆给调用方各排一次，两处迟早会差几个 dp。
 */
@Composable
internal fun ColumnScope.StampQuoteBlock(
    lines: List<String>,
    isEnglish: Boolean,
    title: String,
    titleWrap: Pair<String, String>,
    year: Int,
    palette: SplashPalette,
    size: StampTextSize = StampTextSize.Full,
    lineReveal: (Int) -> StampReveal = { StampReveal() },
    sourceReveal: StampReveal = StampReveal(),
    sourceOnClick: (() -> Unit)? = null,
) {
    lines.forEachIndexed { index, line ->
        StampQuoteLine(
            text = line,
            palette = palette,
            isEnglish = isEnglish,
            lineCount = lines.size,
            modifier = Modifier.stampReveal(lineReveal(index)),
            size = size,
        )
    }
    Spacer(Modifier.height(StampLinesToSource))
    StampSourceLine(
        title = title,
        titleWrap = titleWrap,
        year = year,
        palette = palette,
        modifier = Modifier.stampReveal(sourceReveal),
        size = size,
        onClick = sourceOnClick,
    )
}

/**
 * 海报的装裱：奶油色卡纸 + 一层投影 + 压在海报上的纸色，[content] 是海报本体。
 *
 * 压色是必要的：未处理的彩色海报直接贴在暖纸上，看起来像硬插进来的一块图，
 * 压掉一点饱和度之后它才像原本就印在这张纸上。
 */
@Composable
internal fun StampPosterFrame(
    palette: SplashPalette,
    size: DpSize,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tintColor = if (palette.isDark) Color(0xFF16100B) else palette.paper
    val tintAlpha = if (palette.isDark) 0.22f else 0.14f
    Box(
        modifier = modifier
            .size(size)
            .stampPosterShadow(palette)
            .clip(RoundedCornerShape(PosterCorner))
            .background(palette.cream)
            // 卡纸边：三面 7dp，底边 15dp。相纸的白边从来不是四边等宽——下边宽出来的那一截
            // 是留给手拿的地方，也让海报在这一列里读起来是「装裱好的一张」，不是一块贴纸。
            .padding(
                start = PosterMat,
                top = PosterMat,
                end = PosterMat,
                bottom = PosterMatBottom,
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 内层圆角比外层小一档：卡纸的角总是比压在里面那张相纸圆一点
                .clip(RoundedCornerShape(PosterWindowCorner))
                .drawWithContent {
                    drawContent()
                    drawRect(color = tintColor, alpha = tintAlpha, blendMode = BlendMode.Multiply)
                }
        ) {
            content()
        }
    }
}

/**
 * 海报的投影。
 *
 * 自己画而不是 `Modifier.shadow`：平台投影走的是 GraphicsLayer/RenderNode，
 * 而日签卡要用 Picture 录下这一页重放成导出图，图层在录制里会整块漏掉——屏幕上有的影，
 * 导出图上会没有。九层同心圆角矩形逐层收进去、每层都是一档很淡的黑，出来是一圈没有台阶的影。
 */
private fun Modifier.stampPosterShadow(palette: SplashPalette): Modifier = drawBehind {
    val spread = PosterShadowSpread.toPx()
    val drop = PosterShadowDrop.toPx()
    val corner = PosterCorner.toPx()
    val ink = if (palette.isDark) PosterShadowInkDark else PosterShadowInkLight
    val step = ink / PosterShadowSteps
    repeat(PosterShadowSteps) { index ->
        val grow = spread * (PosterShadowSteps - index) / PosterShadowSteps
        drawRoundRect(
            color = Color.Black.copy(alpha = step),
            topLeft = Offset(-grow, -grow + drop),
            size = Size(size.width + grow * 2f, size.height + grow * 2f),
            cornerRadius = CornerRadius(corner + grow),
        )
    }
}

/**
 * 海报尺寸。
 *
 * 挤的从来是纵向——这一页是一整列竖排，窄屏本来就留着页边距，真会把印章顶出去的是矮屏。
 * 所以矮屏（和超大字号）整块收一档，四行台词的那一档再收一点。
 *
 * [compact] 由调用方判：开屏看真实屏高，卡片的设计高度是固定的（见 [StampDesignHeight]），
 * 于是只有超大字号那一档需要收。
 */
internal fun stampPosterSize(lineCount: Int, compact: Boolean): DpSize = when {
    compact -> DpSize(PosterCompactWidth, PosterCompactHeight)
    lineCount >= PosterSqueezeLines ->
        DpSize(PosterWidth * PosterSqueeze, PosterHeight * PosterSqueeze)
    else -> DpSize(PosterWidth, PosterHeight)
}

/**
 * 印章底下的日期：月日一行大字，年份小字垫在下面。
 *
 * 日期和印章一起是这一页的落款——先有画面和台词，再盖章，最后记下是哪一天。
 *
 * 月日用衬线实心墨（纸上 5.87:1），斜杠单独退到 [SlashInk]：它是分隔符不是信息，
 * 和数字同一个浓度会读成三段等重的字符。年份走 [stampDateInk]，加上 0.42em 字距——
 * 那点刻度感是从顶部那行小字搬下来的，不该在搬家的路上丢掉。
 *
 * 固定 [Locale.US]：跟随系统 Locale 会在部分语言下渲染成非阿拉伯数字，等宽刻度感就没了。
 *
 * 整块合成一个语义节点：逐个字符念出来是「零九 斜杠 零五 二零二六」，
 * 而这里要说的只是一个日期。
 */
@Composable
internal fun StampDateBlock(
    date: LocalDate,
    palette: SplashPalette,
    modifier: Modifier = Modifier,
) {
    val spoken = remember(date) {
        String.format(Locale.US, "%d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
    }
    val monthDay = remember(date) {
        buildAnnotatedString {
            append(String.format(Locale.US, "%02d", date.monthValue))
            withStyle(
                SpanStyle(
                    color = palette.ink.copy(alpha = SlashInk),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Normal,
                )
            ) {
                append(DateSlash)
            }
            append(String.format(Locale.US, "%02d", date.dayOfMonth))
        }
    }
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = monthDay,
            color = palette.ink,
            fontSize = 38.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = date.year.toString(),
            modifier = Modifier.padding(top = 3.dp),
            color = stampDateInk(palette),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.42.em,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 撕口虚线。
 *
 * 同一档 inkFaint（画线用的那一档）画出来：开屏、日签卡、导出图上的撕口必须是同一条线，
 * 否则从开屏走到日签会看出换了张纸。
 */
@Composable
internal fun StampTearLine(palette: SplashPalette, alpha: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
        if (alpha <= 0.001f || size.width <= 0f) return@Canvas
        val dash = TearDash.toPx()
        drawLine(
            color = palette.inkFaint,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
            alpha = alpha,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 1.6f)),
        )
    }
}
