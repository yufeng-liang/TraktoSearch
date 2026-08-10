package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold

/** 评分徽章统一规格：星星 + 数字，无 chip 底色，靠深色描边阴影保证不同海报上的对比度。 */
private val RatingFontSize = 10.sp
private val RatingIconSize = 11.dp

/**
 * 评分徽章（星星 + 数字，无黑色半透明 chip）。
 *
 * 星星与数字均带黑色硬阴影描边，浅色/深色海报上都可读。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(2.dp) 定位到右上角）
 */
@Composable
fun RatingBadge(
    rating: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .offset(y = (-2).dp)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // 星星：底层黑色偏移副本作描边阴影，上层金色主体
        Box {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier
                    .offset(x = 0.6.dp, y = 0.6.dp)
                    .size(RatingIconSize),
                tint = Color.Black.copy(alpha = 0.85f)
            )
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier.size(RatingIconSize),
                tint = RatingGold
            )
        }
        Text(
            text = "%.1f".format(rating),
            color = Color.White,
            fontSize = RatingFontSize,
            fontWeight = FontWeight.Bold,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.9f),
                    offset = Offset(0.6f, 0.6f),
                    blurRadius = 1.5f
                )
            )
        )
    }
}

/**
 * 豆瓣评分徽章（与 [RatingBadge] 统一为星星 + 数字样式）。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(2.dp) 定位到右上角）
 */
@Composable
fun DoubanRatingBadge(
    rating: Double,
    modifier: Modifier = Modifier
) {
    RatingBadge(rating = rating, modifier = modifier)
}
