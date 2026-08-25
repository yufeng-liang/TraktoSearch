package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/** 激活页标题上方的场记板品牌图标，颜色随主题 primary/surface 变化。 */
@Composable
fun CinemaClapperIcon(modifier: Modifier = Modifier) {
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val paper = androidx.compose.material3.MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier.size(width = 84.dp, height = 66.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 66.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(accent)
                .drawBehind {
                    val bandWidth = 9.dp.toPx()
                    val bandStep = 19.dp.toPx()
                    val firstBand = 16.dp.toPx()
                    repeat(3) { index ->
                        val left = firstBand + index * bandStep
                        val path = Path().apply {
                            moveTo(left + 6.dp.toPx(), 0f)
                            lineTo(left + bandWidth + 6.dp.toPx(), 0f)
                            lineTo(left + bandWidth, 12.dp.toPx())
                            lineTo(left, 12.dp.toPx())
                            close()
                        }
                        drawPath(path = path, color = paper)
                    }
                }
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .background(paper.copy(alpha = 0.28f))
                .size(width = 60.dp, height = 2.dp)
        )
    }
}

@Composable
fun BrandClapperIcon(modifier: Modifier = Modifier) = CinemaClapperIcon(modifier)
