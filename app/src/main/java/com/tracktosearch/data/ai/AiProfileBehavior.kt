package com.tracktosearch.data.ai

import com.tracktosearch.data.local.db.AiProfileBehaviorDailyEntity

enum class DetailDwellBucket {
    IGNORED,
    TEN_TO_THIRTY_SECONDS,
    THIRTY_TO_ONE_TWENTY_SECONDS,
    AT_LEAST_ONE_TWENTY_SECONDS
}

enum class PlaybackProgressBucket {
    P25,
    P50,
    P75,
    P100
}

sealed interface AiProfileBehavior {
    data class DetailDwell(val durationMs: Long) : AiProfileBehavior
    data object SearchClick : AiProfileBehavior
    data object EpisodeStarted : AiProfileBehavior
    data object EpisodeCompleted : AiProfileBehavior
    data class PlaybackProgress(val percent: Int) : AiProfileBehavior
}

data class AiBehaviorAggregate(
    val dwellIgnoredCount: Int = 0,
    val dwell10To30Count: Int = 0,
    val dwell30To120Count: Int = 0,
    val dwell120PlusCount: Int = 0,
    val searchClickCount: Int = 0,
    val episodeStartCount: Int = 0,
    val episodeCompleteCount: Int = 0,
    val progress25Count: Int = 0,
    val progress50Count: Int = 0,
    val progress75Count: Int = 0,
    val progress100Count: Int = 0
)

fun detailDwellBucket(durationMs: Long): DetailDwellBucket = when {
    durationMs < 10_000L -> DetailDwellBucket.IGNORED
    durationMs < 30_000L -> DetailDwellBucket.TEN_TO_THIRTY_SECONDS
    durationMs < 120_000L -> DetailDwellBucket.THIRTY_TO_ONE_TWENTY_SECONDS
    else -> DetailDwellBucket.AT_LEAST_ONE_TWENTY_SECONDS
}

fun progressBucket(percent: Int): PlaybackProgressBucket? = when {
    percent < 25 -> null
    percent < 50 -> PlaybackProgressBucket.P25
    percent < 75 -> PlaybackProgressBucket.P50
    percent < 100 -> PlaybackProgressBucket.P75
    else -> PlaybackProgressBucket.P100
}

fun applyBehavior(
    aggregate: AiBehaviorAggregate,
    behavior: AiProfileBehavior
): AiBehaviorAggregate = when (behavior) {
    is AiProfileBehavior.DetailDwell -> when (detailDwellBucket(behavior.durationMs)) {
        // 记录为低敏 ignored 桶，但调用方不能把它当作兴趣正向信号。
        DetailDwellBucket.IGNORED -> aggregate.copy(
            dwellIgnoredCount = aggregate.dwellIgnoredCount + 1
        )
        DetailDwellBucket.TEN_TO_THIRTY_SECONDS -> aggregate.copy(
            dwell10To30Count = aggregate.dwell10To30Count + 1
        )
        DetailDwellBucket.THIRTY_TO_ONE_TWENTY_SECONDS -> aggregate.copy(
            dwell30To120Count = aggregate.dwell30To120Count + 1
        )
        DetailDwellBucket.AT_LEAST_ONE_TWENTY_SECONDS -> aggregate.copy(
            dwell120PlusCount = aggregate.dwell120PlusCount + 1
        )
    }
    AiProfileBehavior.SearchClick -> aggregate.copy(
        searchClickCount = aggregate.searchClickCount + 1
    )
    AiProfileBehavior.EpisodeStarted -> aggregate.copy(
        episodeStartCount = aggregate.episodeStartCount + 1
    )
    AiProfileBehavior.EpisodeCompleted -> aggregate.copy(
        episodeCompleteCount = aggregate.episodeCompleteCount + 1
    )
    is AiProfileBehavior.PlaybackProgress -> when (progressBucket(behavior.percent)) {
        null -> aggregate
        PlaybackProgressBucket.P25 -> aggregate.copy(progress25Count = aggregate.progress25Count + 1)
        PlaybackProgressBucket.P50 -> aggregate.copy(progress50Count = aggregate.progress50Count + 1)
        PlaybackProgressBucket.P75 -> aggregate.copy(progress75Count = aggregate.progress75Count + 1)
        PlaybackProgressBucket.P100 -> aggregate.copy(progress100Count = aggregate.progress100Count + 1)
    }
}

fun AiProfileBehaviorDailyEntity.toBehaviorAggregate(): AiBehaviorAggregate = AiBehaviorAggregate(
    dwellIgnoredCount = dwellIgnoredCount,
    dwell10To30Count = dwell10To30Count,
    dwell30To120Count = dwell30To120Count,
    dwell120PlusCount = dwell120PlusCount,
    searchClickCount = searchClickCount,
    episodeStartCount = episodeStartCount,
    episodeCompleteCount = episodeCompleteCount,
    progress25Count = progress25Count,
    progress50Count = progress50Count,
    progress75Count = progress75Count,
    progress100Count = progress100Count
)
