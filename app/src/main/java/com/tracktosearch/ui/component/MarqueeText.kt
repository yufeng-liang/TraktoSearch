package com.tracktosearch.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * 跑马灯文字组件
 * 当文字超出容器宽度时自动无限循环滚动，否则正常显示
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = LocalContentColor.current
) {
    // basicMarquee 仅在内容超出容器时才会滚动
    // 不主动 fillMaxWidth，由外部容器约束宽度
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center
    )
}
