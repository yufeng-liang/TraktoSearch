package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.RatingGold

/** 评分徽章统一规格：星星 + 数字，scrim 半透明底 + 圆角 + 细边框，保证不同海报上的对比度。 */
private val RatingFontSize = 13.sp
private val RatingIconSize = 14.dp
private val RatingCorner = RoundedCornerShape(7.dp)

/**
 * 评分徽章（星星 + 数字，scrim 半透明深色底）。
 *
 * 星星与数字均带黑色硬阴影描边，浅色/深色海报上都可读。
 * 底色用 scrim 半透明黑（alpha≈0.45）替代原 per-card Haze 毛玻璃层：
 * 30+ 张卡不再各自注册 HazeState/hazeSource 离屏采样层，纯绘制层叠加即可。
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
            .clip(RatingCorner)
            .background(Color.Black.copy(alpha = 0.45f), RatingCorner)
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.46f),
                shape = RatingCorner
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // 星星：四向黑色副本形成硬描边，上层金色主体保留海报上的识别度。
        Box {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier
                    .offset(x = (-0.7).dp)
                    .size(RatingIconSize),
                tint = Color.Black.copy(alpha = 0.85f)
            )
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier
                    .offset(x = 0.7.dp)
                    .size(RatingIconSize),
                tint = Color.Black.copy(alpha = 0.85f)
            )
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier
                    .offset(y = (-0.7).dp)
                    .size(RatingIconSize),
                tint = Color.Black.copy(alpha = 0.85f)
            )
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                modifier = Modifier
                    .offset(y = 0.7.dp)
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
            fontWeight = FontWeight.ExtraBold,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.98f),
                    offset = Offset.Zero,
                    blurRadius = 2.4f
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
