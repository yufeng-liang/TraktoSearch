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

/**
 * 帮助页的说明书色板。
 *
 * 只映射当前 [MaterialTheme] 的中性色和强调色，不再借用开屏黄纸色。命名沿用旧组件
 * 使用的 ink / seal，避免排版组件为换色做无关重构。
 */
@Immutable
internal data class HelpPaperPalette(
    val paper: Color,
    val cream: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    val seal: Color,
    val ochre: Color,
)

@Immutable
internal data class HelpPaper(
    val palette: HelpPaperPalette,
    val body: Color,
)

/** 取当前主题的 background / surface / onSurface 等颜色，动态主题切换时同步更新。 */
@Composable
internal fun rememberHelpPaper(): HelpPaper {
    val colors = MaterialTheme.colorScheme
    return HelpPaper(
        palette = HelpPaperPalette(
            paper = colors.background,
            cream = colors.surface,
            ink = colors.onSurface,
            inkSoft = colors.onSurfaceVariant,
            inkFaint = colors.outlineVariant,
            seal = colors.primary,
            ochre = colors.secondary,
        ),
        body = colors.onSurface,
    )
}

/** Unicode 圆圈数字覆盖 1 至 20；更长章节安全回退普通阿拉伯数字。 */
private val CIRCLED_NUMERALS = listOf(
    "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
    "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
)

/** 段落编号，[index] 从 0 起；所有语言统一使用阿拉伯数字。 */
internal fun helpSectionNumeral(index: Int): String = (index + 1).toString()

/** 条目编号，[index] 从 0 起；1 至 20 使用圆圈数字，超出后安全回退普通数字。 */
internal fun helpItemNumeral(index: Int): String =
    CIRCLED_NUMERALS.getOrNull(index) ?: (index + 1).toString()

/**
 * 把命中的关键词标成当前主题强调色。
 *
 * 只上色 + 一层极淡衬底，不加粗：加粗会让命中的词在行内跳出，
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

/** 一道极淡分隔线，说明书排版里代替卡片做分界。 */
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
 * 编号用当前主题强调色，形成全页稳定的视觉骨架。
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
 * 编号占一列固定宽，让所有条目的文字左边缘对齐。
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
 * 用当前主题 surface 装表格和代码块，再加一道 outlineVariant 边界。
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
