package com.tracktosearch.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.component.SectionHeader
import com.tracktosearch.ui.theme.StatusCanceled
import com.tracktosearch.ui.theme.StatusEnded
import com.tracktosearch.ui.theme.StatusInProduction
import com.tracktosearch.ui.theme.StatusPlanned
import com.tracktosearch.ui.theme.StatusReleased
import com.tracktosearch.ui.theme.StatusRumored
import com.tracktosearch.ui.theme.StatusUnknown

/**
 * 影视详情页与豆瓣条目详情页的共用视觉层。
 *
 * 两个页面都铺同一套沉浸式海报渐变，于是「文字该配黑还是白」「顶栏底色怎么算」
 * 这两段判据被逐字抄了好几份：onPosterColor / onPosterVariantColor 在
 * [DetailHeaderContent] 和 DoubanItemHeader 各一份共 4 处，吸顶 Tab 底色那
 * ~25 行在 DetailScreen 和 DoubanItemDetailScreen 里完全重复。
 * 判据收在这里，页面只管排版。
 */

// ====== 沉浸背景下的文字色 ======

/**
 * 沉浸渐变的真实底色。
 *
 * 屏上并不是原始 posterColor，而是海报色 alpha 0.70 叠主题 background 的竖向渐变
 * （见两个页面的 immersiveBackgroundModifier）。直接拿原始色判亮度会把较亮的海报
 * 误判成暗底而配白字，所以先按渐变中段 lerp 0.8f 求混合色再判。
 */
@Composable
private fun blendedBackdropIsLight(posterColor: Color): Boolean =
    lerp(posterColor, MaterialTheme.colorScheme.background, 0.8f).luminance() > 0.5f

/** 沉浸背景上的主文字色（标题等）。posterColor 为 null 时回退主题色。 */
@Composable
internal fun detailOnPosterColor(posterColor: Color?): Color = posterColor?.let { c ->
    if (blendedBackdropIsLight(c)) Color.Black.copy(alpha = 0.92f) else Color.White
} ?: MaterialTheme.colorScheme.onSurface

/** 沉浸背景上的次要文字色（原名 / 元信息等）。 */
@Composable
internal fun detailOnPosterVariantColor(posterColor: Color?): Color = posterColor?.let { c ->
    if (blendedBackdropIsLight(c)) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.72f)
} ?: MaterialTheme.colorScheme.onSurfaceVariant

// ====== 顶栏 / 吸顶 Tab 栏底色 ======

/**
 * 影视详情页顶栏与吸顶 Tab 栏共用的底色：主题 surface 实色。
 *
 * 原先顶部是三段各走一套材质的横条：状态栏铺「主题 background × 海报色 0.635」的沉浸实色、
 * 标题栏是 Haze 毛玻璃、吸顶 Tab 栏又是那个沉浸实色。滚动后三条深浅不一地叠在一起。
 * 现在统一成同一个实色，标题行与 Tab 行同在一个 Column 里一次铺底、中间不加分隔线，
 * 状态栏条用同一个值，滚过临界点时三者一起淡入。
 *
 * 不掺海报色也不留透明度：沉浸渐变留给页面背景，顶栏只做「浮在内容之上的一条」。
 * 半透明会让下面滚过的海报、卡片、文字隐约透上来，吸顶后这一条始终糊着一层动的东西；
 * surface 与 background 本身就差一档（浅色 #FFFFFF vs #F0F1F3、深色 #1A1A2E vs #0F0F1A），
 * 实色照样能和内容区分开。文字色因此可以固定走主题 onSurface，不必再按海报亮度翻来翻去。
 */
@Composable
internal fun detailBarColor(): Color = MaterialTheme.colorScheme.surface

/** 顶栏标题行高度，与悬浮的返回/分享按钮（40dp + 4dp 上边距）对齐。 */
internal val DETAIL_TOP_BAR_HEIGHT = 48.dp

// ====== 影视状态色 ======

/** TMDB 英文状态 → 状态绑带底色。文案本地化见 DetailHeaderContent.getStatusDisplayText。 */
internal fun detailStatusColor(status: String): Color = when (status) {
    "Released", "Returning Series" -> StatusReleased
    "In Production", "Post Production", "Pilot" -> StatusInProduction
    "Planned" -> StatusPlanned
    "Rumored" -> StatusRumored
    "Canceled" -> StatusCanceled
    "Ended" -> StatusEnded
    else -> StatusUnknown
}

// ====== 共用小组件 ======

/**
 * 栏目标题：转调共用 [SectionHeader]，只把详情页的两处差异固化下来。
 *
 * 一是字号压到 15sp——详情页一屏能出五六个栏目，18sp 会把标题堆成主视觉；
 * 二是颜色走 [androidx.compose.material3.LocalContentColor]，页面已经在 LazyColumn
 * 外把随沉浸背景自适应的 tabContentColor provide 下来了，而原先六处自制标题各自
 * 硬编码 onSurface，深色海报叠浅色主题时对比度不足。
 */
@Composable
internal fun DetailSectionHeader(
    title: String,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    SectionHeader(
        title = title,
        actionText = actionText,
        onActionClick = onActionClick,
        modifier = modifier,
        titleFontSize = 15.sp,
        titleColor = androidx.compose.material3.LocalContentColor.current,
        bottomPadding = 6.dp
    )
}

/** 一枚元信息胶囊。[description] 供读屏使用：胶囊只显示值，念出来是个孤立词。 */
@Immutable
internal data class DetailMetaChip(
    val text: String,
    val description: String
)

/**
 * 头部元信息胶囊行。
 *
 * 原先类型 / 国家 / 日期 / 时长 是四个 `Box(height(18.dp))` 固定高度槽，同为
 * bodySmall 同一个颜色竖排：字段为空照样留 18dp 死白，有值也分不出主次。
 * 收成一行胶囊后空字段直接不占位，[FlowRow] 装不下会自然换行而不是截断。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailMetaChips(
    chips: List<DetailMetaChip>,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    if (chips.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        chips.forEach { chip ->
            Surface(
                shape = RoundedCornerShape(50),
                color = contentColor.copy(alpha = 0.12f)
            ) {
                Text(
                    text = chip.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier
                        .clearAndSetSemantics { contentDescription = chip.description }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}
