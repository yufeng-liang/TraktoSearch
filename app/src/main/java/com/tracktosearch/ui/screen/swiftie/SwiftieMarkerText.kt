package com.tracktosearch.ui.screen.swiftie

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit

/**
 * Honey Script SemiBold 的一行字。
 *
 * 只剩落款 `13 + 87 = 100` 在用（终局与静态终态各一处）。**出题页的算式不走这里** ——
 * 那一条是按原图实测的包围盒逐组排出来的路径，见 [SwiftiePosterInk]：文本布局给不出
 * 「这个 `87` 的墨迹要正好落在这个框里」这种约束。
 */
@Composable
internal fun SwiftieMarkerText(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = SwiftieFonts.Marker,
            fontSize = fontSize,
            color = color,
            textAlign = TextAlign.Center
        ),
        maxLines = 1,
        modifier = modifier
    )
}
