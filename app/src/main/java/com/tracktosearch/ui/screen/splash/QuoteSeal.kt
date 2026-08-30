package com.tracktosearch.ui.screen.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 日签关键词的印章。
 *
 * 关键词是从整部片提炼的主题词，不是台词里摘的短句，所以它需要一个「盖上去」的形态
 * 而不是又一行正文。取自闲章：朱色印边、界格留白、印文撑满，再压一层不匀的印泥。
 *
 * 三种形态，按字数分：
 * - 两字：46×46 方印，竖排一列。方块字竖着叠才把上下撑满，横排会拉成一条像标签。
 * - 三字：46×64 长方印，同样竖排一列，字号不缩。三字塞进方印只能缩到 0.56，
 *   那一枚会比别的印明显小一号；改印章形状而不是改字号，三档字才是同一个视觉重量。
 * - 四字：46×46 方印，2+2 两行两列，字号让一档到 0.88。
 *
 * 中文印文走繁体（见 SplashQuote.sealKeywordFor），[latin] 是印在方印下方的英文小字，
 * CJK 语言下才有；界面本来是英文时传 null，同一个词印两遍不是设计。
 *
 * [ground] 是印章压着的底色。做旧那层是「拿底色按噪点盖掉一部分印面」，所以它必须知道
 * 底色是什么：开屏压在 paper 上，日签卡片压在 sheet 上。传错了会在印面上留下一层色差。
 */
@Composable
internal fun QuoteSeal(
    keyword: String,
    latin: String?,
    palette: SplashPalette,
    modifier: Modifier = Modifier,
    ground: Color = palette.paper,
    sealWidth: Dp = SEAL_WIDTH,
    fontSize: TextUnit = 17.sp,
    latinFontSize: TextUnit = 9.sp,
) {
    if (keyword.isBlank()) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isSquareSeal(keyword)) {
            CarvedSeal(keyword, palette, ground, sealWidth, fontSize)
        } else {
            RibbonSeal(keyword, palette, ground, fontSize)
        }
        if (latin != null) {
            Text(
                text = latin.uppercase(),
                modifier = Modifier.padding(top = 6.dp),
                color = palette.seal.copy(alpha = 0.88f),
                fontSize = latinFontSize,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.18.em,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 刻出来的那枚印：CJK 关键词。
 *
 * 印文一行一个 Text，行内容可能是一个字（竖排）或两个字（四字印的一行）。
 * 字形做了 6% 横向、10% 纵向的拉伸——印章上的字本来就不是印刷体的比例，
 * 撑满印面才像刻的；拉伸走 graphicsLayer，不占布局尺寸，界格的留白按未拉伸的字号算。
 */
@Composable
private fun CarvedSeal(
    keyword: String,
    palette: SplashPalette,
    ground: Color,
    sealWidth: Dp,
    fontSize: TextUnit,
) {
    val rows = sealRows(keyword)
    // 竖排一列时行距收到 .98，才给界格让出一圈白；两行两列不用收，本来就有余量
    val column = rows.all { it.length == 1 }
    val glyphSize = fontSize * glyphScale(keyword)
    Box(
        modifier = Modifier
            .size(width = sealWidth, height = if (rows.size == 3) RECT_HEIGHT else sealWidth)
            .sealFace(palette.seal, ground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            rows.forEach { row ->
                Text(
                    text = row,
                    modifier = Modifier.graphicsLayer {
                        scaleX = GLYPH_SCALE_X
                        scaleY = GLYPH_SCALE_Y
                    },
                    color = palette.seal,
                    fontSize = glyphSize,
                    lineHeight = glyphSize * if (column) 0.98f else 1.06f,
                    fontFamily = SealFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.02).em,
                    style = TightLineStyle,
                )
            }
        }
    }
}

/** 长印：拉丁关键词专用。「Farewell」塞不进方印，改成长条 + 拉开的字距，仍是一枚印 */
@Composable
private fun RibbonSeal(
    keyword: String,
    palette: SplashPalette,
    ground: Color,
    fontSize: TextUnit,
) {
    Box(
        modifier = Modifier
            .sealFace(palette.seal, ground, borderWidth = 1.2.dp, innerFrame = false)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = keyword.uppercase(),
            color = palette.seal,
            fontSize = fontSize * 0.62f,
            fontFamily = SealFontFamily,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.em,
        )
    }
}

/**
 * 印面：印泥底 → 印文 → 外围洇色 → 印边 → 界格 → 咬痕。
 *
 * 顺序就是盖章的顺序，也是它们必须的叠放次序：印文压在印泥底上，三条线画在印文之上
 * （印章是刻出来的，边框不会被印文盖住），咬痕最后盖，把上面所有东西一起吃掉一部分。
 *
 * 洇色和咬痕都画到印面之外一点，不裁切——印泥压出去的那一圈本来就在印外面。
 */
private fun Modifier.sealFace(
    seal: Color,
    ground: Color,
    borderWidth: Dp = BORDER_WIDTH,
    innerFrame: Boolean = true,
) = drawWithContent {
    val radius = CORNER.toPx()
    drawRoundRect(color = seal.copy(alpha = 0.07f), cornerRadius = CornerRadius(radius))
    drawContent()
    drawBleed(seal)
    drawFrame(seal, borderWidth.toPx(), radius)
    if (innerFrame) drawInnerFrame(seal)
    drawMottle(ground)
}

/** 外围洇色：印泥压出去的一圈，描边中线正好落在印边上，一半在外一半在内 */
private fun DrawScope.drawBleed(seal: Color) {
    val width = 2.6.dp.toPx()
    drawRoundRect(
        color = seal.copy(alpha = 0.12f),
        cornerRadius = CornerRadius((4.dp.toPx() - width / 2f).coerceAtLeast(0f)),
        style = Stroke(width),
    )
}

