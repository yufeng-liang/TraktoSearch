package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoubanSyncEtaEstimatorTest {

    @Test
    fun firstSampleIsUnknown() {
        val clock = FakeTimeProvider(nowMs = 1_000L)
        val estimator = DoubanSyncEtaEstimator(clock)

        assertThat(estimator.update(current = 1, total = 10))
            .isEqualTo(DoubanSyncEtaEstimator.UNKNOWN_ETA_SECONDS)
    }

    @Test
    fun fewerThanFiveCompletedItemsAreUnknown() {
        val clock = FakeTimeProvider(nowMs = 1_000L)
        val estimator = DoubanSyncEtaEstimator(clock)

        clock.nowMs = 2_000L
        estimator.update(current = 1, total = 10)
        clock.nowMs = 3_000L
        estimator.update(current = 2, total = 10)
        clock.nowMs = 4_000L
        estimator.update(current = 3, total = 10)
        clock.nowMs = 5_000L

        assertThat(estimator.update(current = 4, total = 10))
            .isEqualTo(DoubanSyncEtaEstimator.UNKNOWN_ETA_SECONDS)
    }

    @Test
    fun estimatesRemainingSecondsWithCeilingAfterFiveCompletedItems() {
        val clock = FakeTimeProvider(nowMs = 0L)
        val estimator = DoubanSyncEtaEstimator(clock)

        estimator.update(current = 1, total = 8)
        clock.nowMs = 1_500L
        estimator.update(current = 2, total = 8)
        clock.nowMs = 3_000L
        estimator.update(current = 3, total = 8)
        clock.nowMs = 4_500L
        estimator.update(current = 4, total = 8)
        clock.nowMs = 6_000L

        assertThat(estimator.update(current = 5, total = 8)).isEqualTo(5L)
    }

    @Test
    fun usesOnlyTheFixedRecentSampleWindow() {
        val clock = FakeTimeProvider(nowMs = 0L)
        val estimator = DoubanSyncEtaEstimator(clock)

        estimator.update(current = 0, total = 10)
        clock.nowMs = 10_000L
        estimator.update(current = 1, total = 10)
        clock.nowMs = 20_000L
        estimator.update(current = 2, total = 10)
        clock.nowMs = 30_000L
        estimator.update(current = 3, total = 10)
        clock.nowMs = 40_000L
        estimator.update(current = 4, total = 10)
        clock.nowMs = 41_000L
        estimator.update(current = 5, total = 10)
        clock.nowMs = 42_000L
        estimator.update(current = 6, total = 10)
        clock.nowMs = 43_000L
        estimator.update(current = 7, total = 10)
        clock.nowMs = 44_000L
        estimator.update(current = 8, total = 10)
        clock.nowMs = 45_000L

        assertThat(estimator.update(current = 9, total = 10)).isEqualTo(1L)
    }

    @Test
    fun ignoresProgressRollbackWithoutChangingTheWindow() {
        val clock = FakeTimeProvider(nowMs = 0L)
        val estimator = DoubanSyncEtaEstimator(clock)

        estimator.update(current = 0, total = 10)
        clock.nowMs = 1_000L
        estimator.update(current = 1, total = 10)
        clock.nowMs = 2_000L
        estimator.update(current = 2, total = 10)
        clock.nowMs = 3_000L
        estimator.update(current = 3, total = 10)
        clock.nowMs = 4_000L
        estimator.update(current = 4, total = 10)
        clock.nowMs = 5_000L
        estimator.update(current = 5, total = 10)
        clock.nowMs = 6_000L

        assertThat(estimator.update(current = 2, total = 10)).isEqualTo(5L)

        clock.nowMs = 10_000L
        assertThat(estimator.update(current = 6, total = 10)).isEqualTo(8L)
    }

    @Test
    fun ignoresNonMonotonicClockSample() {
        val clock = FakeTimeProvider(nowMs = 0L)
        val estimator = DoubanSyncEtaEstimator(clock)

        estimator.update(current = 0, total = 10)
        clock.nowMs = 1_000L
        estimator.update(current = 1, total = 10)
        clock.nowMs = 2_000L
        estimator.update(current = 2, total = 10)
        clock.nowMs = 3_000L
        estimator.update(current = 3, total = 10)
        clock.nowMs = 4_000L
        estimator.update(current = 4, total = 10)
        clock.nowMs = 5_000L
        estimator.update(current = 5, total = 10)
        clock.nowMs = 4_500L

        assertThat(estimator.update(current = 6, total = 10)).isEqualTo(5L)
    }

    @Test
    fun completedSyncReturnsZero() {
        val clock = FakeTimeProvider(nowMs = 0L)
        val estimator = DoubanSyncEtaEstimator(clock)

        estimator.update(current = 0, total = 10)

        assertThat(estimator.update(current = 10, total = 10, isComplete = true)).isEqualTo(0L)
    }

    @Test
    fun batchTrackerDoesNotUseCloudPrefetchTimeWhenProgressJumps() {
        val clock = FakeTimeProvider(nowMs = 0L)
        val tracker = DoubanBatchProgressTracker(total = 10, timeProvider = clock)

        tracker.update(0, com.tracktosearch.data.repository.DoubanSyncSubStage.PULLING_CLOUD.name)
        clock.nowMs = 60_000L

        // 并发任务可能一次回调多个已完成条目；预取等待不能被当成这段吞吐时间。
        val firstProcessing = tracker.update(5, "详情页")

        assertThat(firstProcessing.current).isEqualTo(5)
        assertThat(firstProcessing.etaSeconds)
            .isEqualTo(DoubanSyncEtaEstimator.UNKNOWN_ETA_SECONDS)
    }

    @Test
    fun batchTrackerKeepsSkippedItemsOutOfEtaDenominator() {
        val clock = FakeTimeProvider(nowMs = 0L)
        val tracker = DoubanBatchProgressTracker(total = 10, timeProvider = clock)

        tracker.update(3, "断点续传跳过")
        clock.nowMs = 60_000L
        val firstProcessing = tracker.update(8, "详情页")

        assertThat(firstProcessing.current).isEqualTo(8)
        assertThat(firstProcessing.etaSeconds)
            .isEqualTo(DoubanSyncEtaEstimator.UNKNOWN_ETA_SECONDS)
    }

    private class FakeTimeProvider(var nowMs: Long) : DoubanSyncTimeProvider {
        override fun nowMs(): Long = nowMs
    }
}
