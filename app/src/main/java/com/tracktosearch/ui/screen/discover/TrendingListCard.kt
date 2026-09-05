package com.tracktosearch.ui.screen.discover

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.component.TraktListCardCorner
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable

/**
 * 社区列表卡片的形状。取 [TraktListCardCorner]（20dp）而不是就地写死：
 * 这张卡片是容器变形的来源侧，圆角必须与榜单详情页那一端读的是同一个常量。
 */
private val TrendingListCardShape = RoundedCornerShape(TraktListCardCorner)

/**
 * 社区热门列表单行卡片。
 *
 * 版式与搜索源页的内置源卡片、统计页卡片一致：20dp 圆角、无描边、极轻投影、
 * 标题 titleSmall + SemiBold、副文本 bodySmall。
 *
 * 填充色是唯一的例外：搜索源页那批用 surfaceVariant（tone 90），压在页面底（tone 95）上
 * 只有 1.16 的对比度，在发现页这种卡片之间留白更大的地方直接和背景融成一片。这里改用
 * surfaceContainerLowest（浅色档纸白 tone 100、深色档 tone 3），对比度到 1.19，
 * 卡片边界能看出来，也是全套色阶里离页面底最远的一级。
 *
 * 这里不用 Glass 材质：发现页与「查看全部」弹窗里是同一批列表，两处必须长得一样，
 * 而弹窗在 ModalBottomSheet 自己的窗口里采不到页面 backdrop，玻璃在那侧只能退化成半透明色块。
 * 换成不透明卡面后两处天然一致，也顺带断掉了这些卡片对 backdrop 的采样。
 *
 * @param modifier 外层修饰符。发现页在这里挂 `appSharedBounds` + `appSkipToLookaheadSize`
 *   做容器变形；弹窗那侧配不上共享元素，只传 `fillMaxWidth`。
 * @param description 列表简介，为空时不占位。发现页只显示标题与 meta，弹窗里多显示这一行。
 */
@Composable
internal fun TrendingListCard(
    title: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        label = "trending_list_card_scale"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            // scale 放在裁剪与填色之前：按压缩放要连卡面一起缩，只缩内容会露出卡面边缘
            .scale(cardScale)
            .shadow(1.dp, TrendingListCardShape)
            .clip(TrendingListCardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            // 一屏里排十几张的榜单行，与 ui/component/MovieCard、PosterCard 同档
            .hapticClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
                onClick = onClick
            )
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
