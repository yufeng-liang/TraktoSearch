package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeSampling
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** 评分玻璃芯片统一规格：保持紧凑，减少对海报内容的遮挡。 */
private val RatingCorner = RoundedCornerShape(7.dp)
private val RatingFontSize = 10.sp
private val RatingIconSize = 11.dp

/**
 * 评分徽章（Haze 深色玻璃底 + 高对比文字 + 悬浮阴影）。
 *
 * 用于 Trakt/TMDB/豆瓣 等所有平台评分，风格统一。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(2.dp) 定位到右上角）
 * @param hazeState 海报内容对应的 Haze 状态，用于采样并模糊评分芯片下方的海报。
 */
@Composable
fun RatingBadge(
    rating: Double,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val hazeModifier = if (hazeState != null) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(state = hazeState),
            style = HazeBlurStyle {
                blurRadius(14.dp)
                colorEffects(listOf(HazeColorEffect.tint(Color.Black.copy(alpha = 0.42f))))
            },
            sampling = HazeSampling.Adaptive
        )
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .offset(y = (-2).dp)
            .dropShadow(
                shape = RatingCorner,
                shadow = Shadow(
                    radius = 7.dp,
                    color = Color.Black.copy(alpha = 0.48f),
                    offset = DpOffset(0.dp, 2.dp)
                )
            )
            .clip(RatingCorner)
            .then(hazeModifier)
            .background(Color.Black.copy(alpha = 0.30f), RatingCorner)
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.46f),
                shape = RatingCorner
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            modifier = Modifier.size(RatingIconSize),
            tint = RatingGold
        )
        Text(
            text = "%.1f".format(rating),
            color = Color.White,
            fontSize = RatingFontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 豆瓣评分徽章（与 [RatingBadge] 统一为深色玻璃芯片样式）。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(2.dp) 定位到右上角）
 */
@Composable
fun DoubanRatingBadge(
    rating: Double,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    RatingBadge(rating = rating, modifier = modifier, hazeState = hazeState)
}
