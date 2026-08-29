package com.tracktosearch.ui.screen.help

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.tracktosearch.ui.screen.splash.SplashPalette

/**
 * 帮助页的纸面色板。
 *
 * 包一层 [SplashPalette] 而不是新起一套色：这一页要和开屏台词、日签卡片是同一张纸，
 * 换套色就看出换了张纸。
 *
 * 唯一的改动是正文墨色。[SplashPalette.ink] 压在浅色纸上只有 4.36:1 对比度——够读
 * 开屏那四秒，不够读一整页（WCAG AA 正文要 4.5:1）。这里压深到 7.4:1，段落标题、
 * 编号、分隔线仍用原来的 ink / inkSoft / inkFaint，纸面气质不变。
 */
@Immutable
internal data class HelpPaper(
    val palette: SplashPalette,
    /** 正文墨色，比 [SplashPalette.ink] 深，专为长文可读性 */
    val body: Color,
)

/** 浅色纸上的正文墨。深色主题不需要压深：原 ink 在深纸上已有 13.5:1。 */
private val LIGHT_BODY_INK = Color(0xFF6B4430)

/**
 * 取当前主题下的纸面色板。
 *
 * 按 surface 亮度判深浅，和日签页同一条规则（本 App 主题模式由 ThemeStorage 控制，
 * 可与系统不一致，所以不能用 isSystemInDarkTheme）。
 */
@Composable
internal fun rememberHelpPaper(): HelpPaper {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(dark) {
        val palette = if (dark) SplashPalette.Dark else SplashPalette.Light
        HelpPaper(
            palette = palette,
            body = if (dark) palette.ink else LIGHT_BODY_INK,
        )
    }
}

/**
 * 中文大写数字。
 *
 * 用壹贰叁而不是一二三：一二三随手就能写，壹贰叁是要落在契约和票据上的字，
 * 那股「这是印出来的」的意思正是说明书要的。
 */
private val ZH_NUMERALS = listOf(
    "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖", "拾",
    "拾壹", "拾贰", "拾叁", "拾肆",
)

/** 日韩共用的汉数字。壹贰叁在日韩不是日常字，一二三才是。 */
private val CJK_NUMERALS = listOf(
    "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
    "十一", "十二", "十三", "十四",
)

/** 其余语言（含英文）用罗马数字：拉丁文里它才是「章」的编号。 */
private val ROMAN_NUMERALS = listOf(
    "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
    "XI", "XII", "XIII", "XIV",
)

/**
 * 段落编号，[index] 从 0 起。
 *
 * 超出预置表时退回阿拉伯数字：目录长到第十五段时这里不该是崩溃点，
 * 补表的提醒交给 HelpPaperTest。
 */
internal fun helpSectionNumeral(lang: String, index: Int): String {
    val table = when (lang) {
        "zh" -> ZH_NUMERALS
        "ja", "ko" -> CJK_NUMERALS
        else -> ROMAN_NUMERALS
    }
    return table.getOrNull(index) ?: (index + 1).toString()
}

/**
 * 条目编号，[index] 从 0 起。
 *
 * 比段落编号轻一档：段落是「章」，条目只是章内的第几条。中日韩都用小写汉数字，
 * 拉丁语系用阿拉伯数字——罗马数字放在正文里会和段落编号撞辈分。
 */
internal fun helpItemNumeral(lang: String, index: Int): String = when (lang) {
    "zh", "ja", "ko" -> CJK_NUMERALS.getOrNull(index) ?: (index + 1).toString()
    else -> (index + 1).toString()
}

/**
 * 把命中的关键词标成朱砂，像用红铅笔划过一道。
 *
 * 只上色 + 一层极淡的朱砂衬底，不加粗：加粗会让命中的词在纸面上跳出行，
 * 读者反而看不清它在句子里的位置。
 */
internal fun helpHighlight(text: String, query: String, seal: Color): AnnotatedString {
    val keyword = query.trim()
    if (keyword.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor <= text.length - keyword.length) {
            val hit = text.indexOf(keyword, cursor, ignoreCase = true)
            if (hit < 0) break
            append(text, cursor, hit)
            withStyle(SpanStyle(color = seal, background = seal.copy(alpha = 0.14f))) {
                append(text, hit, hit + keyword.length)
            }
            cursor = hit + keyword.length
        }
        append(text, cursor, text.length)
    }
}

