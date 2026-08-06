package com.tracktosearch.ui.screen.detail

import androidx.compose.ui.graphics.Color

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
        ?.times(10.0)
        ?.coerceIn(0.0, 100.0)
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
    isDarkTheme: Boolean,
    unavailableColor: Color
): Color {
    return when (band) {
        RatingBand.NONE -> unavailableColor
        RatingBand.LOW -> if (isDarkTheme) Color(0xFFA8BECC) else Color(0xFF5F7080)
        RatingBand.MEDIUM -> if (isDarkTheme) Color(0xFFD1BDD0) else Color(0xFF866F85)
        RatingBand.HIGH -> if (isDarkTheme) Color(0xFFE2BE88) else Color(0xFF9A7538)
    }
}
