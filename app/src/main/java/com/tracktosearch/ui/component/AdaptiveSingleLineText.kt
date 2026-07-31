package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

internal fun findBestSingleLineFontSize(
    maxFontSize: Float,
    minFontSize: Float,
    fits: (Float) -> Boolean
): Float {
    require(maxFontSize >= minFontSize)

    var low = minFontSize
    var high = maxFontSize
    var best = minFontSize
    while (low <= high) {
        val candidate = (low + high) / 2f
        if (fits(candidate)) {
            best = candidate
            low = candidate + 0.5f
        } else {
            high = candidate - 0.5f
        }
    }
    return best
}

@Composable
internal fun AdaptiveSingleLineText(
    text: String,
    style: TextStyle,
    maxFontSize: TextUnit,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 10.sp,
    textAlign: TextAlign? = null,
    fillMaxWidth: Boolean = false
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val fontSize = remember(text, style, maxFontSize, minFontSize, maxWidthPx) {
            if (maxWidthPx <= 0 || maxWidthPx == Constraints.Infinity) {
                maxFontSize
            } else {
                findBestSingleLineFontSize(
                    maxFontSize = maxFontSize.value,
                    minFontSize = minFontSize.value
                ) { candidate ->
                    textMeasurer.measure(
                        text = text,
                        style = style.copy(fontSize = candidate.sp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    ).size.width <= maxWidthPx
                }.sp
            }
        }

        Text(
            text = text,
            style = style.copy(fontSize = fontSize),
            modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    }
}
