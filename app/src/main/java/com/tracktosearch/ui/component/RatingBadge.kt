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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold

/** 评分徽章统一规格：内边距更扁、圆角 6dp */
private val RatingCorner = RoundedCornerShape(6.dp)
private val RatingFontSize = 10.sp

/**
 * 评分徽章（统一黑底金字 + 圆角星星图标样式）。
 *
 * 用于 Trakt/TMDB/豆瓣 等所有平台评分，风格统一。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(4.dp) 定位到右上角）
 */
@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    val background = Brush.linearGradient(
        0f to Color(0xFF000000).copy(alpha = 0.88f),
        1f to Color(0xFF1E1914).copy(alpha = 0.92f)
    )
    Row(
        modifier = modifier
            .background(background, RatingCorner)
            .padding(horizontal = 3.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            modifier = Modifier.size(9.dp),
            tint = RatingGold
        )
        Text(
            text = "%.1f".format(rating),
            color = RatingGold,
            fontSize = RatingFontSize,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * 豆瓣评分徽章（与 [RatingBadge] 统一为黑底金字 + 圆角星星样式）。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(4.dp) 定位到右上角）
 */
@Composable
fun DoubanRatingBadge(rating: Double, modifier: Modifier = Modifier) {
    RatingBadge(rating = rating, modifier = modifier)
}
