package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold
import com.tracktosearch.ui.theme.RatingGoldDim

/**
 * 评分徽章
 *
 * 高分（>= 8.0）使用高亮金色，中分（6.0 - 7.9）使用暗金色，低分（< 6.0）使用半透明灰底。
 *
 * @param rating 评分值
 * @param modifier 外部传入的修饰符
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
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 2.dp, vertical = 0.dp)
    )
}
