package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.douban.DelayInfo
import com.tracktosearch.data.remote.douban.DelayType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DoubanSyncProgressPublisherTest {

    @Test
    fun lateBatchCallbackCannotOverwriteNewerProgressState() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })
        val callback = publisher.createBatchProgressCallback(
            tracker = DoubanBatchProgressTracker(total = 10),
            recentFailures = ArrayDeque<DoubanSyncFailure>()
        )

        callback(5, "Trakt 查询", 1, "较新的条目", null)
        callback(4, "详情页", 1, "迟到的条目", null)

        assertThat(progress.value.current).isEqualTo(5)
        assertThat(progress.value.subPhase).isEqualTo("Trakt 查询")
        assertThat(progress.value.currentTitle).isEqualTo("较新的条目")
        assertThat(progress.value.cacheHitCount).isEqualTo(1)
    }

    @Test
    fun skippedItemsKeepCloudPrefetchStageAndProgressBaseline() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { false })
        val callback = publisher.createBatchProgressCallback(
            tracker = DoubanBatchProgressTracker(total = 10),
            recentFailures = ArrayDeque<DoubanSyncFailure>()
        )

        callback(3, "断点续传跳过", 0, null, null)
        callback(0, DoubanSyncSubStage.PULLING_CLOUD.name, 0, null, null)

        assertThat(progress.value.current).isEqualTo(3)
        assertThat(progress.value.subStage).isEqualTo(DoubanSyncSubStage.PULLING_CLOUD)
        assertThat(progress.value.subPhase).isEqualTo(DoubanSyncSubStage.PULLING_CLOUD.name)
    }

    @Test
    fun finalProgressUsesCancellationStateReadInsidePublisher() {
        val cancelled = AtomicBoolean(false)
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                current = 7,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = cancelled::get)

        cancelled.set(true)
        publisher.publishFinal { current, wasCancelled ->
            current.copy(
                isRunning = false,
                isComplete = true,
                stage = if (wasCancelled) {
                    DoubanSyncStage.CANCELLING
                } else {
                    DoubanSyncStage.COMPLETED
                },
                phase = if (wasCancelled) "已取消" else "同步完成",
                isCancelling = current.isCancelling || wasCancelled
            )
        }

        assertThat(progress.value.current).isEqualTo(7)
        assertThat(progress.value.stage).isEqualTo(DoubanSyncStage.CANCELLING)
        assertThat(progress.value.phase).isEqualTo("已取消")
        assertThat(progress.value.isCancelling).isTrue()
    }

    @Test
    fun cancellationPreventsLateBatchAndDelayEventsFromRestoringRunningState() {
        val progress = MutableStateFlow(
            DoubanSyncProgress(
                isRunning = true,
                total = 10,
                stage = DoubanSyncStage.PARSING_DATA
            )
        )
        val publisher = DoubanSyncProgressPublisher(progress, isCancelled = { true })
        val callback = publisher.createBatchProgressCallback(
            tracker = DoubanBatchProgressTracker(total = 10),
            recentFailures = ArrayDeque<DoubanSyncFailure>()
        )

        publisher.publishCancelling()
        callback(5, "Trakt 查询", 0, "不应显示", null)
        publisher.publishDelay(
            DelayInfo(
                type = DelayType.DOUBAN_DETAIL_CRAWL,
                totalSeconds = 3,
                startMs = 1_000L
            )
        )

        assertThat(progress.value.isCancelling).isTrue()
        assertThat(progress.value.stage).isEqualTo(DoubanSyncStage.CANCELLING)
        assertThat(progress.value.phase).isEqualTo("正在取消...")
        assertThat(progress.value.current).isEqualTo(0)
        assertThat(progress.value.currentTitle).isNull()
        assertThat(progress.value.delayInfo).isNull()
    }
}
