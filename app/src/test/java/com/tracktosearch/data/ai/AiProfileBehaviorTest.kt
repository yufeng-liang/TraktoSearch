package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiProfileBehaviorTest {

    @Test
    fun detailDwellUsesTheDefinedBuckets() {
        assertThat(detailDwellBucket(-1L)).isEqualTo(DetailDwellBucket.IGNORED)
        assertThat(detailDwellBucket(3_500L)).isEqualTo(DetailDwellBucket.IGNORED)
        assertThat(detailDwellBucket(9_999L)).isEqualTo(DetailDwellBucket.IGNORED)
        assertThat(detailDwellBucket(10_000L)).isEqualTo(DetailDwellBucket.TEN_TO_THIRTY_SECONDS)
        assertThat(detailDwellBucket(29_999L)).isEqualTo(DetailDwellBucket.TEN_TO_THIRTY_SECONDS)
        assertThat(detailDwellBucket(30_000L)).isEqualTo(DetailDwellBucket.THIRTY_TO_ONE_TWENTY_SECONDS)
        assertThat(detailDwellBucket(119_999L)).isEqualTo(DetailDwellBucket.THIRTY_TO_ONE_TWENTY_SECONDS)
        assertThat(detailDwellBucket(120_000L)).isEqualTo(DetailDwellBucket.AT_LEAST_ONE_TWENTY_SECONDS)
    }

    @Test
    fun automaticEntryAtThreePointFiveSecondsDoesNotCreateAnInterestSignal() {
        val aggregate = applyBehavior(
            AiBehaviorAggregate(),
            AiProfileBehavior.DetailDwell(durationMs = 3_500L)
        )

        assertThat(aggregate.dwellIgnoredCount).isEqualTo(1)
        assertThat(aggregate.dwell10To30Count).isEqualTo(0)
        assertThat(aggregate.dwell30To120Count).isEqualTo(0)
        assertThat(aggregate.dwell120PlusCount).isEqualTo(0)
        assertThat(aggregate.searchClickCount).isEqualTo(0)
    }

    @Test
    fun behaviorEventsIncrementOnlyTheirLowSensitivityAggregate() {
        var aggregate = AiBehaviorAggregate()
        aggregate = applyBehavior(aggregate, AiProfileBehavior.DetailDwell(12_000L))
        aggregate = applyBehavior(aggregate, AiProfileBehavior.SearchClick)
        aggregate = applyBehavior(aggregate, AiProfileBehavior.EpisodeStarted)
        aggregate = applyBehavior(aggregate, AiProfileBehavior.EpisodeCompleted)
        aggregate = applyBehavior(aggregate, AiProfileBehavior.PlaybackProgress(25))
        aggregate = applyBehavior(aggregate, AiProfileBehavior.PlaybackProgress(50))
        aggregate = applyBehavior(aggregate, AiProfileBehavior.PlaybackProgress(75))
        aggregate = applyBehavior(aggregate, AiProfileBehavior.PlaybackProgress(100))

        assertThat(aggregate.dwell10To30Count).isEqualTo(1)
        assertThat(aggregate.searchClickCount).isEqualTo(1)
        assertThat(aggregate.episodeStartCount).isEqualTo(1)
        assertThat(aggregate.episodeCompleteCount).isEqualTo(1)
        assertThat(aggregate.progress25Count).isEqualTo(1)
        assertThat(aggregate.progress50Count).isEqualTo(1)
        assertThat(aggregate.progress75Count).isEqualTo(1)
        assertThat(aggregate.progress100Count).isEqualTo(1)
        assertThat(aggregate.dwellIgnoredCount).isEqualTo(0)
        assertThat(aggregate.dwell30To120Count).isEqualTo(0)
        assertThat(aggregate.dwell120PlusCount).isEqualTo(0)
    }

    @Test
    fun progressIsNormalizedToTheFourSupportedBuckets() {
        assertThat(progressBucket(0)).isNull()
        assertThat(progressBucket(24)).isNull()
        assertThat(progressBucket(25)).isEqualTo(PlaybackProgressBucket.P25)
        assertThat(progressBucket(49)).isEqualTo(PlaybackProgressBucket.P25)
        assertThat(progressBucket(50)).isEqualTo(PlaybackProgressBucket.P50)
        assertThat(progressBucket(74)).isEqualTo(PlaybackProgressBucket.P50)
        assertThat(progressBucket(75)).isEqualTo(PlaybackProgressBucket.P75)
        assertThat(progressBucket(99)).isEqualTo(PlaybackProgressBucket.P75)
        assertThat(progressBucket(100)).isEqualTo(PlaybackProgressBucket.P100)
        assertThat(progressBucket(101)).isEqualTo(PlaybackProgressBucket.P100)
    }
}
