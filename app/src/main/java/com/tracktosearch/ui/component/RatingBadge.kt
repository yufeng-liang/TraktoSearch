package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold
import com.tracktosearch.ui.theme.RatingGoldDim

/** 评分徽章统一规格：外边距 4dp、内边距 horizontal=4dp/vertical=0dp、圆角 4dp */
private val RatingCorner = RoundedCornerShape(4.dp)
private val RatingPadding = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
private val RatingFontSize = 11.sp

/**
 * 评分徽章（带星星样式，用于 Trakt/TMDB 等平台评分）
 *
 * 高分（>= 8.0）使用高亮金色，中分（6.0 - 7.9）使用暗金色，低分（< 6.0）使用半透明灰底。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(4.dp) 定位到右上角）
 */
@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    val background = when {
        rating >= 8.0 -> RatingGold.copy(alpha = 0.9f)
        rating >= 6.0 -> RatingGoldDim
        else -> Color.White.copy(alpha = 0.2f)
    }
    val textColor = if (rating >= 6.0) Color.Black else Color.White
    Text(
        text = "★ %.1f".format(rating),
        color = textColor,
        fontSize = RatingFontSize,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(background, RatingCorner)
            .then(RatingPadding)
    )
}

/**
 * 豆瓣评分徽章（绿色底部填充样式，用于豆瓣评分）
 *
 * 固定绿色背景 + 白色文字，与 [RatingBadge] 边距/圆角完全一致，仅颜色和文本格式不同。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符（通常包含 align + padding(4.dp) 定位到右上角）
 */
@Composable
fun DoubanRatingBadge(rating: Double, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RatingCorner,
        color = Color(0xFF68BD5B)
    ) {
        Text(
            text = "%.1f".format(rating),
            color = Color.White,
            fontSize = RatingFontSize,
            fontWeight = FontWeight.Bold,
            modifier = RatingPadding
        )
    }
}
