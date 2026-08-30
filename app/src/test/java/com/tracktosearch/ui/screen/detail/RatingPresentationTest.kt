package com.tracktosearch.ui.screen.detail

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RatingPresentationTest {

    @Test
    fun ratingNormalization_handlesValidInvalidAndMissingValues() {
        assertThat(normalizeTenPointRating(8.0)).isEqualTo(80.0)
        listOf<Double?>(null, -1.0, 88.0).forEach { assertThat(normalizeTenPointRating(it)).isNull() }

        assertThat(normalizePercentRating("88%")).isEqualTo(88.0)
        assertThat(normalizePercentRating("88/100")).isEqualTo(88.0)
        assertThat(normalizePercentRating("101%")).isEqualTo(100.0)
        assertThat(normalizePercentRating("-5/100")).isEqualTo(0.0)
        listOf(" ", "N/A", "not-a-score", "88").forEach {
            assertThat(normalizePercentRating(it)).isNull()
        }
    }

    @Test
    fun ratingBand_usesFixedThresholdsAndNoneForMissingScore() {
        assertThat(ratingBand(null)).isEqualTo(RatingBand.NONE)
        assertThat(ratingBand(59.9)).isEqualTo(RatingBand.LOW)
        assertThat(ratingBand(60.0)).isEqualTo(RatingBand.MEDIUM)
        assertThat(ratingBand(74.9)).isEqualTo(RatingBand.MEDIUM)
        assertThat(ratingBand(75.0)).isEqualTo(RatingBand.HIGH)
    }

    @Test
    fun ratingBandColor_distinguishesThemesAndUnavailableState() {
        val unavailable = Color(0xFF7A7A7A)
        assertThat(ratingBandColor(RatingBand.LOW, Color.Black, unavailable)).isEqualTo(Color(0xFFB4E5F5))
        assertThat(ratingBandColor(RatingBand.HIGH, Color.White, unavailable)).isEqualTo(Color(0xFF9C6A10))
        assertThat(ratingBandColor(RatingBand.NONE, Color.Black, unavailable)).isEqualTo(unavailable)
        assertThat(ratingBandColor(RatingBand.NONE, Color.White, unavailable)).isEqualTo(unavailable)
    }

    @Test
    fun ratingCardColor_blendsImmersionColorWithWhiteByTheme() {
        val light = ratingCardColor(Color.Black, isDarkTheme = false)
        val dark = ratingCardColor(Color.Black, isDarkTheme = true)
        assertThat(light.red).isGreaterThan(dark.red)
        assertThat(dark.red).isGreaterThan(0f)
        assertThat(light.red).isLessThan(1f)
    }
}
