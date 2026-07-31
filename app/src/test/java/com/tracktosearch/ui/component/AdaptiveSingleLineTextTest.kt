package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveSingleLineTextTest {

    @Test
    fun `returns the largest font size that fits`() {
        val result = findBestSingleLineFontSize(
            maxFontSize = 16f,
            minFontSize = 10f,
            fits = { it <= 13f }
        )

        assertThat(result).isWithin(0.01f).of(13f)
    }

    @Test
    fun `keeps the minimum font size when no candidate fits`() {
        val result = findBestSingleLineFontSize(
            maxFontSize = 16f,
            minFontSize = 10f,
            fits = { false }
        )

        assertThat(result).isWithin(0.01f).of(10f)
    }
}
