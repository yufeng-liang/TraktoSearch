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
import androidx.compose.ui.text.font.Font
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
import com.tracktosearch.R

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
 * [sealLang] 是 [keyword] 那个字形所属的语言，用来选印文字体，见 [sealTypeface]。
 * 不从码位反推：中日两种关键词都可能是纯汉字，而它们要走的字库不是同一份。
 *
 * [ground] 是印章压着的底色。做旧那层是「拿底色按噪点盖掉一部分印面」，所以它必须知道
 * 底色是什么：开屏压在 paper 上，日签卡片压在 sheet 上。传错了会在印面上留下一层色差。
 */
@Composable
internal fun QuoteSeal(
    keyword: String,
    latin: String?,
    sealLang: String,
    palette: SplashPalette,
    modifier: Modifier = Modifier,
    ground: Color = palette.paper,
    sealWidth: Dp = SEAL_WIDTH,
    fontSize: TextUnit = 17.sp,
    latinFontSize: TextUnit = 9.sp,
) {
    if (keyword.isBlank()) return
    val typeface = sealTypeface(sealLang)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isSquareSeal(keyword)) {
            CarvedSeal(keyword, typeface, palette, ground, sealWidth, fontSize)
        } else {
            RibbonSeal(keyword, typeface, palette, ground, fontSize)
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
    typeface: SealTypeface,
    palette: SplashPalette,
    ground: Color,
    sealWidth: Dp,
    fontSize: TextUnit,
) {
    val rows = sealRows(keyword)
    // 竖排一列时行距收到 .98，才给界格让出一圈白；两行两列不用收，本来就有余量
    val column = rows.all { it.length == 1 }
    val glyphSize = fontSize * glyphScale(keyword, typeface)
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
                    fontFamily = typeface.family,
                    fontWeight = typeface.weight,
                    letterSpacing = (-0.02).em,
                    style = TightLineStyle,
                )
            }
        }
    }
}