/**
 * 页首的书名。
 *
 * 字距拉到 0.42em 让四个字散开成题签，下面一道短横线收住——横线不满宽，
 * 满宽就成了分隔线，这里要的是「题目底下画一笔」。
 */
@Composable
internal fun HelpMasthead(title: String, paper: HelpPaper) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = paper.palette.ink,
            fontSize = 19.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.42.em,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(1.dp)
                .background(paper.palette.inkFaint)
        )
    }
}

/** 分组小标题。字距同题签，但字号压到最小，让它像页边的类目标注而不是又一个标题。 */
@Composable
internal fun HelpGroupLabel(label: String, paper: HelpPaper) {
    Text(
        text = label,
        color = paper.palette.inkSoft,
        fontSize = 11.sp,
        fontFamily = FontFamily.Serif,
        letterSpacing = 0.34.em,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

/** 一道极淡的墨线，纸质页里代替卡片做分界。实线会把纸切开，所以只用 inkFaint 的一半。 */
@Composable
internal fun HelpInkRule(paper: HelpPaper, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(paper.palette.inkFaint.copy(alpha = paper.palette.inkFaint.alpha * 0.5f))
    )
}

/**
 * 段落标题行：编号 · 标题 ————— 折角。
 *
 * 编号用 [SplashPalette.seal] 的朱砂，是整行唯一的另一个色相——纸面全是褐色，
 * 朱砂编号一竖排下来就成了页面的骨架。
 *
 * 折角图标只做旋转不做换图：换图（ExpandMore ↔ ExpandLess）中间会闪一帧，
 * 旋转是连续的，像纸角被慢慢揭开。
 */
@Composable
internal fun HelpSectionHeader(
    numeral: String,
    title: AnnotatedString,
    expanded: Boolean,
    paper: HelpPaper,
    onToggle: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "helpChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = numeral,
            color = paper.palette.seal,
            fontSize = 13.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.06.em,
        )
        Text(
            text = title,
            color = paper.palette.ink,
            fontSize = 16.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.04.em,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = paper.palette.inkFaint,
            modifier = Modifier
                .size(17.dp)
                .rotate(rotation),
        )
    }
}

/**
 * 一条正文。
 *
 * 编号占一列固定宽，让所有条目的文字左边缘对齐——不对齐的话十条正文会看成十个碎块。
 * 22dp 是按中日韩「十一」和英文「10」里较宽的那个量的。
 */
@Composable
internal fun HelpItem(
    numeral: String,
    text: AnnotatedString,
    paper: HelpPaper,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = numeral,
            color = paper.palette.inkFaint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Serif,
            lineHeight = 23.sp,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = text,
            color = paper.body,
            fontSize = 14.sp,
            lineHeight = 23.sp,
            fontFamily = FontFamily.Serif,
            letterSpacing = 0.01.em,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 段内小标题。左边缘和条目的编号列对齐，不缩进——它是这一小节的头，不是某条的下属。 */
@Composable
internal fun HelpSubtitle(text: String, paper: HelpPaper) {
    Text(
        text = text,
        color = paper.palette.ink,
        fontSize = 12.5.sp,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.08.em,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
    )
}

/**
 * 铺在纸上的另一档纸色，装表格和代码块。
 *
 * 用 [SplashPalette.cream]：浅色下比纸面深一档、深色下浅一档，两边都读成「同一张纸的
 * 另一块区域」。再加一道极淡的边——没有边的话它在纸上像一块洇开的水渍。
 */
@Composable
internal fun HelpPaperPanel(
    paper: HelpPaper,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(paper.palette.cream)
            .border(
                width = 1.dp,
                color = paper.palette.inkFaint.copy(alpha = paper.palette.inkFaint.alpha * 0.4f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        content()
    }
}

/**
 * 代码块。
 *
 * 唯一保留等宽字的地方：这是要照着抄进配置里的 JSON，衬线字下「l」和「1」分不开，
 * 抄错一个字符整个搜索源就不通。等宽在这里不是风格选择。
 */
@Composable
internal fun HelpCodeBlock(text: String, paper: HelpPaper) {
    HelpPaperPanel(paper = paper) {
        Text(
            text = text,
            color = paper.body,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
        )
    }
}
