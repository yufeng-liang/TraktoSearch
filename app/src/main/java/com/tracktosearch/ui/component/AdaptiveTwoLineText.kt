package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

internal fun shouldShrinkTwoLineTitle(
    lineCount: Int,
    didExceedMaxLines: Boolean,
    secondLineCharacterCount: Int
): Boolean = !didExceedMaxLines && lineCount == 2 && secondLineCharacterCount in 1..2

internal fun findBestTwoLineTitleFontSize(
    maxFontSize: Float,
    minFontSize: Float,
    fits: (Float) -> Boolean
): Float? {
    if (!fits(minFontSize)) return null
    return findBestSingleLineFontSize(maxFontSize, minFontSize, fits)
}

@Composable
internal fun AdaptiveTwoLineTitle(
    text: String,
    style: TextStyle,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        // 测量结果只依赖字体度量属性：style 每次重组都是新实例，直接用 style 做键恒失效，
        // 改为只取 fontFamily/fontWeight/fontStyle/letterSpacing，重组时复用缓存。
        val fontSize = remember(
            text,
            style.fontFamily,
            style.fontWeight,
            style.fontStyle,
            style.letterSpacing,
            maxFontSize,
            minFontSize,
            maxWidthPx
        ) {
            if (maxWidthPx <= 0 || maxWidthPx == Constraints.Infinity) {
                maxFontSize
            } else {
                val fullLayout = textMeasurer.measure(
                    text = text,
                    style = style.copy(fontSize = maxFontSize),
                    constraints = Constraints(maxWidth = maxWidthPx),
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip
                )
                val secondLineCharacterCount = if (fullLayout.lineCount >= 2) {
                    val start = fullLayout.getLineStart(1)
                    val end = fullLayout.getLineEnd(1, visibleEnd = true)
                    text.codePointCount(start, end)
                } else {
                    0
                }

                if (!shouldShrinkTwoLineTitle(
                        lineCount = minOf(fullLayout.lineCount, 2),
                        didExceedMaxLines = fullLayout.lineCount > 2,
                        secondLineCharacterCount = secondLineCharacterCount
                    )
                ) {
                    maxFontSize
                } else {
                    findBestTwoLineTitleFontSize(
                        maxFontSize = maxFontSize.value,
                        minFontSize = minFontSize.value
                    ) { candidate ->
                        textMeasurer.measure(
                            text = text,
                            style = style.copy(fontSize = candidate.sp),
                            constraints = Constraints(maxWidth = maxWidthPx),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        ).size.width <= maxWidthPx
                    }?.sp ?: maxFontSize
                }
            }
        }

        Text(
            text = text,
            style = style.copy(fontSize = fontSize),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
