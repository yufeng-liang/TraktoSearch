package com.tracktosearch.ui.screen.help

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 帮助页的排版零件。
 *
 * 原先这一页自成一套「纸质说明书」视觉：整页衬线字、em 级字距、题签 + 短横线、
 * 用极淡墨线代替卡片分界，颜色还包了一层 paper / cream / ink / seal 的别名。
 * 结果 App 里只有这一页长这样，从设置页点进来像换了个应用。现在全部改回主题
 * 排版与卡片，规格对齐搜索源管理页（[com.tracktosearch.ui.screen.searchsource]）。
 */

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
 * 把命中的关键词标成主题强调色。
 *
 * 只上色 + 一层极淡衬底，不加粗：加粗会让命中的词在行内跳出，
 * 读者反而看不清它在句子里的位置。
 */
internal fun helpHighlight(text: String, query: String, highlightColor: Color): AnnotatedString {
    val keyword = query.trim()
    if (keyword.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor <= text.length - keyword.length) {
            val hit = text.indexOf(keyword, cursor, ignoreCase = true)
            if (hit < 0) break
            append(text, cursor, hit)
            withStyle(SpanStyle(color = highlightColor, background = highlightColor.copy(alpha = 0.14f))) {
                append(text, hit, hit + keyword.length)
            }
            cursor = hit + keyword.length
        }
        append(text, cursor, text.length)
    }
}

/** 段落卡规格：20dp 圆角、无描边、极轻投影，与搜索源管理页的卡片一致。 */
internal val HELP_CARD_SHAPE = RoundedCornerShape(20.dp)

/** 分组小标题：与搜索源管理页的分组标题同字号、同左边距。 */
@Composable
internal fun HelpGroupLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

/**
 * 段落标题行：编号 + 标题 + 折角。编号走主题强调色，构成全页稳定的视觉骨架。
 *
 * 折角图标只做旋转不做换图：换图（ExpandMore ↔ ExpandLess）中间会闪一帧，
 * 旋转是连续的。
 */
@Composable
internal fun HelpSectionHeader(
    numeral: String,
    title: AnnotatedString,
    expanded: Boolean,
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
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = numeral,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .rotate(rotation),
        )
    }
}

/** 一条正文。编号占一列固定宽，让所有条目的文字左边缘对齐。 */
@Composable
internal fun HelpItem(numeral: String, text: AnnotatedString) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = numeral,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            lineHeight = 21.sp,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 21.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 段内小标题。左边缘和条目的编号列对齐，不缩进——它是这一小节的头，不是某条的下属。 */
@Composable
internal fun HelpSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

/**
 * 表格与代码块的底。段落卡本身是 surfaceVariant，这层用 surface 差一档，
 * 再加一道 outlineVariant 描边，让附录性质的内容和正文分开。
 */
@Composable
internal fun HelpPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        content()
    }
}

/**
 * 代码块。
 *
 * 唯一保留等宽字的地方：这是要照着抄进配置里的 JSON，比例字体下「l」和「1」分不开，
 * 抄错一个字符整个搜索源就不通。等宽在这里不是风格选择。
 */
@Composable
internal fun HelpCodeBlock(text: String) {
    HelpPanel {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 18.sp,
        )
    }
}
