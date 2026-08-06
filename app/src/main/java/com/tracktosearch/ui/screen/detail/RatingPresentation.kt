package com.tracktosearch.ui.screen.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** 评分展示分档，所有来源先统一到百分制。 */
internal enum class RatingBand {
    NONE,
    LOW,
    MEDIUM,
    HIGH
}

internal fun normalizeTenPointRating(value: Double?): Double? {
    return value
        ?.takeIf { it.isFinite() }
        ?.takeIf { it in 0.0..10.0 }
        ?.times(10.0)
}

internal fun normalizePercentRating(value: String): Double? {
    val normalized = value.trim().replace(" ", "")
    if (normalized.isEmpty() || normalized.equals("N/A", ignoreCase = true)) return null

    val numericPart = when {
        normalized.endsWith("%") -> normalized.dropLast(1)
        normalized.endsWith("/100") -> normalized.removeSuffix("/100")
        else -> return null
    }
    return numericPart
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0.0, 100.0)
}

internal fun ratingBand(score: Double?): RatingBand {
    return when {
        score == null || !score.isFinite() -> RatingBand.NONE
        score < 60.0 -> RatingBand.LOW
        score < 75.0 -> RatingBand.MEDIUM
        else -> RatingBand.HIGH
    }
}

internal fun ratingBandColor(
    band: RatingBand,
    surfaceColor: Color,
    unavailableColor: Color
): Color {
    val useBrightPalette = surfaceColor.luminance() <= 0.5f
    return when (band) {
        RatingBand.NONE -> unavailableColor
        RatingBand.LOW -> if (useBrightPalette) Color(0xFFB4E5F5) else Color(0xFF1B6E8A)
        RatingBand.MEDIUM -> if (useBrightPalette) Color(0xFFF1BEE4) else Color(0xFF8E3C83)
        RatingBand.HIGH -> if (useBrightPalette) Color(0xFFFFD27E) else Color(0xFF9C6A10)
    }
}

internal fun ratingCardColor(baseColor: Color, isDarkTheme: Boolean): Color =
    lerp(baseColor, Color.White, if (isDarkTheme) 0.20f else 0.40f)
