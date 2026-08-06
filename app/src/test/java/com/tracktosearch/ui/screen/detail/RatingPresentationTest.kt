package com.tracktosearch.ui.screen.detail

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RatingPresentationTest {

    @Test
    fun normalizeTenPointRating_convertsTenPointScoreToPercent() {
        assertThat(normalizeTenPointRating(8.0)).isEqualTo(80.0)
        assertThat(normalizeTenPointRating(null)).isNull()
    }

    @Test
    fun normalizeTenPointRating_rejectsValuesOutsideTenPointRange() {
        assertThat(normalizeTenPointRating(-1.0)).isNull()
        assertThat(normalizeTenPointRating(88.0)).isNull()
    }

    @Test
    fun normalizePercentRating_parsesPercentAndFractionAndClampsToPercentRange() {
        assertThat(normalizePercentRating("88%")).isEqualTo(88.0)
        assertThat(normalizePercentRating("88/100")).isEqualTo(88.0)
        assertThat(normalizePercentRating("101%")).isEqualTo(100.0)
        assertThat(normalizePercentRating("-5/100")).isEqualTo(0.0)
    }

    @Test
    fun normalizePercentRating_returnsNullForBlankUnavailableAndInvalidValues() {
        assertThat(normalizePercentRating(" ")).isNull()
        assertThat(normalizePercentRating("N/A")).isNull()
        assertThat(normalizePercentRating("not-a-score")).isNull()
        assertThat(normalizePercentRating("88")).isNull()
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
    fun ratingBandColor_returnsBrightColorsForDarkRatingCard() {
        assertThat(ratingBandColor(RatingBand.LOW, surfaceColor = Color.Black, Color.Black))
            .isEqualTo(Color(0xFFB4E5F5))
        assertThat(ratingBandColor(RatingBand.MEDIUM, surfaceColor = Color.Black, Color.Black))
            .isEqualTo(Color(0xFFF1BEE4))
        assertThat(ratingBandColor(RatingBand.HIGH, surfaceColor = Color.Black, Color.Black))
            .isEqualTo(Color(0xFFFFD27E))
    }

    @Test
    fun ratingBandColor_returnsDeepColorsForLightRatingCard() {
        assertThat(ratingBandColor(RatingBand.LOW, surfaceColor = Color.White, Color.White))
            .isEqualTo(Color(0xFF1B6E8A))
        assertThat(ratingBandColor(RatingBand.MEDIUM, surfaceColor = Color.White, Color.White))
            .isEqualTo(Color(0xFF8E3C83))
        assertThat(ratingBandColor(RatingBand.HIGH, surfaceColor = Color.White, Color.White))
            .isEqualTo(Color(0xFF9C6A10))
    }

    @Test
    fun ratingBandColor_usesUnavailableColorForNoneInBothThemes() {
        val unavailableColor = Color(0xFF7A7A7A)

        assertThat(ratingBandColor(RatingBand.NONE, surfaceColor = Color.Black, unavailableColor))
            .isEqualTo(unavailableColor)
        assertThat(ratingBandColor(RatingBand.NONE, surfaceColor = Color.White, unavailableColor))
            .isEqualTo(unavailableColor)
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
