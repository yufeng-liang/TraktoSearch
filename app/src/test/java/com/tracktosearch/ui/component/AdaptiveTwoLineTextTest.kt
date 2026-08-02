package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveTwoLineTextTest {

    @Test
    fun `second line with one or two characters can collapse`() {
        assertThat(
            shouldShrinkTwoLineTitle(
                lineCount = 2,
                didExceedMaxLines = false,
                secondLineCharacterCount = 2
            )
        ).isTrue()
    }

    @Test
    fun `second line with more than two characters keeps two lines`() {
        assertThat(
            shouldShrinkTwoLineTitle(
                lineCount = 2,
                didExceedMaxLines = false,
                secondLineCharacterCount = 3
            )
        ).isFalse()
    }

    @Test
    fun `titles exceeding two lines keep the original font size`() {
        assertThat(
            shouldShrinkTwoLineTitle(
                lineCount = 2,
                didExceedMaxLines = true,
                secondLineCharacterCount = 1
            )
        ).isFalse()
    }

    @Test
    fun `returns no collapsed size when minimum size still does not fit`() {
        val result = findBestTwoLineTitleFontSize(
            maxFontSize = 15f,
            minFontSize = 13f,
            fits = { false }
        )

        assertThat(result).isNull()
    }

    @Test
    fun `returns the largest fitting collapsed size`() {
        val result = findBestTwoLineTitleFontSize(
            maxFontSize = 15f,
            minFontSize = 13f,
            fits = { it <= 14f }
        )

        assertThat(result).isWithin(0.01f).of(14f)
    }
}
