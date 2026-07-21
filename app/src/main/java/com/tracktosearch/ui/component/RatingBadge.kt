package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold

/** 评分徽章统一规格：与豆瓣失败项页一致 */
private val RatingCorner = RoundedCornerShape(8.dp)
private val RatingFontSize = 10.sp
private val RatingIconSize = 11.dp

/**
 * 评分徽章（统一黑底金字 + 圆角星星图标样式）。
 *
 * 用于 Trakt/TMDB/豆瓣 等所有平台评分，风格统一。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(6.dp) 定位到右上角）
 */
@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xCC000000), RatingCorner)
            .padding(horizontal = 5.dp, vertical = 2.dp),
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
            color = RatingGold,
            fontSize = RatingFontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * 豆瓣评分徽章（与 [RatingBadge] 统一为黑底金字 + 圆角星星样式）。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(6.dp) 定位到右上角）
 */
@Composable
fun DoubanRatingBadge(rating: Double, modifier: Modifier = Modifier) {
    RatingBadge(rating = rating, modifier = modifier)
}
