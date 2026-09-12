package com.tracktosearch.data.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiDetailMediaDto(
    val mediaKey: String,
    val mediaType: String,
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val overview: String? = null,
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val publicRating: Double? = null,
    val mediaIds: AiMediaIdsDto = AiMediaIdsDto(),
    val userRating: Double? = null,
    val userComment: String? = null
)

@Serializable
data class AiDetailEnvironmentDto(
    val localDate: String,
    val weekday: Int,
    val timeOfDay: String,
    val season: String,
    val weatherTag: String? = null
)

@Serializable
data class AiDetailAnalyzeRequest(
    val media: AiDetailMediaDto,
    val scene: String,
    val environment: AiDetailEnvironmentDto? = null,
    val forceRefresh: Boolean = false,
    val sessionId: String = "detail"
)

@Serializable
data class AiWatchTimingDto(
    val level: String = "UNKNOWN",
    val basis: List<String> = emptyList()
)

@Serializable
data class AiDetailAnalysisDto(
    val scene: String = "UNMARKED",
    val interestLevel: String = "LOW",
    val confidence: String = "LOW",
    val spoilerFreeSummary: String = "",
    val publicRatingInterpretation: String = "",
    val watchAdvice: String = "",
    val reasons: List<String> = emptyList(),
    val watchTiming: AiWatchTimingDto? = null,
    val profileVersion: Long = 0L,
    val quota: AiQuotaDto? = null
)

@Serializable
data class AiDetailRecommendationDto(
    val mediaKey: String,
    val mediaType: String,
    val title: String,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val mediaIds: AiMediaIdsDto = AiMediaIdsDto(),
    val posterUrl: String? = null,
    val reason: String = ""
)

@Serializable
data class AiRecommendationsRankRequest(
    val currentMedia: AiDetailMediaDto,
    val candidates: List<AiDetailRecommendationDto> = emptyList(),
    val sessionId: String = "detail-recommendations"
)

@Serializable
data class AiRecommendationsRankDto(
    val recommendations: List<AiDetailRecommendationDto> = emptyList(),
    val profileVersion: Long = 0L,
    val quota: AiQuotaDto? = null
)

enum class AiDetailInterestLevel {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun parse(value: String): AiDetailInterestLevel =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: LOW
    }
}

enum class AiDetailWatchTiming {
    NOW,
    SOON,
    LATER,
    UNKNOWN;

    companion object {
        fun parse(value: String): AiDetailWatchTiming =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

data class AiDetailAnalysis(
    val scene: String,
    val interestLevel: AiDetailInterestLevel,
    val confidence: AiDetailInterestLevel,
    val spoilerFreeSummary: String,
    val publicRatingInterpretation: String,
    val watchAdvice: String,
    val reasons: List<String>,
    val watchTiming: AiDetailWatchTiming,
    val watchTimingBasis: List<String>,
    val profileVersion: Long,
    val quota: AiQuota? = null
)

data class AiDetailRecommendation(
    val mediaKey: String,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    val mediaIds: AiMediaIdsDto,
    val posterUrl: String?,
    val reason: String
)

fun AiDetailAnalysisDto.toDomain(outerQuota: AiQuotaDto? = null): AiDetailAnalysis = AiDetailAnalysis(
    scene = scene,
    interestLevel = AiDetailInterestLevel.parse(interestLevel),
    confidence = AiDetailInterestLevel.parse(confidence),
    spoilerFreeSummary = spoilerFreeSummary,
    publicRatingInterpretation = publicRatingInterpretation,
    watchAdvice = watchAdvice,
    reasons = reasons,
    watchTiming = AiDetailWatchTiming.parse(watchTiming?.level.orEmpty()),
    watchTimingBasis = watchTiming?.basis.orEmpty(),
    profileVersion = profileVersion,
    quota = (outerQuota ?: quota)?.toDomain()
)

fun AiRecommendationsRankDto.toDomain(outerQuota: AiQuotaDto? = null): List<AiDetailRecommendation> =
    recommendations.map {
        AiDetailRecommendation(
            mediaKey = it.mediaKey,
            mediaType = it.mediaType,
            title = it.title,
            year = it.year,
            genres = it.genres,
            mediaIds = it.mediaIds,
            posterUrl = it.posterUrl,
            reason = it.reason
        )
    }