/** 长印：拉丁关键词专用。「Unbound」塞不进方印，改成长条 + 拉开的字距，仍是一枚印 */
@Composable
private fun RibbonSeal(
    keyword: String,
    typeface: SealTypeface,
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
            fontFamily = typeface.family,
            fontWeight = typeface.weight,
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
 * 再乘一档字体自己的补偿：不同字体在同一字号下的字面大小差得很远，见 [SealTypeface.sizeScale]。
 */
private fun glyphScale(keyword: String, typeface: SealTypeface): Float =
    (if (keyword.length == 4) 0.88f else 1f) * typeface.sizeScale

/** 印文的字体、它该用的字重，以及把它的字面拉到跟基准一致的倍数，见 [sealTypeface] */
private class SealTypeface(
    val family: FontFamily,
    val weight: FontWeight,
    /**
     * 字号补偿。
     *
     * 同一个字号下各字体的字面大小差很多：在中日关键词共用的那 235 个汉字上量到的字面
     * 高度（em = 100 时的墨迹高度）衬线是 92、王漢宗隸書只有 72，UnYetgul 的谚文 80。
     * 隸書本来就是扁方结体，补偿只补到「墨迹刚好占满界格里的地方」，不去把它拉成方的
     * ——那就不是隸書了。
     *
     * 每一档都是把台词库里所有关键词逐个算过余量定下来的，见各字体自己的注释。量的是
     * 墨迹外框到界格的距离，上下左右分开算：界格是画出来的一条线，越界的是笔画，不是
     * 字体的行盒——UnYetgul 的行盒有 1.21em，在任何可用字号下都比行高高，但笔画离界格
     * 还远。算的是字体度量而不是真机截图，Compose 的 [TightLineStyle] 居中和这套算法
     * 差零点几 dp，所以余量留在 1dp 以上才算安全。
     */
    val sizeScale: Float,
)

/**
 * 印文字体，按印面字形的语言选。
 *
 * 设计定的是隸書：横画收笔上扬、结体扁方，正好填满方印，而且隶变之后的字形普通人认得出
 * ——小篆认不出，古印体（隸書骨架 + 边缘残缺）是日本印章行业的专门书体，但唯一免费的
 * 白舟古印体教漢只收 1026 个教育漢字，我们的繁体关键词有 464 个不同汉字，缺字会在一枚印里
 * 混出两种字形。
 *
 * 免费商用中文字体里隸書一类几乎全是商业授权，挂开源协议的只有两份，仓库里用的就是它们：
 * - 中文（[HAN_LANG]）走王漢宗中隸書繁，GPL-2.0 或更新版本，繁体关键词 464 字全覆盖。
 * - 韩文（[HANGUL_LANG]）走 UnYetgul 은 옛글，同为 GNU GPL，韩文关键词 376 字全覆盖。
 *   它是隸書骨架的谚文字体，和中文那份不同源，但都是「刻」出来的气质。
 * 两份都是 GPL 且不带字体例外条款，随 APK 分发有授权风险；许可证与出处见
 * licenses/HanWangLiSuMedium-GPL2.txt、licenses/UnYetgul-GPL2.txt。
 *
 * 日文和英文落回 [FontFamily.Serif]：这两份字库都不能完整覆盖日文关键词——王漢宗没有假名，
 * UnYetgul 缺 61 个日文关键词用到的汉字，103 枚纯汉字的日文方印里有 18 枚踩在缺字上。
 * 缺一个字就在那枚印里混出第二种字形，与其让这 18 枚长得不一样，不如整个日文都用同一套衬线。
 *
 * 自带字库按 [FontWeight.Normal] 请求：这两份都只有一个字重，请求 SemiBold 会让 Compose
 * 合成假粗，把隶书的燕尾糊成一团。Serif 那一支反过来需要加粗才撑得住印面。
 */
private fun sealTypeface(lang: String): SealTypeface = when (lang) {
    HAN_LANG -> SealHanLiShu
    HANGUL_LANG -> SealHangulLiShu
    else -> SealSerif
}

/**
 * 回落用的系统衬线（Android 上是思源宋体一族），日文和英文关键词走它。
 *
 * 0.97 是修出来的：衬线汉字的字面比隸書高一截（92 比 72），不补偿时 276 枚日文方印里最紧的
 * 「深淵」离界格只剩 0.5dp，再往上到 1.03 就压出界格——界格本来就是用来定住那圈留白的，
 * 压出去就白设了。收到 0.97 之后剩 1.05dp，和两份隸書子集的余量落在同一档。
 *
 * 量的是本机的 Noto Serif SC（wght 600）；Android 上 [FontFamily.Serif] 解析到的是同族的
 * Noto Serif CJK，度量接近但不保证逐字相同，所以这一档留的余量按 1dp 算，不按零点几。
 *
 * 长印不吃这一档：[RibbonSeal] 的字号是 fontSize * 0.62 直接算的，不过 [glyphScale]，
 * 所以英文关键词不受这一档影响。
 */
private val SealSerif = SealTypeface(FontFamily.Serif, FontWeight.SemiBold, sizeScale = 0.97f)

/**
 * 王漢宗中隸書繁的子集，只含 assets/quotes.json 里 `keyword["zh-Hant"]` 用到的 464 个字。
 *
 * 完整字库 8.1 MB，子集 238 KB。往台词库加中文关键词时得重新子集化，否则新字静默回落系统
 * 字体。子集化命令记在 licenses/HanWangLiSuMedium-GPL2.txt 里。
 *
 * 1.02 是量出来的：全部 365 个中文关键词逐个算余量，这一档下最紧的「命定」还剩 2.21dp，
 * 要到 1.16 才有「無絕對」越界。所以卡住这一档的不是界格而是字面：隸書的墨迹只有衬线的
 * 72/92，补到 1.02 是让它和回落衬线的日文印看着一样重，再往上就反过来重一号了。
 */
private val SealHanLiShu = SealTypeface(
    family = FontFamily(Font(R.font.hanwang_lisu_seal)),
    weight = FontWeight.Normal,
    sizeScale = 1.02f,
)

/**
 * UnYetgul 은 옛글 的子集，只含 `keyword["ko"]` 用到的 376 个字，50 KB（完整 5.9 MB）。
 *
 * 0.98 取代了以前给谚文的那一档 0.88：那个 0.88 是按回落衬线的谚文定的，而这套字自己的
 * 字面只有 80（衬线汉字 92），再让 0.88 就明显小了半圈。同样逐个量过 152 枚方印，纵向
 * 最紧的「응시」还剩 0.91dp、横向最紧的「돌아보다」剩 3.68dp，到 1.04 才有「기개」越界。
 *
 * 谚文只会出现在韩文关键词里，也就只会走这套字，所以这一档补偿收在这里，
 * 不再留在 [glyphScale] 里判码位。
 */
private val SealHangulLiShu = SealTypeface(
    family = FontFamily(Font(R.font.unyetgul_seal)),
    weight = FontWeight.Normal,
    sizeScale = 0.98f,
)

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

/** 自带隸書字库覆盖到的两种语言，其余语言的印文走系统衬线，见 [sealTypeface] */
private const val HAN_LANG = "zh"
private const val HANGUL_LANG = "ko"