/** 印边：整枚印最实的一条线，描边整条压在印面里侧 */
private fun DrawScope.drawFrame(seal: Color, width: Float, radius: Float) {
    drawRoundRect(
        color = seal.copy(alpha = 0.85f),
        topLeft = Offset(width / 2f, width / 2f),
        size = Size(size.width - width, size.height - width),
        cornerRadius = CornerRadius((radius - width / 2f).coerceAtLeast(0f)),
        style = Stroke(width),
    )
}

/** 界格：闲章里把印文和印边隔开的那条细线。它定住了四周那圈留白，去掉印文就会贴边 */
private fun DrawScope.drawInnerFrame(seal: Color) {
    val width = 0.7.dp.toPx()
    val inset = 4.dp.toPx() + width / 2f
    drawRoundRect(
        color = seal.copy(alpha = 0.22f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius((1.5.dp.toPx() - width / 2f).coerceAtLeast(0f)),
        style = Stroke(width),
    )
}

/**
 * 咬痕：拿底色按噪点瓦片盖一层，把印面吃掉一部分。噪点见 [SealInk]。
 *
 * 平铺按整数像素步进，不按浮点累加：浮点会在瓦片之间留下一像素的缝，缝连成网格线，
 * 一眼就能看出这是贴图。
 */
private fun DrawScope.drawMottle(ground: Color) {
    val step = SealInk.tileSize.roundToPx().coerceAtLeast(1)
    val bleed = 1.dp.roundToPx()
    val src = IntSize(SealInk.TILE_PX, SealInk.TILE_PX)
    val dst = IntSize(step, step)
    val filter = ColorFilter.tint(ground)
    val right = size.width.toInt() + bleed
    val bottom = size.height.toInt() + bleed
    var y = -bleed
    while (y < bottom) {
        var x = -bleed
        while (x < right) {
            drawImage(
                image = SealInk.tile,
                srcOffset = IntOffset.Zero,
                srcSize = src,
                dstOffset = IntOffset(x, y),
                dstSize = dst,
                alpha = SealInk.OPACITY,
                colorFilter = filter,
            )
            x += step
        }
        y += step
    }
}

/**
 * 方印只给 4 字以内的 CJK 关键词。
 *
 * 判据用码位而不是语言标签：日文关键词是汉字、韩文是谚文，都在 0x2E80 之上，
 * 而拉丁字母在下面。这样加第五种语言时不用回来改这里。
 */
private fun isSquareSeal(keyword: String): Boolean =
    keyword.length <= 4 && keyword.all { it.code >= CJK_START }

/** 两字、三字竖排一列（一行一个字）；四字排 2+2 */
private fun sealRows(keyword: String): List<String> =
    if (keyword.length == 4) {
        listOf(keyword.substring(0, 2), keyword.substring(2, 4))
    } else {
        keyword.map { it.toString() }
    }

/**
 * 印文字号相对基准的倍数。
 *
 * 三字撑成长方印之后不用缩，和两字同一个视觉重量；四字挤在方印里得让一档，
 * 0.88 是两行两列都还不碰界格的上限。
 *
 * 谚文再让一档：韩文的方块比汉字宽，同字号下「석별」竖排一列会顶到界格。
 */
private fun glyphScale(keyword: String): Float {
    val base = if (keyword.length == 4) 0.88f else 1f
    return if (keyword.any { it.code in HANGUL_SYLLABLES }) base * 0.88f else base
}

/**
 * 印文字体。
 *
 * 设计定的是隸書：横画收笔上扬、结体扁方，正好填满方印，而且隶变之后的字形普通人认得出
 * ——小篆认不出，古印体（隸書骨架 + 边缘残缺）是日本印章行业的专门书体，但唯一免费的
 * 白舟古印体教漢只收 1026 个教育漢字，我们的关键词有 454 个不同汉字，缺字会在一枚印里
 * 混出两种字形。
 *
 * 所以这里暂时落回 [FontFamily.Serif]（Android 上是思源宋体一族）：免费商用中文字体里
 * 隸書这一类几乎全是商业授权，挂开源协议的只有 GPLv3 的 UnYetgul（韓國隸書），GPL 字体
 * 不带字体例外条款打进 APK 有授权风险；王漢宗中隸書繁虽标 GPL，但整个王漢宗系列在台湾
 * 业界被指有侵权争议。真要上隸書，拿一份授权明确的字库子集化后放进 res/font
 * （关键词是闭集，四语合计一两百 KB，做法见 com.tracktosearch.ui.theme.PixelFontFamily），
 * 然后只改这一行。
 */
private val SealFontFamily = FontFamily.Serif

/**
 * 印文的行盒：行高严格等于 lineHeight，字形在行盒里居中。
 *
 * 默认行盒会带上字体自己的上下留白，两个 17sp 的汉字竖排起来就有 46dp 高，正好顶穿
 * 46dp 的印面。Trim.Both 去掉首末行那份留白，行高才真的是设定的倍数。
 */
private val TightLineStyle = TextStyle(
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

private val SEAL_WIDTH = 46.dp

/** 三字长方印的高度：宽度不变，只把上下撑开，容得下竖排一列三个字 */
private val RECT_HEIGHT = 64.dp
private val CORNER = 3.dp
private val BORDER_WIDTH = 1.4.dp
private const val GLYPH_SCALE_X = 1.06f
private const val GLYPH_SCALE_Y = 1.10f
private const val CJK_START = 0x2E80
private val HANGUL_SYLLABLES = 0xAC00..0xD7A3
