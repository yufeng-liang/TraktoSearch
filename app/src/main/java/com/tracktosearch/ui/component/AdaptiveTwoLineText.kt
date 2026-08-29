package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.util.LinkedHashMap

private const val ADAPTIVE_TWO_LINE_TITLE_CACHE_SIZE = 256

internal data class AdaptiveTwoLineTitleFontSizeCacheKey(
    val text: String,
    val style: TextStyle,
    val maxFontSize: TextUnit,
    val minFontSize: TextUnit,
    val maxWidthPx: Int,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: LayoutDirection
)

internal class AdaptiveTwoLineTitleFontSizeCache(
    private val maxSize: Int
) {
    init {
        require(maxSize > 0)
    }

    private val entries = object : LinkedHashMap<AdaptiveTwoLineTitleFontSizeCacheKey, TextUnit>(
        maxSize,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<AdaptiveTwoLineTitleFontSizeCacheKey, TextUnit>
        ): Boolean = size > maxSize
    }

    fun getOrPut(
        key: AdaptiveTwoLineTitleFontSizeCacheKey,
        calculate: () -> TextUnit
    ): TextUnit {
        synchronized(entries) {
            entries[key]?.let { return it }
        }

        val calculated = calculate()
        return synchronized(entries) {
            entries[key] ?: calculated.also { entries[key] = it }
        }
    }
}

private val adaptiveTwoLineTitleFontSizeCache = AdaptiveTwoLineTitleFontSizeCache(
    maxSize = ADAPTIVE_TWO_LINE_TITLE_CACHE_SIZE
)

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
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = constraints.maxWidth
        val fontSize = if (maxWidthPx <= 0 || maxWidthPx == Constraints.Infinity) {
            maxFontSize
        } else {
            val cacheKey = remember(
                text,
                style,
                maxFontSize,
                minFontSize,
                maxWidthPx,
                density.density,
                density.fontScale,
                layoutDirection
            ) {
                AdaptiveTwoLineTitleFontSizeCacheKey(
                    text = text,
                    style = style,
                    maxFontSize = maxFontSize,
                    minFontSize = minFontSize,
                    maxWidthPx = maxWidthPx,
                    density = density.density,
                    fontScale = density.fontScale,
                    layoutDirection = layoutDirection
                )
            }
            remember(cacheKey) {
                adaptiveTwoLineTitleFontSizeCache.getOrPut(cacheKey) {
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
        }

        Text(
            text = text,
            style = style.copy(fontSize = fontSize),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
